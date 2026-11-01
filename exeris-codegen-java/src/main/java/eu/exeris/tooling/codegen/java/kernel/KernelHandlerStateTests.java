package eu.exeris.tooling.codegen.java.kernel;

import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.ASSERTIONS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.FIXED_ID;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.HTTP_KERNEL_PROVIDERS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.HTTP_STATUS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.KERNEL_PROVIDERS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.LIST;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.OPTIONAL;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.SCOPED_VALUE;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.UUID;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.invoke;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.test;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Emits the generated handler test cases for optimistic-lock versioning and soft delete (ADR-058, ADR-076).
 */
final class KernelHandlerStateTests {

    private KernelHandlerStateTests() {
    }

    /** What every state case of one entity shares. */
    private record StateCase(DomainMetadata metadata, ClassName entityType, ClassName handlerType,
                             ClassName exchangeType, ClassName bodyType, ClassName stubType,
                             String basePath, boolean tenantScoped, Map<String, CodeBlock> baseline) {
    }

    /**
     * Emits the state test cases for the entity: stale optimistic-lock version on update (409) and
     * soft-deleted row get/list/update behaviour.
     */
    static List<MethodSpec> cases(DomainMetadata metadata, ClassName entityType, ClassName handlerType,
                                  ClassName exchangeType, ClassName bodyType, ClassName stubType,
                                  String basePath, boolean tenantScoped,
                                  Map<String, CodeBlock> updateBaseline) {
        StateCase c = new StateCase(metadata, entityType, handlerType, exchangeType, bodyType, stubType,
                basePath, tenantScoped, updateBaseline);
        List<MethodSpec> cases = new ArrayList<>();
        if (metadata.versioned() && updateBaseline != null) {
            cases.add(updateRefusedTest(c, "handleUpdateRespondsConflictOnStaleVersion",
                    "A stale optimistic-lock version answers 409 Conflict (ADR-076).\n",
                    "service.rowExists = false", "CONFLICT"));
        }
        if (metadata.softDelete()) {
            cases.add(getByIdSoftDeletedTest(c));
            cases.add(listSoftDeletedTest(c));
            if (!metadata.versioned() && updateBaseline != null) {
                cases.add(updateRefusedTest(c, "handleUpdateRespondsNotFoundWhenRowIsSoftDeleted",
                        "Updating a soft-deleted row on an unversioned entity answers 404.\n",
                        "service.rowDeleted = true", "NOT_FOUND"));
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

    /**
     * A PUT of a valid body that the stub refuses through {@code rowState}, answered with
     * {@code status} and no update.
     */
    private static MethodSpec updateRefusedTest(StateCase c, String name, String javadoc, String rowState,
                                                String status) {
        MethodSpec.Builder m = test(name);
        m.addJavadoc(javadoc);
        m.addStatement("$T service = new $T()", c.stubType(), c.stubType())
                .addStatement("$T body = new $T()", c.bodyType(), c.bodyType())
                .addStatement("$T handler = newHandler(service, body)", c.handlerType())
                .addStatement("$T decoded = new $T()", c.entityType(), c.entityType());
        for (KernelValidationRules.FieldRules fr : KernelValidationRules.onUpdate(c.metadata())) {
            CodeBlock staged = c.baseline().get(fr.field().name());
            if (staged != null) {
                m.addStatement("decoded.$L($L)", fr.mutator(), staged);
            }
        }
        m.addStatement("body.next = decoded")
                .addStatement(rowState)
                .addStatement("$T exchange = $T.put($S, body).withPathParam($S, $S)",
                        c.exchangeType(), c.exchangeType(), c.basePath() + "/" + FIXED_ID, "id", FIXED_ID);
        if (c.tenantScoped()) {
            m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                            + ".where($T.STORAGE_CONTEXT, TENANT_SCOPE)\n"
                            + ".run(() -> handler.handleUpdate(exchange))",
                    SCOPED_VALUE, HTTP_KERNEL_PROVIDERS, KERNEL_PROVIDERS);
        } else {
            m.addStatement("$T.where($T.HTTP_REQUEST_BODY_DECODER_REGISTRY, body)\n"
                            + ".run(() -> handler.handleUpdate(exchange))",
                    SCOPED_VALUE, HTTP_KERNEL_PROVIDERS);
        }
        m.addStatement("$T.assertThat(exchange.status()).isEqualTo($T.$L)", ASSERTIONS, HTTP_STATUS, status)
                .addStatement("$T.assertThat(service.updatedId).isNull()", ASSERTIONS);
        return m.build();
    }

    private static MethodSpec getByIdSoftDeletedTest(StateCase c) {
        String setter = "set" + capitalize(softDeleteField(c.metadata()));
        return test("handleGetByIdRespondsNotFoundWhenRowIsSoftDeleted")
                .addJavadoc("A soft-deleted row answers 404, the same status an absent row answers.\n")
                .addStatement("$T found = new $T()", c.entityType(), c.entityType())
                .addStatement("found.$L(true)", setter)
                .addStatement("$T service = new $T()", c.stubType(), c.stubType())
                .addStatement("service.byId = $T.of(found)", OPTIONAL)
                .addStatement("$T handler = newHandler(service)", c.handlerType())
                .addStatement("$T exchange = $T.get($S).withPathParam($S, $S)",
                        c.exchangeType(), c.exchangeType(), c.basePath() + "/" + FIXED_ID, "id", FIXED_ID)
                .addStatement(invoke("handleGetById", c.tenantScoped()))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.NOT_FOUND)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.lookedUp).isEqualTo($T.fromString($S))",
                        ASSERTIONS, UUID, FIXED_ID)
                .build();
    }

    private static MethodSpec listSoftDeletedTest(StateCase c) {
        ClassName pageType = KernelListQueryGenerator.pageType(c.metadata());
        String setter = "set" + capitalize(softDeleteField(c.metadata()));
        return test("handleGetAllExcludesSoftDeletedRows")
                .addJavadoc("Soft-deleted rows are filtered from the returned page.\n")
                .addStatement("$T service = new $T()", c.stubType(), c.stubType())
                .addStatement("$T handler = newHandler(service)", c.handlerType())
                .addStatement("$T live = new $T()", c.entityType(), c.entityType())
                .addStatement("live.$L(false)", setter)
                .addStatement("$T deleted = new $T()", c.entityType(), c.entityType())
                .addStatement("deleted.$L(true)", setter)
                .addStatement("service.page = $T.of($T.of(live, deleted), 2L, 0, 20)", pageType, LIST)
                .addStatement("$T exchange = $T.get($S)", c.exchangeType(), c.exchangeType(), c.basePath())
                .addStatement(invoke("handleGetAll", c.tenantScoped()))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat((($T) exchange.body()).content()).containsExactly(live)",
                        ASSERTIONS, pageType)
                .build();
    }
}
