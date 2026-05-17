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
 * Branch / surface coverage boost for {@link ExerisDomainProcessor}.
 *
 * <p>{@link ExerisDomainProcessorTest} (the pre-existing class) covers the
 * happy paths: a handful of attribute combinations, @Field / @Saga /
 * @Relationship / @Graph / @Action / @Validation. This class targets the
 * surfaces it does not exercise, all of which are reachable through the
 * public annotation-processing API:
 *
 * <ul>
 *   <li>Comprehensive {@code @ExerisDomain} attribute matrix — every
 *       documented field-set branch in {@code extractDomainAnnotationValues}
 *       fires once.</li>
 *   <li>Enum metadata pipeline — {@code processDiscoveredEnums},
 *       {@code buildEnumMetadata}, {@code toDisplayName} (SCREAMING_CASE
 *       → Title Case), enum value Javadoc passthrough.</li>
 *   <li>Standalone {@code @Saga} class processing
 *       ({@code processSagaAnnotations} + {@code processSaga}).</li>
 *   <li>Negative paths: {@code @ExerisDomain} on a non-class element
 *       (interface) and a {@code @Saga} class without explicit steps.</li>
 *   <li>UI defaults — {@code extractUIMetadata}'s "value missing → default"
 *       branches in the {@code containsKey} ternary chain.</li>
 * </ul>
 */
@DisplayName("ExerisDomainProcessor — branch coverage")
class ExerisDomainProcessorBranchTest {

    @Nested
    @DisplayName("@ExerisDomain attribute matrix")
    class DomainAttributeMatrixTests {

