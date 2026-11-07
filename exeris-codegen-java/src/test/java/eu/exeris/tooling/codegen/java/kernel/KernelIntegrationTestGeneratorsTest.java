package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.BlobMetadata;
import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.RouteAccess;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The emitted text of the generated integration tests (ADR-058 Amendment 1): which cases each
 * precondition admits, and what a case sends. Whether the cases pass against a booted application
 * is {@code GeneratedIntegrationTestsE2ETest}'s.
 */
@DisplayName("Generated integration tests: support, CRUD and route-policy emission")
class KernelIntegrationTestGeneratorsTest {

    private static final String BASE = "com.shop";
    private static final String DOMAIN = "com.shop.domain";

    private static DomainMetadata plain(String name, String path, FieldMetadata... fields) {
        return DomainMetadata.builder(name, DOMAIN).path(path).fields(List.of(fields)).build();
    }

    private static String crud(DomainMetadata entity, List<DomainMetadata> build) {
        return new KernelCrudIntegrationTestGenerator().generate(entity, BASE, build).content();
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("a build of GLOBAL entities without graph, blob or composition is in scope")
        void globalBuildIsInScope() {
            DomainMetadata note = plain("Note", "/notes", FieldMetadata.builder("title", "String").build());
            assertThat(KernelIntegrationScope.withheldReason(List.of(note), false)).isEmpty();
        }

        @Test
        @DisplayName("each reason names what rules the build out, the first tenant-partitioned entity first")
        void reasonsNameTheCause() {
            DomainMetadata tenant = DomainMetadata.builder("Invoice", DOMAIN).path("/invoices")
                    .dataScope(DataScope.TENANT).build();
            DomainMetadata graph = DomainMetadata.builder("Node", DOMAIN).path("/nodes")
                    .graphMetadata(GraphMetadata.simple("Node")).build();
            DomainMetadata blob = plain("Scan", "/scans", FieldMetadata.builder("file", "BlobRef")
                    .blob(BlobMetadata.ofContentTypes(List.of("application/pdf"))).build());
            DomainMetadata note = plain("Note", "/notes");

            assertThat(KernelIntegrationScope.withheldReason(List.of(note, tenant, graph), false))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("com.shop.domain.Invoice", "TENANT"));
            assertThat(KernelIntegrationScope.withheldReason(List.of(note, graph), false))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("graph"));
            assertThat(KernelIntegrationScope.withheldReason(List.of(note, blob), false))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("@Blob"));
            assertThat(KernelIntegrationScope.withheldReason(List.of(note), true))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("@CapabilityModule"));
        }
    }

    @Nested
    @DisplayName("support")
    class Support {

        @Test
        @DisplayName("ApplicationUnderTest lists the migrations in version order and boots the base package's Application")
        void applicationUnderTestNamesItsInputs() {
            DomainMetadata author = plain("Author", "/authors", FieldMetadata.builder("name", "String").build());
            DomainMetadata book = DomainMetadata.builder("Book", DOMAIN).path("/books")
                    .fields(List.of(FieldMetadata.builder("authorId", "UUID").build()))
                    .relationships(List.of(RelationshipMetadata.builder("authorId", "Author")
                            .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                    .build();

            List<GeneratedFile> files = new KernelIntegrationSupportGenerator().generateAll(List.of(author, book), BASE);

            assertThat(files).extracting(GeneratedFile::className)
                    .containsExactly("ApplicationUnderTest", "IntegrationHttp");
            String boot = files.getFirst().content();
            assertThat(boot).contains("DEFAULT_APPLICATION = \"com.shop.Application\"",
                    "APPLICATION_PROPERTY = \"exeris.it.application\"", "__create_authors.sql",
                    "__create_books.sql", "\"db/migration/V3000000__foreign_keys.sql\"")
                    .doesNotContain("HttpServer", "exeris.security.jwks.uri");
            assertThat(boot.indexOf("__create_")).isLessThan(boot.indexOf("V3000000__foreign_keys"));
            assertThat(files.get(1).content()).contains("HttpClient.Version.HTTP_1_1",
                    "public static boolean listed(String path, String id, String bearer)",
                    "PAGE_SIZE = 100");
            List<String> migrations = KernelIntegrationSupportGenerator.migrations(List.of(author, book));
            assertThat(migrations).isSorted().hasSize(3);
            assertThat(migrations.getLast()).isEqualTo("db/migration/V3000000__foreign_keys.sql");
        }

        @Test
        @DisplayName("a route that demands an identity adds the token class and the loopback key set")
        void securityAddsTheTokens() {
            DomainMetadata note = DomainMetadata.builder("Note", DOMAIN).path("/notes")
                    .routeAccess(RouteAccess.AUTHENTICATED).build();

            List<GeneratedFile> files = new KernelIntegrationSupportGenerator().generateAll(List.of(note), BASE);

            assertThat(files).extracting(GeneratedFile::className)
                    .containsExactly("ApplicationUnderTest", "IntegrationHttp", "IntegrationTokens");
            assertThat(files.getFirst().content()).contains("keys = serveKeys()", "\"exeris.security.jwks.uri\"",
                    "\"exeris.security.jwt.issuer\", IntegrationTokens.ISSUER");
            assertThat(files.get(2).content()).contains("\"kid\\\":\\\"\" + KEY_ID",
                    "SUBJECT = \"01890000-0000-7000-8000-000000000001\"", "SHA256withRSA");
        }
    }

    @Nested
    @DisplayName("CRUD")
    class Crud {

        @Test
        @DisplayName("an entity with a synthesizable body gets the round trip and a pair per declared bound")
        void creatableEntityGetsEveryCase() {
            DomainMetadata memo = plain("Memo", "/memos",
                    FieldMetadata.builder("title", "String").required(true).minLength(2).maxLength(40).build(),
                    FieldMetadata.builder("priority", "int").min(1L).max(9L).build(),
                    FieldMetadata.builder("ratio", "double").min(0L).build(),
                    FieldMetadata.builder("amount", "BigDecimal").max(1000L).build(),
                    FieldMetadata.builder("done", "boolean").build(),
                    FieldMetadata.builder("ref", "UUID").build(),
                    FieldMetadata.builder("due", "LocalDate").build(),
                    FieldMetadata.builder("at", "Instant").build(),
                    FieldMetadata.builder("stamp", "LocalDateTime").build(),
                    FieldMetadata.builder("code", "String").unique(true).maxLength(10).build(),
                    FieldMetadata.builder("serial", "long").unique(true).build(),
                    FieldMetadata.builder("token", "UUID").unique(true).build(),
                    FieldMetadata.builder("tags", "List<String>").build(),
                    FieldMetadata.builder("slug", "String").pattern("[a-z]+").minLength(1).build());

            String test = crud(memo, List.of(memo));

            assertThat(test).contains("final class MemoCrudIT", "void aRowRoundTripsThroughEveryRoute()",
                    "\\\"title\\\":\\\"sample\\\"", "\\\"priority\\\":7", "\\\"ratio\\\":7.0",
                    "\\\"amount\\\":7", "\\\"done\\\":true", "\\\"due\\\":\\\"2000-01-01\\\"",
                    "\\\"at\\\":\\\"2000-01-01T00:00:00Z\\\"", "\\\"stamp\\\":\\\"2000-01-01T00:00:00\\\"",
                    "IntegrationHttp.jsonString(IntegrationHttp.unique(6))",
                    "Long.toString(IntegrationHttp.sequence())", "UUID.randomUUID().toString()",
                    "void titleOnMinLengthBound()", "void titleOnMaxLengthBound()", "void titleLeftOutIsBadRequest()",
                    "void priorityOnMinBound()", "void priorityOnMaxBound()", "void amountOnMaxBound()",
                    "contains(\"\\\"title\\\":\\\"sample\\\"\")", "contains(\"\\\"priority\\\":7\")")
                    .doesNotContain("\\\"tags\\\"", "\\\"slug\\\"", "ratioOnMinBound", "slugOn", "codeOn",
                            "anUpdateCarryingAReplacedVersionIsConflict", "IntegrationTokens");
        }

        @Test
        @DisplayName("a required field with no synthesizable value leaves the bodyless cases only")
        void uncreatableEntityKeepsTheBodylessCases() {
            DomainMetadata tagged = plain("Tagged", "/tagged",
                    FieldMetadata.builder("tags", "List<String>").required(true).build());
            DomainMetadata serverSet = plain("Ticket", "/tickets",
                    FieldMetadata.builder("status", "String").required(true).readOnly(true).build());
            DomainMetadata empty = plain("Bounded", "/bounded",
                    FieldMetadata.builder("name", "String").required(true).minLength(5).maxLength(2).build());

            for (DomainMetadata entity : List.of(tagged, serverSet, empty)) {
                assertThat(crud(entity, List.of(entity))).as(entity.entityName())
                        .contains("void anAbsentRowIsNotFound()", "void aMalformedKeyIsBadRequest()")
                        .doesNotContain("aRowRoundTripsThroughEveryRoute", "postRow");
            }
        }

        @Test
        @DisplayName("a versioned entity gets the stale-version case, carrying its version on every PUT")
        void versionedEntityGetsTheStaleCase() {
            DomainMetadata ledger = DomainMetadata.builder("Ledger", DOMAIN).path("/ledgers").versioned(true)
                    .fields(List.of(FieldMetadata.builder("name", "String").build(),
                            FieldMetadata.builder("version", "Integer").build()))
                    .build();

            assertThat(crud(ledger, List.of(ledger)))
                    .contains("void anUpdateCarryingAReplacedVersionIsConflict()", "\\\"version\\\":0}")
                    .doesNotContain("\\\"version\\\":7");
        }

        @Test
        @DisplayName("a required relationship creates its target first; each relationship gets the 422 and, when restricted, the 409")
        void relationshipsGetTheirCases() {
            DomainMetadata author = plain("Author", "/authors", FieldMetadata.builder("name", "String").build());
            DomainMetadata book = DomainMetadata.builder("Book", DOMAIN).path("/books")
                    .fields(List.of(FieldMetadata.builder("title", "String").build(),
                            FieldMetadata.builder("authorId", "UUID").required(true).build(),
                            FieldMetadata.builder("sequelOfId", "UUID").build(),
                            FieldMetadata.builder("seriesId", "UUID").build()))
                    .relationships(List.of(
                            manyToOne("authorId", "Author", RelationshipMetadata.CascadeType.NONE),
                            manyToOne("sequelOfId", "Book", RelationshipMetadata.CascadeType.NONE),
                            manyToOne("seriesId", "Author", RelationshipMetadata.CascadeType.ALL)))
                    .build();
            List<DomainMetadata> build = List.of(author, book);

            String test = crud(book, build);

            assertThat(test).contains("String authorIdTarget = createAuthor();",
                    "IntegrationHttp.jsonString(authorIdTarget)",
                    "void authorIdNamingAnAbsentRowIsUnprocessable()", "\"\\\"field\\\":\\\"authorId\\\"\"",
                    "void authorIdTargetStillReferencedCannotBeDeleted()", "\"/authors/\" + referenced",
                    "void sequelOfIdNamingAnAbsentRowIsUnprocessable()",
                    "void seriesIdNamingAnAbsentRowIsUnprocessable()", "void authorIdLeftOutIsBadRequest()",
                    "private static String createAuthor()", "private static String bearerForAuthor()")
                    .doesNotContain("sequelOfIdTargetStillReferenced", "seriesIdTargetStillReferenced",
                            "createBook()");
            assertThat(test.substring(test.indexOf("void authorIdLeftOutIsBadRequest()")))
                    .doesNotContain("authorIdTarget = ");
        }

        @Test
        @DisplayName("a required self reference makes the entity uncreatable")
        void requiredSelfReferenceIsUncreatable() {
            DomainMetadata node = DomainMetadata.builder("Node", DOMAIN).path("/nodes")
                    .fields(List.of(FieldMetadata.builder("parentId", "UUID").required(true).build()))
                    .relationships(List.of(manyToOne("parentId", "Node", RelationshipMetadata.CascadeType.NONE)))
                    .build();

            assertThat(crud(node, List.of(node))).doesNotContain("aRowRoundTripsThroughEveryRoute",
                    "parentIdNamingAnAbsentRowIsUnprocessable");
        }

        @Test
        @DisplayName("routes that demand an identity carry a token with the first declared scope")
        void identityRoutesCarryAToken() {
            DomainMetadata scoped = DomainMetadata.builder("Note", DOMAIN).path("/notes")
                    .permissions(List.of("note:read", "note:write")).build();
            DomainMetadata authenticated = DomainMetadata.builder("Draft", DOMAIN).path("/drafts")
                    .routeAccess(RouteAccess.AUTHENTICATED).build();
            DomainMetadata open = DomainMetadata.builder("Notice", DOMAIN).path("/notices")
                    .routeAccess(RouteAccess.PUBLIC).build();

            assertThat(crud(scoped, List.of(scoped))).contains("return IntegrationTokens.bearer(\"note:read\");");
            assertThat(crud(authenticated, List.of(authenticated))).contains("return IntegrationTokens.bearer();");
            assertThat(crud(open, List.of(open))).contains("return null;").doesNotContain("IntegrationTokens");
        }

        private static RelationshipMetadata manyToOne(String name, String target, RelationshipMetadata.CascadeType cascade) {
            return RelationshipMetadata.builder(name, target)
                    .type(RelationshipMetadata.RelationType.MANY_TO_ONE).cascade(cascade).build();
        }
    }

    @Nested
    @DisplayName("route policy")
    class RoutePolicy {

        @Test
        @DisplayName("no route that demands an identity: no test")
        void nothingWithoutIdentity() {
            DomainMetadata open = DomainMetadata.builder("Notice", DOMAIN).path("/notices")
                    .routeAccess(RouteAccess.PUBLIC).build();
            assertThat(new KernelRoutePolicyIntegrationTestGenerator().generate(List.of(open), BASE)).isNull();
        }

        @Test
        @DisplayName("each requirement kind gets its cases, and an action's own scopes refuse the entity's")
        void eachKindGetsItsCases() {
            DomainMetadata note = DomainMetadata.builder("Note", DOMAIN).path("/notes")
                    .permissions(List.of("note:read"))
                    .actions(List.of(
                            ActionMetadata.builder("archive").permissions(List.of("note:archive")).build(),
                            ActionMetadata.builder("peek").routeAccess(RouteAccess.PUBLIC).build(),
                            ActionMetadata.builder("touch").routeAccess(RouteAccess.AUTHENTICATED).build(),
                            ActionMetadata.builder("watch").streaming(true).build()))
                    .build();
            DomainMetadata draft = DomainMetadata.builder("Draft", DOMAIN).path("/drafts")
                    .routeAccess(RouteAccess.AUTHENTICATED).build();
            DomainMetadata notice = DomainMetadata.builder("Notice", DOMAIN).path("/notices")
                    .routeAccess(RouteAccess.PUBLIC).build();
            DomainMetadata plain = DomainMetadata.builder("Memo", DOMAIN).path("/memos").build();

            GeneratedFile file = new KernelRoutePolicyIntegrationTestGenerator()
                    .generate(List.of(note, draft, notice, plain), BASE);

            assertThat(file.packageName()).isEqualTo(BASE);
            assertThat(file.className()).isEqualTo("GeneratedRoutePolicyIT");
            assertThat(file.content()).contains(
                    "status(\"GET\", \"/notes?x=1\", null, null, 401);",
                    "status(\"HEAD\", \"/notes/\" + KEY, null, null, 401);",
                    "status(\"GET\", \"/notes/\" + KEY, null, IntegrationTokens.bearer(), 403);",
                    "admitted(\"GET\", \"/notes/\" + KEY, IntegrationTokens.bearer(\"note:read\"));",
                    "status(\"POST\", \"/notes/\" + KEY + \"/actions/archive\", \"{}\", "
                            + "IntegrationTokens.bearer(\"note:read\"), 403);",
                    "void notePeekActionAdmitsAnAnonymousCaller()",
                    "void noteTouchActionAdmitAnyVerifiedCaller()",
                    "void draftRoutesAdmitAnyVerifiedCaller()",
                    "status(\"GET\", \"/notices/\" + KEY, null, null, 404);")
                    .doesNotContain("Watch", "memoRoutes");
        }
    }
}
