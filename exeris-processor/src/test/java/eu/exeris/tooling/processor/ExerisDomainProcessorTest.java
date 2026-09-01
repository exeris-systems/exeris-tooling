package eu.exeris.tooling.processor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import eu.exeris.sdk.sourcemodel.ast.BindSource;
import eu.exeris.sdk.sourcemodel.ast.BindingMetadata;
import eu.exeris.sdk.sourcemodel.ast.BlockType;
import eu.exeris.sdk.sourcemodel.ast.ComponentNodeMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.RegionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ViewKind;
import eu.exeris.sdk.sourcemodel.ast.ViewMetadata;
import eu.exeris.sdk.sourcemodel.mutation.BaselineTrust;
import eu.exeris.sdk.sourcemodel.mutation.SchemaVersion;
import eu.exeris.sdk.sourcemodel.mutation.SourceDigest;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ExerisDomainProcessor - Phase 1.1 Metadata Verification.
 * Tests that the processor correctly builds metadata from SDK annotations.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
@DisplayName("ExerisDomainProcessor Tests")
class ExerisDomainProcessorTest {

    @Nested
    @DisplayName("1.1.1 Field Annotation Processing")
    class FieldAnnotationTests {

        @Test
        @DisplayName("Should extract required and searchable from @Field annotation")
        void shouldExtractFieldAttributes() throws IOException {
            // Given: Entity with @Field annotations
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;
                    
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    
                    @ExerisDomain(module = "sales", path = "/orders")
                    public class Order {
                        
                        @Field(label = "Customer ID", required = true)
                        private String customerId;
                        
                        @Field(label = "Description", searchable = true, sortable = true)
                        private String description;
                        
                        @Field(label = "Amount", filterable = true)
                        private Long amount;
                    }
                    """
            );

            // When: Compile with ExerisDomainProcessor
            Compilation compilation = compileWithProcessor(source);

            // Then: Compilation succeeds
            assertThat(compilation).succeededWithoutWarnings();

            // And: Metadata file is generated
            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/Order.json"
            );
            assertThat(metadataFile).isPresent();

            // And: Metadata contains basic structure
            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("\"entityName\" : \"Order\"")
                    .contains("\"packageName\" : \"com.example\"")
                    .contains("\"module\" : \"sales\"")
                    .contains("\"path\" : \"/orders\"");
        }

        @Test
        @DisplayName("Should extract field with unique and indexed attributes")
        void shouldExtractFieldWithUniqueAndIndexed() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Customer",
                    """
                    package com.example;
                    
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    
                    @ExerisDomain(module = "crm", path = "/customers")
                    public class Customer {
                        
                        @Field(label = "Email", required = true, unique = true, indexed = true)
                        private String email;
                        
                        @Field(label = "Account Number", sortable = true, filterable = true)
                        private String accountNumber;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/Customer.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("\"entityName\" : \"Customer\"")
                    .contains("\"packageName\" : \"com.example\"");
        }

        @Test
        @DisplayName("Should extract @Field.dataType into FieldMetadata.dataType (Wave 1A)")
        void shouldExtractFieldDataType() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Invoice",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "billing", path = "/invoices")
                    public class Invoice {

                        @Field(label = "Amount", dataType = "currency")
                        private java.math.BigDecimal amount;

                        @Field(label = "Reference")
                        private String reference;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Invoice.json");
            assertThat(metadataFile).isPresent();

            ObjectMapper mapper = new ObjectMapper()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            DomainMetadata dm = mapper.readValue(readContent(metadataFile.get()), DomainMetadata.class);

            // The annotated dataType is propagated…
            assertThat(dm.findField("amount")).get()
                    .extracting("dataType").isEqualTo("currency");
            // …and a field without dataType keeps it null (blank -> null in the builder).
            assertThat(dm.findField("reference")).get()
                    .extracting("dataType").isNull();
        }
    }

    @Nested
    @DisplayName("1.1.2 Saga Annotation Processing")
    class SagaAnnotationTests {

        @Test
        @DisplayName("Should extract Saga metadata with steps")
        void shouldExtractSagaWithSteps() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.OrderCreationSaga",
                    """
                    package com.example;
                    
                    import eu.exeris.sdk.annotation.Saga;
                    import eu.exeris.sdk.annotation.SagaStep;
                    
                    @Saga(name = "OrderCreationSaga", timeout = "PT30M", maxRetries = 3)
                    public class OrderCreationSaga {
                        
                        @SagaStep(order = 1, name = "validateOrder", service = "order-service", command = "ValidateOrderCommand", compensation = "CancelValidationCommand")
                        public void step1() {}
                        
                        @SagaStep(order = 2, name = "reserveInventory", service = "inventory-service", command = "ReserveInventoryCommand", compensation = "ReleaseInventoryCommand")
                        public void step2() {}
                        
                        @SagaStep(order = 3, name = "processPayment", service = "payment-service", command = "ProcessPaymentCommand", compensation = "RefundPaymentCommand")
                        public void step3() {}
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/OrderCreationSaga.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("OrderCreationSaga")
                    .contains("\"saga\" : true");
        }

        @Test
        @DisplayName("S2: a repeated @SagaStep contributes its steps instead of vanishing")
        void repeatedSagaStepIsExtracted() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.CheckoutSaga",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Saga;
                    import eu.exeris.sdk.annotation.SagaStep;

                    @Saga(name = "CheckoutSaga")
                    public class CheckoutSaga {

                        // Repeated on ONE method: javac replaces both with the synthesised
                        // @SagaSteps container and leaves no direct @SagaStep mirror behind.
                        @SagaStep(order = 1, name = "reserve", service = "stock", command = "Reserve")
                        @SagaStep(order = 2, name = "charge", service = "billing", command = "Charge")
                        public void bothHalves() {}

                        @SagaStep(order = 3, name = "notify", service = "mail", command = "Notify")
                        public void notifyCustomer() {}
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();

            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/CheckoutSaga.json").orElseThrow());

            // Before S2 the first two were dropped: the lookup matched the exact type
            // eu.exeris.sdk.annotation.SagaStep, which a synthesised container does not present.
            // The emitted flow was short by two steps, with no diagnostic anywhere.
            assertThat(metadata)
                    .contains("\"reserve\"")
                    .contains("\"charge\"")
                    .contains("\"notify\"");
        }

        @Test
        @DisplayName("S2: the container and a standalone step on the same class do not double-count")
        void repeatedAndStandaloneStepsAreEachCountedOnce() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.CheckoutSaga",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Saga;
                    import eu.exeris.sdk.annotation.SagaStep;

                    @Saga(name = "CheckoutSaga")
                    public class CheckoutSaga {

                        @SagaStep(order = 1, name = "reserve", service = "stock", command = "Reserve")
                        @SagaStep(order = 2, name = "charge", service = "billing", command = "Charge")
                        public void bothHalves() {}

                        @SagaStep(order = 3, name = "notify", service = "mail", command = "Notify")
                        public void notifyCustomer() {}
                    }
                    """
            );

            String metadata = readContent(compileWithProcessor(source).generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/CheckoutSaga.json").orElseThrow());

            // Reading both the direct mirror and the container is the fix; reading them without
            // the exclusivity javac guarantees would emit "notify" twice. javac never presents
            // both shapes on one element, and this pins that assumption rather than trusting it.
            assertThat(metadata.split("\"notify\"", -1)).hasSize(2);
        }

        @Test
        @DisplayName("S1: @Saga.version reaches the metadata instead of always reporting 1")
        void sagaVersionIsExtracted() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.CheckoutSaga",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Saga;
                    import eu.exeris.sdk.annotation.SagaStep;

                    @Saga(name = "CheckoutSaga", version = 3)
                    public class CheckoutSaga {

                        @SagaStep(order = 1, name = "reserve", service = "stock", command = "Reserve")
                        public void reserve() {}
                    }
                    """
            );

            String metadata = readContent(compileWithProcessor(source).generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/CheckoutSaga.json").orElseThrow());

            // SagaMetadata declares `int version` defaulting to 1, and nothing set it — so a
            // document that said version 1 sat next to a source that said 3. This corrects an
            // existing field rather than adding one.
            assertThat(metadata).contains("\"version\" : 3");
        }

        @Test
        @DisplayName("Should extract Saga with compensation")
        void shouldExtractSagaWithCompensation() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.PaymentSaga",
                    """
                    package com.example;
                    
                    import eu.exeris.sdk.annotation.Saga;
                    import eu.exeris.sdk.annotation.SagaStep;
                    
                    @Saga(name = "PaymentSaga")
                    public class PaymentSaga {
                        
                        @SagaStep(order = 1, name = "chargeCard", service = "payment-service", command = "ChargeCardCommand", compensation = "RefundCommand", parallel = false)
                        public void chargeStep() {}
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/PaymentSaga.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("PaymentSaga")
                    .contains("\"saga\" : true");
        }
    }

    @Nested
    @DisplayName("1.1.3 Relationship & Graph Edge Processing")
    class RelationshipAndGraphTests {

