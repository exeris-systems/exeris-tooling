package eu.exeris.tooling.codegen.java.openapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TypeMapper (OpenAPI)")
class TypeMapperTest {

    @Test
    @DisplayName("toOpenApiType: null → \"string\"; unknown → \"string\"")
    void typeFallbacks() {
        assertThat(TypeMapper.toOpenApiType(null)).isEqualTo("string");
        assertThat(TypeMapper.toOpenApiType("CustomBean")).isEqualTo("string");
    }

    @Test
    @DisplayName("toOpenApiType: integer types map to \"integer\"; floating to \"number\"")
    void typeNumericMapping() {
        assertThat(TypeMapper.toOpenApiType("int")).isEqualTo("integer");
        assertThat(TypeMapper.toOpenApiType("Integer")).isEqualTo("integer");
        assertThat(TypeMapper.toOpenApiType("long")).isEqualTo("integer");
        assertThat(TypeMapper.toOpenApiType("Long")).isEqualTo("integer");
        assertThat(TypeMapper.toOpenApiType("double")).isEqualTo("number");
        assertThat(TypeMapper.toOpenApiType("Double")).isEqualTo("number");
        assertThat(TypeMapper.toOpenApiType("BigDecimal")).isEqualTo("number");
    }

    @Test
    @DisplayName("toOpenApiType: boolean → \"boolean\"; UUID / LocalDate / Instant → \"string\"")
    void typeNonNumericMapping() {
        assertThat(TypeMapper.toOpenApiType("boolean")).isEqualTo("boolean");
        assertThat(TypeMapper.toOpenApiType("Boolean")).isEqualTo("boolean");
        assertThat(TypeMapper.toOpenApiType("UUID")).isEqualTo("string");
        assertThat(TypeMapper.toOpenApiType("java.util.UUID")).isEqualTo("string");
        assertThat(TypeMapper.toOpenApiType("LocalDate")).isEqualTo("string");
        assertThat(TypeMapper.toOpenApiType("Instant")).isEqualTo("string");
    }

    @Test
    @DisplayName("toOpenApiFormat: null → null; unknown → null")
    void formatFallbacks() {
        assertThat(TypeMapper.toOpenApiFormat(null)).isNull();
        assertThat(TypeMapper.toOpenApiFormat("String")).isNull();
        assertThat(TypeMapper.toOpenApiFormat("CustomBean")).isNull();
    }

    @Test
    @DisplayName("toOpenApiFormat: int32 / int64 / double / float / date / date-time / uuid")
    void formatKnownTypes() {
        assertThat(TypeMapper.toOpenApiFormat("int")).isEqualTo("int32");
        assertThat(TypeMapper.toOpenApiFormat("Integer")).isEqualTo("int32");
        assertThat(TypeMapper.toOpenApiFormat("long")).isEqualTo("int64");
        assertThat(TypeMapper.toOpenApiFormat("Long")).isEqualTo("int64");
        assertThat(TypeMapper.toOpenApiFormat("double")).isEqualTo("double");
        assertThat(TypeMapper.toOpenApiFormat("Float")).isEqualTo("float");
        assertThat(TypeMapper.toOpenApiFormat("LocalDate")).isEqualTo("date");
        assertThat(TypeMapper.toOpenApiFormat("LocalDateTime")).isEqualTo("date-time");
        assertThat(TypeMapper.toOpenApiFormat("OffsetDateTime")).isEqualTo("date-time");
        assertThat(TypeMapper.toOpenApiFormat("Instant")).isEqualTo("date-time");
        assertThat(TypeMapper.toOpenApiFormat("UUID")).isEqualTo("uuid");
    }

    @Test
    @DisplayName("simplifyType strips generic parameters and custom package prefixes; preserves java.* FQCNs")
    void simplifyTypeBranches() {
        // Generic-stripping branch.
        assertThat(TypeMapper.toOpenApiType("List<UUID>")).isEqualTo("string");
        // Custom package prefix stripped.
        assertThat(TypeMapper.toOpenApiType("com.acme.Integer")).isEqualTo("integer");
        // java.* preserved by simplifier (direct lookup).
        assertThat(TypeMapper.toOpenApiType("java.lang.String")).isEqualTo("string");
    }
}
