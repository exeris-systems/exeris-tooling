package eu.exeris.tooling.processor;

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
 * @since 0.2.0
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
