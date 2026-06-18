package eu.exeris.tooling.codegen.java.support;

import java.util.Locale;

/**
 * Single source of the two name transforms that bind the generated {@code @Action}
 * surface together across generators:
 *
 * <ul>
 *   <li>{@link #kebab(String)} — the URL segment for an action route. The kernel route
 *       ({@code KernelApplicationGenerator}), the OpenAPI path ({@code OpenApiPathsBuilder}),
 *       and the page/table DSL endpoints ({@code PageDslGenerator}, {@code TableDslGenerator})
 *       must all advertise the <em>same</em> segment, or the served route will not match the
 *       advertised path.</li>
 *   <li>{@link #pascal(String)} — the Java identifier fragment for an action handler. The
 *       route's method reference ({@code orderHandler::handle<X>}) emitted by
 *       {@code KernelApplicationGenerator} must resolve to the {@code handle<X>} method emitted
 *       by {@code KernelHandlerGenerator}; both derive {@code <X>} from this one method.</li>
 * </ul>
 *
 * <p>Previously each generator carried its own byte-identical copy; a future divergence would
 * only surface as a generated-code compile failure (route reference to a missing method) or a
 * silent 404 (route/path mismatch). Centralising removes that coupling risk and pins the
 * transforms under one test.
 *
 * <p>Both transforms are locale-independent ({@link Locale#ROOT}) to honour the determinism
 * constraint — same metadata, byte-identical output, regardless of the build machine's locale.
 *
 * @author Exeris Team
 * @since 0.6.0
 */
public final class NameCasing {

    private NameCasing() {
    }

    /**
     * Kebab-cases an action identity for use as a URL path segment
     * ({@code markUrgent} → {@code mark-urgent}). Already-kebab or snake input passes through
     * unchanged apart from lower-casing.
     */
    public static String kebab(String input) {
        return input.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    /**
     * PascalCases an action identity (camelCase, kebab-case, or snake_case) into a Java
     * identifier fragment ({@code mark-urgent} / {@code mark_urgent} / {@code markUrgent}
     * → {@code MarkUrgent}). Non-alphanumeric separators are dropped and the following
     * letter is upper-cased.
     */
    public static String pascal(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        boolean upper = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }
}
