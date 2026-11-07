package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.EmittedJavac;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An {@code @ActionParam(defaultValue)} on a parameter bound from the request body, through the
 * processor and the pipeline: the generated request record is compiled and decoded by a JSON
 * mapper, and a value the body omits or sends as {@code null} takes the declared default, while an
 * explicit value, zero included, is kept.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("action body parameter: defaultValue applies to an omitted or null value")
class ActionParamBodyDefaultE2ETest {

    private static final String REQUEST_RECORD = "com.billing.handler.InvoiceHandler$IssueRequest";
    private static final String REVISE_RECORD = "com.billing.handler.InvoiceHandler$ReviseRequest";
    private static final String DUE_IN_DAYS = "dueInDays";

    @Test
    @DisplayName("a body parameter's defaultValue is applied by the decoded request record")
    void bodyDefaultIsAppliedOnDecode(@TempDir Path workspace) throws Exception {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        Path generatedTests = workspace.resolve("src/test/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, invoiceCorpus());

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, "com.billing");
        pipeline.runTests(metadataDir, generatedTests, "com.billing");
        Path regenerated = workspace.resolve("regenerated");
        CodegenPipeline.createDefault().run(metadataDir, regenerated, "com.billing");
        String handlerPath = "com/billing/handler/InvoiceHandler.java";
        assertThat(Files.readAllBytes(regenerated.resolve(handlerPath)))
                .as("a second generation of the handler is byte-identical")
                .isEqualTo(Files.readAllBytes(generatedMain.resolve(handlerPath)));
        assertThat(Files.readString(generatedMain.resolve(handlerPath)))
                .contains("record IssueRequest(Integer dueInDays, String note, int copies)")
                .contains("dueInDays = Integer.valueOf(\"30\");")
                .contains("note = \"net\";")
                .contains("record ReviseRequest(Boolean rush)")
                .contains("throw new IllegalArgumentException(\"default value is not a boolean: soon\");");

        List<String> files = new ArrayList<>();
        for (Path root : List.of(generatedMain, generatedTests)) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().forEach(files::add);
            }
        }
        Path appClasses = workspace.resolve("target/app-classes");
        EmittedJavac.Result result = EmittedJavac.compile(files, appClasses,
                System.getProperty("java.class.path") + File.pathSeparator + entityClasses);
        assertThat(result.clean())
                .as("javac output:%n%s", result.render())
                .isTrue();

        try (URLClassLoader loader = new URLClassLoader(new URL[] {
                appClasses.toUri().toURL(), entityClasses.toUri().toURL()}, getClass().getClassLoader())) {
            assertDecodedDefaults(loader);
        }
    }

    private static void assertDecodedDefaults(ClassLoader loader) throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        Class<?> issue = loader.loadClass(REQUEST_RECORD);

        Object omitted = mapper.readValue("{\"copies\":1}", issue);
        assertThat(component(omitted, DUE_IN_DAYS)).isEqualTo(30);
        assertThat(component(omitted, "note")).isEqualTo("net");

        Object sentNull = mapper.readValue("{\"dueInDays\":null,\"note\":null,\"copies\":1}", issue);
        assertThat(component(sentNull, DUE_IN_DAYS)).isEqualTo(30);
        assertThat(component(sentNull, "note")).isEqualTo("net");

        Object sentZero = mapper.readValue("{\"dueInDays\":0,\"note\":\"\",\"copies\":1}", issue);
        assertThat(component(sentZero, DUE_IN_DAYS)).isEqualTo(0);
        assertThat(component(sentZero, "note")).isEqualTo("");

        Object sent = mapper.readValue("{\"dueInDays\":14,\"note\":\"eom\",\"copies\":2}", issue);
        assertThat(component(sent, DUE_IN_DAYS)).isEqualTo(14);
        assertThat(component(sent, "note")).isEqualTo("eom");

        Class<?> revise = loader.loadClass(REVISE_RECORD);
        assertThat(component(mapper.readValue("{\"rush\":false}", revise), "rush")).isEqualTo(false);
        assertThatThrownBy(() -> mapper.readValue("{}", revise))
                .isInstanceOf(JacksonException.class)
                .hasRootCauseMessage("default value is not a boolean: soon");
    }

    private static Object component(Object record, String name) throws Exception {
        return record.getClass().getMethod(name).invoke(record);
    }

    private static Map<String, String> invoiceCorpus() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/billing/domain/Invoice.java",
                """
                package com.billing.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.ActionParam;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "billing", path = "/invoices")
                public class Invoice {

                    private UUID id;

                    @Field(label = "Due in days")
                    private Integer dueInDays;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public Integer getDueInDays() { return dueInDays; }
                    public void setDueInDays(Integer dueInDays) { this.dueInDays = dueInDays; }

                    @Action(name = "issue", label = "Issue")
                    public void issue(
                            @ActionParam(label = "Due in days", required = false, defaultValue = "30") int dueInDays,
                            @ActionParam(label = "Note", required = false, defaultValue = "net") String note,
                            @ActionParam(label = "Copies") int copies) {
                        this.dueInDays = dueInDays;
                    }

                    @Action(name = "revise", label = "Revise")
                    public void revise(
                            @ActionParam(label = "Rush", required = false, defaultValue = "soon") boolean rush) {
                    }
                }
                """);
        return sources;
    }
}
