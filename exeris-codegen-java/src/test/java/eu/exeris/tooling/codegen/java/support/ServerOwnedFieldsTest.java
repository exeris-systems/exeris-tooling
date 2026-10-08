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
    @DisplayName("a UNIVERSE shared scope is not in the body and is still written (ADR-090 §3)")
    void sharedScope() {
        DomainMetadata metadata = DomainMetadata.builder("Species", "com.example.domain")
                .dataScope(DataScope.UNIVERSE)
                .systemFields(SystemFieldsMetadata.builder().sharedScopeField("worldId").build())
                .fields(List.of(FieldMetadata.builder("worldId", "java.util.UUID").build()))
                .build();

        assertThat(ServerOwnedFields.notInUpdateBody(metadata)).contains("worldId", "tenantId");
        assertThat(ServerOwnedFields.keptOnUpdate(metadata)).contains("tenantId").doesNotContain("worldId");
    }
}
