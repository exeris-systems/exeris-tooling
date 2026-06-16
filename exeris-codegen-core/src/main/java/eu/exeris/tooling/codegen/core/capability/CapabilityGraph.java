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
        List<CapabilityModuleDescriptor> modules,
        List<Resolution> resolutions,
        List<String> initOrder,
        List<String> warnings
) {

    /** Manifest schema version — bump on any breaking shape change. */
    public static final int SCHEMA_VERSION = 1;

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
     * Resolves and validates the graph.
     *
     * @param input the capability module descriptors (any order)
     * @return the resolved, deterministic graph
     * @throws CapabilityGraphException on unsatisfied requirement / version mismatch / cycle
     */
    public static CapabilityGraph build(List<CapabilityModuleDescriptor> input) {
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

        return new CapabilityGraph(SCHEMA_VERSION, List.copyOf(modules),
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