        @Test
        @DisplayName("Should extract @Relationship metadata")
        void shouldExtractRelationshipMetadata() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Invoice",
                    """
                    package com.example;
                    
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Relationship;
                    import eu.exeris.sdk.annotation.Field;
                    import java.util.List;
                    
                    @ExerisDomain(module = "billing", path = "/invoices")
                    public class Invoice {
                        
                        @Field(label = "Invoice Number", required = true)
                        private String invoiceNumber;
                        
                        @Field(label = "Customer")
                        @Relationship(targetEntity = Object.class, displayField = "name")
                        private Object customer;
                        
                        @Field(label = "Items")
                        @Relationship(targetEntity = Object.class, displayField = "name")
                        private List<Object> items;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/Invoice.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("\"entityName\" : \"Invoice\"")
                    .contains("\"relationships\"");
        }

        @Test
        @DisplayName("@Relationship.relationshipType reaches the metadata — a ONE_TO_MANY side "
                + "must not be recorded as MANY_TO_ONE")
        void shouldExtractRelationshipType() throws IOException {
            // Regression: the extraction read an attribute named `type`, which the SDK
            // annotation does not have (it declares `relationshipType`). Every relationship
            // therefore kept the builder default MANY_TO_ONE, and since the Flyway, repository,
            // service and FK-constraint emitters all gate on MANY_TO_ONE, the collection side of
            // a relationship was getting an FK column, an index, a FOREIGN KEY constraint and a
            // findBy…Id finder that belong to the owning side.
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Relationship;
                    import eu.exeris.sdk.annotation.Relationship.RelationshipType;
                    import java.util.List;
                    import java.util.UUID;

                    @ExerisDomain(module = "sales", path = "/orders")
                    public class Order {

                        @Relationship(targetEntity = Object.class, displayField = "name",
                                relationshipType = RelationshipType.ONE_TO_MANY)
                        private List<Object> items;

                        // No relationshipType written: the annotation default (MANY_TO_ONE) and
                        // the AST default agree, so the owning side still resolves correctly.
                        @Relationship(targetEntity = Object.class, displayField = "name")
                        private UUID customerId;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Order.json").orElseThrow());
            assertThat(metadata)
                    .contains("\"name\" : \"items\"")
                    .contains("\"type\" : \"ONE_TO_MANY\"")
                    .contains("\"name\" : \"customerId\"")
                    .contains("\"type\" : \"MANY_TO_ONE\"");
        }

        @Test
        @DisplayName("@Relationship cascade flags map onto the AST CascadeType the FK emitter reads")
        void shouldExtractCascadeFlags() throws IOException {
            // deletePolicy() reads ALL/REMOVE as ON DELETE CASCADE; neither flag was extracted
            // before, so every generated FOREIGN KEY was RESTRICT regardless of the annotation.
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Relationship;
                    import java.util.UUID;

                    @ExerisDomain(module = "sales", path = "/orders")
                    public class Order {

                        @Relationship(targetEntity = Object.class, displayField = "name",
                                cascadeDelete = true)
                        private UUID customerId;

                        @Relationship(targetEntity = Object.class, displayField = "name",
                                cascadeDelete = true, cascadeUpdate = true)
                        private UUID warehouseId;

                        @Relationship(targetEntity = Object.class, displayField = "name",
                                cascadeUpdate = true)
                        private UUID carrierId;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Order.json").orElseThrow());
            assertThat(metadata)
                    .contains("\"cascade\" : \"REMOVE\"")
                    .contains("\"cascade\" : \"ALL\"")
                    // cascadeUpdate alone must NOT become a delete cascade — MERGE is outside
                    // deletePolicy()'s CASCADE set, so that FK stays RESTRICT.
                    .contains("\"cascade\" : \"MERGE\"");
        }

        @Test
        @DisplayName("T4: @Relationship.targetEntity wins over the field's Java type")
        void shouldHonourExplicitTargetEntityOverFieldType() throws IOException {
            JavaFileObject customer = JavaFileObjects.forSourceString(
                    "com.example.Customer",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "billing", path = "/customers")
                    public class Customer {
                        @Field(label = "Name")
                        private String name;
                    }
                    """
            );
            JavaFileObject order = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Relationship;
                    import java.util.UUID;

                    @ExerisDomain(module = "sales", path = "/orders")
                    public class Order {

                        // Explicit-UUID-FK style: the field type is UUID, but the
                        // relationship targets Customer — targetEntity must win.
                        @Relationship(targetEntity = Customer.class, displayField = "name")
                        private UUID customerId;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(customer, order);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/Order.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            // targetEntity resolves to the explicit Customer.class, not the UUID field
            // type. (The customerId field itself is legitimately UUID-typed — we assert
            // only that the relationship *target* is not derived from it.)
            assertThat(metadata)
                    .contains("\"targetEntity\" : \"Customer\"")
                    .doesNotContain("\"targetEntity\" : \"UUID\"")
                    .doesNotContain("\"targetEntity\" : \"java.util.UUID\"");
        }

        @Test
        @DisplayName("Should extract @Graph and @GraphEdge metadata")
        void shouldExtractGraphEdgeMetadata() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Person",
                    """
                    package com.example;
                    
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import eu.exeris.sdk.annotation.Graph;
                    import eu.exeris.sdk.annotation.GraphEdge;
                    
                    @ExerisDomain(module = "social", path = "/persons")
                    @Graph(nodeClass = "Person", syncToGraph = true)
                    public class Person {
                        
                        @Field(label = "Name", required = true)
                        private String name;
                        
                        @GraphEdge(type = "KNOWS", target = Person.class)
                        private Object friends;
                        
                        @GraphEdge(type = "WORKS_AT", target = Object.class)
                        private Object employer;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/Person.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("\"entityName\" : \"Person\"")
                    .contains("\"graphMetadata\"");
        }
    }

    @Nested
    @DisplayName("1.1.4 Domain Entity Configuration")
    class DomainEntityConfigTests {

        @Test
        @DisplayName("Should extract @ExerisDomain attributes")
        void shouldExtractDomainAttributes() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Product",
                    """
                    package com.example;
                    
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    
                    @ExerisDomain(
                        module = "inventory",
                        path = "/products",
                        description = "Product entity",
                        softDelete = true,
                        audited = true
                    )
                    public class Product {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/Product.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("\"entityName\" : \"Product\"")
                    .contains("\"softDelete\" : true")
                    .contains("\"audited\" : true");
        }

        @Test
        @DisplayName("Should extract EventSourced configuration")
        void shouldExtractEventSourcedConfig() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Account",
                    """
                    package com.example;
                    
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.EventSourced;
                    
                    @ExerisDomain(module = "banking", path = "/accounts")
                    @EventSourced(streamPrefix = "Account", snapshotThreshold = 50)
                    public class Account {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/Account.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("\"entityName\" : \"Account\"")
                    .contains("\"eventSourced\"")
                    .contains("\"enabled\" : true");
        }

        @Test
        @DisplayName("Should extract InternalApi configuration")
        void shouldExtractInternalApiConfig() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.AuditLog",
                    """
                    package com.example;
                    
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.InternalApi;
                    
