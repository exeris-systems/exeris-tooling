package eu.exeris.tooling.codegen.java.kernel;

import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.ASSERTIONS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.FIXED_ID;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.HTTP_STATUS;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.LIST;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.UUID;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.invoke;
import static eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator.test;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;

import java.util.List;

/**
 * Emits the generated handler test cases of the list route (ADR-096).
 */
final class KernelHandlerListTests {

    private KernelHandlerListTests() {
    }

    /**
     * The list route: what reaches the service for a request, and what is refused before it does.
     *
     * <p>The service double records the {@code <Entity>ListQuery} it was handed, so each case asserts
     * the parse rather than a status alone: a handler that answered {@code 200} while dropping the
     * page or the filter on the floor would pass a status check. The refusals assert the service was
     * never reached. A sort case is emitted only for an entity with a sortable property, a filter case
     * only for a filter whose value a literal can be written for, and a stripped-term search case only
     * for an entity with a searchable property; an entity without one is refused any non-blank term.
     */
    static void addTo(TypeSpec.Builder type, DomainMetadata metadata, ClassName entityType,
                      ClassName handlerType, ClassName exchangeType, ClassName stubType,
                      String basePath, boolean tenantScoped) {
        ClassName queryType = KernelListQueryGenerator.listQueryType(metadata);
        ClassName pageType = KernelListQueryGenerator.pageType(metadata);

        type.addMethod(test("handleGetAllRespondsOkWithThePageTheServiceRead")
                .addJavadoc("No query string: the first page, at the default size, unsorted and unfiltered.\n")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("service.page = $T.of($T.of(new $T()), 1L, 0, $T.DEFAULT_SIZE)",
                        pageType, LIST, entityType, queryType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType, basePath)
                .addStatement(invoke("handleGetAll", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(exchange.body()).isSameAs(service.page)", ASSERTIONS)
                .addStatement("$T.assertThat(service.listQuery).isEqualTo($T.of(0, $T.DEFAULT_SIZE))",
                        ASSERTIONS, queryType, queryType)
                .build());

        type.addMethod(test("handleGetAllPassesTheRequestedPageAndSize")
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType,
                        basePath + "?page=2&size=5")
                .addStatement(invoke("handleGetAll", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.listQuery).isEqualTo($T.of(2, 5))", ASSERTIONS, queryType)
                .build());

        List<ListQuerySupport.Property> sortable = ListQuerySupport.sortable(metadata);
        if (!sortable.isEmpty()) {
            String property = sortable.get(0).name();
            type.addMethod(test("handleGetAllPassesTheRequestedSort")
                    .addStatement("$T service = new $T()", stubType, stubType)
                    .addStatement("$T handler = newHandler(service)", handlerType)
                    .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType,
                            basePath + "?sort=" + property + ",desc")
                    .addStatement(invoke("handleGetAll", tenantScoped))
                    .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                    .addStatement("$T.assertThat(service.listQuery.sort()).isEqualTo($S)", ASSERTIONS, property)
                    .addStatement("$T.assertThat(service.listQuery.descending()).isTrue()", ASSERTIONS)
                    .build());
        }

        ListQuerySupport.filters(metadata).stream()
                .filter(filter -> rawFilterValue(filter) != null)
                .findFirst()
                .ifPresent(filter -> type.addMethod(test("handleGetAllPassesTheRequestedFilter")
                    .addJavadoc("The first filter a literal can be written for: {@code $L}.\n", filter.name())
                    .addStatement("$T service = new $T()", stubType, stubType)
                    .addStatement("$T handler = newHandler(service)", handlerType)
                    .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType,
                            basePath + "?" + filter.name() + "=" + rawFilterValue(filter))
                    .addStatement(invoke("handleGetAll", tenantScoped))
                    .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.OK)", ASSERTIONS, HTTP_STATUS)
                    .addStatement("$T.assertThat(service.listQuery.filter().$L()).isEqualTo($L)",
                            ASSERTIONS, filter.name(), filterSample(filter))
                    .build()));

