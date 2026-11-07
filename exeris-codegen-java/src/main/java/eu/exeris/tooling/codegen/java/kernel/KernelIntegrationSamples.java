package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The JSON values the generated integration tests send (ADR-058 Amendment 1), and the request
 * bodies built from them.
 *
 * <p>A value is either fixed text, the same on every run, or an expression the test evaluates at
 * runtime: a unique column needs a value no earlier row holds, and a relationship needs the key of
 * a row the test created. Fixed values sit inside the field's declared bounds, so a valid body is
 * accepted by the handler's {@code @Validation} guards and by the migration's {@code CHECK}
 * constraints alike. A type with no JSON form both the request decoder and the test know, a
 * {@code pattern} rule, and a unique column of a bounded or non-integral type have no value, and a
 * required field without one makes the entity uncreatable.
 *
 * <p>Every literal is fixed, never derived from the clock or a random source, so the emitted source
 * is the same for the same metadata.
 *
 * @since 0.11
 */
final class KernelIntegrationSamples {

    /** The text a fixed string value is cut from or repeats. */
    private static final String SAMPLE = "sample";

    /** The value of a numeric field with no bound, and the start of a bounded one's search. */
    private static final long SAMPLE_NUMBER = KernelTestSamples.SAMPLE_NUMBER;

    /** The shortest unique string {@code IntegrationHttp.unique} produces for a run's rows. */
    private static final int MIN_UNIQUE_LENGTH = 3;

    /**
     * One JSON value.
     *
     * @param fixed      the JSON text when it is the same on every run, else {@code null}
     * @param expression the expression producing the JSON text at runtime when {@code fixed} is
     *                   {@code null}
     * @param assertable whether a response echoes the fixed text byte for byte, so a test may look
     *                   for it in the body
     */
    record Value(String fixed, CodeBlock expression, boolean assertable) {

        static Value of(String json, boolean assertable) {
            return new Value(json, null, assertable);
        }

        static Value runtime(CodeBlock expression) {
            return new Value(null, expression, false);
        }
    }

    /**
     * One member of a JSON object.
     *
     * @param name  the member's name: the entity property
     * @param value its value
     */
    record Member(String name, Value value) {
    }

    private KernelIntegrationSamples() {
    }

    /**
     * The value a valid body gives {@code field}, or nothing when no value can be synthesized.
     *
     * @param field the field
     * @param http  the emitted {@code IntegrationHttp} type, which produces runtime values
     * @return the value
     */
    static Optional<Value> valid(FieldMetadata field, ClassName http) {
        if (field.pattern() != null && !field.pattern().isEmpty()) {
            return Optional.empty();
        }
        String type = KernelValidationRules.simpleTypeName(field.type());
        if (field.unique()) {
            return unique(field, type, http);
        }
        if (KernelValidationRules.isStringType(type)) {
            return stringLength(field).map(length -> Value.of(quote(text(length)), true));
        }
        if (isIntegral(type) || KernelValidationRules.isBigDecimal(type)) {
            return number(field).map(n -> Value.of(Long.toString(n), isIntegral(type)));
        }
        if (isFloating(type)) {
            return number(field).map(n -> Value.of(n + ".0", false));
        }
        return Optional.ofNullable(fixedOther(type));
    }

    /** The JSON value of a string of {@code length} characters cut from the sample text. */
    static String text(int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(SAMPLE.charAt(i % SAMPLE.length()));
        }
        return text.toString();
    }

    /** {@code value} between double quotes. */
    static String quote(String value) {
        return '"' + value + '"';
    }

    /**
     * The Java expression of a JSON object holding {@code members} in order: string literals for
     * the fixed parts, joined by {@code +} with the runtime ones.
     *
     * @param members the members
     * @return an expression of type {@code String}
     */
    static CodeBlock body(List<Member> members) {
        List<CodeBlock> parts = new ArrayList<>();
        StringBuilder pending = new StringBuilder("{");
        for (int i = 0; i < members.size(); i++) {
            Member member = members.get(i);
            if (i > 0) {
                pending.append(',');
            }
            pending.append(quote(member.name())).append(':');
            if (member.value().fixed() != null) {
                pending.append(member.value().fixed());
            } else {
                parts.add(CodeBlock.of("$S", pending.toString()));
                parts.add(member.value().expression());
                pending.setLength(0);
            }
        }
        pending.append('}');
        parts.add(CodeBlock.of("$S", pending.toString()));
        return CodeBlock.join(parts, " + ");
    }

    /** Whether {@code type} is an integral number the JSON codec writes back unchanged. */
    static boolean isIntegral(String type) {
        return switch (type) {
            case "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte" -> true;
            default -> false;
        };
    }

    /** Whether {@code type} is a floating-point number. */
    static boolean isFloating(String type) {
        return switch (type) {
            case "float", "Float", "double", "Double" -> true;
            default -> false;
        };
    }

    private static Optional<Value> unique(FieldMetadata field, String type, ClassName http) {
        if (KernelValidationRules.isStringType(type)) {
            int max = field.maxLength() == null ? Integer.MAX_VALUE : field.maxLength();
            int length = Math.max(Math.max(SAMPLE.length(), MIN_UNIQUE_LENGTH),
                    field.minLength() == null ? 0 : field.minLength());
            if (length > max) {
                return Optional.empty();
            }
            return Optional.of(Value.runtime(CodeBlock.of("$T.jsonString($T.unique($L))", http, http, length)));
        }
        if (isIntegral(type) && field.min() == null && field.max() == null) {
            return Optional.of(Value.runtime(CodeBlock.of("$T.toString($T.sequence())", Long.class, http)));
        }
        if ("UUID".equals(type)) {
            return Optional.of(Value.runtime(CodeBlock.of("$T.jsonString($T.randomUUID().toString())", http,
                    ClassName.get("java.util", "UUID"))));
        }
        return Optional.empty();
    }

    /** The length of a valid string: the sample's own, moved inside the declared bounds. */
    private static Optional<Integer> stringLength(FieldMetadata field) {
        int min = field.minLength() == null ? 0 : field.minLength();
        int max = field.maxLength() == null ? Integer.MAX_VALUE : field.maxLength();
        if (min > max) {
            return Optional.empty();
        }
        return Optional.of(Math.min(Math.max(SAMPLE.length(), min), max));
    }

    /** A valid number: the sample number, moved inside the declared bounds. */
    private static Optional<Long> number(FieldMetadata field) {
        long min = field.min() == null ? Long.MIN_VALUE : field.min();
        long max = field.max() == null ? Long.MAX_VALUE : field.max();
        if (min > max) {
            return Optional.empty();
        }
        return Optional.of(Math.min(Math.max(SAMPLE_NUMBER, min), max));
    }

    private static Value fixedOther(String type) {
        return switch (type) {
            case "UUID" -> Value.of(quote(KernelTestSamples.FIXED_ID), true);
            case "boolean", "Boolean" -> Value.of("true", true);
            case "Instant", "OffsetDateTime", "ZonedDateTime" -> Value.of(quote("2000-01-01T00:00:00Z"), false);
            case "LocalDate" -> Value.of(quote("2000-01-01"), false);
            case "LocalDateTime" -> Value.of(quote("2000-01-01T00:00:00"), false);
            default -> null;
        };
    }
}
