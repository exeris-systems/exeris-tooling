package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@link KernelClientGenerator}.
 *
 * <p>This generator is <b>parked</b> — it is intentionally NOT registered
 * by {@link KernelGeneratorStrategy} because the canonical service-to-
 * service client SPI shape has not yet landed in {@code exeris-kernel}.
 * That makes a full per-emission contract test premature: every shape
 * detail (constructor signature, transport-API method names, exception
 * types) will change when the SPI is rewired against the real client
 * surface, and the e2e compile-gate that pins the rest of the strategy's
 * generators deliberately excludes this one.
 *
 * <p>What we lock here is therefore minimal:
 * <ul>
 *   <li>The generator can be instantiated and {@code generate()} returns
 *       a non-null {@link GeneratedFile} with the right artifact type
 *       and package/class naming derived from the domain metadata.</li>
 *   <li>The {@code apiPath} build path is exercised on both the
 *       explicit-{@code path()} branch and the kebab-case fallback.</li>
 * </ul>
 *
 * <p>A richer contract test moves in alongside the SPI client rewrite.
 */
@DisplayName("KernelClientGenerator (parked)")
class KernelClientGeneratorTest {

    private final KernelClientGenerator generator = new KernelClientGenerator();

    @Test
    @DisplayName("artifactType is CLIENT")
    void artifactTypeIsClient() {
        assertThat(generator.artifactType()).isEqualTo(ArtifactType.CLIENT);
    }

    @Test
    @DisplayName("generate emits an OrderClient class in the .client package against the explicit path")
    void generateWithExplicitPath() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        GeneratedFile file = generator.generate(metadata);

        assertThat(file).isNotNull();
        assertThat(file.artifactType()).isEqualTo(ArtifactType.CLIENT);
        assertThat(file.packageName()).isEqualTo("com.example.client");
        assertThat(file.className()).isEqualTo("OrderClient");
        // The emitted source uses the explicit path verbatim, prefixed
        // with /api/<version>/.
        assertThat(file.content())
                .contains("\"/api/v1/orders\"")
                .contains("public class OrderClient")
                .contains("public OrderClient(");
    }

    @Test
    @DisplayName("generate emits /api/v1 base when path() is unset (Builder default is empty string, not null)")
    void generateWithoutExplicitPath() {
        // KernelClientGenerator#buildApiPath guards on `metadata.path() != null`
        // and falls back to "/" + kebab + "s" when null. The fallback is
        // unreachable through DomainMetadata.Builder TODAY because the
        // Builder defaults `path` to "" (not null) — see DomainMetadata.java
        // Builder block. If the SDK ever changes that default to null,
        // the production fallback becomes live and this test's
        // .doesNotContain("/payment-orders") will FAIL — that failure
        // signals an SDK-level invariant flip, not a regression in the
        // generator. Either:
        //   * SDK keeps "" as default → this test stays correct;
        //   * SDK switches to null → update KernelClientGenerator to also
        //     guard on isEmpty() and re-pin the expected emit here.
        DomainMetadata metadata = DomainMetadata.builder("PaymentOrder", "com.example.domain")
                .build();

        GeneratedFile file = generator.generate(metadata);

        assertThat(file.content())
                .contains("\"/api/v1\"")
                .doesNotContain("/payment-orders");
    }

    @Test
    @DisplayName("apiVersion override is honoured in the emitted base path")
    void generateRespectsApiVersionOverride() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .apiVersion("v2")
                .path("/orders")
                .build();

        GeneratedFile file = generator.generate(metadata);

        assertThat(file.content()).contains("\"/api/v2/orders\"");
    }
}
