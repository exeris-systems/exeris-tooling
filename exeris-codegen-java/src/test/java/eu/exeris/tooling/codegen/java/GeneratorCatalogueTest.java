package eu.exeris.tooling.codegen.java;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@code META-INF/exeris/generator-catalogue.json} to the code that writes the files it
 * describes and to {@code docs/generators.md} (ADR-097).
 *
 * <p>What this cannot see is whether a pattern matches the paths a real run writes: that is the
 * conformance test in {@code exeris-e2e-tests}, which runs the processor and the pipeline over
 * annotated fixtures and reads the manifests they leave.
 */
class GeneratorCatalogueTest {

    private static final String RESOURCE = "/META-INF/exeris/generator-catalogue.json";
    private static final Path SOURCE = Path.of("src", "main", "resources", "META-INF", "exeris",
            "generator-catalogue.json");
    private static final Path DOC = Path.of("..", "docs", "generators.md");

    private static final List<String> TOP_LEVEL_KEYS = List.of("catalogueFormat", "rows", "retired");
    private static final List<String> ROW_KEYS = List.of("id", "generator", "artefact", "outputRoot",
            "pathPattern", "pathTemplate", "scope", "cardinality", "condition", "ambiguousWith", "adr",
            "since");
    private static final List<String> RETIRED_KEYS = List.of("retiredIn", "replacedBy");

    private static final Set<String> OUTPUT_ROOTS = Set.of("main", "test");
    private static final Set<String> SCOPES = Set.of("entity", "project");
    private static final Set<String> CARDINALITIES =
            Set.of("one", "zero-or-one", "one-per-streaming-action", "fixed-set");

    private static final Pattern ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern ADR = Pattern.compile("ADR-\\d{3}");
    private static final Pattern RELEASE = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    /** Placeholder values for {@link #everyTemplateIsFirstMatchedByItsOwnRow}. */
    private static final Map<String, String> SAMPLE = Map.of(
            "{base}", "com/acme/sales",
            "{app}", "com/acme",
            "{E}", "Invoice",
            "{Action}", "Settle",
            "{Flow}", "InvoiceSagaFlow",
            "{table}", "invoices",
            "{tier}", "1",
            "{nnnnnn}", "123456",
            "{kebab(E)}", "invoice");

    private static final Pattern BRACE_SET = Pattern.compile("\\{([^{}]*,[^{}]*)}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String text;
    private static JsonNode catalogue;
    private static List<JsonNode> rows;

    @BeforeAll
    static void load() throws IOException {
        text = Files.readString(SOURCE, StandardCharsets.UTF_8);
        catalogue = MAPPER.readTree(text);
        rows = new ArrayList<>();
        catalogue.get("rows").forEach(rows::add);
    }

    @Test
    @DisplayName("the catalogue in the jar is the committed file, byte for byte")
    void resourceIsTheCommittedFile() throws IOException {
        try (InputStream in = GeneratorCatalogueTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE + " on the classpath").isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(text);
        }
    }

    @Test
    @DisplayName("the file is in canonical form: re-serialising it yields the same bytes")
    void fileIsCanonical() throws IOException {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter().withSeparators(
                Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                        .withArrayEmptySeparator("")
                        .withObjectEmptySeparator(""));
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);

