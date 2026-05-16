package eu.exeris.tooling.codegen.java;

import eu.exeris.tooling.codegen.core.OutputWriter;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.GeneratorRegistry;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.java.kernel.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Main entry point for Exeris Java Code Generator.
 * <p>
 * Generates the SPI-aligned set of artifacts from domain metadata. The
 * per-entity pass is driven by {@link
 * eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy} and
 * emits:
 * <ul>
 *   <li>{@code *Handler.java} — HTTP handlers against the SPI {@code HttpExchange}</li>
 *   <li>{@code *Service.java} — POJO domain services</li>
 *   <li>{@code *Repository.java} — repositories against {@code spi.persistence.TransactionalExecutor}</li>
 *   <li>{@code *EventPublisher.java} / {@code *EventSubscriber.java} — domain events on the SPI event bus</li>
 *   <li>{@code *GraphSync.java} — graph-sync projection (when {@code @Graph} is declared)</li>
 *   <li>{@code *SagaFlow.java} — saga skeleton on the SPI flow framework (when {@code @Saga} is declared)</li>
 *   <li>Flyway SQL migrations</li>
 *   <li>OpenAPI 3.1 YAML specs</li>
 * </ul>
 * <p>After the per-entity loop, the project-wide bootstrap pair is
 * emitted by {@link eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator}:
 * <ul>
 *   <li>{@code Application.java} — {@code KernelBootstrap} entry point with a
 *       {@code transactionalExecutor()} override hook</li>
 *   <li>{@code RuntimeLifecycle.java} — composes Repository → Service → Handler
 *       chains, builds the {@code HttpRouter}, parks on a shutdown latch</li>
 * </ul>
 * <p>The only generator still parked is {@code KernelClientGenerator}
 * (service-to-service client; awaits canonical client SPI shape).
 *
 * <h2>Usage:</h2>
 * <pre>
 * java -cp ... eu.exeris.tooling.codegen.java.CodegenMain \
 *     --metadata-dir=target/exeris-metadata \
 *     --output-dir=target/generated-sources/exeris \
 *     --base-package=eu.exeris.foundation
 * </pre>
 *
 * <h2>Logging:</h2>
 * Uses {@link System.Logger} (JDK-standard, JSR 264). The default JDK
 * implementation routes to stderr at INFO+; downstream consumers can plug a
 * {@code LoggerFinder} (or, when running as a Maven plugin, the
 * {@code slf4j-jdk-platform-logging} bridge) to fold output into the host's
 * log infrastructure. No third-party logging dependency is brought into the
 * tooling.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class CodegenMain {

    private static final Logger LOG = System.getLogger(CodegenMain.class.getName());

    private static final ObjectMapper MAPPER = createMapper();

    public static void main(String[] args) {
        LOG.log(Level.INFO, "Exeris Java Code Generator v0.1.0 starting");

        try {
            // Parse arguments
            Path metadataDir = null;
            Path outputDir = null;
            String basePackage = null;

            for (String arg : args) {
                if (arg.startsWith("--metadata-dir=")) {
                    metadataDir = Path.of(arg.substring("--metadata-dir=".length()));
                } else if (arg.startsWith("--output-dir=")) {
                    outputDir = Path.of(arg.substring("--output-dir=".length()));
                } else if (arg.startsWith("--base-package=")) {
                    basePackage = arg.substring("--base-package=".length());
                }
            }

            if (metadataDir == null || outputDir == null) {
                printUsage();
                System.exit(1);
            }

            LOG.log(Level.INFO, "metadata-dir=" + metadataDir);
            LOG.log(Level.INFO, "output-dir=" + outputDir);
            LOG.log(Level.INFO, "target=Exeris Kernel");
            LOG.log(Level.INFO, "base-package=" + (basePackage != null ? basePackage : "(auto-detect)"));

            // Load metadata
            List<DomainMetadata> domains = loadMetadata(metadataDir);

            if (domains.isEmpty()) {
                LOG.log(Level.WARNING, "No domain metadata found in " + metadataDir
                        + " — make sure @ExerisDomain-annotated classes are compiled first");
                System.exit(0);
            }

            LOG.log(Level.INFO, "Found " + domains.size() + " domain(s)");
            for (DomainMetadata domain : domains) {
                int fieldCount = domain.fields() != null ? domain.fields().size() : 0;
                LOG.log(Level.DEBUG, () -> "domain=" + domain.entityName()
                        + " path=" + domain.effectivePath()
                        + " package=" + domain.packageName()
                        + " fields=" + fieldCount);
            }

            // Auto-detect base package if not provided
            if (basePackage == null) {
                basePackage = domains.get(0).packageName().replace(".domain", "");
                LOG.log(Level.INFO, "Auto-detected base-package=" + basePackage);
            }

            // Create output directory
            Files.createDirectories(outputDir);
            OutputWriter writer = new OutputWriter(outputDir);

            int filesGenerated = 0;

            // ---------------------------------------------------------------
            // Per-entity code (active SPI-aligned generators only — see
            // KernelGeneratorStrategy for the parked roster and migration
            // targets for the rest)
            // ---------------------------------------------------------------
            LOG.log(Level.INFO, "Generating per-entity code");

            KernelGeneratorStrategy strategy = new KernelGeneratorStrategy();
            GeneratorRegistry registry = strategy.getRegistry();

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

            // ---------------------------------------------------------------
            // Application bootstrap (one-shot, project-wide — Application
            // and RuntimeLifecycle wired against Open-Core SPI). Not part
            // of the per-entity strategy loop because it composes across
            // the full domain list.
            // ---------------------------------------------------------------
            LOG.log(Level.INFO, "Generating application bootstrap");
            KernelApplicationGenerator appGen = new KernelApplicationGenerator();
            for (GeneratedFile file : appGen.generateAll(domains, basePackage)) {
                writeFile(writer, file);
                LOG.log(Level.DEBUG, () -> "wrote " + file.className()
                        + " (" + appGen.artifactType() + ")");
                filesGenerated++;
            }

            LOG.log(Level.INFO, "Code generation complete: files=" + filesGenerated
                    + " output=" + outputDir);

        } catch (Exception e) {
            LOG.log(Level.ERROR, "Code generation failed", e);
            System.exit(1);
        }
    }

    private static List<DomainMetadata> loadMetadata(Path metadataDir) throws IOException {
        List<DomainMetadata> result = new ArrayList<>();

        if (!Files.exists(metadataDir)) {
            return result;
        }

        try (Stream<Path> files = Files.list(metadataDir)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith("enum_")) // Skip enum metadata files
                    .toList();

            for (Path jsonFile : jsonFiles) {
                DomainMetadata metadata = MAPPER.readValue(jsonFile.toFile(), DomainMetadata.class);
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
            // SQL files - write as resource without Java header
            String relativePath = file.relativePath();
            writer.writeResource(relativePath, file.content());
        } else if ("yaml".equals(extension) || "yml".equals(extension)) {
            // YAML files (OpenAPI specs) - write as resource
            String relativePath = file.packageName().replace('.', '/') + "/" + file.className() + "." + extension;
            writer.writeResource(relativePath, file.content());
        } else {
            // Java files - write with header
            writer.writeJavaSource(
                    file.packageName(),
                    file.className(),
                    file.content(),
                    file.className() + ".java"
            );
        }
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    private static void printUsage() {
        // Argument-parsing usage hint — emitted to stderr because the JVM is about to
        // exit with status 1, before the logging pipeline guarantees flush. CLI
        // contract: usage goes to stderr, not the logger.
        System.err.println("Usage: CodegenMain <options>");
        System.err.println("Required:");
        System.err.println("  --metadata-dir=<path>   Path to exeris-metadata JSON files");
        System.err.println("  --output-dir=<path>     Path for generated Java sources");
        System.err.println("Optional:");
        System.err.println("  --base-package=<pkg>    Base package for application classes");
    }
}
