package eu.exeris.tooling.processor;

import eu.exeris.sdk.sourcemodel.ast.BlobMetadata;
import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphPropertyMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accessors {@link KernelAccessors} requires, per metadata shape: exactly the ones the Java
 * emitters call, so a field the generated code only writes needs no getter and a field it only
 * reads needs no setter.
 */
@DisplayName("KernelAccessors — the accessors the generated application calls")
class KernelAccessorsTest {

    @Test
    @DisplayName("a plain entity: the key and every column, read as is<Name>() for a primitive boolean")
    void plainEntity() {
        DomainMetadata metadata = entity()
                .fields(List.of(field("title", "java.lang.String"), field("active", "boolean"),
                        field("archived", "java.lang.Boolean")))
                .build();

        assertThat(calls(metadata)).containsExactly(
                "getId()", "setId(…)", "getTitle()", "setTitle(…)", "isActive()", "setActive(…)",
                "getArchived()", "setArchived(…)");
    }

    @Test
    @DisplayName("a @Blob field is reset through its setter and never read")
    void blobFieldNeedsOnlySetter() {
        DomainMetadata metadata = entity()
                .dataScope(DataScope.TENANT)
                .fields(List.of(FieldMetadata.builder("scan", "eu.exeris.kernel.spi.storage.blob.BlobRef")
                        .required(true).blob(BlobMetadata.unrestricted()).build()))
                .build();

        assertThat(calls(metadata)).containsExactly(
                "getId()", "setId(…)", "getTenantId()", "setTenantId(…)", "setScan(…)");
    }

    @Test
    @DisplayName("an inverse collection is reset through its setter and never read, even when required")
    void inverseCollectionNeedsOnlySetter() {
        DomainMetadata metadata = entity()
                .fields(List.of(field("number", "String"),
                        FieldMetadata.builder("lines", "java.util.List<com.example.notes.domain.Line>")
                                .required(true).readOnly(true).build()))
                .relationships(List.of(RelationshipMetadata.builder("lines", "Line")
                        .type(RelationshipMetadata.RelationType.ONE_TO_MANY).mappedBy("noteId").build()))
                .build();

        assertThat(calls(metadata)).containsExactly(
                "getId()", "setId(…)", "getNumber()", "setNumber(…)", "setLines(…)");
    }

    @Test
    @DisplayName("a List field is a column when its ONE_TO_MANY names no mappedBy, and so is a mappedBy scalar")
    void listWithoutMappedByIsAColumn() {
        DomainMetadata metadata = entity()
                .fields(List.of(field("tags", "List<String>"), field("ownerId", "java.util.UUID")))
                .relationships(List.of(
                        RelationshipMetadata.builder("tags", "Tag")
                                .type(RelationshipMetadata.RelationType.ONE_TO_MANY).build(),
                        RelationshipMetadata.builder("ownerId", "Owner")
                                .type(RelationshipMetadata.RelationType.ONE_TO_MANY).mappedBy("noteId").build()))
                .build();

        assertThat(calls(metadata)).containsExactly(
                "getId()", "setId(…)", "getTags()", "setTags(…)", "getOwnerId()", "setOwnerId(…)");
    }

    @Test
    @DisplayName("the system columns follow the flags and the names the systemFields block declares")
    void systemColumnsFollowFlagsAndOverrides() {
        DomainMetadata metadata = entity()
                .dataScope(DataScope.TENANT)
                .audited(true)
                .softDelete(true)
                .versioned(true)
                .systemFields(new SystemFieldsMetadata("key", "created", null, null, null, "owner",
                        "revision", "removed", null, null, null))
                .fields(List.of(field("key", "java.util.UUID"), field("createdBy", "java.lang.String"),
                        field("deletedAt", "java.time.Instant")))
                .build();

        assertThat(calls(metadata)).containsExactly(
                "getKey()", "setKey(…)", "getCreatedBy()", "setCreatedBy(…)", "getDeletedAt()",
                "setDeletedAt(…)", "getOwner()", "setOwner(…)", "getCreated()", "setCreated(…)",
                "getUpdatedAt()", "setUpdatedAt(…)", "isRemoved()", "setRemoved(…)", "getRevision()",
                "setRevision(…)");
    }

