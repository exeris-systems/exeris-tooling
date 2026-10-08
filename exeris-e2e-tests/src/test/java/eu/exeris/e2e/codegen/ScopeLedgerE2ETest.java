package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scope lists {@code RuntimeComponents} publishes agree with what the generated code
 * actually reads, in both directions.
 *
 * <p>{@code COMPOSITION_SCOPES} and {@code REQUEST_SCOPES} exist so a harness composing outside a
 * kernel boot binds everything in one pass instead of discovering the set one failed request at a
 * time. A list like that is worth exactly as much as its accuracy, and it is derived by the emitter
 * from the branches that emit each read — so this test does not trust the derivation. It builds a
 * real corpus (annotated sources → processor → pipeline → {@code javac}), scans every emitted main
 * source for {@code KernelProviders.X} / {@code HttpKernelProviders.X} reads — constants and their
 * accessor methods, with comments and string literals stripped so Javadoc samples and error
 * messages do not count — resolves each to the real {@code ScopedValue} constant, and compares that
 * set with the lists read off the loaded class:
 *
 * <ul>
 *   <li><b>every read is accounted for</b> — listed, or on the allowlist below with the one file
 *       allowed to read it, or the optional codec registry named in the list's Javadoc;</li>
 *   <li><b>every listed scope is read</b> — a list that asks a harness to bind something nothing
 *       reads is as wrong as one that omits a read;</li>
 *   <li><b>the phase is right</b> — a composition scope is read by {@code RuntimeComponents}; a
 *       request scope by the code that serves a request, never by the bootstrap files.</li>
 * </ul>
 *
 * <p>The corpora cover each feature that brings a scope in — events, a stream, a saga, a tenant —
 * alone and together, and the plain entity that brings in only the two every app reads. The
 * emitted <em>test</em> tree is out of scope: it binds its own scopes.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("T51 — RuntimeComponents' scope lists match every kernel scope the generated code reads")
