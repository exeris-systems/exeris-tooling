package eu.exeris.tooling.codegen.java;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.OutputWriter;
import eu.exeris.tooling.codegen.core.capability.CapabilityGraph;
import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.core.capability.CapabilityModuleDescriptor;
import eu.exeris.tooling.codegen.core.capability.CompositionStamp;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.GeneratorRegistry;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The actual code-generation pipeline — metadata loading, per-entity
 * generator dispatch, and application-bootstrap emission. Separated from
 * {@link CodegenMain} so the work is exercisable from unit tests without
 * going through {@code System.exit} / argument parsing / stderr.
 *
 * <p>Constructor takes its collaborators explicitly so tests can swap the
 * generator set or the {@link ObjectMapper}. Production callers should use
 * {@link #createDefault()}.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class CodegenPipeline {

    private static final Logger LOG = System.getLogger(CodegenPipeline.class.getName());

    /**
     * Output-root file name of the capability manifest ({@code cap-manifest.json}) —
     * emitted by {@link #run}, preserved (not re-emitted) on the T18(a) deferred
     * capability-failure path. Package-private for test visibility.
     */
    static final String CAP_MANIFEST = "cap-manifest.json";

    private final GeneratorRegistry registry;
    private final KernelApplicationGenerator applicationGenerator;
    private final ObjectMapper mapper;

    public CodegenPipeline(GeneratorRegistry registry,
                           KernelApplicationGenerator applicationGenerator,
                           ObjectMapper mapper) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.applicationGenerator = Objects.requireNonNull(applicationGenerator, "applicationGenerator");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Default wiring: the Kernel SPI-aligned strategy + the bootstrap
     * generator + a Jackson mapper configured to tolerate forward-compatible
     * metadata fields and the {@code java.time} module.
     */
    public static CodegenPipeline createDefault() {
        return new CodegenPipeline(
                new KernelGeneratorStrategy().getRegistry(),
                new KernelApplicationGenerator(),
                defaultMapper()
        );
    }

    /**
     * Runs the pipeline. Returns the number of files written.
     *
     * <p>An empty or non-existent {@code metadataDir}, or a directory that
     * contains no usable domain JSON, is not an error — the pipeline logs a
     * warning and returns {@code 0}. Anything actually unable-to-read
     * surfaces as {@link IOException}.
     *
     * @param metadataDir         directory holding processor-emitted JSON
     * @param outputDir           target for generated sources (created if absent)
     * @param explicitBasePackage caller-supplied base package, or {@code null}
     *                            to auto-detect from the first domain
     * @throws IOException if metadata or output cannot be read/written
     * @throws eu.exeris.tooling.codegen.core.capability.CapabilityGraphException
     *         (unchecked) if the capability graph cannot be resolved — an
     *         unsatisfied non-optional {@code @Requires}, version mismatch, or
     *         dependency cycle. Callers that wrap {@code run} should catch it
     *         alongside {@link IOException}.
     */
    public int run(Path metadataDir, Path outputDir, String explicitBasePackage) throws IOException {
        // Backward-compatible entry: the T18 masked-compile-failure guard is ON
        // (allowEmpty=false) — empty metadata never silently wipes a committed tree.
        return run(metadataDir, outputDir, explicitBasePackage, false);
    }

    /**
     * Runs the pipeline with strict capability validation — a capability-graph
     * failure aborts the run. See the five-argument
     * {@link #run(Path, Path, String, boolean, boolean)} for the full contract
     * and the T18(a) deferred alternative.
     *
     * @since 0.6.0
     */
    public int run(Path metadataDir, Path outputDir, String explicitBasePackage, boolean allowEmpty)
            throws IOException {
        return run(metadataDir, outputDir, explicitBasePackage, allowEmpty, false);
    }

    /**
     * Runs the pipeline (primary implementation). Returns the number of files written.
     *
     * <p>An empty or non-existent {@code metadataDir}, or a directory that
     * contains no usable domain JSON, is not an error — the pipeline logs a
     * warning and returns {@code 0}. Anything actually unable-to-read surfaces
     * as {@link IOException}.
     *
     * @param metadataDir         directory holding processor-emitted JSON
     * @param outputDir           target for generated sources (created if absent)
     * @param explicitBasePackage caller-supplied base package, or {@code null}
     *                            to auto-detect from the first domain
     * @param allowEmpty          when {@code true}, a run that loads zero
     *                            {@code @ExerisDomain} entities is permitted to
     *                            prune a previously-generated tree — the explicit
     *                            "I removed every entity" teardown. Default
     *                            ({@code false}) refuses that prune (T18), since
     *                            empty metadata is almost always a masked compile
     *                            failure.
     * @param deferCapabilityFailure when {@code true}, a capability-graph failure
     *                            does not abort the run: it degrades to a WARNING,
     *                            the previous {@code cap-manifest.json} (if owned)
     *                            is preserved, and generation continues (T18(a)).
     *                            Only sound when the caller guarantees an
     *                            authoritative re-validation against fresh
     *                            metadata later in the same build — see
     *                            {@link #validateCapabilities(Path)}. Default
     *                            ({@code false}) keeps the fail-fast abort.
     * @return the number of files written
     * @throws IOException if metadata or output cannot be read/written
     * @throws EmptyMetadataException if {@code allowEmpty} is {@code false} and the
     *         run loads zero domains while a previous run owns a committed tree the
     *         prune would delete (T18 guard)
     * @throws eu.exeris.tooling.codegen.core.capability.CapabilityGraphException
     *         (unchecked) if {@code deferCapabilityFailure} is {@code false} and the
     *         capability graph cannot be resolved — an unsatisfied non-optional
     *         {@code @Requires}, version mismatch, or dependency cycle. Callers
     *         that wrap {@code run} should catch it alongside {@link IOException}.
     * @since 0.6.0
     */
    public int run(Path metadataDir, Path outputDir, String explicitBasePackage, boolean allowEmpty,
                   boolean deferCapabilityFailure) throws IOException {
        Objects.requireNonNull(metadataDir, "metadataDir");
        Objects.requireNonNull(outputDir, "outputDir");

        LOG.log(Level.INFO, "metadata-dir=" + metadataDir);
        LOG.log(Level.INFO, "output-dir=" + outputDir);
        LOG.log(Level.INFO, "target=Exeris Kernel");
        LOG.log(Level.INFO, "base-package=" + (explicitBasePackage != null ? explicitBasePackage : "(auto-detect)"));

        List<DomainMetadata> domains = loadMetadata(metadataDir);
        List<CapabilityModuleDescriptor> capabilities = loadCapabilities(metadataDir);

        if (domains.isEmpty() && capabilities.isEmpty()) {
            LOG.log(Level.WARNING, "No domain or capability metadata found in " + metadataDir
                    + " — make sure @ExerisDomain / @CapabilityModule-annotated classes are compiled first");
            // T18: empty metadata is overwhelmingly a masked compile failure, not
            // an intentional teardown. If a *previous* run owns a committed tree,
            // pruning here would silently wipe it (recoverable only via git
            // restore). Refuse unless the caller explicitly opts into the
            // "I removed every @ExerisDomain" teardown. A directory with no prior
            // manifest is left untouched (nothing was ever generated here).
            OutputWriter emptyWriter = new OutputWriter(outputDir);
            int wouldOrphan = emptyWriter.countOrphans();
            if (wouldOrphan == 0) {
                return 0;
            }
            if (!allowEmpty) {
                throw new EmptyMetadataException(wouldOrphan, metadataDir, outputDir);
            }
            int pruned = emptyWriter.pruneOrphansAndWriteManifest();
            if (pruned > 0) {
                LOG.log(Level.INFO, "Pruned " + pruned + " orphaned generated file(s) (allowEmpty)");
            }
            return 0;
        }

        Files.createDirectories(outputDir);
        OutputWriter writer = new OutputWriter(outputDir);
        int filesGenerated = 0;

        if (!domains.isEmpty()) {
            LOG.log(Level.INFO, "Found " + domains.size() + " domain(s)");
            for (DomainMetadata domain : domains) {
                int fieldCount = domain.fields() != null ? domain.fields().size() : 0;
                LOG.log(Level.DEBUG, () -> "domain=" + domain.entityName()
                        + " path=" + domain.effectivePath()
                        + " package=" + domain.packageName()
                        + " fields=" + fieldCount);
            }

            String basePackage = explicitBasePackage;
            if (basePackage == null) {
                basePackage = domains.get(0).packageName().replace(".domain", "");
                LOG.log(Level.INFO, "Auto-detected base-package=" + basePackage);
            }

            LOG.log(Level.INFO, "Generating per-entity code");
            List<KernelArtifactGenerator> generators = registry.getGenerators();
            for (DomainMetadata domain : domains) {
                LOG.log(Level.DEBUG, () -> "generating entity=" + domain.entityName());
                for (KernelArtifactGenerator generator : generators) {
                    // generateMultiple(...) emits N files where the driver is a
                    // metadata collection (e.g. one stream handler per
                    // @Action(streaming); ADR-044 Slice 2); its default wraps the
                    // single generate(...) result for every other generator.
                    for (GeneratedFile file : generator.generateMultiple(domain)) {
                        if (file != null) {
                            writeFile(writer, file);
                            LOG.log(Level.DEBUG, () -> "wrote " + file.className()
                                    + " (" + generator.artifactType() + ")");
                            filesGenerated++;
                        }
                    }
                }
            }

            LOG.log(Level.INFO, "Generating application bootstrap");
            for (GeneratedFile file : applicationGenerator.generateAll(domains, basePackage)) {
                writeFile(writer, file);
                LOG.log(Level.DEBUG, () -> "wrote " + file.className()
                        + " (" + applicationGenerator.artifactType() + ")");
                filesGenerated++;
            }

            // T9: one trailing Flyway migration adding every cross-table FOREIGN
            // KEY constraint, app-wide so each relationship's target table can be
            // resolved (and external targets skipped). Pinned above every
            // CREATE TABLE migration so the referenced tables already exist.
            // null when the project has no in-scope MANY_TO_ONE relationship.
            GeneratedFile foreignKeys = applicationGenerator.generateForeignKeys(domains);
            if (foreignKeys != null) {
                writeFile(writer, foreignKeys);
                LOG.log(Level.DEBUG, () -> "wrote " + foreignKeys.className()
                        + " (" + foreignKeys.artifactType() + ")");
                filesGenerated++;
            }
        }

        if (!capabilities.isEmpty()) {
            filesGenerated += emitCapabilityManifest(capabilities, writer, deferCapabilityFailure);
        }

        // T18: even with capability metadata present, zero @ExerisDomain entities
        // while a prior run owns domain-derived files is the masked-compile-failure
        // signature — the prune below would wipe that committed tree. Refuse unless
        // the teardown is explicit. (Domains present → a normal prune is correct.)
        if (domains.isEmpty() && !allowEmpty) {
            int wouldOrphan = writer.countOrphans();
            if (wouldOrphan > 0) {
                throw new EmptyMetadataException(wouldOrphan, metadataDir, outputDir);
            }
        }

        // T13: generation owns its output tree — delete files a previous run
        // emitted that this run no longer produces (e.g. a removed/re-homed
        // entity), then persist the manifest of this run's files.
        int pruned = writer.pruneOrphansAndWriteManifest();
        if (pruned > 0) {
            LOG.log(Level.INFO, "Pruned " + pruned + " orphaned generated file(s)");
        }

        LOG.log(Level.INFO, "Code generation complete: files=" + filesGenerated
                + " pruned=" + pruned + " output=" + outputDir);
        return filesGenerated;
    }

    List<DomainMetadata> loadMetadata(Path metadataDir) throws IOException {
        List<DomainMetadata> result = new ArrayList<>();

        if (!Files.exists(metadataDir)) {
            return result;
        }

        try (Stream<Path> files = Files.list(metadataDir)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("enum_"))
                    .filter(p -> !p.getFileName().toString().startsWith("capability_"))
                    .toList();

            for (Path jsonFile : jsonFiles) {
                DomainMetadata metadata = mapper.readValue(jsonFile.toFile(), DomainMetadata.class);
                if (metadata.entityName() != null && !metadata.entityName().isBlank()) {
                    result.add(metadata);
                }
            }
        }

        return result;
    }

    /**
     * Loads {@code capability_*.json} into capability descriptors. Capabilities are
     * app-wide (parallel to, never nested in, {@link DomainMetadata}); the load is
     * order-independent — {@link CapabilityGraph#build} sorts deterministically.
     */
    List<CapabilityModuleDescriptor> loadCapabilities(Path metadataDir) throws IOException {
        List<CapabilityModuleDescriptor> result = new ArrayList<>();

        if (!Files.exists(metadataDir)) {
            return result;
        }

        try (Stream<Path> files = Files.list(metadataDir)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> p.getFileName().toString().startsWith("capability_"))
                    .toList();

            for (Path jsonFile : jsonFiles) {
                CapabilityModuleDescriptor descriptor =
                        mapper.readValue(jsonFile.toFile(), CapabilityModuleDescriptor.class);
                if (descriptor.qualifiedName() != null && !descriptor.qualifiedName().isBlank()) {
                    result.add(descriptor);
                }
            }
        }

        return result;
    }

    /**
     * Loads {@code capability_*.json} from {@code metadataDir} and resolves +
     * validates the capability graph <b>without writing anything</b> — the
     * authoritative T18(a) gate. Bound (via {@code exeris:verify-capabilities})
     * after the {@code compile} phase, it always sees the metadata the processor
     * emitted <em>this</em> build, so a failure here is a genuine graph problem,
     * never the stale-input deadlock the deferred generate-sources pass avoids.
     * No capability metadata is not an error — there is nothing to validate.
     *
     * @param metadataDir directory holding processor-emitted JSON
     * @return the number of capability modules validated ({@code 0} when there
     *         is no capability metadata)
     * @throws IOException if metadata cannot be read
     * @throws eu.exeris.tooling.codegen.core.capability.CapabilityGraphException
     *         (unchecked) on an unsatisfied non-optional {@code @Requires},
     *         version mismatch, or dependency cycle
     * @since 0.6.0
     */
    public int validateCapabilities(Path metadataDir) throws IOException {
        Objects.requireNonNull(metadataDir, "metadataDir");
        List<CapabilityModuleDescriptor> capabilities = loadCapabilities(metadataDir);
        if (capabilities.isEmpty()) {
            LOG.log(Level.INFO, "No capability metadata in " + metadataDir + " — nothing to validate");
            return 0;
        }
        LOG.log(Level.INFO, "Validating capability graph (" + capabilities.size() + " module(s))");
        CapabilityGraph graph = CapabilityGraph.build(capabilities);
        for (String warning : graph.warnings()) {
            LOG.log(Level.WARNING, "capability: " + warning);
        }
        return capabilities.size();
    }

    /**
     * Resolves + validates the capability graph (failing the build via
     * {@link eu.exeris.tooling.codegen.core.capability.CapabilityGraphException} on an
     * unsatisfied non-optional requirement, version mismatch, or cycle) and writes the
     * deterministic {@code cap-manifest.json} at the output root — the build-time,
     * platform-side capability registry (input for the mesh contract, T12).
     *
     * <p>Runs after domain emission, so a graph failure here leaves any domain files
     * from this run on disk with orphan-pruning un-run (the {@code @throws} aborts
     * before {@link OutputWriter#pruneOrphansAndWriteManifest()}). That is standard
     * fail-fast behaviour — the next successful build prunes correctly.
     *
     * <p>With {@code deferCapabilityFailure} the abort is replaced: the failure is
     * logged as a WARNING, the previous {@code cap-manifest.json} (when owned by a
     * prior run) is preserved so the trailing prune keeps it, and the run continues
     * — see the T18(a) contract on {@link #run(Path, Path, String, boolean, boolean)}.
     *
     * @return the number of files written ({@code 1}, or {@code 0} on the deferred
     *         failure path)
     */
    private int emitCapabilityManifest(List<CapabilityModuleDescriptor> capabilities,
                                       OutputWriter writer,
                                       boolean deferCapabilityFailure) throws IOException {
        LOG.log(Level.INFO, "Resolving capability graph (" + capabilities.size() + " module(s))");
        // ADR-024 obligation 7: stamp the composition with its release identity. The
        // version is a build input (not derivable from the graph) — the build/plugin sets
        // -Dexeris.composition.version=${project.version}; absent, it degrades to
        // CompositionStamp.UNVERSIONED. The content binding is always graph-derived.
        String compositionVersion = System.getProperty("exeris.composition.version",
                CompositionStamp.UNVERSIONED);
        CapabilityGraph graph;
        try {
            graph = CapabilityGraph.build(capabilities, compositionVersion);
        } catch (CapabilityGraphException e) {
            if (!deferCapabilityFailure) {
                throw e;
            }
            // T18(a): at generate-sources the capability metadata on disk is by
            // construction LAST build's output — the processor only refreshes it
            // during the upcoming compile phase. Hard-failing here deadlocks the
            // build on its own stale input (the @Requires edit that fixes the
            // graph can never take effect). The caller signalled that the
            // authoritative gate (exeris:verify-capabilities, post-compile)
            // re-validates FRESH metadata this same build — so degrade to a
            // WARNING, keep the prior cap-manifest.json in place, and let that
            // gate deliver the fail-closed verdict.
            LOG.log(Level.WARNING, "Capability graph invalid on possibly-stale metadata — deferring "
                    + "to the post-compile verify-capabilities gate: " + e.getMessage());
            if (writer.preserve(CAP_MANIFEST)) {
                LOG.log(Level.INFO, "Kept previous " + CAP_MANIFEST
                        + " (refreshed on the next successful generate)");
            }
            return 0;
        }
        for (String warning : graph.warnings()) {
            LOG.log(Level.WARNING, "capability: " + warning);
        }

        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        String json = mapper.writer(printer).writeValueAsString(graph) + "\n";

        writer.writeResource(CAP_MANIFEST, json);
        LOG.log(Level.INFO, "Wrote " + CAP_MANIFEST + " (init order: " + graph.initOrder() + ")");
        return 1;
    }

    private static void writeFile(OutputWriter writer, GeneratedFile file) throws IOException {
        String extension = file.extension();

        if ("sql".equals(extension)) {
            writer.writeResource(file.relativePath(), file.content());
        } else if ("yaml".equals(extension) || "yml".equals(extension)) {
            String relativePath = file.packageName().replace('.', '/') + "/" + file.className() + "." + extension;
            writer.writeResource(relativePath, file.content());
        } else {
            writer.writeJavaSource(
                    file.packageName(),
                    file.className(),
                    file.content(),
                    file.className() + ".java"
            );
        }
    }

    static ObjectMapper defaultMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return m;
    }
}
