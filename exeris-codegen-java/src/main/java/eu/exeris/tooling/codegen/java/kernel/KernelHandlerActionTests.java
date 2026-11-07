package eu.exeris.tooling.codegen.java.kernel;

import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.ASSERTIONS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.FIXED_ID;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.HTTP_KERNEL_PROVIDERS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.HTTP_STATUS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.KERNEL_PROVIDERS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.OPTIONAL;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.REQUEST_BODY_DECODE_EXCEPTION;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.SCOPED_VALUE;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.UUID;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.invoke;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.test;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.ParamSource;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits the generated handler test cases for {@code @Action} methods (ADR-058).
 */
final class KernelHandlerActionTests {

    private static final String HANDLE = "handle";
    private static final String ID = "id";
    private static final String ASSERT_NOT_UPDATED = "$T.assertThat(service.updatedId).isNull()";
    private static final String ASSERT_STATUS = "$T.assertThat(exchange.status()).isEqualTo($T.$L)";

    private KernelHandlerActionTests() {
    }

    /** What every case of one action shares: the types it names and how its request is shaped. */
    private record ActionCase(ActionMetadata action, ClassName entityType, ClassName handlerType,
                              ClassName exchangeType, ClassName bodyType, ClassName stubType,
                              ClassName requestType, List<ActionParamMetadata> bodyParams,
                              boolean tenantScoped) {

        boolean hasBodyParams() {
            return requestType != null;
        }

        String handlerMethod() {
            return HANDLE + NameCasing.pascal(action.name());
        }
    }

    /**
     * Emits the test cases for each respond-once {@code @Action} on the entity: 200 OK with the
     * updated row on success, 404 for a missing row, 400 for an undecodable body, 409 for a stale
     * version on a versioned entity, and 400 when a required query parameter is missing.
     */
    static List<MethodSpec> cases(DomainMetadata metadata, ClassName entityType, ClassName handlerType,
                                  ClassName exchangeType, ClassName bodyType, ClassName stubType,
                                  String basePath, boolean tenantScoped) {
        List<MethodSpec> cases = new ArrayList<>();
        for (ActionMetadata action : metadata.actions()) {
            if (action.streaming()) {
                continue;
            }
            List<ActionParamMetadata> bodyParams = action.params().stream()
                    .filter(p -> p.effectiveBindingSource() == ParamSource.BODY)
                    .toList();
            ClassName requestType = bodyParams.isEmpty()
                    ? null
                    : handlerType.nestedClass(KernelHandlerGenerator.actionRequestName(action));
            ActionCase actionCase = new ActionCase(action, entityType, handlerType, exchangeType, bodyType,
                    stubType, requestType, bodyParams, tenantScoped);
            cases.addAll(casesFor(actionCase, metadata.versioned(), basePath));
        }
        return List.copyOf(cases);
    }

    private static List<MethodSpec> casesFor(ActionCase c, boolean versioned, String basePath) {
        List<ActionParamMetadata> queryParams = c.action().params().stream()
                .filter(p -> p.effectiveBindingSource() == ParamSource.QUERY)
                .toList();
        String actionPath = basePath + "/" + FIXED_ID + "/actions/" + NameCasing.kebab(c.action().name());
        String url = withQuery(actionPath, actionQueryString(queryParams, null));

        List<MethodSpec> cases = new ArrayList<>();
        cases.add(actionSuccessTest(c, url));
        cases.add(actionMissingRowTest(c, url));
        if (c.hasBodyParams()) {
            cases.add(actionUndecodableBodyTest(c, url));
        }
        if (versioned) {
            cases.add(actionStaleVersionTest(c, url));
        }
        queryParams.stream()
                .filter(p -> p.required() && (p.defaultValue() == null || p.defaultValue().isBlank()))
                .findFirst()
                .ifPresent(missing -> cases.add(actionMissingRequiredQueryParamTest(c,
                        withQuery(actionPath, actionQueryString(queryParams, missing)))));
        return cases;
    }

    private static String withQuery(String path, String query) {
        return query.isEmpty() ? path : path + "?" + query;
    }

