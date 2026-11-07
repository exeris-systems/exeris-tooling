package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.EmittedJavac;
import eu.exeris.e2e.codegen.compile.InMemoryJavaCompiler;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.BlobMetadata;
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
import eu.exeris.tooling.codegen.java.kernel.UnpersistableFieldTypeException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static final String BLOB_PACKAGE = "eu.exeris.e2e.blobapp.domain";

    /**
     * ADR-108: an entity with a restricted and an unrestricted {@code @Blob} field through the full
     * strategy and the composition root, hard- and soft-deleted. The handler is the first emitted
     * code to call {@code BlobStore}, {@code BlobUploadHandle} and {@code BlobDownloadHandle} and to
     * answer a raw {@code HttpResponse} over a buffer of its own, so javac against the real kernel SPI
     * is the proof those calls have the shape the emitter assumes. A {@code DELETE}-triggered event
     * puts the publisher ahead of the store in the handler's constructor.
     */
    @ParameterizedTest(name = "softDelete={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("ADR-108: a @Blob entity's artefacts compile against the kernel SPI, hard- and soft-deleted")
    void blobFieldArtifactsCompile(boolean softDelete) {
        String blobRef = "eu.exeris.kernel.spi.storage.blob.BlobRef";
        DomainMetadata metadata = DomainMetadata.builder("Ticket", BLOB_PACKAGE)
                .path("/tickets")
                .module("support")
                .dataScope(DataScope.TENANT)
                .softDelete(softDelete)
                .fields(List.of(
                        FieldMetadata.builder("code", "String").required(true).filterable(true).build(),
                        FieldMetadata.builder("receipt", blobRef)
                                .blob(BlobMetadata.ofContentTypes(
                                        List.of("application/pdf")))
                                .build(),
                        FieldMetadata.builder("photo", blobRef)
                                .blob(new BlobMetadata("photos", List.of()))
                                .build()))
                .events(List.of(DomainEventMetadata.builder("TicketClosed")
                        .trigger(DomainEventMetadata.Trigger.DELETE)
                        .build()))
                .build();

        List<GeneratedFile> generated = new KernelGeneratorStrategy().generate(metadata);
        String handler = generated.stream().filter(f -> f.className().equals("TicketHandler"))
                .findFirst().orElseThrow().content();
        assertThat(handler)
                .as("non-vacuous: the code under compilation uses the blob SPI")
                .contains("public TicketHandler(TicketService service, MemoryAllocator allocator,")
                .contains("blobStore.beginUpload(")
                .contains("blobStore.openDownload(ref)")
                .contains("handleUploadPhoto");
        assertThat(handler.contains("deleteBlobObjects(id)")).isEqualTo(!softDelete);

        List<GeneratedFile> applicationFiles = new KernelApplicationGenerator()
                .generateAll(List.of(metadata), BLOB_PACKAGE.replace(".domain", ""), false);

        InMemoryJavaCompiler compiler = new InMemoryJavaCompiler()
                .addSource(BLOB_PACKAGE + ".Ticket", """
                        package %s;

                        import eu.exeris.kernel.spi.storage.blob.BlobRef;
                        import java.util.UUID;

                        public class Ticket {
                            private UUID id;
                            private String code;
                            private UUID tenantId;
                            private boolean deleted;
                            private BlobRef receipt;
                            private BlobRef photo;

                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }
                            public String getCode() { return code; }
                            public void setCode(String code) { this.code = code; }
                            public UUID getTenantId() { return tenantId; }
                            public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
                            public boolean isDeleted() { return deleted; }
                            public void setDeleted(boolean deleted) { this.deleted = deleted; }
                            public BlobRef getReceipt() { return receipt; }
                            public void setReceipt(BlobRef receipt) { this.receipt = receipt; }
                            public BlobRef getPhoto() { return photo; }
                            public void setPhoto(BlobRef photo) { this.photo = photo; }
                        }
                        """.formatted(BLOB_PACKAGE));
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
                .doesNotContain(" id UUID")
                .doesNotContain("default_status")
                .doesNotContain("system_tag")
                .doesNotContain("session_token");
        assertThat(migration(generatedMain, "create_receipts"))
                .contains("receipt_no UUID PRIMARY KEY DEFAULT gen_random_uuid()")
                .doesNotContain(" id UUID");
        assertThat(migration(generatedMain, "foreign_keys"))
                .contains("FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_no)");
        assertThat(Files.readString(generatedMain.resolve("com/billing/repository/InvoiceRepository.java")))
                .contains("WHERE invoice_no = ?")
                .contains("entity.setInvoiceNo(");
        assertThat(Files.readString(generatedMain.resolve("com/billing/repository/ReceiptRepository.java")))
                .contains("WHERE receipt_no = ?")
                .contains("entity.setReceiptNo(");

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

    /**
     * A field typed as another entity — a {@code @Relationship MANY_TO_ONE} to it, or a field that
     * only a {@code @GraphEdge} describes — has no column the repository can bind and read back,
     * so the run is refused with {@code EXT-GEN-3003} before anything is written, naming each such
     * field and the key it should hold.
     */
    @Test
    @DisplayName("EXT-GEN-3003: fields typed as another entity are refused through the real chain, "
            + "nothing written")
    void entityTypedReferenceCorpusIsRefused(@TempDir Path workspace) throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null,
                entityReferenceCorpus(false));

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        assertThatThrownBy(() -> pipeline.run(metadataDir, generatedMain, "com.shop"))
                .isInstanceOfSatisfying(UnpersistableFieldTypeException.class, e -> assertThat(e.fields())
                        .containsExactly(
                                "com.shop.domain.Invoice.customer : com.shop.domain.Customer (a reference to"
                                        + " the entity Customer, which no column stores; hold its key instead:"
                                        + " UUID customerId, with @Relationship(targetEntity = Customer.class)"
                                        + " for a foreign key)",
                                "com.shop.domain.InvoiceLine.invoice : com.shop.domain.Invoice (a reference to"
                                        + " the entity Invoice, which no column stores; hold its key instead:"
                                        + " UUID invoiceId, with @Relationship(targetEntity = Invoice.class)"
                                        + " for a foreign key)",
                                "com.shop.domain.Product.category : com.shop.domain.Category (a reference to"
                                        + " the entity Category, which no column stores; hold its key instead:"
                                        + " UUID categoryId, with @Relationship(targetEntity = Category.class)"
                                        + " for a foreign key)"))
                .hasMessageStartingWith("[Exeris] EXT-GEN-3003: ");
        assertThat(generatedMain).doesNotExist();
    }

    /**
     * The same corpus holding each reference as the referenced row's key compiles, main and test
     * trees, with a foreign key for each {@code @Relationship MANY_TO_ONE}. {@code Invoice.lines}
     * is the inverse of {@code InvoiceLine.invoiceId}: the repository neither binds nor reads it.
     */
    @Test
    @DisplayName("the key-holding form of the refused entity references compiles, main and test trees")
    void keyHoldingReferenceCorpusCompilesClean(@TempDir Path workspace) throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        Path generatedTests = workspace.resolve("src/test/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null,
                entityReferenceCorpus(true));

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, "com.shop");
        pipeline.runTests(metadataDir, generatedTests, "com.shop");

        assertThat(migration(generatedMain, "foreign_keys"))
                .contains("FOREIGN KEY (customer_id) REFERENCES customers(id)")
                .contains("FOREIGN KEY (invoice_id) REFERENCES invoices(id)");
        assertThat(Files.readString(generatedMain.resolve("com/shop/repository/InvoiceRepository.java")))
                .contains("entity.setCustomerId(")
                .doesNotContain("setLines(")
                .doesNotContain("parseList(");
        assertThat(Files.readString(generatedMain.resolve("com/shop/handler/InvoiceHandler.java")))
                .contains("entity.setLines(null);");
        assertThat(Files.readString(generatedMain.resolve("com/shop/repository/ProductRepository.java")))
                .contains("entity.setCategoryId(");

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

    /**
     * The reference shapes of the SDK examples: {@code Invoice} and {@code InvoiceLine} each
     * reference an entity through {@code @Relationship MANY_TO_ONE}, and {@code Product} its
     * {@code Category} through {@code @GraphEdge}. With {@code keyed}, each reference is held as the
     * referenced row's {@code UUID} key; without, as a field typed as the referenced entity. Either
     * way {@code Invoice} declares its lines as the inverse collection {@code List<InvoiceLine>}
     * whose {@code mappedBy} names {@code InvoiceLine}'s reference to it.
     */
    private static Map<String, String> entityReferenceCorpus(boolean keyed) {
        return entityReferenceCorpus(keyed, "relationshipType = Relationship.RelationshipType.ONE_TO_MANY,"
                + " mappedBy = \"invoice\"");
    }

    /**
     * {@link #entityReferenceCorpus(boolean)} with {@code Invoice.lines} declared by
     * {@code linesRelationship}: the attributes of its {@code @Relationship} after
     * {@code targetEntity} and {@code displayField}.
     */
    private static Map<String, String> entityReferenceCorpus(boolean keyed, String linesRelationship) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String entity : List.of("Customer", "Category")) {
            sources.put("com/shop/domain/" + entity + ".java",
                    """
                    package com.shop.domain;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    import java.util.UUID;

                    @ExerisDomain(module = "shop", path = "/%2$s")
                    public class %1$s {

                        private UUID id;

                        @Field(label = "Name", required = true)
                        private String name;

                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                        public String getName() { return name; }
                        public void setName(String name) { this.name = name; }
                    }
                    """.formatted(entity, entity.equals("Customer") ? "customers" : "categories"));
        }
        sources.put("com/shop/domain/Invoice.java", referencing("Invoice", "invoices", "number",
                "@Relationship(targetEntity = Customer.class, displayField = \"name\",\n"
                        + "            relationshipType = Relationship.RelationshipType.MANY_TO_ONE)",
                "Customer", "customer", keyed)
                .replace("    public UUID getId()", """
                    @Relationship(targetEntity = InvoiceLine.class, displayField = "description",
                            %s)
                    private java.util.List<InvoiceLine> lines;

                    public java.util.List<InvoiceLine> getLines() { return lines; }
                    public void setLines(java.util.List<InvoiceLine> lines) { this.lines = lines; }
                    public UUID getId()""".formatted(linesRelationship)));
        sources.put("com/shop/domain/InvoiceLine.java", referencing("InvoiceLine", "invoice-lines",
                "description",
                "@Relationship(targetEntity = Invoice.class, displayField = \"number\",\n"
                        + "            relationshipType = Relationship.RelationshipType.MANY_TO_ONE)",
                "Invoice", "invoice", keyed));
        sources.put("com/shop/domain/Product.java", referencing("Product", "products", "title",
                "@GraphEdge(type = \"BELONGS_TO\", target = Category.class)", "Category", "category", keyed));
        return sources;
    }

    /**
     * An entity with one {@code String} field and one reference to {@code target}, held as a field
     * named {@code reference} typed as {@code target}, or, when {@code keyed}, as
     * {@code <reference>Id} typed {@code UUID}. An entity whose reference is a {@code @GraphEdge}
     * is a {@code @Graph} node.
     */
    private static String referencing(String entity, String path, String text, String annotation,
                                      String target, String reference, boolean keyed) {
        String type = keyed ? "UUID" : target;
        String field = keyed ? reference + "Id" : reference;
        String accessor = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        String textAccessor = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        return """
                package com.shop.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Graph;
                import eu.exeris.sdk.annotation.GraphEdge;
                import eu.exeris.sdk.annotation.Relationship;

                import java.util.UUID;

                @ExerisDomain(module = "shop", path = "/%2$s")%9$s
                public class %1$s {

                    private UUID id;

                    @Field(label = "Text", required = true)
                    private String %3$s;

                    %4$s
                    private %5$s %6$s;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String get%7$s() { return %3$s; }
                    public void set%7$s(String %3$s) { this.%3$s = %3$s; }
                    public %5$s get%8$s() { return %6$s; }
                    public void set%8$s(%5$s %6$s) { this.%6$s = %6$s; }
                }
                """.formatted(entity, path, text, annotation, type, field, textAccessor, accessor,
                        annotation.startsWith("@GraphEdge") ? "\n@Graph(nodeClass = \"" + entity + "\")" : "");
    }

    /**
     * EXT-PROC-1034 from the other side: an entity declaring exactly the accessors the processor
     * requires, and no other, passes the processor, and the main and test trees generated from it
     * compile. A system column, an event payload, a graph edge with its weight, a graph property,
     * a validated field, a server-set field and a primitive boolean each reach the code that calls
     * their accessors; the {@code @Blob} field has its setter and no getter, and the constant and
     * the transient field have neither. An emitter that calls an accessor the processor does not
     * require fails here.
     */
    @Test
    @DisplayName("EXT-PROC-1034: an entity with only the accessors the processor requires compiles, "
            + "main and test trees")
    void requiredAccessorsAreEnough(@TempDir Path workspace) throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        Path generatedTests = workspace.resolve("src/test/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, minimalAccessorCorpus());

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, "com.minimal");
        pipeline.runTests(metadataDir, generatedTests, "com.minimal");

        assertThat(Files.readString(generatedMain.resolve("com/minimal/handler/LedgerHandler.java")))
                .as("non-vacuous: the handler resets the blob field and validates the title")
                .contains("entity.setScan(null)")
                .contains("entity.getTitle()");
        assertThat(Files.readString(generatedMain.resolve("com/minimal/event/LedgerEventPublisher.java")))
                .contains("entity.isActive()");
        assertThat(Files.readString(generatedMain.resolve("com/minimal/graph/LedgerGraphSync.java")))
                .contains("entity.getOwnerId()")
                .contains("entity.getWeight()");

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

    private static Map<String, String> minimalAccessorCorpus() {
        return Map.of("com/minimal/domain/Ledger.java", """
                package com.minimal.domain;

                import eu.exeris.kernel.spi.storage.blob.BlobRef;
                import eu.exeris.sdk.annotation.Blob;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Graph;
                import eu.exeris.sdk.annotation.GraphEdge;
                import eu.exeris.sdk.annotation.GraphProperty;
                import eu.exeris.sdk.annotation.Validation;

                import java.time.Instant;
                import java.util.UUID;

                @ExerisDomain(module = "minimal", path = "/ledgers", dataScope = ExerisDomain.DataScope.TENANT,
                        audited = true, softDelete = true, versioned = true)
                @DomainEvent(name = "LedgerOpened", trigger = DomainEvent.Trigger.CREATE, topic = "ledgers.opened",
                        includeFields = {"title", "active"})
                @Graph(nodeClass = "Ledger")
                public class Ledger {

                    public static final String KIND = "ledger";
                    private transient String rendered;

                    private UUID id;

                    @Field(label = "Title", required = true)
                    @Validation(maxLength = 64)
                    @GraphProperty(indexed = true)
                    private String title;

                    @Field(label = "Reference", required = true, readOnly = true)
                    private String reference;

                    @Field(label = "Active")
                    private boolean active;

                    @Field(label = "Owner ID")
                    @GraphEdge(type = "OWNED_BY", targetLabel = "Owner", weighted = true, weightField = "weight")
                    private UUID ownerId;

                    @Field(label = "Weight")
                    private double weight;

                    @Blob
                    private BlobRef scan;

                    private UUID tenantId;
                    private Instant createdAt;
                    private Instant updatedAt;
                    private boolean deleted;
                    private Long version;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public String getReference() { return reference; }
                    public void setReference(String reference) { this.reference = reference; }
                    public boolean isActive() { return active; }
                    public void setActive(boolean active) { this.active = active; }
                    public UUID getOwnerId() { return ownerId; }
                    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
                    public double getWeight() { return weight; }
                    public void setWeight(double weight) { this.weight = weight; }
                    public void setScan(BlobRef scan) { this.scan = scan; }
                    public UUID getTenantId() { return tenantId; }
                    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
                    public Instant getCreatedAt() { return createdAt; }
                    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
                    public Instant getUpdatedAt() { return updatedAt; }
                    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
                    public boolean isDeleted() { return deleted; }
                    public void setDeleted(boolean deleted) { this.deleted = deleted; }
                    public Long getVersion() { return version; }
                    public void setVersion(Long version) { this.version = version; }
                }
                """);
    }

    @Test
    @DisplayName("J2a: an entity carrying @Graph, @GraphProperty and @UIGroup compiles end to end")
    void entityWithGraphPropertiesAndUiGroupsCompilesEndToEnd(@TempDir Path workspace) throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        Path generatedTests = workspace.resolve("src/test/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, graphUiGroupCorpus());

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, "com.knowledge");
        pipeline.runTests(metadataDir, generatedTests, "com.knowledge");

        Path metadataFile = metadataDir.resolve("Article.json");
        assertThat(metadataFile).exists();
        String json = Files.readString(metadataFile);
        assertThat(json)
                .contains("\"graphMetadata\"")
                .contains("\"properties\"")
                .contains("\"edges\"")
                .contains("\"uiMetadata\"")
                .contains("\"groups\"");

        Path graphSyncFile = generatedMain.resolve("com/knowledge/graph/ArticleGraphSync.java");
        assertThat(graphSyncFile).exists();
        String graphSyncContent = Files.readString(graphSyncFile);
        assertThat(graphSyncContent)
                .contains("AUTHOR_ID_EDGE")
                .contains("RELATED_ARTICLE_ID_REFERENCES_EDGE")
                .contains("RELATED_ARTICLE_ID_SIMILAR_TO_EDGE")
                .contains("TOPIC_ID_EDGE")
                .contains("allocator.allocateNetwork(bytes.length)");

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

    private static Map<String, String> graphUiGroupCorpus() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/knowledge/domain/Article.java",
                """
                package com.knowledge.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Graph;
                import eu.exeris.sdk.annotation.GraphEdge;
                import eu.exeris.sdk.annotation.GraphProperty;
                import eu.exeris.sdk.annotation.UI;
                import eu.exeris.sdk.annotation.UIGroup;

                import java.util.UUID;

                @ExerisDomain(module = "knowledge", path = "/articles")
                @Graph(nodeClass = "Article")
                @UI
                public class Article {

                    private UUID id;

                    @Field(label = "Title", required = true, sortable = true, filterable = true)
                    @GraphProperty(indexed = true)
                    @UIGroup(name = "content", label = "Content Details", order = 1)
                    private String title;

                    @Field(label = "Slug", required = true, unique = true)
                    @GraphProperty(name = "article_slug", graphType = GraphProperty.GraphType.STRING)
                    @UIGroup(name = "content")
                    private String slug;

                    @Field(label = "Summary")
                    @GraphProperty(graphType = GraphProperty.GraphType.AUTO)
                    @UIGroup(name = "meta", label = "Metadata", order = 2, collapsible = true, collapsed = false)
                    private String summary;

                    @Field(label = "Author ID")
                    @GraphEdge(type = "WRITTEN_BY", targetLabel = "Author", direction = GraphEdge.Direction.OUTGOING)
                    private UUID authorId;

                    @Field(label = "Related Article ID")
                    @GraphEdge(type = "REFERENCES", targetLabel = "Article", staticWeight = 2.5)
                    @GraphEdge(type = "SIMILAR_TO", targetLabel = "Article", bidirectional = true)
                    private UUID relatedArticleId;

                    @Field(label = "Topic ID")
                    @GraphEdge(type = "TAGGED_WITH", targetLabel = "Topic", weighted = true, weightField = "importance")
                    private UUID topicId;

                    @Field(label = "Importance")
                    private double importance;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public String getSlug() { return slug; }
                    public void setSlug(String slug) { this.slug = slug; }
                    public String getSummary() { return summary; }
                    public void setSummary(String summary) { this.summary = summary; }
                    public UUID getAuthorId() { return authorId; }
                    public void setAuthorId(UUID authorId) { this.authorId = authorId; }
                    public UUID getRelatedArticleId() { return relatedArticleId; }
                    public void setRelatedArticleId(UUID relatedArticleId) { this.relatedArticleId = relatedArticleId; }
                    public UUID getTopicId() { return topicId; }
                    public void setTopicId(UUID topicId) { this.topicId = topicId; }
                    public double getImportance() { return importance; }
                    public void setImportance(double importance) { this.importance = importance; }
                }
                """);
        return sources;
    }

    /**
     * A {@code List} of entities that is no resolvable inverse collection is read from no table: a
     * {@code ONE_TO_MANY} without {@code mappedBy}, and one whose {@code mappedBy} names no
     * {@code MANY_TO_ONE} back to the owner. The run is refused with {@code EXT-GEN-3003} before
     * anything is written, naming the remedy.
     */
    @Test
    @DisplayName("EXT-GEN-3003: a List of entities that is no resolvable inverse is refused through the real chain, "
            + "nothing written")
    void unresolvableEntityCollectionIsRefused(@TempDir Path workspace) throws IOException {
        String field = "com.shop.domain.Invoice.lines : java.util.List<com.shop.domain.InvoiceLine> (";
        assertCollectionRefused(workspace.resolve("no-mapped-by"),
                "relationshipType = Relationship.RelationshipType.ONE_TO_MANY",
                field + "a collection of the entity InvoiceLine, which no column stores; read it from InvoiceLine's"
                        + " rows with @Relationship(targetEntity = InvoiceLine.class, relationshipType = ONE_TO_MANY,"
                        + " mappedBy = \"invoiceId\"), or hold their keys: List<UUID> invoiceLineIds)");
        assertCollectionRefused(workspace.resolve("dangling"),
                "relationshipType = Relationship.RelationshipType.ONE_TO_MANY, mappedBy = \"order\"",
                field + "mappedBy \"order\" names no MANY_TO_ONE of InvoiceLine that references Invoice; declare"
                        + " @Relationship(targetEntity = Invoice.class) UUID invoiceId on InvoiceLine)");
    }

    private static void assertCollectionRefused(Path workspace, String linesRelationship, String expected)
            throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null,
                entityReferenceCorpus(true, linesRelationship));

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        assertThatThrownBy(() -> pipeline.run(metadataDir, generatedMain, "com.shop"))
                .isInstanceOfSatisfying(UnpersistableFieldTypeException.class,
                        e -> assertThat(e.fields()).containsExactly(expected))
                .hasMessageStartingWith("[Exeris] EXT-GEN-3003: ");
        assertThat(generatedMain).doesNotExist();
    }

    /**
     * A {@code @GraphEdge} on a {@code List} of entities holds no key the graph sync can write, so
     * the run is refused with {@code EXT-GEN-3003} before anything is written, naming the
     * {@code List<UUID>} to hold instead.
     */
    @Test
    @DisplayName("EXT-GEN-3003: a @GraphEdge on a List of entities is refused through the real chain, nothing written")
    void collectionEdgeOfEntitiesIsRefused(@TempDir Path workspace) throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, Map.of(
                "com/catalog/domain/Product.java", """
                        package com.catalog.domain;

                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.Field;
                        import eu.exeris.sdk.annotation.Graph;
                        import eu.exeris.sdk.annotation.GraphEdge;

                        import java.util.List;
                        import java.util.UUID;

                        @ExerisDomain(module = "catalog", path = "/products")
                        @Graph(nodeClass = "Product")
                        public class Product {

                            private UUID id;

                            @Field(label = "Title", required = true)
                            private String title;

                            @GraphEdge(type = "RELATED_TO", target = Product.class)
                            private List<Product> related;

                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }
                            public String getTitle() { return title; }
                            public void setTitle(String title) { this.title = title; }
                            public List<Product> getRelated() { return related; }
                            public void setRelated(List<Product> related) { this.related = related; }
                        }
                        """));

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        assertThatThrownBy(() -> pipeline.run(metadataDir, generatedMain, "com.catalog"))
                .isInstanceOfSatisfying(UnpersistableFieldTypeException.class, e -> assertThat(e.fields())
                        .containsExactly("com.catalog.domain.Product.related : java.util.List<com.catalog.domain"
                                + ".Product> (a collection graph edge to the entity Product, which no edge stores;"
                                + " hold the targets' keys instead: List<UUID> relatedIds)"))
                .hasMessageStartingWith("[Exeris] EXT-GEN-3003: ");
        assertThat(generatedMain).doesNotExist();
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

                    public static final String DEFAULT_STATUS = "ISSUED";
                    private static final String SYSTEM_TAG = "BILLING";
                    private transient String sessionToken;

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
        sources.put("com/billing/domain/Receipt.java",
                """
                package com.billing.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.system.PrimaryKey;

                import java.util.UUID;

                @ExerisDomain(module = "billing", path = "/receipts")
                public class Receipt {

                    @PrimaryKey
                    private UUID receiptNo;

                    @Field(label = "Merchant", required = true, sortable = true, filterable = true)
                    private String merchant;

                    public UUID getReceiptNo() { return receiptNo; }
                    public void setReceiptNo(UUID receiptNo) { this.receiptNo = receiptNo; }
                    public String getMerchant() { return merchant; }
                    public void setMerchant(String merchant) { this.merchant = merchant; }
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

    @Test
    @DisplayName("an entity with @Action carrying @QueryParam parameters compiles cleanly through processor and codegen")
    void actionWithQueryParamCorpusCompilesClean(@TempDir Path workspace) throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, queryActionCorpus());

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, "com.catalog");

        Path handlerPath = generatedMain.resolve("com/catalog/handler/ItemHandler.java");
        assertThat(Files.readString(handlerPath))
                .contains("queryParam(rawQuery, \"q\")")
                .contains("queryParam(rawQuery, \"limit\")")
                .contains("Integer.parseInt(raw_limit)")
                .contains("String raw_query = queryParam(rawQuery, \"query\");")
                .contains("query_grade = raw_grade.charAt(0);")
                .contains("query_since = Instant.parse(raw_since);");

        List<String> files = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(generatedMain)) {
            tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().forEach(files::add);
        }
        EmittedJavac.Result result = EmittedJavac.compile(files, workspace.resolve("target/app-classes"),
                System.getProperty("java.class.path") + File.pathSeparator + entityClasses);
        assertThat(result.clean())
                .as("javac output:%n%s", result.render())
                .isTrue();
    }

    private static Map<String, String> queryActionCorpus() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/catalog/domain/Item.java",
                """
                package com.catalog.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.ActionParam;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.QueryParam;

                import java.util.UUID;

                @ExerisDomain(module = "catalog", path = "/items")
                public class Item {

                    private UUID id;

                    @Field(label = "Title")
                    private String title;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }

                    @Action(name = "search", label = "Search items")
                    public void search(
                            @QueryParam("q") String term,
                            @QueryParam(value = "limit", required = false, defaultValue = "20") int limit) {
                    }

                    @Action(name = "lookup", label = "Look up")
                    public void lookup(
                            @QueryParam("query") String query,
                            @QueryParam("params") String params,
                            @QueryParam(value = "grade", required = false) char grade,
                            @QueryParam(value = "since", required = false) java.time.Instant since,
                            @QueryParam(value = "at", required = false) java.time.LocalDateTime at) {
                    }

                    @Action(name = "filter", label = "Filter and tag")
                    public void filter(
                            @QueryParam("category") String category,
                            @ActionParam(label = "Note") String note) {
                    }
                }
                """);
        return sources;
    }
}
