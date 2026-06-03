package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
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
}
