package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Emits the generated handler test cases for optimistic-lock versioning and soft delete (ADR-058, ADR-076).
 */
final class KernelHandlerStateTests {

    private static final String FIXED_ID = "00000000-0000-4000-8000-000000000001";
    private static final String AS_TENANT = "asTenant";

    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName ASSERTIONS = ClassName.get("org.assertj.core.api", "Assertions");
    private static final ClassName HTTP_STATUS = ClassName.get("eu.exeris.kernel.spi.http", "HttpStatus");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName SCOPED_VALUE = ClassName.get("java.lang", "ScopedValue");
    private static final ClassName HTTP_KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpKernelProviders");
    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");

    private KernelHandlerStateTests() {
    }

    /**
     * Emits the state test cases for the entity: stale optimistic-lock version on update (409) and
     * soft-deleted row get/list/update behaviour.
     */
    static List<MethodSpec> cases(DomainMetadata metadata, ClassName entityType, ClassName handlerType,
                                  ClassName exchangeType, ClassName bodyType, ClassName stubType,
                                  String basePath, boolean tenantScoped,
                                  Map<String, CodeBlock> updateBaseline) {
        List<MethodSpec> cases = new ArrayList<>();
        if (metadata.versioned() && updateBaseline != null) {
            cases.add(updateStaleVersionTest(metadata, entityType, handlerType, exchangeType,
                    bodyType, stubType, basePath, tenantScoped, updateBaseline));
        }
        if (metadata.softDelete()) {
            cases.add(getByIdSoftDeletedTest(metadata, entityType, handlerType, exchangeType,
                    stubType, basePath, tenantScoped));
            cases.add(listSoftDeletedTest(metadata, entityType, handlerType, exchangeType,
                    stubType, basePath, tenantScoped));
            if (!metadata.versioned() && updateBaseline != null) {
                cases.add(updateSoftDeletedTest(metadata, entityType, handlerType, exchangeType,
                        bodyType, stubType, basePath, tenantScoped, updateBaseline));
            }
        }
        return List.copyOf(cases);
    }

    static String softDeleteField(DomainMetadata metadata) {
        if (metadata.systemFields() != null && metadata.systemFields().softDeleteField() != null
                && !metadata.systemFields().softDeleteField().isBlank()) {
            return metadata.systemFields().softDeleteField();
        }
        return "deleted";
    }

    static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static MethodSpec updateStaleVersionTest(DomainMetadata metadata, ClassName entityType,
                                                    ClassName handlerType, ClassName exchangeType,
                                                    ClassName bodyType, ClassName stubType,
                                                    String basePath, boolean tenantScoped,
                                                    Map<String, CodeBlock> baseline) {
        MethodSpec.Builder m = test("handleUpdateRespondsConflictOnStaleVersion");
        m.addJavadoc("A stale optimistic-lock version answers 409 Conflict (ADR-076).\n");
        m.addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T body = new $T()", bodyType, bodyType)
                .addStatement("$T handler = newHandler(service, body)", handlerType)
                .addStatement("$T decoded = new $T()", entityType, entityType);
        for (KernelValidationRules.FieldRules fr : KernelValidationRules.onUpdate(metadata)) {
            CodeBlock staged = baseline.get(fr.field().name());
            if (staged != null) {
                m.addStatement("decoded.$L($L)", fr.mutator(), staged);
            }
        }
        m.addStatement("body.next = decoded")
                .addStatement("service.rowExists = false")
                .addStatement("$T exchange = $T.put($S, body).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID);
        if (tenantScoped) {
            m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                            + ".where($T.STORAGE_CONTEXT, TENANT_SCOPE)\n"
                            + ".run(() -> handler.handleUpdate(exchange))",
                    SCOPED_VALUE, HTTP_KERNEL_PROVIDERS, KERNEL_PROVIDERS);
        } else {
            m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                            + ".run(() -> handler.handleUpdate(exchange))",
                    SCOPED_VALUE, HTTP_KERNEL_PROVIDERS);
        }
        m.addStatement("$T.assertThat(exchange.status()).isEqualTo($T.CONFLICT)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS);
        return m.build();
    }

