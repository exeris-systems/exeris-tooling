package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.NavMenuMetadata;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata.UIGroupMetadata;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata.UITabMetadata;
import eu.exeris.tooling.codegen.core.MetadataLoader;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The presentation metadata of an entity carrying {@code @UI}, {@code @UIGroup}, {@code @Tab} and
 * {@code @NavMenu}, compiled through the real processor and read back through the pipeline's
 * loader, as the generators receive it.
 *
 * <p>Every attribute the source sets differs from the value the processor writes when the
 * attribute is absent, so an attribute the processor stops reading changes the metadata. The type
 * level {@code @UI} leaves {@code listView} and {@code createForm} undeclared: with {@code @UI}
 * present they read as {@code true}, the SDK-documented "you added @UI, so the views are on". Two
 * fields share a group and two share a tab, each declaring different attributes, so the group and
 * the tab take each attribute from the first field that declares it, and list their fields in
 * declaration order. The Java emitters read none of this; the TypeScript generator does, from the
 * same JSON.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("@UI, @UIGroup, @Tab and @NavMenu from the processor: each declared value reaches the metadata")
class UiMetadataFromProcessorE2ETest {

    @TempDir
    static Path workspace;

    private static DomainMetadata ticket;
    private static DomainMetadata plain;

    @BeforeAll
    static void compileAndLoad() throws IOException {
        Path classes = workspace.resolve("target/classes");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), classes, null, sources());
        List<DomainMetadata> loaded = CodegenPipeline.createDefault()
                .loadMetadata(classes.resolve(MetadataLoader.METADATA_DIR));
        ticket = loaded.stream().filter(d -> d.entityName().equals("Ticket")).findFirst().orElseThrow();
        plain = loaded.stream().filter(d -> d.entityName().equals("Plain")).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the type-level @UI view switches reach UIMetadata, the undeclared ones on")
    void viewSwitchesReachTheMetadata() {
        UIMetadata ui = ticket.uiMetadata();
        assertThat(ui).isNotNull();
        assertThat(ui.listView()).as("listView, undeclared").isTrue();
        assertThat(ui.createForm()).as("createForm, undeclared").isTrue();
        assertThat(ui.detailView()).as("detailView").isFalse();
        assertThat(ui.editForm()).as("editForm").isFalse();
        assertThat(ui.searchable()).as("searchable").isFalse();
        assertThat(ui.filterable()).as("filterable").isFalse();
        assertThat(ui.exportable()).as("exportable").isTrue();
    }

    @Test
    @DisplayName("@UIGroup fields merge into one group per name, first declaration winning, in declaration order")
    void groupsReachTheMetadata() {
        assertThat(ticket.uiMetadata().groups()).containsExactly(
                new UIGroupMetadata("details", "Details", "What the ticket is about", 1, false, true, "info",
                        UIGroupMetadata.DEFAULT_GRID_SPAN, List.of("subject", "body")),
                new UIGroupMetadata("triage", null, null, UIGroupMetadata.DEFAULT_ORDER, true, false, null,
                        UIGroupMetadata.DEFAULT_GRID_SPAN, List.of("priority")));
    }

    @Test
    @DisplayName("@Tab fields merge into one tab per name, first declaration winning, in declaration order")
    void tabsReachTheMetadata() {
        assertThat(ticket.uiMetadata().tabs()).containsExactly(
                new UITabMetadata("main", "Main", 1, "home", false, null, null, false, null, null, null,
                        List.of("subject", "body")),
                new UITabMetadata("history", "History", 2, null, true, "replies", "warn", true,
                        "closed", "staff", "history-tab", List.of("replies")));
    }

    @Test
    @DisplayName("@NavMenu reaches NavMenuMetadata with every declared value")
    void navMenuReachesTheMetadata() {
        assertThat(ticket.navMenu()).isEqualTo(NavMenuMetadata.builder()
                .group("Support")
                .parent("Helpdesk")
                .label("Tickets")
                .icon("confirmation_number")
                .order(3)
                .groupOrder(2)
                .groupIcon("support_agent")
                .hidden(true)
                .roles(List.of("agent"))
                .permissions(List.of("ticket:read", "ticket:triage"))
                .badge("open")
                .badgeColor("warn")
                .tooltip("Open tickets")
                .openInNewTab(true)
                .routePath("/support/tickets")
                .queryParams("status=open")
                .expanded(true)
                .divider(NavMenuMetadata.Divider.BEFORE)
                .build());
    }

    @Test
    @DisplayName("an entity with none of the four annotations carries neither UIMetadata nor NavMenuMetadata")
    void absentAnnotationsLeaveNoMetadata() {
        assertThat(plain.uiMetadata()).isNull();
        assertThat(plain.navMenu()).isNull();
    }

    private static Map<String, String> sources() {
        return Map.of(
                "eu/exeris/e2e/support/domain/Ticket.java", """
                        package eu.exeris.e2e.support.domain;

                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.Field;
                        import eu.exeris.sdk.annotation.NavMenu;
                        import eu.exeris.sdk.annotation.Tab;
                        import eu.exeris.sdk.annotation.UI;
                        import eu.exeris.sdk.annotation.UIGroup;

                        import java.util.UUID;

                        @ExerisDomain(module = "support", path = "/tickets")
                        @UI(detailView = false, editForm = false, searchable = false, filterable = false,
                            exportable = true)
                        @NavMenu(group = "Support", parent = "Helpdesk", label = "Tickets",
                                 icon = "confirmation_number", order = 3, groupOrder = 2,
                                 groupIcon = "support_agent", hidden = true, roles = {"agent"},
                                 permissions = {"ticket:read", "ticket:triage"}, badge = "open",
                                 badgeColor = "warn", tooltip = "Open tickets", openInNewTab = true,
                                 routePath = "/support/tickets", queryParams = "status=open",
                                 expanded = true, divider = NavMenu.Divider.BEFORE)
                        public class Ticket {
                            private UUID id;

                            @Field(label = "Subject")
                            @UIGroup(name = "details", label = "Details", description = "What the ticket is about",
                                     order = 1, collapsible = false)
                            @Tab(name = "main", label = "Main", order = 1, icon = "home")
                            private String subject;

                            @Field(label = "Body")
                            @UIGroup(name = "details", label = "Ignored", order = 7, collapsed = true, icon = "info")
                            @Tab(name = "main", label = "Ignored too")
                            private String body;

                            @Field(label = "Priority")
                            @UIGroup(name = "triage")
                            private int priority;

                            @Field(label = "Replies")
                            @Tab(name = "history", label = "History", order = 2, lazy = true, badgeField = "replies",
                                 badgeColor = "warn", disabled = true, disabledWhen = "closed",
                                 visibleWhen = "staff", cssClass = "history-tab")
                            private int replies;

                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }
                            public String getSubject() { return subject; }
                            public void setSubject(String subject) { this.subject = subject; }
                            public String getBody() { return body; }
                            public void setBody(String body) { this.body = body; }
                            public int getPriority() { return priority; }
                            public void setPriority(int priority) { this.priority = priority; }
                            public int getReplies() { return replies; }
                            public void setReplies(int replies) { this.replies = replies; }
                        }
                        """,
                "eu/exeris/e2e/support/domain/Plain.java", """
                        package eu.exeris.e2e.support.domain;

                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.Field;

                        import java.util.UUID;

                        @ExerisDomain(module = "support", path = "/plains")
                        public class Plain {
                            private UUID id;
                            @Field(label = "Name")
                            private String name;
                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }
                            public String getName() { return name; }
                            public void setName(String name) { this.name = name; }
                        }
                        """);
    }
}
