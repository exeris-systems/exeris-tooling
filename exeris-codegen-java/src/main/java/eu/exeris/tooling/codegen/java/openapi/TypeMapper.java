package eu.exeris.tooling.codegen.java.openapi;

import java.util.Map;

/**
 * Maps Java types to OpenAPI types and formats.
 * @author Exeris Team
 * @since 0.1.0
 */
public final class TypeMapper {

    private static final Map<String, String> TYPE_MAPPINGS = Map.ofEntries(
        Map.entry("String", "string"),
        Map.entry("java.lang.String", "string"),
        Map.entry("int", "integer"),
        Map.entry("Integer", "integer"),
        Map.entry("java.lang.Integer", "integer"),
        Map.entry("long", "integer"),
        Map.entry("Long", "integer"),
        Map.entry("java.lang.Long", "integer"),
        Map.entry("double", "number"),
        Map.entry("Double", "number"),
        Map.entry("float", "number"),
        Map.entry("Float", "number"),
        Map.entry("BigDecimal", "number"),
        Map.entry("java.math.BigDecimal", "number"),
        Map.entry("boolean", "boolean"),
        Map.entry("Boolean", "boolean"),
        Map.entry("java.lang.Boolean", "boolean"),
        Map.entry("LocalDate", "string"),
        Map.entry("java.time.LocalDate", "string"),
        Map.entry("LocalDateTime", "string"),
        Map.entry("java.time.LocalDateTime", "string"),
        Map.entry("OffsetDateTime", "string"),
        Map.entry("java.time.OffsetDateTime", "string"),
        Map.entry("Instant", "string"),
        Map.entry("java.time.Instant", "string"),
        Map.entry("UUID", "string"),
        Map.entry("java.util.UUID", "string")
    );

    private static final Map<String, String> FORMAT_MAPPINGS = Map.ofEntries(
        Map.entry("long", "int64"),
        Map.entry("Long", "int64"),
        Map.entry("java.lang.Long", "int64"),
        Map.entry("int", "int32"),
        Map.entry("Integer", "int32"),
        Map.entry("java.lang.Integer", "int32"),
        Map.entry("double", "double"),
        Map.entry("Double", "double"),
        Map.entry("float", "float"),
        Map.entry("Float", "float"),
        Map.entry("LocalDate", "date"),
        Map.entry("java.time.LocalDate", "date"),
        Map.entry("LocalDateTime", "date-time"),
        Map.entry("java.time.LocalDateTime", "date-time"),
        Map.entry("OffsetDateTime", "date-time"),
        Map.entry("java.time.OffsetDateTime", "date-time"),
        Map.entry("Instant", "date-time"),
        Map.entry("java.time.Instant", "date-time"),
        Map.entry("UUID", "uuid"),
        Map.entry("java.util.UUID", "uuid")
    );

    private TypeMapper() {}

    public static String toOpenApiType(String javaType) {
        if (javaType == null) return "string";
        String simple = simplifyType(javaType);
        return TYPE_MAPPINGS.getOrDefault(simple, "string");
    }

    public static String toOpenApiFormat(String javaType) {
        if (javaType == null) return null;
        String simple = simplifyType(javaType);
        return FORMAT_MAPPINGS.get(simple);
    }

    private static String simplifyType(String type) {
        int genericStart = type.indexOf('<');
        if (genericStart > 0) type = type.substring(0, genericStart);
        int lastDot = type.lastIndexOf('.');
        if (lastDot > 0 && !type.startsWith("java.")) return type.substring(lastDot + 1);
        return type;
    }
}

