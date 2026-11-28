package eu.exeris.tooling.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.tooling.diagnostics.DiagnosticId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@code annotation-dispositions.json} to the SDK annotation catalogue the build resolves,
 * to the SDK's Preview list, to the generator catalogue and the coverage plan, and to the
 * strict-audit registries of {@link ExerisDomainProcessor}.
 *
 * <p>The registry gives every annotation and every attribute of the catalogue exactly one state
 * ({@code EMITTED}, {@code SDK_REMOVED}, {@code OUTSIDE_FREEZE}, {@code REFUSED}, {@code EXCEPTION}
 * or {@code OPEN}). The processor never reads it: it is a census that this test keeps complete and
 * consistent with what {@code -Aexeris.strict} reports.
 *
 * <p>Every failure of one run is listed together. A pair the registry lacks is printed as a row
 * ready to paste, with a {@code planRow} placeholder this test refuses until it is replaced.
 */
@DisplayName("Annotation dispositions are complete, canonical and consistent with the strict audit")
class AnnotationDispositionRegistryTest {

    private static final String RESOURCE_DIR = "/eu/exeris/tooling/processor/";
    private static final String CATALOG = "META-INF/exeris/annotation-catalog.json";
    private static final Path GENERATOR_CATALOGUE = Path.of("..", "exeris-codegen-java", "src", "main",
            "resources", "META-INF", "exeris", "generator-catalogue.json");
    private static final Path GENERATORS_DOC = Path.of("..", "docs", "generators.md");
    private static final Path COVERAGE_PLAN = Path.of("..", "docs", "1.0.0-annotation-coverage-plan.md");

    private static final String PLACEHOLDER_PLAN_ROW = "UNCLASSIFIED";
    private static final String TS_BEGIN = "<!-- catalogue:ts:begin -->";
    private static final String TS_END = "<!-- catalogue:ts:end -->";

    private static final List<String> STATES =
            List.of("EMITTED", "SDK_REMOVED", "OUTSIDE_FREEZE", "REFUSED", "EXCEPTION", "OPEN");
    private static final Map<String, String> STATE_KEY = Map.of(
            "EMITTED", "emittedBy",
            "OUTSIDE_FREEZE", "ruling",
            "REFUSED", "diagnostic",
            "EXCEPTION", "ruling",
            "OPEN", "planRow");
    private static final List<String> KEY_ORDER = List.of(
            "annotation", "attribute", "on", "state", "emittedBy", "ruling", "diagnostic", "planRow", "note");
    private static final Set<String> INHERITING_STATES = Set.of("SDK_REMOVED", "OUTSIDE_FREEZE", "REFUSED");
    private static final Set<String> STRICT_REPORTED_STATES =
            Set.of("OPEN", "EXCEPTION", "SDK_REMOVED", "OUTSIDE_FREEZE");
    /**
     * The attribute states that require an {@code INERT_ATTRIBUTES} entry. {@code SDK_REMOVED} is not
     * among them: javac reports a use of an attribute the SDK marks for removal, so an entry for it is
     * allowed and not required.
     */
    private static final Set<String> ENTRY_REQUIRED_STATES = Set.of("OPEN", "EXCEPTION", "OUTSIDE_FREEZE");

    private static final String RULE_NOT_EXTRACTED = "emitted-annotation-not-extracted";
    private static final String RULE_INERT_ANNOTATION = "emitted-annotation-is-inert";
    private static final String RULE_NOT_REPORTED = "annotation-not-reported";
    private static final String RULE_UNREGISTERED = "attribute-unregistered";
    private static final String RULE_STALE_ENTRY = "attribute-entry-on-emitted";
    private static final String RULE_NOTE = "unread-note-on-emitted-annotation";

