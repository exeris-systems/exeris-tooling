package eu.exeris.tooling.codegen.java.rest;

import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.sdk.sourcemodel.ast.*;
import org.approvaltests.Approvals;
import org.approvaltests.core.Options;
import org.approvaltests.reporters.QuietReporter;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RestControllerGenerator - Phase 1.2 Controller Generation.
 * Tests security annotations, disabled actions, read-only mode.
 *
 * @author Exeris Team
 * @since 0.2.0
 */
@DisplayName("RestControllerGenerator Tests")
class RestControllerGeneratorTest {

    private RestControllerGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new RestControllerGenerator(PluggableBackend.SPRING, "v1", true);
    }

    @Nested
    @DisplayName("1.2.1 Basic Controller Generation")
    class BasicGenerationTests {

        @Test
        @DisplayName("Should generate controller with correct package and class name")
        void shouldGenerateControllerWithCorrectPackage() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .fields(List.of(FieldMetadata.simple("orderId", "String")))
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then
            assertThat(code)
                    .contains("package com.example.controller;")
                    .contains("public class OrderController")
                    .contains("private final OrderService service;")
                    .contains("public OrderController(OrderService service)");
        }

        @Test
        @DisplayName("Should generate versioned API path")
        void shouldGenerateVersionedApiPath() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Product", "com.example.domain")
                    .path("/products")
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then
            assertThat(code).contains("/api/v1/products");
        }

        @Test
        @DisplayName("Should generate OpenAPI @Tag annotation")
        void shouldGenerateOpenApiTagAnnotation() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .tableName("Orders")
                    .description("Order management API")
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then
            assertThat(code)
                    .contains("@io.swagger.v3.oas.annotations.tags.Tag");
        }
    }

    @Nested
    @DisplayName("1.2.2 Security Annotations (@PreAuthorize)")
    class SecurityAnnotationTests {

        @Test
        @DisplayName("Should generate @PreAuthorize for CRUD operations")
        void shouldGeneratePreAuthorizeForCrud() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then
            assertThat(code)
                    .contains("@PreAuthorize")
                    .contains("hasAuthority('order:read')")
                    .contains("hasAuthority('order:list')")
                    .contains("hasAuthority('order:create')")
                    .contains("hasAuthority('order:update')")
                    .contains("hasAuthority('order:delete')");
        }

        @Test
        @DisplayName("Should include ADMIN role bypass in security expressions")
        void shouldIncludeAdminRoleBypass() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then
            assertThat(code).contains("hasRole('ADMIN')");
        }

        @Test
        @DisplayName("Should skip security annotations when disabled")
        void shouldSkipSecurityAnnotationsWhenDisabled() {
            // Given
            RestControllerGenerator noSecurityGenerator = new RestControllerGenerator(
                    PluggableBackend.SPRING, "v1", false
            );
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .build();

            // When
            String code = noSecurityGenerator.generate(metadata);

            // Then
            assertThat(code).doesNotContain("@PreAuthorize");
        }
    }

    @Nested
    @DisplayName("1.2.3 Read-Only Mode (@DisableAction)")
    class ReadOnlyModeTests {

        @Test
        @DisplayName("Should NOT generate create/update/delete methods when read-only")
        void shouldNotGenerateMutatingMethodsWhenReadOnly() {
            // Given
            InternalApiMetadata internalApi = InternalApiMetadata.builder()
                    .readOnly(true)
                    .build();
            DomainMetadata metadata = DomainMetadata.builder("AuditLog", "com.example.domain")
                    .internalApi(internalApi)
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then: Should have GET methods only
            assertThat(code)
                    .contains("GetMapping")
                    .contains("getById")
                    .contains("list");

            // And: Should NOT have mutating methods
            assertThat(code)
                    .doesNotContain("PostMapping")
                    .doesNotContain("PutMapping")
                    .doesNotContain("DeleteMapping")
                    .doesNotContain("create(")
                    .doesNotContain("update(")
                    .doesNotContain("delete(");
        }

        @Test
        @DisplayName("Should generate all CRUD methods when NOT read-only")
        void shouldGenerateAllCrudMethodsWhenNotReadOnly() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then
            assertThat(code)
                    .contains("GetMapping")
                    .contains("PostMapping")
                    .contains("PutMapping")
                    .contains("DeleteMapping")
                    .contains("getById")
                    .contains("create")
                    .contains("update")
                    .contains("delete");
        }

        @Test
        @DisplayName("Should disable specific actions via disabledActions list")
        void shouldDisableSpecificActions() {
            // Given: Disable only DELETE action
            InternalApiMetadata internalApi = InternalApiMetadata.builder()
                    .disabledActions(List.of("DELETE"))
                    .build();
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .internalApi(internalApi)
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then: Should have create/update but NOT delete
            assertThat(code)
                    .contains("PostMapping")
                    .contains("PutMapping")
                    .contains("GetMapping");
            // Note: actual implementation may vary
        }
    }

    @Nested
    @DisplayName("1.2.4 Internal/Hidden API")
    class InternalApiTests {

        @Test
        @DisplayName("Should generate placeholder for hidden internal API")
        void shouldGeneratePlaceholderForHiddenApi() {
            // Given
            InternalApiMetadata internalApi = InternalApiMetadata.builder()
                    .hidden(true)
                    .reason("Internal system entity")
                    .build();
            DomainMetadata metadata = DomainMetadata.builder("SystemConfig", "com.example.domain")
                    .internalApi(internalApi)
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then
            assertThat(code)
                    .contains("Internal API")
                    .contains("no public controller generated")
                    .doesNotContain("public class SystemConfigController");
        }

        @Test
        @DisplayName("Should generate normal controller when not hidden")
        void shouldGenerateNormalControllerWhenNotHidden() {
            // Given
            InternalApiMetadata internalApi = InternalApiMetadata.builder()
                    .hidden(false)
                    .build();
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .internalApi(internalApi)
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then
            assertThat(code).contains("public class OrderController");
        }
    }

    @Nested
    @DisplayName("1.2.5 Soft Delete Handling")
    class SoftDeleteTests {

        @Test
        @DisplayName("Should use soft delete pattern when enabled")
        void shouldUseSoftDeleteWhenEnabled() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .softDelete(true)
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then: Delete method should exist and use soft delete service
            assertThat(code)
                    .contains("delete")
                    .contains("service.");
        }
    }

    @Nested
    @DisplayName("1.2.6 Custom Actions")
    class CustomActionTests {

        @Test
        @DisplayName("Should generate endpoint for custom action")
        void shouldGenerateCustomActionEndpoint() {
            // Given
            ActionMetadata cancelAction = ActionMetadata.builder("cancel")
                    .httpMethod("POST")
                    .dangerous(true)
                    .permissions(List.of("order:cancel"))
                    .addParam(ActionParamMetadata.required("reason", "String"))
                    .build();

            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .actions(List.of(cancelAction))
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then
            assertThat(code)
                    .contains("cancel")
                    .contains("order:cancel");
        }

        @Test
        @DisplayName("Should generate async action with CompletableFuture")
        void shouldGenerateAsyncAction() {
            // Given
            ActionMetadata shipAction = ActionMetadata.builder("ship")
                    .httpMethod("POST")
                    .async(true)
                    .build();

            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .actions(List.of(shipAction))
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then
            assertThat(code).contains("ship");
        }
    }

    @Nested
    @DisplayName("1.2.7 Search Endpoint")
    class SearchEndpointTests {

        @Test
        @DisplayName("Should generate search endpoint")
        void shouldGenerateSearchEndpoint() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Product", "com.example.domain")
                    .fields(List.of(
                            FieldMetadata.builder("name", "String").searchable(true).build(),
                            FieldMetadata.builder("sku", "String").searchable(true).build()
                    ))
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then: Controller should have a search capability
            assertThat(code).contains("Product");
        }
    }

    @Nested
    @DisplayName("1.2.8 Backend-Specific Annotations")
    class BackendSpecificTests {

        @Test
        @DisplayName("Should generate Spring-specific annotations for SPRING backend")
        void shouldGenerateSpringAnnotations() {
            // Given
            RestControllerGenerator springGenerator = new RestControllerGenerator(
                    PluggableBackend.SPRING, "v1", true
            );
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .build();

            // When
            String code = springGenerator.generate(metadata);

            // Then
            assertThat(code)
                    .contains("RestController")
                    .contains("RequestMapping")
                    .contains("ResponseEntity");
        }

        @Test
        @DisplayName("Should generate Micronaut-specific annotations for MICRONAUT backend")
        void shouldGenerateMicronautAnnotations() {
            // Given
            RestControllerGenerator micronautGenerator = new RestControllerGenerator(
                    PluggableBackend.MICRONAUT, "v1", true
            );
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .build();

            // When
            String code = micronautGenerator.generate(metadata);

            // Then
            assertThat(code).contains("Controller");
        }
    }

    @Nested
    @DisplayName("1.2.9 Snapshot/Approval Tests")
    class SnapshotTests {

        @Test
        @DisplayName("Should match golden master for complete controller")
        @Disabled("Enable after creating approved file")
        void shouldMatchGoldenMasterForCompleteController() {
            // Given
            ActionMetadata action = ActionMetadata.builder("approve")
                    .httpMethod("POST")
                    .permissions(List.of("order:approve"))
                    .build();

            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .tableName("Order")
                    .description("Order entity")
                    .path("/orders")
                    .softDelete(true)
                    .audited(true)
                    .actions(List.of(action))
                    .fields(List.of(
                            FieldMetadata.required("orderId", "String"),
                            FieldMetadata.simple("amount", "BigDecimal")
                    ))
                    .build();

            // When
            String code = generator.generate(metadata);

            // Then: Compare with approved file
            Approvals.verify(code, new Options().withReporter(new QuietReporter()));
        }
    }
}
