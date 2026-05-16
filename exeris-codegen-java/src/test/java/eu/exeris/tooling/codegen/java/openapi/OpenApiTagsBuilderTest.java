package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import io.swagger.v3.oas.models.tags.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenApiTagsBuilder")
class OpenApiTagsBuilderTest {

    @Test
    @DisplayName("Single entity tag emitted when no actions are declared")
    void singleTagWhenNoActions() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain").build();

        List<Tag> tags = OpenApiTagsBuilder.buildTags(meta);

        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).getName()).isEqualTo("Order");
        // No description set + Builder default is "" (blank), so the
        // fallback wording fires.
        assertThat(tags.get(0).getDescription()).isEqualTo("Operations for Order");
    }

    @Test
    @DisplayName("Description from metadata flows into the entity tag when set")
    void descriptionUsedWhenSet() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .description("Customer order entity").build();

        List<Tag> tags = OpenApiTagsBuilder.buildTags(meta);

        assertThat(tags.get(0).getDescription()).isEqualTo("Customer order entity");
    }

    @Test
    @DisplayName("Domains with actions get an additional \"<Entity> Actions\" tag")
    void actionsAppendSecondTag() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .actions(List.of(ActionMetadata.simple("approve")))
                .build();

        List<Tag> tags = OpenApiTagsBuilder.buildTags(meta);

        assertThat(tags).hasSize(2);
        assertThat(tags.get(1).getName()).isEqualTo("Order Actions");
        assertThat(tags.get(1).getDescription()).isEqualTo("Custom actions for Order");
    }
}
