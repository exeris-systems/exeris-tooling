package eu.exeris.tooling.codegen.java.openapi;

import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Gives a schema its type in the form the OpenAPI 3.1 writer serialises.
 *
 * <p>The swagger model keeps a schema's type in two places: {@code type}, a single string, and
 * {@code types}, the 3.1 set. The 3.1 mapper {@link OpenApiGenerator} writes with ignores the
 * first and writes {@code type} from the second, so a schema typed through {@code setType} alone
 * is published with no type at all. Every typed schema the builders emit goes through
 * {@link #typed}, which sets both: {@code types} for the document and {@code type} for a reader of
 * the model.
 */
final class OpenApiSchemas {

    private OpenApiSchemas() {}

    /**
     * Sets {@code type} as the schema's only type and marks the schema as OpenAPI 3.1.
     *
     * @param schema the schema to type
     * @param type   the JSON Schema type, such as {@code string} or {@code object}
     * @param <S>    the schema's own type, so a call can be chained
     * @return {@code schema}
     */
    static <S extends Schema<?>> S typed(S schema, String type) {
        schema.setSpecVersion(SpecVersion.V31);
        schema.setType(type);
        schema.setTypes(new LinkedHashSet<>(List.of(type)));
        return schema;
    }
}