                    @ExerisDomain(module = "system", path = "/audit-logs")
                    @InternalApi(consumers = {"admin-service"}, rateLimit = 100)
                    public class AuditLog {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/AuditLog.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("\"entityName\" : \"AuditLog\"")
                    .contains("\"internalApi\"");
        }
    }

    @Nested
    @DisplayName("1.1.5 Action Processing")
    class ActionProcessingTests {

        @Test
        @DisplayName("Should extract @Action with parameters")
        void shouldExtractActionWithParameters() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;
                    
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Action;
                    import eu.exeris.sdk.annotation.ActionParam;
                    
                    @ExerisDomain(module = "sales", path = "/orders")
                    public class Order {
                        
                        @Action(name = "cancel", label = "Cancel Order", path = "/{id}/cancel", roles = {"order:cancel"})
                        public void cancel(@ActionParam(label = "Reason", required = true) String reason) {}
                        
                        @Action(name = "ship", label = "Ship Order", path = "/{id}/ship", async = true)
                        public void ship(@ActionParam(label = "Tracking Number") String trackingNumber) {}
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/Order.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("\"entityName\" : \"Order\"")
                    .contains("\"actions\"")
                    .contains("\"module\" : \"sales\"");
        }

        @Test
        @DisplayName("T3: action identity is @Action(name=…), not the method name")
        void shouldUseActionNameAttributeOverMethodName() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Squad",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Action;

                    @ExerisDomain(module = "tactics", path = "/squads")
                    public class Squad {

                        // Bean-setter-shaped method: its simple name would collide with
                        // the generated setter — the action must be identified by `name`.
                        @Action(name = "assign-formation", label = "Assign Formation", path = "/{id}/assign-formation")
                        public void setFormation(String formation) {}
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/Squad.json"
            );
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            // The action IDENTITY is @Action(name), never the method name (T3); but the
            // JVM method name is recorded separately as methodName so the T1 server-side
            // dispatch can invoke the actual aggregate method.
            assertThat(metadata)
                    .contains("\"name\" : \"assign-formation\"")
                    .contains("\"methodName\" : \"setFormation\"");
        }
    }

    @Nested
    @DisplayName("1.1.7 ADR-059 data-scope tier (dataScope supersedes tenantScoped)")
    class DataScopeTests {

        @Test
        @DisplayName("dataScope alone — extracted, no fallback warning")
        void canonicalDataScopeNoWarning() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "catalog", path = "/items",
                            dataScope = ExerisDomain.DataScope.TENANT)
                    public class Item {
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Item.json").orElseThrow());
            assertThat(metadata).contains("\"dataScope\" : \"TENANT\"");
        }

        @Test
        @DisplayName("UNSPECIFIED is not a tier — absent from the JSON, same as unwritten")
        void unspecifiedIsAbsentOnTheWire() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "catalog", path = "/items",
                            dataScope = ExerisDomain.DataScope.UNSPECIFIED)
                    public class Item {
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            // UNSPECIFIED exists only because an annotation attribute cannot
            // default to null; the AST expresses it as an absent field and has
            // no such constant. Writing it must not materialise anything, or a
            // reader would round-trip a tier the author never declared.
            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Item.json").orElseThrow());
            assertThat(metadata).doesNotContain("dataScope");
        }

        @Test
        @DisplayName("tenantScoped alone — read as a fallback, with a deprecation warning")
        void deprecatedTenantScopedWarnsAndCarriesOver() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "catalog", path = "/items", tenantScoped = true)
                    public class Item {
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();
            assertThat(compilation)
                    .hadWarningContaining("@ExerisDomain.tenantScoped is deprecated for removal");
            assertThat(compilation)
                    .hadWarningContaining("tenantScoped = true → TENANT");

            // The boolean still reaches the AST — the fallback lives in
            // DomainMetadata.effectiveDataScope(), so a pre-0.10.0 baseline
            // reads back with the meaning it always had. No tier is invented.
            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Item.json").orElseThrow());
            assertThat(metadata).contains("\"tenantScoped\" : true");
            assertThat(metadata).doesNotContain("dataScope");
        }

        @Test
        @DisplayName("Agreeing pair — no warning, both flow through")
        void agreeingPairIsSilent() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "catalog", path = "/items",
                            dataScope = ExerisDomain.DataScope.TENANT, tenantScoped = true)
                    public class Item {
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();

            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Item.json").orElseThrow());
            assertThat(metadata)
                    .contains("\"tenantScoped\" : true")
                    .contains("\"dataScope\" : \"TENANT\"");
        }

        @Test
        @DisplayName("Contradicting pair — build error, not a silent resolution")
        void contradictingPairIsRejected() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "catalog", path = "/items",
                            dataScope = ExerisDomain.DataScope.GLOBAL, tenantScoped = true)
                    public class Item {
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).failed();
            assertThat(compilation)
                    .hadErrorContaining("contradict each other");
        }

        @ParameterizedTest(name = "tenantScoped = {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("UNIVERSE + any tenantScoped contradicts — the boolean cannot express the tier")
        void universeWithTenantScopedIsRejected(boolean tenantScoped) {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "catalog", path = "/items",
                            dataScope = ExerisDomain.DataScope.UNIVERSE, tenantScoped = %s)
                    public class Item {
                    }
                    """.formatted(tenantScoped)
            );

            // The fallback mapping is total (true → TENANT, false → GLOBAL), so
            // no boolean value agrees with UNIVERSE. Declaring both is always a
            // contradiction — which is the point of the enum: the third tier is
            // exactly what the boolean could not say. Both values are exercised
            // because "any" in the display name is a claim, not a shorthand.
            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("contradict each other");
            // The reserved-tier warning is suppressed on the error path — a
            // declaration that never reaches codegen should not also be told
            // what it would have emitted.
            assertThat(compilation).hadWarningCount(0);
        }

        @Test
        @DisplayName("T29: UNIVERSE alone is refused at the declaration, not half-emitted")
        void universeIsRefusedAtTheDeclaration() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "catalog", path = "/items",
                            dataScope = ExerisDomain.DataScope.UNIVERSE)
                    public class Item {
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);

            // Until 0.8.0 this was a WARNING and the build went on to emit the TENANT
            // shape. On the archetypal UNIVERSE entity — the one above, a shared-world
            // row with no tenant property — that shape binds entity.getTenantId() and
            // the consumer's build died with `cannot find symbol` inside a generated
            // file. The refusal lands on the declaration instead.
            assertThat(compilation).failed();
            assertThat(compilation)
                    .hadErrorContaining("DataScope.UNIVERSE is reserved");
            // The message has to be actionable on its own terms: what would have been
            // emitted, why it breaks, and the one thing the author can do today.
            assertThat(compilation).hadErrorContaining("getTenantId()");
            assertThat(compilation).hadErrorContaining("Declare dataScope = TENANT");
            assertThat(compilation)
                    .hadErrorContaining("cross-tenant read-widening from this build yet");
        }

        @Test
        @DisplayName("T29: a contradicted UNIVERSE reports the contradiction only — "
                + "not a second error about an emission the fixed declaration may never reach")
        void contradictedUniverseDoesNotAlsoReportTheReservedTier() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "catalog", path = "/items",
                            dataScope = ExerisDomain.DataScope.UNIVERSE, tenantScoped = true)
                    public class Item {
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);

            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining("tenantScoped");
            assertThat(compilation).hadErrorCount(1);
        }
    }

    @Nested
    @DisplayName("1.1.6 Deprecated @Validation read-and-warn (SDK 0.2.x → 1.0.0)")
    class DeprecatedValidationFallbackTests {

        @Test
        @DisplayName("@Field(required=true) alone — no warning, required=true")
        void canonicalFieldRequiredNoWarning() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "catalog", path = "/items")
                    public class Item {
                        @Field(label = "Name", required = true)
                        private String name;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Item.json").orElseThrow());
            assertThat(metadata).contains("\"required\" : true");
        }

        @Test
        @DisplayName("@Validation(required=true) only — read-and-warn, required carries over")
        void deprecatedValidationRequiredOnly() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import eu.exeris.sdk.annotation.Validation;

                    @ExerisDomain(module = "catalog", path = "/items")
                    public class Item {
                        @Field(label = "Name")
                        @Validation(required = true)
                        private String name;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();
            assertThat(compilation)
                    .hadWarningContaining("@Validation.required is deprecated")
                    .inFile(source)
                    .onLineContaining("private String name");
            assertProcessorWarningCount(compilation, 1);

            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Item.json").orElseThrow());
            assertThat(metadata).contains("\"required\" : true");
        }

        @Test
        @DisplayName("@Field(required=true) AND @Validation(required=true) — Field wins, still warns once")
        void bothRequiredFieldWinsStillWarns() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import eu.exeris.sdk.annotation.Validation;

                    @ExerisDomain(module = "catalog", path = "/items")
                    public class Item {
                        @Field(label = "Name", required = true)
                        @Validation(required = true)
                        private String name;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();
            assertThat(compilation).hadWarningContaining("@Validation.required is deprecated");
            assertProcessorWarningCount(compilation, 1);

            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Item.json").orElseThrow());
            assertThat(metadata).contains("\"required\" : true");
        }

        @Test
        @DisplayName("@Validation(validateOn=\"CREATE\") — maps to inUpdate=false, warns")
        void deprecatedValidateOnCreate() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import eu.exeris.sdk.annotation.Validation;

                    @ExerisDomain(module = "catalog", path = "/items")
                    public class Item {
                        @Field(label = "Created At")
                        @Validation(validateOn = "CREATE")
                        private String createdAt;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();
            assertThat(compilation)
                    .hadWarningContaining("@Validation.validateOn is deprecated")
                    .inFile(source)
                    .onLineContaining("private String createdAt");
            assertProcessorWarningCount(compilation, 1);

            JsonNode field = readFirstField(compilation, "Item");
            assertThat(field.path("inUpdate").asBoolean(false))
                    .as("validateOn=CREATE should force inUpdate=false")
                    .isFalse();
            assertThat(field.path("inCreate").asBoolean(false))
                    .as("inCreate left untouched at default true")
                    .isTrue();
        }

        @Test
        @DisplayName("@Validation(validateOn=\"UPDATE\") — maps to inCreate=false, warns")
        void deprecatedValidateOnUpdate() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import eu.exeris.sdk.annotation.Validation;

                    @ExerisDomain(module = "catalog", path = "/items")
                    public class Item {
                        @Field(label = "Modified At")
                        @Validation(validateOn = "UPDATE")
                        private String modifiedAt;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();
            assertProcessorWarningCount(compilation, 1);

            JsonNode field = readFirstField(compilation, "Item");
            assertThat(field.path("inCreate").asBoolean(false))
                    .as("validateOn=UPDATE should force inCreate=false")
                    .isFalse();
            assertThat(field.path("inUpdate").asBoolean(false))
                    .as("inUpdate left untouched at default true")
                    .isTrue();
        }

        @Test
        @DisplayName("@Field(inUpdate=false) AND @Validation(validateOn=\"CREATE\") — Field wins, still warns once")
        void bothValidateOnFieldWinsStillWarns() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import eu.exeris.sdk.annotation.Validation;

                    @ExerisDomain(module = "catalog", path = "/items")
                    public class Item {
                        @Field(label = "Created At", inUpdate = false)
                        @Validation(validateOn = "CREATE")
                        private String createdAt;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();
            assertThat(compilation).hadWarningContaining("@Validation.validateOn is deprecated");
            assertProcessorWarningCount(compilation, 1);

            JsonNode field = readFirstField(compilation, "Item");
            assertThat(field.path("inUpdate").asBoolean(false))
                    .as("@Field.inUpdate=false set explicitly — value preserved")
                    .isFalse();
        }

        @Test
        @DisplayName("@Validation(validateOn=\"FOO\") — unrecognized value, two warnings, no fallback")
        void unrecognizedValidateOnTwoWarnings() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import eu.exeris.sdk.annotation.Validation;

                    @ExerisDomain(module = "catalog", path = "/items")
                    public class Item {
                        @Field(label = "Mystery")
                        @Validation(validateOn = "MAYBE")
                        private String mystery;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();
            assertThat(compilation).hadWarningContaining("@Validation.validateOn is deprecated");
            assertThat(compilation).hadWarningContaining(
                    "@Validation.validateOn = \"MAYBE\" is not a recognized value");
            assertProcessorWarningCount(compilation, 2);

            JsonNode field = readFirstField(compilation, "Item");
            // Neither inCreate nor inUpdate touched — both stay at builder default true.
            assertThat(field.path("inCreate").asBoolean(false))
                    .as("inCreate untouched at builder default true")
                    .isTrue();
            assertThat(field.path("inUpdate").asBoolean(false))
                    .as("inUpdate untouched at builder default true")
                    .isTrue();
        }

        @Test
        @DisplayName("@Validation() with empty validateOn (default) — no warning, no behavior change")
        void emptyValidateOnNoWarning() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import eu.exeris.sdk.annotation.Validation;

                    @ExerisDomain(module = "catalog", path = "/items")
                    public class Item {
                        @Field(label = "Email")
                        @Validation(email = true, minLength = 3)
                        private String email;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();
        }

        /**
         * Counts only the warnings emitted by the processor itself (i.e. those
         * carrying our {@code [Exeris]} prefix). javac may emit additional
         * "removal" warnings at the user-side {@code @Validation(required=true)}
         * call site because the SDK declares those attributes
         * {@code @Deprecated(forRemoval=true)}; those are independent of this
         * test's contract and should not be counted.
         */
        private void assertProcessorWarningCount(Compilation compilation, int expected) {
            long count = compilation.warnings().stream()
                    .filter(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("[Exeris]"))
                    .count();
            assertThat(count)
                    .as("processor-emitted [Exeris] warnings")
                    .isEqualTo(expected);
        }

        /**
         * Parses the generated {@code exeris-metadata/<entity>.json} and returns
         * its first {@code fields[]} entry as a JsonNode. Using JsonNode rather
         * than asserting on raw string substrings makes the inCreate/inUpdate
         * checks robust against {@code @JsonInclude(NON_DEFAULT)} dropping
         * primitive-default values from the wire form.
         */
        private JsonNode readFirstField(Compilation compilation, String entity) throws IOException {
            JavaFileObject metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/" + entity + ".json")
                    .orElseThrow();
            JsonNode root = new ObjectMapper().readTree(readContent(metadataFile));
            return root.path("fields").get(0);
        }
    }

    @Nested
    @DisplayName("Processor minors — trigger suffix mapping")
    class TriggerSuffixTests {

        @Test
        @DisplayName("Each canonical Trigger maps to its expected event-name suffix")
        void canonicalTriggerSuffixMapping() throws IOException {
            // Repeatable @DomainEvent on one entity covers each branch of
            // triggerToEventSuffix. Names are derived from
            // <EntitySimpleName> + suffix. STATE_TRANSITION has no explicit
            // suffix mapping and must fall through to the generic "Event".
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.DomainEvent;
                    import eu.exeris.sdk.annotation.DomainEvent.Trigger;

                    @ExerisDomain(module = "sales", path = "/orders")
                    @DomainEvent(trigger = Trigger.CREATE,           topic = "orders.created")
                    @DomainEvent(trigger = Trigger.UPDATE,           topic = "orders.updated")
                    @DomainEvent(trigger = Trigger.DELETE,           topic = "orders.deleted")
                    @DomainEvent(trigger = Trigger.FIELD_CHANGED,    topic = "orders.changed", field  = "status")
                    @DomainEvent(trigger = Trigger.ACTION,           topic = "orders.action",  action = "cancel")
                    @DomainEvent(trigger = Trigger.STATE_TRANSITION, topic = "orders.state")
                    public class Order {
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();

            JsonNode events = readMetadataRoot(compilation, "Order").path("events");
            assertThat(events.isArray()).isTrue();

            // Collect derived event names (order does not matter — JSR 269 makes
            // no guarantee about repeatable-annotation iteration order).
            java.util.Set<String> names = new java.util.HashSet<>();
            for (JsonNode event : events) {
                names.add(event.path("name").asText());
            }
            assertThat(names).containsExactlyInAnyOrder(
                    "OrderCreatedEvent",
                    "OrderUpdatedEvent",
                    "OrderDeletedEvent",
                    "OrderChangedEvent",
                    "OrderActionEvent",
                    "OrderEvent"
            );
        }

        private JsonNode readMetadataRoot(Compilation compilation, String entity) throws IOException {
            JavaFileObject metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/" + entity + ".json")
                    .orElseThrow();
            return new ObjectMapper().readTree(readContent(metadataFile));
        }
    }

    @Nested
    @DisplayName("Processor minors — -Aexeris.verbose option")
    class VerboseOptionTests {

        @Test
        @DisplayName("Default build emits no [Exeris] NOTE messages (clean output)")
        void defaultBuildIsQuiet() {
            JavaFileObject source = simpleDomainSource();

            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            long exerisNotes = compilation.notes().stream()
                    .filter(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("[Exeris]"))
                    .count();
            assertThat(exerisNotes)
                    .as("[Exeris] NOTE messages with verbose unset")
                    .isZero();
        }

        @Test
        @DisplayName("-Aexeris.verbose=true emits per-entity [Exeris] NOTE messages")
        void verboseFlagEmitsNotes() {
            JavaFileObject source = simpleDomainSource();

            Compilation compilation = javac()
                    .withOptions("-Aexeris.verbose=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            long exerisNotes = compilation.notes().stream()
                    .filter(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("[Exeris]"))
                    .count();
            assertThat(exerisNotes)
                    .as("[Exeris] NOTE messages with verbose set")
                    .isPositive();
        }

        private JavaFileObject simpleDomainSource() {
            return JavaFileObjects.forSourceString(
                    "com.example.Widget",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "core", path = "/widgets")
                    public class Widget {
                        private String name;
                    }
                    """
            );
        }
    }

    @Nested
    @DisplayName("-Aexeris.strict unread-annotation audit (C0)")
    class StrictModeUnreadAnnotationTests {

        /**
         * C0's pass reports "never read", which is a different sentence from the inert pass's
         * "no code generator consumes it" — deliberately, so the two counts stay separable and a
         * test cannot mistake one pass's output for the other's.
         */
        private long unreadWarnings(Compilation compilation) {
            return compilation.warnings().stream()
                    .filter(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("this processor never reads it"))
                    .count();
        }

        private boolean hasUnreadWarningFor(Compilation compilation, String annotation) {
            return compilation.warnings().stream()
                    .anyMatch(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("this processor never reads it")
                            && d.getMessage(null).contains(annotation));
        }

        private long inertWarnings(Compilation compilation) {
            return compilation.warnings().stream()
                    .filter(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("no code generator consumes it"))
                    .count();
        }

        @Test
        @DisplayName("warns on @NavMenu — a type-level annotation no extraction ever touched")
        void strictWarnsOnUnreadTypeAnnotation() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Account",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.NavMenu;

                    @ExerisDomain(module = "core", path = "/accounts")
                    @NavMenu(label = "Accounts")
                    public class Account {
                        private String name;
                    }
                    """
            );

            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            assertThat(hasUnreadWarningFor(compilation, "@NavMenu"))
                    .as("warning naming @NavMenu")
                    .isTrue();
            // Before C0 this was unreportable by construction: no extraction means no call site,
            // and the audit was driven from call sites. Neither inert registry could hold it.
            assertThat(inertWarnings(compilation))
                    .as("the inert pass says nothing about it — the two passes are separate")
                    .isZero();
        }

        @Test
        @DisplayName("warns on @Derived at the field sweep, and says it is reserved rather than unbuilt")
        void strictWarnsOnUnreadFieldAnnotationWithItsReason() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Account",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Derived;
                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "core", path = "/accounts")
                    public class Account {
                        @Derived(expression = "first + last")
                        private String fullName;
                    }
                    """
            );

            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            assertThat(hasUnreadWarningFor(compilation, "@Derived")).isTrue();
            // "No effect on output" covers both a reserved surface and an unbuilt one, and an
            // author is owed the difference: @Derived is design-gated, not forgotten.
            assertThat(compilation.warnings().stream()
                    .anyMatch(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("@Derived")
                            && d.getMessage(null).contains("Reserved rather than overlooked")))
                    .as("the registered reason, not the generic sentence")
                    .isTrue();
        }

        @Test
        @DisplayName("warns on @QueryParam — the action-parameter site C0 added")
        void strictWarnsOnUnreadParameterAnnotation() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Account",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Action;
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.QueryParam;

                    @ExerisDomain(module = "core", path = "/accounts")
                    public class Account {
                        private String name;

                        @Action(name = "search", label = "Search")
                        public void search(@QueryParam("q") String query) {
                        }
                    }
                    """
            );

            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            // The parameter carries no @ActionParam, so the pre-existing sweep never visited it:
            // the audit call had to sit outside that gate, which is exactly the shape this pins.
            assertThat(hasUnreadWarningFor(compilation, "@QueryParam"))
                    .as("warning naming @QueryParam on an otherwise-unannotated parameter")
                    .isTrue();
        }

        /**
         * Both {@code @Repeatable} idioms the SDK uses, in one test because the assertion is the
         * same and only the idiom differs — which is exactly the asymmetry that produced the bug.
         *
         * <p>{@code @SagaStep} takes the plain-plural container ({@code @SagaSteps});
         * {@code @DomainEvent} takes the nested one ({@code @DomainEvent.DomainEvents}), whose
         * last dot-segment is {@code "DomainEvents"}. The first implementation keyed a registry on
         * simple names, covered the first idiom and missed the second, so an entity declaring two
         * events — ordinary, encouraged, and exercised six-deep elsewhere in this file — was told
         * that an annotation the processor fully extracts "is never read". Caught in review of the
         * C0 PR; the fix detects containers structurally (JLS 9.6.3) rather than by name.
         */
        static Stream<Arguments> repeatableIdioms() {
            return Stream.of(
                    Arguments.of("plain-plural container (@SagaStep → @SagaSteps)",
                            """
                            package com.example;

                            import eu.exeris.sdk.annotation.Saga;
                            import eu.exeris.sdk.annotation.SagaStep;

                            @Saga(name = "Checkout")
                            public class Subject {

                                @SagaStep(order = 1, name = "reserve", service = "stock", command = "reserve")
                                public void reserve() {
                                }

                                @SagaStep(order = 2, name = "charge", service = "billing", command = "charge")
                                public void charge() {
                                }
                            }
                            """),
                    Arguments.of("nested container (@DomainEvent → @DomainEvent.DomainEvents)",
                            """
                            package com.example;

                            import eu.exeris.sdk.annotation.DomainEvent;
                            import eu.exeris.sdk.annotation.DomainEvent.Trigger;
                            import eu.exeris.sdk.annotation.ExerisDomain;

                            @ExerisDomain(module = "core", path = "/orders")
                            @DomainEvent(trigger = Trigger.CREATE, topic = "orders.created")
                            @DomainEvent(trigger = Trigger.DELETE, topic = "orders.deleted")
                            public class Subject {
                                private String reference;
                            }
                            """));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("repeatableIdioms")
        @DisplayName("a synthesised @Repeatable container is not reported — the member is what the author wrote")
        void strictDoesNotWarnOnSynthesisedContainer(String idiom, String sourceText) {
            JavaFileObject source = JavaFileObjects.forSourceString("com.example.Subject", sourceText);

            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            assertThat(unreadWarnings(compilation))
                    .as("no warning about a container the author never wrote (%s)", idiom)
                    .isZero();
        }

        @Test
        @DisplayName("default build stays quiet — the audit is opt-in like the rest of strict mode")
        void defaultBuildDoesNotWarnOnUnreadAnnotations() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Account",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.NavMenu;

                    @ExerisDomain(module = "core", path = "/accounts")
                    @NavMenu(label = "Accounts")
                    public class Account {
                        private String name;
                    }
                    """
            );

            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            assertThat(unreadWarnings(compilation)).isZero();
        }

        @Test
        @DisplayName("an entity using only extracted annotations draws no unread warning")
        void strictIsQuietForAFullyReadEntity() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Account",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Action;
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "core", path = "/accounts")
                    public class Account {
                        @Field(label = "Name")
                        private String name;

                        @Action(name = "close", label = "Close")
                        public void close() {
                        }
                    }
                    """
            );

            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            assertThat(unreadWarnings(compilation))
                    .as("no false positive on the annotations the pipeline does read")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("Processor minors — -Aexeris.strict inert-attribute audit (T11)")
    class StrictModeInertAttributeTests {

        private long inertWarnings(Compilation compilation) {
            return compilation.warnings().stream()
                    .filter(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("no code generator consumes it"))
                    .count();
        }

        private boolean hasInertWarningFor(Compilation compilation, String attribute) {
            return compilation.warnings().stream()
                    .anyMatch(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("no code generator consumes it")
                            && d.getMessage(null).contains(attribute));
        }

        @Test
        @DisplayName("Default build stays quiet even when an inert attribute is set")
        void defaultBuildDoesNotWarnOnInertAttribute() {
            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(actionParamWithInertAttributes());

            assertThat(compilation).succeeded();
            assertThat(inertWarnings(compilation))
                    .as("inert-attribute warnings with strict unset")
                    .isZero();
        }

        @Test
        @DisplayName("-Aexeris.strict no longer flags @Field.dataType (it is consumed now — Wave 1A)")
        void strictDoesNotWarnOnConsumedFieldDataType() {
            // Wave 1A wired @Field.dataType end-to-end (processor extract + reader
            // parity + emitters), so its INERT_ATTRIBUTES entry was removed; the
            // strict audit must NOT flag it as inert any more.
            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(fieldWithConsumedDataType());

            assertThat(compilation).succeeded();
            assertThat(hasInertWarningFor(compilation, "@Field.dataType"))
                    .as("no inert warning naming @Field.dataType (now consumed)")
                    .isFalse();
            assertThat(inertWarnings(compilation))
                    .as("no inert warnings at all (dataType is the only attribute set)")
                    .isZero();
        }

        @Test
        @DisplayName("-Aexeris.strict warns on @ActionParam.description and .required")
        void strictWarnsOnInertActionParamAttributes() {
            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(actionParamWithInertAttributes());

            assertThat(compilation).succeeded();
            assertThat(hasInertWarningFor(compilation, "@ActionParam.description")).isTrue();
            assertThat(hasInertWarningFor(compilation, "@ActionParam.required")).isTrue();
            assertThat(inertWarnings(compilation))
                    .as("exactly two inert warnings (description + required)")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("-Aexeris.strict warns on the four access attributes nothing extracts (T51)")
        void strictWarnsOnInertAccessAttributes() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Action;

                    @ExerisDomain(module = "core", path = "/orders",
                            roles = {"ROLE_MANAGER"}, permissions = {"order:read"})
                    public class Order {
                        @Action(name = "approve", label = "Approve",
                                roles = {"ROLE_MANAGER"}, permissions = {"order:approve"})
                        public void approve() {
                        }
                    }
                    """
            );

            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            // An author who writes a permission today gets no enforcement anywhere and, until this
            // entry existed, no warning either: the processor never extracts these, so the fields
            // that carry them are empty in every build.
            assertThat(hasInertWarningFor(compilation, "@ExerisDomain.roles")).isTrue();
            assertThat(hasInertWarningFor(compilation, "@ExerisDomain.permissions")).isTrue();
            assertThat(hasInertWarningFor(compilation, "@Action.roles")).isTrue();
            assertThat(hasInertWarningFor(compilation, "@Action.permissions")).isTrue();
            assertThat(inertWarnings(compilation))
                    .as("exactly four inert warnings, one per access attribute set")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("-Aexeris.strict warns on @ActionParam.required even when set to false")
        void strictWarnsOnActionParamRequiredFalse() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Action;
                    import eu.exeris.sdk.annotation.ActionParam;

                    @ExerisDomain(module = "core", path = "/orders")
                    public class Order {
                        @Action(name = "approve", label = "Approve")
                        public void approve(
                                @ActionParam(label = "Reason", required = false) String reason) {
                        }
                    }
                    """
            );

            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            // A deliberate opt-out (required = false) is still inert — the explicit
            // write is what matters, not the value. Only `required` is set here.
            assertThat(hasInertWarningFor(compilation, "@ActionParam.required")).isTrue();
            assertThat(inertWarnings(compilation))
                    .as("exactly one inert warning (only required is set)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("-Aexeris.strict warns on @EventSourced (no generator consumes it yet)")
        void strictWarnsOnInertEventSourcedAnnotation() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Account",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.EventSourced;

                    @ExerisDomain(module = "core", path = "/accounts")
                    @EventSourced
                    public class Account {
                        private String name;
                    }
                    """
            );

            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            assertThat(hasInertWarningFor(compilation, "@EventSourced"))
                    .as("warning naming @EventSourced")
                    .isTrue();
            assertThat(inertWarnings(compilation))
                    .as("exactly one inert warning (reported once per entity, not per attribute)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("Default build stays quiet even when @EventSourced is set")
        void defaultBuildDoesNotWarnOnEventSourced() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Account",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.EventSourced;

                    @ExerisDomain(module = "core", path = "/accounts")
                    @EventSourced
                    public class Account {
                        private String name;
                    }
                    """
            );

            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            assertThat(inertWarnings(compilation))
                    .as("inert warnings with strict unset")
                    .isZero();
        }

        @Test
        @DisplayName("-Aexeris.strict is quiet when no inert attribute is set")
        void strictIsQuietWithoutInertAttributes() {
            JavaFileObject clean = JavaFileObjects.forSourceString(
                    "com.example.Widget",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "core", path = "/widgets")
                    public class Widget {
                        @Field(label = "Name")
                        private String name;
                    }
                    """
            );

            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(clean);

            assertThat(compilation).succeeded();
            assertThat(inertWarnings(compilation))
                    .as("inert-attribute warnings when nothing inert is set")
                    .isZero();
        }

        private JavaFileObject fieldWithConsumedDataType() {
            return JavaFileObjects.forSourceString(
                    "com.example.Widget",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "core", path = "/widgets")
                    public class Widget {
                        @Field(label = "Name", dataType = "text")
                        private String name;
                    }
                    """
            );
        }

        @Test
        @DisplayName("-Aexeris.strict reaches every registered inert attribute (the audit is driven by call sites)")
        void strictReachesEveryRegisteredInertAttribute() {
            // The registry is a list; the warnings come from warnInertAttributes call
            // sites. An entry whose annotation has no call site is unreachable and reads
            // as coverage while producing nothing — which is what @Action.path and
            // @ExerisDomain.apiVersion did. This fixture sets all four registered
            // attributes at once, so a future entry added without its call site fails
            // here rather than going quiet.
            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(everyInertAttributeSet());

            assertThat(compilation).succeeded();
            assertThat(hasInertWarningFor(compilation, "@Action.path")).isTrue();
            assertThat(hasInertWarningFor(compilation, "@ExerisDomain.apiVersion")).isTrue();
            assertThat(hasInertWarningFor(compilation, "@ActionParam.description")).isTrue();
            assertThat(hasInertWarningFor(compilation, "@ActionParam.required")).isTrue();
            assertThat(inertWarnings(compilation))
                    .as("one warning per registered inert attribute, no more")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("-Aexeris.strict is quiet on an @Action that omits path (SDK 0.11.0 gave it a default)")
        void strictIsQuietOnActionWithoutPath() {
            // Before SDK 0.11.0 `path` had no default, so every @Action carried it and
            // a working audit would have warned on all of them. Omitting it is legal
            // now, and the audit reports only what an author actually wrote.
            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(actionWithoutPath());

            assertThat(compilation).succeeded();
            assertThat(inertWarnings(compilation))
                    .as("inert warnings when the action sets nothing inert")
                    .isZero();
        }

        @Test
        @DisplayName("Default build stays quiet on @Action.path and @ExerisDomain.apiVersion too")
        void defaultBuildDoesNotWarnOnTheNewlyReachedEntries() {
            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(everyInertAttributeSet());

            assertThat(compilation).succeeded();
            assertThat(inertWarnings(compilation))
                    .as("inert-attribute warnings with strict unset")
                    .isZero();
        }

        @Test
        @DisplayName("-Aexeris.strict reaches every registered inert annotation, at all three targets (D6)")
        void strictReachesEveryRegisteredInertAnnotation() {
            // Same gate as the attribute one, for the other registry. INERT_ANNOTATIONS
            // is driven by warnInertAnnotations call sites, and until D6 there were only
            // type-level ones — so a FIELD- or METHOD-targeted entry would have been dead
            // on arrival. This fixture places all three registered annotations at their
            // declared targets in one compilation unit.
            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(everyInertAnnotationSet());

            assertThat(compilation).succeeded();
            assertThat(hasInertWarningFor(compilation, "@EventSourced")).isTrue();
            assertThat(hasInertWarningFor(compilation, "@Blob")).isTrue();
            assertThat(hasInertWarningFor(compilation, "@Schedule")).isTrue();
            assertThat(inertWarnings(compilation))
                    .as("one warning per registered inert annotation, no more")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("-Aexeris.strict warns on @Blob on a field that carries no @Field")
        void strictWarnsOnBlobWithoutField() {
            // The sweep must not sit inside extractFieldMetadata, which is reached only
            // when @Field is present. A @Blob field with no @Field is a real shape — the
            // annotation describes a byte carrier, not a column — and the field is still
            // admitted to the AST through FieldMetadata.simple(...).
            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(blobOnAFieldWithoutFieldAnnotation());

            assertThat(compilation).succeeded();
            assertThat(hasInertWarningFor(compilation, "@Blob")).isTrue();
            assertThat(inertWarnings(compilation)).isEqualTo(1);
        }

        @Test
        @DisplayName("-Aexeris.strict warns on @Schedule on a method that carries no @Action")
        void strictWarnsOnScheduleWithoutAction() {
            Compilation compilation = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(scheduleOnAMethodWithoutAction());

            assertThat(compilation).succeeded();
            assertThat(hasInertWarningFor(compilation, "@Schedule")).isTrue();
            assertThat(inertWarnings(compilation)).isEqualTo(1);
        }

        @Test
        @DisplayName("Default build stays quiet on @Blob and @Schedule too")
        void defaultBuildDoesNotWarnOnTheNewAnnotationEntries() {
            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(everyInertAnnotationSet());

            assertThat(compilation).succeeded();
            assertThat(inertWarnings(compilation))
                    .as("inert warnings with strict unset")
                    .isZero();
        }

        private JavaFileObject everyInertAnnotationSet() {
            return JavaFileObjects.forSourceString(
                    "com.example.Document",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Action;
                    import eu.exeris.sdk.annotation.Blob;
                    import eu.exeris.sdk.annotation.EventSourced;
                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Schedule;

                    @ExerisDomain(module = "core", path = "/documents")
                    @EventSourced
                    public class Document {
                        @Blob
                        private byte[] content;

                        @Schedule(cron = "0 0 * * *")
                        @Action(name = "archive", label = "Archive")
                        public void archive() {
                        }
                    }
                    """
            );
        }

        private JavaFileObject blobOnAFieldWithoutFieldAnnotation() {
            return JavaFileObjects.forSourceString(
                    "com.example.Document",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Blob;
                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "core", path = "/documents")
                    public class Document {
                        @Blob
                        private byte[] content;
                    }
                    """
            );
        }

        private JavaFileObject scheduleOnAMethodWithoutAction() {
            return JavaFileObjects.forSourceString(
                    "com.example.Document",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Schedule;

                    @ExerisDomain(module = "core", path = "/documents")
                    public class Document {
                        @Schedule(every = "PT1H")
                        public void sweep() {
                        }
                    }
                    """
            );
        }

        private JavaFileObject everyInertAttributeSet() {
            return JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Action;
                    import eu.exeris.sdk.annotation.ActionParam;

                    @ExerisDomain(module = "core", path = "/orders", apiVersion = "v1")
                    public class Order {
                        @Action(name = "approve", label = "Approve", path = "/{id}/approve")
                        public void approve(
                                @ActionParam(label = "Reason",
                                        description = "Why this order is approved",
                                        required = true) String reason) {
                        }
                    }
                    """
            );
        }

        private JavaFileObject actionWithoutPath() {
            return JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Action;

                    @ExerisDomain(module = "core", path = "/orders")
                    public class Order {
                        @Action(name = "approve", label = "Approve")
                        public void approve() {
                        }
                    }
                    """
            );
        }

        private JavaFileObject actionParamWithInertAttributes() {
            return JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Action;
                    import eu.exeris.sdk.annotation.ActionParam;

                    @ExerisDomain(module = "core", path = "/orders")
                    public class Order {
                        @Action(name = "approve", label = "Approve")
                        public void approve(
                                @ActionParam(label = "Reason",
                                        description = "Why this order is approved",
                                        required = true) String reason) {
                        }
                    }
                    """
            );
        }
    }

    @Nested
    @DisplayName("ADR-042 baseline trust (T16) — sourceDigest + schemaVersion siblings")
    class BaselineTrustTests {

        private final ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        @Test
        @DisplayName("Domain JSON stamps sourceDigest + current schemaVersion as siblings of DomainMetadata")
        void stampsBaselineTrustSiblings() throws IOException {
            String source = """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "sales", path = "/orders")
                    public class Order {
                        @Field(label = "Number")
                        private String number;
                    }
                    """;

            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(JavaFileObjects.forSourceString("com.example.Order", source));

            assertThat(compilation).succeeded();
            Optional<JavaFileObject> file = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Order.json");
            assertThat(file).isPresent();
            String json = readContent(file.get());

            // Two reads of one file: BaselineTrust picks up just the trust fields…
            BaselineTrust trust = mapper.readValue(json, BaselineTrust.class);
            assertThat(trust.schemaVersion()).isEqualTo(SchemaVersion.CURRENT);
            assertThat(trust.sourceDigest()).isNotBlank();
            // …and equals SourceDigest.of over the SAME source text the -io reader will recompute
            assertThat(trust.sourceDigest()).isEqualTo(SourceDigest.of(source));
            // checkBaselineTrust's gate is exactly this — schema is current ⇒ trusted
            assertThat(SchemaVersion.isCurrent(trust.schemaVersion())).isTrue();

            // …while a DomainMetadata read of the SAME file ignores the trust fields
            DomainMetadata dm = mapper.readValue(json, DomainMetadata.class);
            assertThat(dm.entityName()).isEqualTo("Order");
            assertThat(dm.module()).isEqualTo("sales");
        }

        @Test
        @DisplayName("Degraded path (no source text) stamps schemaVersion only, omits sourceDigest")
        void degradedPathOmitsDigest() throws IOException {
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .module("sales").path("/orders").build();

            // source == null simulates an environment without the javac Tree API
            var node = ExerisDomainProcessor.buildMetadataNode(mapper, metadata, null);
            String json = mapper.writeValueAsString(node);

            // sourceDigest is omitted (not an explicit JSON null), schemaVersion still stamped
            assertThat(node.has("sourceDigest")).isFalse();
            BaselineTrust trust = mapper.readValue(json, BaselineTrust.class);
            assertThat(trust.sourceDigest()).isNull();
            assertThat(trust.schemaVersion()).isEqualTo(SchemaVersion.CURRENT);
            // still a valid DomainMetadata baseline
            assertThat(mapper.readValue(json, DomainMetadata.class).entityName()).isEqualTo("Order");
        }

        @Test
        @DisplayName("A standalone @Saga entity is also stamped with baseline-trust fields")
        void sagaEntityAlsoStamped() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.OrderSaga",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Saga;
                    import eu.exeris.sdk.annotation.SagaStep;

                    @Saga(name = "OrderSaga", timeout = "PT30M", maxRetries = 3)
                    public class OrderSaga {
                        @SagaStep(order = 1, name = "validate", service = "order-service",
                                command = "ValidateCommand", compensation = "CancelCommand")
                        public void step1() {}
                    }
                    """
            );

            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            Optional<JavaFileObject> file = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/OrderSaga.json");
            assertThat(file).isPresent();
            String json = readContent(file.get());

            BaselineTrust trust = mapper.readValue(json, BaselineTrust.class);
            assertThat(trust.schemaVersion()).isEqualTo(SchemaVersion.CURRENT);
            assertThat(trust.sourceDigest()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Capability modules (PR-E) — capability_*.json extraction")
    class CapabilityModuleTests {

        @Test
        @DisplayName("@CapabilityModule with single @Provides/@Requires emits capability JSON")
        void shouldExtractSingleProvidesAndRequires() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.BillingModule",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.capability.CapabilityModule;
                    import eu.exeris.sdk.annotation.capability.CapabilityLifecycle;
                    import eu.exeris.sdk.annotation.capability.Provides;
                    import eu.exeris.sdk.annotation.capability.Requires;

                    interface PaymentApi {}
                    interface LedgerApi {}

                    @CapabilityModule
                    @CapabilityLifecycle
                    @Provides(service = PaymentApi.class, version = "1.2.0")
                    @Requires(service = LedgerApi.class, versionRange = "[1.0,2.0)")
                    public class BillingModule {}
                    """
            );

            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            Optional<JavaFileObject> file = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/capability_BillingModule.json");
            assertThat(file).isPresent();

            String json = readContent(file.get());
            assertThat(json)
                    .contains("\"name\" : \"BillingModule\"")
                    .contains("\"qualifiedName\" : \"com.example.BillingModule\"")
                    .contains("com.example.PaymentApi")
                    .contains("\"version\" : \"1.2.0\"")
                    .contains("com.example.LedgerApi")
                    .contains("[1.0,2.0)")
                    // @CapabilityLifecycle present on the module -> it owns its lifecycle
                    .contains("\"lifecycleOwner\" : \"com.example.BillingModule\"");
        }

        @Test
        @DisplayName("repeatable @Provides (container form) are all captured")
        void shouldExtractRepeatableProvides() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.MultiModule",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.capability.CapabilityModule;
                    import eu.exeris.sdk.annotation.capability.Provides;

                    interface AlphaApi {}
                    interface BetaApi {}

                    @CapabilityModule
                    @Provides(service = AlphaApi.class, version = "1.0")
                    @Provides(service = BetaApi.class)
                    public class MultiModule {}
                    """
            );

            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(source);

            assertThat(compilation).succeeded();
            Optional<JavaFileObject> file = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/capability_MultiModule.json");
            assertThat(file).isPresent();

            String json = readContent(file.get());
            assertThat(json)
                    .contains("com.example.AlphaApi")
                    .contains("com.example.BetaApi");
            // BetaApi has no version -> NON_NULL omits the version field (no "version" : "")
            assertThat(json).doesNotContain("\"version\" : \"\"");
            // no @CapabilityLifecycle -> lifecycleOwner omitted (NON_NULL)
            assertThat(json).doesNotContain("lifecycleOwner");
        }
    }

    @Nested
    @DisplayName("Presentation views (RFC-2026-06-28) — view_*.json extraction")
    class ViewExtractionTests {

        // ObjectMapper that tolerates the ViewJson wrapper's name/packageName/
        // qualifiedName siblings when reading the nested ViewMetadata back out, and
        // the @JsonInclude(NON_NULL) absence of unset fields.
        private final ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        /**
         * The first-slice fixture (RFC-2026-06-28 §1): a PAGE @View with two
         * regions. The {@code body} region's block class carries two @Block+@Bind
         * members, one of which is a nested block class (recursion → children).
         * Bindings exercise both STATIC and ENTITY sources.
         *
         * <pre>
         * ProductLanding (@View PAGE /products)
         *   ├─ header → HeaderRegion
         *   │     └─ @Block(HERO)  @Bind(STATIC)         "banner"
         *   └─ body   → BodyRegion
         *         ├─ @Block(GRID)  → ProductCard (recurse)
         *         │     └─ @Block(CARD) @Bind(ENTITY ref=Product path=name)
         *         └─ @Block(LIST)  @Bind(ENTITY ref=Product path=tags)  "related"
         * </pre>
         */
        private JavaFileObject productLandingFixture() {
            return JavaFileObjects.forSourceString(
                    "com.example.view.ProductLanding",
                    """
                    package com.example.view;

                    import eu.exeris.sdk.annotation.View;
                    import eu.exeris.sdk.annotation.Region;
                    import eu.exeris.sdk.annotation.Block;
                    import eu.exeris.sdk.annotation.Block.BlockType;
                    import eu.exeris.sdk.annotation.Bind;
                    import eu.exeris.sdk.annotation.Bind.Source;

                    @View(name = "ProductLanding", kind = View.Kind.PAGE,
                          route = "/products", title = "Products")
                    public final class ProductLanding {

                        // Region order is source declaration order: header, then body.
                        @Region(slot = "header")
                        HeaderRegion header;

                        // Blank slot → derived from the member name ("body").
                        @Region
                        BodyRegion body;

                        // A region class: its @Block members become the region's nodes.
                        static final class HeaderRegion {
                            @Block(type = BlockType.HERO)
                            @Bind(source = Source.STATIC)
                            String banner;
                        }

                        static final class BodyRegion {
                            // A @Block whose TYPE is itself block-shaped → children (recursion).
                            @Block(type = BlockType.GRID)
                            ProductCard grid;

                            // A @Block + @Bind(ENTITY) leaf-ish node (its String type
                            // has no @Block members, so no children).
                            @Block(type = BlockType.LIST)
                            @Bind(source = Source.ENTITY, ref = "Product", path = "tags")
                            String related;
                        }

                        static final class ProductCard {
                            @Block(type = BlockType.CARD)
                            @Bind(source = Source.ENTITY, ref = "Product", path = "name")
                            String title;
                        }
                    }
                    """
            );
        }

        @Test
        @DisplayName("@View PAGE emits view_*.json with the wrapper identity")
        void emitsViewJsonWithIdentity() throws IOException {
            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(productLandingFixture());

            assertThat(compilation).succeeded();
            Optional<JavaFileObject> file = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/view_ProductLanding.json");
            assertThat(file).isPresent();

            String json = readContent(file.get());
            assertThat(json)
                    .contains("\"name\" : \"ProductLanding\"")
                    .contains("\"packageName\" : \"com.example.view\"")
                    .contains("\"qualifiedName\" : \"com.example.view.ProductLanding\"");
        }

        @Test
        @DisplayName("The emitted JSON deserializes to the expected ViewMetadata tree")
        void deserializesToExpectedTree() throws IOException {
            Compilation compilation = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(productLandingFixture());

            assertThat(compilation).succeeded();
            String json = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/view_ProductLanding.json").orElseThrow());

            // Read the nested ViewMetadata out of the wrapper (the mapper ignores
            // the name/packageName/qualifiedName siblings).
            ViewMetadata view = mapper.readTree(json).get("view") != null
                    ? mapper.treeToValue(mapper.readTree(json).get("view"), ViewMetadata.class)
                    : mapper.readValue(json, ViewMetadata.class);

            // --- View root ---
            assertThat(view.name()).isEqualTo("ProductLanding");
            assertThat(view.effectiveKind()).isEqualTo(ViewKind.PAGE);
            assertThat(view.route()).isEqualTo("/products");
            assertThat(view.title()).isEqualTo("Products");

            // --- Regions in source declaration order: header, then body ---
            assertThat(view.regions()).hasSize(2);
            RegionMetadata header = view.regions().get(0);
            RegionMetadata body = view.regions().get(1);
            assertThat(header.slot()).isEqualTo("header");
            // Blank @Region.slot derives the slot from the member name.
            assertThat(body.slot()).isEqualTo("body");

            // --- header region: a single HERO block bound STATIC ---
            assertThat(header.components()).hasSize(1);
            ComponentNodeMetadata hero = header.components().get(0);
            assertThat(hero.effectiveType()).isEqualTo(BlockType.HERO);
            assertThat(hero.binding()).isNotNull();
            assertThat(hero.binding().effectiveSource()).isEqualTo(BindSource.STATIC);
            assertThat(hero.children()).isEmpty();

            // --- body region: a GRID (with children via recursion) then a LIST leaf ---
            assertThat(body.components()).hasSize(2);
            ComponentNodeMetadata grid = body.components().get(0);
            ComponentNodeMetadata related = body.components().get(1);

            assertThat(grid.effectiveType()).isEqualTo(BlockType.GRID);
            // The GRID's TYPE is ProductCard → its @Block member is a child node.
            assertThat(grid.children()).hasSize(1);
            ComponentNodeMetadata card = grid.children().get(0);
            assertThat(card.effectiveType()).isEqualTo(BlockType.CARD);
            BindingMetadata cardBinding = card.binding();
            assertThat(cardBinding).isNotNull();
            assertThat(cardBinding.effectiveSource()).isEqualTo(BindSource.ENTITY);
            assertThat(cardBinding.ref()).isEqualTo("Product");
            assertThat(cardBinding.path()).isEqualTo("name");

            assertThat(related.effectiveType()).isEqualTo(BlockType.LIST);
            assertThat(related.children()).isEmpty();
            BindingMetadata relatedBinding = related.binding();
            assertThat(relatedBinding).isNotNull();
            assertThat(relatedBinding.effectiveSource()).isEqualTo(BindSource.ENTITY);
            assertThat(relatedBinding.ref()).isEqualTo("Product");
            assertThat(relatedBinding.path()).isEqualTo("tags");
        }

        @Test
        @DisplayName("@View is consumed, so -Aexeris.strict emits NO inert warning for it (RFC-2026-06-28 §4)")
        void strictDoesNotFlagViewAsInert() {
            // The codegen-ts presentation-IR emitter (view-gen) now consumes
            // view_*.json, so @View is no longer inert (consumption = Java∪TS
            // union). Strict mode must therefore stay silent about @View — both
            // with strict unset (always quiet) and with strict enabled.
            Compilation quiet = javac()
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(productLandingFixture());
            assertThat(quiet).succeeded();
            long quietInert = quiet.warnings().stream()
                    .filter(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("no code generator consumes it"))
                    .count();
            assertThat(quietInert)
                    .as("inert warnings with strict unset")
                    .isZero();

            // Strict build: a @View-only compilation must produce ZERO inert
            // warnings naming @View (the INERT_ANNOTATIONS @View entry was removed
            // in lock-step with wiring the ViewGenerator).
            Compilation strict = javac()
                    .withOptions("-Aexeris.strict=true")
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(productLandingFixture());
            assertThat(strict).succeeded();
            long viewInert = strict.warnings().stream()
                    .filter(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("no code generator consumes it")
                            && d.getMessage(null).contains("@View"))
                    .count();
            assertThat(viewInert)
                    .as("strict mode must NOT name @View as inert — it is now consumed by the codegen-ts emitter")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("System-field overrides (T5)")
    class SystemFieldOverrideTests {

        @Test
        @DisplayName("Explicit overrides reach the JSON under systemFields")
        void shouldEmitSystemFieldsWhenOverridden() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "sales", path = "/orders",
                            updatedAtField = "modifiedAt", tenantIdField = "orgId")
                    public class Order {
                        private String name;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Order.json");
            assertThat(metadataFile).isPresent();

            String metadata = readContent(metadataFile.get());
            assertThat(metadata)
                    .contains("\"systemFields\"")
                    .contains("\"updatedAtField\" : \"modifiedAt\"")
                    .contains("\"tenantIdField\" : \"orgId\"")
                    // Unset components fall back to the canonical defaults so the
                    // record is internally complete.
                    .contains("\"createdAtField\" : \"createdAt\"")
                    .contains("\"versionField\" : \"version\"");
        }

        @Test
        @DisplayName("No overrides → systemFields is absent (determinism / default-case)")
        void shouldOmitSystemFieldsWhenNoOverride() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "sales", path = "/orders")
                    public class Order {
                        private String name;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Order.json").orElseThrow());
            assertThat(metadata).doesNotContain("\"systemFields\"");
        }

        @Test
        @DisplayName("primaryKeyField = \"id\" alone does not trigger systemFields (it is the annotation default)")
        void shouldNotTriggerOnDefaultPrimaryKey() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "sales", path = "/orders", primaryKeyField = "id")
                    public class Order {
                        private String name;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String metadata = readContent(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Order.json").orElseThrow());
            assertThat(metadata).doesNotContain("\"systemFields\"");
        }
    }

    // Helper methods

    @Nested
    @DisplayName("EV1 — @DomainEvent payload-field resolution")
    class EventPayloadResolutionTests {

        private static final String ORDER = """
                package com.example;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.DomainEvent.Trigger;
                import eu.exeris.sdk.annotation.Field;

                @ExerisDomain(module = "sales", path = "/orders")
                @DomainEvent(name = "OrderCreated", trigger = Trigger.CREATE, topic = "orders.created")
                @DomainEvent(name = "OrderPicked", trigger = Trigger.ACTION, action = "pick",
                        topic = "orders.picked",
                        includeFields = {"amount", "orderNumber"}, sensitiveFields = "customerEmail")
                @DomainEvent(name = "OrderRedacted", trigger = Trigger.UPDATE, topic = "orders.redacted",
                        excludeFields = {"customerEmail"})
                @DomainEvent(name = "OrderBoth", trigger = Trigger.DELETE, topic = "orders.both",
                        includeFields = {"amount", "orderNumber", "customerEmail"},
                        excludeFields = {"customerEmail"})
                public class Order {
                    @Field(label = "Order Number", required = true) private String orderNumber;
                    @Field(label = "Amount") private java.math.BigDecimal amount;
                    @Field(label = "Customer Email") private String customerEmail;
                    // A field WITHOUT @Field must NOT enter the default payload universe.
                    private String internalNote;

                    // Legacy inner-class event form — must resolve EV1 payload/sensitive
                    // fields against the enclosing entity, not silently drop them.
                    @DomainEvent(name = "OrderArchived", trigger = Trigger.DELETE, topic = "orders.archived",
                            includeFields = {"amount"}, sensitiveFields = "customerEmail")
                    public static class OrderArchived {}
                }
                """;

        private DomainMetadata read() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString("com.example.Order", ORDER);
            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();
            Optional<JavaFileObject> metadataFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT, "exeris-metadata/Order.json");
            assertThat(metadataFile).isPresent();
            return new ObjectMapper()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(readContent(metadataFile.get()), DomainMetadata.class);
        }

        @Test
        @DisplayName("EV2 (T48): the trigger triple reaches the AST, in both event forms")
        void extractsTheTriggerTriple() throws IOException {
            DomainMetadata dm = read();

            assertThat(event(dm, "OrderCreated").trigger())
                    .isEqualTo(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.Trigger.CREATE);
            assertThat(event(dm, "OrderRedacted").trigger())
                    .isEqualTo(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.Trigger.UPDATE);
            // The nested-class legacy form goes through a different extraction path and had to
            // be taught the triple separately.
            assertThat(event(dm, "OrderArchived").trigger())
                    .isEqualTo(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.Trigger.DELETE);

            assertThat(event(dm, "OrderPicked").trigger())
                    .isEqualTo(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.Trigger.ACTION);
            assertThat(event(dm, "OrderPicked").actionName()).isEqualTo("pick");
            // Unset @DomainEvent.action / .field are blank on the annotation and null in the
            // AST — "" would read as an action named the empty string when a generator matches.
            assertThat(event(dm, "OrderCreated").actionName()).isNull();
            assertThat(event(dm, "OrderCreated").fieldName()).isNull();
        }

        @Test
        @DisplayName("EV2 (T48): an explicit name no longer hides the trigger")
        void keepsTheTriggerWhenTheNameIsExplicit() throws IOException {
            // The defect the carrier exists to close: the processor read `trigger` only to
            // derive a name suffix, and only when no explicit name was given, so
            // @DomainEvent(name = "...", trigger = CREATE) left no trace of CREATE anywhere.
            // Every event in the fixture above declares an explicit name.
            DomainMetadata dm = read();

            assertThat(dm.events())
                    .allSatisfy(event -> assertThat(event.trigger()).isNotNull());
        }

        private eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata event(DomainMetadata dm, String name) {
            return dm.events().stream()
                    .filter(e -> name.equals(e.name()))
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        @DisplayName("No include/exclude → ALL @Field names in declaration order (non-@Field excluded)")
        void defaultPayloadIsAllFieldNames() throws IOException {
            DomainMetadata dm = read();
            // internalNote has no @Field, so it is NOT in the payload universe.
            assertThat(event(dm, "OrderCreated").payloadFields())
                    .containsExactly("orderNumber", "amount", "customerEmail");
            assertThat(event(dm, "OrderCreated").sensitiveFields()).isEmpty();
        }

        @Test
        @DisplayName("includeFields non-empty → exactly those names (written order); sensitiveFields verbatim")
        void includeFieldsRestrictsPayload() throws IOException {
            DomainMetadata dm = read();
            assertThat(event(dm, "OrderPicked").payloadFields())
                    .containsExactly("amount", "orderNumber");
            assertThat(event(dm, "OrderPicked").sensitiveFields())
                    .containsExactly("customerEmail");
        }

        @Test
        @DisplayName("excludeFields removes a name from the default (all-fields) payload")
        void excludeFieldsRemovedFromDefault() throws IOException {
            DomainMetadata dm = read();
            assertThat(event(dm, "OrderRedacted").payloadFields())
                    .containsExactly("orderNumber", "amount");
        }

        @Test
        @DisplayName("includeFields AND excludeFields → exclusion runs on the narrowed include set")
        void includeAndExcludeBothApplied() throws IOException {
            DomainMetadata dm = read();
            // base = include {amount, orderNumber, customerEmail} (written order),
            // then minus exclude {customerEmail} → [amount, orderNumber].
            assertThat(event(dm, "OrderBoth").payloadFields())
                    .containsExactly("amount", "orderNumber");
        }

        @Test
        @DisplayName("Legacy inner-class @DomainEvent resolves EV1 payload/sensitive fields (not dropped)")
        void nestedClassEventResolvesPayload() throws IOException {
            DomainMetadata dm = read();
            assertThat(event(dm, "OrderArchived").payloadFields())
                    .containsExactly("amount");
            assertThat(event(dm, "OrderArchived").sensitiveFields())
                    .containsExactly("customerEmail");
        }
    }

    private Compilation compileWithProcessor(JavaFileObject... sources) {
        return javac()
                .withProcessors(new ExerisDomainProcessor())
                .compile(sources);
    }

    private String readContent(JavaFileObject file) throws IOException {
        try (var inputStream = file.openInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
