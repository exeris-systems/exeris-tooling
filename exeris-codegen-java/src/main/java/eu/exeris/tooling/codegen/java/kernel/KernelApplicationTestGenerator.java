package eu.exeris.tooling.codegen.java.kernel;

import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.ASSERTIONS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.FIXED_ID;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.test;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.access.RouteRequirements;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Optional;

/**
 * Emits {@code ApplicationRoutePolicyTest} — the generated test of the route policy the generated
 * {@code Application} binds (ADR-058, ADR-105).
 *
 * <p>{@code Application.routePolicy()} folds {@code applicationPolicy()}, the generated
 * {@code GeneratedRoutePolicy} and {@code unmatchedRoutes()}, and hands each policy it folds the
 * path without its query string and a {@code HEAD} request as {@code GET}. Those are the decisions
 * this test pins, in the consumer's own build:
 * <ul>
 *   <li>a policy sees the path without its query string, and {@code HEAD} as {@code GET};</li>
 *   <li>{@code applicationPolicy()} answers ahead of the generated policy;</li>
 *   <li>{@code unmatchedRoutes()} answers a route no policy describes, and by default that answer is
 *       {@code permitAll()};</li>
 *   <li>when an entity declares access, the generated policy answers its routes through the fold,
 *       whatever query string the request carries and for {@code HEAD} as for {@code GET}.</li>
 * </ul>
 *
 * <p>The application's hooks are {@code protected}, and the test sits in the application's package,
 * so it reads them through an anonymous subclass: no kernel boots, and the classpath stays JUnit 5 +
 * AssertJ and the kernel SPI the generated main code already requires.
 *
 * <p>One file per run. Deterministic: the emitted source is a pure function of the metadata, in the
 * order the build lists the entities.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 * @since 0.11
 */
public final class KernelApplicationTestGenerator {

    /** Simple name of the emitted test. */
    public static final String CLASS_NAME = "ApplicationRoutePolicyTest";

    private static final String SPI_HTTP = "eu.exeris.kernel.spi.http";
    private static final ClassName HTTP_ROUTE_POLICY = ClassName.get(SPI_HTTP, "HttpRoutePolicy");
    private static final ClassName ROUTE_REQUIREMENT = ClassName.get(SPI_HTTP, "RouteRequirement");
    private static final ClassName HTTP_METHOD = ClassName.get(SPI_HTTP, "HttpMethod");
    private static final String JAVA_UTIL = "java.util";
    private static final ClassName LIST = ClassName.get(JAVA_UTIL, "List");
    private static final ClassName ARRAY_LIST = ClassName.get(JAVA_UTIL, "ArrayList");
    private static final ClassName SET = ClassName.get(JAVA_UTIL, "Set");

    /** A path no generated route has: the entities' paths are their own literal segments. */
    private static final String UNDESCRIBED_PATH = "/exeris-generated-test/undescribed";
    private static final String SEEN = "seen";
    private static final String ROUTE_POLICY_OF = "$T policy = $L.routePolicy();\n";
    private static final String ASSERT_REQUIREMENT = "$T.assertThat(policy.requirementFor($T.$L, $S)).isEqualTo($L)";

    /**
     * Creates the generator. It keeps no state, so one instance serves every run.
     */
    public KernelApplicationTestGenerator() {
        // no state to initialise
    }

    /**
     * Emits the route-policy test of the generated application.
     *
     * @param domains     every entity of the build, in the order the build lists them
     * @param basePackage the package {@code Application} is emitted in
     * @return the emitted test; never {@code null}
     */
    public GeneratedFile generate(List<DomainMetadata> domains, String basePackage) {
        ClassName applicationType = ClassName.get(basePackage, "Application");
        TypeSpec.Builder type = KernelScaffold.publicClass(CLASS_NAME)
                .addJavadoc("Generated tests for the route policy {@link $T#routePolicy()} hands the kernel.\n",
                        applicationType)
                .addJavadoc("<p>Covers how the policy decides: on the path without its query string, on\n")
                .addJavadoc("{@code HEAD} as {@code GET}, with {@code applicationPolicy()} ahead of the generated\n")
                .addJavadoc("policy and {@code unmatchedRoutes()} answering the routes neither describes.\n")
                .addJavadoc("<p>Requires JUnit 5 and AssertJ on the test classpath, and nothing else.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");

        type.addMethod(observedCase("routePolicyHandsThePathWithoutItsQueryString", applicationType,
                "GET", UNDESCRIBED_PATH + "?page=1&size=5", "GET " + UNDESCRIBED_PATH));
        type.addMethod(observedCase("routePolicyDecidesAHeadRequestAsAGet", applicationType,
                "HEAD", UNDESCRIBED_PATH, "GET " + UNDESCRIBED_PATH));
        type.addMethod(applicationPolicyFirstCase(applicationType, domains));
        type.addMethod(unmatchedRoutesCase(applicationType));
        type.addMethod(test("aRouteNoPolicyDescribesIsPublicByDefault")
                .addJavadoc("The default {@code unmatchedRoutes()} answers {@code permitAll()}.\n")
                .addStatement("$T policy = new $T().routePolicy()", HTTP_ROUTE_POLICY, applicationType)
                .addStatement(ASSERT_REQUIREMENT, ASSERTIONS, HTTP_METHOD, "GET", UNDESCRIBED_PATH,
                        CodeBlock.of("$T.permitAll()", ROUTE_REQUIREMENT))
                .build());
        firstDescribedEntity(domains).ifPresent(domain ->
                type.addMethod(generatedPolicyCase(applicationType, domain)));

        return new GeneratedFile(basePackage, CLASS_NAME,
                KernelScaffold.render(basePackage, type.build()), ArtifactType.TEST);
    }

