package eu.exeris.tooling.codegen.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import eu.exeris.tooling.codegen.java.dsl.EntitySchemaGenerator;
import eu.exeris.tooling.codegen.java.dsl.FormDslGenerator;
import eu.exeris.tooling.codegen.java.dsl.PageDslGenerator;
import eu.exeris.tooling.codegen.java.dsl.TableDslGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same metadata, same bytes, whatever the JVM's default locale (codegen-determinism policy, rule 4).
 *
 * <p><b>Why Turkish.</b> It is the locale where {@code String.toLowerCase()} without an argument
 * visibly breaks identifiers: {@code 'I'} lower-cases to dotless {@code 'ı'} (U+0131), so a default
 * table for {@code Invoice} would come out as {@code ınvoices}, and {@code itemId} as the column
 * {@code item_ıd}. Every such call in the emitters passes {@code Locale.ROOT}, and this test keeps it
 * that way: it runs the whole production pipeline — main tree, generated-test tree, and the
 * DSL emitters — once under {@link Locale#ROOT} and once under {@code tr-TR}, and requires the two
 * output trees to be identical file for file and byte for byte.
 *
 * <p>The fixture's names are chosen to put an upper-case {@code I} through every lower-casing site:
 * a default table and route ({@code Invoice}), an explicit override ({@code LINE_ITEMS}), a
 * snake-cased column ({@code itemId}), a relationship target, graph-sync table names, the OpenAPI
 * file name, and a {@code format} the form DSL switches on ({@code EMAIL}, which lower-cases to
 * {@code emaıl} in Turkish and misses its branch).
 *
 * <p>The default locale is JVM-global, so this sets and restores all three categories in
 * {@code finally}; the build runs tests single-threaded per fork.
 */
@DisplayName("Locale independence: tr-TR output == ROOT output")
class LocaleIndependenceTest {

    private static final Locale TURKISH = Locale.of("tr", "TR");

    @TempDir
    Path scratch;

    @Test
    @DisplayName("the pipeline and the DSL emitters produce byte-identical trees under ROOT and tr-TR")
    void turkishLocaleChangesNoByte() throws IOException {
        Path metadataDir = Files.createDirectories(scratch.resolve("metadata"));
        ObjectMapper mapper = CodegenPipeline.defaultMapper();
        for (DomainMetadata domain : fixture()) {
            mapper.writeValue(metadataDir.resolve("domain_" + domain.entityName() + ".json").toFile(), domain);
        }

        Map<String, byte[]> root = generateUnder(Locale.ROOT, metadataDir, scratch.resolve("root"));
        Map<String, byte[]> turkish = generateUnder(TURKISH, metadataDir, scratch.resolve("tr"));

        assertThat(turkish.keySet()).as("emitted paths").isEqualTo(root.keySet());
        for (Map.Entry<String, byte[]> file : root.entrySet()) {
            assertThat(new String(turkish.get(file.getKey()), java.nio.charset.StandardCharsets.UTF_8))
                    .as("%s under tr-TR", file.getKey())
                    .isEqualTo(new String(file.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        // Guard against a vacuous pass: the sites this test exists for really were exercised.
        assertThat(root.keySet()).anyMatch(p -> p.contains("create_invoices"));
        assertThat(root.keySet()).anyMatch(p -> p.contains("create_line_items"));
        assertThat(root.keySet()).anyMatch(p -> p.endsWith("invoice-api.yaml"));
        assertThat(root.keySet()).anyMatch(p -> p.endsWith("invoice.create-form.json"));
        assertThat(root.values().stream().map(b -> new String(b, java.nio.charset.StandardCharsets.UTF_8)))
                .as("the defaulted route")
                .anyMatch(s -> s.contains("\"/invoices\""));
        // No dotless i (U+0131) anywhere, in either run.
        assertThat(root.values().stream().map(b -> new String(b, java.nio.charset.StandardCharsets.UTF_8)))
                .noneMatch(s -> s.indexOf('\u0131') >= 0);
    }

    private static Map<String, byte[]> generateUnder(Locale locale, Path metadataDir, Path out) throws IOException {
        Locale saved = Locale.getDefault();
        Locale savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale savedFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(locale);
            CodegenPipeline pipeline = CodegenPipeline.createDefault();
            pipeline.run(metadataDir, out.resolve("main"), "com.shop");
            pipeline.runTests(metadataDir, out.resolve("test"), "com.shop");
            Path dsl = Files.createDirectories(out.resolve("dsl"));
            for (DomainMetadata domain : fixture()) {
                new EntitySchemaGenerator(domain).writeTo(dsl);
                new PageDslGenerator(domain).writeListPageTo(dsl);
                new PageDslGenerator(domain).writeDetailPageTo(dsl);
                new TableDslGenerator(domain).writeTo(dsl);
                new FormDslGenerator(domain).writeCreateFormTo(dsl);
                new FormDslGenerator(domain).writeEditFormTo(dsl);
            }
        } finally {
            Locale.setDefault(saved);
            Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
            Locale.setDefault(Locale.Category.FORMAT, savedFormat);
        }
        return readTree(out);
    }

    private static Map<String, byte[]> readTree(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return files;
    }

    private static List<DomainMetadata> fixture() {
        // No explicit paths: every route comes from the SDK's DomainMetadata.effectivePath().
        DomainMetadata invoice = DomainMetadata.builder("Invoice", "com.shop.domain")
                .module("billing")
                .dataScope(DataScope.TENANT)
                .audited(true)
                .versioned(true)
                .softDelete(true)
                .realTimeApi(true)
                .fields(List.of(
                        FieldMetadata.builder("id", "java.util.UUID").required(true).build(),
                        FieldMetadata.builder("itemId", "java.util.UUID").filterable(true).build(),
                        FieldMetadata.builder("issuerEmail", "String").format("EMAIL").build(),
                        FieldMetadata.builder("isPaid", "boolean").build(),
                        FieldMetadata.builder("lineIds", "List<java.util.UUID>").build(),
                        FieldMetadata.builder("tenantId", "java.util.UUID").build(),
                        FieldMetadata.builder("version", "long").build(),
                        FieldMetadata.builder("createdAt", "java.time.Instant").build(),
                        FieldMetadata.builder("updatedAt", "java.time.Instant").build()))
                .relationships(List.of(
                        RelationshipMetadata.builder("lineItem", "LineItem")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE)
                                .build()))
                .actions(List.of(ActionMetadata.builder("issueInvoice").methodName("issueInvoice").build()))
                .events(List.of(DomainEventMetadata.simple("InvoiceIssued")))
                .graphMetadata(GraphMetadata.simple("Invoice"))
                .sagaMetadata(SagaMetadata.simple("InvoiceIssuing"))
                .build();
        DomainMetadata lineItem = DomainMetadata.builder("LineItem", "com.shop.domain")
                .module("billing")
                .tableName("LINE_ITEMS")
                .fields(List.of(
                        FieldMetadata.builder("id", "java.util.UUID").required(true).build(),
                        FieldMetadata.builder("itemName", "String").required(true).build()))
                .build();
        return List.of(invoice, lineItem);
    }
}
