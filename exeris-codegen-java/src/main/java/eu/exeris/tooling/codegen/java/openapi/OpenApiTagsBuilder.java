package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import io.swagger.v3.oas.models.tags.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds OpenAPI tags from domain metadata.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class OpenApiTagsBuilder {

    private OpenApiTagsBuilder() {}

    public static List<Tag> buildTags(DomainMetadata metadata) {
        List<Tag> tags = new ArrayList<>();

        Tag entityTag = new Tag();
        entityTag.setName(metadata.entityName());
        entityTag.setDescription(metadata.description() != null && !metadata.description().isBlank()
            ? metadata.description()
            : "Operations for " + metadata.entityName());
        tags.add(entityTag);

        if (metadata.hasActions()) {
            Tag actionsTag = new Tag();
            actionsTag.setName(metadata.entityName() + " Actions");
            actionsTag.setDescription("Custom actions for " + metadata.entityName());
            tags.add(actionsTag);
        }

        return tags;
    }
}

