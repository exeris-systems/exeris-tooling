package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Pins the whole OpenAPI document for one representative entity, byte for byte.
 *
 * <p>Key order in the emitted YAML is not fixed by this repository: the maps it fills are
 * insertion-ordered, but the property order of the swagger model classes is whatever the
 * serializer derives. An in-JVM determinism check cannot see a change in that order, because both
 * emissions share one toolchain. A committed document compared on every JDK row of the CI matrix
 * can: a toolchain whose serializer orders keys differently fails here, on that row.
 *
 * <p>The fixture covers every part of the document the generator writes: info and servers, tags,
 * the collection, item and action paths, the list operation's {@code page} / {@code size} /
 * {@code sort} and filter parameters, the per-operation error statuses including {@code 409} for a
 * versioned entity, an action request body, and the entity, create, update and page schemas with
 * constraints, a format, an enum-typed field and a server-owned tenant column.
 *
 * <p>To regenerate after an intended change to the generator's output, run
 * {@code mvn -pl exeris-codegen-java -am test -Dtest=KernelOpenApiGoldenDocumentTest
 * -Dexeris.golden.update=true -Dsurefire.failIfNoSpecifiedTests=false} from the repository root,
 * then review the diff of {@value #GOLDEN_SOURCE} like any other change. The property is refused
 * when {@code CI} is set, so a CI run can only compare.
 */
@DisplayName("KernelOpenApiGenerator golden document")
class KernelOpenApiGoldenDocumentTest {

    private static final String GOLDEN_RESOURCE = "/openapi/order-api.golden.yaml";
    private static final String GOLDEN_SOURCE = "src/test/resources/openapi/order-api.golden.yaml";
    private static final String UPDATE_PROPERTY = "exeris.golden.update";
    private static final int CONTEXT_LINES = 3;

    @Test
    @DisplayName("the emitted document is byte-identical to the committed golden")
    void emittedDocumentMatchesGolden() throws IOException {
        GeneratedFile file = new KernelOpenApiGenerator().generate(order());
        String actual = file.content();

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            rewriteGolden(actual);
            return;
        }

        String expected = readGolden();
        if (!expected.equals(actual)) {
            fail(mismatchMessage(expected, actual));
        }
        assertThat(file.relativePath()).isEqualTo("openapi/order-api.yaml");
    }

    private static DomainMetadata order() {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .description("Customer order")
                .versioned(true)
                .dataScope(DataScope.TENANT)
                .fields(List.of(
                        FieldMetadata.builder("id", "java.util.UUID").readOnly(true).build(),
                        FieldMetadata.builder("orderNumber", "java.lang.String")
                                .required(true).minLength(3).maxLength(32).pattern("^ORD-[0-9]+$")
                                .description("Human-facing order number")
                                .sortable(true).filterable(true).build(),
                        FieldMetadata.builder("status", "com.example.domain.OrderStatus")
                                .enumType("com.example.domain.OrderStatus")
                                .sortable(true).filterable(true).build(),
                        FieldMetadata.builder("amount", "java.math.BigDecimal")
                                .min(0L).max(1_000_000L).sortable(true).build(),
                        FieldMetadata.builder("urgent", "boolean").filterable(true).build(),
                        FieldMetadata.builder("dueOn", "java.time.LocalDate")
                                .sortable(true).filterable(true).build(),
                        FieldMetadata.builder("placedAt", "java.time.Instant").sortable(true).build(),
                        FieldMetadata.builder("website", "java.lang.String").dataType("url").build(),
                        FieldMetadata.builder("tenantId", "java.util.UUID").build()))
                .actions(List.of(
                        ActionMetadata.builder("approve")
                                .description("Approve the order")
                                .addParam(ActionParamMetadata.builder("note", "java.lang.String")
                                        .required(true).maxLength(200).build())
                                .build(),
                        ActionMetadata.builder("cancel").build()))
                .build();
    }

    private static String readGolden() throws IOException {
        try (InputStream in = KernelOpenApiGoldenDocumentTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
            if (in == null) {
                throw new AssertionError("Golden document " + GOLDEN_RESOURCE + " is not on the test classpath. "
                        + "Create it with -D" + UPDATE_PROPERTY + "=true (see this class's Javadoc).");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void rewriteGolden(String actual) throws IOException {
        if (System.getenv("CI") != null) {
            fail("-D" + UPDATE_PROPERTY + "=true is refused when CI is set: a CI run compares, "
                    + "it never rewrites the golden document.");
        }
        Path golden = Path.of(GOLDEN_SOURCE);
        Files.createDirectories(golden.getParent());
        Files.writeString(golden, actual, StandardCharsets.UTF_8);
        fail("Rewrote " + golden.toAbsolutePath() + " because -D" + UPDATE_PROPERTY
                + "=true was set. Review its diff, then run again without the property.");
    }

    /**
     * Names the first line that differs, with the lines around it from both documents, and the
     * JDK that produced the actual one — a key-order change is a toolchain fact, so the row that
     * failed is part of the diagnosis.
     */
    private static String mismatchMessage(String expected, String actual) {
        List<String> want = expected.lines().toList();
        List<String> got = actual.lines().toList();
        int first = 0;
        while (first < want.size() && first < got.size() && want.get(first).equals(got.get(first))) {
            first++;
        }
        StringBuilder message = new StringBuilder()
                .append("Emitted OpenAPI document differs from ").append(GOLDEN_SOURCE)
                .append(" (java.version=").append(System.getProperty("java.version"))
                .append(", java.vendor=").append(System.getProperty("java.vendor")).append(").\n")
                .append("First difference at line ").append(first + 1)
                .append(" (golden ").append(want.size()).append(" lines, emitted ")
                .append(got.size()).append(" lines).\n");
        if (first == want.size() && first == got.size()) {
            message.append("Every line matches; the documents differ in line endings or the final newline.\n");
        }
        appendWindow(message, "golden", want, first);
        appendWindow(message, "emitted", got, first);
        message.append("If the change is intended, regenerate with -D").append(UPDATE_PROPERTY)
                .append("=true (see this class's Javadoc). If only the key order moved, the "
                        + "toolchain changed the serializer's order and the writer needs an explicit one.");
        return message.toString();
    }

    private static void appendWindow(StringBuilder message, String label, List<String> lines, int at) {
        message.append("--- ").append(label).append(" ---\n");
        int from = Math.max(0, at - CONTEXT_LINES);
        int to = Math.min(lines.size(), at + CONTEXT_LINES + 1);
        for (int i = from; i < to; i++) {
            message.append(i == at ? "> " : "  ").append(String.format(Locale.ROOT, "%4d | ", i + 1))
                    .append(lines.get(i)).append('\n');
        }
        if (at >= lines.size()) {
            message.append(">      <end of document>\n");
        }
    }
}
