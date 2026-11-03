package eu.exeris.e2e.codegen.compile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Writes a {@code @Blob} facet onto one field of the processor's metadata — the shape
 * {@code FieldMetadata.blob} reads (ADR-108) — so the facet an emitted tree is built from is set by
 * the test, whatever annotations the source carries and whatever the processor refuses on them.
 */
public final class BlobFacet {

    private BlobFacet() {
    }

    /**
     * The edit that gives {@code entity.field} a blob facet with {@code contentTypes} and a derived
     * container.
     *
     * @param entity       the entity's simple name
     * @param field        the field's name
     * @param contentTypes the accepted media types; empty for unrestricted
     * @return the edit
     */
    public static GeneratedTree.MetadataEdit on(String entity, String field, List<String> contentTypes) {
        return metadataDir -> {
            JsonMapper mapper = JsonMapper.builder().build();
            boolean edited = false;
            try (Stream<Path> files = Files.list(metadataDir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    JsonNode root = mapper.readTree(file.toFile());
                    if (!entity.equals(root.path("entityName").asString(""))) {
                        continue;
                    }
                    for (JsonNode node : root.path("fields")) {
                        if (field.equals(node.path("name").asString(""))) {
                            ObjectNode blob = ((ObjectNode) node).putObject("blob");
                            ArrayNode types = blob.putArray("contentTypes");
                            contentTypes.forEach(types::add);
                            edited = true;
                        }
                    }
                    Files.writeString(file, mapper.writeValueAsString(root));
                }
            }
            if (!edited) {
                throw new IOException("no metadata field " + entity + "." + field + " under " + metadataDir);
            }
        };
    }
}
