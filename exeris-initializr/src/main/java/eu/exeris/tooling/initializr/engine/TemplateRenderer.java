package eu.exeris.tooling.initializr.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight, zero-dependency template engine supporting variable substitution ({{key}}),
 * conditional sections ({{#key}}...{{/key}}), and inverted sections ({{^key}}...{{/key}}).
 */
public final class TemplateRenderer {

    private TemplateRenderer() {}

    /**
     * Reads a template resource from the classpath.
     */
    public static String readResource(String resourcePath) {
        String path = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        try (InputStream is = TemplateRenderer.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("Template resource not found: " + path);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    sb.append(line).append("\n");
                    line = reader.readLine();
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read template resource: " + path, e);
        }
    }

    /**
     * Renders a template string given a context map of variables and booleans.
     */
    public static String render(String template, Map<String, Object> context) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Objects.requireNonNull(context, "context must not be null");

        String result = renderSections(template, context);
        return renderVariables(result, context);
    }

    private static String renderSections(String text, Map<String, Object> context) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;

        while (cursor < text.length()) {
            int openIdx = text.indexOf("{{", cursor);
            if (openIdx == -1) {
                out.append(text.substring(cursor));
                break;
            }

            out.append(text, cursor, openIdx);
            int closeIdx = text.indexOf("}}", openIdx);
            if (closeIdx == -1) {
                out.append(text.substring(openIdx));
                break;
            }

            String tag = text.substring(openIdx + 2, closeIdx).trim();

            if (tag.startsWith("#")) {
                // Conditional section: {{#key}} ... {{/key}}
                String key = tag.substring(1).trim();
                String endTag = "{{/" + key + "}}";
                int endIdx = text.indexOf(endTag, closeIdx + 2);
                if (endIdx == -1) {
                    // Malformed section, output as-is
                    out.append(text, openIdx, closeIdx + 2);
                    cursor = closeIdx + 2;
                    continue;
                }

                String sectionBody = text.substring(closeIdx + 2, endIdx);
                boolean conditionMet = isTruthy(context.get(key));

                if (conditionMet) {
                    out.append(renderSections(sectionBody, context));
                }
                cursor = endIdx + endTag.length();

            } else if (tag.startsWith("^")) {
                // Inverted section: {{^key}} ... {{/key}}
                String key = tag.substring(1).trim();
                String endTag = "{{/" + key + "}}";
                int endIdx = text.indexOf(endTag, closeIdx + 2);
                if (endIdx == -1) {
                    out.append(text, openIdx, closeIdx + 2);
                    cursor = closeIdx + 2;
                    continue;
                }

                String sectionBody = text.substring(closeIdx + 2, endIdx);
                boolean conditionMet = !isTruthy(context.get(key));

                if (conditionMet) {
                    out.append(renderSections(sectionBody, context));
                }
                cursor = endIdx + endTag.length();

            } else {
                // Ordinary tag, leave for variable pass or output now
                out.append(text, openIdx, closeIdx + 2);
                cursor = closeIdx + 2;
            }
        }

        return out.toString();
    }

    private static String renderVariables(String text, Map<String, Object> context) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;

        while (cursor < text.length()) {
            int openIdx = text.indexOf("{{", cursor);
            if (openIdx == -1) {
                out.append(text.substring(cursor));
                break;
            }

            out.append(text, cursor, openIdx);
            int closeIdx = text.indexOf("}}", openIdx);
            if (closeIdx == -1) {
                out.append(text.substring(openIdx));
                break;
            }

            String key = text.substring(openIdx + 2, closeIdx).trim();
            Object value = context.get(key);

            if (value != null) {
                out.append(value);
            }
            cursor = closeIdx + 2;
        }

        return out.toString();
    }

    private static boolean isTruthy(Object val) {
        if (val == null) {
            return false;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s) {
            return !s.isBlank();
        }
        if (val instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (val instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        return true;
    }
}
