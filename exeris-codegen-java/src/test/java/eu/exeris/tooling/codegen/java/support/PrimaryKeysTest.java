package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PrimaryKeys — the effective key (ADR-104)")
class PrimaryKeysTest {

    private static DomainMetadata entity(SystemFieldsMetadata systemFields) {
        return DomainMetadata.builder("Invoice", "com.example.domain").systemFields(systemFields).build();
    }

    @Test
    @DisplayName("no systemFields: the key is id, column id, accessors getId / setId")
    void defaultsToId() {
        DomainMetadata metadata = entity(null);

        assertThat(PrimaryKeys.field(metadata)).isEqualTo("id");
        assertThat(PrimaryKeys.column(metadata)).isEqualTo("id");
        assertThat(PrimaryKeys.getter(metadata)).isEqualTo("getId");
        assertThat(PrimaryKeys.setter(metadata)).isEqualTo("setId");
    }

    @ParameterizedTest(name = "primaryKeyField=\"{0}\"")
    @ValueSource(strings = {"", "  "})
    @DisplayName("a blank primaryKeyField names no key, so the key stays id")
    void blankStaysId(String blank) {
        assertThat(PrimaryKeys.field(entity(SystemFieldsMetadata.builder().primaryKeyField(blank).build())))
                .isEqualTo("id");
    }

    @Test
    @DisplayName("systemFields without a primaryKeyField keeps id")
    void otherRolesKeepId() {
        SystemFieldsMetadata roles = SystemFieldsMetadata.builder().primaryKeyField(null).tenantIdField("orgId").build();

        assertThat(PrimaryKeys.field(entity(roles))).isEqualTo("id");
    }

    @Test
    @DisplayName("a named key: the field, its snake-case column and its accessors follow the name")
    void followsTheNamedKey() {
        DomainMetadata metadata = entity(SystemFieldsMetadata.builder().primaryKeyField("invoiceNo").build());

        assertThat(PrimaryKeys.field(metadata)).isEqualTo("invoiceNo");
        assertThat(PrimaryKeys.column(metadata)).isEqualTo("invoice_no");
        assertThat(PrimaryKeys.getter(metadata)).isEqualTo("getInvoiceNo");
        assertThat(PrimaryKeys.setter(metadata)).isEqualTo("setInvoiceNo");
        assertThat(PrimaryKeys.isKey(metadata, "invoiceNo")).isTrue();
        assertThat(PrimaryKeys.isKey(metadata, "id")).isFalse();
    }
}