    @Test
    @DisplayName("a field shadowing a system column is that column, named once")
    void fieldShadowingSystemColumnIsTheColumn() {
        DomainMetadata metadata = entity()
                .softDelete(true)
                .fields(List.of(field("deleted", "java.lang.Boolean"), field("id", "java.util.UUID")))
                .build();

        assertThat(calls(metadata)).containsExactly("getId()", "setId(…)", "isDeleted()", "setDeleted(…)");
    }

    @Test
    @DisplayName("the handlers name the validated, server-set and reset fields in PascalCase, so a "
            + "snake-case name needs both spellings")
    void handlersSpellSnakeCaseNamesInPascalCase() {
        DomainMetadata metadata = entity()
                .fields(List.of(
                        FieldMetadata.builder("first_name", "java.lang.String").required(true).build(),
                        FieldMetadata.builder("issued_on", "java.lang.String").required(true).readOnly(true)
                                .build(),
                        FieldMetadata.builder("big_count", "java.lang.Long").min(0L).build(),
                        FieldMetadata.builder("code", "java.lang.String").maxLength(8).inCreate(false)
                                .inUpdate(false).build()))
                .build();

        assertThat(calls(metadata)).containsExactly(
                "getId()", "setId(…)", "getFirst_name()", "setFirst_name(…)", "getIssued_on()",
                "setIssued_on(…)", "getBig_count()", "setBig_count(…)", "getCode()", "setCode(…)",
                "getFirstName()", "getIssuedOn()", "getBigCount()");
    }

    @Test
    @DisplayName("an event reads its payload fields, minus the sensitive ones, a blob among them")
    void eventPayloadFields() {
        DomainMetadata metadata = entity()
                .dataScope(DataScope.TENANT)
                .fields(List.of(field("flag", "boolean"),
                        FieldMetadata.builder("scan", "BlobRef").blob(BlobMetadata.unrestricted()).build(),
                        field("secret", "java.lang.String")))
                .events(List.of(new DomainEventMetadata("Scanned", null, null, null,
                        List.of("flag", "scan", "secret", "absent"), List.of("secret"))))
                .build();

        assertThat(calls(metadata)).containsExactly(
                "getId()", "setId(…)", "isFlag()", "setFlag(…)", "getSecret()", "setSecret(…)",
                "getTenantId()", "setTenantId(…)", "setScan(…)", "getScan()");
    }

    @Test
    @DisplayName("the graph projection reads the key, the edges, their weights and the properties")
    void graphProjection() {
        DomainMetadata metadata = entity()
                .fields(List.of(field("score", "double"), field("pinned", "boolean")))
                .graphMetadata(new GraphMetadata("Note",
                        List.of(new GraphPropertyMetadata("isPinned", "BOOLEAN", false, "pinned"),
                                new GraphPropertyMetadata("label", "STRING", false)),
                        List.of(new GraphEdgeMetadata("LINKS", "Note", "LINKS", "linkedNoteId", null, null,
                                        null, null, true, "score", null),
                                new GraphEdgeMetadata("tags", "Tag", "TAGGED", "", null, null, null, null,
                                        null, null, null)),
                        List.of()))
                .build();

        assertThat(calls(metadata)).containsExactly(
                "getId()", "setId(…)", "getScore()", "setScore(…)", "isPinned()", "setPinned(…)",
                "getLabel()", "getLinkedNoteId()", "getTags()");
    }

    @Test
    @DisplayName("a UNIVERSE entity's shared scope is an ordinary column the update body carries")
    void universeSharedScope() {
        DomainMetadata metadata = entity()
                .dataScope(DataScope.UNIVERSE)
                .systemFields(new SystemFieldsMetadata(null, null, null, null, null, null, null, null, null,
                        null, "region"))
                .fields(List.of(FieldMetadata.builder("region", "java.lang.String").required(true).build(),
                        field("tenantId", "java.util.UUID")))
                .build();

        assertThat(calls(metadata)).containsExactly(
                "getId()", "setId(…)", "getRegion()", "setRegion(…)", "getTenantId()", "setTenantId(…)");
    }

    private static DomainMetadata.Builder entity() {
        return DomainMetadata.builder("Note", "com.example.notes.domain");
    }

    private static FieldMetadata field(String name, String type) {
        return FieldMetadata.builder(name, type).build();
    }

    private static List<String> calls(DomainMetadata metadata) {
        return KernelAccessors.required(metadata).stream()
                .map(accessor -> accessor.method() + (accessor.setter() ? "(…)" : "()"))
                .toList();
    }
}
