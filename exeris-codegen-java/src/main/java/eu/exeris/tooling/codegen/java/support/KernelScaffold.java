package eu.exeris.tooling.codegen.java.support;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;

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

    private KernelScaffold() {
    }

    /**
     * Returns a {@link TypeSpec.Builder} for a public class with the given
     * name. Caller chains Javadoc, fields, and methods.
     */
    public static TypeSpec.Builder publicClass(String className) {
        return TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC);
    }

    /**
     * Renders a {@link TypeSpec} to its canonical Exeris-Kernel source form:
     * 4-space indent, {@code java.lang} imports skipped.
     */
    public static String render(String packageName, TypeSpec type) {
        return JavaFile.builder(packageName, type)
                .indent("    ")
                .skipJavaLangImports(true)
                .build()
                .toString();
    }
}
