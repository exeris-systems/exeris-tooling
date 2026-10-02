package eu.exeris.tooling.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link DiagnosticId} registry is a contract with every tool that matches on an identifier,
 * {@code exeris-ai-bridge}'s {@code build:explain_diagnostic} first among them. These tests pin
 * each identifier's value, its format, its uniqueness and its presence in
 * {@code docs/diagnostics.md}, so renumbering, reusing or forgetting to document one fails here.
 */
@DisplayName("DiagnosticId — stable diagnostic identifier registry")
class DiagnosticIdTest {

    /** The regular expression ADR-095 and {@code docs/diagnostics.md} give consumers. */
    private static final Pattern CONSUMER = Pattern.compile("\\[Exeris\\] (EXT-[A-Z]+-\\d{4}): ");

    private static final Map<String, Character> AREA_BLOCK = Map.of("PROC", '1', "PLUG", '2', "GEN", '3');

    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^\\|\\s*`(EXT-[A-Z]+-\\d{4})`\\s*\\|");

    private static final String RETIRED_HEADING = "## Retired identifiers";

    private static final Pattern FORMAT = Pattern.compile("EXT-(PROC|PLUG|GEN)-\\d{4}");

    /**
     * Every identifier, pinned by constant. Adding a constant means adding it here; changing a
     * value fails the test, which is the point — a published identifier does not move.
     */
    private static final Map<DiagnosticId, String> PINNED = pinned();

    private static Map<DiagnosticId, String> pinned() {
        Map<DiagnosticId, String> m = new LinkedHashMap<>();
        m.put(DiagnosticId.ANNOTATION_ON_WRONG_ELEMENT, "EXT-PROC-1001");
        m.put(DiagnosticId.PROCESSING_FAILURE, "EXT-PROC-1002");
        m.put(DiagnosticId.DATA_SCOPE_CONTRADICTS_TENANT_SCOPED, "EXT-PROC-1003");
        m.put(DiagnosticId.PUBLIC_ROUTE_WITH_PERMISSIONS, "EXT-PROC-1004");
        m.put(DiagnosticId.ACTION_PERMISSIONS_ON_INHERITED_PUBLIC_ROUTE, "EXT-PROC-1005");
        m.put(DiagnosticId.UNIVERSE_WITHOUT_OWNER_FIELD, "EXT-PROC-1006");
        m.put(DiagnosticId.UNIVERSE_WITHOUT_SHARED_SCOPE, "EXT-PROC-1007");
        m.put(DiagnosticId.SHARED_SCOPE_WRONG_TYPE, "EXT-PROC-1008");
        m.put(DiagnosticId.SHARED_SCOPE_ON_OWNER_FIELD, "EXT-PROC-1009");
        m.put(DiagnosticId.SHARED_SCOPE_REQUIRED, "EXT-PROC-1010");
        m.put(DiagnosticId.SYSTEM_FIELD_ROLE_REPEATED, "EXT-PROC-1011");
        m.put(DiagnosticId.SYSTEM_FIELD_ROLE_CONFLICTS_WITH_OVERRIDE, "EXT-PROC-1012");
        m.put(DiagnosticId.GRAPH_EDGE_REPEATED_ON_FIELD, "EXT-PROC-1013");
        m.put(DiagnosticId.TENANT_SCOPED_DEPRECATED, "EXT-PROC-1101");
        m.put(DiagnosticId.VALIDATION_ATTRIBUTE_DEPRECATED, "EXT-PROC-1102");
        m.put(DiagnosticId.VALIDATE_ON_UNRECOGNISED, "EXT-PROC-1103");
        m.put(DiagnosticId.DEFAULT_TABLE_NAME_CHANGED, "EXT-PROC-1104");
        m.put(DiagnosticId.SHARED_SCOPE_OUTSIDE_UNIVERSE, "EXT-PROC-1105");
        m.put(DiagnosticId.BIND_WITHOUT_SOURCE_IGNORED, "EXT-PROC-1106");
        m.put(DiagnosticId.STREAMING_ACTION_NOT_INVOKED, "EXT-PROC-1107");
        m.put(DiagnosticId.STRICT_INERT_ATTRIBUTE, "EXT-PROC-1201");
        m.put(DiagnosticId.STRICT_INERT_ANNOTATION, "EXT-PROC-1202");
        m.put(DiagnosticId.STRICT_UNREAD_ANNOTATION, "EXT-PROC-1203");
        m.put(DiagnosticId.VERBOSE_PROGRESS, "EXT-PROC-1901");
        m.put(DiagnosticId.VERBOSE_SOURCE_UNREADABLE, "EXT-PROC-1902");
        m.put(DiagnosticId.EMPTY_METADATA_REFUSED, "EXT-PLUG-2001");
        m.put(DiagnosticId.GENERATION_FAILED, "EXT-PLUG-2002");
        m.put(DiagnosticId.TEST_GENERATION_FAILED, "EXT-PLUG-2003");
        m.put(DiagnosticId.DETACH_FAILED, "EXT-PLUG-2101");
        m.put(DiagnosticId.DETACH_CONFLICTS, "EXT-PLUG-2102");
        m.put(DiagnosticId.CAPABILITY_GRAPH_UNRESOLVED, "EXT-PLUG-2201");
        m.put(DiagnosticId.CAPABILITY_VERIFICATION_FAILED, "EXT-PLUG-2202");
        m.put(DiagnosticId.CAP_TIER_WALL_VIOLATED, "EXT-PLUG-2203");
        m.put(DiagnosticId.CAP_TIER_WALL_SCAN_FAILED, "EXT-PLUG-2204");
        m.put(DiagnosticId.CAP_TIER_WALL_DISABLED, "EXT-PLUG-2205");
        m.put(DiagnosticId.CAP_TIER_WALL_SCANNED_NOTHING, "EXT-PLUG-2206");
        m.put(DiagnosticId.RUNTIME_METADATA_UNREADABLE, "EXT-PLUG-2301");
        m.put(DiagnosticId.RUNTIME_DRIVER_MISSING, "EXT-PLUG-2302");
        m.put(DiagnosticId.CLI_GENERATION_FAILED, "EXT-GEN-3001");
        m.put(DiagnosticId.CLI_ARGUMENTS_INVALID, "EXT-GEN-3002");
        m.put(DiagnosticId.NO_METADATA_FOUND, "EXT-GEN-3101");
        m.put(DiagnosticId.OPTIONAL_REQUIREMENT_UNSATISFIED, "EXT-GEN-3102");
        m.put(DiagnosticId.CAPABILITY_GRAPH_DEFERRED, "EXT-GEN-3103");
        return m;
    }

