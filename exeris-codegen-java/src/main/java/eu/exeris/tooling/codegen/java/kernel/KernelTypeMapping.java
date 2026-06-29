package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

/**
 * Single source of truth for mapping a metadata field-type string to a JavaPoet
 * {@link TypeName}, shared by the Java-emitting kernel generators (repository,
 * service, …). Primitives map to keyword types; common simple JDK type names are
 * pinned to their fully qualified {@link ClassName} so JavaPoet emits the matching
 * import (a bare {@code ClassName.bestGuess("BigDecimal")} would land in the default
 * package and not import); everything else falls back to {@code bestGuess} on the
 * raw type (generic arguments dropped — finders filter on a scalar column).
 *
 * <p>Extracted so the repository and service finder emitters do not carry
 * drift-prone private copies (the two must agree on the parameter type they emit
 * for the same field).
 */
final class KernelTypeMapping {

    private static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");
    private static final ClassName INSTANT = ClassName.get("java.time", "Instant");
    private static final ClassName LOCAL_DATE = ClassName.get("java.time", "LocalDate");
    private static final ClassName LOCAL_DATE_TIME = ClassName.get("java.time", "LocalDateTime");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");

    private KernelTypeMapping() {
    }

    /** Maps a field-type string to its JavaPoet parameter {@link TypeName}. */
    static TypeName typeNameOf(String type) {
        return switch (type) {
            case "boolean" -> TypeName.BOOLEAN;
            case "int" -> TypeName.INT;
            case "long" -> TypeName.LONG;
            case "double" -> TypeName.DOUBLE;
            case "BigDecimal" -> BIG_DECIMAL;
            case "Instant" -> INSTANT;
            case "LocalDate" -> LOCAL_DATE;
            case "LocalDateTime" -> LOCAL_DATE_TIME;
            // Both the short form and the FQCN — the processor pins UUID FKs to
            // java.util.UUID, but a filterable field declared as the short "UUID"
            // must still resolve to the imported type, not bestGuess's default-package
            // ClassName (which would emit an un-importable, non-compiling parameter).
            case "UUID", "java.util.UUID" -> UUID;
            default -> {
                int lt = type.indexOf('<');
                yield ClassName.bestGuess(lt >= 0 ? type.substring(0, lt) : type);
            }
        };
    }
}
