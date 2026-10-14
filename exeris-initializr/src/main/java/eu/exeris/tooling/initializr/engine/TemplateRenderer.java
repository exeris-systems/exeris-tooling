package eu.exeris.tooling.initializr.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Renders the initializr's text templates: {@code {{key}}} is replaced by a value, and
 * {@code {{#key}}…{{/key}}} keeps its body when {@code key} is {@code true} while
 * {@code {{^key}}…{{/key}}} keeps it when {@code key} is {@code false}.
 *
 * <p>Rendering is strict. A variable or a section whose key the context does not hold, a section
 * key that is not a {@link Boolean}, and a section without its closing tag each raise
 * {@link IllegalArgumentException}, so a template and the code that fills it cannot drift apart
 * without a test noticing. A substituted value is not scanned again. Sections with different keys
 * nest; a section does not nest inside one with the same key.
 *
 * @since 0.10
 */
public final class TemplateRenderer {

    private static final String OPEN = "{{";
    private static final String CLOSE = "}}";

    private TemplateRenderer() {
    }

    /**
     * Reads a template from the classpath, as UTF-8.
     *
     * @param resource the absolute resource name, starting with {@code /}
     * @return the template text
     * @throws IllegalArgumentException if no resource has that name
     * @throws UncheckedIOException     if the resource cannot be read
     * @since 0.10
     */
    public static String readResource(String resource) {
        try (InputStream in = TemplateRenderer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no template resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("reading template resource " + resource + " failed", e);
        }
    }

    /**
     * Renders a template against a context.
     *
     * @param template the template text
     * @param context  the values: a {@link Boolean} for every section key, and any value, rendered
     *                 with {@link String#valueOf(Object)}, for every variable
     * @return the rendered text
     * @throws IllegalArgumentException if the template names a key the context does not hold, uses
     *                                  a non-boolean as a section key, or leaves a section or a tag
     *                                  unclosed
     * @since 0.10
     */
    public static String render(String template, Map<String, ?> context) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(context, "context");
        StringBuilder out = new StringBuilder(template.length());
        int cursor = 0;
        while (true) {
            int open = template.indexOf(OPEN, cursor);
            if (open < 0) {
                out.append(template, cursor, template.length());
                return out.toString();
            }
            out.append(template, cursor, open);
            int close = template.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                throw new IllegalArgumentException("unclosed tag at offset " + open);
            }
            String tag = template.substring(open + OPEN.length(), close).trim();
            cursor = close + CLOSE.length();
            char kind = tag.isEmpty() ? ' ' : tag.charAt(0);
            if (kind == '#' || kind == '^') {
                cursor = renderSection(template, cursor, tag, kind == '#', context, out);
            } else if (kind == '/') {
                throw new IllegalArgumentException("closing tag {{" + tag + "}} without its section");
            } else {
                out.append(variable(context, tag));
            }
        }
    }

    /** Renders the section opened by {@code tag}; returns the offset after its closing tag. */
    private static int renderSection(String template, int bodyStart, String tag, boolean keepWhenTrue,
                                     Map<String, ?> context, StringBuilder out) {
        String key = tag.substring(1).trim();
        String end = OPEN + "/" + key + CLOSE;
        int endAt = template.indexOf(end, bodyStart);
        if (endAt < 0) {
            throw new IllegalArgumentException("section '" + key + "' has no " + end);
        }
        if (section(context, key) == keepWhenTrue) {
            out.append(render(template.substring(bodyStart, endAt), context));
        }
        return endAt + end.length();
    }

    private static boolean section(Map<String, ?> context, String key) {
        if (!(context.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException("section key '" + key + "' is not a boolean in the context");
        }
        return value;
    }

    private static String variable(Map<String, ?> context, String key) {
        if (!context.containsKey(key)) {
            throw new IllegalArgumentException("no value for '" + key + "' in the context");
        }
        return String.valueOf(context.get(key));
    }
}
