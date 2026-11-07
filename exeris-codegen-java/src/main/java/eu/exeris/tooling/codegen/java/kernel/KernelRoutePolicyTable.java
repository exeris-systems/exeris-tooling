package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.core.access.RouteRequirements;
import eu.exeris.tooling.codegen.core.access.RouteRequirements.Requirement;
import eu.exeris.tooling.codegen.java.support.BlobFields;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The route table of a {@code GeneratedRoutePolicy} that declares requirements (ADR-105).
 *
 * <p>The table mirrors the kernel router's resolution so that the requirement it answers is the
 * requirement of the route the router dispatches to. For one method the router tries stream
 * routes before respond-once routes, and within each kind exact paths before templates, each in
 * registration order. A {@code {x}} placeholder matches one non-empty segment, a trailing slash
 * is one more segment, the query component is not part of the path, and {@code HEAD} is served
 * from the {@code GET} respond-once routes, never from a stream route. Every generated route is a
 * row, an undeclared one answering an abstention, so that a row of one entity never claims a
 * request the router gives to another.
 *
 * <p>The routes and their order are the ones {@code RuntimeLifecycle} registers: per entity in
 * build order, {@code GET {base}}, {@code GET {base}/{id}}, {@code POST {base}},
 * {@code PUT {base}/{id}}, {@code DELETE {base}/{id}}, then {@code PUT}, {@code GET} and
 * {@code DELETE {base}/{id}/blobs/<field>} per {@code @Blob} field in declaration order, each under
 * the entity's requirement, and one {@code POST} per respond-once action;
 * then the stream routes, per entity, each streaming action and, for a real-time entity, the live
 * view and the spectate route.
 */
final class KernelRoutePolicyTable {

    private static final ClassName HTTP_ROUTE_POLICY =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpRoutePolicy");
    private static final ClassName ROUTE_REQUIREMENT =
            ClassName.get("eu.exeris.kernel.spi.http", "RouteRequirement");
    private static final ClassName HTTP_METHOD = ClassName.get("eu.exeris.kernel.spi.http", "HttpMethod");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final String ABSTAIN = "ABSTAIN";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String PUT = "PUT";
    private static final String DELETE = "DELETE";
    private static final String ID_SEGMENT = "/{id}";
    private static final List<String> METHODS = List.of(GET, POST, PUT, DELETE);

    /**
     * One generated route.
     *
     * @param method      the HTTP method name
     * @param template    the path template the router registers
     * @param stream      whether it is a stream route
     * @param requirement its requirement, or {@code null} where the policy abstains
     * @param constant    the name of the constant holding the requirement, or {@code null}
     */
    private record Route(String method, String template, boolean stream, Requirement requirement,
                         String constant) {

        boolean exact() {
            return template.indexOf('{') < 0;
        }
    }

    private KernelRoutePolicyTable() {
    }

