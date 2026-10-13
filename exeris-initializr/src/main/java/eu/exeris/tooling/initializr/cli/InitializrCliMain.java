package eu.exeris.tooling.initializr.cli;

import eu.exeris.tooling.initializr.engine.ProjectGenerator;
import eu.exeris.tooling.initializr.engine.ZipArchiveGenerator;
import eu.exeris.tooling.initializr.model.DatabaseType;
import eu.exeris.tooling.initializr.model.ExerisCapability;
import eu.exeris.tooling.initializr.model.ProjectScaffoldRequest;
import eu.exeris.tooling.initializr.model.ProjectTopology;
import eu.exeris.tooling.initializr.model.ProjectTree;
import eu.exeris.tooling.initializr.model.ScaffoldFile;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line interface for the Exeris Initializr engine.
 */
public final class InitializrCliMain {

    public static void main(String[] args) {
        if (args.length == 0 || hasArg(args, "--help") || hasArg(args, "-h")) {
            printHelp();
            return;
        }

        String name = getArgValue(args, "--name", "my-exeris-app");
        String slug = getArgValue(args, "--slug", name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-"));
        String groupId = getArgValue(args, "--group-id", "eu.exeris.sample");
        String artifactId = getArgValue(args, "--artifact-id", slug);
        String packageName = getArgValue(args, "--package", groupId + "." + slug.replace('-', '.'));
        String topologyStr = getArgValue(args, "--type", "E2E").toUpperCase(Locale.ROOT);
        String dbStr = getArgValue(args, "--db", "H2").toUpperCase(Locale.ROOT);
        String outputDir = getArgValue(args, "--output", null);
        String zipFile = getArgValue(args, "--zip", null);
        String entityName = getArgValue(args, "--entity", "Item");

        ProjectTopology topology;
        try {
            topology = ProjectTopology.valueOf(topologyStr);
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown topology: " + topologyStr + ". Available: E2E, BACKEND_ONLY, FRONTEND_ONLY");
            System.exit(1);
            return;
        }

        DatabaseType database;
        try {
            database = DatabaseType.valueOf(dbStr);
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown database: " + dbStr + ". Available: H2, POSTGRESQL");
            System.exit(1);
            return;
        }

        List<ExerisCapability> capabilities = new ArrayList<>();
        String capsStr = getArgValue(args, "--capabilities", "");
        if (!capsStr.isBlank()) {
            for (String capKey : capsStr.split(",")) {
                try {
                    capabilities.add(ExerisCapability.fromKey(capKey.trim()));
                } catch (IllegalArgumentException e) {
                    System.err.println("Warning: ignoring unknown capability " + capKey);
                }
            }
        }

        ProjectScaffoldRequest request = ProjectScaffoldRequest.builder()
                .name(name)
                .slug(slug)
                .groupId(groupId)
                .artifactId(artifactId)
                .packageName(packageName)
                .topology(topology)
                .database(database)
                .capabilities(capabilities)
                .entityName(entityName)
                .build();

        System.out.println("Scaffolding Exeris project: " + name + " [" + topology + "]...");
        ProjectTree tree = ProjectGenerator.generate(request);
        System.out.println("Generated " + tree.size() + " files.");

        if (zipFile != null) {
            byte[] zipBytes = ZipArchiveGenerator.toZipBytes(tree, slug);
            try (FileOutputStream fos = new FileOutputStream(zipFile)) {
                fos.write(zipBytes);
            } catch (IOException e) {
                System.err.println("Failed to write zip file: " + e.getMessage());
                System.exit(1);
            }
            System.out.println("Wrote archive to " + zipFile);
        }

        if (outputDir != null || zipFile == null) {
            Path targetPath = Paths.get(outputDir != null ? outputDir : "./" + slug);
            try {
                for (ScaffoldFile file : tree.files()) {
                    Path filePath = targetPath.resolve(file.path());
                    Files.createDirectories(filePath.getParent());
                    Files.write(filePath, file.content());
                }
            } catch (IOException e) {
                System.err.println("Failed to write files to disk: " + e.getMessage());
                System.exit(1);
            }
            System.out.println("Scaffolded project created at: " + targetPath.toAbsolutePath());
        }
    }

    private static void printHelp() {
        System.out.println("""
                Exeris Initializr CLI
                Usage: java -cp ... eu.exeris.tooling.initializr.cli.InitializrCliMain [options]
                
                Options:
                  --name <name>            Project display name (default: my-exeris-app)
                  --slug <slug>            Project slug / folder name
                  --type <type>            Topology: E2E | BACKEND_ONLY | FRONTEND_ONLY (default: E2E)
                  --db <db>                Database: H2 | POSTGRESQL (default: H2)
                  --group-id <group>       Maven groupId (default: eu.exeris.sample)
                  --artifact-id <artifact> Maven artifactId (default: slug)
                  --package <pkg>          Java package (default: eu.exeris.sample.<slug>)
                  --entity <name>          Demo domain entity name (default: Item)
                  --capabilities <caps>    Comma-separated list (e.g. audit-trail,jwt-validation)
                  --output <dir>           Directory to write files to
                  --zip <file>             Path to write ZIP archive to
                  -h, --help               Show this help message
                """);
    }

    private static boolean hasArg(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }

    private static String getArgValue(String[] args, String key, String defaultValue) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase(key) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (args[i].toLowerCase(Locale.ROOT).startsWith(key.toLowerCase(Locale.ROOT) + "=")) {
                return args[i].substring(key.length() + 1);
            }
        }
        return defaultValue;
    }
}
