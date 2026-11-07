package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.EmittedJavac;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.kernel.community.testkit.FixtureBootLock;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADR-058 Amendment 1 gate: the generated integration tests are compiled <b>and executed</b>
 * against the generated application they boot.
 *
 * <pre>
 *   @ExerisDomain source
 *        → javac + ExerisDomainProcessor        (real metadata)
 *        → CodegenPipeline.run                  (the application)
 *        → CodegenPipeline.runIntegrationTests  (the *IT classes and their support)
 *        → javac over both trees                (JUnit 5, AssertJ, the kernel and nothing else)
 *        → JUnit Platform launcher              (they boot the application on H2 and must pass)
 * </pre>
 *
 * <p>The trees compile against a named classpath, as {@code GeneratedTestsE2ETest}'s do, so an
 * emitter that starts importing H2 or a kernel testkit fails here. They run with the test
 * classpath, which carries the kernel's community driver and H2, as a consumer's Failsafe run
 * does. The launcher runs under the testkit's boot lock, since the support class hands its
 * configuration to the kernel as JVM-wide system properties, and the application is stopped
 * before the lock is released.
 *
 * <p>The fixture holds one entity per kind of case: a plain one with length and numeric bounds, a
 * versioned, soft-deleted and audited one, a pair joined by a required {@code MANY_TO_ONE} and an
 * optional self reference, one whose routes and an action demand scopes, one that demands an
 * identity only, and a public one. A second build with a {@code TENANT} entity gets no tree.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("ADR-058 Amendment 1 — the generated integration tests boot the generated application and pass")
