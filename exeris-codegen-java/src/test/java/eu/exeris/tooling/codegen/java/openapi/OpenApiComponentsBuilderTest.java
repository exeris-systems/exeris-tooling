package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenApiComponentsBuilder")
class OpenApiComponentsBuilderTest {

    @Test
    @DisplayName("Builds entity / CreateDto / UpdateDto schemas + security schemes")
    void buildsThreeSchemasAndSecurity() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .description("Customer order entity")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").required(true).build(),
                        FieldMetadata.builder("amount", "BigDecimal").build()))
                .build();

        Components components = OpenApiComponentsBuilder.buildComponents(meta);

        assertThat(components.getSchemas())
                .containsKeys("Order", "OrderCreateDto", "OrderUpdateDto");
        assertThat(components.getSecuritySchemes()).isNotEmpty();
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
}