    /**
     * The handler, the request body when the action decodes one, and the exchange for {@code url},
     * then the dispatch.
     */
    private static void arrangeAndDispatch(MethodSpec.Builder m, ActionCase c, String url) {
        if (c.hasBodyParams()) {
            m.addStatement("$T body = new $T()", c.bodyType(), c.bodyType())
                    .addStatement("$T handler = newHandler(service, body)", c.handlerType())
                    .addStatement("body.next = new $T($L)", c.requestType(), actionRequestArgs(c.bodyParams()))
                    .addStatement("$T exchange = $T.post($S, body).withPathParam($S, $S)",
                            c.exchangeType(), c.exchangeType(), url, ID, FIXED_ID);
        } else {
            m.addStatement("$T handler = newHandler(service)", c.handlerType())
                    .addStatement("$T exchange = $T.post($S).withPathParam($S, $S)",
                            c.exchangeType(), c.exchangeType(), url, ID, FIXED_ID);
        }
        appendActionDispatch(m, c.handlerMethod(), c.hasBodyParams(), c.tenantScoped());
    }

    private static MethodSpec actionSuccessTest(ActionCase c, String url) {
        MethodSpec.Builder m = test(c.handlerMethod() + "RespondsOkWithUpdatedEntity");
        m.addJavadoc("An action call on an existing entity updates the aggregate and responds 200 OK.\n");
        m.addStatement("$T entity = new $T()", c.entityType(), c.entityType())
                .addStatement("$T service = new $T()", c.stubType(), c.stubType())
                .addStatement("service.byId = $T.of(entity)", OPTIONAL);
        arrangeAndDispatch(m, c, url);
        if (c.hasBodyParams()) {
            m.addStatement("$T.assertThat(body.decodedType).isEqualTo($T.class)", ASSERTIONS, c.requestType());
        }
        m.addStatement(ASSERT_STATUS, ASSERTIONS, HTTP_STATUS, "OK")
                .addStatement("$T.assertThat(exchange.body()).isSameAs(entity)", ASSERTIONS)
                .addStatement("$T.assertThat(service.updatedId).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID);
        return m.build();
    }

    private static MethodSpec actionMissingRowTest(ActionCase c, String url) {
        MethodSpec.Builder m = test(c.handlerMethod() + "RespondsNotFoundWhenRowIsAbsent");
        m.addJavadoc("An action call on an absent entity responds 404 Not Found.\n");
        m.addStatement("$T service = new $T()", c.stubType(), c.stubType())
                .addStatement("service.byId = $T.empty()", OPTIONAL);
        arrangeAndDispatch(m, c, url);
        m.addStatement(ASSERT_STATUS, ASSERTIONS, HTTP_STATUS, "NOT_FOUND")
                .addStatement(ASSERT_NOT_UPDATED, ASSERTIONS);
        return m.build();
    }

    private static MethodSpec actionUndecodableBodyTest(ActionCase c, String url) {
        MethodSpec.Builder m = test(c.handlerMethod() + "RespondsBadRequestOnAnUndecodableBody");
        m.addJavadoc("An undecodable action request body responds 400 Bad Request.\n");
        m.addStatement("$T service = new $T()", c.stubType(), c.stubType())
                .addStatement("$T body = new $T()", c.bodyType(), c.bodyType())
                .addStatement("body.failure = $T.malformedBody($T.class.getName(), 0L, null)",
                        REQUEST_BODY_DECODE_EXCEPTION, c.requestType())
                .addStatement("$T handler = newHandler(service, body)", c.handlerType())
                .addStatement("$T exchange = $T.post($S, body).withPathParam($S, $S)",
                        c.exchangeType(), c.exchangeType(), url, ID, FIXED_ID);
        appendActionDispatch(m, c.handlerMethod(), true, c.tenantScoped());
        m.addStatement(ASSERT_STATUS, ASSERTIONS, HTTP_STATUS, "BAD_REQUEST")
                .addStatement(ASSERT_NOT_UPDATED, ASSERTIONS);
        return m.build();
    }

    private static MethodSpec actionStaleVersionTest(ActionCase c, String url) {
        MethodSpec.Builder m = test(c.handlerMethod() + "RespondsConflictOnStaleVersion");
        m.addJavadoc("A stale optimistic-lock version on an action call answers 409 Conflict.\n");
        m.addStatement("$T entity = new $T()", c.entityType(), c.entityType())
                .addStatement("$T service = new $T()", c.stubType(), c.stubType())
                .addStatement("service.byId = $T.of(entity)", OPTIONAL)
                .addStatement("service.rowExists = false");
        arrangeAndDispatch(m, c, url);
        m.addStatement(ASSERT_STATUS, ASSERTIONS, HTTP_STATUS, "CONFLICT")
                .addStatement(ASSERT_NOT_UPDATED, ASSERTIONS);
        return m.build();
    }

    private static MethodSpec actionMissingRequiredQueryParamTest(ActionCase c, String missingUrl) {
        MethodSpec.Builder m = test(c.handlerMethod() + "RespondsBadRequestWhenRequiredQueryParameterIsMissing");
        m.addJavadoc("Omitting a required query parameter answers 400 Bad Request.\n");
        m.addStatement("$T service = new $T()", c.stubType(), c.stubType());
        arrangeAndDispatch(m, c, missingUrl);
        m.addStatement(ASSERT_STATUS, ASSERTIONS, HTTP_STATUS, "BAD_REQUEST")
                .addStatement(ASSERT_NOT_UPDATED, ASSERTIONS);
        return m.build();
    }

    static void appendActionDispatch(MethodSpec.Builder method, String handlerMethod,
                                     boolean hasBodyParams, boolean tenantScoped) {
        if (hasBodyParams) {
            if (tenantScoped) {
                method.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                                + ".where($T.STORAGE_CONTEXT, TENANT_SCOPE)\n"
                                + ".run(() -> handler.$L(exchange))",
                        SCOPED_VALUE, HTTP_KERNEL_PROVIDERS, KERNEL_PROVIDERS, handlerMethod);
            } else {
                method.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                                + ".run(() -> handler.$L(exchange))",
                        SCOPED_VALUE, HTTP_KERNEL_PROVIDERS, handlerMethod);
            }
        } else {
            method.addStatement(invoke(handlerMethod, tenantScoped));
        }
    }

    static CodeBlock actionRequestArgs(List<ActionParamMetadata> bodyParams) {
        List<CodeBlock> args = new ArrayList<>();
        for (ActionParamMetadata p : bodyParams) {
            args.add(sampleActionParamValue(p));
        }
        return CodeBlock.join(args, ", ");
    }

    private static CodeBlock sampleActionParamValue(ActionParamMetadata p) {
        String type = p.type();
        if ("char".equals(type) || "Character".equals(type) || "java.lang.Character".equals(type)) {
            return CodeBlock.of("'a'");
        }
        return KernelTestSamples.of(type);
    }

    private static String sampleQueryValue(ActionParamMetadata p) {
        String type = p.type();
        if ("char".equals(type) || "Character".equals(type) || "java.lang.Character".equals(type)) {
            return "a";
        }
        if ("boolean".equals(type) || "Boolean".equals(type) || "java.lang.Boolean".equals(type)) {
            return "true";
        }
        if ("LocalDate".equals(type) || "java.time.LocalDate".equals(type)) {
            return "2000-01-01";
        }
        if ("Instant".equals(type) || "java.time.Instant".equals(type)) {
            return "1970-01-01T00:00:00Z";
        }
        if ("UUID".equals(type) || "java.util.UUID".equals(type)) {
            return FIXED_ID;
        }
        if ("float".equals(type) || "Float".equals(type) || "java.lang.Float".equals(type)
                || "double".equals(type) || "Double".equals(type) || "java.lang.Double".equals(type)) {
            return "7.0";
        }
        if ("int".equals(type) || "Integer".equals(type) || "java.lang.Integer".equals(type)
                || "long".equals(type) || "Long".equals(type) || "java.lang.Long".equals(type)
                || "short".equals(type) || "Short".equals(type) || "java.lang.Short".equals(type)
                || "byte".equals(type) || "Byte".equals(type) || "java.lang.Byte".equals(type)
                || "BigDecimal".equals(type) || "java.math.BigDecimal".equals(type)) {
            return "7";
        }
        return "sample";
    }

    private static String actionQueryString(List<ActionParamMetadata> queryParams,
                                            ActionParamMetadata exclude) {
        StringBuilder sb = new StringBuilder();
        for (ActionParamMetadata p : queryParams) {
            if (exclude != null && p.name().equals(exclude.name())) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("&");
            }
            sb.append(p.effectiveQueryKey()).append("=").append(sampleQueryValue(p));
        }
        return sb.toString();
    }
}
