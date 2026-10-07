package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.EnumMetadata;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Which emitted enum a field's values come from.
 *
 * <p>The processor writes one {@code enum_<Name>.json} beside the entities for every enum an
 * {@code @ExerisDomain} field is typed as, and leaves {@code FieldMetadata.enumType} unset. The
 * TypeScript emitter treats a field as an enum when its {@code enumType}, else its {@code type},
 * names one of those enums — by qualified name, else by simple name — and offers an enum filter
 * only for such a field. This class applies the same rule on the Java side, so the list route
 * accepts an enum filter for exactly the fields the front sends one for.
 *
 * @since 0.9
 */
public final class EnumTypes {

    private EnumTypes() {}

    /**
     * The enums in the order {@link #resolve} searches them: by qualified name, so a simple name two
     * enums share resolves to the same one on every run.
     *
     * @param enums the emitted enums
     * @return them, sorted
     */
    public static List<EnumMetadata> sorted(Collection<EnumMetadata> enums) {
        return enums.stream()
                .filter(e -> e.qualifiedName() != null && !e.qualifiedName().isBlank())
                .sorted(Comparator.comparing(EnumMetadata::qualifiedName))
                .toList();
    }

    /**
     * The emitted enum a field resolves to: the one its {@code enumType} names when that is set,
     * else the one its {@code type} names; a qualified name is matched first, then a simple one.
     *
     * @param enumType the field's {@code enumType}, possibly {@code null} or blank
     * @param type     the field's {@code type}
     * @param enums    the emitted enums, in {@link #sorted} order
     * @return the enum, or {@code null} when the field names none of them
     */
    public static EnumMetadata resolve(String enumType, String type, List<EnumMetadata> enums) {
        String name = enumType == null || enumType.isBlank() ? type : enumType;
        if (name == null || name.isBlank()) {
            return null;
        }
        for (EnumMetadata e : enums) {
            if (name.equals(e.qualifiedName())) {
                return e;
            }
        }
        for (EnumMetadata e : enums) {
            if (Objects.equals(name, e.name())) {
                return e;
            }
        }
        return null;
    }
}
