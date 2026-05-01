package eu.exeris.tooling.codegen.java.service;

/**
 * Utility methods for code generation.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class GeneratorUtils {

    private GeneratorUtils() {
        // Utility class
    }

    /**
     * Converts a string to camelCase.
     * Handles SCREAMING_SNAKE_CASE, kebab-case, and PascalCase.
     *
     * @param input Input string
     * @return camelCase version
     */
    public static String toCamel(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;
        boolean first = true;

        for (char c : input.toCharArray()) {
            if (c == '_' || c == '-') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else if (first) {
                result.append(Character.toLowerCase(c));
                first = false;
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * Converts a string to PascalCase.
     *
     * @param input Input string
     * @return PascalCase version
     */
    public static String toPascal(String input) {
        String camel = toCamel(input);
        if (camel == null || camel.isEmpty()) {
            return camel;
        }
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    /**
     * Converts a string to kebab-case.
     *
     * @param input Input string
     * @return kebab-case version
     */
    public static String toKebab(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .replaceAll("_", "-")
                .toLowerCase();
    }

    /**
     * Converts a string to SCREAMING_SNAKE_CASE.
     *
     * @param input Input string
     * @return SCREAMING_SNAKE_CASE version
     */
    public static String toScreamingSnake(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("-", "_")
                .toUpperCase();
    }
}

