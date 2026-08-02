package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KernelTestSupportGenerator")
class KernelTestSupportGeneratorTest {

    @Test
    @DisplayName("emits RecordingHttpExchange once per app, under <basePackage>.testsupport")
    void emitsTheExchangeDouble() {
        GeneratedFile file = new KernelTestSupportGenerator().generate("com.example");

        assertThat(file.className()).isEqualTo("RecordingHttpExchange");
        assertThat(file.packageName()).isEqualTo("com.example.testsupport");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.TEST);
    }

    @Test
    @DisplayName("overrides every respond(...) overload, the interface defaults included")
    void overridesEveryRespondOverload() {
        // The defaults build an HttpResponse through the codec path, which needs an encoder bound
        // at runtime — a double that inherited them would drag kernel runtime state into a plain
        // handler unit test, which is the whole thing this type exists to avoid.
        String source = new KernelTestSupportGenerator().generate("com.example").content();

        assertThat(source)
                .contains("public void respond(HttpResponse response)")
                .contains("public void respond(HttpStatus status)")
                .contains("public void respond(HttpStatus status, Object body)")
                .contains("implements HttpExchange");
    }

    @Test
    @DisplayName("exposes what a handler test asserts on: status, body and path params")
    void exposesTheRecordedState() {
        String source = new KernelTestSupportGenerator().generate("com.example").content();

        assertThat(source)
                .contains("public HttpStatus status()")
                .contains("public Object body()")
                .contains("public RecordingHttpExchange withPathParam(String name, String value)")
                .contains("public static RecordingHttpExchange get(String path)")
                .contains("public static RecordingHttpExchange delete(String path)")
                // Bodyless POST/PUT: enough to reach the body-carrying routes' guard paths,
                // because those reject before anything reads the request body.
                .contains("public static RecordingHttpExchange post(String path)")
                .contains("public static RecordingHttpExchange put(String path)");
    }

    @Test
    @DisplayName("generateAll emits both shared doubles into the same support package")
    void emitsBothDoubles() {
        List<GeneratedFile> files = new KernelTestSupportGenerator().generateAll("com.example");

        assertThat(files).extracting(GeneratedFile::className)
                .containsExactly("RecordingHttpExchange", "RecordingPersistence", "RecordingFlow");
        assertThat(files).allSatisfy(f ->
                assertThat(f.packageName()).isEqualTo("com.example.testsupport"));
    }

    @Test
    @DisplayName("RecordingPersistence plays every persistence-SPI role a repository walks through")
    void persistenceDoubleImplementsTheWholeChain() {
        // The repository chains executor → connection → statement → result → cursor. One object
        // playing all five is what lets a test replay recorded binds back as a query result.
        String source = new KernelTestSupportGenerator().generatePersistence("com.example").content();

        assertThat(source)
                .contains("class RecordingPersistence implements TransactionalExecutor,")
                .contains("PersistenceConnection,")
                .contains("PersistenceStatement,")
                .contains("QueryResult,")
                .contains("RowCursor {")
                .contains("public PersistenceStatement prepare(String sql)")
                .contains("public Map<Integer, Object> recordedRow()");
    }

    @Test
    @DisplayName("the double records binds and stages rows, and closing does not erase either")
    void recordsAndStages() {
        String source = new KernelTestSupportGenerator().generatePersistence("com.example").content();

        assertThat(source)
                .contains("binds.put(index, value)")
                .contains("public Map<Integer, Object> row")
                .contains("public long rowsAffected = 1L")
                // close() is inert on purpose: the repository closes statements and results inside
                // try-with-resources, so a close that reset state would erase the recording.
                .contains("public void close() {\n    }");
    }

    @Test
    @DisplayName("unread cursor accessors throw rather than answer null")
    void unreadAccessorsThrow() {
        String source = new KernelTestSupportGenerator().generatePersistence("com.example").content();

        // No emitted repository reads a column as a segment; a double that returned null would
        // hide the day one starts.
        assertThat(source)
                .contains("public MemorySegment getSegment(int column)")
                .contains("throw new UnsupportedOperationException");
    }

    @Test
    @DisplayName("RecordingFlow plays every flow-SPI role a saga touches, and records both walks")
    void flowDoubleRecordsStepsAndTransitions() {
        String source = new KernelTestSupportGenerator().generateFlow("com.example").content();

        assertThat(source)
                .contains("class RecordingFlow implements FlowEngine,")
                .contains("FlowDefinitionBuilder,")
                .contains("FlowExecutionPlan,")
                .contains("FlowContext {")
                // Steps and transitions in one object is what lets a test compare the two walks.
                .contains("steps.add(name)")
                .contains("transitions.add(fromStep + \"->\" + toStep)")
                .contains("public int compiled");
    }

    @Test
    @DisplayName("the flow double's build() returns null — the recorded calls carry more than it would")
    void flowDoubleDoesNotFakeTheDefinition() {
        // Nothing under test reads the FlowDefinition back; what a saga test asserts is what the
        // builder was told, and the call lists hold that.
        assertThat(new KernelTestSupportGenerator().generateFlow("com.example").content())
                .contains("public FlowDefinition build() {\n        return null;");
    }

    @Test
    @DisplayName("the dependency contract holds: no mocking framework in either double")
    void bindsNoMockingFramework() {
        for (GeneratedFile file : new KernelTestSupportGenerator().generateAll("com.example")) {
            assertThat(file.content())
                    .doesNotContain("org.mockito")
                    .doesNotContain("org.easymock");
            assertThat(file.artifactType()).isEqualTo(ArtifactType.TEST);
        }
    }

    @Test
    @DisplayName("emission is deterministic — byte-identical across runs")
    void emissionIsDeterministic() {
        assertThat(new KernelTestSupportGenerator().generateAll("com.example"))
                .extracting(GeneratedFile::content)
                .isEqualTo(new KernelTestSupportGenerator().generateAll("com.example").stream()
                        .map(GeneratedFile::content).toList());
    }
}