    private static MethodSpec getByIdSoftDeletedTest(DomainMetadata metadata, ClassName entityType,
                                                    ClassName handlerType, ClassName exchangeType,
                                                    ClassName stubType, String basePath,
                                                    boolean tenantScoped) {
        String setter = "set" + capitalize(softDeleteField(metadata));
        return test("handleGetByIdRespondsNotFoundWhenRowIsSoftDeleted")
                .addJavadoc("A soft-deleted row answers 404, the same status an absent row answers.\n")
                .addStatement("$T found = new $T()", entityType, entityType)
                .addStatement("found.$L(true)", setter)
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.byId = $T.of(found)", OPTIONAL)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.get($S).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement(invoke("handleGetById", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.NOT_FOUND)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.lookedUp).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID)
                .build();
    }

    private static MethodSpec listSoftDeletedTest(DomainMetadata metadata, ClassName entityType,
                                                 ClassName handlerType, ClassName exchangeType,
                                                 ClassName stubType, String basePath,
                                                 boolean tenantScoped) {
        ClassName pageType = KernelListQueryGenerator.pageType(metadata);
        String setter = "set" + capitalize(softDeleteField(metadata));
        return test("handleGetAllExcludesSoftDeletedRows")
                .addJavadoc("Soft-deleted rows are filtered from the returned page.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T live = new $T()", entityType, entityType)
                .addStatement("live.$L(false)", setter)
                .addStatement("$T deleted = new $T()", entityType, entityType)
                .addStatement("deleted.$L(true)", setter)
                .addStatement("service.page = $T.of($T.of(live, deleted), 2L, 0, 20)", pageType, LIST)
                .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType, basePath)
                .addStatement(invoke("handleGetAll", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat((($T) exchange.body()).content()).containsExactly(live)",
                        ASSERTIONS, pageType)
                .build();
    }

    private static MethodSpec updateSoftDeletedTest(DomainMetadata metadata, ClassName entityType,
                                                   ClassName handlerType, ClassName exchangeType,
                                                   ClassName bodyType, ClassName stubType,
                                                   String basePath, boolean tenantScoped,
                                                   Map<String, CodeBlock> baseline) {
        MethodSpec.Builder m = test("handleUpdateRespondsNotFoundWhenRowIsSoftDeleted");
        m.addJavadoc("Updating a soft-deleted row on an unversioned entity answers 404.\n");
        m.addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T body = new $T()", bodyType, bodyType)
                .addStatement("$T handler = newHandler(service, body)", handlerType)
                .addStatement("$T decoded = new $T()", entityType, entityType);
        for (KernelValidationRules.FieldRules fr : KernelValidationRules.onUpdate(metadata)) {
            CodeBlock staged = baseline.get(fr.field().name());
            if (staged != null) {
                m.addStatement("decoded.$L($L)", fr.mutator(), staged);
            }
        }
        m.addStatement("body.next = decoded")
                .addStatement("service.rowDeleted = true")
                .addStatement("$T exchange = $T.put($S, body).withPathParam($S, $S)",
                        exchangeType, exchangeType, basePath + "/" + FIXED_ID, "id", FIXED_ID);
        if (tenantScoped) {
            m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                            + ".where($T.STORAGE_CONTEXT, TENANT_SCOPE)\n"
                            + ".run(() -> handler.handleUpdate(exchange))",
                    SCOPED_VALUE, HTTP_KERNEL_PROVIDERS, KERNEL_PROVIDERS);
        } else {
            m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                            + ".run(() -> handler.handleUpdate(exchange))",
                    SCOPED_VALUE, HTTP_KERNEL_PROVIDERS);
        }
        m.addStatement("$T.assertThat(exchange.status()).isEqualTo($T.NOT_FOUND)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS);
        return m.build();
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
