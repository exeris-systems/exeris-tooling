package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.ParamSource;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits the generated handler test cases for {@code @Action} methods (ADR-058).
 */
final class KernelHandlerActionTests {

    private static final String FIXED_ID = "00000000-0000-4000-8000-000000000001";
    private static final String AS_TENANT = "asTenant";

    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName ASSERTIONS = ClassName.get("org.assertj.core.api", "Assertions");
    private static final ClassName HTTP_STATUS = ClassName.get("eu.exeris.kernel.spi.http", "HttpStatus");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName SCOPED_VALUE = ClassName.get("java.lang", "ScopedValue");
    private static final ClassName HTTP_KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpKernelProviders");
    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName REQUEST_BODY_DECODE_EXCEPTION =
            ClassName.get("eu.exeris.kernel.spi.exceptions.http", "RequestBodyDecodeException");

    private KernelHandlerActionTests() {
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
            List<ActionParamMetadata> queryParams = action.params().stream()
                    .filter(p -> p.effectiveBindingSource() == ParamSource.QUERY)
                    .toList();
            List<ActionParamMetadata> bodyParams = action.params().stream()
                    .filter(p -> p.effectiveBindingSource() == ParamSource.BODY)
                    .toList();

            boolean hasBodyParams = !bodyParams.isEmpty();
            ClassName requestType = hasBodyParams
                    ? handlerType.nestedClass(KernelHandlerGenerator.actionRequestName(action))
                    : null;
            String actionName = action.name();
            String pascalAction = NameCasing.pascal(actionName);
            String handlerMethod = "handle" + pascalAction;
            String actionPath = basePath + "/" + FIXED_ID + "/actions/" + NameCasing.kebab(actionName);
            String fullQuery = actionQueryString(queryParams, null);
            String url = fullQuery.isEmpty() ? actionPath : actionPath + "?" + fullQuery;

            cases.add(actionSuccessTest(action, entityType, handlerType, exchangeType, bodyType,
                    stubType, url, handlerMethod, hasBodyParams, requestType, bodyParams, tenantScoped));

            cases.add(actionMissingRowTest(action, handlerType, exchangeType, bodyType,
                    stubType, url, handlerMethod, hasBodyParams, requestType, bodyParams, tenantScoped));

            if (hasBodyParams) {
                cases.add(actionUndecodableBodyTest(action, handlerType, exchangeType,
                        bodyType, stubType, url, handlerMethod, requestType, tenantScoped));
            }

            if (metadata.versioned()) {
                cases.add(actionStaleVersionTest(action, entityType, handlerType, exchangeType,
                        bodyType, stubType, url, handlerMethod, hasBodyParams, requestType, bodyParams, tenantScoped));
            }

            ActionParamMetadata requiredQuery = queryParams.stream()
                    .filter(p -> p.required() && (p.defaultValue() == null || p.defaultValue().isBlank()))
                    .findFirst()
                    .orElse(null);
            if (requiredQuery != null) {
                String missingQuery = actionQueryString(queryParams, requiredQuery);
                String missingUrl = missingQuery.isEmpty() ? actionPath : actionPath + "?" + missingQuery;
                cases.add(actionMissingRequiredQueryParamTest(action, handlerType,
                        exchangeType, bodyType, stubType, missingUrl, handlerMethod, hasBodyParams,
                        requestType, bodyParams, tenantScoped));
            }
        }
        return List.copyOf(cases);
    }

    private static MethodSpec actionSuccessTest(ActionMetadata action, ClassName entityType,
                                                ClassName handlerType, ClassName exchangeType,
                                                ClassName bodyType, ClassName stubType,
                                                String url, String handlerMethod, boolean hasBodyParams,
                                                ClassName requestType, List<ActionParamMetadata> bodyParams,
                                                boolean tenantScoped) {
        String pascalAction = NameCasing.pascal(action.name());
        MethodSpec.Builder m = test("handle" + pascalAction + "RespondsOkWithUpdatedEntity");
        m.addJavadoc("An action call on an existing entity updates the aggregate and responds 200 OK.\n");
        m.addStatement("$T entity = new $T()", entityType, entityType)
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.byId = $T.of(entity)", OPTIONAL);
        if (hasBodyParams) {
            m.addStatement("$T body = new $T()", bodyType, bodyType)
                    .addStatement("$T handler = newHandler(service, body)", handlerType)
                    .addStatement("body.next = new $T($L)", requestType, actionRequestArgs(bodyParams))
                    .addStatement("$T exchange = $T.post($S, body).withPathParam($S, $S)",
                            exchangeType, exchangeType, url, "id", FIXED_ID);
            appendActionDispatch(m, handlerMethod, true, tenantScoped);
            m.addStatement("$T.assertThat(body.decodedType).isEqualTo($T.class)", ASSERTIONS, requestType);
        } else {
            m.addStatement("$T handler = newHandler(service)", handlerType)
                    .addStatement("$T exchange = $T.post($S).withPathParam($S, $S)",
                            exchangeType, exchangeType, url, "id", FIXED_ID);
            appendActionDispatch(m, handlerMethod, false, tenantScoped);
        }
        m.addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(exchange.body()).isSameAs(entity)", ASSERTIONS)
                .addStatement("$T.assertThat(service.updatedId).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID);
        return m.build();
    }

    private static MethodSpec actionMissingRowTest(ActionMetadata action,
                                                   ClassName handlerType, ClassName exchangeType,
                                                   ClassName bodyType, ClassName stubType,
                                                   String url, String handlerMethod, boolean hasBodyParams,
                                                   ClassName requestType, List<ActionParamMetadata> bodyParams,
                                                   boolean tenantScoped) {
        String pascalAction = NameCasing.pascal(action.name());
        MethodSpec.Builder m = test("handle" + pascalAction + "RespondsNotFoundWhenRowIsAbsent");
        m.addJavadoc("An action call on an absent entity responds 404 Not Found.\n");
        m.addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.byId = $T.empty()", OPTIONAL);
        if (hasBodyParams) {
            m.addStatement("$T body = new $T()", bodyType, bodyType)
                    .addStatement("$T handler = newHandler(service, body)", handlerType)
                    .addStatement("body.next = new $T($L)", requestType, actionRequestArgs(bodyParams))
                    .addStatement("$T exchange = $T.post($S, body).withPathParam($S, $S)",
                            exchangeType, exchangeType, url, "id", FIXED_ID);
            appendActionDispatch(m, handlerMethod, true, tenantScoped);
        } else {
            m.addStatement("$T handler = newHandler(service)", handlerType)
                    .addStatement("$T exchange = $T.post($S).withPathParam($S, $S)",
                            exchangeType, exchangeType, url, "id", FIXED_ID);
            appendActionDispatch(m, handlerMethod, false, tenantScoped);
        }
        m.addStatement("$T.assertThat(exchange.status()).isEqualTo($T.NOT_FOUND)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS);
        return m.build();
    }

    private static MethodSpec actionUndecodableBodyTest(ActionMetadata action,
                                                        ClassName handlerType, ClassName exchangeType,
                                                        ClassName bodyType, ClassName stubType,
                                                        String url, String handlerMethod,
                                                        ClassName requestType, boolean tenantScoped) {
        String pascalAction = NameCasing.pascal(action.name());
        MethodSpec.Builder m = test("handle" + pascalAction + "RespondsBadRequestOnAnUndecodableBody");
        m.addJavadoc("An undecodable action request body responds 400 Bad Request.\n");
        m.addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T body = new $T()", bodyType, bodyType)
                .addStatement("body.failure = $T.malformedBody($T.class.getName(), 0L, null)",
                        REQUEST_BODY_DECODE_EXCEPTION, requestType)
                .addStatement("$T handler = newHandler(service, body)", handlerType)
                .addStatement("$T exchange = $T.post($S, body).withPathParam($S, $S)",
                        exchangeType, exchangeType, url, "id", FIXED_ID);
        appendActionDispatch(m, handlerMethod, true, tenantScoped);
        m.addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS);
        return m.build();
    }

    private static MethodSpec actionStaleVersionTest(ActionMetadata action, ClassName entityType,
                                                     ClassName handlerType, ClassName exchangeType,
                                                     ClassName bodyType, ClassName stubType,
                                                     String url, String handlerMethod, boolean hasBodyParams,
                                                     ClassName requestType, List<ActionParamMetadata> bodyParams,
                                                     boolean tenantScoped) {
        String pascalAction = NameCasing.pascal(action.name());
        MethodSpec.Builder m = test("handle" + pascalAction + "RespondsConflictOnStaleVersion");
        m.addJavadoc("A stale optimistic-lock version on an action call answers 409 Conflict.\n");
        m.addStatement("$T entity = new $T()", entityType, entityType)
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.byId = $T.of(entity)", OPTIONAL)
                .addStatement("service.rowExists = false");
        if (hasBodyParams) {
            m.addStatement("$T body = new $T()", bodyType, bodyType)
                    .addStatement("$T handler = newHandler(service, body)", handlerType)
                    .addStatement("body.next = new $T($L)", requestType, actionRequestArgs(bodyParams))
                    .addStatement("$T exchange = $T.post($S, body).withPathParam($S, $S)",
                            exchangeType, exchangeType, url, "id", FIXED_ID);
            appendActionDispatch(m, handlerMethod, true, tenantScoped);
        } else {
            m.addStatement("$T handler = newHandler(service)", handlerType)
                    .addStatement("$T exchange = $T.post($S).withPathParam($S, $S)",
                            exchangeType, exchangeType, url, "id", FIXED_ID);
            appendActionDispatch(m, handlerMethod, false, tenantScoped);
        }
        m.addStatement("$T.assertThat(exchange.status()).isEqualTo($T.CONFLICT)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS);
        return m.build();
    }

    private static MethodSpec actionMissingRequiredQueryParamTest(ActionMetadata action,
                                                                  ClassName handlerType, ClassName exchangeType,
                                                                  ClassName bodyType, ClassName stubType,
                                                                  String missingUrl, String handlerMethod,
                                                                  boolean hasBodyParams, ClassName requestType,
                                                                  List<ActionParamMetadata> bodyParams,
                                                                  boolean tenantScoped) {
        String pascalAction = NameCasing.pascal(action.name());
        MethodSpec.Builder m = test("handle" + pascalAction
                + "RespondsBadRequestWhenRequiredQueryParameterIsMissing");
        m.addJavadoc("Omitting a required query parameter answers 400 Bad Request.\n");
        m.addStatement("$T service = new $T()", stubType, stubType);
        if (hasBodyParams) {
            m.addStatement("$T body = new $T()", bodyType, bodyType)
                    .addStatement("$T handler = newHandler(service, body)", handlerType)
                    .addStatement("body.next = new $T($L)", requestType, actionRequestArgs(bodyParams))
                    .addStatement("$T exchange = $T.post($S, body).withPathParam($S, $S)",
                            exchangeType, exchangeType, missingUrl, "id", FIXED_ID);
            appendActionDispatch(m, handlerMethod, true, tenantScoped);
        } else {
            m.addStatement("$T handler = newHandler(service)", handlerType)
                    .addStatement("$T exchange = $T.post($S).withPathParam($S, $S)",
                            exchangeType, exchangeType, missingUrl, "id", FIXED_ID);
            appendActionDispatch(m, handlerMethod, false, tenantScoped);
        }
        m.addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS);
        return m.build();
    }

    private static void appendActionDispatch(MethodSpec.Builder method, String handlerMethod,
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

    private static CodeBlock actionRequestArgs(List<ActionParamMetadata> bodyParams) {
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

    private static String invoke(String handlerMethod, boolean tenantScoped) {
        String call = "handler." + handlerMethod + "(exchange)";
        return tenantScoped ? AS_TENANT + "(() -> " + call + ")" : call;
    }

    private static MethodSpec.Builder test(String name) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(TEST)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID);
    }
}
