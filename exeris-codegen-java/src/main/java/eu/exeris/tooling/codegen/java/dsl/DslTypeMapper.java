package eu.exeris.tooling.codegen.java.dsl;

import java.util.Map;

/**
 * Maps Java types to DSL/TypeScript types.
 * @author Exeris Team
 * @since 0.1.0
 */
public final class DslTypeMapper {

    private static final Map<String, String> TYPE_MAPPINGS = Map.ofEntries(
        Map.entry("String", "string"),
        Map.entry("java.lang.String", "string"),
        Map.entry("int", "number"),
        Map.entry("Integer", "number"),
        Map.entry("java.lang.Integer", "number"),
        Map.entry("long", "number"),
        Map.entry("Long", "number"),
        Map.entry("java.lang.Long", "number"),
        Map.entry("double", "number"),
        Map.entry("Double", "number"),
        Map.entry("float", "number"),
        Map.entry("Float", "number"),
        Map.entry("BigDecimal", "number"),
        Map.entry("java.math.BigDecimal", "number"),
        Map.entry("boolean", "boolean"),
        Map.entry("Boolean", "boolean"),
        Map.entry("java.lang.Boolean", "boolean"),
        Map.entry("LocalDate", "date"),
        Map.entry("java.time.LocalDate", "date"),
        Map.entry("LocalDateTime", "datetime"),
        Map.entry("java.time.LocalDateTime", "datetime"),
        Map.entry("OffsetDateTime", "datetime"),
        Map.entry("java.time.OffsetDateTime", "datetime"),
        Map.entry("Instant", "datetime"),
        Map.entry("java.time.Instant", "datetime"),
        Map.entry("UUID", "uuid"),
        Map.entry("java.util.UUID", "uuid")
    );

    private DslTypeMapper() {}

    public static String mapType(String javaType) {
        if (javaType == null) return "string";
        String simple = simplifyType(javaType);
        return TYPE_MAPPINGS.getOrDefault(simple, "string");
    }

    public static String pluralize(String name) {
        if (name == null || name.isEmpty()) return name;
        if (name.endsWith("y")) return name.substring(0, name.length() - 1) + "ies";
        if (name.endsWith("s") || name.endsWith("x") || name.endsWith("ch") || name.endsWith("sh")) return name + "es";
        return name + "s";
    }

    private static String simplifyType(String type) {
        int genericStart = type.indexOf('<');
        if (genericStart > 0) type = type.substring(0, genericStart);
        int lastDot = type.lastIndexOf('.');
        if (lastDot > 0 && !type.startsWith("java.")) return type.substring(lastDot + 1);
        return type;
    }
}
