package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DomainTypeKind")
class DomainTypeKindTest {

    @Test
    @DisplayName("a type string nothing recognises or refuses is OPAQUE, an enum's included")
    void unrecognisedTypeStringsAreOpaque() {
        assertThat(DomainTypeKind.of("com.example.domain.OrderStatus")).isEqualTo(DomainTypeKind.OPAQUE);
        assertThat(DomainTypeKind.of("com.example.domain.Money")).isEqualTo(DomainTypeKind.OPAQUE);
        // JDK types with a static valueOf(String) keep the string round-trip.
        assertThat(DomainTypeKind.of("java.time.DayOfWeek")).isEqualTo(DomainTypeKind.OPAQUE);
        assertThat(DomainTypeKind.of("java.sql.Timestamp")).isEqualTo(DomainTypeKind.OPAQUE);
    }

    @Test
    @DisplayName("Short, Byte and Float have kinds of their own, in every spelling")
    void narrowNumericsAreRecognised() {
        for (String type : new String[] {"short", "Short", "java.lang.Short"}) {
            assertThat(DomainTypeKind.of(type)).as(type).isEqualTo(DomainTypeKind.SHORT);
        }
        for (String type : new String[] {"byte", "Byte", "java.lang.Byte"}) {
            assertThat(DomainTypeKind.of(type)).as(type).isEqualTo(DomainTypeKind.BYTE);
        }
        for (String type : new String[] {"float", "Float", "java.lang.Float"}) {
            assertThat(DomainTypeKind.of(type)).as(type).isEqualTo(DomainTypeKind.FLOAT);
        }
    }

    @Test
    @DisplayName("OffsetDateTime and ZonedDateTime have kinds of their own, in both spellings")
    void zonedDateTimesAreRecognised() {
        assertThat(DomainTypeKind.of("java.time.OffsetDateTime")).isEqualTo(DomainTypeKind.OFFSET_DATE_TIME);
        assertThat(DomainTypeKind.of("OffsetDateTime")).isEqualTo(DomainTypeKind.OFFSET_DATE_TIME);
        assertThat(DomainTypeKind.of("java.time.ZonedDateTime")).isEqualTo(DomainTypeKind.ZONED_DATE_TIME);
        assertThat(DomainTypeKind.of("ZonedDateTime")).isEqualTo(DomainTypeKind.ZONED_DATE_TIME);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "java.math.BigInteger", "BigInteger", "char", "Character", "java.lang.Character",
            "java.time.LocalTime", "LocalTime", "java.time.OffsetTime", "java.time.Duration", "Duration",
            "java.time.Period", "java.time.Year", "java.time.YearMonth", "java.time.MonthDay",
            "java.time.ZoneId", "java.time.ZoneOffset", "java.util.Date", "java.util.Currency",
            "java.util.Locale", "java.net.URI", "java.net.URL", "java.lang.Object",
            "byte[]", "char[]", "java.time.Instant[]",
            "java.util.Map<java.lang.String,java.time.LocalDate>", "java.util.Optional<java.time.Instant>",
            "java.util.Set<java.lang.String>"})
    @DisplayName("a type with no column encoding is UNSTORABLE")
    void unstorableTypes(String type) {
        assertThat(DomainTypeKind.of(type)).isEqualTo(DomainTypeKind.UNSTORABLE);
    }

    @Test
    @DisplayName("a List is LIST whatever its element type")
    void listIsList() {
        assertThat(DomainTypeKind.of("java.util.List<java.time.Instant>")).isEqualTo(DomainTypeKind.LIST);
        assertThat(DomainTypeKind.of("List<String>")).isEqualTo(DomainTypeKind.LIST);
    }

    @Test
    @DisplayName("only the primitive spellings are primitive")
    void primitiveSpellings() {
        for (String type : new String[] {"long", "int", "short", "byte", "boolean", "float", "double"}) {
            assertThat(DomainTypeKind.isPrimitive(type)).as(type).isTrue();
        }
        for (String type : new String[] {"Long", "java.lang.Integer", "Short", "Byte", "Boolean", "Float",
                "java.lang.Double", "String", "java.util.UUID"}) {
            assertThat(DomainTypeKind.isPrimitive(type)).as(type).isFalse();
        }
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

    @Test
    @DisplayName("an enum named like a refused JDK type is ENUM once resolved, and refused while it is not")
    void enumNamedLikeARefusedType() {
        assertThat(DomainTypeKind.of(FieldMetadata.builder("window", "Duration")
                .enumType("com.example.domain.Duration").build())).isEqualTo(DomainTypeKind.ENUM);
        assertThat(DomainTypeKind.of(FieldMetadata.simple("window", "Duration")))
                .isEqualTo(DomainTypeKind.UNSTORABLE);
        // A parameterised type or an array is never an enum, whatever enumType says.
        assertThat(DomainTypeKind.of(FieldMetadata.builder("tags", "java.util.Set<Grade>")
                .enumType("com.example.domain.Grade").build())).isEqualTo(DomainTypeKind.UNSTORABLE);
    }
}
