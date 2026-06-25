package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-generator test for {@link KernelHandlerGenerator}.
 *
 * <p>Goes through {@link KernelGeneratorStrategy} so the test mirrors the
 * way the generator is actually invoked in production (registry-driven,
 * not direct).
 */
@DisplayName("KernelHandlerGenerator")
class KernelHandlerGeneratorTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

    @Test
    @DisplayName("Should generate Handler emitting against Open-Core SPI HttpExchange")
    void shouldGenerateHandler() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        GeneratedFile handler = files.stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst()
                .orElseThrow();

        assertThat(handler.className()).isEqualTo("OrderHandler");
        assertThat(handler.packageName()).isEqualTo("com.example.handler");
        assertThat(handler.content())
                .contains("public class OrderHandler")
                .contains("import eu.exeris.kernel.spi.http.HttpExchange")
                .contains("import eu.exeris.kernel.spi.http.HttpStatus")
                .contains("import eu.exeris.kernel.spi.memory.LoanedBuffer")
                .contains("OrderService service")
                .contains("handleGetAll(HttpExchange exchange)")
                .contains("handleGetById(HttpExchange exchange)")
                .contains("handleCreate(HttpExchange exchange)")
                .contains("handleUpdate(HttpExchange exchange)")
                .contains("handleDelete(HttpExchange exchange)")
                .contains("exchange.respond(HttpStatus.OK")
                .contains("HttpStatus.CREATED")
                .contains("HttpStatus.NO_CONTENT")
                .contains("HttpStatus.BAD_REQUEST")
                .contains("HttpStatus.NOT_FOUND")
                .contains("HttpStatus.INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("Request body decode resolves the ADR-036 SPI registry, not an inline Jackson MAPPER")
    void shouldDecodeRequestBodyViaRequestBodyDecoderSpi() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        GeneratedFile handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst()
                .orElseThrow();

        assertThat(handler.content())
                // ADR-036: request body decode resolves through the SPI registry …
                .contains("import eu.exeris.kernel.spi.http.HttpRequestBodyDecoder")
                .contains("import eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry")
                .contains("import eu.exeris.kernel.spi.http.HttpRequestDecodingContext")
                .contains("import eu.exeris.kernel.spi.http.HttpKernelProviders")
                .contains("import eu.exeris.kernel.spi.context.KernelProviders")
                .contains("httpRequestBodyDecoderRegistry()")
                .contains("registry.resolve(type, contentType)")
                .contains("decoder.decode(body, type, context)")
                .contains("firstHeader(\"content-type\")")
                // … hands the decoder the LoanedBuffer + a fresh decoding context …
                .contains("exchange.request().hasBody()")
                .contains("new HttpRequestDecodingContext(")
                .contains("KernelProviders.MEMORY_ALLOCATOR.get()")
                // … and consumes the LoanedBuffer directly — no byte[]/String round-trip.
                .doesNotContain("new String(")
                .doesNotContain("MemorySegment.copy");
        // The Wall: no concrete Jackson type may be baked into generated application source.
        assertThat(handler.content())
                .doesNotContain("tools.jackson")
                .doesNotContain("ObjectMapper")
                .doesNotContain("MAPPER");
    }

    @Test
    @DisplayName("parseBody guards resolve/decode in one try; 5xx (IllegalState) re-thrown, only decode failures map to 400 (ADR-036 §2)")
    void shouldPreserveStatusMappingAcrossResolveAndDecode() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst()
                .orElseThrow()
                .content();

        // Blocker fix: registry.resolve + context construction + decode are all inside
        // the same try, so a resolve-time RuntimeException cannot escape parseBody
        // unmapped. The IllegalStateException catch re-throws unchanged so the
        // intentional 5xx mappings (unbound registry / unregistered decoder) are NOT
        // downgraded to 400; everything else becomes a 400 IllegalArgumentException.
        assertThat(handler)
                .contains("catch (IllegalStateException e)")
                .contains("throw e;")
                .contains("catch (RuntimeException e)")
                .contains("throw new IllegalArgumentException(\"Invalid request body\", e)")
                // resolve sits ABOVE the IllegalState re-throw, i.e. inside the guarded try
                .containsSubsequence(
                        "registry.resolve(type, contentType)",
                        "catch (IllegalStateException e)",
                        "throw e;",
                        "catch (RuntimeException e)");
        // null content-type renders a friendly token in the unresolved-decoder message.
        assertThat(handler).contains("contentType != null ? contentType : \"(absent)\"");
    }

    @Test
    @DisplayName("T1: serves @Action — loads aggregate, invokes the entity method, responds with updated entity")
    void shouldGenerateActionHandlers() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        // action identity (name) differs from the JVM method — the
                        // handler must invoke the methodName, not the name.
                        ActionMetadata.builder("markUrgent").methodName("flagUrgent").build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                .contains("void handleCancel(HttpExchange exchange)")
                .contains("extractActionPathId(exchange)")
                .contains("service.findById(id)")
                .contains("exchange.respond(HttpStatus.NOT_FOUND)")
                .contains("entity.cancel()")
                .contains("service.update(id, entity)")
                .contains("exchange.respond(HttpStatus.OK, updated)")
                // id is extracted via the action-aware helper (segment before /actions/)
                .contains("\"/actions/\"")
                // name != method: handler name follows the action identity, invocation the method
                .contains("void handleMarkUrgent(HttpExchange exchange)")
                .contains("entity.flagUrgent()");
    }

    @Test
    @DisplayName("T1: @Action with @ActionParams decodes a generated request record and passes the args")
    void shouldGenerateActionHandlerWithParams() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("applyDiscount").methodName("applyDiscount")
                                .params(List.of(
                                        ActionParamMetadata.required("percent", "java.math.BigDecimal"),
                                        ActionParamMetadata.required("reason", "java.lang.String")))
                                .build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                .contains("record ApplyDiscountRequest(")
                .contains("BigDecimal percent")
                .contains("String reason")
                .contains("parseBody(exchange, ApplyDiscountRequest.class)")
                .contains("entity.applyDiscount(request.percent(), request.reason())");
    }

    @Test
    @DisplayName("ADR-044 Slice 2: a @Action(streaming) action gets NO respond-once handle<Action> "
            + "(served by the per-action stream handler via streamRoute); non-streaming siblings still do")
    void shouldNotEmitRespondOnceHandlerForStreamingAction() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("trackShipment").methodName("trackShipment")
                                .streaming(true).streamEventType("ShipmentMoved").build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                // non-streaming action keeps its respond-once handler + the shared helper
                .contains("void handleCancel(HttpExchange exchange)")
                .contains("extractActionPathId(exchange)")
                // streaming action is served by the stream handler, not a dead respond-once method
                .doesNotContain("handleTrackShipment");
    }

    @Test
    @DisplayName("ADR-044 Slice 2: an entity whose ONLY action streams emits neither a respond-once "
            + "action handler nor the now-unused extractActionPathId helper")
    void shouldOmitActionHelperWhenAllActionsStream() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("trackShipment").methodName("trackShipment")
                                .streaming(true).streamEventType("ShipmentMoved").build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                .doesNotContain("handleTrackShipment")
                .doesNotContain("extractActionPathId");
    }

    @Test
    @DisplayName("T10: enforces @Validation server-side in create/update — 400 before persist, parity with the client Zod schema")
    void shouldEnforceValidationServerSide() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String")
                                .required(true).minLength(3).maxLength(20).pattern("[A-Z0-9-]+").build(),
                        FieldMetadata.builder("amount", "BigDecimal")
                                .required(true).min(0L).max(1000L).build(),
                        FieldMetadata.builder("weight", "Long")
                                .min(1L).build(),
                        FieldMetadata.builder("quantity", "int")
                                .min(1L).build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                // value read once into a prefixed local (T22 — collision-proof), then checked
                .contains("var valOrderNumber = entity.getOrderNumber()")
                // required → not-null on a reference type
                .contains("if (valOrderNumber == null)")
                // String length + pattern (null-guarded)
                .contains("valOrderNumber != null && valOrderNumber.length() < 3")
                .contains("valOrderNumber != null && valOrderNumber.length() > 20")
                .contains("!valOrderNumber.matches(\"[A-Z0-9-]+\")")
                // BigDecimal min/max via compareTo
                .contains("valAmount.compareTo(BigDecimal.valueOf(0L)) < 0")
                .contains("valAmount.compareTo(BigDecimal.valueOf(1000L)) > 0")
                // boxed numeric → null-guarded direct comparison
                .contains("valWeight != null && valWeight < 1L")
                // primitive numeric → direct comparison, no null guard
                .contains("valQuantity < 1L")
                .doesNotContain("valQuantity != null")
                // rejects with 400, and the guard precedes BOTH service calls
                // (create AND update each emit it before persisting)
                .contains("exchange.respond(HttpStatus.BAD_REQUEST)")
                .containsSubsequence(
                        "var valOrderNumber = entity.getOrderNumber()",
                        "service.save(entity)",
                        "var valOrderNumber = entity.getOrderNumber()",
                        "service.update(id, entity)");
    }

    @Test
    @DisplayName("T22: a validated field whose name collides with a handler-scope var (id) gets a "
            + "prefixed local — no `var id` clash with handleUpdate's path-id")
    void shouldPrefixValidationLocalToAvoidPathIdCollision() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                // a validated field literally named `id` — the exact T22 collision
                .fields(List.of(FieldMetadata.builder("id", "java.util.UUID").required(true).build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                // the validation local is prefixed; the bare `id` stays the path-id only
                .contains("var valId = entity.getId()")
                .contains("if (valId == null)")
                .doesNotContain("var id = entity.getId()");
    }

    @Test
    @DisplayName("T10: a primitive required field emits no null-check (a primitive can't be null)")
    void shouldNotNullCheckPrimitiveRequired() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.builder("quantity", "int").required(true).build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        // A primitive field with only `required` has no emittable check, so no
        // local read and no null comparison are generated for it at all.
        assertThat(handler)
                .doesNotContain("quantity == null")
                .doesNotContain("var quantity = entity.getQuantity()");
    }
}
