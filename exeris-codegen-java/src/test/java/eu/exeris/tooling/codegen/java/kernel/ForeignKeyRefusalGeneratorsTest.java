package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-076 Amendment 1: a write naming a missing row through a relationship is refused with
 * {@code <Entity>ReferenceNotFoundException} and answered 422; a delete of a row other rows still
 * reference is refused with {@code <Entity>StillReferencedException} and answered 409. Each is
 * emitted only where a foreign key of the build can produce it.
 */
@DisplayName("ADR-076 Amendment 1: foreign-key refusals in the repository, error types and handler")
class ForeignKeyRefusalGeneratorsTest {

    private static final String PACKAGE = "com.example.domain";

    private static final DomainMetadata CUSTOMER = DomainMetadata.builder("Customer", PACKAGE)
            .path("/customers")
            .fields(List.of(FieldMetadata.builder("name", "String").build()))
            .build();

    /** Two relationships whose constraint names are prefixes of one another, and one external target. */
    private static final DomainMetadata ORDER = DomainMetadata.builder("Order", PACKAGE)
            .path("/orders")
            .fields(List.of(FieldMetadata.builder("total", "BigDecimal").build()))
            .relationships(List.of(
                    manyToOne("customer", "Customer"),
                    manyToOne("customerId2", "Customer"),
                    manyToOne("ledger", "Ledger")))
            .actions(List.of(ActionMetadata.builder("approve").methodName("approve").build(),
                    ActionMetadata.builder("ship").methodName("ship").streaming(true).build()))
            .build();

    private static final DomainMetadata ARCHIVE = DomainMetadata.builder("Archive", PACKAGE)
            .path("/archives")
            .softDelete(true)
            .fields(List.of(FieldMetadata.builder("label", "String").build()))
            .build();

    private static final DomainMetadata NOTE = DomainMetadata.builder("Note", PACKAGE)
            .path("/notes")
            .fields(List.of(FieldMetadata.builder("text", "String").build()))
            .relationships(List.of(manyToOne("archive", "Archive")))
            .build();

    private static final List<DomainMetadata> BUILD = List.of(ARCHIVE, CUSTOMER, NOTE, ORDER);

    private static RelationshipMetadata manyToOne(String name, String target) {
        return RelationshipMetadata.builder(name, target)
                .type(RelationshipMetadata.RelationType.MANY_TO_ONE)
                .build();
    }

