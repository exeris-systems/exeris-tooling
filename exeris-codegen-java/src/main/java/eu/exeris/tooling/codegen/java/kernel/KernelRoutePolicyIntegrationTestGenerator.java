package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.access.RouteRequirements;
import eu.exeris.tooling.codegen.core.access.RouteRequirements.Kind;
import eu.exeris.tooling.codegen.core.access.RouteRequirements.Requirement;
import eu.exeris.tooling.codegen.core.access.RouteRequirements.Source;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Locale;

/**
 * Emits {@code GeneratedRoutePolicyIT}, the generated integration test of the route policy the
 * booted application binds (ADR-058 Amendment 1, ADR-105).
 *
 * <p>The expected requirement of each route is read from the declarations through
 * {@link RouteRequirements}, the rules {@code GeneratedRoutePolicy} is emitted from; the request is
 * built from the entity's path and a fixed key, independently of the routing table that policy
 * holds. The kernel router dispatches the request and the policy answers it, so a row of the table
 * that disagrees with the router fails here. Per entity and per respond-once action that declares
 * access:
 *
 * <ul>
 *   <li>a route that demands an identity is {@code 401} without a token — on every CRUD route, with
 *       a query string, and on {@code HEAD} of a {@code GET} route;</li>
 *   <li>a route that demands scopes is {@code 403} for a verified caller holding none of them, and
 *       passes the policy for a caller holding any one, each declared scope tried;</li>
 *   <li>a route that demands an identity only passes the policy for a verified caller with no
 *       scope;</li>
 *   <li>a {@code PUBLIC} route reaches its handler without a token.</li>
 * </ul>
 *
 * <p>Passing the policy is asserted as a status other than {@code 401} and {@code 403}: the handler
 * then answers for a row that does not exist. Emitted only when some route demands an identity,
 * which is when the build derives the security subsystem.
 *
 * @since 0.11
 */
public final class KernelRoutePolicyIntegrationTestGenerator {

    /** Simple name of the emitted test. */
    public static final String CLASS_NAME = "GeneratedRoutePolicyIT";

    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName DISPLAY_NAME = ClassName.get("org.junit.jupiter.api", "DisplayName");
    private static final ClassName ASSERTIONS = ClassName.get("org.assertj.core.api", "Assertions");
    private static final ClassName STRING = ClassName.get(String.class);

    private static final String GET = "GET";
    private static final String HEAD = "HEAD";
    private static final String POST = "POST";
    private static final String PUT = "PUT";
    private static final String DELETE = "DELETE";
    private static final String KEY = "KEY";
    private static final String EMPTY_BODY = "\"{}\"";
    private static final String NO_BODY = "null";
    private static final String STATUS = "status";
    private static final String ADMITTED = "admitted";
    private static final String QUERY = "?x=1";
    private static final String STATUS_CALL = "$N($S, $L, $L, $L, $L)";
    private static final String ADMITTED_CALL = "$N($S, $L, $L)";
    private static final String UNAUTHORIZED = "401";
    private static final String FORBIDDEN = "403";
    private static final String NOT_FOUND = "404";

    /**
     * Creates the generator. It keeps no state, so one instance serves every build.
     */
    public KernelRoutePolicyIntegrationTestGenerator() {
        // no state to initialise
    }

    /** The emitted support types. */
    private record Support(ClassName http, ClassName response, ClassName tokens) {
    }

    /**
     * One request a verified caller sends to a route.
     *
     * @param method the HTTP method
     * @param path   the expression of the path
     * @param body   the expression of the JSON body, {@code null} for none
     */
    private record Probe(String method, CodeBlock path, String body) {
    }

