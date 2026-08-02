package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

/**
 * Sample values the generated tests (T2, ADR-058) pass into generated code.
 *
 * <p>Dispatches on the metadata type <em>string</em>, the way {@link KernelTypeMapping} does,
 * rather than on a resolved {@code TypeName}: an unqualified {@code String} field resolves through
 * {@code ClassName.bestGuess} to a default-package {@code String} — which compiles, because the
 * emitted source says {@code String}, but is not equal to {@code ClassName.get(String.class)}.
 * Matching the string keeps the two dispatches on the same input.
 *
 * <p>Types with no synthesizable literal — an enum (whose constants this generator cannot know), a
 * collection, an unrecognised domain type — return {@code null}. That is a value of every reference
 * type, so the emitted call still compiles; what the caller must drop is the assertion, since
 * {@code null} round-trips and passes through vacuously. {@link #isNull} is the check for that.
 *
 * <p>Every literal is fixed, never derived from the clock or a random source — the emitted source
 * has to be byte-identical across runs (hard-constraint #3).
 *
 * @since 0.7.0
 */
final class KernelTestSamples {

    /** The fixed identifier every generated test uses where it needs one. */
    static final String FIXED_ID = "00000000-0000-4000-8000-000000000001";

    /** An arbitrary non-zero, so a value left at its default cannot pass an assertion. */
    static final long SAMPLE_NUMBER = 7L;

    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");
    private static final ClassName INSTANT = ClassName.get("java.time", "Instant");
    private static final ClassName LOCAL_DATE = ClassName.get("java.time", "LocalDate");
    private static final ClassName LOCAL_DATE_TIME = ClassName.get("java.time", "LocalDateTime");

    private KernelTestSamples() {
    }

    /** A literal of {@code typeName}, or {@code null} when none can be synthesized. */
    static CodeBlock of(String typeName) {
        return switch (typeName) {
            case "UUID", "java.util.UUID" -> CodeBlock.of("$T.fromString($S)", UUID, FIXED_ID);
            case "String", "java.lang.String" -> CodeBlock.of("$S", "sample");
            case "boolean", "Boolean", "java.lang.Boolean" -> CodeBlock.of("true");
            case "int", "Integer", "java.lang.Integer" -> CodeBlock.of("$L", SAMPLE_NUMBER);
            case "long", "Long", "java.lang.Long" -> CodeBlock.of("$LL", SAMPLE_NUMBER);
            case "double", "Double", "java.lang.Double" -> CodeBlock.of("$L.0", SAMPLE_NUMBER);
            // toPlainString() → new BigDecimal(String) is the repository's round-trip, and it
            // preserves scale, so an unscaled literal compares equal after it.
            case "BigDecimal", "java.math.BigDecimal" ->
                    CodeBlock.of("new $T($S)", BIG_DECIMAL, String.valueOf(SAMPLE_NUMBER));
            case "Instant", "java.time.Instant" -> CodeBlock.of("$T.EPOCH", INSTANT);
            case "LocalDate", "java.time.LocalDate" -> CodeBlock.of("$T.of(2000, 1, 1)", LOCAL_DATE);
            case "LocalDateTime", "java.time.LocalDateTime" ->
                    CodeBlock.of("$T.of(2000, 1, 1, 0, 0)", LOCAL_DATE_TIME);
            default -> CodeBlock.of("null");
        };
    }

    /** Whether {@link #of} gave up on this type, so an assertion over the value would be vacuous. */
    static boolean isNull(CodeBlock sample) {
        return "null".equals(sample.toString());
    }
}
