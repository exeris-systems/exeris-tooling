package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.EmittedJavac;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated tests of an entity pair joined by foreign keys compile against the ADR-058 contract
 * classpath and pass, the foreign-key cases among them (ADR-076 Amendment 1).
 *
 * <p>{@code Book} references {@code Author} and itself, so its repository and handler tests carry
 * the missing-reference cases and its own delete is refusable; {@code Author}'s carry the
 * still-referenced delete. The cases are run by name as well as with their classes, so a generator
 * that stopped emitting them fails here rather than passing with fewer tests.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("ADR-076 Amendment 1 — the generated foreign-key cases compile against the contract and pass")
class ForeignKeyGeneratedTestsE2ETest {

    private static final String BASE_PACKAGE = "com.library";
    private static final String BOOK_REPOSITORY_TEST = "com.library.repository.BookRepositoryTest";
    private static final String AUTHOR_REPOSITORY_TEST = "com.library.repository.AuthorRepositoryTest";
    private static final String BOOK_HANDLER_TEST = "com.library.handler.BookHandlerTest";
    private static final String AUTHOR_HANDLER_TEST = "com.library.handler.AuthorHandlerTest";

    @TempDir
    static Path workspace;

    private static Path entityClasses;
    private static Path compiled;

    @BeforeAll
    static void generateAndCompile() throws IOException {
        entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        Path generatedTests = workspace.resolve("src/test/generated/java");
        compiled = workspace.resolve("target/test-classes");

        ProcessorCompiler.compile(workspace.resolve("src"), entityClasses, null, sources());
        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, BASE_PACKAGE);
        pipeline.runTests(metadataDir, generatedTests, BASE_PACKAGE);

        List<String> files = new ArrayList<>();
        for (Path root : List.of(generatedMain, generatedTests)) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(files::add);
            }
        }
        EmittedJavac.Result result = EmittedJavac.compile(files, compiled, contractClasspath());
        assertThat(result.clean()).as("generated sources must compile without errors or warnings:%n%s",
                result.render()).isTrue();
    }

    @Test
    @DisplayName("the four test classes pass whole")
    void generatedTestsPass() throws Exception {
        TestExecutionSummary summary = run(
                DiscoverySelectors.selectClass(BOOK_REPOSITORY_TEST),
                DiscoverySelectors.selectClass(AUTHOR_REPOSITORY_TEST),
                DiscoverySelectors.selectClass(BOOK_HANDLER_TEST),
                DiscoverySelectors.selectClass(AUTHOR_HANDLER_TEST));

        assertThat(summary.getTestsFailedCount()).as("generated-test failures:%n%s", render(summary)).isZero();
        assertThat(summary.getTestsSucceededCount()).isPositive();
    }

    @Test
    @DisplayName("each foreign-key case is emitted where it applies, and passes")
    void foreignKeyCasesPass() throws Exception {
        TestExecutionSummary summary = run(
                DiscoverySelectors.selectMethod(BOOK_REPOSITORY_TEST, "saveRefusesARowNamingAMissingReference"),
                DiscoverySelectors.selectMethod(BOOK_REPOSITORY_TEST, "updateRefusesARowNamingAMissingReference"),
                DiscoverySelectors.selectMethod(BOOK_REPOSITORY_TEST, "saveLeavesAViolationOfAnotherConstraintAsItIs"),
                DiscoverySelectors.selectMethod(BOOK_REPOSITORY_TEST, "deleteByIdRefusesARowOtherRowsStillReference"),
                DiscoverySelectors.selectMethod(AUTHOR_REPOSITORY_TEST, "deleteByIdRefusesARowOtherRowsStillReference"),
                DiscoverySelectors.selectMethod(BOOK_HANDLER_TEST, "handleCreateRespondsUnprocessableForAMissingReference"),
                DiscoverySelectors.selectMethod(BOOK_HANDLER_TEST, "handleUpdateRespondsUnprocessableForAMissingReference"),
                DiscoverySelectors.selectMethod(BOOK_HANDLER_TEST,
                        "handleDeleteRespondsConflictWhenOtherRowsStillReferenceTheRow"),
                DiscoverySelectors.selectMethod(AUTHOR_HANDLER_TEST,
                        "handleDeleteRespondsConflictWhenOtherRowsStillReferenceTheRow"));

        assertThat(summary.getTestsFailedCount()).as("generated-test failures:%n%s", render(summary)).isZero();
        assertThat(summary.getTestsSucceededCount()).isEqualTo(9);
    }

    private static TestExecutionSummary run(DiscoverySelector... selectors) throws IOException {
        try (URLClassLoader appLoader = new URLClassLoader(
                new URL[]{compiled.toUri().toURL(), entityClasses.toUri().toURL()},
                ForeignKeyGeneratedTestsE2ETest.class.getClassLoader())) {
            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(appLoader);
            try {
                launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectors).build(), listener);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
            return listener.getSummary();
        }
    }

    private static String render(TestExecutionSummary summary) {
        StringWriter out = new StringWriter();
        summary.printFailuresTo(new PrintWriter(out), 20);
        return out.toString();
    }

    /** The kernel the generated main code binds, and the ADR-058 §2 test dependencies; nothing more. */
    private static String contractClasspath() {
        List<String> entries = new ArrayList<>(List.of(
                codeSourceOf("eu.exeris.kernel.spi.http.HttpExchange"),
                codeSourceOf("eu.exeris.kernel.core.bootstrap.KernelBootstrap"),
                codeSourceOf("org.junit.jupiter.api.Test"),
                codeSourceOf("org.assertj.core.api.Assertions"),
                codeSourceOf("org.apiguardian.api.API"),
                entityClasses.toString()));
        return String.join(File.pathSeparator, entries);
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

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/library/domain/Author.java",
                """
                package com.library.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "library", path = "/authors")
                public class Author {

                    private UUID id;

                    @Field(label = "Name")
                    private String name;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        sources.put("com/library/domain/Book.java",
                """
                package com.library.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;

                import java.util.UUID;

                @ExerisDomain(module = "library", path = "/books")
                public class Book {

                    private UUID id;

                    @Field(label = "Title", required = true)
                    private String title;

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
        return sources;
    }
}