    /**
     * The disagreements between the registry and the strict-audit registries,
     * one {@code <rule> <annotation>[#<attribute>]} entry per line. The set only shrinks: the test
     * fails on a disagreement that is not listed and on an entry that no longer disagrees.
     */
    private static final String KNOWN_DISAGREEMENTS_TEXT = """
            annotation-not-reported InternalApi
            attribute-unregistered Action#async
            attribute-unregistered Action#bulk
            attribute-unregistered Action#closeAfter
            attribute-unregistered Action#confirm
            attribute-unregistered Action#debounce
            attribute-unregistered Action#errorMessage
            attribute-unregistered Action#location
            attribute-unregistered Action#maxConcurrent
            attribute-unregistered Action#order
            attribute-unregistered Action#realTimeUpdates
            attribute-unregistered Action#redirectTo
            attribute-unregistered Action#refreshAfter
            attribute-unregistered Action#showProgress
            attribute-unregistered Action#showResultMessage
            attribute-unregistered Action#successMessage
            attribute-unregistered Action#timeout
            attribute-unregistered Action#ui
            attribute-unregistered Action#variant
            attribute-unregistered Action#visibleWhen
            attribute-unregistered ActionParam#allowedFileTypes
            attribute-unregistered ActionParam#dataType
            attribute-unregistered ActionParam#defaultValueExpression
            attribute-unregistered ActionParam#enabledWhen
            attribute-unregistered ActionParam#format
            attribute-unregistered ActionParam#gridSpan
            attribute-unregistered ActionParam#group
            attribute-unregistered ActionParam#helpText
            attribute-unregistered ActionParam#icon
            attribute-unregistered ActionParam#label
            attribute-unregistered ActionParam#masked
            attribute-unregistered ActionParam#maxFileSize
            attribute-unregistered ActionParam#multipleFiles
            attribute-unregistered ActionParam#nullable
            attribute-unregistered ActionParam#optionIcons
            attribute-unregistered ActionParam#optionLabelField
            attribute-unregistered ActionParam#optionLabels
            attribute-unregistered ActionParam#optionValueField
            attribute-unregistered ActionParam#options
            attribute-unregistered ActionParam#optionsExpression
            attribute-unregistered ActionParam#order
            attribute-unregistered ActionParam#placeholder
            attribute-unregistered ActionParam#relationship
            attribute-unregistered ActionParam#requiredWhen
            attribute-unregistered ActionParam#transformExpression
            attribute-unregistered ActionParam#ui
            attribute-unregistered ActionParam#validation
            attribute-unregistered ActionParam#visibleWhen
            attribute-unregistered ExerisDomain#aggregate
            attribute-unregistered ExerisDomain#cacheRegion
            attribute-unregistered ExerisDomain#cacheTtl
            attribute-unregistered ExerisDomain#cacheable
            attribute-unregistered ExerisDomain#fullTextSearch
            attribute-unregistered ExerisDomain#graphqlApi
            attribute-unregistered ExerisDomain#internalClient
            attribute-unregistered ExerisDomain#module
            attribute-unregistered ExerisDomain#restApi
            attribute-unregistered ExerisDomain#searchConfig
            attribute-unregistered ExerisDomain#sensitive
            attribute-unregistered ExerisDomain#tags
            attribute-unregistered ExerisDomain#ui
            attribute-unregistered ExerisDomain#validationMode
            attribute-unregistered Graph#additionalLabels
            attribute-unregistered Graph#alertOnSyncFailure
            attribute-unregistered Graph#batchSync
            attribute-unregistered Graph#cacheQueries
            attribute-unregistered Graph#cacheRegion
            attribute-unregistered Graph#cacheTtl
            attribute-unregistered Graph#createIndexes
            attribute-unregistered Graph#createSimilarityEdges
            attribute-unregistered Graph#database
            attribute-unregistered Graph#defaultMaxDepth
            attribute-unregistered Graph#defaultMaxResults
            attribute-unregistered Graph#embeddingService
            attribute-unregistered Graph#emitMetrics
            attribute-unregistered Graph#enableSimilarity
            attribute-unregistered Graph#excludeEdgeTypes
            attribute-unregistered Graph#excludeFromSync
            attribute-unregistered Graph#excludeNodeLabels
            attribute-unregistered Graph#fullTextAnalyzer
            attribute-unregistered Graph#fullTextIndex
            attribute-unregistered Graph#fullTextProperties
            attribute-unregistered Graph#graphDataSource
            attribute-unregistered Graph#graphName
            attribute-unregistered Graph#graphSchema
            attribute-unregistered Graph#graphqlExtensions
            attribute-unregistered Graph#graphqlQueries
            attribute-unregistered Graph#graphqlTypeName
            attribute-unregistered Graph#idProperty
            attribute-unregistered Graph#includeInEntityType
            attribute-unregistered Graph#indexedProperties
            attribute-unregistered Graph#invalidateCacheOnUpdate
            attribute-unregistered Graph#maxSimilarResults
            attribute-unregistered Graph#nodeType
            attribute-unregistered Graph#queryAlertThreshold
            attribute-unregistered Graph#queryLogLevel
            attribute-unregistered Graph#recalculateSimilarityOnUpdate
            attribute-unregistered Graph#similarityAlgorithm
            attribute-unregistered Graph#similarityEdgeType
            attribute-unregistered Graph#similarityFields
            attribute-unregistered Graph#similaritySchedule
            attribute-unregistered Graph#similarityThreshold
            attribute-unregistered Graph#sourceTable
            attribute-unregistered Graph#syncBatchSize
            attribute-unregistered Graph#syncCondition
            attribute-unregistered Graph#syncDlq
            attribute-unregistered Graph#syncOnEvents
            attribute-unregistered Graph#syncRetries
            attribute-unregistered Graph#syncRetryDelay
            attribute-unregistered Graph#syncTimeout
            attribute-unregistered Graph#syncToGraph
            attribute-unregistered Graph#syncType
            attribute-unregistered Graph#tenantFilterInTraversals
            attribute-unregistered Graph#traversalEdgeTypes
            attribute-unregistered Graph#traversalNodeLabels
            attribute-unregistered Graph#uniqueConstraint
            attribute-unregistered Graph#useVectorEmbeddings
            attribute-unregistered Graph#vectorDimensions
            attribute-unregistered GraphEdge#bidirectional
            attribute-unregistered GraphEdge#description
            attribute-unregistered GraphEdge#direction
            attribute-unregistered GraphEdge#inverseType
            attribute-unregistered GraphEdge#properties
            attribute-unregistered GraphEdge#propertyMappings
            attribute-unregistered GraphEdge#staticProperties
            attribute-unregistered GraphEdge#staticWeight
            attribute-unregistered GraphEdge#upsert
            attribute-unregistered GraphEdge#weightField
            attribute-unregistered GraphEdge#weighted
            attribute-unregistered GraphProperty#array
            attribute-unregistered GraphProperty#compositeIndex
            attribute-unregistered GraphProperty#compositeIndexOrder
            attribute-unregistered GraphProperty#defaultValue
            attribute-unregistered GraphProperty#description
            attribute-unregistered GraphProperty#embeddingService
            attribute-unregistered GraphProperty#excludeFromInitialSync
            attribute-unregistered GraphProperty#fullText
            attribute-unregistered GraphProperty#fullTextAnalyzer
            attribute-unregistered GraphProperty#fullTextBoost
            attribute-unregistered GraphProperty#fullTextIndex
            attribute-unregistered GraphProperty#graphType
            attribute-unregistered GraphProperty#hashed
            attribute-unregistered GraphProperty#indexType
            attribute-unregistered GraphProperty#indexed
            attribute-unregistered GraphProperty#masked
            attribute-unregistered GraphProperty#normalize
            attribute-unregistered GraphProperty#nullable
            attribute-unregistered GraphProperty#similarity
            attribute-unregistered GraphProperty#similarityMethod
            attribute-unregistered GraphProperty#similarityWeight
            attribute-unregistered GraphProperty#sync
            attribute-unregistered GraphProperty#syncOnEvents
            attribute-unregistered GraphProperty#tokenize
            attribute-unregistered GraphProperty#tokenizer
            attribute-unregistered GraphProperty#transform
            attribute-unregistered GraphProperty#transformer
            attribute-unregistered GraphProperty#unique
            attribute-unregistered GraphProperty#vectorDimensions
            attribute-unregistered GraphProperty#vectorEmbedding
            attribute-unregistered GraphProperty#vectorProperty
            attribute-unregistered Relationship#allowCreate
            attribute-unregistered Relationship#allowInlineCreate
            attribute-unregistered Relationship#allowInlineEdit
            attribute-unregistered Relationship#autocompleteThreshold
            attribute-unregistered Relationship#cascadeUpdate
            attribute-unregistered Relationship#dependsOn
            attribute-unregistered Relationship#dependsOnField
            attribute-unregistered Relationship#display
            attribute-unregistered Relationship#displayFields
            attribute-unregistered Relationship#displayTemplate
            attribute-unregistered Relationship#fetchType
            attribute-unregistered Relationship#filter
            attribute-unregistered Relationship#icon
            attribute-unregistered Relationship#labelField
            attribute-unregistered Relationship#orderBy
            attribute-unregistered Relationship#required
            attribute-unregistered Relationship#searchFields
            attribute-unregistered Relationship#searchable
            attribute-unregistered Saga#module
            attribute-unregistered Saga#tags
            attribute-unregistered SagaStep#kind
            attribute-unregistered SagaStep#order
            attribute-unregistered SagaStep#parallel
            attribute-unregistered SagaStep#tags
            attribute-unregistered SagaStep#timeout
            attribute-unregistered UIGroup#icon
            attribute-unregistered Validation#asyncValidator
            attribute-unregistered Validation#customValidator
            attribute-unregistered Validation#decimalMax
            attribute-unregistered Validation#decimalMin
            attribute-unregistered Validation#email
            attribute-unregistered Validation#future
            attribute-unregistered Validation#futureOrPresent
            attribute-unregistered Validation#groups
            attribute-unregistered Validation#message
            attribute-unregistered Validation#negative
            attribute-unregistered Validation#negativeOrZero
            attribute-unregistered Validation#past
            attribute-unregistered Validation#pastOrPresent
            attribute-unregistered Validation#positive
            attribute-unregistered Validation#positiveOrZero
            attribute-unregistered Validation#url
            attribute-unregistered Validation#uuid
            attribute-unregistered View#layout
            attribute-unregistered View#titleKey
            attribute-unregistered system.PrimaryKey#strategy
            """;

