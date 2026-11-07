package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.EmittedJavac;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An entity whose package holds {@code domain} before its trailing {@code .domain} segment gets
 * its infrastructure in the packages beside that segment, from the main and the test emitters
 * alike: the generated tests compile against the generated main tree.
 *
 * <p>The streaming action subscribes to an action-triggered event, so the generated stream test
 * names the event publisher from the derived event package too.
 *
 * <p>Both trees are generated with the base package auto-detected, so the application bootstrap
 * and the generated test support follow the same derivation as the per-entity types.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Infrastructure packages are derived from the trailing .domain segment only")
class DomainPackageDerivationE2ETest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "com.domainco.app.domain, com.domainco.app",
            "a.domain.b.domain,       a.domain.b"
    })
    @DisplayName("main and test trees agree on every derived package, and compile together")
    void mainAndTestTreesAgree(String domainPackage, String basePackage, @TempDir Path workspace)
            throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        Path generatedTests = workspace.resolve("src/test/generated/java");
        String sourcePath = domainPackage.replace('.', '/') + "/Widget.java";

        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null,
                Map.of(sourcePath, widget(domainPackage)));
        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, null);
        pipeline.runTests(metadataDir, generatedTests, null);

        Path base = Path.of(basePackage.replace('.', '/'));
        assertThat(generatedMain.resolve(base.resolve("Application.java"))).exists();
        assertThat(generatedMain.resolve(base.resolve("repository/WidgetRepository.java"))).exists();
        assertThat(generatedMain.resolve(base.resolve("service/WidgetService.java"))).exists();
        assertThat(generatedMain.resolve(base.resolve("handler/WidgetHandler.java"))).exists();
        assertThat(generatedMain.resolve(base.resolve("event/WidgetEventPublisher.java"))).exists();
        assertThat(generatedMain.resolve(base.resolve("saga/WidgetSagaFlow.java"))).exists();
        assertThat(generatedTests.resolve(base.resolve("repository/WidgetRepositoryTest.java"))).exists();
        assertThat(generatedTests.resolve(base.resolve("service/WidgetServiceTest.java"))).exists();
        assertThat(generatedTests.resolve(base.resolve("handler/WidgetHandlerTest.java"))).exists();
        assertThat(generatedTests.resolve(base.resolve("handler/WidgetHandlerTest.java")))
                .content().contains("WidgetEventPublisher");
        assertThat(generatedTests.resolve(base.resolve("testsupport/RecordingPersistence.java"))).exists();

        List<String> files = new ArrayList<>();
        for (Path root : List.of(generatedMain, generatedTests)) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted()
                        .forEach(files::add);
            }
        }
        EmittedJavac.Result result = EmittedJavac.compile(files, workspace.resolve("target/test-classes"),
                System.getProperty("java.class.path") + File.pathSeparator + entityClasses);
        assertThat(result.clean()).as("generated sources must compile without errors or warnings:%n%s",
                result.render()).isTrue();
    }

    private static String widget(String domainPackage) {
        return """
                package %s;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Saga;
                import eu.exeris.sdk.annotation.SagaStep;

                import java.util.UUID;

                @ExerisDomain(module = "catalog", path = "/widgets")
                @DomainEvent(name = "WidgetCreated", trigger = DomainEvent.Trigger.CREATE, topic = "widgets.created")
                @DomainEvent(name = "WidgetTracked", trigger = DomainEvent.Trigger.ACTION, action = "track",
                        topic = "widgets.tracked")
                @Saga(name = "WidgetSaga", timeout = "PT5M", maxRetries = 1)
                public class Widget {

                    private UUID id;

                    @Field(label = "Name", required = true, filterable = true)
                    private String name;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }

                    @SagaStep(order = 1, name = "reserve", service = "inventory", command = "Reserve")
                    public void reserve() {}

                    @Action(name = "archive", label = "Archive")
                    public void archive() {}

                    @Action(name = "track", label = "Track", streaming = true)
                    public void track() {}
                }
                """.formatted(domainPackage);
    }
}
