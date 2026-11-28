package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.core.MetadataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The view-site fixture, Java sources under {@code src/test/fixtures/view-site}, through javac and
 * the processor. Its metadata stays at {@code target/view-site/classes/exeris-metadata}, which the
 * frontend job reads: {@code exeris-codegen-ts/scripts/gen-sdk-examples-app.mjs} generates the
 * Angular application from it and {@code ng build} builds that application.
 *
 * <p>The {@code Storefront} page holds a header region with a built-in block, a body region with a grid of cards, a
 * form leaf and a list bound to the {@code Order} entity of the same fixture. The {@code About} page
 * holds authored blocks only: it is the page a {@code render: 'ssg'} build prerenders.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("view-site fixture: a @View page and its entity through the processor, for the frontend build")
class ViewSiteFixtureE2ETest {

    private static final Path FIXTURE = Path.of("src/test/fixtures/view-site");
    private static final Path OUTPUT = Path.of("target/view-site/classes");

    private static Path metadata;
    private static JsonNode view;

    @BeforeAll
    static void compileAndRead() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(FIXTURE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                sources.put(FIXTURE.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        }
        Path work = Path.of("target/view-site");
        deleteTree(work);
        ProcessorCompiler.compile(work.resolve("src"), OUTPUT, null, sources);
        metadata = OUTPUT.resolve(MetadataLoader.METADATA_DIR);
        view = JsonMapper.builder().build().readTree(metadata.resolve("view_Storefront.json").toFile()).path("view");
    }

    @Test
    @DisplayName("the processor writes the entity and the view, and nothing else")
    void writesTheEntityAndTheView() throws IOException {
        try (Stream<Path> files = Files.list(metadata)) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted())
                    .containsExactly("Order.json", "view_About.json", "view_Storefront.json");
        }
    }

    @Test
    @DisplayName("the page has a header and a body region holding the built-in blocks, a form and an entity list")
    void pageStructure() {
        assertThat(view.path("kind").asString()).isEqualTo("PAGE");
        assertThat(view.path("route").asString()).isEqualTo("/storefront");

        JsonNode regions = view.path("regions");
        assertThat(regions).hasSize(2);
        assertThat(regions.get(0).path("slot").asString()).isEqualTo("header");
        assertThat(regions.get(0).path("components").get(0).path("type").asString()).isEqualTo("HERO");

        JsonNode body = regions.get(1).path("components");
        assertThat(types(body)).containsExactly("GRID", "FORM", "LIST");
        assertThat(types(body.get(0).path("children"))).containsExactly("CARD", "CARD");
        assertThat(body.get(2).path("binding").path("source").asString()).isEqualTo("ENTITY");
        assertThat(body.get(2).path("binding").path("ref").asString()).isEqualTo("Order");
    }

    @Test
    @DisplayName("the About page holds authored blocks only and binds no entity, so a static build can prerender it")
    void aboutPageReadsNoEntity() throws IOException {
        JsonNode about = JsonMapper.builder().build().readTree(metadata.resolve("view_About.json").toFile()).path("view");
        assertThat(about.path("kind").asString()).isEqualTo("PAGE");
        assertThat(about.path("route").asString()).isEqualTo("/about");
        JsonNode components = about.path("regions").get(0).path("components");
        assertThat(types(components)).containsExactly("HERO", "CARD");
        components.forEach(c -> assertThat(c.path("binding").path("source").asString()).isEqualTo("STATIC"));
    }

    private static List<String> types(JsonNode nodes) {
        List<String> types = new ArrayList<>();
        nodes.forEach(node -> types.add(node.path("type").asString()));
        return types;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
