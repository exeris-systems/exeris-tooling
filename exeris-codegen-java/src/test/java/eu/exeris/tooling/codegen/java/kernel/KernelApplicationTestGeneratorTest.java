package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-generator test for {@link KernelApplicationTestGenerator} (ADR-058, ADR-105).
 *
 * <p>Shape only: that the emitted test compiles and passes against the emitted {@code Application}
 * is proven by {@code GeneratedTestsE2ETest}.
 */
@DisplayName("KernelApplicationTestGenerator")
class KernelApplicationTestGeneratorTest {

    private static final DomainMetadata ORDER =
            DomainMetadata.builder("Order", "com.example.domain").path("/orders").build();

    @Test
    @DisplayName("emits ApplicationRoutePolicyTest beside Application, typed as a TEST artefact")
    void emitsBesideTheApplication() {
        GeneratedFile file = new KernelApplicationTestGenerator().generate(List.of(ORDER), "com.example");

        assertThat(file.className()).isEqualTo("ApplicationRoutePolicyTest");
        assertThat(file.packageName()).isEqualTo("com.example");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.TEST);
    }

    @Test
    @DisplayName("covers the query string, HEAD as GET, the application policy first and unmatchedRoutes()")
    void coversTheFold() {
        String source = new KernelApplicationTestGenerator().generate(List.of(ORDER), "com.example").content();

        assertThat(source)
                .contains("void routePolicyHandsThePathWithoutItsQueryString()")
                .contains("policy.requirementFor(HttpMethod.GET, \"/exeris-generated-test/undescribed?page=1&size=5\");")
                .contains("assertThat(seen).containsExactly(\"GET /exeris-generated-test/undescribed\");")
                .contains("void routePolicyDecidesAHeadRequestAsAGet()")
                .contains("policy.requirementFor(HttpMethod.HEAD, \"/exeris-generated-test/undescribed\");")
                .contains("void applicationPolicyAnswersAheadOfTheGeneratedPolicy()")
                .contains("policy.requirementFor(HttpMethod.GET, \"/orders\")).isEqualTo(declared)")
                .contains("void unmatchedRoutesAnswersARouteNoPolicyDescribes()")
                .contains("protected RouteRequirement unmatchedRoutes()")
                .contains("void aRouteNoPolicyDescribesIsPublicByDefault()")
                .contains("isEqualTo(RouteRequirement.permitAll())")
                .doesNotContain("generatedPolicyAnswersTheRoutesItDescribes")
                .doesNotContain("org.mockito");
    }

    @Test
    @DisplayName("an entity that declares access gets the case proving the fold reaches the generated table")
    void declaredAccessReachesTheGeneratedTable() {
        DomainMetadata guarded = DomainMetadata.builder("Order", "com.example.domain").path("/orders")
                .permissions(List.of("order:read"))
                .build();
        String source = new KernelApplicationTestGenerator().generate(List.of(guarded), "com.example").content();

        assertThat(source)
                .contains("void generatedPolicyAnswersTheRoutesItDescribes()")
                .contains("policy.requirementFor(HttpMethod.GET, \"/orders?page=1\"))"
                        + ".isEqualTo(RouteRequirement.requiringAnyScope(Set.of(\"order:read\")))")
                .contains("policy.requirementFor(HttpMethod.HEAD, "
                        + "\"/orders/00000000-0000-4000-8000-000000000001\"))");
    }

    @Test
    @DisplayName("is deterministic: two runs over the same metadata are byte-identical")
    void isDeterministic() {
        KernelApplicationTestGenerator generator = new KernelApplicationTestGenerator();

        assertThat(generator.generate(List.of(ORDER), "com.example").content())
                .isEqualTo(generator.generate(List.of(ORDER), "com.example").content());
    }
}
