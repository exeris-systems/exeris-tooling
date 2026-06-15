package eu.exeris.tooling.codegen.java;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.OutputWriter;
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
     */
    public int run(Path metadataDir, Path outputDir, String explicitBasePackage) throws IOException {
        Objects.requireNonNull(metadataDir, "metadataDir");
        Objects.requireNonNull(outputDir, "outputDir");

        LOG.log(Level.INFO, "metadata-dir=" + metadataDir);
        LOG.log(Level.INFO, "output-dir=" + outputDir);
        LOG.log(Level.INFO, "target=Exeris Kernel");
        LOG.log(Level.INFO, "base-package=" + (explicitBasePackage != null ? explicitBasePackage : "(auto-detect)"));

        List<DomainMetadata> domains = loadMetadata(metadataDir);

        if (domains.isEmpty()) {
            LOG.log(Level.WARNING, "No domain metadata found in " + metadataDir
                    + " — make sure @ExerisDomain-annotated classes are compiled first");
            return 0;
        }

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

        Files.createDirectories(outputDir);
        OutputWriter writer = new OutputWriter(outputDir);

        int filesGenerated = 0;

        LOG.log(Level.INFO, "Generating per-entity code");
        List<KernelArtifactGenerator> generators = registry.getGenerators();
        for (DomainMetadata domain : domains) {
            LOG.log(Level.DEBUG, () -> "generating entity=" + domain.entityName());
            for (KernelArtifactGenerator generator : generators) {
                GeneratedFile file = generator.generate(domain);
                if (file != null) {
                    writeFile(writer, file);
                    LOG.log(Level.DEBUG, () -> "wrote " + file.className()
                            + " (" + generator.artifactType() + ")");
                    filesGenerated++;
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
