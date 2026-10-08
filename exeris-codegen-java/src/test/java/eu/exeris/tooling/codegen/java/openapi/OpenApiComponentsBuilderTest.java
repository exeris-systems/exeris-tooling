package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenApiComponentsBuilder")
class OpenApiComponentsBuilderTest {

    @Test
    @DisplayName("Builds entity / CreateDto / UpdateDto / Page schemas, and declares no security scheme")
    void buildsFourSchemas() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .description("Customer order entity")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").required(true).build(),
                        FieldMetadata.builder("amount", "BigDecimal").build()))
                .build();

        Components components = OpenApiComponentsBuilder.buildComponents(meta);

        assertThat(components.getSchemas())
                .containsOnlyKeys("Order", "OrderCreateDto", "OrderUpdateDto", "OrderPage");
        // ADR-079: the emitted app performs no authentication, so the spec describes none.
        assertThat(components.getSecuritySchemes()).isNull();
    }

    @Test
    @DisplayName("the owner is readOnly on the entity and absent from both DTOs")
    void ownerIsReadOnlyAndNotInTheDtos() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .dataScope(eu.exeris.sdk.sourcemodel.ast.DataScope.TENANT)
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").required(true).build(),
                        FieldMetadata.builder("tenantId", "java.util.UUID").build()))
                .build();

        Components components = OpenApiComponentsBuilder.buildComponents(meta);

        Schema<?> tenant = (Schema<?>) components.getSchemas().get("Order").getProperties().get("tenantId");
        assertThat(tenant.getReadOnly()).isTrue();
        Schema<?> number = (Schema<?>) components.getSchemas().get("Order").getProperties().get("orderNumber");
        assertThat(number.getReadOnly()).isNull();
        assertThat(components.getSchemas().get("OrderCreateDto").getProperties())
                .containsKey("orderNumber").doesNotContainKey("tenantId");
        assertThat(components.getSchemas().get("OrderUpdateDto").getProperties())
                .containsKey("orderNumber").doesNotContainKey("tenantId");
    }

    @Test
    @DisplayName("a renamed owner and a UNIVERSE shared-scope key are both server-owned")
    void renamedOwnerAndSharedScopeAreReadOnly() {
        DomainMetadata meta = DomainMetadata.builder("Species", "com.example.domain")
                .dataScope(eu.exeris.sdk.sourcemodel.ast.DataScope.UNIVERSE)
                .systemFields(new eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata("id", "createdAt",
                        "createdBy", "updatedAt", "updatedBy", "organizationId", "version", null, null,
                        null, "worldId"))
                .fields(List.of(
                        FieldMetadata.builder("name", "String").build(),
                        FieldMetadata.builder("organizationId", "java.util.UUID").build(),
                        FieldMetadata.builder("worldId", "java.util.UUID").build()))
                .build();

        Components components = OpenApiComponentsBuilder.buildComponents(meta);

        for (String owned : List.of("organizationId", "worldId")) {
            Schema<?> field = (Schema<?>) components.getSchemas().get("Species").getProperties().get(owned);
            assertThat(field.getReadOnly()).as(owned).isTrue();
            assertThat(components.getSchemas().get("SpeciesCreateDto").getProperties()).doesNotContainKey(owned);
            assertThat(components.getSchemas().get("SpeciesUpdateDto").getProperties()).doesNotContainKey(owned);
        }
    }

    @Test
    @DisplayName("the update body leaves out the audit and soft-delete fields and keeps the version")
    void updateDtoLeavesOutTheFieldsTheUpdateKeeps() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .audited(true).softDelete(true).versioned(true)
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").required(true).build(),
                        FieldMetadata.builder("createdAt", "java.time.Instant").build(),
                        FieldMetadata.builder("createdBy", "String").build(),
                        FieldMetadata.builder("updatedAt", "java.time.Instant").build(),
                        FieldMetadata.builder("updatedBy", "String").build(),
                        FieldMetadata.builder("deleted", "boolean").build(),
                        FieldMetadata.builder("deletedAt", "java.time.Instant").build(),
                        FieldMetadata.builder("deletedBy", "String").build(),
                        FieldMetadata.builder("version", "Long").build()))
                .build();

        Components components = OpenApiComponentsBuilder.buildComponents(meta);

        assertThat(components.getSchemas().get("OrderUpdateDto").getProperties())
                .containsOnlyKeys("orderNumber", "version");
    }

    @Test
    @DisplayName("a global entity's tenantId-named field is an ordinary, writable field")
    void globalTenantIdFieldIsUntouched() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("tenantId", "java.util.UUID").build()))
                .build();

        Components components = OpenApiComponentsBuilder.buildComponents(meta);

        Schema<?> tenant = (Schema<?>) components.getSchemas().get("Order").getProperties().get("tenantId");
        assertThat(tenant.getReadOnly()).isNull();
        assertThat(components.getSchemas().get("OrderCreateDto").getProperties()).containsKey("tenantId");
    }

    @Test
    @DisplayName("Entity schema: empty Builder-default description is kept verbatim (no \"<Entity> entity\" fallback fires)")
    void entitySchemaDescriptionKeptEmpty() {
        // Contrast with OpenApiTagsBuilder, which guards description on
        // both null AND isBlank(). OpenApiComponentsBuilder.buildEntitySchema
        // only checks != null, and DomainMetadata.Builder defaults
        // description to "" (not null), so the empty string is kept
        // verbatim and the "<Entity> entity" fallback never fires here.
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain").build();

        Schema<?> entitySchema = OpenApiComponentsBuilder.buildComponents(meta)
                .getSchemas().get("Order");

        assertThat(entitySchema.getDescription()).isEmpty();
    }

    @Test
    @DisplayName("Entity schema always carries id (uuid) and createdAt / updatedAt (date-time) properties")
    void entitySchemaContainsAuditFields() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain").build();

        Schema<?> entitySchema = OpenApiComponentsBuilder.buildComponents(meta)
                .getSchemas().get("Order");

        assertThat(entitySchema.getProperties()).containsKeys("id", "createdAt", "updatedAt");
        Schema<?> id = (Schema<?>) entitySchema.getProperties().get("id");
        assertThat(id.getType()).isEqualTo("string");
        assertThat(id.getFormat()).isEqualTo("uuid");
        Schema<?> createdAt = (Schema<?>) entitySchema.getProperties().get("createdAt");
        assertThat(createdAt.getFormat()).isEqualTo("date-time");
    }

    @Test
    @DisplayName("Field with @Field.dataType=url gets OpenAPI format: uri; other dataType facets do not (L1)")
    void dataTypeUrlMapsToFormatUri() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("homepage", "String").dataType("url").build(),
                        FieldMetadata.builder("amount", "BigDecimal").dataType("currency").build()))
                .build();

        Schema<?> entitySchema = OpenApiComponentsBuilder.buildComponents(meta)
                .getSchemas().get("Order");

        // url -> the standard OpenAPI "uri" format (the wire-contract parity hint).
        Schema<?> homepage = (Schema<?>) entitySchema.getProperties().get("homepage");
        assertThat(homepage.getFormat()).isEqualTo("uri");
        // A non-url dataType (currency — an FE-only facet) leaves the format untouched.
        Schema<?> amount = (Schema<?>) entitySchema.getProperties().get("amount");
        assertThat(amount.getFormat()).isNotEqualTo("uri");
    }

    @Test
    @DisplayName("CreateDto: excludes readOnly and \"id\" fields; collects required ones in required[]")
    void createDtoExcludesReadOnlyAndId() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("id", "UUID").build(),
                        FieldMetadata.builder("orderNumber", "String").required(true).build(),
                        FieldMetadata.builder("audit", "String").readOnly(true).build(),
                        FieldMetadata.builder("amount", "BigDecimal").build()))
                .build();

        Schema<?> createDto = OpenApiComponentsBuilder.buildComponents(meta)
                .getSchemas().get("OrderCreateDto");

        assertThat(createDto.getProperties()).containsKeys("orderNumber", "amount");
        assertThat(createDto.getProperties()).doesNotContainKeys("id", "audit");
        assertThat(createDto.getRequired()).containsExactly("orderNumber");
    }

    @Test
    @DisplayName("CreateDto: required[] omitted when no field is flagged required")
    void createDtoOmitsRequiredArrayWhenEmpty() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        Schema<?> createDto = OpenApiComponentsBuilder.buildComponents(meta)
                .getSchemas().get("OrderCreateDto");

        assertThat(createDto.getRequired()).isNullOrEmpty();
    }

    @Test
    @DisplayName("UpdateDto: same shape as CreateDto but without a required[] list")
    void updateDtoMatchesCreateDtoWithoutRequired() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("id", "UUID").build(),
                        FieldMetadata.builder("orderNumber", "String").required(true).build(),
                        FieldMetadata.builder("audit", "String").readOnly(true).build()))
                .build();

        Schema<?> updateDto = OpenApiComponentsBuilder.buildComponents(meta)
                .getSchemas().get("OrderUpdateDto");

        assertThat(updateDto.getProperties()).containsKey("orderNumber");
        assertThat(updateDto.getProperties()).doesNotContainKeys("id", "audit");
        assertThat(updateDto.getRequired()).isNullOrEmpty();
    }

    @Test
    @DisplayName("Field schema: optional metadata (description / lengths / range / pattern) propagates when set")
    void fieldSchemaCarriesOptionalMetadata() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String")
                                .description("Business identifier")
                                .minLength(3).maxLength(20)
                                .pattern("^ORD-\\d+$").build(),
                        FieldMetadata.builder("quantity", "int")
                                .min(1L).max(1000L).build()))
                .build();

        Schema<?> entitySchema = OpenApiComponentsBuilder.buildComponents(meta)
                .getSchemas().get("Order");

        Schema<?> orderNumber = (Schema<?>) entitySchema.getProperties().get("orderNumber");
        assertThat(orderNumber.getDescription()).isEqualTo("Business identifier");
        assertThat(orderNumber.getMinLength()).isEqualTo(3);
        assertThat(orderNumber.getMaxLength()).isEqualTo(20);
        assertThat(orderNumber.getPattern()).isEqualTo("^ORD-\\d+$");

        Schema<?> quantity = (Schema<?>) entitySchema.getProperties().get("quantity");
        assertThat(quantity.getMinimum()).isEqualTo(java.math.BigDecimal.valueOf(1L));
        assertThat(quantity.getMaximum()).isEqualTo(java.math.BigDecimal.valueOf(1000L));
    }

    @Test
    @DisplayName("ADR-104: a renamed key is the entity's identifier property and is absent from both DTOs")
    void renamedKeyIsTheIdentifierProperty() {
        DomainMetadata meta = DomainMetadata.builder("Invoice", "com.example.domain")
                .systemFields(eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata.builder()
                        .primaryKeyField("invoiceNo").build())
                .fields(List.of(
                        FieldMetadata.simple("invoiceNo", "java.util.UUID"),
                        FieldMetadata.builder("customer", "String").required(true).build()))
                .build();

        Components components = OpenApiComponentsBuilder.buildComponents(meta);

        Map<String, Schema> entity = components.getSchemas().get("Invoice").getProperties();
        assertThat(entity.keySet()).startsWith("invoiceNo").doesNotContain("id");
        assertThat(entity.get("invoiceNo").getFormat()).isEqualTo("uuid");
        assertThat(components.getSchemas().get("InvoiceCreateDto").getProperties())
                .containsKey("customer").doesNotContainKey("invoiceNo");
        assertThat(components.getSchemas().get("InvoiceUpdateDto").getProperties())
                .containsKey("customer").doesNotContainKey("invoiceNo");
    }
}
