package eu.exeris.tooling.codegen.java.support;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.Objects;

/**
 * Shared scaffold for {@code Kernel*Generator} classes that emit Java sources
 * via JavaPoet. Codifies the universally-shared pieces of the Phase 1 + Phase 2
 * migration pattern (ADR-015): the public-class TypeSpec setup and the
 * canonical JavaFile rendering.
 *
 * <p>What this helper deliberately does <i>not</i> own:
 * <ul>
 *   <li>Javadoc shape — Handler and Client emit different DO_NOT_EDIT
 *       phrasings; that's caller territory until a future ADR normalizes it.</li>
 *   <li>Field, method, or import composition — those are artifact-specific.</li>
 * </ul>
 *
 * <p>Lives in {@code codegen-java} (not {@code codegen-core}) because its
 * signatures expose JavaPoet's {@link TypeSpec.Builder} — moving it into
 * {@code codegen-core} would force JavaPoet onto that module's classpath and
 * violate ADR-015 §4 (pure-AST scope of {@code codegen-core}).
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class KernelScaffold {

    /**
     * {@code java.lang.System.Logger} — the logging facade generated code binds.
     *
     * <p>Deliberately the JDK's own, not SLF4J. Tooling emits no {@code pom.xml}, so anything
     * generated code imports is a hard requirement on the consumer's build; {@code slf4j-api} is
     * not a dependency of {@code exeris-kernel-spi} or {@code -core}, so a consumer used to get it
     * only through whichever driver tier they happened to pick. {@code System.Logger} is on every
     * JDK, and it still routes to SLF4J (or anything else) for a consumer who wants that, via a
     * {@code System.LoggerFinder} provider.
     */
    public static final ClassName SYSTEM_LOGGER = ClassName.get("java.lang", "System", "Logger");

    /** {@code java.lang.System.Logger.Level} — the level argument of every emitted log call. */
    public static final ClassName LOGGER_LEVEL =
            ClassName.get("java.lang", "System", "Logger", "Level");

    private static final ClassName SYSTEM = ClassName.get("java.lang", "System");

    private KernelScaffold() {
    }

    /**
     * The {@code LOG} field carried by every generated class that logs.
     *
     * <p>{@code selfType} is the generated class's own name — {@code System.getLogger} takes a
     * logger name, not a {@code Class}, so this emits {@code X.class.getName()} rather than
     * {@code X.class}.
     *
     * @param selfType must not be null
     */
    public static FieldSpec loggerField(ClassName selfType) {
        Objects.requireNonNull(selfType, "selfType must not be null");
        return FieldSpec.builder(SYSTEM_LOGGER, "LOG",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($T.class.getName())", SYSTEM, selfType)
                .build();
    }

    /**
     * Returns a {@link TypeSpec.Builder} for a public class with the given
     * name. Caller chains Javadoc, fields, and methods.
     *
     * @param className must not be null
     */
    public static TypeSpec.Builder publicClass(String className) {
        Objects.requireNonNull(className, "className must not be null");
        return TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC);
    }

    /**
     * Escapes single quotes in a {@code System.Logger} message that carries parameters.
     *
     * <p>Such a message is formatted with {@link java.text.MessageFormat}, where a single quote
     * opens a quoted section: {@code 'x'} renders as {@code x}, and — the trap —
     * {@code '{0}'} renders as the literal text <code>{0}</code> instead of the argument.
     * Doubling the quote is MessageFormat's own escape for a literal one. Placeholders are
     * untouched, so a whole pattern can be passed through.
     *
     * <p>Only needed when the call actually passes parameters; the no-parameter
     * {@code log(Level, String)} overload does no formatting at all.
     *
     * @param pattern must not be null
     */
    public static String escapeQuotes(String pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        return pattern.replace("'", "''");
    }

    /**
     * Renders a {@link TypeSpec} to its canonical Exeris-Kernel source form:
     * 4-space indent, {@code java.lang} imports skipped.
     *
     * @param packageName must not be null
     * @param type        must not be null
     */
    public static String render(String packageName, TypeSpec type) {
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(type, "type must not be null");
        return JavaFile.builder(packageName, type)
                .indent("    ")
                .skipJavaLangImports(true)
                .build()
                .toString();
    }
}