        addSearchTests(type, metadata, handlerType, exchangeType, stubType, basePath, tenantScoped);
        refusal(type, "handleGetAllRespondsBadRequestOnAnUnknownParameter",
                "A name the route does not read is refused, not ignored: a mistyped filter would\n"
                        + "otherwise answer every row as if it had matched.\n",
                basePath + "?no-such-parameter=1", handlerType, exchangeType, stubType, tenantScoped);
        refusal(type, "handleGetAllRespondsBadRequestOnAPropertyThatCannotBeSorted",
                null, basePath + "?sort=no-such-property,asc", handlerType, exchangeType, stubType,
                tenantScoped);
        refusal(type, "handleGetAllRespondsBadRequestOnANegativePage",
                null, basePath + "?page=-1", handlerType, exchangeType, stubType, tenantScoped);
        refusal(type, "handleGetAllRespondsBadRequestOnASizeAboveTheMaximum",
                null, basePath + "?size=" + (ListQuerySupport.MAX_SIZE + 1), handlerType, exchangeType,
                stubType, tenantScoped);
    }

    private static void addSearchTests(TypeSpec.Builder type, DomainMetadata metadata, ClassName handlerType,
                                       ClassName exchangeType, ClassName stubType, String base,
                                       boolean tenant) {
        boolean searchable = !ListQuerySupport.searchable(metadata).isEmpty();
        if (searchable) {
            type.addMethod(test("handleGetAllPassesTheRequestedSearchStripped")
                    .addStatement("$T service = new $T()", stubType, stubType)
                    .addStatement("$T handler = newHandler(service)", handlerType)
                    .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType,
                            base + "?search=%20Sample%20")
                    .addStatement(invoke("handleGetAll", tenant))
                    .addStatement("$T.assertThat(service.listQuery.search()).isEqualTo($S)", ASSERTIONS,
                            "Sample")
                    .build());
        }
        refusal(type, searchable ? "handleGetAllRespondsBadRequestOnASearchAboveTheMaximumLength"
                        : "handleGetAllRespondsBadRequestOnASearchWithNothingSearchable", null,
                base + "?search=" + "x".repeat(searchable ? ListQuerySupport.MAX_SEARCH_LENGTH + 1 : 1),
                handlerType, exchangeType, stubType, tenant);
    }

    private static void refusal(TypeSpec.Builder type, String name, String javadoc, String target,
                                ClassName handlerType, ClassName exchangeType, ClassName stubType,
                                boolean tenantScoped) {
        MethodSpec.Builder test = test(name);
        if (javadoc != null) {
            test.addJavadoc(javadoc);
        }
        type.addMethod(test
                .addStatement("$T service = new $T()", stubType, stubType)
                .addStatement("$T handler = newHandler(service)", handlerType)
                .addStatement("$T exchange = $T.get($S)", exchangeType, exchangeType, target)
                .addStatement(invoke("handleGetAll", tenantScoped))
                .addStatement("$T.assertThat(exchange.status()).isEqualTo($T.BAD_REQUEST)",
                        ASSERTIONS, HTTP_STATUS)
                .addStatement("$T.assertThat(service.listQuery).isNull()", ASSERTIONS)
                .build());
    }

    /**
     * A query-string value for a filter of this kind, matching {@link #filterSample}; {@code null}
     * for a kind with no literal this generator can write — an enum, whose constants it does not know.
     */
    private static String rawFilterValue(ListQuerySupport.Property filter) {
        return switch (filter.kind()) {
            case UUID -> FIXED_ID;
            case STRING -> "sample";
            case BOOL -> "true";
            case INT, LONG -> String.valueOf(KernelTestSamples.SAMPLE_NUMBER);
            default -> null;
        };
    }

    /** The Java value {@link #rawFilterValue} parses to, boxed as the filter component holds it. */
    private static CodeBlock filterSample(ListQuerySupport.Property filter) {
        return switch (filter.kind()) {
            case UUID -> CodeBlock.of("$T.fromString($S)", UUID, FIXED_ID);
            case STRING -> CodeBlock.of("$S", "sample");
            case BOOL -> CodeBlock.of("$T.TRUE", Boolean.class);
            case INT -> CodeBlock.of("$T.valueOf($L)", Integer.class, KernelTestSamples.SAMPLE_NUMBER);
            case LONG -> CodeBlock.of("$T.valueOf($LL)", Long.class, KernelTestSamples.SAMPLE_NUMBER);
            default -> throw new IllegalArgumentException("no sample for " + filter.kind());
        };
    }
}
