package eu.exeris.tooling.codegen.core.contract;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A peer's contract as read: its manifest and the metadata of the entities and enums it publishes.
 *
 * @param name      the consumer-declared peer name
 * @param manifest  the parsed {@code cap-manifest.json}
 * @param entities  the entity metadata files, sorted by qualified name
 * @param enums     the enum metadata files, sorted by qualified name
 * @param warnings  non-fatal findings, each worded for the log, in file order
 * @since 0.12
 */
public record PeerContract(
        String name,
        JsonNode manifest,
        List<File> entities,
        List<File> enums,
        List<String> warnings
) {

    /**
     * One metadata file of the contract.
     *
     * @param fileName      the file name inside the contract directory
     * @param qualifiedName the qualified name the file is sorted by
     * @param metadata      the parsed metadata
     */
    public record File(String fileName, String qualifiedName, JsonNode metadata) {
    }

    /**
     * The composition version the peer stamped.
     *
     * @return the stamp's {@code compositionVersion}, or {@code null} when absent
     */
    public String compositionVersion() {
        return text(manifest.path("stamp").path("compositionVersion"));
    }

    /**
     * The content binding the peer stamped.
     *
     * @return the stamp's {@code contentBinding}, or {@code null} when absent
     */
    public String contentBinding() {
        return text(manifest.path("stamp").path("contentBinding"));
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }
}
