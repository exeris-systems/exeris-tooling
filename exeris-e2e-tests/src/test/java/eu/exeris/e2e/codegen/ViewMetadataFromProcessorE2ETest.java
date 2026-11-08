package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.core.MetadataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code view_*.json} the real processor writes for a {@code @View} class: the view's own
 * attributes, its {@code @Region} members as regions, and the {@code @Block} / {@code @Bind}
 * members of each region's type as the component tree.
 *
 * <p>The file is read as JSON, field by field, because its names are the wire shape the
 * TypeScript view emitter parses: a {@code ViewJson} wrapper ({@code name}, {@code packageName},
 * {@code qualifiedName}) around the {@code ViewMetadata} in {@code view}. An attribute the source
 * leaves undeclared is absent from the file, never {@code null}, since that parser accepts a
 * missing string and rejects a {@code null} one.
 *
 * <p>The view declares every {@code @View} attribute with a non-default value. One region names its
 * slot and the other takes the member's name; one block's type is itself block-shaped, so its
 * members become its children; one member carries {@code @Bind} without {@code @Block}, which is a
 * leaf binding node with no type. The Java emitters read no view; the TypeScript generator does.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("@View from the processor: the view, its regions and its component tree reach view_*.json")
class ViewMetadataFromProcessorE2ETest {

    @TempDir
    static Path workspace;

    private static Path metadata;
    private static JsonNode file;
    private static JsonNode view;

