package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-emission tests for {@link KernelClientGenerator}.
 *
 * <p>Since {@code exeris-kernel 0.8.0-SNAPSHOT} the canonical service-
 * to-service client SPI is stable — names settled, no further SPI
 * changes expected — and the generator was un-parked into
 * {@link KernelGeneratorStrategy}'s registered set. Emitted code now
 * targets {@code eu.exeris.kernel.community.http.client.CommunityWebClient}
 * (community module; H3 stays Enterprise-only per ADR-016). The e2e
 * compile-gate at {@code exeris-e2e-tests} now includes CLIENT in the
 * required artifact set and compiles the emitted source against the
 * {@code CommunityWebClient} stub on the test classpath.
 *
 * <p>This test pins the shape of every emitted method (constructor,
 * findById, findAll, paged findAll, create, update, delete) plus the
 * apiPath assembly (explicit-path / Builder-default / apiVersion
 * override) so a future refactor surfaces any contract drift loudly.
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

    // ---------- emitted-method contract (un-parked: SPI now stable) ----------

    @Test
    @DisplayName("Imports the CommunityWebClient FQN from the community.http.client package (NOT the old transport.http3.client)")
    void importsCommunityWebClient() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String content = generator.generate(metadata).content();

        // Three-tier placement: H1/H2 transport lives in the community
        // module; H3 client stays Enterprise-only per ADR-016.
        assertThat(content)
                .contains("import eu.exeris.kernel.community.http.client.CommunityWebClient;")
                .doesNotContain("eu.exeris.kernel.transport.http3.client");
    }

    @Test
    @DisplayName("Constructor takes a CommunityWebClient parameter and stores it as a private final field")
    void constructorShape() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String content = generator.generate(metadata).content();

        assertThat(content)
                .contains("private final CommunityWebClient client;")
                .contains("public OrderClient(CommunityWebClient client)")
                .contains("this.client = client;");
    }

    @Test
    @DisplayName("findById returns Optional<Entity>, uses the WebClientException.isNotFound() arm for empty, rethrows otherwise")
    void findByIdShape() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String content = generator.generate(metadata).content();

        assertThat(content)
                .contains("public Optional<Order> findById(UUID id)")
                .contains("Optional.ofNullable(client.get(BASE_PATH +")
                // 404 → Optional.empty(); any other error → rethrow.
                .contains("catch (CommunityWebClient.WebClientException e)")
                .contains("if (e.isNotFound())")
                .contains("return Optional.empty();")
                .contains("throw e;");
    }

    @Test
    @DisplayName("Both findAll overloads (paged + unpaged) emit; paged uses ?page=X&size=Y query string")
    void findAllOverloads() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String content = generator.generate(metadata).content();

        // Unpaged
        assertThat(content)
                .contains("public List<Order> findAll()")
                .contains("client.get(BASE_PATH, List.class)");

        // Paged
        assertThat(content)
                .contains("public List<Order> findAll(int page, int size)")
                .contains("\"?page=\"")
                .contains("\"&size=\"");

        // Both findAll variants carry @SuppressWarnings("unchecked")
        // because List.class is raw — exactly 2 occurrences (one per
        // overload), not 1 (would miss an overload) or 3 (would mean
        // a leaked extra).
        long uncheckedCount = content.lines()
                .filter(line -> line.contains("@SuppressWarnings(\"unchecked\")"))
                .count();
        assertThat(uncheckedCount).isEqualTo(2L);
    }

    @Test
    @DisplayName("create / update / delete emit POST / PATCH / DELETE with the expected param + return shapes")
    void mutationMethodShapes() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String content = generator.generate(metadata).content();

        // create(Order entity) → POST
        assertThat(content)
                .contains("public Order create(Order entity)")
                .contains("return client.post(BASE_PATH, entity, Order.class);");

        // update(UUID id, Order entity) → PATCH
        assertThat(content)
                .contains("public Order update(UUID id, Order entity)")
                .contains("return client.patch(BASE_PATH + \"/\" + id, entity, Order.class);");

        // delete(UUID id) → DELETE with Void.class
        assertThat(content)
                .contains("public void delete(UUID id)")
                .contains("client.delete(BASE_PATH + \"/\" + id, Void.class);");
    }

    @Test
    @DisplayName("Package of the emitted client is <domainPackage>.replace(\".domain\", \".client\") — placed alongside service / repository")
    void packageNamingConvention() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.shop.module.domain")
                .path("/orders")
                .build();

        GeneratedFile file = generator.generate(metadata);

        // .domain → .client substitution; siblings (service /
        // repository) use the same convention so the generated tree
        // is symmetric.
        assertThat(file.packageName()).isEqualTo("com.shop.module.client");
    }
}