    private static String file(List<GeneratedFile> files, String className) {
        return files.stream()
                .filter(f -> f.className().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + className))
                .content();
    }

    private static List<String> classNames(List<GeneratedFile> files) {
        return files.stream().map(GeneratedFile::className).toList();
    }

    @Nested
    @DisplayName("KernelErrorGenerator")
    class Errors {

        private final KernelErrorGenerator generator = new KernelErrorGenerator();

        @Test
        @DisplayName("a referencing entity gets the reference type, carrying the relationship and the cause")
        void referencingEntityGetsReferenceNotFound() {
            List<GeneratedFile> files = generator.generateMultiple(ORDER, BUILD);

            assertThat(classNames(files)).contains("OrderReferenceNotFoundException")
                    .doesNotContain("OrderStillReferencedException");
            assertThat(file(files, "OrderReferenceNotFoundException"))
                    .contains("package com.example.repository;")
                    .contains("public OrderReferenceNotFoundException(String relation, Throwable cause) {")
                    .contains("super(\"Order names through \" + relation + \" a row that does not exist\", cause);")
                    .contains("public String relation() {")
                    .contains("422 Unprocessable Content");
        }

        @Test
        @DisplayName("a referenced entity whose delete removes the row gets the still-referenced type")
        void referencedEntityGetsStillReferenced() {
            List<GeneratedFile> files = generator.generateMultiple(CUSTOMER, BUILD);

            assertThat(classNames(files)).contains("CustomerStillReferencedException")
                    .doesNotContain("CustomerReferenceNotFoundException");
            assertThat(file(files, "CustomerStillReferencedException"))
                    .contains("public CustomerStillReferencedException(UUID id, Throwable cause) {")
                    .contains("public UUID id() {")
                    .contains("409 Conflict");
        }

        @Test
        @DisplayName("a soft-deleted target, an unreferenced entity and an entity built alone get neither")
        void neitherWhereNoForeignKeyApplies() {
            assertThat(classNames(generator.generateMultiple(ARCHIVE, BUILD)))
                    .noneMatch(name -> name.contains("Referenc"));
            assertThat(classNames(generator.generateMultiple(CUSTOMER, List.of(CUSTOMER))))
                    .noneMatch(name -> name.contains("Referenc"));
            assertThat(classNames(generator.generateMultiple(ORDER)))
                    .as("built alone, Order's targets are not in the build")
                    .noneMatch(name -> name.contains("Referenc"));
        }
    }

    @Nested
    @DisplayName("KernelRepositoryGenerator")
    class Repository {

        private final KernelRepositoryGenerator generator = new KernelRepositoryGenerator();

        @Test
        @DisplayName("save and update turn a foreign-key violation into the reference refusal")
        void writesAreGuarded() {
            String repository = generator.generate(ORDER, BUILD).content();

            assertThat(repository)
                    .contains("private static final Set<String> FOREIGN_KEY_STATES = Set.of(\"23503\", \"23506\");")
                    .contains("} catch (RuntimeException e) {\n            throw refusedReference(e);\n        }")
                    .containsOnlyOnce("private static RuntimeException refusedReference(RuntimeException failure)")
                    .contains("cause instanceof PersistenceProviderException persistence")
                    .contains("KernelErrorCodes.EX_PERS_5003.equals(persistence.errorCode())")
                    .contains("return violation ? text.toString().toLowerCase(Locale.ROOT) : null;");
            assertThat(repository.split("throw refusedReference\\(e\\);", -1))
                    .as("save, update")
                    .hasSize(3);
        }

        @Test
        @DisplayName("the longer constraint name is tested first, and an external target is not tested")
        void constraintsLongestFirst() {
            String repository = generator.generate(ORDER, BUILD).content();

            int longer = repository.indexOf("violation.contains(\"fk_orders_customer_id2_id\")");
            int shorter = repository.indexOf("violation.contains(\"fk_orders_customer_id\")");
            assertThat(longer).isPositive();
            assertThat(shorter).isGreaterThan(longer);
            assertThat(repository)
                    .contains("return new OrderReferenceNotFoundException(\"customerId2\", failure);")
                    .contains("return new OrderReferenceNotFoundException(\"customer\", failure);")
                    .doesNotContain("fk_orders_ledger_id");
        }

        @Test
        @DisplayName("the delete of a referenced row turns any foreign-key violation into the conflict refusal")
        void deleteIsGuarded() {
            String repository = generator.generate(CUSTOMER, BUILD).content();

            assertThat(repository)
                    .contains("if (foreignKeyViolation(e) != null) {\n"
                            + "                throw new CustomerStillReferencedException(id, e);")
                    .doesNotContain("refusedReference");
        }

        @Test
        @DisplayName("an entity no foreign key involves is emitted as before")
        void untouchedWithoutForeignKeys() {
            assertThat(generator.generate(ARCHIVE, BUILD).content())
                    .doesNotContain("foreignKeyViolation")
                    .doesNotContain("FOREIGN_KEY_STATES");
            assertThat(generator.generate(CUSTOMER, List.of(CUSTOMER)).content())
                    .isEqualTo(generator.generate(CUSTOMER).content())
                    .doesNotContain("foreignKeyViolation");
        }
    }

    @Nested
    @DisplayName("KernelHandlerGenerator")
    class Handler {

        private final KernelHandlerGenerator generator = new KernelHandlerGenerator();

        @Test
        @DisplayName("create, update and a respond-once action answer a missing reference 422 with a problem body")
        void writesAnswerUnprocessable() {
            String handler = generator.generate(ORDER, BUILD).content();

            assertThat(handler.split("catch \\(OrderReferenceNotFoundException e\\) \\{\n"
                    + " {12}respondReferenceNotFound\\(exchange, e.relation\\(\\)\\);", -1))
                    .as("handleCreate, handleUpdate, handleApprove")
                    .hasSize(4);
            assertThat(handler)
                    .contains("private static final HttpStatus UNPROCESSABLE_CONTENT = "
                            + "new HttpStatus(422, \"Unprocessable Content\");")
                    .contains("problem.put(\"type\", \"about:blank\");")
                    .contains("problem.put(\"status\", 422);")
                    .contains("problem.put(\"field\", relation);")
                    .contains("exchange.respond(UNPROCESSABLE_CONTENT, problem);");
        }

        @Test
        @DisplayName("the delete of a referenced entity answers a still-referenced row 409")
        void deleteAnswersConflict() {
            String handler = generator.generate(CUSTOMER, BUILD).content();

            assertThat(handler)
                    .contains("} catch (CustomerStillReferencedException e) {\n"
                            + "            exchange.respond(HttpStatus.CONFLICT);")
                    .doesNotContain("UNPROCESSABLE_CONTENT");
        }

        @Test
        @DisplayName("a soft-deleted target's delete and an unrelated entity carry neither catch")
        void untouchedWithoutForeignKeys() {
            assertThat(generator.generate(ARCHIVE, BUILD).content())
                    .doesNotContain("StillReferenced")
                    .doesNotContain("ReferenceNotFound");
            assertThat(generator.generate(ORDER).content())
                    .doesNotContain("ReferenceNotFound");
        }
    }

    @Test
    @DisplayName("KernelActionStreamHandlerGenerator: a streaming action's missing reference is a 422 stream-error frame")
    void streamingActionRefusesWithAFrame() {
        String stream = new KernelActionStreamHandlerGenerator().generateMultiple(ORDER, BUILD).get(0).content();

        assertThat(stream).contains("} catch (OrderReferenceNotFoundException e) {\n"
                + "            refuse(exchange, 422, \"Unprocessable Content\");");
        assertThat(new KernelActionStreamHandlerGenerator().generateMultiple(ORDER).get(0).content())
                .doesNotContain("ReferenceNotFound");
    }

    @Nested
    @DisplayName("generated tests (ADR-058)")
    class GeneratedTests {

        @Test
        @DisplayName("the repository test covers both engines' violation shapes, another constraint, and the delete")
        void repositoryTest() {
            KernelRepositoryTestGenerator generator = new KernelRepositoryTestGenerator();
            String order = generator.generate(ORDER, "com.example", BUILD).content();
            String customer = generator.generate(CUSTOMER, "com.example", BUILD).content();

            assertThat(order)
                    .contains("void saveRefusesARowNamingAMissingReference()")
                    .contains("PersistenceProviderException.queryFailed(\"23503\"")
                    .contains("violates foreign key constraint \\\"fk_orders_customer_id\\\"")
                    .contains("void updateRefusesARowNamingAMissingReference()")
                    .contains("PersistenceProviderException.queryFailed(\"23506\"")
                    .contains("FK_ORDERS_CUSTOMER_ID")
                    .contains("isEqualTo(\"customer\")")
                    .contains("void saveLeavesAViolationOfAnotherConstraintAsItIs()")
                    .doesNotContain("deleteByIdRefusesARowOtherRowsStillReference");
            assertThat(customer)
                    .contains("void deleteByIdRefusesARowOtherRowsStillReference()")
                    .contains("isInstanceOfSatisfying(CustomerStillReferencedException.class")
                    .doesNotContain("MissingReference");
        }

        @Test
        @DisplayName("the handler test pins 422 with the problem body on both writes, and 409 on the delete")
        void handlerTest() {
            KernelHandlerTestGenerator generator = new KernelHandlerTestGenerator();
            String order = generator.generate(ORDER, "com.example", BUILD).content();
            String customer = generator.generate(CUSTOMER, "com.example", BUILD).content();

            assertThat(order)
                    .contains("void handleCreateRespondsUnprocessableForAMissingReference()")
                    .contains("void handleUpdateRespondsUnprocessableForAMissingReference()")
                    .contains("service.refusal = new OrderReferenceNotFoundException(\"customer\", null);")
                    .contains("assertThat(exchange.status().code()).isEqualTo(422);")
                    .contains("Map<?, ?> problem = (Map<?, ?>) exchange.body();")
                    .contains("assertThat(problem.get(\"field\")).isEqualTo(\"customer\");")
                    .doesNotContain("handleDeleteRespondsConflictWhenOtherRowsStillReferenceTheRow");
            assertThat(customer)
                    .contains("void handleDeleteRespondsConflictWhenOtherRowsStillReferenceTheRow()")
                    .contains("service.referenced = true;")
                    .contains("throw new CustomerStillReferencedException(id, null);")
                    .doesNotContain("MissingReference");
        }
    }
}
