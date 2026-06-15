package eu.exeris.tooling.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
            assertThat(metadata)
                    .contains("\"name\" : \"assign-formation\"")
                    .doesNotContain("setFormation");
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
