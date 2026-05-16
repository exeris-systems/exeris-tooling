package eu.exeris.tooling.codegen.java.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DslTypeMapper")
class DslTypeMapperTest {

    @Test
    @DisplayName("mapType returns \"string\" for null input")
    void mapTypeNullDefaultsToString() {
        assertThat(DslTypeMapper.mapType(null)).isEqualTo("string");
    }

    @Test
    @DisplayName("mapType returns \"string\" for unknown / unmapped types")
    void mapTypeUnknownDefaultsToString() {
        assertThat(DslTypeMapper.mapType("com.example.MyCustomType")).isEqualTo("string");
        assertThat(DslTypeMapper.mapType("Unrecognised")).isEqualTo("string");
    }

    @Test
    @DisplayName("mapType maps every textual / numeric / temporal / UUID variant")
    void mapTypeKnownTypes() {
        // Strings
        assertThat(DslTypeMapper.mapType("String")).isEqualTo("string");
        assertThat(DslTypeMapper.mapType("java.lang.String")).isEqualTo("string");

        // Whole numbers
        assertThat(DslTypeMapper.mapType("int")).isEqualTo("number");
        assertThat(DslTypeMapper.mapType("Integer")).isEqualTo("number");
        assertThat(DslTypeMapper.mapType("java.lang.Integer")).isEqualTo("number");
        assertThat(DslTypeMapper.mapType("long")).isEqualTo("number");
        assertThat(DslTypeMapper.mapType("Long")).isEqualTo("number");
        assertThat(DslTypeMapper.mapType("java.lang.Long")).isEqualTo("number");

        // Real numbers
        assertThat(DslTypeMapper.mapType("double")).isEqualTo("number");
        assertThat(DslTypeMapper.mapType("Double")).isEqualTo("number");
        assertThat(DslTypeMapper.mapType("float")).isEqualTo("number");
        assertThat(DslTypeMapper.mapType("Float")).isEqualTo("number");
        assertThat(DslTypeMapper.mapType("BigDecimal")).isEqualTo("number");
        assertThat(DslTypeMapper.mapType("java.math.BigDecimal")).isEqualTo("number");

        // Booleans
        assertThat(DslTypeMapper.mapType("boolean")).isEqualTo("boolean");
        assertThat(DslTypeMapper.mapType("Boolean")).isEqualTo("boolean");
        assertThat(DslTypeMapper.mapType("java.lang.Boolean")).isEqualTo("boolean");

        // Temporal
        assertThat(DslTypeMapper.mapType("LocalDate")).isEqualTo("date");
        assertThat(DslTypeMapper.mapType("java.time.LocalDate")).isEqualTo("date");
        assertThat(DslTypeMapper.mapType("LocalDateTime")).isEqualTo("datetime");
        assertThat(DslTypeMapper.mapType("java.time.LocalDateTime")).isEqualTo("datetime");
        assertThat(DslTypeMapper.mapType("OffsetDateTime")).isEqualTo("datetime");
        assertThat(DslTypeMapper.mapType("java.time.OffsetDateTime")).isEqualTo("datetime");
        assertThat(DslTypeMapper.mapType("Instant")).isEqualTo("datetime");
        assertThat(DslTypeMapper.mapType("java.time.Instant")).isEqualTo("datetime");

        // UUID
        assertThat(DslTypeMapper.mapType("UUID")).isEqualTo("uuid");
        assertThat(DslTypeMapper.mapType("java.util.UUID")).isEqualTo("uuid");
    }

    @Test
    @DisplayName("mapType strips generic parameters before lookup")
    void mapTypeStripsGenerics() {
        // List<String> simplifies to "List" → unknown → "string".
        // The point is the simplifier removed the generic without throwing.
        assertThat(DslTypeMapper.mapType("List<String>")).isEqualTo("string");
        // Optional<UUID> simplifies to "Optional" → unknown → "string".
        assertThat(DslTypeMapper.mapType("Optional<UUID>")).isEqualTo("string");
    }

    @Test
    @DisplayName("mapType strips the package prefix for non-java.* types")
    void mapTypeStripsCustomPackagePrefix() {
        // "com.example.UUID" → simplifies to "UUID" → "uuid".
        assertThat(DslTypeMapper.mapType("com.example.UUID")).isEqualTo("uuid");
        // "org.acme.Boolean" → simplifies to "Boolean" → "boolean".
        assertThat(DslTypeMapper.mapType("org.acme.Boolean")).isEqualTo("boolean");
    }

    @Test
    @DisplayName("mapType keeps java.* FQCNs intact (looked up directly)")
    void mapTypeKeepsJavaPackagePrefix() {
        // java.lang.* and java.util.* lookups go straight to the mapping
        // table; the simplifier explicitly preserves the prefix.
        assertThat(DslTypeMapper.mapType("java.lang.String")).isEqualTo("string");
        assertThat(DslTypeMapper.mapType("java.util.UUID")).isEqualTo("uuid");
    }

    @Test
    @DisplayName("pluralize returns null/empty inputs unchanged")
    void pluralizeNullAndEmpty() {
        assertThat(DslTypeMapper.pluralize(null)).isNull();
        assertThat(DslTypeMapper.pluralize("")).isEmpty();
    }

    @Test
    @DisplayName("pluralize: \"y\" → \"ies\" replacement")
    void pluralizeYEndsInIes() {
        assertThat(DslTypeMapper.pluralize("Category")).isEqualTo("Categories");
        assertThat(DslTypeMapper.pluralize("entity")).isEqualTo("entities");
    }

    @Test
    @DisplayName("pluralize: \"s\" / \"x\" / \"ch\" / \"sh\" → append \"es\"")
    void pluralizeSibilantEndingsAppendEs() {
        assertThat(DslTypeMapper.pluralize("Class")).isEqualTo("Classes");
        assertThat(DslTypeMapper.pluralize("Box")).isEqualTo("Boxes");
        assertThat(DslTypeMapper.pluralize("Branch")).isEqualTo("Branches");
        assertThat(DslTypeMapper.pluralize("Brush")).isEqualTo("Brushes");
    }

    @Test
    @DisplayName("pluralize: regular nouns append \"s\"")
    void pluralizeRegularAppendsS() {
        assertThat(DslTypeMapper.pluralize("Order")).isEqualTo("Orders");
        assertThat(DslTypeMapper.pluralize("Product")).isEqualTo("Products");
    }
}