class ScopeLedgerE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.ledger";

    /** Scopes emitted code may read without being listed, and the one file allowed to. */
    private static final Map<String, String> ALLOWLIST = Map.of(
            // The default TransactionalExecutor — Application.transactionalExecutor() — reads it
            // inside the boot callback. A harness supplies its own executor instead.
            "KernelProviders.PERSISTENCE_ENGINE", "Application.java",
            // Bound by Application around the boot, not read.
            "HttpKernelProviders.HTTP_SERVER_HANDLER", "Application.java",
            "HttpKernelProviders.HTTP_ROUTE_POLICY", "Application.java");

    /** Read at composition, but optional: unbound, payloads publish empty. Named, not listed. */
    private static final String OPTIONAL = "KernelProviders.EVENT_PAYLOAD_CODEC_REGISTRY";

    private static final Set<String> BOOTSTRAP_FILES =
            Set.of("Application.java", "RuntimeComponents.java", "RuntimeLifecycle.java");

    private static final Pattern READ = Pattern.compile(
            "\\b(HttpKernelProviders|KernelProviders)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)");

    /** The lists themselves name every scope they list — that is a declaration, not a read. */
    private static final Pattern LIST_DECLARATION = Pattern.compile(
            "\\b(COMPOSITION_SCOPES|REQUEST_SCOPES)\\s*=\\s*List\\.of\\([^;]*\\);");

    record Corpus(String name, Map<String, String> sources,
                  List<String> composition, List<String> request) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Corpus> corpora() {
        return Stream.of(
                new Corpus("events + stream + saga + tenant", merge(beacon(), ledger()),
                        List.of("KernelProviders.MEMORY_ALLOCATOR", "KernelProviders.EVENT_ENGINE",
                                "KernelProviders.FLOW_ENGINE"),
                        List.of("HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY",
                                "KernelProviders.STORAGE_CONTEXT")),
                new Corpus("plain global entity", tag(),
                        List.of("KernelProviders.MEMORY_ALLOCATOR"),
                        List.of("HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY")),
                new Corpus("saga without events", voyage(),
                        List.of("KernelProviders.MEMORY_ALLOCATOR", "KernelProviders.FLOW_ENGINE"),
                        List.of("HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY")),
                new Corpus("stream without events (keep-alive)", pulse(),
                        List.of("KernelProviders.MEMORY_ALLOCATOR"),
                        List.of("HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY")),
                new Corpus("tenant only", ledger(),
                        List.of("KernelProviders.MEMORY_ALLOCATOR"),
                        List.of("HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY",
                                "KernelProviders.STORAGE_CONTEXT")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpora")
    @DisplayName("every read is listed or allowlisted, every listed scope is read, and each sits in its phase")
    void scopeListsMatchTheReads(Corpus corpus, @TempDir Path workspace) throws Exception {
        try (GeneratedTree tree = GeneratedTree.build(workspace, BASE_PACKAGE, corpus.sources(), Map.of())) {
            Map<String, Set<String>> reads = scanReads(tree);
            Class<?> components = tree.loader().loadClass(BASE_PACKAGE + ".RuntimeComponents");
            List<String> composition = names(scopeList(components, "COMPOSITION_SCOPES"));
            List<String> request = names(scopeList(components, "REQUEST_SCOPES"));
            String componentsSource = Files.readString(
                    tree.generatedRoot().resolve(BASE_PACKAGE.replace('.', '/') + "/RuntimeComponents.java"));

            // The expectation for this corpus — what the feature mix should bring in.
            assertThat(composition).as("COMPOSITION_SCOPES").containsExactlyElementsOf(corpus.composition());
            assertThat(request).as("REQUEST_SCOPES").containsExactlyElementsOf(corpus.request());
            assertThat(composition).as("a scope has one phase").doesNotContainAnyElementsOf(request);

            // Direction 1: every read is listed, allowlisted where allowed, or the named optional.
            for (Map.Entry<String, Set<String>> read : reads.entrySet()) {
                String scope = read.getKey();
                Set<String> files = read.getValue();
                if (composition.contains(scope) || request.contains(scope)) {
                    continue;
                }
                if (ALLOWLIST.containsKey(scope)) {
                    assertThat(files).as("%s is allowed only in %s", scope, ALLOWLIST.get(scope))
                            .allMatch(f -> f.endsWith("/" + ALLOWLIST.get(scope)));
                    continue;
                }
                assertThat(scope).as("unlisted read of %s in %s", scope, files).isEqualTo(OPTIONAL);
                assertThat(componentsSource).as("the optional %s is named in COMPOSITION_SCOPES' Javadoc", scope)
                        .contains("Optional, and so not listed: {@link KernelProviders#EVENT_PAYLOAD_CODEC_REGISTRY}");
            }

            // Direction 2: every listed scope is read, in the phase it is listed under.
            for (String scope : composition) {
                assertThat(reads).as("COMPOSITION_SCOPES lists %s, so something reads it", scope)
                        .containsKey(scope);
                assertThat(reads.get(scope)).as("%s is read by the factories on RuntimeComponents", scope)
                        .anyMatch(f -> f.endsWith("/RuntimeComponents.java"));
            }
            for (String scope : request) {
                assertThat(reads).as("REQUEST_SCOPES lists %s, so something reads it", scope)
                        .containsKey(scope);
                assertThat(reads.get(scope)).as("%s is read by the code that serves a request", scope)
                        .allMatch(f -> !BOOTSTRAP_FILES.contains(f.substring(f.lastIndexOf('/') + 1)));
            }
        }
    }

    // ------------------------------------------------------------------ scanner

    /** Scope name ({@code Holder.CONSTANT}) → the emitted files that read it. */
    private static Map<String, Set<String>> scanReads(GeneratedTree tree) throws IOException {
        Map<String, Set<String>> reads = new LinkedHashMap<>();
        for (Path file : tree.javaSources()) {
            String code = stripCommentsAndLiterals(Files.readString(file));
            if (file.endsWith("RuntimeComponents.java")) {
                Matcher lists = LIST_DECLARATION.matcher(code);
                int declarations = 0;
                StringBuilder withoutLists = new StringBuilder();
                while (lists.find()) {
                    lists.appendReplacement(withoutLists, "");
                    declarations++;
                }
                lists.appendTail(withoutLists);
                assertThat(declarations).as("both list declarations were found and set aside").isEqualTo(2);
                code = withoutLists.toString();
            }
            Matcher m = READ.matcher(code);
            while (m.find()) {
                String scope = m.group(1) + "." + constantName(m.group(2));
                requireScopedValueConstant(scope, file);
                reads.computeIfAbsent(scope, k -> new TreeSet<>())
                        .add(tree.generatedRoot().relativize(file).toString().replace('\\', '/'));
            }
        }
        assertThat(reads).as("the scan found reads at all").isNotEmpty();
        return reads;
    }

    /**
     * {@code eventEngine} → {@code EVENT_ENGINE}; a constant stays as it is. Every accessor on
     * both holders is named for its constant this way — and {@link #requireScopedValueConstant}
     * fails the test the day one is not, rather than letting the mapping guess.
     */
    private static String constantName(String member) {
        if (member.equals(member.toUpperCase(Locale.ROOT))) {
            return member;
        }
        return member.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static void requireScopedValueConstant(String scope, Path file) {
        String holder = scope.substring(0, scope.indexOf('.'));
        String constant = scope.substring(scope.indexOf('.') + 1);
        Class<?> holderType = holder.equals("HttpKernelProviders") ? HttpKernelProviders.class : KernelProviders.class;
        try {
            Field field = holderType.getField(constant);
            assertThat(Modifier.isStatic(field.getModifiers()) && field.getType() == ScopedValue.class)
                    .as("%s (read in %s) is a static ScopedValue", scope, file.getFileName()).isTrue();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(file.getFileName() + " reads " + scope
                    + ", which names no ScopedValue constant — extend constantName()", e);
        }
    }

    /**
     * Blanks out comments, string and text-block literals, and char literals, keeping line
     * structure — so a {@code KernelProviders.X} inside Javadoc or an error message is not a read.
     */
    static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = blank(source, out, i, end < 0 ? n : end + 2);
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = blank(source, out, i, end < 0 ? n : end + 3);
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = blank(source, out, i, Math.min(n, j + 1));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int blank(String source, StringBuilder out, int from, int to) {
        for (int k = from; k < to; k++) {
            out.append(source.charAt(k) == '\n' ? '\n' : ' ');
        }
        return to;
    }

    // ------------------------------------------------------------------ lists

    @SuppressWarnings("unchecked")
    private static List<ScopedValue<?>> scopeList(Class<?> components, String field) throws ReflectiveOperationException {
        return (List<ScopedValue<?>>) components.getField(field).get(null);
    }

    /** Each listed ScopedValue by its constant name, resolved by identity against both holders. */
    private static List<String> names(List<ScopedValue<?>> scopes) throws IllegalAccessException {
        Map<Object, String> byIdentity = new IdentityHashMap<>();
        for (Class<?> holder : List.of(KernelProviders.class, HttpKernelProviders.class)) {
            for (Field field : holder.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == ScopedValue.class) {
                    byIdentity.put(field.get(null), holder.getSimpleName() + "." + field.getName());
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (ScopedValue<?> scope : scopes) {
            String name = byIdentity.get(scope);
            assertThat(name).as("a listed scope is a kernel provider constant").isNotNull();
            names.add(name);
        }
        return names;
    }

    // ------------------------------------------------------------------ corpora

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        Map<String, String> all = new LinkedHashMap<>(a);
        all.putAll(b);
        return all;
    }

    /** Stream (EV1 producer + a streaming action), payload and plain events, a saga. */
    private static Map<String, String> beacon() {
        return Map.of("eu/exeris/e2e/ledger/domain/Beacon.java", """
                package eu.exeris.e2e.ledger.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Saga;
                import eu.exeris.sdk.annotation.SagaStep;

                import java.util.UUID;

                @ExerisDomain(module = "live", path = "/beacons", realTimeApi = true)
                @DomainEvent(name = "BeaconPinged", topic = "live.pinged", trigger = DomainEvent.Trigger.MANUAL,
                        includeFields = {"label"})
                @DomainEvent(name = "BeaconLit", topic = "live.lit", trigger = DomainEvent.Trigger.CREATE)
                @Saga(name = "BeaconSaga", timeout = "PT5M", maxRetries = 2)
                public class Beacon {
                    private UUID id;
                    @Field(label = "Label", required = true)
                    private String label;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    @Action(name = "track", label = "Track", streaming = true)
                    public void track() { }
                    @SagaStep(order = 0, name = "relay", service = "relay", command = "Relay")
                    public void relay() { }
                }
                """);
    }

    /** Tenant-partitioned, with the canonical system-field block. */
    private static Map<String, String> ledger() {
        return Map.of("eu/exeris/e2e/ledger/domain/Ledger.java", """
                package eu.exeris.e2e.ledger.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.time.Instant;
                import java.util.UUID;

                @ExerisDomain(module = "books", path = "/ledgers", dataScope = ExerisDomain.DataScope.TENANT,
                        versioned = true, audited = true)
                public class Ledger {
                    private UUID id;
                    @Field(label = "Name", required = true) private String name;
                    @Field(label = "Tenant") private UUID tenantId;
                    @Field(label = "Version") private long version;
                    @Field(label = "Created") private Instant createdAt;
                    @Field(label = "Updated") private Instant updatedAt;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public UUID getTenantId() { return tenantId; }
                    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
                    public long getVersion() { return version; }
                    public void setVersion(long version) { this.version = version; }
                    public Instant getCreatedAt() { return createdAt; }
                    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
                    public Instant getUpdatedAt() { return updatedAt; }
                    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
                }
                """);
    }

    private static Map<String, String> tag() {
        return Map.of("eu/exeris/e2e/ledger/domain/Tag.java", """
                package eu.exeris.e2e.ledger.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "catalog", path = "/tags")
                public class Tag {
                    private UUID id;
                    @Field(label = "Label", required = true) private String label;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                }
                """);
    }

    private static Map<String, String> voyage() {
        return Map.of("eu/exeris/e2e/ledger/domain/Voyage.java", """
                package eu.exeris.e2e.ledger.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Saga;
                import eu.exeris.sdk.annotation.SagaStep;

                import java.util.UUID;

                @ExerisDomain(module = "fleet", path = "/voyages")
                @Saga(name = "VoyageSaga", timeout = "PT10M", maxRetries = 1)
                public class Voyage {
                    private UUID id;
                    @Field(label = "Destination", required = true) private String destination;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getDestination() { return destination; }
                    public void setDestination(String destination) { this.destination = destination; }
                    @SagaStep(order = 0, name = "depart", service = "fleet", command = "Depart")
                    public void depart() { }
                }
                """);
    }

    /** realTimeApi with no @DomainEvent: the keep-alive handler, which subscribes to nothing. */
    private static Map<String, String> pulse() {
        return Map.of("eu/exeris/e2e/ledger/domain/Pulse.java", """
                package eu.exeris.e2e.ledger.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "live", path = "/pulses", realTimeApi = true)
                public class Pulse {
                    private UUID id;
                    @Field(label = "Rate", required = true) private String rate;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getRate() { return rate; }
                    public void setRate(String rate) { this.rate = rate; }
                }
                """);
    }
}
