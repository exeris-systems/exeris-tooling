package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.tooling.codegen.java.support.DomainTypeKind;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The {@code defaultValue} of a body-bound action parameter, applied by the action request record.
 *
 * <p>A body decoder binds a component the body omits, or sends as {@code null}, to {@code null}
 * only when the component is a reference type; a primitive one reads as its zero value, which is
 * indistinguishable from a value the caller sent. A defaulted component is therefore declared
 * boxed, and the record's compact constructor replaces {@code null} with the declared default.
 * This is the rule the query path applies to an absent query key, so both bindings agree on what
 * an unsent value means, and every handler that decodes the record inherits it.
 *
 * <p>The default is parsed from its declared text when the record is constructed, by the same
 * JDK conversion the query path applies to a query value. A default that does not convert fails
 * that construction; a JSON decoder reports a failed construction as a malformed body.
 */
final class KernelActionRequestDefaults {

    private static final ClassName ILLEGAL_ARGUMENT_EXCEPTION =
            ClassName.get("java.lang", "IllegalArgumentException");

    private static final Set<String> PARSED_TIME_TYPES = Set.of(
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.Instant",
            "java.time.OffsetDateTime", "java.time.ZonedDateTime");

    private static final Set<String> CHAR_TYPES = Set.of("char", "Character", "java.lang.Character");

    private static final String VALUE_OF = "$T.valueOf($S)";

    private KernelActionRequestDefaults() {
    }

    /**
     * Whether a body parameter declares a default its record applies.
     *
     * @param p a body-bound action parameter
     * @return {@code true} when its {@code defaultValue} is set and not blank
     */
    static boolean defaulted(ActionParamMetadata p) {
        return p.defaultValue() != null && !p.defaultValue().isBlank();
    }

    /**
     * The record component type of a body parameter: its declared type, boxed when it is
     * defaulted, so an omitted value is observable as {@code null}.
     *
     * @param p        a body-bound action parameter
     * @param declared the parameter's declared type
     * @return the component type
     */
    static TypeName componentType(ActionParamMetadata p, TypeName declared) {
        return defaulted(p) ? declared.box() : declared;
    }

    /**
     * The compact constructor that applies every declared default, or {@code null} when no body
     * parameter declares one, so a record without defaults is emitted unchanged.
     *
     * @param bodyParams the body-bound parameters, in component order
     * @param typeOf     the declared type of a parameter
     * @return the compact constructor, or {@code null}
     */
    static MethodSpec compactConstructor(List<ActionParamMetadata> bodyParams,
                                         Function<String, TypeName> typeOf) {
        List<ActionParamMetadata> defaulted = bodyParams.stream()
                .filter(KernelActionRequestDefaults::defaulted)
                .toList();
        if (defaulted.isEmpty()) {
            return null;
        }
        MethodSpec.Builder constructor = MethodSpec.compactConstructorBuilder()
                .addModifiers(Modifier.PUBLIC);
        for (ActionParamMetadata p : defaulted) {
            constructor.beginControlFlow("if ($L == null)", p.name())
                    .addCode(assignDefault(p, typeOf.apply(p.type())))
                    .endControlFlow();
        }
        return constructor.build();
    }

    private static CodeBlock assignDefault(ActionParamMetadata p, TypeName declared) {
        String value = p.defaultValue();
        String type = p.type();
        if (CHAR_TYPES.contains(type)) {
            return value.length() == 1
                    ? assign(p, CodeBlock.of("$S.charAt(0)", value))
                    : refuse("default value is not a single character: ", value);
        }
        if (DomainTypeKind.of(type) == DomainTypeKind.BOOL) {
            return booleanDefault(p, value);
        }
        return assign(p, parse(type, declared, value));
    }

    private static CodeBlock booleanDefault(ActionParamMetadata p, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return assign(p, CodeBlock.of("$T.TRUE", Boolean.class));
        }
        if ("false".equalsIgnoreCase(value)) {
            return assign(p, CodeBlock.of("$T.FALSE", Boolean.class));
        }
        return refuse("default value is not a boolean: ", value);
    }

    private static CodeBlock parse(String type, TypeName declared, String value) {
        if (PARSED_TIME_TYPES.contains(type)) {
            return CodeBlock.of("$T.parse($S)", declared, value);
        }
        return switch (DomainTypeKind.of(type)) {
            case STRING -> CodeBlock.of("$S", value);
            case INT -> CodeBlock.of(VALUE_OF, Integer.class, value);
            case LONG -> CodeBlock.of(VALUE_OF, Long.class, value);
            case SHORT -> CodeBlock.of(VALUE_OF, Short.class, value);
            case BYTE -> CodeBlock.of(VALUE_OF, Byte.class, value);
            case FLOAT -> CodeBlock.of(VALUE_OF, Float.class, value);
            case DOUBLE -> CodeBlock.of(VALUE_OF, Double.class, value);
            case BIG_DECIMAL -> CodeBlock.of("new $T($S)", ClassName.get("java.math", "BigDecimal"), value);
            case UUID -> CodeBlock.of("$T.fromString($S)", ClassName.get("java.util", "UUID"), value);
            default -> CodeBlock.of(VALUE_OF, declared.box(), value);
        };
    }

    private static CodeBlock assign(ActionParamMetadata p, CodeBlock value) {
        return CodeBlock.builder().addStatement("$L = $L", p.name(), value).build();
    }

    private static CodeBlock refuse(String message, String value) {
        return CodeBlock.builder()
                .addStatement("throw new $T($S)", ILLEGAL_ARGUMENT_EXCEPTION, message + value)
                .build();
    }
}
