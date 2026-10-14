package eu.exeris.tooling.initializr.engine;

import java.util.Locale;
import java.util.regex.Pattern;

/** The names a generated project derives from the request: paths, identifiers and escaped text. */
final class Names {

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
    private static final Pattern NOT_DATABASE_CHAR = Pattern.compile("[^a-z0-9]+");

    private Names() {
    }

    /** {@code OrderLine} to {@code order-line}. */
    static String kebab(String camel) {
        return CAMEL_BOUNDARY.matcher(camel).replaceAll("-").toLowerCase(Locale.ROOT);
    }

    /** The English plural of a lower-case word: {@code item} to {@code items}, {@code city} to {@code cities}. */
    static String plural(String word) {
        if (word.endsWith("y") && word.length() > 1 && "aeiou".indexOf(word.charAt(word.length() - 2)) < 0) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        if (word.endsWith("s") || word.endsWith("x") || word.endsWith("z")
                || word.endsWith("ch") || word.endsWith("sh")) {
            return word + "es";
        }
        return word + "s";
    }

    /** A database name from an artifactId: lower-case letters, digits and {@code _}, starting with a letter. */
    static String databaseName(String artifactId) {
        String name = NOT_DATABASE_CHAR.matcher(artifactId.toLowerCase(Locale.ROOT)).replaceAll("_");
        name = stripUnderscores(name);
        return name.isEmpty() || !Character.isLetter(name.charAt(0)) ? "app_" + name : name;
    }

    private static String stripUnderscores(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '_') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '_') {
            end--;
        }
        return text.substring(start, end);
    }

    /** An npm package name from an artifactId, which holds only {@code [A-Za-z0-9_.-]}. */
    static String npmName(String artifactId) {
        String name = artifactId.toLowerCase(Locale.ROOT);
        return name.charAt(0) == '.' || name.charAt(0) == '_' ? "app-" + name : name;
    }

    /** Text escaped for an XML element. */
    static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
