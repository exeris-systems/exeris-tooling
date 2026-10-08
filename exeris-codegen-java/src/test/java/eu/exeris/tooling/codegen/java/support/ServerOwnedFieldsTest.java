package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ServerOwnedFields")
class ServerOwnedFieldsTest {

    @Test
    @DisplayName("an entity with no system role: only the key is server-owned")
    void plainEntity() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("version", "Long").build()))
                .build();

        assertThat(ServerOwnedFields.notInUpdateBody(metadata)).containsExactly("id");
        assertThat(ServerOwnedFields.keptOnUpdate(metadata)).containsExactly("id");
    }

    @Test
    @DisplayName("every role on: the body carries the version only; the update writes updatedAt itself")
    void everyRole() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .dataScope(DataScope.TENANT).audited(true).softDelete(true).versioned(true)
                .build();

        assertThat(ServerOwnedFields.notInUpdateBody(metadata)).containsExactly(
                "createdAt", "createdBy", "deleted", "deletedAt", "deletedBy", "id", "tenantId",
                "updatedAt", "updatedBy");
        assertThat(ServerOwnedFields.keptOnUpdate(metadata)).containsExactly(
                "createdAt", "createdBy", "deleted", "deletedAt", "deletedBy", "id", "tenantId", "updatedBy");
    }

    @Test
    @DisplayName("declared names: a renamed version stays in the body, a renamed update stamp is server-set")
    void declaredNames() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .audited(true).versioned(true)
                .systemFields(SystemFieldsMetadata.builder()
                        .primaryKeyField("orderNo").versionField("rev").updatedAtField("touchedAt").build())
                .build();

        assertThat(ServerOwnedFields.notInUpdateBody(metadata))
                .contains("orderNo", "touchedAt").doesNotContain("rev", "id");
        assertThat(ServerOwnedFields.keptOnUpdate(metadata)).doesNotContain("touchedAt", "rev");
    }

    @Test
    @DisplayName("a UNIVERSE shared scope is in the body and is written (ADR-090 §3)")
    void sharedScope() {
        DomainMetadata metadata = DomainMetadata.builder("Species", "com.example.domain")
                .dataScope(DataScope.UNIVERSE)
                .systemFields(SystemFieldsMetadata.builder().sharedScopeField("worldId").build())
                .fields(List.of(FieldMetadata.builder("worldId", "java.util.UUID").build()))
                .build();

        assertThat(ServerOwnedFields.notInUpdateBody(metadata)).contains("tenantId").doesNotContain("worldId");
        assertThat(ServerOwnedFields.keptOnUpdate(metadata)).contains("tenantId").doesNotContain("worldId");
    }

    @Test
    @DisplayName("a read-only field is not in the body and is kept by the request update only (ADR-090 Amendment 2)")
    void readOnlyField() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("title", "String").build(),
                        FieldMetadata.builder("status", "String").readOnly(true).build()))
                .build();

        assertThat(ServerOwnedFields.fixedOnRequestUpdate(metadata)).containsExactly("status");
        assertThat(ServerOwnedFields.notInUpdateBody(metadata)).containsExactly("id", "status");
        assertThat(ServerOwnedFields.keptOnRequestUpdate(metadata)).containsExactly("id", "status");
        assertThat(ServerOwnedFields.keptOnUpdate(metadata)).containsExactly("id");
    }

    @Test
    @DisplayName("a read-only field in a system role keeps that role's rule: the version and the shared scope stay")
    void readOnlySystemRoleKeepsItsRole() {
        DomainMetadata metadata = DomainMetadata.builder("Species", "com.example.domain")
                .dataScope(DataScope.UNIVERSE).versioned(true)
                .systemFields(SystemFieldsMetadata.builder().sharedScopeField("worldId").build())
                .fields(List.of(FieldMetadata.builder("worldId", "java.util.UUID").readOnly(true).build(),
                        FieldMetadata.builder("version", "Long").readOnly(true).build()))
                .build();

        assertThat(ServerOwnedFields.notInUpdateBody(metadata)).contains("id", "tenantId")
                .doesNotContain("worldId", "version");
        assertThat(ServerOwnedFields.keptOnUpdate(metadata)).contains("id", "tenantId")
                .doesNotContain("worldId", "version");
        assertThat(ServerOwnedFields.fixedOnRequestUpdate(metadata)).isEmpty();
    }

    @Test
    @DisplayName("an inUpdate = false field is not in the body and is kept by the request update only (ADR-090 Amendment 3)")
    void notInUpdateField() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("title", "String").build(),
                        FieldMetadata.builder("code", "String").inUpdate(false).build()))
                .build();

        assertThat(ServerOwnedFields.fixedOnRequestUpdate(metadata)).containsExactly("code");
        assertThat(ServerOwnedFields.notInUpdateBody(metadata)).containsExactly("code", "id");
        assertThat(ServerOwnedFields.keptOnRequestUpdate(metadata)).containsExactly("code", "id");
        assertThat(ServerOwnedFields.keptOnUpdate(metadata)).containsExactly("id");
        assertThat(ServerOwnedFields.notInCreateBody(metadata)).containsExactly("id");
    }

    @Test
    @DisplayName("the create body leaves out the key, the owner, the shared scope, read-only and inCreate = false fields")
    void notInCreateBody() {
        DomainMetadata metadata = DomainMetadata.builder("Species", "com.example.domain")
                .dataScope(DataScope.UNIVERSE)
                .systemFields(SystemFieldsMetadata.builder().sharedScopeField("worldId").build())
                .fields(List.of(FieldMetadata.builder("title", "String").build(),
                        FieldMetadata.builder("worldId", "java.util.UUID").build(),
                        FieldMetadata.builder("status", "String").readOnly(true).build(),
                        FieldMetadata.builder("note", "String").inCreate(false).build()))
                .build();

        assertThat(ServerOwnedFields.notInCreateBody(metadata))
                .containsExactly("id", "note", "status", "tenantId", "worldId");
        assertThat(ServerOwnedFields.notInUpdateBody(metadata)).doesNotContain("note", "worldId");
    }

    @Test
    @DisplayName("the server sets a required read-only reference field before the insert; a primitive, an "
            + "optional field and a system role are not listed")
    void setByServerOnCreate() {
        DomainMetadata metadata = DomainMetadata.builder("Tx", "com.example.domain").audited(true)
                .fields(List.of(FieldMetadata.builder("providerTxId", "String").required(true).readOnly(true).build(),
                        FieldMetadata.builder("attempts", "int").required(true).readOnly(true).build(),
                        FieldMetadata.builder("note", "String").readOnly(true).build(),
                        FieldMetadata.builder("createdBy", "String").required(true).readOnly(true).build(),
                        FieldMetadata.builder("title", "String").required(true).build()))
                .build();

        assertThat(ServerOwnedFields.setByServerOnCreate(metadata)).extracting(FieldMetadata::name)
                .containsExactly("providerTxId");
    }

    @Test
    @DisplayName("the version field: the declared name, else version")
    void versionField() {
        assertThat(ServerOwnedFields.versionField(DomainMetadata.builder("Order", "com.example.domain").build()))
                .isEqualTo("version");
        assertThat(ServerOwnedFields.versionField(DomainMetadata.builder("Order", "com.example.domain")
                .systemFields(SystemFieldsMetadata.builder().versionField("rev").build()).build()))
                .isEqualTo("rev");
    }
}