        assertThat(MAPPER.writer(printer).writeValueAsString(catalogue) + "\n").isEqualTo(text);
    }

    @Test
    @DisplayName("format 1: the top-level keys, and every row's keys, in their fixed order")
    void keysAreThoseOfFormatOne() {
        assertThat(fieldNames(catalogue)).containsExactlyElementsOf(TOP_LEVEL_KEYS);
        assertThat(catalogue.get("catalogueFormat").isInt()).isTrue();
        assertThat(catalogue.get("catalogueFormat").asInt()).isEqualTo(1);
        assertThat(catalogue.get("rows").isArray()).isTrue();
        assertThat(catalogue.get("retired").isArray()).isTrue();
        for (JsonNode row : rows) {
            assertThat(fieldNames(row)).as(row.path("id").asText()).containsExactlyElementsOf(ROW_KEYS);
        }
        for (JsonNode retired : catalogue.get("retired")) {
            List<String> expected = new ArrayList<>(ROW_KEYS);
            expected.addAll(RETIRED_KEYS);
            assertThat(fieldNames(retired)).as("retired " + retired.path("id").asText())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("every id is a slug, unique across rows and retired, and a retired id names its successor")
    void idsAreUniqueSlugs() {
        Set<String> rowIds = new HashSet<>();
        Set<String> all = new HashSet<>();
        for (JsonNode row : rows) {
            String id = row.get("id").asText();
            assertThat(id).matches(ID);
            assertThat(all.add(id)).as("duplicate id " + id).isTrue();
            rowIds.add(id);
        }
        for (JsonNode retired : catalogue.get("retired")) {
            String id = retired.get("id").asText();
            assertThat(id).matches(ID);
            assertThat(all.add(id)).as("duplicate id " + id).isTrue();
            assertThat(retired.get("retiredIn").asText()).matches(RELEASE);
            assertThat(rowIds).as("successor of " + id).contains(retired.get("replacedBy").asText());
        }
    }

    @Test
    @DisplayName("every column carries a value its key allows")
    void columnsCarryAllowedValues() {
        for (JsonNode row : rows) {
            String id = row.get("id").asText();
            assertThat(row.get("generator").asText()).as(id).matches("[A-Z][A-Za-z0-9]*");
            assertThat(row.get("artefact").asText()).as(id).isNotBlank();
            assertThat(row.get("outputRoot").asText()).as(id).isIn(OUTPUT_ROOTS);
            assertThat(row.get("pathTemplate").asText()).as(id).isNotBlank();
            assertThat(row.get("scope").asText()).as(id).isIn(SCOPES);
            assertThat(row.get("cardinality").asText()).as(id).isIn(CARDINALITIES);
            assertThat(row.get("condition").asText()).as(id).isNotBlank();
            assertThat(row.get("ambiguousWith").isArray()).as(id).isTrue();
            JsonNode adr = row.get("adr");
            assertThat(adr.isNull() || ADR.matcher(adr.asText()).matches()).as(id + " adr").isTrue();
            assertThat(row.get("since").asText()).as(id).matches(RELEASE);
            for (String column : List.of("artefact", "pathTemplate", "condition")) {
                assertThat(row.get(column).asText()).as(id + " " + column + " in a Markdown cell")
                        .doesNotContain("|");
            }
        }
    }

    @Test
    @DisplayName("every pattern is anchored, compiles, and reads the same in Java and ECMAScript")
    void patternsArePortable() {
        for (JsonNode row : rows) {
            String id = row.get("id").asText();
            String pattern = row.get("pathPattern").asText();
            assertThat(pattern).as(id).startsWith("^").endsWith("$").doesNotEndWith("\\$");
            Pattern.compile(pattern);
            assertThat(portabilityViolation(pattern)).as(id + ": " + pattern).isNull();
        }
    }

    @Test
    @DisplayName("the portability check refuses each construct ADR-097 excludes")
    void portabilityCheckRefusesTheExcludedConstructs() {
        assertThat(portabilityViolation("^(?:[^/]+/)*handler/[^/]+Handler\\.java$")).isNull();
        assertThat(portabilityViolation("^a(?:b|c){2}[0-9]?$")).isNull();
        for (String excluded : List.of(
                "^(?=a)a$", "^(?!a)b$", "^(?<=a)b$", "^(?<!a)b$",
                "^(a)\\1$", "^(?<name>a)$", "^\\k<name>$",
                "^(?i)a$", "^(?s:a)$",
                "^\\w$", "^\\d$", "^\\s$", "^\\b$", "^\\p{L}$", "^[\\w]$",
                "^(a)$", "^a{1,2}$", "^a*?$", "^a++$", "^(?>a)$", "^[a&&b]$", "^[[a]]$")) {
            assertThat(portabilityViolation(excluded)).as(excluded).isNotNull();
        }
    }

    @Test
    @DisplayName("ambiguousWith names existing rows of the same root, never itself, and symmetrically")
    void ambiguityIsSymmetric() {
        Map<String, JsonNode> byId = byId();
        for (JsonNode row : rows) {
            String id = row.get("id").asText();
            Set<String> seen = new HashSet<>();
            for (JsonNode other : row.get("ambiguousWith")) {
                String otherId = other.asText();
                assertThat(seen.add(otherId)).as(id + " lists " + otherId + " twice").isTrue();
                assertThat(otherId).as(id).isNotEqualTo(id);
                assertThat(byId).as(id + " → " + otherId).containsKey(otherId);
                JsonNode target = byId.get(otherId);
                assertThat(target.get("outputRoot").asText()).as(id + " → " + otherId)
                        .isEqualTo(row.get("outputRoot").asText());
                assertThat(texts(target.get("ambiguousWith"))).as(otherId + " lists " + id).contains(id);
            }
        }
    }

    @Test
    @DisplayName("within an ambiguity group, a row with a narrower shape comes before the wider one")
    void narrowerAmbiguousRowsComeFirst() {
        Map<String, JsonNode> byId = byId();
        List<String> order = rows.stream().map(row -> row.get("id").asText()).toList();
        for (JsonNode wide : rows) {
            String wideId = wide.get("id").asText();
            Pattern widePattern = Pattern.compile(wide.get("pathPattern").asText());
            for (String narrowId : texts(wide.get("ambiguousWith"))) {
                JsonNode narrow = byId.get(narrowId);
                Pattern narrowPattern = Pattern.compile(narrow.get("pathPattern").asText());
                // Narrower: the wide row's pattern takes the narrow row's paths, and not the reverse.
                boolean wideTakesNarrow = instantiate(narrow.get("pathTemplate").asText()).stream()
                        .allMatch(path -> widePattern.matcher(path).matches());
                boolean narrowTakesWide = instantiate(wide.get("pathTemplate").asText()).stream()
                        .allMatch(path -> narrowPattern.matcher(path).matches());
                if (wideTakesNarrow && !narrowTakesWide) {
                    assertThat(order.indexOf(narrowId)).as(narrowId + " before " + wideId)
                            .isLessThan(order.indexOf(wideId));
                }
            }
        }
        assertThat(order.indexOf("stream-handler")).isLessThan(order.indexOf("handler"));
        assertThat(order.indexOf("action-stream-handler")).isLessThan(order.indexOf("handler"));
    }

    @Test
    @DisplayName("each row's template, filled in, is first matched by that row or a row it lists")
    void everyTemplateIsFirstMatchedByItsOwnRow() {
        for (JsonNode row : rows) {
            String id = row.get("id").asText();
            for (String path : instantiate(row.get("pathTemplate").asText())) {
                JsonNode first = firstMatch(row.get("outputRoot").asText(), path);
                assertThat(first).as(id + ": no row matches " + path).isNotNull();
                String firstId = first.get("id").asText();
                if (!firstId.equals(id)) {
                    assertThat(texts(first.get("ambiguousWith"))).as(path + " first matches " + firstId)
                            .contains(id);
                }
                assertThat(Pattern.compile(row.get("pathPattern").asText()).matcher(path).matches())
                        .as(id + " matches its own template " + path).isTrue();
            }
        }
    }

    @Test
    @DisplayName("every writer has a row, and every row names a writer (ADR-097 obligation 7)")
    void rowsCoverExactlyTheWriters() {
        Set<String> writers = new TreeSet<>();
        for (KernelArtifactGenerator generator : new KernelGeneratorStrategy().getRegistry().getGenerators()) {
            writers.add(generator.getClass().getSimpleName());
        }
        // The project-wide and test-channel generators are fields of the pipeline rather than
        // registry entries; reading the fields keeps a newly added one from going unnoticed.
        for (Field field : CodegenPipeline.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType().getSimpleName().endsWith("Generator")) {
                writers.add(field.getType().getSimpleName());
            }
        }
        writers.add(CodegenPipeline.class.getSimpleName());

        assertThat(writers).contains("KernelApplicationGenerator", "KernelTestSupportGenerator",
                "KernelHandlerTestGenerator", "KernelServiceTestGenerator",
                "KernelRepositoryTestGenerator", "KernelSagaTestGenerator");
        Set<String> named = new TreeSet<>();
        rows.forEach(row -> named.add(row.get("generator").asText()));
        assertThat(named).containsExactlyElementsOf(writers);
    }

    @Test
    @DisplayName("the pipeline's own row names the file it writes")
    void capManifestRowNamesThePipelineConstant() {
        List<JsonNode> own = rows.stream()
                .filter(row -> row.get("generator").asText().equals(CodegenPipeline.class.getSimpleName()))
                .toList();
        assertThat(own).singleElement().satisfies(row -> {
            assertThat(row.get("pathTemplate").asText()).isEqualTo(CodegenPipeline.CAP_MANIFEST);
            assertThat(row.get("outputRoot").asText()).isEqualTo("main");
        });
    }

    @Test
    @DisplayName("docs/generators.md tables are the catalogue's rows, in order, column for column")
    void documentListsTheRows() throws IOException {
        Doc doc = Doc.read(Files.readString(DOC, StandardCharsets.UTF_8));

        List<List<String>> expected = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode adr = row.get("adr");
            expected.add(List.of(
                    row.get("outputRoot").asText(), row.get("scope").asText(),
                    "`" + row.get("id").asText() + "`", "`" + row.get("generator").asText() + "`",
                    row.get("artefact").asText(), "`" + row.get("pathTemplate").asText() + "`",
                    row.get("cardinality").asText(), row.get("condition").asText(),
                    adr.isNull() ? "—" : adr.asText()));
        }
        assertThat(doc.rows).containsExactlyElementsOf(expected);

        Map<String, String> ambiguous = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            List<String> others = texts(row.get("ambiguousWith"));
            if (!others.isEmpty()) {
                ambiguous.put("`" + row.get("id").asText() + "`",
                        String.join(", ", others.stream().map(o -> "`" + o + "`").toList()));
            }
        }
        assertThat(doc.ambiguous).containsExactlyEntriesOf(ambiguous);

        List<String> retired = texts(catalogue.get("retired"), "id");
        if (retired.isEmpty()) {
            assertThat(doc.retiredSection).contains("None yet.").doesNotContain("| `");
        } else {
            for (String id : retired) {
                assertThat(doc.retiredSection).contains("`" + id + "`");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode firstMatch(String outputRoot, String path) {
        for (JsonNode row : rows) {
            if (row.get("outputRoot").asText().equals(outputRoot)
                    && Pattern.compile(row.get("pathPattern").asText()).matcher(path).matches()) {
                return row;
            }
        }
        return null;
    }

    private static List<String> instantiate(String template) {
        List<String> paths = new ArrayList<>();
        Matcher set = BRACE_SET.matcher(template);
        if (set.find()) {
            for (String alternative : set.group(1).split(",", -1)) {
                paths.addAll(instantiate(template.substring(0, set.start()) + alternative
                        + template.substring(set.end())));
            }
            return paths;
        }
        String path = template;
        for (Map.Entry<String, String> placeholder : SAMPLE.entrySet()) {
            path = path.replace(placeholder.getKey(), placeholder.getValue());
        }
        assertThat(path).as("an unknown placeholder in " + template).doesNotContain("{").doesNotContain("}");
        paths.add(path);
        return paths;
    }

    /**
     * The first construct in {@code pattern} outside the subset ADR-097 obligation 3 allows, or
     * {@code null}. The subset: literals, {@code .}, bracket classes, {@code ? * +}, {@code {n}},
     * non-capturing groups, alternation, and the anchors.
     */
    static String portabilityViolation(String pattern) {
        int i = 0;
        boolean inClass = false;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                if (i + 1 >= pattern.length()) {
                    return "trailing backslash";
                }
                char escaped = pattern.charAt(i + 1);
                if (Character.isLetterOrDigit(escaped)) {
                    return "escape \\" + escaped;
                }
                i += 2;
                continue;
            }
            if (inClass) {
                if (c == '[') {
                    return "nested bracket class";
                }
                if (c == '&' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '&') {
                    return "class intersection";
                }
                if (c == ']') {
                    inClass = false;
                }
                i++;
                continue;
            }
            switch (c) {
                case '[' -> {
                    inClass = true;
                    // A leading ']' or '^]' is a literal in Java and a different class in ECMAScript.
                    int next = i + 1 < pattern.length() && pattern.charAt(i + 1) == '^' ? i + 2 : i + 1;
                    if (next < pattern.length() && pattern.charAt(next) == ']') {
                        return "leading ] in a bracket class";
                    }
                    i = next;
                }
                case '(' -> {
                    if (!pattern.startsWith("(?:", i)) {
                        return "group other than (?:";
                    }
                    i += 3;
                }
                case '{' -> {
                    int close = pattern.indexOf('}', i);
                    if (close < 0 || !pattern.substring(i + 1, close).matches("[0-9]+")) {
                        return "quantifier other than {n}";
                    }
                    i = close + 1;
                    if (i < pattern.length() && (pattern.charAt(i) == '?' || pattern.charAt(i) == '+')) {
                        return "lazy or possessive quantifier";
                    }
                }
                case '?', '*', '+' -> {
                    if (i + 1 < pattern.length() && (pattern.charAt(i + 1) == '?' || pattern.charAt(i + 1) == '+')) {
                        return "lazy or possessive quantifier";
                    }
                    i++;
                }
                default -> i++;
            }
        }
        return inClass ? "unclosed bracket class" : null;
    }

    private static Map<String, JsonNode> byId() {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.get("id").asText(), row));
        return byId;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.get(field).asText()));
        return values;
    }

    /**
     * The parts of {@code docs/generators.md} that restate the catalogue: the row tables under the
     * {@code ### <Root> root, <scope> scope} headings, the ambiguity table, and the retired section.
     */
    private static final class Doc {

        private static final Pattern SECTION =
                Pattern.compile("^### (Main|Test) root, (entity|project) scope$");
        private static final Pattern CELL_SPLIT = Pattern.compile("(?<!\\\\)\\|");

        final List<List<String>> rows = new ArrayList<>();
        final Map<String, String> ambiguous = new LinkedHashMap<>();
        String retiredSection = "";

        static Doc read(String markdown) {
            Doc doc = new Doc();
            String root = null;
            String scope = null;
            String h2 = "";
            StringBuilder retired = new StringBuilder();
            for (String line : markdown.split("\n", -1)) {
                if (line.startsWith("## ")) {
                    h2 = line.substring(3).strip();
                    root = null;
                    continue;
                }
                Matcher section = SECTION.matcher(line);
                if (section.matches()) {
                    root = section.group(1).toLowerCase(java.util.Locale.ROOT);
                    scope = section.group(2);
                    continue;
                }
                if (h2.equals("Retired rows")) {
                    retired.append(line).append('\n');
                    continue;
                }
                if (!line.startsWith("| `")) {
                    continue;
                }
                List<String> cells = cells(line);
                if (h2.equals("Ambiguous rows")) {
                    doc.ambiguous.put(cells.get(0), cells.get(1));
                } else if (root != null) {
                    List<String> row = new ArrayList<>(List.of(root, scope));
                    row.addAll(cells);
                    doc.rows.add(row);
                }
            }
            doc.retiredSection = retired.toString();
            return doc;
        }

        private static List<String> cells(String line) {
            String inner = line.strip();
            inner = inner.substring(1, inner.length() - 1);
            List<String> cells = new ArrayList<>();
            for (String cell : CELL_SPLIT.split(inner, -1)) {
                cells.add(cell.strip().replace("\\|", "|"));
            }
            return cells;
        }
    }
}