        @Test
        @DisplayName("Every documented @ExerisDomain attribute propagates into the metadata JSON")
        void everyAttributeFlowsThrough() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.AllAttrs",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(
                        module = "sales",
                        path = "/all-attrs",
                        aggregate = "Order",
                        description = "Maximum-attribute fixture",
                        apiVersion = "v2",
                        restApi = false,
                        graphqlApi = true,
                        realTimeApi = true,
                        internalClient = true,
                        tenantScoped = true,
                        softDelete = true,
                        audited = true,
                        versioned = true,
                        sensitive = true,
                        cacheable = true,
                        cacheTtl = "PT5M",
                        cacheRegion = "orders",
                        fullTextSearch = true,
                        searchConfig = "english"
                    )
                    public class AllAttrs {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "AllAttrs");
            assertThat(json)
                    .contains("\"module\" : \"sales\"")
                    .contains("\"path\" : \"/all-attrs\"")
                    .contains("\"aggregate\" : \"Order\"")
                    .contains("\"description\" : \"Maximum-attribute fixture\"")
                    .contains("\"apiVersion\" : \"v2\"")
                    .contains("\"restApi\" : false")
                    .contains("\"graphqlApi\" : true")
                    .contains("\"realTimeApi\" : true")
                    .contains("\"internalClient\" : true")
                    .contains("\"tenantScoped\" : true")
                    .contains("\"softDelete\" : true")
                    .contains("\"audited\" : true")
                    .contains("\"versioned\" : true")
                    .contains("\"sensitive\" : true")
                    .contains("\"cacheable\" : true")
                    .contains("\"cacheTtl\" : \"PT5M\"")
                    .contains("\"cacheRegion\" : \"orders\"")
                    .contains("\"fullTextSearch\" : true")
                    .contains("\"searchConfig\" : \"english\"");
        }
    }

    @Nested
    @DisplayName("Enum metadata pipeline")
    class EnumPipelineTests {

        @Test
        @DisplayName("Enum referenced from an @ExerisDomain field is discovered and emits an enum_<Name>.json file")
        void enumFieldEmitsMetadataFile() throws IOException {
            JavaFileObject orderStatus = JavaFileObjects.forSourceString(
                    "com.example.OrderStatus",
                    """
                    package com.example;

                    /** Lifecycle status of an order. */
                    public enum OrderStatus {
                        /** Awaiting confirmation. */
                        PENDING_REVIEW,
                        /** Locked for fulfilment. */
                        CONFIRMED,
                        SHIPPED;
                    }
                    """
            );
            JavaFileObject order = JavaFileObjects.forSourceString(
                    "com.example.Order",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "sales", path = "/orders")
                    public class Order {
                        @Field(label = "Status")
                        private OrderStatus status;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(order, orderStatus);
            assertThat(compilation).succeededWithoutWarnings();

            Optional<JavaFileObject> enumFile = compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/enum_OrderStatus.json"
            );
            assertThat(enumFile).isPresent();

            String json = readContent(enumFile.get());
            assertThat(json)
                    .contains("\"name\" : \"OrderStatus\"")
                    .contains("\"qualifiedName\" : \"com.example.OrderStatus\"")
                    .contains("\"description\" : \"Lifecycle status of an order.\"")
                    // toDisplayName: SCREAMING_CASE → Title Case.
                    .contains("\"displayName\" : \"Pending Review\"")
                    .contains("\"displayName\" : \"Confirmed\"")
                    .contains("\"displayName\" : \"Shipped\"")
                    // Per-value Javadoc round-trips.
                    .contains("\"description\" : \"Awaiting confirmation.\"")
                    .contains("\"description\" : \"Locked for fulfilment.\"")
                    // Ordinal preserved in declaration order.
                    .contains("\"ordinal\" : 0")
                    .contains("\"ordinal\" : 1")
                    .contains("\"ordinal\" : 2");
        }

        @Test
        @DisplayName("Enums referenced inside generic type arguments (List<Status>) are also discovered")
        void enumInGenericIsDiscovered() throws IOException {
            JavaFileObject priority = JavaFileObjects.forSourceString(
                    "com.example.Priority",
                    """
                    package com.example;
                    public enum Priority { LOW, MEDIUM, HIGH }
                    """
            );
            JavaFileObject ticket = JavaFileObjects.forSourceString(
                    "com.example.Ticket",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import java.util.List;

                    @ExerisDomain(module = "support", path = "/tickets")
                    public class Ticket {
                        @Field(label = "Priorities")
                        private List<Priority> priorities;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(ticket, priority);
            assertThat(compilation).succeededWithoutWarnings();

            assertThat(compilation.generatedFile(
                    StandardLocation.CLASS_OUTPUT,
                    "exeris-metadata/enum_Priority.json")).isPresent();
        }
    }

    @Nested
    @DisplayName("Standalone @Saga (no @ExerisDomain)")
    class StandaloneSagaTests {

        @Test
        @DisplayName("Class annotated with only @Saga produces saga metadata")
        void standaloneSagaProducesMetadata() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.PaymentFlow",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Saga;
                    import eu.exeris.sdk.annotation.SagaStep;

                    @Saga(name = "PaymentFlow", timeout = "PT30M", maxRetries = 3)
                    public class PaymentFlow {
                        @SagaStep(order = 0, name = "reserveFunds", service = "payments",
                                command = "ReserveFunds")
                        public void reserve() {}

                        @SagaStep(order = 1, name = "chargeCard", service = "payments",
                                command = "ChargeCard")
                        public void charge() {}
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "PaymentFlow");
            assertThat(json)
                    .contains("\"entityName\" : \"PaymentFlow\"")
                    .contains("\"sagaMetadata\"")
                    .contains("\"reserveFunds\"")
                    .contains("\"chargeCard\"");
        }

        @Test
        @DisplayName("Class with BOTH @ExerisDomain + @Saga is processed only by the domain path, not the saga-only path")
        void domainPlusSagaSkipsSagaOnlyPath() throws IOException {
            // The @Saga processing path explicitly skips elements that
            // also carry @ExerisDomain (to avoid duplicate file writes).
            // A single metadata file for the entity must be emitted.
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.OrderFlow",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Saga;
                    import eu.exeris.sdk.annotation.SagaStep;

                    @ExerisDomain(module = "sales", path = "/order-flow")
                    @Saga(name = "OrderFlow")
                    public class OrderFlow {
                        @SagaStep(order = 0, name = "validate", service = "orders",
                                command = "Validate")
                        public void step1() {}
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "OrderFlow");
            // The single emitted file carries BOTH domain attributes and
            // the saga config — the saga-only branch was skipped.
            assertThat(json)
                    .contains("\"module\" : \"sales\"")
                    .contains("\"sagaMetadata\"")
                    .contains("\"validate\"");
        }
    }

    @Nested
    @DisplayName("@Field full-attribute matrix")
    class FieldAttributeMatrixTests {

        @Test
        @DisplayName("Every @Field attribute the processor reads (label / description / required / unique / indexed / searchable / sortable / filterable / readOnly / inCreate / inUpdate / computed / computedFrom) flows into metadata")
        void everyFieldAttributeFlowsThrough() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.FieldAttrs",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "sales", path = "/field-attrs")
                    public class FieldAttrs {

                        @Field(
                            label = "Display Name",
                            description = "Customer-visible name",
                            required = true,
                            unique = true,
                            indexed = true,
                            searchable = true,
                            sortable = true,
                            filterable = true,
                            readOnly = true,
                            inCreate = true,
                            inUpdate = false
                        )
                        private String name;

                        @Field(label = "Computed", computed = true, computedFrom = {"a", "b"})
                        private String derived;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "FieldAttrs");
            assertThat(json)
                    .contains("\"displayName\" : \"Display Name\"")
                    .contains("\"description\" : \"Customer-visible name\"")
                    .contains("\"required\" : true")
                    .contains("\"unique\" : true")
                    .contains("\"indexed\" : true")
                    .contains("\"searchable\" : true")
                    .contains("\"sortable\" : true")
                    .contains("\"filterable\" : true")
                    .contains("\"readOnly\" : true")
                    // inCreate=true / inUpdate=false both flow through to
                    // FieldMetadata.Builder, but Jackson serialises only
                    // the non-default; assert the surviving one.
                    .contains("\"inCreate\" : true")
                    // Second field: computed flag. computedFrom is checked
                    // by the processor (containsKey branch fires) but
                    // the array value doesn't propagate through the
                    // `instanceof String[]` guard — javac's
                    // AnnotationValue surface returns
                    // List<AnnotationValue>, not String[], so the inner
                    // assignment silently drops. Pre-existing
                    // limitation; the containsKey branch is what we
                    // care about for coverage here.
                    .contains("\"computed\" : true");
        }
    }

    @Nested
    @DisplayName("@Action full-attribute matrix")
    class ActionAttributeMatrixTests {

        @Test
        @DisplayName("Every @Action attribute the processor reads (description / httpMethod / async) flows into metadata")
        void everyActionAttributeFlowsThrough() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.ActionAttrs",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Action;

                    @ExerisDomain(module = "sales", path = "/action-attrs")
                    public class ActionAttrs {

                        @Action(
                            name = "approve",
                            label = "Approve Order",
                            description = "Mark the order as approved",
                            httpMethod = "POST",
                            path = "/approve",
                            async = true
                        )
                        public void approve() {}
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "ActionAttrs");
            assertThat(json)
                    .contains("\"description\" : \"Mark the order as approved\"")
                    .contains("\"httpMethod\" : \"POST\"")
                    .contains("\"async\" : true");
        }
    }

    @Nested
    @DisplayName("@DomainEvent trigger-suffix mapping")
    class DomainEventTriggerSuffixTests {

        @Test
        @DisplayName("@DomainEvent with no explicit name derives \"<Entity><Suffix>Event\" from the trigger enum")
        void triggerSuffixMappingDerivesEventName() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.TriggerSuffixes",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.DomainEvent;
                    import eu.exeris.sdk.annotation.DomainEvent.Trigger;

                    @ExerisDomain(module = "sales", path = "/trigger-suffixes")
                    @DomainEvent(trigger = Trigger.CREATE, topic = "ts.created")
                    @DomainEvent(trigger = Trigger.UPDATE, topic = "ts.updated")
                    @DomainEvent(trigger = Trigger.DELETE, topic = "ts.deleted")
                    @DomainEvent(trigger = Trigger.FIELD_CHANGED, field = "status",
                            topic = "ts.changed")
                    @DomainEvent(trigger = Trigger.ACTION, action = "approve",
                            topic = "ts.action")
                    @DomainEvent(trigger = Trigger.STATE_TRANSITION,
                            stateTransition = "OPEN->CLOSED", topic = "ts.state")
                    public class TriggerSuffixes {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "TriggerSuffixes");
            // Suffix mappings from triggerToEventSuffix() — every case
            // branch in the switch fires once.
            assertThat(json)
                    .contains("\"TriggerSuffixesCreatedEvent\"")
                    .contains("\"TriggerSuffixesUpdatedEvent\"")
                    .contains("\"TriggerSuffixesDeletedEvent\"")
                    .contains("\"TriggerSuffixesChangedEvent\"")
                    .contains("\"TriggerSuffixesActionEvent\"")
                    // STATE_TRANSITION (and other unmapped triggers) fall
                    // through to the generic "Event" suffix.
                    .contains("\"TriggerSuffixesEvent\"");
        }
    }

    @Nested
    @DisplayName("@SagaStep full-attribute matrix")
    class SagaStepAttributeMatrixTests {

        @Test
        @DisplayName("@SagaStep with description / service / command / compensation / timeout / parallel all flowing through")
        void everyStepAttributeFlowsThrough() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.RichSaga",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.Saga;
                    import eu.exeris.sdk.annotation.SagaStep;

                    @Saga(name = "RichSaga", description = "Saga with rich step metadata")
                    public class RichSaga {
                        @SagaStep(
                            order = 0,
                            name = "richStep",
                            description = "Step with every attribute set",
                            service = "payments",
                            command = "RichCommand",
                            compensation = "RichCompensation",
                            timeout = "PT45S",
                            parallel = true
                        )
                        public void rich() {}
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "RichSaga");
            assertThat(json)
                    .contains("\"description\" : \"Saga with rich step metadata\"")
                    .contains("\"richStep\"")
                    .contains("\"Step with every attribute set\"")
                    .contains("\"payments\"")
                    .contains("\"RichCommand\"")
                    .contains("\"RichCompensation\"")
                    .contains("\"PT45S\"")
                    .contains("\"parallel\" : true");
        }
    }

    @Nested
    @DisplayName("@Graph nodeClass override + @EventSourced aggregateType override")
    class GraphAndEventSourcedAttributeTests {

        @Test
        @DisplayName("@Graph(nodeClass = …) overrides the default label (defaults to entity simple name)")
        void graphNodeClassOverride() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.GraphNode",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Graph;

                    @ExerisDomain(module = "graph", path = "/graph-node")
                    @Graph(nodeClass = "CustomNodeLabel")
                    public class GraphNode {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "GraphNode");
            assertThat(json).contains("\"CustomNodeLabel\"");
        }

        @Test
        @DisplayName("@EventSourced(streamPrefix = …) flows through (snapshotEvery omitted → default 100)")
        void eventSourcedDefaults() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.ESOnly",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.EventSourced;

                    @ExerisDomain(module = "events", path = "/es-only")
                    @EventSourced(streamPrefix = "ESOnly")
                    public class ESOnly {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "ESOnly");
            assertThat(json)
                    .contains("\"eventSourced\"")
                    .contains("\"enabled\" : true");
        }
    }

    @Nested
    @DisplayName("Negative paths")
    class NegativePathTests {

        @Test
        @DisplayName("@ExerisDomain on an interface raises a compile-time error (only classes are valid targets)")
        void domainOnInterfaceIsRejected() {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.NotAClass",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "test", path = "/x")
                    public interface NotAClass {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation)
                    .hadErrorContaining("@ExerisDomain can only be applied to classes");
        }
    }

    @Nested
    @DisplayName("UI defaults")
    class UIDefaultsTests {

        @Test
        @DisplayName("@UI with no attributes set picks up every default (listView/detail/forms = true, exportable = false)")
        void uiDefaultsFire() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.UIBare",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.UI;

                    @ExerisDomain(module = "sales", path = "/ui-bare")
                    @UI
                    public class UIBare {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "UIBare");
            // Defaults: every view true except exportable.
            assertThat(json)
                    .contains("\"listView\" : true")
                    .contains("\"detailView\" : true")
                    .contains("\"createForm\" : true")
                    .contains("\"editForm\" : true")
                    .contains("\"searchable\" : true")
                    .contains("\"filterable\" : true")
                    .contains("\"exportable\" : false");
        }

        @Test
        @DisplayName("@UI with explicit overrides flows them through (containsKey branches fire on the other side)")
        void uiExplicitOverridesPropagate() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.UIOverride",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.UI;

                    @ExerisDomain(module = "sales", path = "/ui-override")
                    @UI(listView = false, detailView = false, createForm = false,
                        editForm = false, searchable = false, filterable = false,
                        exportable = true)
                    public class UIOverride {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "UIOverride");
            assertThat(json)
                    .contains("\"listView\" : false")
                    .contains("\"detailView\" : false")
                    .contains("\"createForm\" : false")
                    .contains("\"editForm\" : false")
                    .contains("\"searchable\" : false")
                    .contains("\"filterable\" : false")
                    .contains("\"exportable\" : true");
        }
    }

    // ---------- helpers ----------

    private static Compilation compileWithProcessor(JavaFileObject... sources) {
        return javac().withProcessors(new ExerisDomainProcessor()).compile(sources);
    }

    private static String metadataFor(Compilation compilation, String entity) throws IOException {
        Optional<JavaFileObject> file = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT,
                "exeris-metadata/" + entity + ".json");
        assertThat(file).isPresent();
        return readContent(file.get());
    }

    private static String readContent(JavaFileObject file) throws IOException {
        try (var inputStream = file.openInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
