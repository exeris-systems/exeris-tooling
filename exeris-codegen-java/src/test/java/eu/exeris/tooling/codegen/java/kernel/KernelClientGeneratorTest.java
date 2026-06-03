package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link KernelClientGenerator}.
 *
 * <p>This generator is <b>registered</b> by {@link KernelGeneratorStrategy}.
 * It emits a typed service-to-service HTTP client that binds against the
 * tier-neutral {@code KernelWebClient} facade in
 * {@code eu.exeris.kernel.core.http.client} (ADR-034). The facade exposes the
 * entity-typed convenience verbs the generator targets
 * ({@code get/post/patch/delete(path, [body,] Class<T>)}), so no tooling-side
 * {@code HttpEntityCodec} collaborator is required — see the
 * {@link KernelGeneratorStrategy} Javadoc for the unpark rationale.
 *
 * <p>What we lock here:
 * <ul>
 *   <li>artifact type {@code CLIENT}, package/class naming, and the
 *       {@code apiPath} build (explicit-{@code path()} branch + apiVersion
 *       override);</li>
 *   <li>the emitted CRUD verb surface ({@code client.get/post/patch/delete})
 *       and the {@code 404 → Optional.empty()} mapping via
 *       {@code WebClientException.isNotFound()};</li>
 *   <li>the ADR-034 binding target FQN (regression pin).</li>
 * </ul>
 *
 * <p>The compile gate ({@code KernelCodegenCompileTest}) additionally proves
 * the emitted client {@code javac}-compiles against the {@code KernelWebClient}
 * surface; this class pins the emission shape.
 */
@DisplayName("KernelClientGenerator")
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
        // Pin the ADR-034 binding target. The generator targets the
        // tier-neutral KernelWebClient facade (not the legacy
        // ExerisWebClient under transport.http3.client). JavaPoet emits
        // this as an import; a regression on either constant in
        // KernelClientGenerator surfaces here.
        assertThat(file.content())
                .contains("import eu.exeris.kernel.core.http.client.KernelWebClient;")
                .doesNotContain("eu.exeris.kernel.transport.http3.client");
    }

    @Test
    @DisplayName("generate derives /api/v1/<kebab>s base via SDK effectivePath() when path() is unset")
    void generateWithoutExplicitPath() {
        // buildApiPath delegates to DomainMetadata#effectivePath(), the SDK-canonical
        // derivation (explicit path, else "/" + kebab + "s"). With no explicit path,
        // a "PaymentOrder" entity resolves to /api/v1/payment-orders — consistent with
        // the OpenAPI / Application / DSL generators, which all use effectivePath().
        DomainMetadata metadata = DomainMetadata.builder("PaymentOrder", "com.example.domain")
                .build();

        GeneratedFile file = generator.generate(metadata);

        assertThat(file.content())
                .contains("\"/api/v1/payment-orders\"");
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

    @Test
    @DisplayName("emits the KernelWebClient CRUD verb surface (get/post/patch/delete) with typed Class<T> args")
    void generateEmitsTypedVerbSurface() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        GeneratedFile file = generator.generate(metadata);

        assertThat(file.content())
                .contains("client.get(BASE_PATH + \"/\" + id, Order.class)")
                .contains("client.post(BASE_PATH, entity, Order.class)")
                .contains("client.patch(BASE_PATH + \"/\" + id, entity, Order.class)")
                .contains("client.delete(BASE_PATH + \"/\" + id, Void.class)");
    }

    @Test
    @DisplayName("findById maps a 404 to Optional.empty() via WebClientException.isNotFound()")
    void generateMapsNotFoundToEmpty() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        GeneratedFile file = generator.generate(metadata);

        // The findById body wraps the typed GET in a try/catch that converts a
        // 404 into Optional.empty() and rethrows any other WebClientException.
        assertThat(file.content())
                .contains("Optional.ofNullable(client.get(BASE_PATH + \"/\" + id, Order.class))")
                .contains("catch (KernelWebClient.WebClientException e)")
                .contains("if (e.isNotFound())")
                .contains("return Optional.empty()");
    }
}
