package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.EmittedJavac;
import eu.exeris.e2e.codegen.compile.InMemoryJavaCompiler;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaStepMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compile-test gate: feeds a representative {@link DomainMetadata} through the
 * Kernel generator strategy and runs {@code javac} over the union of generated
 * sources + the source domain entity. Substring assertions in
 * {@link KernelCodegenE2ETest} cannot catch broken imports or referenced symbols
 * that no longer exist — this test does.
 *
 * <p>The strategy registers: Handler, Service, Repository, Event,
 * EventHandler, GraphSync, Saga, Flyway, OpenAPI, Client, StreamHandler. Generated
 * Java imports {@code eu.exeris.kernel.spi.http.*},
 * {@code eu.exeris.kernel.spi.memory.*}, {@code eu.exeris.kernel.spi.events.*},
 * {@code eu.exeris.kernel.spi.graph.*}, {@code eu.exeris.kernel.spi.flow.*},
 * (ADR-036) {@code spi.http.HttpRequestBodyDecoder*} / {@code HttpRequestDecodingContext},
 * and (ADR-043) the streaming SPI {@code spi.http.HttpStreamHandler} /
 * {@code HttpStreamExchange} / {@code StreamEvent}; the real
 * {@code exeris-kernel-spi} artifact (plus Jackson 3) is on the test
 * classpath via {@code exeris-tooling-bom}, which is the single place the
 * version is pinned — deliberately not restated here, since a literal copy
 * goes stale on every kernel bump. The emitted {@code *Client}
 * binds the tier-neutral {@code eu.exeris.kernel.core.http.client.KernelWebClient}
 * facade (ADR-034) and compiles against the real one from {@code exeris-kernel-core}. A
 * test stub at that FQN would shadow the real class, so a verb the facade does not have (a
 * {@code put}, say) would compile here and fail in every consumer.
 *
 * <p>Run twice, once per bootstrap variant: {@code composed=false} is the
 * cap-less application every release before 0.7.0 emitted; {@code composed=true}
 * adds the SDK boot-conductor call site, compiled against the real
 * {@code exeris-sdk-composition-runtime} artifact.
 */
@Tag("e2e")
@Tag("codegen")
@Tag("compile")
@DisplayName("Kernel Codegen Compile Gate")
class KernelCodegenCompileTest {

    private static final String DOMAIN_PACKAGE = "eu.exeris.e2e.compileapp.domain";
    private static final String ENTITY_NAME = "Order";