    /**
     * Asks the folded policy about {@code path} under {@code method} with an application policy
     * that records what it was asked and abstains.
     */
    private static MethodSpec observedCase(String name, ClassName applicationType, String method, String path,
                                           String expected) {
        return test(name)
                .addJavadoc("The application policy is asked first, so it sees what every policy the fold\n")
                .addJavadoc("asks sees.\n")
                .addStatement("$T $L = new $T<>()", ParameterizedTypeName.get(LIST, ClassName.get(String.class)),
                        SEEN, ARRAY_LIST)
                .addCode(ROUTE_POLICY_OF, HTTP_ROUTE_POLICY,
                        applicationWith(applicationType, recordingPolicy()))
                .addStatement("policy.requirementFor($T.$L, $S)", HTTP_METHOD, method, path)
                .addStatement("$T.assertThat($L).containsExactly($S)", ASSERTIONS, SEEN, expected)
                .build();
    }

    private static MethodSpec applicationPolicyFirstCase(ClassName applicationType, List<DomainMetadata> domains) {
        String path = domains.isEmpty() ? UNDESCRIBED_PATH : domains.get(0).effectivePath();
        return test("applicationPolicyAnswersAheadOfTheGeneratedPolicy")
                .addJavadoc("A requirement the application declares wins on a generated route: the\n")
                .addJavadoc("generated table never declares {@code requiringAllScopes}.\n")
                .addStatement("$T declared = $T.requiringAllScopes($T.of($S))", ROUTE_REQUIREMENT,
                        ROUTE_REQUIREMENT, SET, "generated-test")
                .addCode(ROUTE_POLICY_OF, HTTP_ROUTE_POLICY,
                        applicationWith(applicationType, CodeBlock.of("(method, path) -> declared")))
                .addStatement(ASSERT_REQUIREMENT, ASSERTIONS, HTTP_METHOD, "GET", path, "declared")
                .build();
    }

    private static MethodSpec unmatchedRoutesCase(ClassName applicationType) {
        TypeSpec application = TypeSpec.anonymousClassBuilder("")
                .superclass(applicationType)
                .addMethod(MethodSpec.methodBuilder("unmatchedRoutes")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PROTECTED)
                        .returns(ROUTE_REQUIREMENT)
                        .addStatement("return unmatched")
                        .build())
                .build();
        return test("unmatchedRoutesAnswersARouteNoPolicyDescribes")
                .addStatement("$T unmatched = $T.requiringAllScopes($T.of($S))", ROUTE_REQUIREMENT,
                        ROUTE_REQUIREMENT, SET, "unmatched")
                .addCode(ROUTE_POLICY_OF, HTTP_ROUTE_POLICY, application)
                .addStatement(ASSERT_REQUIREMENT, ASSERTIONS, HTTP_METHOD, "GET", UNDESCRIBED_PATH, "unmatched")
                .build();
    }

    /**
     * The generated policy, reached through the fold, answers the entity's declared requirement on
     * its list route with a query string, and on a {@code HEAD} of one row.
     */
    private static MethodSpec generatedPolicyCase(ClassName applicationType, DomainMetadata domain) {
        CodeBlock expected = KernelRoutePolicyTable.requirementExpression(RouteRequirements.forEntity(domain));
        String base = domain.effectivePath();
        return test("generatedPolicyAnswersTheRoutesItDescribes")
                .addJavadoc("{@code $L} declares access, so the generated policy describes its routes.\n",
                        domain.entityName())
                .addStatement("$T policy = new $T().routePolicy()", HTTP_ROUTE_POLICY, applicationType)
                .addStatement(ASSERT_REQUIREMENT, ASSERTIONS, HTTP_METHOD, "GET", base + "?page=1", expected)
                .addStatement(ASSERT_REQUIREMENT, ASSERTIONS, HTTP_METHOD, "HEAD", base + "/" + FIXED_ID,
                        expected)
                .build();
    }

    /** {@code new Application() { applicationPolicy() returns policy }}. */
    private static TypeSpec applicationWith(ClassName applicationType, CodeBlock policy) {
        return TypeSpec.anonymousClassBuilder("")
                .superclass(applicationType)
                .addMethod(MethodSpec.methodBuilder("applicationPolicy")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PROTECTED)
                        .returns(HTTP_ROUTE_POLICY)
                        .addCode("return $L;\n", policy)
                        .build())
                .build();
    }

    /** A policy that records {@code "<method> <path>"} into {@code seen} and abstains. */
    private static CodeBlock recordingPolicy() {
        return CodeBlock.builder()
                .add("(method, path) -> {\n").indent()
                .add("$L.add(method + $S + path);\n", SEEN, " ")
                .add("return $T.abstain();\n", ROUTE_REQUIREMENT)
                .unindent().add("}")
                .build();
    }

    /** The first entity whose own declaration gives its routes a requirement, if any does. */
    private static Optional<DomainMetadata> firstDescribedEntity(List<DomainMetadata> domains) {
        return domains.stream().filter(domain -> RouteRequirements.forEntity(domain) != null).findFirst();
    }
}
