package eu.exeris.tooling.codegen.core.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.mutation.SchemaVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads one peer's contract from a directory or a jar (ADR-048 Amendment 3, section 10).
 *
 * <p>The rules are the same for both sources: the manifest is required and its
 * {@code schemaVersion} is at least {@link #MIN_MANIFEST_SCHEMA_VERSION}; members of the manifest
 * this reader does not know are ignored; only {@code cap-manifest.json}, {@code enum_*.json} and
 * entity files are read, so a file added to the contract later is skipped by name; entities and
 * enums are sorted by qualified name in {@code String.compareTo} order. A jar is read in memory
 * and only entries under {@link ContractLayout#DIRECTORY} are looked at.
 *
 * @since 0.12
 */
public final class PeerContractReader {

    /** The lowest manifest schema read; v1 carries no composition stamp. */
    public static final int MIN_MANIFEST_SCHEMA_VERSION = 2;

    /** Upper bound on one contract file; a contract file is metadata, never bulk data. */
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String currentSchemaVersion;

    /**
     * Creates a reader that compares peer entities against the SDK's current metadata schema.
     */
    public PeerContractReader() {
        this(SchemaVersion.CURRENT);
    }

    /**
     * Creates a reader that compares peer entities against the given schema version.
     *
     * @param currentSchemaVersion the version a peer entity is expected to carry
     */
    public PeerContractReader(String currentSchemaVersion) {
        this.currentSchemaVersion = currentSchemaVersion;
    }

    /**
     * Reads the contract a location holds.
     *
     * @param peer     the consumer-declared peer name
     * @param location a contract directory, a directory holding {@code META-INF/exeris/contract/},
     *                 or a {@code .jar} file
     * @return the contract
     * @throws PeerContractException when the location is not a readable, current contract
     */
    public PeerContract read(String peer, Path location) {
        Map<String, byte[]> files;
        try {
            files = load(peer, location);
        } catch (IOException e) {
            throw new PeerContractException(peer, "cannot read " + location + ": " + e, e);
        }
        return parse(peer, location, files);
    }

    private Map<String, byte[]> load(String peer, Path location) throws IOException {
        if (!Files.exists(location)) {
            throw new PeerContractException(peer, location + " does not exist");
        }
        if (Files.isRegularFile(location)) {
            if (!location.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new PeerContractException(peer, location + " is a file but not a .jar; expected a contract "
                        + "directory, a directory holding " + ContractLayout.DIRECTORY + "/, or a jar");
            }
            return loadJar(location);
        }
        Path directory = Files.isRegularFile(location.resolve(ContractLayout.MANIFEST))
                ? location
                : location.resolve(ContractLayout.DIRECTORY);
        if (!Files.isDirectory(directory)) {
            throw new PeerContractException(peer, "no contract in " + location + ": it holds neither "
                    + ContractLayout.MANIFEST + " nor " + ContractLayout.DIRECTORY + "/. The backend "
                    + "writes it with exeris:contract at process-classes; a build that stopped earlier "
                    + "has none");
        }
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> listing = Files.list(directory)) {
            for (Path file : (Iterable<Path>) listing.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))::iterator) {
                String name = file.getFileName().toString();
                if (wanted(name)) {
                    files.put(name, bounded(peer, name, Files.newInputStream(file)));
                }
            }
        }
        return files;
    }

    private Map<String, byte[]> loadJar(Path jar) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        String prefix = ContractLayout.DIRECTORY + "/";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<ZipEntry> entries = new ArrayList<>(Collections.list(zip.entries()));
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (ZipEntry entry : entries) {
                String entryName = entry.getName();
                if (entry.isDirectory() || !entryName.startsWith(prefix)) {
                    continue;
                }
                String name = entryName.substring(prefix.length());
                if (name.indexOf('/') < 0 && wanted(name)) {
                    files.put(name, bounded(jar.toString(), name, zip.getInputStream(entry)));
                }
            }
        }
        return files;
    }

    private static boolean wanted(String name) {
        return ContractLayout.MANIFEST.equals(name)
                || ContractLayout.isEnum(name)
                || ContractLayout.isEntityCandidate(name);
    }

    private static byte[] bounded(String source, String name, InputStream in) throws IOException {
        try (in) {
            byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IOException(name + " in " + source + " exceeds " + MAX_FILE_BYTES + " bytes");
            }
            return bytes;
        }
    }

    private PeerContract parse(String peer, Path location, Map<String, byte[]> files) {
        byte[] manifestBytes = files.get(ContractLayout.MANIFEST);
        if (manifestBytes == null) {
            throw new PeerContractException(peer, "no " + ContractLayout.MANIFEST + " in " + location
                    + "; a contract is its manifest plus the metadata of its entities, and metadata "
                    + "alone leaves its stamp unchecked");
        }
        JsonNode manifest = json(peer, ContractLayout.MANIFEST, manifestBytes);
        JsonNode version = manifest.path("schemaVersion");
        if (!version.isInt()) {
            throw new PeerContractException(peer, ContractLayout.MANIFEST + " declares no integer "
                    + "schemaVersion; it is not a capability manifest this tooling wrote");
        }
        if (version.intValue() < MIN_MANIFEST_SCHEMA_VERSION) {
            throw new PeerContractException(peer, ContractLayout.MANIFEST + " declares schemaVersion "
                    + version.intValue() + ", below the floor of " + MIN_MANIFEST_SCHEMA_VERSION
                    + "; it carries no composition stamp. Rebuild the peer with a current exeris-tooling");
        }

        List<PeerContract.File> entities = new ArrayList<>();
        List<PeerContract.File> enums = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String name = file.getKey();
            if (ContractLayout.isEnum(name)) {
                readEnum(peer, name, file.getValue(), enums);
            } else if (ContractLayout.isEntityCandidate(name)) {
                readEntity(peer, name, file.getValue(), entities, warnings);
            }
        }
        Comparator<PeerContract.File> byName = Comparator.comparing(PeerContract.File::qualifiedName);
        entities.sort(byName);
        enums.sort(byName);
        return new PeerContract(peer, manifest, List.copyOf(entities), List.copyOf(enums), List.copyOf(warnings));
    }

    private void readEnum(String peer, String name, byte[] bytes, List<PeerContract.File> enums) {
        JsonNode node = json(peer, name, bytes);
        String qualified = text(node.path("qualifiedName"));
        if (qualified != null) {
            enums.add(new PeerContract.File(name, qualified, node));
        }
    }

    private void readEntity(String peer, String name, byte[] bytes, List<PeerContract.File> entities,
            List<String> warnings) {
        JsonNode node = json(peer, name, bytes);
        String entityName = text(node.path("entityName"));
        if (entityName == null) {
            return;
        }
        String schema = text(node.path("schemaVersion"));
        if (schema == null) {
            throw new PeerContractException(peer, "entity " + entityName + " (" + name
                    + ") carries no schemaVersion, so it cannot be checked against this build");
        }
        if (!schema.equals(currentSchemaVersion)) {
            warnings.add("peer '" + peer + "': entity " + entityName + " has schemaVersion " + schema
                    + ", this build reads " + currentSchemaVersion);
        }
        String pkg = text(node.path("packageName"));
        entities.add(new PeerContract.File(name, pkg == null ? entityName : pkg + "." + entityName, node));
    }

    private JsonNode json(String peer, String name, byte[] bytes) {
        try {
            return mapper.readTree(bytes);
        } catch (IOException e) {
            throw new PeerContractException(peer, name + " is not readable JSON: " + e, e);
        }
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
