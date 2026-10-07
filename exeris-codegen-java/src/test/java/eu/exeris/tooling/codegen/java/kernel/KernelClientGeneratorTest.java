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
 * ({@code get/post/put/delete(path, [body,] Class<T>)}), so no tooling-side
 * {@code HttpEntityCodec} collaborator is required — see the
 * {@link KernelGeneratorStrategy} Javadoc for the unpark rationale.
 *
 * <p>What we lock here:
 * <ul>
 *   <li>artifact type {@code CLIENT}, package/class naming, and the
 *       {@code apiPath} build (explicit-{@code path()} branch + apiVersion
 *       override);</li>
 *   <li>the emitted CRUD verb surface ({@code client.get/post/put/delete})
 *       and the {@code 404 → Optional.empty()} mapping via
 *       {@code WebClientException.isNotFound()};</li>
 *   <li>the ADR-034 binding target FQN (regression pin);</li>
 *   <li>the ADR-045 composition-root retry wiring example — Javadoc-only,
 *       so no {@code eu.exeris.kernel.community.*} import couples the
 *       compiled surface (The Wall);</li>
 *   <li>the kernel ADR-074 peer-addressing guidance — also Javadoc-only,
 *       so {@code HttpConfig} never becomes an import the compiled client does
 *       not need.</li>
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
                .contains("\"/orders\"")
                .doesNotContain("/api/")
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
    @DisplayName("the code example in the emitted Javadoc is a {@snippet} block")
    void emittedJavadocExampleIsASnippet() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        assertThat(generator.generate(metadata).content())
                .contains("{@snippet :")
                .doesNotContain("<pre>")
                .doesNotContain("</pre>");
    }

    @Test
    @DisplayName("generate derives /<kebab>s base via SDK effectivePath() when path() is unset")
    void generateWithoutExplicitPath() {
        // buildApiPath delegates to DomainMetadata#effectivePath(), the SDK-canonical
        // derivation (explicit path, else "/" + kebab + "s"). With no explicit path,
        // a "PaymentOrder" entity resolves to /payment-orders — consistent with
        // the OpenAPI / Application generators, which all use effectivePath().
        DomainMetadata metadata = DomainMetadata.builder("PaymentOrder", "com.example.domain")
                .build();

        GeneratedFile file = generator.generate(metadata);

        assertThat(file.content())
                .contains("\"/payment-orders\"")
                .doesNotContain("/api/");
    }

    @Test
    @DisplayName("apiVersion does NOT reach the client's base path — the server serves no versioned route")
    void generateIgnoresApiVersion() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .apiVersion("v2")
                .path("/orders")
                .build();

        GeneratedFile file = generator.generate(metadata);

        // The client must request what KernelApplicationGenerator registers and what the OpenAPI
        // document publishes, and neither of those carries an /api/<version> prefix. A client that
        // versioned its own path could not reach the router it was generated alongside.
        assertThat(file.content())
                .contains("\"/orders\"")
                .doesNotContain("/api/v2");
    }

    @Test
    @DisplayName("emits the KernelWebClient CRUD verb surface (get/post/put/delete) with typed Class<T> args")
    void generateEmitsTypedVerbSurface() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        GeneratedFile file = generator.generate(metadata);

        assertThat(file.content())
                .contains("client.get(BASE_PATH + \"/\" + id, Order.class)")
                .contains("client.post(BASE_PATH, entity, Order.class)")
                .contains("client.put(BASE_PATH + \"/\" + id, entity, Order.class)")
                .contains("client.delete(BASE_PATH + \"/\" + id, Void.class)");
    }

    @Test
    @DisplayName("PATCH/PUT parity: update() sends PUT, the verb the generated router serves, and no PATCH")
    void updateSendsPut() {
        DomainMetadata metadata = DomainMetadata.builder("PurchaseOrder", "com.example.domain")
                .path("/purchase-orders")
                .build();

        String content = generator.generate(metadata).content();

        assertThat(content)
                .contains("return client.put(BASE_PATH + \"/\" + id, entity, PurchaseOrder.class);")
                .doesNotContain("client.patch(")
                .doesNotContain("HttpMethod.PATCH");
    }

    @Test
    @DisplayName("ADR-045: constructor Javadoc carries the composition-root retry wiring example — Javadoc-only")
    void generateEmitsRetryCompositionRootExample() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        GeneratedFile file = generator.generate(metadata);

        // The ADR-045 obligation is a composition-root *example*, not per-entity
        // codegen: the emitted constructor Javadoc shows CommunityHttpRetryPolicy
        // wired into the shared KernelWebClient, with the HttpRetryPolicy.none()
        // no-implicit-retry default (ADR-026) named as the opt-out.
        assertThat(file.content())
                .contains("new eu.exeris.kernel.community.http.CommunityHttpRetryPolicy()")
                .contains("HttpRetryPolicy.none()")
                .contains("@param client the web client (injected from CompositionRoot)");
        // The Wall: comments don't couple the binary. The Community FQN (and the
        // retry SPI) must appear as Javadoc text only — never as an import, which
        // would encode tier identity into the compiled surface and break the
        // compile gate (the stub classpath carries only KernelWebClient).
        assertThat(file.content())
                .doesNotContain("import eu.exeris.kernel.community")
                .doesNotContain("import eu.exeris.kernel.spi.http.HttpRetryPolicy");
    }

    @Test
    @DisplayName("K8 / kernel ADR-074: the composition-root example addresses the peer, and says what an unaddressed client does")
    void generateEmitsPeerAddressingGuidance() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        GeneratedFile file = generator.generate(metadata);

        // A CLIENT-mode HttpConfig(bindHost, port) is a listen address and is never dialled, and no
        // signature says so: wiring that sets only those compiles and is refused at the first
        // request. The example must therefore carry the
        // address, and the prose must name both remedies the kernel offers (the per-client view and
        // the engine default) plus the key the refusal message names.
        assertThat(file.content())
                .contains("var client = new OrderClient(webClient.withAuthority(\"peer-host:8080\"));")
                .contains("Addressing the peer (kernel ADR-074)")
                .contains("{@code withAuthority(\"host:port\")}")
                .contains("{@code http.client.defaultAuthority}")
                .contains("{@code defaultAuthority} component of")
                .contains("{@code HttpConfig} when the engine is built by hand")
                .contains("are a listen address and are never dialled")
                .doesNotContain("new OrderClient(webClient);");
        // Javadoc text only: an import used by nothing but a comment would be a consumer-build
        // requirement the compiled client does not have.
        assertThat(file.content())
                .doesNotContain("import eu.exeris.kernel.spi.http.HttpConfig")
                .doesNotContain("import eu.exeris.kernel.spi.http.HttpRequest");
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

    @Test
    @DisplayName("findAll returns the page envelope: by page and size, or by a full list query whose own "
            + "query string it sends")
    void findAllReturnsThePage() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain").path("/orders").build();
        String client = new KernelClientGenerator().generate(metadata).content();

        assertThat(client)
                .contains("public OrderPage findAll(int page, int size)")
                .contains("return findAll(OrderListQuery.of(page, size));")
                .contains("public OrderPage findAll(OrderListQuery query)")
                .contains("return client.get(BASE_PATH + \"?\" + query.toQueryString(), OrderPage.class);")
                .contains("import com.example.repository.OrderPage;")
                // The unpaged findAll() is gone rather than quietly returning one page.
                .doesNotContain("public List<Order> findAll()")
                .doesNotContain("Order[].class");
    }
}