class GeneratedIntegrationTestsE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.itapp";
    private static final String DOMAIN = "eu/exeris/e2e/itapp/domain/";

    @TempDir
    static Path workspace;

    private static Path entityClasses;
    private static Path generatedMain;
    private static Path generatedIt;
    private static Path compiled;
    private static CodegenPipeline.IntegrationTestRun run;

    @BeforeAll
    static void generateAndCompile() throws IOException {
        entityClasses = workspace.resolve("target/classes");
        generatedMain = workspace.resolve("src/main/generated/java");
        generatedIt = workspace.resolve("src/test/generated-it/java");
        compiled = workspace.resolve("target/test-classes");

        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, sources());
        Path metadata = entityClasses.resolve("exeris-metadata");
        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        pipeline.run(metadata, generatedMain, BASE_PACKAGE);
        run = pipeline.runIntegrationTests(metadata, generatedIt, BASE_PACKAGE);

        List<String> files = new ArrayList<>(javaSourcesUnder(generatedMain));
        files.addAll(javaSourcesUnder(generatedIt));
        EmittedJavac.Result result = EmittedJavac.compile(files, compiled, contractClasspath());
        assertThat(result.clean()).as("the generated trees must compile without errors or warnings:%n%s",
                result.render()).isTrue();
    }

    @Test
    @DisplayName("the integration tests land in their own root, with their own manifest")
    void testsLandInTheirOwnRoot() {
        assertThat(run.withheld()).isFalse();
        assertThat(generatedIt.resolve(".exeris-codegen-manifest")).exists();
        assertThat(generatedIt.resolve("eu/exeris/e2e/itapp/testsupport/ApplicationUnderTest.java")).exists();
        assertThat(generatedIt.resolve("eu/exeris/e2e/itapp/testsupport/IntegrationHttp.java")).exists();
        assertThat(generatedIt.resolve("eu/exeris/e2e/itapp/testsupport/IntegrationTokens.java")).exists();
        assertThat(generatedIt.resolve("eu/exeris/e2e/itapp/handler/MemoCrudIT.java")).exists();
        assertThat(generatedIt.resolve("eu/exeris/e2e/itapp/GeneratedRoutePolicyIT.java")).exists();
        assertThat(generatedMain.resolve("eu/exeris/e2e/itapp/handler/MemoCrudIT.java")).doesNotExist();
    }

    @Test
    @DisplayName("the integration tests pass against the booted application, every kind of case among them")
    void integrationTestsPass() throws Exception {
        Map<String, String> outcomes = runTheIntegrationTests();

        assertThat(outcomes).as("every case's outcome").allSatisfy((name, outcome) ->
                assertThat(outcome).as(name).isEqualTo("SUCCESSFUL"));
        // 34 CRUD cases (Author 4, Book 8, Draft 3, Ledger 5, Memo 8, Note 3, Notice 3) and 9 route
        // policy cases (Note's routes 3, its action 3, Draft's routes 2, Notice's 1).
        assertThat(outcomes).hasSize(43);
        assertThat(outcomes.keySet()).contains(
                "MemoCrudIT.anAbsentRowIsNotFound",
                "MemoCrudIT.aMalformedKeyIsBadRequest",
                "MemoCrudIT.aRowRoundTripsThroughEveryRoute",
                "MemoCrudIT.titleOnMinLengthBound",
                "MemoCrudIT.titleOnMaxLengthBound",
                "MemoCrudIT.titleLeftOutIsBadRequest",
                "MemoCrudIT.priorityOnMinBound",
                "MemoCrudIT.priorityOnMaxBound",
                "LedgerCrudIT.aRowRoundTripsThroughEveryRoute",
                "LedgerCrudIT.anUpdateCarryingAReplacedVersionIsConflict",
                "BookCrudIT.aRowRoundTripsThroughEveryRoute",
                "BookCrudIT.authorIdNamingAnAbsentRowIsUnprocessable",
                "BookCrudIT.authorIdTargetStillReferencedCannotBeDeleted",
                "BookCrudIT.sequelOfIdNamingAnAbsentRowIsUnprocessable",
                "BookCrudIT.authorIdLeftOutIsBadRequest",
                "NoteCrudIT.aRowRoundTripsThroughEveryRoute",
                "GeneratedRoutePolicyIT.noteRoutesRefuseAnAnonymousCaller",
                "GeneratedRoutePolicyIT.noteRoutesRefuseACallerWithoutTheScope",
                "GeneratedRoutePolicyIT.noteRoutesAdmitEachDeclaredScope",
                "GeneratedRoutePolicyIT.noteArchiveActionRefusesAnAnonymousCaller",
                "GeneratedRoutePolicyIT.noteArchiveActionRefuseACallerWithoutTheScope",
                "GeneratedRoutePolicyIT.noteArchiveActionAdmitEachDeclaredScope",
                "GeneratedRoutePolicyIT.draftRoutesRefuseAnAnonymousCaller",
                "GeneratedRoutePolicyIT.draftRoutesAdmitAnyVerifiedCaller",
                "GeneratedRoutePolicyIT.noticeRoutesAdmitAnAnonymousCaller");
    }

    @Test
    @DisplayName("the same metadata gives the same integration-test tree, byte for byte")
    void emissionIsDeterministic() throws IOException {
        Path again = workspace.resolve("again");
        CodegenPipeline.createDefault().runIntegrationTests(entityClasses.resolve("exeris-metadata"), again,
                BASE_PACKAGE);
        Map<String, String> first = contents(generatedIt);
        Map<String, String> second = contents(again);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("a build with a TENANT entity gets no integration-test tree and is told why (EXT-GEN-3105)")
    void tenantBuildIsWithheld() throws IOException {
        Path tenantWorkspace = workspace.resolve("tenant");
        Path classes = tenantWorkspace.resolve("target/classes");
        ProcessorCompiler.compile(tenantWorkspace.resolve("src/main/java"), classes, null, Map.of(
                "eu/exeris/e2e/ittenant/domain/Invoice.java", """
                        package eu.exeris.e2e.ittenant.domain;

                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.Field;

                        import java.util.UUID;

                        @ExerisDomain(module = "billing", path = "/invoices", tenantScoped = true)
                        public class Invoice {
                            private UUID id;
                            private UUID tenantId;
                            @Field(label = "Number")
                            private String number;
                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }
                            public UUID getTenantId() { return tenantId; }
                            public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
                            public String getNumber() { return number; }
                            public void setNumber(String number) { this.number = number; }
                        }
                        """));
        Path itRoot = tenantWorkspace.resolve("src/test/generated-it/java");

        CodegenPipeline.IntegrationTestRun withheld = CodegenPipeline.createDefault()
                .runIntegrationTests(classes.resolve("exeris-metadata"), itRoot, "eu.exeris.e2e.ittenant");

        assertThat(withheld.withheld()).isTrue();
        assertThat(withheld.filesWritten()).isZero();
        assertThat(withheld.withheldReason()).contains("eu.exeris.e2e.ittenant.domain.Invoice", "TENANT");
        assertThat(itRoot).doesNotExist();
    }

    // ------------------------------------------------------------------ running

    /** Runs every emitted {@code *IT} class and returns each case's outcome by class and method. */
    private static Map<String, String> runTheIntegrationTests() throws Exception {
        Map<String, String> outcomes = new TreeMap<>();
        AtomicReference<TestExecutionSummary> summary = new AtomicReference<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{
                compiled.toUri().toURL(), entityClasses.toUri().toURL(), generatedMain.toUri().toURL()},
                GeneratedIntegrationTestsE2ETest.class.getClassLoader())) {
            LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request();
            for (String className : integrationTestClasses()) {
                request.selectors(DiscoverySelectors.selectClass(Class.forName(className, true, loader)));
            }
            LauncherDiscoveryRequest discovery = request.build();
            AtomicReference<Exception> failure = new AtomicReference<>();
            FixtureBootLock.bootExclusively(() -> {
                ClassLoader previous = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(loader);
                try {
                    Launcher launcher = LauncherFactory.create();
                    SummaryGeneratingListener listener = new SummaryGeneratingListener();
                    launcher.execute(discovery, listener, outcomeRecorder(outcomes));
                    summary.set(listener.getSummary());
                    loader.loadClass(BASE_PACKAGE + ".testsupport.ApplicationUnderTest")
                            .getMethod("stop").invoke(null);
                } catch (ReflectiveOperationException e) {
                    failure.set(e);
                } finally {
                    Thread.currentThread().setContextClassLoader(previous);
                }
            });
            if (failure.get() != null) {
                throw failure.get();
            }
        }
        assertThat(summary.get().getTestsFailedCount()).as("integration-test failures:%n%s",
                render(summary.get())).isZero();
        assertThat(summary.get().getTestsSucceededCount()).isEqualTo(outcomes.size()).isPositive();
        return outcomes;
    }

    private static TestExecutionListener outcomeRecorder(Map<String, String> outcomes) {
        return new TestExecutionListener() {
            @Override
            public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                if (identifier.getSource().orElse(null) instanceof MethodSource method) {
                    String className = method.getClassName();
                    outcomes.put(className.substring(className.lastIndexOf('.') + 1) + "." + method.getMethodName(),
                            result.getStatus().name());
                }
            }
        };
    }

    private static List<String> integrationTestClasses() throws IOException {
        try (Stream<Path> tree = Files.walk(generatedIt)) {
            return tree.filter(p -> p.getFileName().toString().endsWith("IT.java"))
                    .map(p -> generatedIt.relativize(p).toString().replace(File.separatorChar, '.')
                            .replaceAll("\\.java$", ""))
                    .sorted()
                    .toList();
        }
    }

    private static String render(TestExecutionSummary summary) {
        StringWriter out = new StringWriter();
        summary.printFailuresTo(new PrintWriter(out), 20);
        return out.toString();
    }

    // ------------------------------------------------------------------ compiling

    /**
     * What the generated trees may compile against: the kernel the main code targets, the two
     * libraries ADR-058 lets a generated test import, and the entities. Nothing else — H2 is a
     * runtime need of the integration tests and must not be importable.
     */
    private static String contractClasspath() {
        return String.join(File.pathSeparator, List.of(
                codeSourceOf("eu.exeris.kernel.spi.http.HttpExchange"),
                codeSourceOf("eu.exeris.kernel.core.bootstrap.KernelBootstrap"),
                codeSourceOf("org.junit.jupiter.api.Test"),
                codeSourceOf("org.assertj.core.api.Assertions"),
                codeSourceOf("org.apiguardian.api.API"),
                entityClasses.toString()));
    }

    private static String codeSourceOf(String className) {
        try {
            CodeSource source = Class.forName(className).getProtectionDomain().getCodeSource();
            assertThat(source).as("no code source for contract anchor %s", className).isNotNull();
            return Path.of(source.getLocation().toURI()).toString();
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new IllegalStateException("contract-classpath anchor is missing: " + className, e);
        }
    }

    private static List<String> javaSourcesUnder(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().toList();
        }
    }

    private static Map<String, String> contents(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path file : tree.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(file).toString(), Files.readString(file));
            }
        }
        return files;
    }

    // ------------------------------------------------------------------ fixture

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(DOMAIN + "Memo.java", """
                package eu.exeris.e2e.itapp.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Validation;

                import java.util.UUID;

                @ExerisDomain(module = "memos", path = "/memos")
                public class Memo {
                    private UUID id;
                    @Field(label = "Title", required = true)
                    @Validation(minLength = 2, maxLength = 40)
                    private String title;
                    @Field(label = "Priority")
                    @Validation(min = 1, max = 9)
                    private int priority;
                    @Field(label = "Done")
                    private boolean done;
                    @Field(label = "Code", unique = true)
                    private String code;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public int getPriority() { return priority; }
                    public void setPriority(int priority) { this.priority = priority; }
                    public boolean isDone() { return done; }
                    public void setDone(boolean done) { this.done = done; }
                    public String getCode() { return code; }
                    public void setCode(String code) { this.code = code; }
                }
                """);
        sources.put(DOMAIN + "Ledger.java", """
                package eu.exeris.e2e.itapp.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.time.Instant;
                import java.util.UUID;

                @ExerisDomain(module = "ledgers", path = "/ledgers", versioned = true, softDelete = true,
                        audited = true)
                public class Ledger {
                    private UUID id;
                    @Field(label = "Name", required = true)
                    private String name;
                    private Instant createdAt;
                    private Instant updatedAt;
                    private boolean deleted;
                    private Integer version;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public Instant getCreatedAt() { return createdAt; }
                    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
                    public Instant getUpdatedAt() { return updatedAt; }
                    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
                    public boolean isDeleted() { return deleted; }
                    public void setDeleted(boolean deleted) { this.deleted = deleted; }
                    public Integer getVersion() { return version; }
                    public void setVersion(Integer version) { this.version = version; }
                }
                """);
        sources.put(DOMAIN + "Author.java", """
                package eu.exeris.e2e.itapp.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "library", path = "/authors")
                public class Author {
                    private UUID id;
                    @Field(label = "Name", required = true)
                    private String name;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        sources.put(DOMAIN + "Book.java", """
                package eu.exeris.e2e.itapp.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;

                import java.util.UUID;

                @ExerisDomain(module = "library", path = "/books")
                public class Book {
                    private UUID id;
                    @Field(label = "Title", required = true)
                    private String title;
                    @Field(label = "Author", required = true)
                    @Relationship(targetEntity = Author.class, displayField = "name")
                    private UUID authorId;
                    @Relationship(targetEntity = Book.class, displayField = "title")
                    private UUID sequelOfId;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public UUID getAuthorId() { return authorId; }
                    public void setAuthorId(UUID authorId) { this.authorId = authorId; }
                    public UUID getSequelOfId() { return sequelOfId; }
                    public void setSequelOfId(UUID sequelOfId) { this.sequelOfId = sequelOfId; }
                }
                """);
        sources.put(DOMAIN + "Note.java", """
                package eu.exeris.e2e.itapp.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "notes", path = "/notes", permissions = {"note:read", "note:write"})
                public class Note {
                    private UUID id;
                    @Field(label = "Body")
                    private String body;
                    @Field(label = "Archived")
                    private boolean archived;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getBody() { return body; }
                    public void setBody(String body) { this.body = body; }
                    public boolean isArchived() { return archived; }
                    public void setArchived(boolean archived) { this.archived = archived; }

                    @Action(name = "archive", label = "Archive", permissions = {"note:archive"})
                    public void archive() {
                        this.archived = true;
                    }
                }
                """);
        sources.put(DOMAIN + "Draft.java", """
                package eu.exeris.e2e.itapp.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.RouteAccess;

                import java.util.UUID;

                @ExerisDomain(module = "drafts", path = "/drafts")
                @RouteAccess(RouteAccess.Level.AUTHENTICATED)
                public class Draft {
                    private UUID id;
                    @Field(label = "Text")
                    private String text;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getText() { return text; }
                    public void setText(String text) { this.text = text; }
                }
                """);
        sources.put(DOMAIN + "Notice.java", """
                package eu.exeris.e2e.itapp.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.RouteAccess;

                import java.util.UUID;

                @ExerisDomain(module = "notices", path = "/notices")
                @RouteAccess(RouteAccess.Level.PUBLIC)
                public class Notice {
                    private UUID id;
                    @Field(label = "Headline")
                    private String headline;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getHeadline() { return headline; }
                    public void setHeadline(String headline) { this.headline = headline; }
                }
                """);
        return sources;
    }
}