    @BeforeAll
    static void compileAndRead() throws IOException {
        Path classes = workspace.resolve("target/classes");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), classes, null, sources());
        metadata = classes.resolve(MetadataLoader.METADATA_DIR);
        file = JsonMapper.builder().build().readTree(metadata.resolve("view_SupportDesk.json").toFile());
        view = file.path("view");
    }

    @Test
    @DisplayName("one view file per @View class, named after the class, beside the entity metadata")
    void oneFilePerView() throws IOException {
        try (Stream<Path> files = Files.list(metadata)) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted())
                    .containsExactly("Ticket.json", "view_SupportDesk.json");
        }
        assertThat(file.path("name").asString()).isEqualTo("SupportDesk");
        assertThat(file.path("packageName").asString()).isEqualTo("eu.exeris.e2e.desk.ui");
        assertThat(file.path("qualifiedName").asString()).isEqualTo("eu.exeris.e2e.desk.ui.SupportDesk");
    }

    @Test
    @DisplayName("every @View attribute reaches the view with its declared value")
    void viewAttributesReachTheFile() {
        assertThat(view.path("name").asString()).isEqualTo("Desk");
        assertThat(view.path("kind").asString()).isEqualTo("SECTION");
        assertThat(view.path("route").asString()).isEqualTo("/desk");
        assertThat(view.path("title").asString()).isEqualTo("Support desk");
        assertThat(view.path("titleKey").asString()).isEqualTo("desk.title");
        assertThat(view.path("layout").asString()).isEqualTo("two-column");
    }

    @Test
    @DisplayName("each @Region member is a region in declaration order, its slot declared or the member's name")
    void regionsFollowTheMembers() {
        assertThat(texts(view.path("regions"), "slot")).containsExactly("aside", "main");
    }

    @Test
    @DisplayName("each region's @Block and @Bind members form its component tree, with type, props, binding and children")
    void componentTreeFollowsTheRegionTypes() {
        JsonNode aside = view.path("regions").get(0).path("components");
        assertThat(aside).hasSize(1);
        assertThat(aside.get(0).path("type").asString()).isEqualTo("NAV");
        assertThat(aside.get(0).path("binding").path("source").asString()).isEqualTo("STATIC");

        JsonNode main = view.path("regions").get(1).path("components");
        assertThat(main).hasSize(3);

        JsonNode tickets = main.get(0);
        assertThat(tickets.path("type").asString()).isEqualTo("LIST");
        assertThat(tickets.path("props").asString()).isEqualTo("{\"pageSize\":20}");
        assertThat(tickets.path("binding").path("source").asString()).isEqualTo("ENTITY");
        assertThat(tickets.path("binding").path("ref").asString()).isEqualTo("Ticket");
        assertThat(tickets.path("binding").path("path").asString()).isEqualTo("subject");

        JsonNode stats = main.get(1);
        assertThat(stats.path("type").asString()).isEqualTo("CUSTOM");
        assertThat(stats.path("customType").asString()).isEqualTo("ticket-stats");
        assertThat(stats.has("binding")).as("a block without @Bind has no binding").isFalse();
        JsonNode children = stats.path("children");
        assertThat(children).hasSize(1);
        assertThat(children.get(0).path("type").asString()).isEqualTo("CARD");
        assertThat(children.get(0).path("binding").path("source").asString()).isEqualTo("ACTION");
        assertThat(children.get(0).path("binding").path("ref").asString()).isEqualTo("close");

        JsonNode openCount = main.get(2);
        assertThat(openCount.has("type")).as("a @Bind-only member declares no block type").isFalse();
        assertThat(openCount.path("binding").path("source").asString()).isEqualTo("PROJECTION");
        assertThat(openCount.path("binding").path("ref").asString()).isEqualTo("OpenCount");
        assertThat(openCount.path("binding").path("expression").asString()).isEqualTo("count > 0");
        assertThat(openCount.path("binding").path("language").asString()).isEqualTo("cel");
    }

    @Test
    @DisplayName("no attribute in the file is null: an undeclared one is absent")
    void undeclaredAttributesAreAbsent() {
        List<String> nulls = new ArrayList<>();
        collectNulls(file, "$", nulls);
        assertThat(nulls).isEmpty();
        assertThat(view.path("regions").get(0).path("components").get(0).has("customType")).isFalse();
        assertThat(view.path("regions").get(0).path("components").get(0).path("binding").has("ref")).isFalse();
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asString()));
        return values;
    }

    private static void collectNulls(JsonNode node, String at, List<String> nulls) {
        if (node.isNull()) {
            nulls.add(at);
        } else if (node.isObject()) {
            node.properties().forEach(entry -> collectNulls(entry.getValue(), at + "." + entry.getKey(), nulls));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectNulls(node.get(i), at + "[" + i + "]", nulls);
            }
        }
    }

    private static Map<String, String> sources() {
        return Map.of(
                "eu/exeris/e2e/desk/domain/Ticket.java", """
                        package eu.exeris.e2e.desk.domain;

                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.Field;

                        import java.util.UUID;

                        @ExerisDomain(module = "desk", path = "/tickets")
                        public class Ticket {
                            private UUID id;
                            @Field(label = "Subject")
                            private String subject;
                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }
                            public String getSubject() { return subject; }
                            public void setSubject(String subject) { this.subject = subject; }
                        }
                        """,
                "eu/exeris/e2e/desk/ui/SupportDesk.java", """
                        package eu.exeris.e2e.desk.ui;

                        import eu.exeris.sdk.annotation.Bind;
                        import eu.exeris.sdk.annotation.Block;
                        import eu.exeris.sdk.annotation.Region;
                        import eu.exeris.sdk.annotation.View;

                        @View(name = "Desk", kind = View.Kind.SECTION, route = "/desk", title = "Support desk",
                              titleKey = "desk.title", layout = "two-column")
                        public final class SupportDesk {

                            @Region(slot = "aside")
                            AsideRegion side;

                            @Region
                            MainRegion main;

                            static final class AsideRegion {
                                @Block(type = Block.BlockType.NAV)
                                @Bind(source = Bind.Source.STATIC)
                                String menu;
                            }

                            static final class MainRegion {
                                @Block(type = Block.BlockType.LIST, props = "{\\"pageSize\\":20}")
                                @Bind(source = Bind.Source.ENTITY, ref = "Ticket", path = "subject")
                                String tickets;

                                @Block(type = Block.BlockType.CUSTOM, customType = "ticket-stats")
                                Stats stats;

                                @Bind(source = Bind.Source.PROJECTION, ref = "OpenCount", expression = "count > 0",
                                      language = "cel")
                                String openCount;
                            }

                            static final class Stats {
                                @Block(type = Block.BlockType.CARD)
                                @Bind(source = Bind.Source.ACTION, ref = "close")
                                String closeCard;
                            }
                        }
                        """);
    }
}