    /**
     * Emits the policy class, its table built from {@code domains}.
     *
     * @param selfType the {@code GeneratedRoutePolicy} type
     * @param domains  every entity, in build order
     * @return the type
     */
    static TypeSpec emit(ClassName selfType, List<DomainMetadata> domains) {
        Map<Requirement, String> constants = new LinkedHashMap<>();
        List<Route> routes = routes(domains, constants);
        ClassName rowType = selfType.nestedClass("Row");

        TypeSpec.Builder type = KernelScaffold.publicClass(selfType.simpleName()).addModifiers(Modifier.FINAL)
                .addSuperinterface(HTTP_ROUTE_POLICY)
                .addJavadoc("Generated route policy: the generated half of the policy the application\n")
                .addJavadoc("binds (ADR-105).\n")
                .addJavadoc("<p>Its table holds every generated route, in the order the router resolves\n")
                .addJavadoc("them: for one method stream routes before respond-once routes, exact paths\n")
                .addJavadoc("before templates, each in registration order, {@code HEAD} answered from the\n")
                .addJavadoc("{@code GET} respond-once rows, since the router serves no stream to {@code HEAD}.\n")
                .addJavadoc("A route whose entity and action declare no access answers an\n")
                .addJavadoc("abstention, and so does a path no row matches; the application decides both.\n")
                .addJavadoc("A requirement it declares for a stream route is a prompt one.\n")
                .addJavadoc("<p>{@code requirementFor} walks the path in place and allocates nothing.\n")
                .addJavadoc("<p>{@code Application} asks it after {@code applicationPolicy()}.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");

        type.addField(FieldSpec.builder(ROUTE_REQUIREMENT, ABSTAIN, Modifier.PRIVATE, Modifier.STATIC,
                Modifier.FINAL).initializer("$T.abstain()", ROUTE_REQUIREMENT).build());
        constants.forEach((requirement, name) -> type.addField(FieldSpec.builder(ROUTE_REQUIREMENT, name,
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(requirementExpression(requirement)).build()));
        ArrayTypeName rows = ArrayTypeName.of(rowType);
        for (String method : METHODS) {
            type.addField(FieldSpec.builder(rows, method + "_ROWS", Modifier.PRIVATE, Modifier.STATIC,
                    Modifier.FINAL).initializer(rowsInitializer(routes, method, true)).build());
        }
        type.addField(FieldSpec.builder(rows, "HEAD_ROWS", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The {@code GET} respond-once rows: the router serves no stream to {@code HEAD}.\n")
                .initializer(rowsInitializer(routes, GET, false)).build());
        type.addField(FieldSpec.builder(rows, "NO_ROWS", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("{}").build());
        type.addField(FieldSpec.builder(selfType, "INSTANCE", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The one instance; built before the kernel boots.\n")
                .initializer("new $T()", selfType).build());

        return type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addMethod(requirementFor(rows))
                .addMethod(MethodSpec.methodBuilder("row")
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(rowType)
                        .addParameter(String.class, "template")
                        .addParameter(ROUTE_REQUIREMENT, "requirement")
                        .addStatement("String[] segments = template.split($S, -1)", "/")
                        .beginControlFlow("for (int i = 0; i < segments.length; i++)")
                        .beginControlFlow("if (segments[i].startsWith($S))", "{")
                        .addStatement("segments[i] = null")
                        .endControlFlow()
                        .endControlFlow()
                        .addStatement("return new $T(segments, requirement)", rowType)
                        .build())
                .addType(rowType(rowType))
                .build();
    }

    /** The routes, in registration order, each with its requirement; fills {@code constants}. */
    private static List<Route> routes(List<DomainMetadata> domains, Map<Requirement, String> constants) {
        List<Route> routes = new ArrayList<>();
        for (DomainMetadata domain : domains) {
            Requirement entity = RouteRequirements.forEntity(domain);
            String base = domain.effectivePath();
            String entityConstant = constant(constants, entity, domain, null);
            routes.add(new Route(GET, base, false, entity, entityConstant));
            routes.add(new Route(GET, base + ID_SEGMENT, false, entity, entityConstant));
            routes.add(new Route(POST, base, false, entity, entityConstant));
            routes.add(new Route(PUT, base + ID_SEGMENT, false, entity, entityConstant));
            routes.add(new Route(DELETE, base + ID_SEGMENT, false, entity, entityConstant));
            for (FieldMetadata field : BlobFields.of(domain)) {
                String path = base + BlobFields.routeSuffix(field);
                routes.add(new Route(PUT, path, false, entity, entityConstant));
                routes.add(new Route(GET, path, false, entity, entityConstant));
                routes.add(new Route(DELETE, path, false, entity, entityConstant));
            }
            for (ActionMetadata action : domain.actions()) {
                if (!action.streaming()) {
                    routes.add(actionRoute(domain, action, false, constants));
                }
            }
        }
        for (DomainMetadata domain : domains) {
            Requirement entity = RouteRequirements.forEntity(domain);
            String base = domain.effectivePath();
            String entityConstant = constant(constants, entity, domain, null);
            for (ActionMetadata action : domain.actions()) {
                if (action.streaming()) {
                    routes.add(actionRoute(domain, action, true, constants));
                }
            }
            if (domain.realTimeApi()) {
                routes.add(new Route(GET, base + "/stream", true, entity, entityConstant));
                routes.add(new Route(GET, base + ID_SEGMENT + "/stream", true, entity, entityConstant));
            }
        }
        return routes;
    }

    private static Route actionRoute(DomainMetadata domain, ActionMetadata action, boolean stream,
                                     Map<Requirement, String> constants) {
        Requirement requirement = RouteRequirements.forAction(domain, action);
        String path = domain.effectivePath() + ID_SEGMENT + "/actions/" + NameCasing.kebab(action.name());
        return new Route(POST, path, stream, requirement, constant(constants, requirement, domain, action));
    }

    /**
     * The constant holding {@code requirement}: the first route carrying an equal requirement
     * names it after its entity, and its action when the action declared it, with a counter when
     * that name is taken.
     */
    private static String constant(Map<Requirement, String> constants, Requirement requirement,
                                   DomainMetadata domain, ActionMetadata action) {
        if (requirement == null) {
            return null;
        }
        Requirement key = new Requirement(requirement.kind(), requirement.scopes(),
                RouteRequirements.Source.ENTITY);
        String owner = action != null && requirement.source() == RouteRequirements.Source.ACTION
                ? domain.entityName() + "_" + action.name()
                : domain.entityName();
        String existing = constants.get(key);
        if (existing != null) {
            return existing;
        }
        String name = constantCase(owner) + "_ACCESS";
        String unique = name;
        for (int n = 2; constants.containsValue(unique); n++) {
            unique = name + "_" + n;
        }
        constants.put(key, unique);
        return unique;
    }

    private static String constantCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replace('-', '_').toUpperCase(Locale.ROOT);
    }

    static CodeBlock requirementExpression(Requirement requirement) {
        return switch (requirement.kind()) {
            case PERMIT_ALL -> CodeBlock.of("$T.permitAll()", ROUTE_REQUIREMENT);
            case AUTHENTICATED -> CodeBlock.of("$T.authenticated()", ROUTE_REQUIREMENT);
            case ANY_SCOPE -> CodeBlock.of("$T.requiringAnyScope($T.of($L))", ROUTE_REQUIREMENT, SET,
                    requirement.scopes().stream().map(s -> CodeBlock.of("$S", s)).collect(CodeBlock.joining(", ")));
        };
    }

    /**
     * One method's rows: stream routes when {@code withStreams}, then respond-once; within each,
     * exact paths, then templates.
     */
    private static CodeBlock rowsInitializer(List<Route> routes, String method, boolean withStreams) {
        List<CodeBlock> rows = new ArrayList<>();
        for (boolean stream : withStreams ? new boolean[] {true, false} : new boolean[] {false}) {
            for (boolean exact : new boolean[] {true, false}) {
                for (Route route : routes) {
                    if (route.method().equals(method) && route.stream() == stream && route.exact() == exact) {
                        rows.add(CodeBlock.of("row($S, $L)", route.template(),
                                route.constant() != null ? route.constant() : ABSTAIN));
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            return CodeBlock.of("{}");
        }
        return CodeBlock.builder().add("{\n").indent()
                .add(CodeBlock.join(rows, ",\n")).add("\n")
                .unindent().add("}").build();
    }

    private static MethodSpec requirementFor(ArrayTypeName rows) {
        return MethodSpec.methodBuilder("requirementFor")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ROUTE_REQUIREMENT)
                .addParameter(HTTP_METHOD, "method")
                .addParameter(String.class, "path")
                .addJavadoc("Answers the requirement of the first row matching the route, or an abstention.\n")
                .addJavadoc("\n")
                .addJavadoc("@param method the method of the route the request is dispatched to\n")
                .addJavadoc("@param path the request path; a query component is ignored\n")
                .addJavadoc("@return the matching row's requirement, or an abstention\n")
                .addStatement("$T table = switch (method) {\n"
                                + "    case GET -> GET_ROWS;\n"
                                + "    case HEAD -> HEAD_ROWS;\n"
                                + "    case POST -> POST_ROWS;\n"
                                + "    case PUT -> PUT_ROWS;\n"
                                + "    case DELETE -> DELETE_ROWS;\n"
                                + "    default -> NO_ROWS;\n"
                                + "}", rows)
                .addStatement("int query = path.indexOf('?')")
                .addStatement("int end = query < 0 ? path.length() : query")
                .beginControlFlow("for (Row row : table)")
                .beginControlFlow("if (row.matches(path, end))")
                .addStatement("return row.requirement()")
                .endControlFlow()
                .endControlFlow()
                .addStatement("return $L", ABSTAIN)
                .build();
    }

    private static TypeSpec rowType(ClassName rowType) {
        MethodSpec matches = MethodSpec.methodBuilder("matches")
                .returns(TypeName.BOOLEAN)
                .addParameter(String.class, "path")
                .addParameter(TypeName.INT, "end")
                .addJavadoc("Whether {@code path} up to {@code end} matches this row's template: a\n")
                .addJavadoc("{@code null} segment is a placeholder taking one non-empty segment, any other\n")
                .addJavadoc("segment matches its text exactly, and the segment counts are equal.\n")
                .addStatement("int start = 0")
                .beginControlFlow("for (int i = 0; i < segments.length; i++)")
                .addStatement("int slash = path.indexOf('/', start)")
                .addStatement("boolean last = i == segments.length - 1")
                .beginControlFlow("if (last != (slash < 0 || slash >= end))")
                .addStatement("return false")
                .endControlFlow()
                .addStatement("int segmentEnd = last ? end : slash")
                .addStatement("String segment = segments[i]")
                .beginControlFlow("if (segment == null ? segmentEnd == start\n"
                        + ": segment.length() != segmentEnd - start\n"
                        + "        || !path.regionMatches(start, segment, 0, segment.length()))")
                .addStatement("return false")
                .endControlFlow()
                .addStatement("start = segmentEnd + 1")
                .endControlFlow()
                .addStatement("return true")
                .build();
        return TypeSpec.recordBuilder(rowType.simpleName())
                .addModifiers(Modifier.PRIVATE)
                .addJavadoc("One route: its template split on {@code /}, a placeholder as {@code null}.\n")
                .recordConstructor(MethodSpec.constructorBuilder()
                        .addParameter(ArrayTypeName.of(String.class), "segments")
                        .addParameter(ROUTE_REQUIREMENT, "requirement")
                        .build())
                .addMethod(matches)
                .build();
    }
}
