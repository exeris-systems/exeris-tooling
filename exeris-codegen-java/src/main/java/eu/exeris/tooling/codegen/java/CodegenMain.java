package eu.exeris.tooling.codegen.java;

import eu.exeris.tooling.codegen.core.OutputWriter;
import eu.exeris.tooling.codegen.core.generator.BackendGenerator;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.GeneratorRegistry;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.java.kernel.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Main entry point for Exeris Java Code Generator.
 * <p>
 * Generates complete application from domain metadata:
 * <ul>
 *   <li>Application.java - Entry point</li>
 *   <li>CompositionRoot.java - Manual DI wiring</li>
 *   <li>RouterConfig.java - HTTP/3 routes</li>
 *   <li>*Repository.java - Data access for each entity</li>
 *   <li>*Service.java - Business logic for each entity</li>
 *   <li>*Handler.java - HTTP handlers for each entity</li>
 *   <li>Flyway SQL migrations</li>
 * </ul>
 *
 * <h2>Usage:</h2>
 * <pre>
 * java -cp ... eu.exeris.tooling.codegen.java.CodegenMain \
 *     --metadata-dir=target/exeris-metadata \
 *     --output-dir=target/generated-sources/exeris \
 *     --base-package=eu.exeris.foundation
 * </pre>
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class CodegenMain {

    private static final ObjectMapper MAPPER = createMapper();

    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("  Exeris Java Code Generator v0.1.0");
        System.out.println("  Generates COMPLETE application from domain entities");
        System.out.println("═══════════════════════════════════════════════════════════════════");

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

            System.out.println("\n📂 Metadata dir:  " + metadataDir);
            System.out.println("📁 Output dir:    " + outputDir);
            System.out.println("🎯 Target:        Exeris Kernel");
            System.out.println("📦 Base package:  " + (basePackage != null ? basePackage : "(auto-detect)"));

            // Load metadata
            List<DomainMetadata> domains = loadMetadata(metadataDir);

            if (domains.isEmpty()) {
                System.out.println("\n⚠️ No domain metadata found in " + metadataDir);
                System.out.println("   Make sure @ExerisDomain annotated classes are compiled first.");
                System.exit(0);
            }

            System.out.println("\n📦 Found " + domains.size() + " domain(s):");
            for (DomainMetadata domain : domains) {
                System.out.println("   - " + domain.entityName() + " (" + domain.effectivePath() + ")");
                System.out.println("     Package: " + domain.packageName());
                System.out.println("     Fields:  " + (domain.fields() != null ? domain.fields().size() : 0));
            }

            // Auto-detect base package if not provided
            if (basePackage == null && !domains.isEmpty()) {
                basePackage = domains.get(0).packageName().replace(".domain", "");
                System.out.println("\n📦 Auto-detected base package: " + basePackage);
            }

            // Create output directory
            Files.createDirectories(outputDir);
            OutputWriter writer = new OutputWriter(outputDir);

            int filesGenerated = 0;

            // ═══════════════════════════════════════════════════════════════════
            // 1. Generate per-entity code (Repository, Service, Handler, etc.)
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("\n🔧 Generating per-entity code...");

            // Use KernelGeneratorStrategy which registers all generators
            KernelGeneratorStrategy strategy = new KernelGeneratorStrategy();
            GeneratorRegistry registry = strategy.getRegistry();

            List<BackendGenerator> generators = registry.getGenerators();

            // Filter out application generator (we'll handle it separately)
            generators = generators.stream()
                    .filter(g -> !(g instanceof KernelApplicationGenerator))
                    .toList();

            for (DomainMetadata domain : domains) {
                System.out.println("\n   📝 " + domain.entityName() + ":");
                for (BackendGenerator generator : generators) {
                    GeneratedFile file = generator.generate(domain);
                    if (file != null) {
                        writeFile(writer, file);
                        System.out.println("      ✅ " + file.className() + " (" + generator.artifactType() + ")");
                        filesGenerated++;
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // 2. Generate application infrastructure (Application, CompositionRoot, Router)
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("\n🏗️ Generating application infrastructure...");
            KernelApplicationGenerator appGen = new KernelApplicationGenerator();
            List<GeneratedFile> appFiles = appGen.generateAll(domains, basePackage);

            for (GeneratedFile file : appFiles) {
                writeFile(writer, file);
                System.out.println("   ✅ " + file.className());
                filesGenerated++;
            }

            // ═══════════════════════════════════════════════════════════════════
            // Summary
            // ═══════════════════════════════════════════════════════════════════
            System.out.println("\n═══════════════════════════════════════════════════════════════════");
            System.out.println("  ✅ Code generation complete!");
            System.out.println("  📊 Files generated: " + filesGenerated);
            System.out.println("  📁 Output: " + outputDir);
            System.out.println("═══════════════════════════════════════════════════════════════════");

        } catch (Exception e) {
            System.err.println("\n❌ Code generation failed: " + e.getMessage());
            e.printStackTrace();
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
        System.err.println("\nUsage: CodegenMain <options>");
        System.err.println("\nRequired:");
        System.err.println("  --metadata-dir=<path>   Path to exeris-metadata JSON files");
        System.err.println("  --output-dir=<path>     Path for generated Java sources");
        System.err.println("\nOptional:");
        System.err.println("  --base-package=<pkg>    Base package for application classes");
    }
}

