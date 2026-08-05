package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code @Validation} rules a generated handler enforces (T10), as data.
 *
 * <p>Two emitters read this: {@link KernelHandlerGenerator}, which turns each rule into a
 * {@code 400 BAD_REQUEST} guard, and {@link KernelHandlerTestGenerator}, which turns each rule into
 * a case that drives the handler across it. That is the whole reason this type exists — the two must
 * agree on <em>which</em> fields carry a check, or the generated test silently covers a different set
 * of guards than the handler emits and reports green either way.
 *
 * <p>What is <em>not</em> shared is the comparison itself. The handler decides that {@code min} is
 * inclusive by emitting {@code <}; the test asserts inclusiveness by driving the boundary value
 * itself and expecting it through. Deriving the operator here and handing it to both would make the
 * pair circular — they would agree by construction rather than by behaviour.
 *
 * <p>Rule order per field is fixed and matches emission order, so the handler's guards and the
 * generated tests stay in step and output stays deterministic (hard-constraint #3).
 *
 * @since 0.7.0
 */
final class KernelValidationRules {

    /** The rule kinds that are type-safe to enforce; anything else in {@code @Validation} is skipped. */
    enum Kind { NOT_NULL, MIN_LENGTH, MAX_LENGTH, PATTERN, MIN, MAX }

    /**
     * One check.
     *
     * @param kind    which check
     * @param bound   the numeric or length bound; {@code null} for {@link Kind#NOT_NULL} and
     *                {@link Kind#PATTERN}
     * @param pattern the regex; {@code null} unless {@link Kind#PATTERN}
     */
    record Rule(Kind kind, Long bound, String pattern) {
    }

    /**
     * Every rule on one field, plus the names the emitters use for it.
     *
     * @param field    the metadata the rules came from
     * @param local    the handler-scope local the value is read into once. Prefixed so it can never
     *                 collide with a handler variable — a field literally named {@code id} would
     *                 otherwise clash with {@code handleUpdate}'s path-id (T22).
     * @param accessor the getter the handler calls
     * @param mutator  the setter a generated test calls to stage a value
     * @param rules    in emission order
     */
    record FieldRules(FieldMetadata field, String local, String accessor, String mutator,
                      List<Rule> rules) {

        boolean has(Kind kind) {
            return rules.stream().anyMatch(r -> r.kind() == kind);
        }
    }

    private KernelValidationRules() {
    }

    /** Every field that carries at least one enforceable rule, in declaration order. */
    static List<FieldRules> of(List<FieldMetadata> fields) {
        List<FieldRules> all = new ArrayList<>();
        for (FieldMetadata f : fields) {
            List<Rule> rules = rulesFor(f);
            if (rules.isEmpty()) {
                continue;
            }
            String pascal = NameCasing.pascal(f.name());
            all.add(new FieldRules(f, "val" + pascal, "get" + pascal, "set" + pascal, rules));
        }
        return List.copyOf(all);
    }

    private static List<Rule> rulesFor(FieldMetadata f) {
        boolean nullCheck = f.required() && !isPrimitive(f.type());
        boolean strChecks = isStringType(f.type())
                && (f.minLength() != null || f.maxLength() != null || f.pattern() != null);
        boolean numChecks = (f.min() != null || f.max() != null) && isNumeric(f.type());
        if (!nullCheck && !strChecks && !numChecks) {
            return List.of();
        }

        List<Rule> rules = new ArrayList<>();
        if (nullCheck) {
            rules.add(new Rule(Kind.NOT_NULL, null, null));
        }
        if (strChecks) {
            if (f.minLength() != null) {
                rules.add(new Rule(Kind.MIN_LENGTH, (long) f.minLength(), null));
            }
            if (f.maxLength() != null) {
                rules.add(new Rule(Kind.MAX_LENGTH, (long) f.maxLength(), null));
            }
            if (f.pattern() != null) {
                rules.add(new Rule(Kind.PATTERN, null, f.pattern()));
            }
        }
        if (numChecks && f.min() != null) {
            rules.add(new Rule(Kind.MIN, f.min(), null));
        }
        if (numChecks && f.max() != null) {
            rules.add(new Rule(Kind.MAX, f.max(), null));
        }
        return rules;
    }

    static String simpleTypeName(String type) {
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    static boolean isPrimitive(String type) {
        return switch (type) {
            case "int", "long", "short", "byte", "boolean", "char", "float", "double" -> true;
            default -> false;
        };
    }

    static boolean isStringType(String type) {
        return "String".equals(simpleTypeName(type));
    }

    static boolean isBigDecimal(String type) {
        return "BigDecimal".equals(simpleTypeName(type));
    }

    static boolean isPrimitiveNumeric(String type) {
        return switch (type) {
            case "int", "long", "short", "byte", "float", "double" -> true;
            default -> false;
        };
    }

    static boolean isBoxedNumeric(String type) {
        return switch (simpleTypeName(type)) {
            case "Integer", "Long", "Short", "Byte", "Float", "Double" -> true;
            default -> false;
        };
    }

    static boolean isNumeric(String type) {
        return isBigDecimal(type) || isPrimitiveNumeric(type) || isBoxedNumeric(type);
    }
}
