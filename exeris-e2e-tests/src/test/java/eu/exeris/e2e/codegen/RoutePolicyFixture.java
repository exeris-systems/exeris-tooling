package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.EmittedJavac;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.BlobMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RouteAccess;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The domain model the route-policy tests generate from, and the compiled policy it yields.
 *
 * <p>{@code Order} requires scopes on the entity, one action replaces them and two inherit them,
 * one of which streams; its {@code receipt} is a {@code @Blob} field restricted to one media type.
 * {@code Invoice} requires an identity and makes one action public. {@code Catalog} is public, has
 * an unrestricted {@code @Blob} field {@code image}, and its base path sits under {@code /orders},
 * so its exact routes and {@code Order}'s templates compete for the same requests. {@code Tag}
 * declares nothing, so its routes are rows the policy abstains on.
 */
final class RoutePolicyFixture {

    static final String DOMAIN_PACKAGE = "eu.exeris.e2e.parity.domain";
    static final String BASE_PACKAGE = "eu.exeris.e2e.parity";
    static final String POLICY_TYPE = "GeneratedRoutePolicy";

    private static final String BLOB_REF = "eu.exeris.kernel.spi.storage.blob.BlobRef";

    private static final Pattern REGISTRATION = Pattern.compile(
            "routerBuilder\\.(route|streamRoute)\\(HttpMethod\\.(\\w+),\\s*\"([^\"]+)\"");

    private RoutePolicyFixture() {
    }

    static List<DomainMetadata> domains() {
        DomainMetadata order = DomainMetadata.builder("Order", DOMAIN_PACKAGE)
                .path("/orders")
                .realTimeApi(true)
                .fields(List.of(FieldMetadata.builder("receipt", BLOB_REF)
                        .blob(BlobMetadata.ofContentTypes(List.of("application/pdf"))).build()))
                .permissions(List.of("order:read", "order:write"))
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("approve").methodName("approve")
                                .permissions(List.of("order:approve")).build(),
                        ActionMetadata.builder("trackShipment").methodName("trackShipment")
                                .streaming(true).build()))
                .build();
        DomainMetadata invoice = DomainMetadata.builder("Invoice", DOMAIN_PACKAGE)
                .path("/invoices")
                .realTimeApi(true)
                .routeAccess(RouteAccess.AUTHENTICATED)
                .actions(List.of(
                        ActionMetadata.builder("export").methodName("export")
                                .routeAccess(RouteAccess.PUBLIC).build()))
                .build();
        DomainMetadata catalog = DomainMetadata.builder("Catalog", DOMAIN_PACKAGE)
                .path("/orders/catalog")
                .realTimeApi(true)
                .fields(List.of(FieldMetadata.builder("image", BLOB_REF).blob(BlobMetadata.unrestricted()).build()))
                .routeAccess(RouteAccess.PUBLIC)
                .build();
        DomainMetadata tag = DomainMetadata.builder("Tag", DOMAIN_PACKAGE)
                .path("/tags")
                .actions(List.of(ActionMetadata.builder("merge").methodName("merge").build()))
                .build();
        return List.of(order, invoice, catalog, tag);
    }

    static List<GeneratedFile> generate() {
        return new KernelApplicationGenerator().generateAll(domains(), BASE_PACKAGE);
    }

    static String content(List<GeneratedFile> files, String className) {
        return files.stream()
                .filter(file -> className.equals(file.className()))
                .map(GeneratedFile::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError(className + " is not emitted"));
    }

    /** Compiles the emitted policy alone, against the kernel SPI, and returns its instance. */
    static HttpRoutePolicy compilePolicy(String source, Path workDir) throws IOException, ReflectiveOperationException {
        Path sourceFile = workDir.resolve("src").resolve(BASE_PACKAGE.replace('.', '/')).resolve(POLICY_TYPE + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        Path classes = Files.createDirectories(workDir.resolve("classes"));
        EmittedJavac.Result result = EmittedJavac.compile(
                List.of(sourceFile.toString()), classes, System.getProperty("java.class.path"));
        if (!result.success()) {
            throw new AssertionError("the emitted policy does not compile: " + result.diagnostics());
        }
        URLClassLoader loader = new URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, RoutePolicyFixture.class.getClassLoader());
        Class<?> type = loader.loadClass(BASE_PACKAGE + "." + POLICY_TYPE);
        return (HttpRoutePolicy) type.getField("INSTANCE").get(null);
    }

    /** A requirement as the contract writes it: its kind, and its scopes for {@code anyScope}. */
    static String describe(RouteRequirement requirement) {
        return switch (requirement.kind()) {
            case ABSTAIN -> "abstain";
            case PERMIT_ALL -> "permitAll";
            case AUTHENTICATED -> "authenticated";
            case ANY_SCOPE -> "anyScope" + sortedScopes(requirement);
            case ALL_SCOPES -> "allScopes" + sortedScopes(requirement);
        };
    }

    private static List<String> sortedScopes(RouteRequirement requirement) {
        List<String> scopes = new ArrayList<>(requirement.scopeCount());
        for (int i = 0; i < requirement.scopeCount(); i++) {
            scopes.add(requirement.scopeAt(i));
        }
        scopes.sort(null);
        return scopes;
    }

    static HttpMethod method(String name) {
        return HttpMethod.valueOf(name);
    }

    /** A route the generated {@code RuntimeLifecycle} registers on the router. */
    record RegisteredRoute(String method, String template, boolean stream) {
    }

    /** The routes {@code RuntimeLifecycle} registers, in registration order. */
    static List<RegisteredRoute> registeredRoutes(String lifecycle) {
        List<RegisteredRoute> routes = new ArrayList<>();
        Matcher m = REGISTRATION.matcher(lifecycle);
        while (m.find()) {
            routes.add(new RegisteredRoute(m.group(2), m.group(3), "streamRoute".equals(m.group(1))));
        }
        return routes;
    }

    /** The concrete path of a contract row's template for {@code entity}. */
    static String instantiate(String template, String entity, String action, String field) {
        DomainMetadata domain = domains().stream()
                .filter(d -> d.entityName().equals(entity))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no fixture entity " + entity));
        String path = template.replace("{base}", domain.effectivePath());
        if (field != null) {
            path = path.replace("{field}", field);
        }
        return action == null ? path : path.replace("{action}", NameCasing.kebab(action));
    }
}
