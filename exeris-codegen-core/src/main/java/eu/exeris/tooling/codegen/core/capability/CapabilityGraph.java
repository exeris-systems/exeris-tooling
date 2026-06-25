package eu.exeris.tooling.codegen.core.capability;

import eu.exeris.sdk.sourcemodel.ast.CapabilityModuleMetadata;
import eu.exeris.sdk.sourcemodel.ast.ProvidesMetadata;
import eu.exeris.sdk.sourcemodel.ast.RequiresMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The resolved capability composition graph — a <b>build-time, platform-side</b>
 * artifact. Nodes are {@code @CapabilityModule} classes; an edge {@code A → B} means
 * module A {@code @Requires} a service that module B {@code @Provides}.
 *
 * <p>This is deliberately <em>not</em> a kernel concern: {@code @Provides}/{@code @Requires}
 * model platform composition / SKU / mesh, and the dependency direction is platform → kernel,
 * never the reverse. The graph is resolved and validated here, at build time, and serialized to
 * {@code cap-manifest.json} as the platform registry input (feeds the cross-app mesh contract,
 * roadmap T12). No runtime wiring into the kernel is emitted or implied.
 *
 * <p>{@link #build(List)} resolves every {@code @Requires} against the providers, then fails the
 * build (via {@link CapabilityGraphException}) on any unsatisfied non-optional requirement, version
 * mismatch, or dependency cycle. An unsatisfied <em>optional</em> requirement is a warning, not an
 * error. Output is fully deterministic (all collections sorted) so the manifest is byte-stable.
 *
 * @since 0.5.0
 */
public record CapabilityGraph(
        int schemaVersion,
        CompositionStamp stamp,
        List<CapabilityModuleDescriptor> modules,
        List<Resolution> resolutions,
        List<String> initOrder,
        List<String> warnings
) {

    /**
     * Every graph is produced by {@link #build} on the validation-success path, so it is
     * always stamped. The guard makes that invariant explicit — a {@code null} stamp (e.g.
     * a stampless v1 manifest deserialized into this record) fails fast at construction
     * rather than NPE-ing later in {@code stamp().validated()}.
     */
    public CapabilityGraph {
        Objects.requireNonNull(stamp, "stamp");
    }

    /**
     * Manifest schema version. Any breaking shape change to the serialized manifest —
     * a new/removed/renamed record component on {@link CapabilityGraph} or
     * {@link Resolution}, or a changed field meaning — must bump this and ship a
     * migration note for {@code cap-manifest.json} consumers (e.g. the T12 registry,
     * and the platform composition runtime that asserts {@link CompositionStamp}).
     *
     * <p>v2 (0.6.0) adds the ADR-024 {@link CompositionStamp} (validated verdict +
     * composition version + content binding) the platform composition runtime asserts.
     */
    public static final int SCHEMA_VERSION = 2;

    /**
     * One resolved {@code @Requires} edge.
     *
     * @param module       requiring module's qualified name
     * @param service      required service FQN
     * @param versionRange declared range, or {@code null}
     * @param optional     whether the requirement is optional
     * @param satisfied    whether at least one provider matched
     * @param providers    qualified names of modules whose provided version matched (sorted)
     */
    public record Resolution(
            String module,
            String service,
            String versionRange,
            boolean optional,
            boolean satisfied,
            List<String> providers
    ) {}

    private record Provider(String module, String version) {}

    /**
     * Resolves and validates the graph, stamping it as {@link CompositionStamp#UNVERSIONED}.
     * Equivalent to {@link #build(List, String)} with no composition version supplied.
     *
     * @param input the capability module descriptors (any order)
     * @return the resolved, deterministic, stamped graph
     * @throws CapabilityGraphException on unsatisfied requirement / version mismatch / cycle
     */
    public static CapabilityGraph build(List<CapabilityModuleDescriptor> input) {
        return build(input, CompositionStamp.UNVERSIONED);
    }

    /**
     * Resolves and validates the graph, then stamps it with the ADR-024
     * {@link CompositionStamp} (obligation 7). The stamp is computed only on the
     * validation-success path — an unsatisfied non-optional requirement, version mismatch,
     * or cycle throws before any stamp is produced, so an emitted manifest is always
     * {@code validated:true} over a content-bound cap set.
     *
     * @param input              the capability module descriptors (any order)
     * @param compositionVersion the composition release identity (the
     *                           {@code -Dexeris.composition.version} build seam), or
     *                           {@code null}/blank for {@link CompositionStamp#UNVERSIONED}
     * @return the resolved, deterministic, stamped graph
     * @throws CapabilityGraphException on unsatisfied requirement / version mismatch / cycle
     */
    public static CapabilityGraph build(List<CapabilityModuleDescriptor> input, String compositionVersion) {
        List<CapabilityModuleDescriptor> modules = new ArrayList<>(input);
        modules.sort(Comparator.comparing(CapabilityModuleDescriptor::qualifiedName));

        // service FQN -> providers (sorted by module qName)
        Map<String, List<Provider>> providersByService = new TreeMap<>();
        for (CapabilityModuleDescriptor m : modules) {
            CapabilityModuleMetadata body = m.moduleOrEmpty();
            if (body.provides() == null) {
                continue;
            }
            for (ProvidesMetadata p : body.provides()) {
                providersByService
                        .computeIfAbsent(p.service(), k -> new ArrayList<>())
                        .add(new Provider(m.qualifiedName(), p.version()));
            }
        }
        for (List<Provider> ps : providersByService.values()) {
            ps.sort(Comparator.comparing(Provider::module));
        }

        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Resolution> resolutions = new ArrayList<>();
        // depends-on adjacency for cycle detection + topo order (requirer -> provider)
        Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
        for (CapabilityModuleDescriptor m : modules) {
            dependsOn.put(m.qualifiedName(), new LinkedHashSet<>());
        }

        for (CapabilityModuleDescriptor m : modules) {
            CapabilityModuleMetadata body = m.moduleOrEmpty();
            if (body.requires() == null) {
                continue;
            }
            for (RequiresMetadata r : body.requires()) {
                VersionRange range = VersionRange.parse(r.versionRange());
                List<Provider> candidates = providersByService.getOrDefault(r.service(), List.of());
                List<String> matched = new ArrayList<>();
                for (Provider p : candidates) {
                    if (range.matches(p.version())) {
                        matched.add(p.module());
                        // self-provision is fine; do not add a self-edge (no spurious cycle)
                        if (!p.module().equals(m.qualifiedName())) {
                            dependsOn.get(m.qualifiedName()).add(p.module());
                        }
                    }
                }
                boolean satisfied = !matched.isEmpty();
                resolutions.add(new Resolution(
                        m.qualifiedName(), r.service(), r.versionRange(), r.optional(),
                        satisfied, List.copyOf(matched)));

                if (!satisfied) {
                    String detail = describeUnsatisfied(m.qualifiedName(), r, candidates);
                    if (r.optional()) {
                        warnings.add(detail + " (optional — skipped)");
                    } else {
                        problems.add(detail);
                    }
                }
            }
        }

        problems.addAll(detectCycles(modules, dependsOn));

        if (!problems.isEmpty()) {
            throw new CapabilityGraphException(problems);
        }

        resolutions.sort(Comparator.comparing(Resolution::module).thenComparing(Resolution::service));
        warnings.sort(Comparator.naturalOrder());
        List<String> initOrder = topoOrder(modules, dependsOn);

        // Validation passed (no throw above) → stamp the resolved, sorted cap set.
        CompositionStamp stamp = CompositionStamp.of(modules, compositionVersion);

        return new CapabilityGraph(SCHEMA_VERSION, stamp, List.copyOf(modules),
                List.copyOf(resolutions), List.copyOf(initOrder), List.copyOf(warnings));
    }

    private static String describeUnsatisfied(String module, RequiresMetadata r, List<Provider> candidates) {
        StringBuilder sb = new StringBuilder()
                .append("module ").append(module)
                .append(" @Requires service ").append(r.service());
        if (r.versionRange() != null && !r.versionRange().isBlank()) {
            sb.append(" version ").append(r.versionRange());
        }
        if (candidates.isEmpty()) {
            sb.append(" but no @CapabilityModule provides it");
        } else {
            sb.append(" but no provider matches (providers: ");
            for (int i = 0; i < candidates.size(); i++) {
                Provider p = candidates.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(p.module()).append('=').append(p.version() == null ? "(unversioned)" : p.version());
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /** DFS cycle detection (white/grey/black); returns one message per distinct cycle found. */
    private static List<String> detectCycles(List<CapabilityModuleDescriptor> modules,
                                             Map<String, Set<String>> dependsOn) {
        List<String> cycles = new ArrayList<>();
        Map<String, Integer> color = new LinkedHashMap<>();   // 0 white, 1 grey, 2 black
        for (CapabilityModuleDescriptor m : modules) {
            color.put(m.qualifiedName(), 0);
        }
        for (CapabilityModuleDescriptor m : modules) {
            if (color.get(m.qualifiedName()) == 0) {
                dfsCycle(m.qualifiedName(), dependsOn, color, new ArrayList<>(), cycles);
            }
        }
        return cycles;
    }

    private static void dfsCycle(String node, Map<String, Set<String>> dependsOn,
                                 Map<String, Integer> color, List<String> stack, List<String> cycles) {
        color.put(node, 1);
        stack.add(node);
        for (String next : dependsOn.getOrDefault(node, Set.of())) {
            Integer c = color.get(next);
            if (c != null && c == 1) {
                int from = stack.indexOf(next);
                List<String> cyc = new ArrayList<>(stack.subList(from, stack.size()));
                cyc.add(next);
                cycles.add("dependency cycle: " + String.join(" -> ", cyc));
            } else if (c != null && c == 0) {
                dfsCycle(next, dependsOn, color, stack, cycles);
            }
        }
        stack.remove(stack.size() - 1);
        color.put(node, 2);
    }

    /** Post-order DFS over depends-on edges → dependencies (providers) appear before dependents. */
    private static List<String> topoOrder(List<CapabilityModuleDescriptor> modules,
                                          Map<String, Set<String>> dependsOn) {
        List<String> order = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (CapabilityModuleDescriptor m : modules) {
            topoVisit(m.qualifiedName(), dependsOn, visited, order);
        }
        return order;
    }

    private static void topoVisit(String node, Map<String, Set<String>> dependsOn,
                                  Set<String> visited, List<String> order) {
        if (!visited.add(node)) {
            return;
        }
        // neighbours iterated in sorted order for determinism
        List<String> neighbours = new ArrayList<>(dependsOn.getOrDefault(node, Set.of()));
        neighbours.sort(Comparator.naturalOrder());
        for (String next : neighbours) {
            topoVisit(next, dependsOn, visited, order);
        }
        order.add(node);
    }
}