    private static final Set<String> KNOWN_DISAGREEMENTS = new TreeSet<>(Arrays.stream(
            KNOWN_DISAGREEMENTS_TEXT.split("\n")).map(String::strip).filter(s -> !s.isEmpty()).toList());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode catalogue;
    private static String catalogueVersion;
    private static Map<String, JsonNode> annotations;
    private static Set<String> containers;
    private static String registryText;
    private static List<JsonNode> rows;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = AnnotationDispositionRegistryTest.class.getClassLoader()
                .getResourceAsStream(CATALOG)) {
            assertThat(in).as("%s on the test classpath", CATALOG).isNotNull();
            catalogue = MAPPER.readTree(in);
        }
        catalogueVersion = catalogue.path("sdkVersion").asText();
        annotations = new TreeMap<>();
        containers = new TreeSet<>();
        for (JsonNode annotation : catalogue.path("annotations")) {
            annotations.put(annotation.path("qualifiedName").asText(), annotation);
            if (annotation.hasNonNull("repeatableContainer")) {
                containers.add(annotation.path("repeatableContainer").asText());
            }
        }
        registryText = resource("annotation-dispositions.json");
        rows = new ArrayList<>();
        MAPPER.readTree(registryText).path("rows").forEach(rows::add);
    }

    @Test
    @DisplayName("catalogFormat is the one this test reads")
    void catalogFormatIsKnown() {
        assertThat(catalogue.path("catalogFormat").asInt())
                .as("catalogFormat of %s in the SDK annotations jar; the structural keys read here are"
                        + " defined for format 1 only", CATALOG)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the registry covers the catalogue, holds the states' shape and agrees with the strict audit")
    void registryHoldsTheCatalogue() throws IOException, ReflectiveOperationException {
        List<String> failures = new ArrayList<>();
        failures.addAll(canonicalForm());
        failures.addAll(rowShape());
        failures.addAll(census());
        failures.addAll(removalMarks());
        failures.addAll(previewList());
        failures.addAll(references());
        assertThat(failures).as("registry failures; each line is one fix").isEmpty();
    }

    @Test
    @DisplayName("the annotation-level lookup finds both rows of every type-level annotation")
    void annotationRowsCoverTypeLevelAnnotations() {
        Map<String, JsonNode> byKey = rowsByKey();
        List<String> failures = new ArrayList<>();
        for (String simple : typeLevelNames()) {
            annotations.keySet().stream().filter(q -> simpleName(q).equals(simple)).forEach(q -> {
                List<String> on = annotationRows(byKey, q).stream().map(r -> r.path("on").asText()).toList();
                if (!on.equals(List.of("TYPE", "NON_TYPE"))) {
                    failures.add(q + ": the lookup found the rows on " + on + ", not TYPE and NON_TYPE");
                }
            });
        }
        assertThat(failures).as("type-level lookups; each line is one fix").isEmpty();
    }

    @Test
    @DisplayName("the strict-audit registries agree with the states, except the listed known disagreements")
    void strictAuditAgreement() throws ReflectiveOperationException {
        Set<String> actual = new TreeSet<>(agreement());
        List<String> unlisted = actual.stream().filter(d -> !KNOWN_DISAGREEMENTS.contains(d)).toList();
        List<String> resolved = KNOWN_DISAGREEMENTS.stream().filter(d -> !actual.contains(d)).toList();
        List<String> failures = new ArrayList<>();
        unlisted.forEach(d -> failures.add("disagreement with INERT_ATTRIBUTES / INERT_ANNOTATIONS /"
                + " UNREAD_NOTES / EXTRACTED_ANNOTATIONS that KNOWN_DISAGREEMENTS does not list: " + d));
        resolved.forEach(d -> failures.add("KNOWN_DISAGREEMENTS entry that no longer disagrees; delete it: " + d));
        assertThat(failures).as("strict-audit agreement failures; each line is one fix").isEmpty();
    }

    // ---- canonical form ------------------------------------------------------------------------

    private static List<String> canonicalForm() throws IOException {
        List<JsonNode> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparing((JsonNode r) -> r.path("annotation").asText())
                .thenComparing(r -> r.has("attribute"))
                .thenComparing(r -> r.path("on").asText())
                .thenComparing(r -> r.path("attribute").asText()));
        StringBuilder out = new StringBuilder("{\n  \"registryFormat\": 1,\n  \"rows\": [\n");
        for (int i = 0; i < sorted.size(); i++) {
            out.append("    ").append(render(sorted.get(i))).append(i + 1 < sorted.size() ? ",\n" : "\n");
        }
        out.append("  ]\n}\n");
        List<String> failures = new ArrayList<>();
        if (MAPPER.readTree(registryText).path("registryFormat").asInt() != 1) {
            failures.add("annotation-dispositions.json: registryFormat must be 1");
        }
        if (!out.toString().equals(registryText)) {
            failures.add("annotation-dispositions.json differs from its canonical serialisation: rows sorted by"
                    + " annotation, annotation-level row first, then attribute; keys in the order "
                    + KEY_ORDER + "; two-space indent, one row per line, trailing newline");
        }
        return failures;
    }

    private static String render(JsonNode row) throws IOException {
        List<String> keys = new ArrayList<>();
        row.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.comparingInt((String k) -> {
            int i = KEY_ORDER.indexOf(k);
            return i < 0 ? KEY_ORDER.size() : i;
        }).thenComparing(Comparator.naturalOrder()));
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            JsonNode value = row.get(key);
            String text;
            if (value.isArray()) {
                List<String> items = new ArrayList<>();
                for (JsonNode item : value) {
                    items.add(MAPPER.writeValueAsString(item.asText()));
                }
                text = "[" + String.join(", ", items) + "]";
            } else {
                text = MAPPER.writeValueAsString(value.asText());
            }
            parts.add(MAPPER.writeValueAsString(key) + ": " + text);
        }
        return "{" + String.join(", ", parts) + "}";
    }

    // ---- row shape -----------------------------------------------------------------------------

    private static List<String> rowShape() {
        List<String> failures = new ArrayList<>();
        Set<String> typeLevel = typeLevelNames();
        Map<String, Set<String>> onByAnnotation = new TreeMap<>();
        for (JsonNode row : rows) {
            String where = describe(row);
            String state = row.path("state").asText();
            if (!STATES.contains(state)) {
                failures.add(where + ": state '" + state + "' is not one of " + STATES);
                continue;
            }
            String required = STATE_KEY.get(state);
            Set<String> allowed = new HashSet<>(List.of("annotation", "attribute", "on", "state", "note"));
            if (required != null) {
                allowed.add(required);
                if (!row.hasNonNull(required)) {
                    failures.add(where + ": state " + state + " requires '" + required + "'");
                }
            }
            row.fieldNames().forEachRemaining(key -> {
                if (!allowed.contains(key)) {
                    failures.add(where + ": state " + state + " does not take the key '" + key + "'");
                }
            });
            if (row.has("note") && (!row.get("note").isTextual() || row.get("note").asText().contains("\n"))) {
                failures.add(where + ": note must be one line of text");
            }
            if ("EMITTED".equals(state) && row.hasNonNull("emittedBy")) {
                JsonNode emittedBy = row.get("emittedBy");
                List<String> values = new ArrayList<>();
                emittedBy.forEach(v -> values.add(v.asText()));
                if (!emittedBy.isArray() || values.isEmpty()) {
                    failures.add(where + ": emittedBy must be a non-empty array");
                } else if (!values.equals(values.stream().sorted().distinct().toList())) {
                    failures.add(where + ": emittedBy must be sorted and without duplicates");
                }
            }
            if (row.has("on")) {
                String on = row.path("on").asText();
                String simple = simpleName(row.path("annotation").asText());
                if (row.has("attribute") || !typeLevel.contains(simple)) {
                    failures.add(where + ": 'on' belongs only to the annotation-level rows of an annotation in"
                            + " TYPE_LEVEL_EXTRACTION");
                } else if (!List.of("TYPE", "NON_TYPE").contains(on)) {
                    failures.add(where + ": on must be TYPE or NON_TYPE");
                } else {
                    onByAnnotation.computeIfAbsent(row.path("annotation").asText(), k -> new TreeSet<>()).add(on);
                }
            }
        }
        for (JsonNode annotation : annotations.values()) {
            String q = annotation.path("qualifiedName").asText();
            if (typeLevel.contains(simpleName(q)) && !containers.contains(q)
                    && !onByAnnotation.getOrDefault(q, Set.of()).equals(Set.of("TYPE", "NON_TYPE"))) {
                failures.add(q + ": an annotation in TYPE_LEVEL_EXTRACTION needs an annotation-level row for"
                        + " both TYPE and NON_TYPE");
            }
        }
        return failures;
    }

    // ---- census --------------------------------------------------------------------------------

    private static List<String> census() {
        List<String> failures = new ArrayList<>();
        Set<String> typeLevel = typeLevelNames();
        Map<String, String> actualKeys = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String key = key(row);
            if (actualKeys.put(key, row.path("state").asText()) != null) {
                failures.add(key + ": more than one row");
            }
        }
        Set<String> expected = new TreeSet<>();
        for (JsonNode annotation : annotations.values()) {
            String q = annotation.path("qualifiedName").asText();
            if (containers.contains(q)) {
                continue;
            }
            List<String> annotationKeys = typeLevel.contains(simpleName(q))
                    ? List.of(key(q, null, "NON_TYPE"), key(q, null, "TYPE")) : List.of(key(q, null, null));
            String state = null;
            for (String annotationKey : annotationKeys) {
                expected.add(annotationKey);
                String found = actualKeys.get(annotationKey);
                if (found == null) {
                    failures.add("missing row, paste: " + pasteRow(q, null, annotationKey.contains("NON_TYPE")
                            ? "NON_TYPE" : annotationKey.contains("TYPE") ? "TYPE" : null));
                } else if (state == null) {
                    state = found;
                }
            }
            if (state != null && INHERITING_STATES.contains(state)) {
                continue;
            }
            for (JsonNode attribute : annotation.path("attributes")) {
                String name = attribute.path("name").asText();
                String attributeKey = key(q, name, null);
                expected.add(attributeKey);
                if (!actualKeys.containsKey(attributeKey)) {
                    failures.add("missing row, paste: " + pasteRow(q, name, null));
                }
            }
        }
        for (String actualKey : actualKeys.keySet()) {
            if (!expected.contains(actualKey)) {
                failures.add("stale row: " + actualKey + " is not a pair of the catalogue, is under an"
                        + " annotation whose state it inherits, or belongs to a repeatable container");
            }
        }
        return failures;
    }

    private static String pasteRow(String annotation, String attribute, String on) throws IllegalStateException {
        try {
            StringBuilder row = new StringBuilder("{\"annotation\": " + MAPPER.writeValueAsString(annotation));
            if (attribute != null) {
                row.append(", \"attribute\": ").append(MAPPER.writeValueAsString(attribute));
            }
            if (on != null) {
                row.append(", \"on\": \"").append(on).append('"');
            }
            return row.append(", \"state\": \"OPEN\", \"planRow\": \"").append(PLACEHOLDER_PLAN_ROW)
                    .append("\"}").toString();
        } catch (IOException e) {
            throw new IllegalStateException(e.toString(), e);
        }
    }

    // ---- SDK_REMOVED against forRemoval --------------------------------------------------------

    private static List<String> removalMarks() {
        List<String> failures = new ArrayList<>();
        Map<String, JsonNode> byKey = rowsByKey();
        for (JsonNode annotation : annotations.values()) {
            String q = annotation.path("qualifiedName").asText();
            boolean annotationRemoved = removedAnnotation(q);
            for (JsonNode row : rows) {
                if (!row.path("annotation").asText().equals(q)) {
                    continue;
                }
                boolean isRemoved = "SDK_REMOVED".equals(row.path("state").asText());
                boolean attributeLevel = row.has("attribute");
                boolean catalogueRemoved = attributeLevel
                        ? attributeRemoved(annotation, row.path("attribute").asText())
                        : annotationRemoved;
                if (isRemoved && !catalogueRemoved) {
                    failures.add(describe(row) + ": SDK_REMOVED, but the catalogue does not mark it"
                            + " deprecated.forRemoval");
                }
            }
            List<JsonNode> annotationRows = annotationRows(byKey, q);
            if (annotationRemoved && !containers.contains(q)) {
                for (JsonNode row : annotationRows) {
                    if (!"SDK_REMOVED".equals(row.path("state").asText())) {
                        failures.add(describe(row) + ": the catalogue marks it forRemoval, so its row must be"
                                + " SDK_REMOVED");
                    }
                }
            }
            boolean inherits = !annotationRows.isEmpty() && annotationRows.stream()
                    .allMatch(row -> INHERITING_STATES.contains(row.path("state").asText()));
            if (!inherits) {
                for (JsonNode attribute : annotation.path("attributes")) {
                    String name = attribute.path("name").asText();
                    JsonNode row = byKey.get(key(q, name, null));
                    if (row != null && attributeRemoved(annotation, name)
                            && !"SDK_REMOVED".equals(row.path("state").asText())) {
                        failures.add(describe(row) + ": the catalogue marks it forRemoval, so its row must be"
                                + " SDK_REMOVED");
                    }
                }
            }
        }
        return failures;
    }

    private static boolean removedAnnotation(String qualifiedName) {
        JsonNode annotation = annotations.get(qualifiedName);
        if (annotation != null && annotation.path("deprecated").path("forRemoval").asBoolean()) {
            return true;
        }
        int dot = qualifiedName.lastIndexOf('.');
        String parent = qualifiedName.substring(0, dot);
        return annotation != null && annotation.path("nested").asBoolean() && annotations.containsKey(parent)
                && removedAnnotation(parent);
    }

    private static boolean attributeRemoved(JsonNode annotation, String attribute) {
        for (JsonNode candidate : annotation.path("attributes")) {
            if (candidate.path("name").asText().equals(attribute)) {
                return candidate.path("deprecated").path("forRemoval").asBoolean();
            }
        }
        return false;
    }

    // ---- Preview list --------------------------------------------------------------------------

    private static List<String> previewList() throws IOException {
        List<String> failures = new ArrayList<>();
        JsonNode transcript = MAPPER.readTree(resource("sdk-preview-surfaces.json"));
        if (hasStabilityField(catalogue)) {
            failures.add("the SDK catalogue carries a stability field: read it here and delete"
                    + " sdk-preview-surfaces.json");
        }
        if (!catalogueVersion.equals(transcript.path("sdkVersion").asText())) {
            failures.add("sdk-preview-surfaces.json is stamped " + transcript.path("sdkVersion").asText()
                    + " but the catalogue is " + catalogueVersion + ": re-read the SDK Preview list and re-stamp");
        }
        if (transcript.path("source").asText().isBlank()) {
            failures.add("sdk-preview-surfaces.json needs a source");
        }
        Set<String> surfaces = new TreeSet<>();
        for (JsonNode surface : transcript.path("surfaces")) {
            String q = surface.path("annotation").asText();
            String attribute = surface.has("attribute") ? surface.path("attribute").asText() : null;
            JsonNode annotation = annotations.get(q);
            if (annotation == null || (attribute != null && !declares(annotation, attribute))) {
                failures.add("Preview surface " + key(q, attribute, null) + " is not in the catalogue");
            }
            surfaces.add(key(q, attribute, null));
        }
        Map<String, JsonNode> byKey = rowsByKey();
        for (JsonNode row : rows) {
            if ("OUTSIDE_FREEZE".equals(row.path("state").asText())) {
                String q = row.path("annotation").asText();
                String attribute = row.has("attribute") ? row.path("attribute").asText() : null;
                if (!surfaces.contains(key(q, attribute, null)) && !surfaces.contains(key(q, null, null))) {
                    failures.add(describe(row) + ": OUTSIDE_FREEZE, but the SDK Preview list does not name it");
                }
            }
        }
        for (String surface : surfaces) {
            String[] parts = surface.split("#", 2);
            JsonNode own = parts.length == 2 ? byKey.get(surface) : null;
            List<JsonNode> annotationRows = annotationRows(byKey, parts[0]);
            boolean known = annotations.containsKey(parts[0]) && !containers.contains(parts[0]);
            if (own == null && annotationRows.isEmpty()) {
                if (known) {
                    failures.add("Preview surface " + surface + " has no row; it must be OUTSIDE_FREEZE or REFUSED");
                }
            } else if (!isPreviewState(own)
                    && annotationRows.stream().noneMatch(AnnotationDispositionRegistryTest::isPreviewState)) {
                failures.add("Preview surface " + surface + " must be OUTSIDE_FREEZE or REFUSED");
            }
        }
        return failures;
    }

    private static boolean isPreviewState(JsonNode row) {
        return row != null && Set.of("OUTSIDE_FREEZE", "REFUSED").contains(row.path("state").asText());
    }

    private static boolean hasStabilityField(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("stability".equals(field.getKey()) || hasStabilityField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (hasStabilityField(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- references ----------------------------------------------------------------------------

    private static List<String> references() throws IOException {
        List<String> failures = new ArrayList<>();
        Set<String> generatorIds = generatorRowIds();
        String tsTable = tsGeneratorTable(failures);
        Set<String> diagnostics = new TreeSet<>();
        Arrays.stream(DiagnosticId.values()).forEach(d -> diagnostics.add(d.code()));
        PlanRows plan = planRows();
        for (JsonNode row : rows) {
            String where = describe(row);
            if (row.has("emittedBy") && row.get("emittedBy").isArray()) {
                for (JsonNode reference : row.get("emittedBy")) {
                    String value = reference.asText();
                    if (value.startsWith("java:")) {
                        if (!generatorIds.contains(value.substring(5))) {
                            failures.add(where + ": " + value + " is not a row of generator-catalogue.json");
                        }
                    } else if (value.startsWith("ts:")) {
                        if (!tsTable.contains("`" + value.substring(3) + "`")) {
                            failures.add(where + ": " + value + " is not a generator in docs/generators.md");
                        }
                    } else {
                        failures.add(where + ": emittedBy entry '" + value + "' is neither java:<id> nor ts:<path>");
                    }
                }
            }
            if (row.has("diagnostic") && !diagnostics.contains(row.path("diagnostic").asText())) {
                failures.add(where + ": " + row.path("diagnostic").asText() + " is not a DiagnosticId code");
            }
            if (row.has("ruling") && row.path("ruling").asText().isBlank()) {
                failures.add(where + ": ruling is blank");
            }
            if (row.has("planRow") && !plan.contains(row.path("planRow").asText())) {
                failures.add(where + ": planRow '" + row.path("planRow").asText() + "' names no row or list of"
                        + " docs/1.0.0-annotation-coverage-plan.md");
            }
        }
        return failures;
    }

    private static Set<String> generatorRowIds() throws IOException {
        JsonNode generators = MAPPER.readTree(Files.readString(GENERATOR_CATALOGUE, StandardCharsets.UTF_8));
        Set<String> retired = new TreeSet<>();
        JsonNode retiredNode = generators.path("retired");
        if (retiredNode.isObject()) {
            retiredNode.fieldNames().forEachRemaining(retired::add);
        } else {
            retiredNode.forEach(r -> retired.add(r.path("id").asText()));
        }
        Set<String> ids = new TreeSet<>();
        generators.path("rows").forEach(r -> ids.add(r.path("id").asText()));
        ids.removeAll(retired);
        return ids;
    }

    /** The TypeScript generator table of {@code docs/generators.md}; empty, with a failure, when its markers are missing. */
    private static String tsGeneratorTable(List<String> failures) throws IOException {
        String doc = Files.readString(GENERATORS_DOC, StandardCharsets.UTF_8);
        int begin = doc.indexOf(TS_BEGIN);
        int end = doc.indexOf(TS_END);
        if (begin < 0 || end <= begin) {
            failures.add("docs/generators.md must hold " + TS_BEGIN + " before " + TS_END);
            return "";
        }
        return doc.substring(begin, end);
    }

    /** The rows and lists of the coverage plan that a {@code planRow} may name. */
    private record PlanRows(Set<String> rows012, Set<String> rows013, Set<String> decisions, boolean withdraw,
                            boolean outsideFreeze) {

        boolean contains(String planRow) {
            if ("withdraw".equals(planRow)) {
                return withdraw;
            }
            if ("outside-freeze".equals(planRow)) {
                return outsideFreeze;
            }
            if (planRow.startsWith("0.12.0/")) {
                return rows012.contains(planRow.substring(7));
            }
            if (planRow.startsWith("0.13.0/")) {
                return rows013.contains(planRow.substring(7));
            }
            return decisions.contains(planRow);
        }
    }

    private static PlanRows planRows() throws IOException {
        String plan = Files.readString(COVERAGE_PLAN, StandardCharsets.UTF_8);
        Pattern rowId = Pattern.compile("^\\| \\*\\*([^*]+)\\*\\* \\|", Pattern.MULTILINE);
        Pattern decision = Pattern.compile("^\\| (W0-\\d+) \\|", Pattern.MULTILINE);
        Set<String> rows012 = new TreeSet<>();
        Set<String> rows013 = new TreeSet<>();
        String[] sections = plan.split("(?m)^## ");
        for (String section : sections) {
            Set<String> target = section.startsWith("0.12.0") ? rows012
                    : section.startsWith("0.13.0") ? rows013 : null;
            if (target == null) {
                continue;
            }
            Matcher m = rowId.matcher(section);
            while (m.find()) {
                target.addAll(Arrays.asList(m.group(1).split(" \\+ ")));
            }
        }
        Set<String> decisions = new TreeSet<>();
        Matcher d = decision.matcher(plan);
        while (d.find()) {
            decisions.add(d.group(1));
        }
        return new PlanRows(rows012, rows013, decisions, plan.contains("\n## SDK withdrawal list"),
                plan.contains("\n## Outside-freeze asks"));
    }

    // ---- agreement with the strict-audit registries --------------------------------------------

    private static Set<String> agreement() throws ReflectiveOperationException {
        Set<String> disagreements = new TreeSet<>();
        Map<String, Set<String>> inertAttributes = new TreeMap<>();
        for (Object entry : registry("INERT_ATTRIBUTES")) {
            inertAttributes.computeIfAbsent(component(entry, "annotation"), k -> new TreeSet<>())
                    .add(component(entry, "attribute"));
        }
        Set<String> inertAnnotations = new TreeSet<>();
        for (Object entry : registry("INERT_ANNOTATIONS")) {
            inertAnnotations.add(component(entry, "fqn"));
        }
        Set<String> extracted = new TreeSet<>(registryNames("EXTRACTED_ANNOTATIONS"));
        Set<String> typeLevel = typeLevelNames();
        Map<String, JsonNode> byKey = rowsByKey();

        for (JsonNode row : rows) {
            if (row.has("attribute")) {
                continue;
            }
            String q = row.path("annotation").asText();
            String simple = simpleName(q);
            String state = row.path("state").asText();
            String on = row.path("on").asText(null);
            boolean nonType = "NON_TYPE".equals(on);
            if ("EMITTED".equals(state)) {
                if (!extracted.contains(simple)) {
                    disagreements.add(RULE_NOT_EXTRACTED + " " + display(q));
                }
                if (inertAnnotations.contains(q)) {
                    disagreements.add(RULE_INERT_ANNOTATION + " " + display(q));
                }
                for (JsonNode attribute : annotations.get(q).path("attributes")) {
                    String name = attribute.path("name").asText();
                    JsonNode attributeRow = byKey.get(key(q, name, null));
                    if (attributeRow == null) {
                        continue;
                    }
                    boolean registered = inertAttributes.getOrDefault(simple, Set.of()).contains(name);
                    boolean emitted = "EMITTED".equals(attributeRow.path("state").asText());
                    if (emitted && registered) {
                        disagreements.add(RULE_STALE_ENTRY + " " + display(q) + "#" + name);
                    } else if (!emitted && !registered
                            && ENTRY_REQUIRED_STATES.contains(attributeRow.path("state").asText())) {
                        disagreements.add(RULE_UNREGISTERED + " " + display(q) + "#" + name);
                    }
                }
            } else if (STRICT_REPORTED_STATES.contains(state)) {
                boolean reported = nonType ? typeLevel.contains(simple)
                        : inertAnnotations.contains(q) || !extracted.contains(simple);
                if (!reported) {
                    disagreements.add(RULE_NOT_REPORTED + " " + display(q));
                }
            }
        }
        for (Object entry : registry("UNREAD_NOTES")) {
            String simple = component(entry, "display");
            for (JsonNode annotation : annotations.values()) {
                String q = annotation.path("qualifiedName").asText();
                if (!simpleName(q).equals(simple) || containers.contains(q)) {
                    continue;
                }
                JsonNode row = typeLevel.contains(simple) ? byKey.get(key(q, null, "NON_TYPE"))
                        : byKey.get(key(q, null, null));
                boolean extractedThere = extracted.contains(simple) && !typeLevel.contains(simple);
                if ((row != null && "EMITTED".equals(row.path("state").asText())) || extractedThere) {
                    disagreements.add(RULE_NOTE + " " + display(q));
                }
            }
        }
        return disagreements;
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Set<String> typeLevelNames() {
        try {
            return new TreeSet<>(registryNames("TYPE_LEVEL_EXTRACTION"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e.toString(), e);
        }
    }

    /**
     * The annotation-level rows of {@code qualifiedName}: the single row, or the {@code TYPE} and
     * {@code NON_TYPE} rows of an annotation in {@code TYPE_LEVEL_EXTRACTION}.
     */
    private static List<JsonNode> annotationRows(Map<String, JsonNode> byKey, String qualifiedName) {
        List<JsonNode> found = new ArrayList<>();
        for (String on : new String[] {null, "TYPE", "NON_TYPE"}) {
            JsonNode row = byKey.get(key(qualifiedName, null, on));
            if (row != null) {
                found.add(row);
            }
        }
        return found;
    }

    private static Map<String, JsonNode> rowsByKey() {
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        rows.forEach(row -> byKey.putIfAbsent(key(row), row));
        return byKey;
    }

    private static boolean declares(JsonNode annotation, String attribute) {
        for (JsonNode candidate : annotation.path("attributes")) {
            if (candidate.path("name").asText().equals(attribute)) {
                return true;
            }
        }
        return false;
    }

    private static String key(JsonNode row) {
        return key(row.path("annotation").asText(), row.has("attribute") ? row.path("attribute").asText() : null,
                row.has("on") ? row.path("on").asText() : null);
    }

    private static String key(String annotation, String attribute, String on) {
        return annotation + (attribute != null ? "#" + attribute : "") + (on != null ? " on " + on : "");
    }

    private static String describe(JsonNode row) {
        return key(row);
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    /** The annotation as the SDK package declares it: {@code Saga.SagaTrigger}, {@code security.Encrypted}. */
    private static String display(String qualifiedName) {
        return qualifiedName.substring("eu.exeris.sdk.annotation.".length());
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = AnnotationDispositionRegistryTest.class.getResourceAsStream(RESOURCE_DIR + name)) {
            assertThat(in).as("test resource %s%s", RESOURCE_DIR, name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> registry(String fieldName) throws ReflectiveOperationException {
        java.lang.reflect.Field field = ExerisDomainProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (List<Object>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> registryNames(String fieldName) throws ReflectiveOperationException {
        java.lang.reflect.Field field = ExerisDomainProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Collection<String>) field.get(null);
    }

    private static String component(Object entry, String name) throws ReflectiveOperationException {
        for (RecordComponent component : entry.getClass().getRecordComponents()) {
            if (component.getName().equals(name)) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                return (String) accessor.invoke(entry);
            }
        }
        throw new NoSuchFieldException(entry.getClass().getName() + " has no record component '" + name + "'");
    }
}