    /**
     * Emits the route-policy integration test, or nothing when no route demands an identity.
     *
     * @param domains     every entity of the build, in build order
     * @param basePackage the build's base package, where the test goes
     * @return the test, or {@code null} when no route demands an identity
     */
    public GeneratedFile generate(List<DomainMetadata> domains, String basePackage) {
        if (!RouteRequirements.demandIdentity(domains)) {
            return null;
        }
        String support = KernelTestSupportGenerator.supportPackage(basePackage);
        ClassName http = ClassName.get(support, KernelIntegrationSupportGenerator.INTEGRATION_HTTP);
        Support types = new Support(http, http.nestedClass("Response"),
                ClassName.get(support, KernelIntegrationSupportGenerator.INTEGRATION_TOKENS));

        TypeSpec.Builder type = KernelScaffold.publicClass(CLASS_NAME)
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Generated integration test of the route policy the booted application binds\n")
                .addJavadoc("(ADR-058 Amendment 1, ADR-105). Failsafe runs it, by name.\n")
                .addJavadoc("<p>Each expected requirement comes from the declarations; each request is built\n")
                .addJavadoc("from the entity's path, not from the routing table {@code GeneratedRoutePolicy}\n")
                .addJavadoc("holds, so the router and that table are compared on a real boot.\n")
                .addJavadoc("<p>Requires JUnit 5 and AssertJ on the test classpath, H2 and a kernel driver at\n")
                .addJavadoc("test runtime, and nothing else.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addAnnotation(displayName("The generated route policy, on the booted application"))
                .addField(FieldSpec.builder(STRING, KEY, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", KernelTestSamples.FIXED_ID)
                        .build());

        for (DomainMetadata domain : domains) {
            addEntityCases(type, types, domain);
            if (domain.hasActions()) {
                for (ActionMetadata action : domain.actions()) {
                    if (!action.streaming()) {
                        addActionCases(type, types, domain, action);
                    }
                }
            }
        }
        type.addMethod(statusMethod(types));
        type.addMethod(admittedMethod(types));
        return new GeneratedFile(basePackage, CLASS_NAME, KernelScaffold.render(basePackage, type.build()),
                ArtifactType.TEST);
    }

    private static void addEntityCases(TypeSpec.Builder type, Support types, DomainMetadata domain) {
        Requirement requirement = RouteRequirements.forEntity(domain);
        if (requirement == null) {
            return;
        }
        String prefix = lowerCamel(domain.entityName()) + "Routes";
        String base = domain.effectivePath();
        CodeBlock list = CodeBlock.of("$S", base);
        CodeBlock byId = CodeBlock.of("$S + $N", base + "/", KEY);
        if (requirement.kind() == Kind.PERMIT_ALL) {
            type.addMethod(test(prefix + "AdmitAnAnonymousCaller",
                    "the public routes of " + base + " reach the handler without a token")
                    .addStatement(STATUS_CALL, STATUS, GET, byId, NO_BODY, NO_BODY, NOT_FOUND)
                    .build());
            return;
        }
        type.addMethod(test(prefix + "RefuseAnAnonymousCaller",
                "every route of " + base + " is 401 without a token, with a query string and on HEAD")
                .addStatement(STATUS_CALL, STATUS, GET, list, NO_BODY, NO_BODY, UNAUTHORIZED)
                .addStatement(STATUS_CALL, STATUS, GET, CodeBlock.of("$S", base + QUERY), NO_BODY, NO_BODY,
                        UNAUTHORIZED)
                .addStatement(STATUS_CALL, STATUS, GET, byId, NO_BODY, NO_BODY, UNAUTHORIZED)
                .addStatement(STATUS_CALL, STATUS, GET, CodeBlock.of("$L + $S", byId, QUERY), NO_BODY, NO_BODY,
                        UNAUTHORIZED)
                .addStatement(STATUS_CALL, STATUS, HEAD, byId, NO_BODY, NO_BODY, UNAUTHORIZED)
                .addStatement(STATUS_CALL, STATUS, POST, list, EMPTY_BODY, NO_BODY, UNAUTHORIZED)
                .addStatement(STATUS_CALL, STATUS, PUT, byId, EMPTY_BODY, NO_BODY, UNAUTHORIZED)
                .addStatement(STATUS_CALL, STATUS, DELETE, byId, NO_BODY, NO_BODY, UNAUTHORIZED)
                .build());
        addVerifiedCases(type, types, prefix, base, new Probe(GET, byId, NO_BODY), requirement, List.of());
    }

    private static void addActionCases(TypeSpec.Builder type, Support types, DomainMetadata domain,
                                       ActionMetadata action) {
        Requirement requirement = RouteRequirements.forAction(domain, action);
        if (requirement == null) {
            return;
        }
        String prefix = lowerCamel(domain.entityName()) + NameCasing.pascal(action.name()) + "Action";
        String segment = "/actions/" + NameCasing.kebab(action.name());
        Probe probe = new Probe(POST, CodeBlock.of("$S + $N + $S", domain.effectivePath() + "/", KEY, segment),
                EMPTY_BODY);
        String route = "POST " + domain.effectivePath() + "/{id}" + segment;
        if (requirement.kind() == Kind.PERMIT_ALL) {
            type.addMethod(test(prefix + "AdmitsAnAnonymousCaller", "the public " + route + " passes without a token")
                    .addStatement(ADMITTED_CALL, ADMITTED, probe.method(), probe.path(), NO_BODY)
                    .build());
            return;
        }
        type.addMethod(test(prefix + "RefusesAnAnonymousCaller", route + " is 401 without a token")
                .addStatement(STATUS_CALL, STATUS, probe.method(), probe.path(), probe.body(), NO_BODY, UNAUTHORIZED)
                .build());
        addVerifiedCases(type, types, prefix, route, probe, requirement, replacedScopes(domain, requirement));
    }

    /**
     * The entity's scopes an action's own list replaces: a caller holding only these is refused on
     * the action's route, since the nearest declaration wins.
     */
    private static List<String> replacedScopes(DomainMetadata domain, Requirement action) {
        Requirement entity = RouteRequirements.forEntity(domain);
        if (action.source() != Source.ACTION || action.kind() != Kind.ANY_SCOPE
                || entity == null || entity.kind() != Kind.ANY_SCOPE) {
            return List.of();
        }
        return entity.scopes().stream().filter(scope -> !action.scopes().contains(scope)).toList();
    }

    /**
     * The cases of a verified caller: refused or passed by the scopes it holds. A caller holding only
     * {@code refusedScopes} is refused as one holding none.
     */
    private static void addVerifiedCases(TypeSpec.Builder type, Support types, String prefix, String what,
                                         Probe probe, Requirement requirement, List<String> refusedScopes) {
        if (requirement.kind() == Kind.AUTHENTICATED) {
            type.addMethod(test(prefix + "AdmitAnyVerifiedCaller", what + " passes for a verified caller with no scope")
                    .addStatement(ADMITTED_CALL, ADMITTED, probe.method(), probe.path(),
                            CodeBlock.of("$T.bearer()", types.tokens()))
                    .build());
            return;
        }
        MethodSpec.Builder refused = test(prefix + "RefuseACallerWithoutTheScope",
                what + " is 403 for a verified caller holding none of " + requirement.scopes())
                .addStatement(STATUS_CALL, STATUS, probe.method(), probe.path(), probe.body(),
                        CodeBlock.of("$T.bearer()", types.tokens()), FORBIDDEN);
        if (!refusedScopes.isEmpty()) {
            refused.addStatement(STATUS_CALL, STATUS, probe.method(), probe.path(), probe.body(),
                    CodeBlock.of("$T.bearer($L)", types.tokens(), refusedScopes.stream()
                            .map(scope -> CodeBlock.of("$S", scope)).collect(CodeBlock.joining(", "))),
                    FORBIDDEN);
        }
        type.addMethod(refused.build());
        MethodSpec.Builder admitted = test(prefix + "AdmitEachDeclaredScope",
                what + " passes for a caller holding any one of " + requirement.scopes());
        for (String scope : requirement.scopes()) {
            admitted.addStatement(ADMITTED_CALL, ADMITTED, probe.method(), probe.path(),
                    CodeBlock.of("$T.bearer($S)", types.tokens(), scope));
        }
        type.addMethod(admitted.build());
    }


    private static MethodSpec statusMethod(Support types) {
        return MethodSpec.methodBuilder(STATUS)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(STRING, "method")
                .addParameter(STRING, "path")
                .addParameter(STRING, "json")
                .addParameter(STRING, "bearer")
                .addParameter(TypeName.INT, "expected")
                .addStatement("$T response = $T.send(method, path, json, bearer)", types.response(), types.http())
                .addStatement("$T.assertThat(response.status()).as(method + $S + path + $S + response.body())"
                        + ".isEqualTo(expected)", ASSERTIONS, " ", ": ")
                .build();
    }

    private static MethodSpec admittedMethod(Support types) {
        return MethodSpec.methodBuilder(ADMITTED)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(STRING, "method")
                .addParameter(STRING, "path")
                .addParameter(STRING, "bearer")
                .addStatement("$T response = $T.send(method, path, $S.equals(method) ? $L : null, bearer)",
                        types.response(), types.http(), POST, EMPTY_BODY)
                .addStatement("$T.assertThat(response.status()).as(method + $S + path + $S + response.body())"
                        + ".isNotIn($L, $L)", ASSERTIONS, " ", ": ", UNAUTHORIZED, FORBIDDEN)
                .build();
    }

    private static MethodSpec.Builder test(String name, String display) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(TEST)
                .addAnnotation(displayName(display));
    }

    private static AnnotationSpec displayName(String text) {
        return AnnotationSpec.builder(DISPLAY_NAME).addMember("value", "$S", text).build();
    }

    private static String lowerCamel(String name) {
        String pascal = NameCasing.pascal(name);
        return pascal.substring(0, 1).toLowerCase(Locale.ROOT) + pascal.substring(1);
    }
}