    @Test
    @DisplayName("every constant is pinned, and every pinned value is what the constant carries")
    void everyIdentifierIsPinned() {
        assertThat(PINNED.keySet()).containsExactlyInAnyOrder(DiagnosticId.values());
        PINNED.forEach((id, code) -> assertThat(id.code()).as(id.name()).isEqualTo(code));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DiagnosticId.class)
    @DisplayName("matches EXT-(PROC|PLUG|GEN)-dddd, with PROC in 1xxx, PLUG in 2xxx and GEN in 3xxx")
    void formatAndArea(DiagnosticId id) {
        Matcher m = FORMAT.matcher(id.code());
        assertThat(m.matches()).as(id.code()).isTrue();
        char block = id.code().charAt(id.code().length() - 4);
        assertThat(block).as(id.code()).isEqualTo(AREA_BLOCK.get(m.group(1)));
    }

    @Test
    @DisplayName("the TS area and its 4xxx block are reserved, not allocated")
    void typeScriptAreaIsReserved() {
        assertThat(DiagnosticId.values()).allSatisfy(id -> {
            assertThat(id.code()).doesNotStartWith("EXT-TS-");
            assertThat(id.code().charAt(id.code().length() - 4)).isNotEqualTo('4');
        });
    }

    @Test
    @DisplayName("the consumer regex finds the identifier anywhere in a line, as Maven prints it")
    void consumerRegexFindsIdentifierInsideALine() {
        String line = "[ERROR] Failed to execute goal eu.exeris.tooling:exeris-codegen-maven-plugin:"
                + "0.9.0:generate (exeris-generate) on project shop: "
                + DiagnosticId.EMPTY_METADATA_REFUSED.format("Refusing to wipe the committed tree");
        Matcher m = CONSUMER.matcher(line);
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isEqualTo("EXT-PLUG-2001");
    }

    @Test
    @DisplayName("no two constants share an identifier")
    void identifiersAreUnique() {
        Set<String> codes = Arrays.stream(DiagnosticId.values())
                .map(DiagnosticId::code)
                .collect(Collectors.toSet());
        assertThat(codes).hasSize(DiagnosticId.values().length);
    }

    @Test
    @DisplayName("is distinct from the kernel's EX- namespace")
    void distinctFromKernelCodes() {
        assertThat(DiagnosticId.values())
                .allSatisfy(id -> assertThat(id.code()).doesNotMatch("EX-[A-Z]+-\\d+"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DiagnosticId.class)
    @DisplayName("carries a one-line meaning")
    void meaningIsOneLine(DiagnosticId id) {
        assertThat(id.meaning()).isNotBlank().doesNotContain("\n");
    }

    @Test
    @DisplayName("format puts the prefix and the identifier ahead of the message, unchanged")
    void formatShape() {
        assertThat(DiagnosticId.PROCESSING_FAILURE.format("Failed to process enum: x"))
                .isEqualTo("[Exeris] EXT-PROC-1002: Failed to process enum: x");
        assertThat(DiagnosticId.RUNTIME_DRIVER_MISSING.format("line one\nline two"))
                .startsWith(DiagnosticId.PREFIX + "EXT-PLUG-2302: line one");
    }

    @Test
    @DisplayName("docs/diagnostics.md lists exactly the registry's identifiers, and retires none of them")
    void referenceTableMatchesRegistry() throws IOException {
        Path doc = Path.of("..", "docs", "diagnostics.md");
        assertThat(doc).as("the diagnostics reference, relative to the module directory").exists();
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        int retiredAt = text.indexOf(RETIRED_HEADING);
        assertThat(retiredAt).as("the reference keeps a '" + RETIRED_HEADING + "' section").isNotNegative();

        Set<String> documented = rows(text.substring(0, retiredAt));
        Set<String> retired = rows(text.substring(retiredAt));
        Set<String> registered = Arrays.stream(DiagnosticId.values())
                .map(DiagnosticId::code)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(documented).isEqualTo(registered);
        assertThat(retired).as("a retired identifier is never allocated again").doesNotContainAnyElementsOf(registered);
        assertThat(text).as("the consumer regex, verbatim").contains("`" + CONSUMER.pattern() + "`");
    }

    private static Set<String> rows(String markdown) {
        Set<String> codes = new TreeSet<>();
        Matcher row = TABLE_ROW.matcher(markdown);
        while (row.find()) {
            assertThat(codes.add(row.group(1))).as("listed twice: " + row.group(1)).isTrue();
        }
        return codes;
    }
}