    @ParameterizedTest(name = "composed={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("Generated kernel artifacts compile (full registered set incl. Client), "
            + "for both bootstrap variants")
    void generatedArtifactsCompile(boolean composed) {
        DomainMetadata metadata = DomainMetadata.builder(ENTITY_NAME, DOMAIN_PACKAGE)
                .path("/orders")
                .module("sales")
                .description("Compile-test order entity")
                .tenantScoped(true)
                .audited(true)
                .softDelete(true)
                // ADR-043 Slice 1: drives KernelStreamHandlerGenerator + the
                // Application generator's streamRoute(GET, "/orders/stream", ...)
                // registration, so the gate compiles the SSE handler against the
                // real kernel 0.10 streaming SPI (HttpStreamHandler /
                // HttpStreamExchange / StreamEvent).
                .realTimeApi(true)
                .fields(List.of(
                        // T22: a validated field literally named `id`. The handler's
                        // validation guard reads each validated field into a local; if
                        // that local were the bare field name it would emit
                        // `var id = entity.getId()` and clash with handleUpdate's path-id
                        // `UUID id` — uncompilable. This field makes javac the regression
                        // guard for the prefixed-local fix.
                        FieldMetadata.builder("id", "java.util.UUID")
                                .required(true)
                                .build(),
                        // T10: validation rules exercise the handler's server-side
                        // validation guard across every emitted shape — required
                        // null-check, String minLength/maxLength/pattern, and numeric
                        // (BigDecimal) min/max — so the generated checks must compile
                        // against the real getters.
                        // The list route: sortable and filterable fields of a String, a
                        // BigDecimal, a boolean and an enum (below), plus the foreign key, drive
                        // every bind the emitted list filter makes and the sort-column table.
                        FieldMetadata.builder("orderNumber", "String")
                                .required(true)
                                .unique(true)
                                .searchable(true)
                                .sortable(true)
                                .filterable(true)
                                .minLength(3)
                                .pattern("[A-Z0-9-]+")
                                .build(),
                        FieldMetadata.builder("customerName", "String")
                                .required(true)
                                .searchable(true)
                                .maxLength(120)
                                .build(),
                        FieldMetadata.builder("amount", "BigDecimal")
                                .required(true)
                                .sortable(true)
                                .filterable(true)
                                .min(0L)
                                .max(1000000L)
                                .build(),
                        FieldMetadata.builder("tags", "List<java.util.UUID>")
                                .build(),
                        // T19b: a LocalDateTime field. The Repository generator must
                        // bridge it through the native getInstant/bindInstant SPI at the
                        // UTC offset — the kernel has no typed LocalDateTime accessor, so
                        // emitting row.getInstant() straight into a LocalDateTime setter
                        // would not compile. This makes javac the regression guard.
                        FieldMetadata.builder("scheduledFor", "java.time.LocalDateTime")
                                .sortable(true)
                                .build(),
                        // Enum field: exercises the Repository generator's
                        // fall-through emit path. Must be FQCN so JavaPoet
                        // can inject the matching import.
                        //
                        // T8: marked filterable so the Repository + Service
                        // generators emit findByStatus(OrderStatus) — compiling the
                        // enum-typed finder param + the null-guarded String bind
                        // against the real entity getter and kernel SPI.
                        FieldMetadata.builder("status",
                                        DOMAIN_PACKAGE + ".OrderStatus")
                                .filterable(true)
                                .build(),
                        // EV1/B3: a primitive boolean payload field exercises the
                        // `isX()` accessor in the generated publisher — the gate
                        // javac-compiles entity.isExpedited() against the entity.
                        FieldMetadata.builder("expedited", "boolean")
                                .filterable(true)
                                .build()))
                // T8: a MANY_TO_ONE relationship drives the FK finder
                // findByCustomerId(UUID) on the Repository + Service — javac is the
                // regression guard for the generated WHERE customer_id = ? lookup +
                // bindUuid against the kernel persistence SPI.
                .relationships(List.of(
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE)
                                .build()))
                // T1: actions exercise the server-side dispatch generator end-to-end:
                // a no-arg action, a params action (→ generated request record decoded
                // via the codec SPI), and one whose @Action(name) differs from the JVM
                // method (methodName drives the invocation, name drives the route/URL).
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("applyDiscount").methodName("applyDiscount")
                                .params(List.of(
                                        ActionParamMetadata.required("percent", "java.math.BigDecimal"),
                                        ActionParamMetadata.required("reason", "java.lang.String")))
                                .build(),
                        ActionMetadata.builder("markUrgent").methodName("flagUrgent").build(),
                        // ADR-044 Amendment 2: a streaming action on a tenant-partitioned
                        // entity, with a body and an ACTION-triggered event, drives every
                        // part of the per-action driver javac has to accept against the
                        // kernel SPI: the tenant guard, parseBody over HttpStreamExchange,
                        // the stream-id-filtered subscription, the publish calls and the
                        // RuntimeComponents factory that hands the handler its publisher
                        // and EventEngine.
                        ActionMetadata.builder("trackShipment").methodName("trackShipment")
                                .streaming(true)
                                .streamEventType("ShipmentMoved")
                                .params(List.of(ActionParamMetadata.required("carrier", "java.lang.String")))
                                .build()))
                // T48 (ADR-075): every trigger the handler serves is represented here, because
                // the emitted publish call and the emitted publish method are produced by two
                // different generators and only javac proves their signatures agree. CREATE and
                // DELETE take the no-payload overload, ACTION the payload one, and the DELETE
                // event deliberately carries no payload so the delete path keeps its
                // single-statement shape — the payload-bearing delete is covered by the
                // generator's own unit test, where an extra read is cheap to assert.
                .events(List.of(
                        DomainEventMetadata.builder("OrderCreated")
                                .trigger(DomainEventMetadata.Trigger.CREATE)
                                .build(),
                        DomainEventMetadata.builder("OrderCancelled")
                                .trigger(DomainEventMetadata.Trigger.DELETE)
                                .build(),
                        DomainEventMetadata.builder("OrderDiscounted")
                                .trigger(DomainEventMetadata.Trigger.ACTION)
                                .actionName("applyDiscount")
                                .payloadFields(List.of("amount", "orderNumber"))
                                .build(),
                        DomainEventMetadata.withTopic("OrderShipped", "orders.shipped"),
                        DomainEventMetadata.builder("OrderTracked")
                                .trigger(DomainEventMetadata.Trigger.ACTION)
                                .actionName("trackShipment")
                                .payloadFields(List.of("orderNumber"))
                                .build(),
                        // EV1 (ADR-046): an event WITH payloadFields drives the
                        // codec-resolved publish path — the generated publisher emits a
                        // redacted <Event>Payload record (customerName is sensitive →
                        // dropped) and resolves EventPayloadCodec via
                        // KernelProviders.eventPayloadCodecRegistry(), so the gate javac-
                        // compiles it against the real kernel codec SPI + jdk.jfr.
                        DomainEventMetadata.builder("OrderPlaced")
                                .payloadFields(List.of("amount", "orderNumber", "customerName", "expedited"))
                                .sensitiveFields(List.of("customerName"))
                                .build()))
                .graphMetadata(new GraphMetadata("Order", List.of(),
                        List.of(new GraphEdgeMetadata("tenantId", "Tenant", "OWNED_BY")),
                        List.of()))
                .sagaMetadata(SagaMetadata.builder("OrderFulfillment")
                        // A declared version > 1 emits builder.version(DEFINITION_VERSION),
                        // so javac proves the call against the real FlowDefinitionBuilder. The
                        // method exists from kernel 0.12, so this line is also what fails the
                        // gate if the kernel pin ever drops back below it.
                        .version(2)
                        .timeout("PT45M")
                        .maxRetries(5)
                        .steps(List.of(
                                SagaStepMetadata.builder("reserve-inventory", 0)
                                        .compensation("restoreInventory")
                                        .build(),
                                SagaStepMetadata.simple("send-email", 1, null)))
                        .build())
                .build();

        List<GeneratedFile> generated = new KernelGeneratorStrategy().generate(metadata);
        // ADR-044 Amendment 2 decision 7: realTimeApi also emits the spectate handler. On this
        // tenant-partitioned entity it carries the tenant guard and subscribes to every event,
        // so javac sees its guard, its row read and its filtered subscriptions.
        assertThat(generated.stream().filter(f -> f.className().equals("OrderSpectateStreamHandler"))
                .findFirst().orElseThrow().content())
                .as("the spectate handler is among the compiled sources, in its full shape")
                .contains("KernelProviders.STORAGE_CONTEXT.isBound()")
                .contains("found = service.findById(id)")
                .contains("tokens.add(bus.subscribe(\"OrderPlacedEvent\"");

        // Application + RuntimeComponents + RuntimeLifecycle are project-wide; not in the
        // strategy. Run the Application generator separately so the
        // compile-gate verifies the full bootstrap stack resolves
        // against the real exeris-kernel-spi and -core artifacts.
        // The composed variant emits the boot-conductor call site, so this run also
        // javac-compiles the try-with-resources against the real
        // eu.exeris.sdk.composition.runtime.CompositionConductor — including the fact that
        // its close() declares no checked exception (a boot(Runnable) lambda could not
        // otherwise hold it).
        String basePackage = DOMAIN_PACKAGE.replace(".domain", "");
        List<GeneratedFile> applicationFiles = new KernelApplicationGenerator()
                .generateAll(List.of(metadata), basePackage, composed);

        InMemoryJavaCompiler compiler = new InMemoryJavaCompiler()
                .addSource(DOMAIN_PACKAGE + "." + ENTITY_NAME, sourceEntity())
                .addSource(DOMAIN_PACKAGE + ".OrderStatus", sourceStatusEnum());

        for (GeneratedFile file : generated) {
            if ("java".equals(file.extension())) {
                compiler.addSource(file.packageName() + "." + file.className(), file.content());
            }
        }
        for (GeneratedFile file : applicationFiles) {
            if ("java".equals(file.extension())) {
                compiler.addSource(file.packageName() + "." + file.className(), file.content());
            }
        }

        InMemoryJavaCompiler.Result result = compiler.compile();
        assertThat(result.success())
                .as("javac output:%n%s", result.renderErrors())
                .isTrue();
    }

    private static final String UNIVERSE_PACKAGE = "eu.exeris.e2e.universeapp.domain";

    /**
     * A {@code DataScope.UNIVERSE} entity — an owner plus a {@code @SharedScope} key —
     * through the full strategy and the composition root. The shared-scope stamp is the first
     * emitted code to call {@code StorageContext.sharedScopeKey()} and
     * {@code KernelProviders.storageContextOrSystem()}, so javac against the real kernel SPI is the
     * proof those calls exist with the shape the emitter assumes. Both key types, because the
     * emitted resolver differs: a UUID key is parsed (and a parse failure wrapped), a String one is
     * returned as bound.
     */
    @ParameterizedTest(name = "sharedScope={0}")
    @ValueSource(strings = {"java.util.UUID", "java.lang.String"})
    @DisplayName("T29 B: a UNIVERSE entity's artefacts compile against the kernel SPI, for both key types")
    void universeArtifactsCompile(String scopeType) {
        DomainMetadata metadata = DomainMetadata.builder("Species", UNIVERSE_PACKAGE)
                .path("/species")
                .module("catalog")
                .dataScope(DataScope.UNIVERSE)
                .audited(true)
                .versioned(true)
                .systemFields(SystemFieldsMetadata.builder()
                        .tenantIdField("organizationId").sharedScopeField("worldId").build())
                .fields(List.of(
                        FieldMetadata.builder("name", "String").required(true).build(),
                        FieldMetadata.builder("organizationId", "java.util.UUID").build(),
                        FieldMetadata.builder("worldId", scopeType).filterable(true).build()))
                // A streaming action on a versioned UNIVERSE entity: its handler catches the
                // version conflict (409) and both caller-fault types in one multi-catch (400).
                .actions(List.of(ActionMetadata.builder("rename").methodName("rename")
                        .streaming(true)
                        .params(List.of(ActionParamMetadata.required("name", "java.lang.String")))
                        .build()))
                .events(List.of(DomainEventMetadata.builder("SpeciesRenamed")
                        .trigger(DomainEventMetadata.Trigger.ACTION)
                        .actionName("rename")
                        .build()))
                .build();

        List<GeneratedFile> generated = new KernelGeneratorStrategy().generate(metadata);
        assertThat(generated)
                .as("the additive shared-scope migration is part of the emitted set")
                .anyMatch(f -> f.className().contains("__shared_scope_specieses"));
        String repository = generated.stream()
                .filter(f -> f.className().equals("SpeciesRepository"))
                .findFirst().orElseThrow().content();
        assertThat(repository)
                .as("non-vacuous: the code under compilation reads the kernel's shared-scope key")
                .contains("sharedScopeKey()")
                .contains("actingSharedScope()")
                .contains("refuseForeignTenant(")
                .contains("refuseForeignSharedScope(");
        assertThat(generated)
                .as("both caller-fault types are among the compiled sources")
                .extracting(GeneratedFile::className)
                .contains("SpeciesTenantMismatchException", "SpeciesSharedScopeMismatchException");
        assertThat(generated.stream().filter(f -> f.className().equals("SpeciesHandler"))
                .findFirst().orElseThrow().content())
                .as("the multi-catch javac has to accept (disjoint types)")
                .contains("catch (SpeciesTenantMismatchException | SpeciesSharedScopeMismatchException e)");
        assertThat(generated.stream().filter(f -> f.className().equals("SpeciesRenameStreamHandler"))
                .findFirst().orElseThrow().content())
                .as("the per-action stream handler answers the same caller faults")
                .contains("catch (SpeciesVersionConflictException e)")
                .contains("catch (SpeciesTenantMismatchException | SpeciesSharedScopeMismatchException e)");

        List<GeneratedFile> applicationFiles = new KernelApplicationGenerator()
                .generateAll(List.of(metadata), UNIVERSE_PACKAGE.replace(".domain", ""), false);

        String javaScopeType = scopeType.substring(scopeType.lastIndexOf('.') + 1);
        InMemoryJavaCompiler compiler = new InMemoryJavaCompiler()
                .addSource(UNIVERSE_PACKAGE + ".Species", """
                        package %s;

                        import java.time.Instant;
                        import java.util.UUID;

                        public class Species {
                            private UUID id;
                            private String name;
                            private UUID organizationId;
                            private %s worldId;
                            private Instant createdAt;
                            private Instant updatedAt;
                            private long version;

                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }
                            public String getName() { return name; }
                            public void setName(String name) { this.name = name; }
                            public UUID getOrganizationId() { return organizationId; }
                            public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
                            public %s getWorldId() { return worldId; }
                            public void setWorldId(%s worldId) { this.worldId = worldId; }
                            public Instant getCreatedAt() { return createdAt; }
                            public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
                            public Instant getUpdatedAt() { return updatedAt; }
                            public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
                            public long getVersion() { return version; }
                            public void setVersion(long version) { this.version = version; }
                            public void rename(String name) { this.name = name; }
                        }
                        """.formatted(UNIVERSE_PACKAGE, javaScopeType, javaScopeType, javaScopeType));
        for (GeneratedFile file : generated) {
            if ("java".equals(file.extension())) {
                compiler.addSource(file.packageName() + "." + file.className(), file.content());
            }
        }
        for (GeneratedFile file : applicationFiles) {
            if ("java".equals(file.extension())) {
                compiler.addSource(file.packageName() + "." + file.className(), file.content());
            }
        }

        InMemoryJavaCompiler.Result result = compiler.compile();
        assertThat(result.success())
                .as("javac output:%n%s", result.renderErrors())
                .isTrue();
    }

    /**
     * ADR-104: an entity whose key {@code primaryKeyField} renames, referenced by another entity's
     * {@code MANY_TO_ONE}, through the real chain — annotated sources, {@code javac} + the processor,
     * {@code CodegenPipeline} for the main and the test tree — and compiled with every lint category
     * on. Both trees call the key's accessors, so a generator that wrote {@code getId()} or
     * {@code setId(...)} fails here; the foreign key and the key column are asserted on the SQL.
     */
    @Test
    @DisplayName("ADR-104: a renamed-key entity and a MANY_TO_ONE into it compile without a warning, "
            + "main and test trees")
    void renamedPrimaryKeyCorpusCompilesClean(@TempDir Path workspace) throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        Path generatedTests = workspace.resolve("src/test/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, renamedKeyCorpus());

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, "com.billing");
        pipeline.runTests(metadataDir, generatedTests, "com.billing");

        assertThat(migration(generatedMain, "create_invoices"))
                .contains("invoice_no UUID PRIMARY KEY DEFAULT gen_random_uuid()")
                .doesNotContain(" id UUID");
        assertThat(migration(generatedMain, "foreign_keys"))
                .contains("FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_no)");
        assertThat(Files.readString(generatedMain.resolve("com/billing/repository/InvoiceRepository.java")))
                .contains("WHERE invoice_no = ?")
                .contains("entity.setInvoiceNo(");

        List<String> files = new ArrayList<>();
        for (Path root : List.of(generatedMain, generatedTests)) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().forEach(files::add);
            }
        }
        EmittedJavac.Result result = EmittedJavac.compile(files, workspace.resolve("target/app-classes"),
                System.getProperty("java.class.path") + File.pathSeparator + entityClasses);
        assertThat(result.clean())
                .as("javac output:%n%s", result.render())
                .isTrue();
    }

    /** Reads the one emitted migration whose file name contains {@code fragment}. */
    private static String migration(Path generated, String fragment) throws IOException {
        try (Stream<Path> files = Files.walk(generated.resolve("db/migration"))) {
            Path file = files.filter(p -> p.getFileName().toString().contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no migration matching '" + fragment + "'"));
            return Files.readString(file);
        }
    }

    /** {@code Invoice}, keyed by {@code invoiceNo}, and {@code Payment}, which references it. */
    private static Map<String, String> renamedKeyCorpus() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/billing/domain/Invoice.java",
                """
                package com.billing.domain;

                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "billing", path = "/invoices", primaryKeyField = "invoiceNo")
                @DomainEvent(name = "InvoiceIssued", trigger = DomainEvent.Trigger.CREATE, topic = "invoices.issued")
                public class Invoice {

                    private UUID invoiceNo;

                    @Field(label = "Customer", required = true, sortable = true, filterable = true)
                    private String customer;

                    public UUID getInvoiceNo() { return invoiceNo; }
                    public void setInvoiceNo(UUID invoiceNo) { this.invoiceNo = invoiceNo; }
                    public String getCustomer() { return customer; }
                    public void setCustomer(String customer) { this.customer = customer; }
                }
                """);
        sources.put("com/billing/domain/Payment.java",
                """
                package com.billing.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;

                import java.math.BigDecimal;
                import java.util.UUID;

                @ExerisDomain(module = "billing", path = "/payments")
                public class Payment {

                    private UUID id;

                    @Field(label = "Amount")
                    private BigDecimal amount;

                    @Relationship(targetEntity = Invoice.class, displayField = "customer")
                    private UUID invoiceId;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public BigDecimal getAmount() { return amount; }
                    public void setAmount(BigDecimal amount) { this.amount = amount; }
                    public UUID getInvoiceId() { return invoiceId; }
                    public void setInvoiceId(UUID invoiceId) { this.invoiceId = invoiceId; }
                }
                """);
        return sources;
    }

    private static String sourceEntity() {
        return """
                package %s;

                import java.math.BigDecimal;
                import java.time.Instant;
                import java.time.LocalDateTime;
                import java.util.List;
                import java.util.UUID;

                public class %s {

                    private UUID id;
                    private String orderNumber;
                    private String customerName;
                    private BigDecimal amount;
                    private List<UUID> tags;
                    private LocalDateTime scheduledFor;
                    private OrderStatus status;
                    private UUID tenantId;
                    private Instant createdAt;
                    private Instant updatedAt;
                    private boolean deleted;
                    private boolean expedited;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }

                    public String getOrderNumber() { return orderNumber; }
                    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

                    public String getCustomerName() { return customerName; }
                    public void setCustomerName(String customerName) { this.customerName = customerName; }

                    public BigDecimal getAmount() { return amount; }
                    public void setAmount(BigDecimal amount) { this.amount = amount; }

                    public List<UUID> getTags() { return tags; }
                    public void setTags(List<UUID> tags) { this.tags = tags; }

                    public LocalDateTime getScheduledFor() { return scheduledFor; }
                    public void setScheduledFor(LocalDateTime scheduledFor) { this.scheduledFor = scheduledFor; }

                    public OrderStatus getStatus() { return status; }
                    public void setStatus(OrderStatus status) { this.status = status; }

                    public boolean isExpedited() { return expedited; }
                    public void setExpedited(boolean expedited) { this.expedited = expedited; }

                    public UUID getTenantId() { return tenantId; }
                    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

                    public Instant getCreatedAt() { return createdAt; }
                    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

                    public Instant getUpdatedAt() { return updatedAt; }
                    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

                    public boolean isDeleted() { return deleted; }
                    public void setDeleted(boolean deleted) { this.deleted = deleted; }

                    // @Action methods — invoked by the generated action handlers (T1).
                    public void cancel() { this.status = OrderStatus.CANCELLED; }
                    public void applyDiscount(BigDecimal percent, String reason) {
                        if (percent != null) { this.amount = this.amount.subtract(percent); }
                    }
                    public void flagUrgent() { /* @Action(name="markUrgent") */ }
                    // @Action(streaming=true) — served by the per-action stream
                    // handler via streamRoute; the handler generator emits NO
                    // respond-once handle method for it (ADR-044).
                    public void trackShipment(String carrier) { this.status = OrderStatus.SHIPPED; }
                }
                """.formatted(DOMAIN_PACKAGE, ENTITY_NAME);
    }

    private static String sourceStatusEnum() {
        return """
                package %s;

                public enum OrderStatus {
                    PENDING, CONFIRMED, SHIPPED, CANCELLED;
                }
                """.formatted(DOMAIN_PACKAGE);
    }
}
