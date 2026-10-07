package eu.exeris.tooling.codegen.java;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/generator-catalogue.md} names every Java generator, and only generators that exist.
 *
 * <p>The catalogue explains a path observed in {@code .exeris-codegen-manifest} by naming its
 * producer. A generator missing from it leaves its paths unexplained, and a row naming a removed
 * class explains them wrongly. The source tree is the authority: every concrete top-level class
 * named {@code *Generator} under this module's {@code src/main/java} must be a row of the Java
 * table, apart from the classes in {@link #NOT_PRODUCERS}.
 */
@DisplayName("Generator catalogue: the Java table names every generator")
class GeneratorCatalogueTest {

    private static final Path MODULE = Path.of(System.getProperty("basedir", "."));
    private static final Path SOURCES = MODULE.resolve("src/main/java");
    private static final Path CATALOGUE = MODULE.resolve("../docs/generator-catalogue.md").normalize();

    private static final String BEGIN = "<!-- catalogue:java:begin -->";
    private static final String END = "<!-- catalogue:java:end -->";

    /**
     * Classes named {@code *Generator} that write no file of their own, so no manifest path maps to
     * them, each with the reason.
     */
    private static final Map<String, String> NOT_PRODUCERS = Map.of(
            "OpenApiGenerator",
            "renders the YAML KernelOpenApiGenerator writes; the pipeline never asks it to write a file");

    private static final Pattern TOP_LEVEL_CLASS = Pattern.compile(
            "^public\\s+((?:abstract|final|sealed|non-sealed)\\s+)*(class|interface|record|enum)\\s+(\\w+)",
            Pattern.MULTILINE);

    private static final Pattern ROW_NAME = Pattern.compile("^\\|\\s*`(\\w+)`\\s*\\|");

    @Test
    @DisplayName("every concrete *Generator class is a row, and every row is such a class")
    void tableMatchesSources() throws IOException {
        Set<String> generators = concreteGenerators();
        Set<String> rows = javaTableRows();

        Set<String> expected = new TreeSet<>(generators);
        expected.removeAll(NOT_PRODUCERS.keySet());

        assertThat(generators)
                .as("the source scan finds the registered generators (wrong basedir otherwise)")
                .contains("KernelHandlerGenerator", "KernelApplicationGenerator");
        assertThat(rows)
                .as("Java table of %s against the concrete *Generator classes under %s",
                        CATALOGUE, SOURCES)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("every excluded class still exists and is absent from the table")
    void exclusionsAreCurrent() throws IOException {
        assertThat(concreteGenerators()).containsAll(NOT_PRODUCERS.keySet());
        assertThat(javaTableRows()).doesNotContainAnyElementsOf(NOT_PRODUCERS.keySet());
    }

    private static Set<String> concreteGenerators() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            for (Path file : walk.filter(p -> p.getFileName().toString().endsWith("Generator.java")).toList()) {
                Matcher m = TOP_LEVEL_CLASS.matcher(Files.readString(file, StandardCharsets.UTF_8));
                if (!m.find()) {
                    continue;
                }
                String modifiers = m.group(1) == null ? "" : m.group(1);
                boolean concreteClass = "class".equals(m.group(2)) && !modifiers.contains("abstract");
                if (concreteClass && m.group(3).endsWith("Generator")) {
                    names.add(m.group(3));
                }
            }
        }
        return names;
    }

    private static Set<String> javaTableRows() throws IOException {
        String doc = Files.readString(CATALOGUE, StandardCharsets.UTF_8);
        int begin = doc.indexOf(BEGIN);
        int end = doc.indexOf(END);
        assertThat(begin).as("%s in %s", BEGIN, CATALOGUE).isNotNegative();
        assertThat(end).as("%s after %s in %s", END, BEGIN, CATALOGUE).isGreaterThan(begin);

        Set<String> rows = new TreeSet<>();
        for (String line : doc.substring(begin, end).split("\n")) {
            Matcher m = ROW_NAME.matcher(line.strip());
            if (m.find()) {
                rows.add(m.group(1));
            }
        }
        return rows;
    }
}
