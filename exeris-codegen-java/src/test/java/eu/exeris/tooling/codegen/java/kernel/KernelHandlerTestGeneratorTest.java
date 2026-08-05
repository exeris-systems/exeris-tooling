package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-generator test for {@link KernelHandlerTestGenerator} (T2, ADR-058).
 *
 * <p>Shape only. That the emitted tests <em>compile and pass</em> against the emitted handler is
 * proven end-to-end by {@code GeneratedTestsE2ETest}, which is the assertion that actually matters
 * for a test emitter — substring checks here would happily accept a test that never runs.
 */
@DisplayName("KernelHandlerTestGenerator")
class KernelHandlerTestGeneratorTest {

    private static final DomainMetadata ORDER =
            DomainMetadata.builder("Order", "com.example.domain").path("/orders").build();

    /** One field per rule kind that carries a boundary, plus a required one that does not. */
    private static final DomainMetadata VALIDATED =
            DomainMetadata.builder("Order", "com.example.domain").path("/orders")
                    .fields(java.util.List.of(
                            FieldMetadata.builder("orderNumber", "String")
                                    .required(true).minLength(3).maxLength(8).build(),
                            FieldMetadata.builder("quantity", "int").min(1L).max(99L).build()))
                    .build();

    @Test
    @DisplayName("emits <Entity>HandlerTest into the handler package, typed as a TEST artefact")
    void emitsHandlerTest() {
        GeneratedFile file = new KernelHandlerTestGenerator().generate(ORDER, "com.example");

        assertThat(file.className()).isEqualTo("OrderHandlerTest");
        assertThat(file.packageName()).isEqualTo("com.example.handler");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.TEST);
    }

    @Test
    @DisplayName("covers the bodyless routes, including both handleGetById branches and the id guard")
    void coversTheBodylessRoutes() {
        String source = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        assertThat(source)
                .contains("handler.handleGetAll(exchange)")
                .contains("HttpStatus.OK")
                .contains("HttpStatus.NOT_FOUND")
                .contains("HttpStatus.BAD_REQUEST")
                .contains("handler.handleDelete(exchange)")
                .contains("HttpStatus.NO_CONTENT");
    }

    @Test
    @DisplayName("covers the body-carrying routes' guard paths, which reject before the body is read")
    void coversTheBodyRouteGuards() {
        String source = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        assertThat(source)
                .contains("handler.handleCreate(exchange)")
                .contains("handler.handleUpdate(exchange)")
                // Bodyless POST/PUT exchanges: parseBody rejects on hasBody() before resolving a
                // decoder, so no request-body double is needed to reach BAD_REQUEST.
                .contains("RecordingHttpExchange.post(")
                .contains("RecordingHttpExchange.put(")
                // Each guard test proves the short-circuit, not just the status.
                .contains("assertThat(service.saved).isNull()")
                .contains("assertThat(service.updatedId).isNull()");
    }

    @Test
    @DisplayName("an entity with no @Validation rule binds no provider slot at all")
    void bindsNoKernelProviderSlotWithoutRules() {
        String source = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        // Binding a ScopedValue is only justified by the guards it lets a test reach. With no
        // rules there are none, so the emitted test stays a plain in-process unit test.
        assertThat(source)
                .doesNotContain("ScopedValue")
                .doesNotContain("KernelProviders")
                .doesNotContain("RecordingRequestBody");
    }

    @Test
    @DisplayName("every bounded rule gets BOTH a reject outside it and an accept sitting on it")
    void coversBothSidesOfEveryBoundary() {
        String source = new KernelHandlerTestGenerator().generate(VALIDATED, "com.example").content();

        // The reject alone would survive an emitter that wrote <= where it meant < — the rule and
        // the probe value come from the same metadata. The accept on the boundary is what pins
        // inclusiveness, so neither may be emitted without the other.
        assertThat(source)
                .contains("void handleCreateRejectsOrderNumberShorterThanMinLength()")
                .contains("void handleCreateAcceptsOrderNumberAtMinLength()")
                .contains("void handleCreateRejectsOrderNumberLongerThanMaxLength()")
                .contains("void handleCreateAcceptsOrderNumberAtMaxLength()")
                .contains("void handleCreateRejectsQuantityBelowMin()")
                .contains("void handleCreateAcceptsQuantityAtMin()")
                .contains("void handleCreateRejectsQuantityAboveMax()")
                .contains("void handleCreateAcceptsQuantityAtMax()")
                .contains("void handleCreateRejectsOrderNumberWhenNull()")
                .contains("void handleUpdateRunsTheSameValidationGuard()");
    }

    @Test
    @DisplayName("the probe values sit exactly one step off each bound, and the accepts sit on it")
    void probesSitOnTheBoundary() {
        String source = new KernelHandlerTestGenerator().generate(VALIDATED, "com.example").content();

        assertThat(source)
                .contains("decoded.setOrderNumber(\"aa\")")        // minLength 3 - 1
                .contains("decoded.setOrderNumber(\"aaa\")")       // minLength 3, inclusive
                .contains("decoded.setOrderNumber(\"aaaaaaaaa\")") // maxLength 8 + 1
                .contains("decoded.setOrderNumber(\"aaaaaaaa\")")  // maxLength 8, inclusive
                .contains("decoded.setQuantity(0)")               // min 1 - 1
                .contains("decoded.setQuantity(1)")               // min 1, inclusive
                .contains("decoded.setQuantity(100)")             // max 99 + 1
                .contains("decoded.setQuantity(99)");             // max 99, inclusive
    }

    @Test
    @DisplayName("the accept case asserts CREATED — the one status no mis-wiring can fake")
    void theAcceptCaseIsTheWiringCanary() {
        String source = new KernelHandlerTestGenerator().generate(VALIDATED, "com.example").content();

        // Every failure past the body guard — unbound registry, unbound allocator, a decode that
        // throws — answers 400, the same status a rejection does. So a reject-only suite would go
        // green having never reached a validation guard. CREATED cannot be produced that way.
        assertThat(source)
                .contains("void handleCreateRespondsCreatedWhenEveryRuleIsSatisfied()")
                .contains("HttpStatus.CREATED")
                .contains("assertThat(service.saved).isSameAs(decoded)")
                .contains("assertThat(body.decodedType).isEqualTo(Order.class)");
    }

    @Test
    @DisplayName("both provider slots are bound — the allocator is not optional")
    void bindsBothProviderSlots() {
        String source = new KernelHandlerTestGenerator().generate(VALIDATED, "com.example").content();

        // HttpRequestDecodingContext rejects a null allocator, and parseBody fills that slot from
        // an unbound ScopedValue if nobody binds it — which throws inside the try that maps
        // everything to 400. Dropping this line turns every reject case green for free.
        assertThat(source)
                .contains("ScopedValue.where(HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)")
                .contains(".where(KernelProviders.MEMORY_ALLOCATOR, body)")
                .contains("RecordingHttpExchange.post(\"/orders\", body)");
    }

    @Test
    @DisplayName("the handleUpdate case survives a leading field whose rules yield no reject")
    void theUpdateCaseDoesNotDependOnDeclarationOrder() {
        // Several rule kinds yield no probe at all — a pattern always, and a minLength of 0 has
        // no value below it. Anchoring the handleUpdate case on the first field's first rule
        // would make its existence depend on which field happens to be declared first.
        DomainMetadata leadingBlank = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(java.util.List.of(
                        FieldMetadata.builder("note", "String").minLength(0).build(),
                        FieldMetadata.builder("quantity", "int").min(1L).build()))
                .build();

        assertThat(new KernelHandlerTestGenerator().generate(leadingBlank, "com.example").content())
                .contains("void handleUpdateRunsTheSameValidationGuard()")
                .contains("handler.handleUpdate(exchange)");
    }

    @Test
    @DisplayName("a required field constrained by a pattern suppresses the cases entirely")
    void aRequiredPatternFieldSuppressesTheCases() {
        // A regex has no synthesizable member, so no valid baseline exists — and without one,
        // every other field's rejection case would be answered by THIS field instead and pass for
        // the wrong reason. Emitting nothing is the honest outcome.
        DomainMetadata patterned = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(java.util.List.of(
                        FieldMetadata.builder("code", "String").required(true)
                                .pattern("^[A-Z]{3}$").build(),
                        FieldMetadata.builder("quantity", "int").min(1L).build()))
                .build();

        assertThat(new KernelHandlerTestGenerator().generate(patterned, "com.example").content())
                .doesNotContain("handleCreateRejectsQuantityBelowMin")
                .doesNotContain("ScopedValue");
    }

    @Test
    @DisplayName("the dependency contract holds: JUnit + AssertJ only, no mocking framework")
    void importsOnlyTheContractDependencies() {
        String source = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        assertThat(source)
                .contains("import org.junit.jupiter.api.Test;")
                .contains("import org.assertj.core.api.Assertions;")
                .contains("import com.example.testsupport.RecordingHttpExchange;");
        assertThat(source)
                .doesNotContain("org.mockito")
                .doesNotContain("org.easymock")
                .doesNotContain("Mockito.");
    }

    @Test
    @DisplayName("the service double subclasses the generated service — no persistence stack involved")
    void stubsTheServiceBySubclassing() {
        String source = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        assertThat(source)
                .contains("static final class StubOrderService extends OrderService")
                .contains("super((OrderRepository) null)")
                .contains("public List<Order> findAll()")
                .contains("public Optional<Order> findById(UUID id)")
                .contains("public void delete(UUID id)");
    }

    @Test
    @DisplayName("emission is deterministic — byte-identical across runs (no random UUID literal)")
    void emissionIsDeterministic() {
        String first = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();
        String second = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotContain("UUID.randomUUID()");
    }

    @Test
    @DisplayName("rejects a domain package that does not end with '.domain'")
    void rejectsNonDomainPackage() {
        DomainMetadata bad = DomainMetadata.builder("Order", "com.example.order").build();
        KernelHandlerTestGenerator generator = new KernelHandlerTestGenerator();

        assertThatThrownBy(() -> generator.generate(bad, "com.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.example.order")
                .hasMessageContaining(".domain");
    }
}
