package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DomainTypeKind")
class DomainTypeKindTest {

    @Test
    @DisplayName("a type string nothing recognises is OPAQUE, an enum's included")
    void unrecognisedTypeStringsAreOpaque() {
        assertThat(DomainTypeKind.of("java.time.OffsetDateTime")).isEqualTo(DomainTypeKind.OPAQUE);
        assertThat(DomainTypeKind.of("java.time.ZonedDateTime")).isEqualTo(DomainTypeKind.OPAQUE);
        assertThat(DomainTypeKind.of("java.math.BigInteger")).isEqualTo(DomainTypeKind.OPAQUE);
        assertThat(DomainTypeKind.of("java.lang.Float")).isEqualTo(DomainTypeKind.OPAQUE);
        assertThat(DomainTypeKind.of("short")).isEqualTo(DomainTypeKind.OPAQUE);
        assertThat(DomainTypeKind.of("java.util.Set<java.lang.String>")).isEqualTo(DomainTypeKind.OPAQUE);
        assertThat(DomainTypeKind.of("com.example.domain.OrderStatus")).isEqualTo(DomainTypeKind.OPAQUE);
    }

    @Test
    @DisplayName("a parameterised type is OPAQUE even when an argument is a recognised type")
    void parameterisedTypesAreOpaque() {
        assertThat(DomainTypeKind.of("java.util.Map<java.lang.String,java.time.LocalDate>"))
                .isEqualTo(DomainTypeKind.OPAQUE);
        assertThat(DomainTypeKind.of("java.util.Optional<java.time.Instant>"))
                .isEqualTo(DomainTypeKind.OPAQUE);
        assertThat(DomainTypeKind.of("java.util.List<java.time.Instant>")).isEqualTo(DomainTypeKind.LIST);
    }

    @Test
    @DisplayName("a field is ENUM when its type is unrecognised and its enumType is set")
    void enumFromEnumType() {
        assertThat(DomainTypeKind.of(FieldMetadata.builder("status", "com.example.domain.OrderStatus")
                .enumType("com.example.domain.OrderStatus").build())).isEqualTo(DomainTypeKind.ENUM);
        assertThat(DomainTypeKind.of(FieldMetadata.simple("status", "com.example.domain.OrderStatus")))
                .isEqualTo(DomainTypeKind.OPAQUE);
        // A recognised type keeps its kind whatever enumType says: the column holds that type.
        assertThat(DomainTypeKind.of(FieldMetadata.builder("code", "String")
                .enumType("com.example.domain.OrderStatus").build())).isEqualTo(DomainTypeKind.STRING);
    }
}
