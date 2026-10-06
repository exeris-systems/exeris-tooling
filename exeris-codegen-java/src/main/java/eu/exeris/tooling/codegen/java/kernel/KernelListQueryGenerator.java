package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.java.support.DomainTypeKind;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport.Property;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Kernel List Query Generator.
 *
 * <p>Emits, per entity, the two types the list route {@code GET {base}} is built on:
 * <ul>
 *   <li>{@code <Entity>ListQuery} — the page, size, sort and filters a request asked for. It parses
 *       them from a query string ({@code parse}), refuses anything outside the entity's whitelist
 *       with {@link IllegalArgumentException}, and writes them back ({@code toQueryString}) for the
 *       generated client. Its nested {@code Filter} record has one nullable component per filter
 *       parameter; {@code null} means "not filtered".</li>
 *   <li>{@code <Entity>Page} — the envelope the route answers with:
 *       {@code content, totalElements, totalPages, size, number, first, last}, the members the
 *       emitted Angular service reads as {@code Page<T>}.</li>
 * </ul>
 *
 * <p>Both live in the generated repository package, which is where the query is executed and,
 * like the ADR-076 error types, a package the generator owns: a {@code Page} or {@code ListQuery}
 * the consumer wrote in their domain package is not overwritten. They are per entity rather than
 * one shared generic {@code Page<T>} because the generated client decodes the response through
 * {@code KernelWebClient.get(path, Class)}, which carries no type argument — a concrete record per
 * entity is what lets {@code content} decode as entities rather than maps — and because a
 * per-domain emitter cannot resolve a project base package (see {@link KernelErrorGenerator}).
 *
 * <p>Which properties may be sorted and filtered, the parameter names, the default and maximum
 * page size and the envelope members come from {@link ListQuerySupport}, the one place the
 * handler, repository, client and OpenAPI document read them.
 *
 * <p>Neither type is an ADR-070 component: nothing constructs it through a {@code create*} factory.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 * @since 0.9.0
 */
public class KernelListQueryGenerator implements KernelArtifactGenerator {

    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName HASH_SET = ClassName.get("java.util", "HashSet");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");
    private static final ClassName LOCAL_DATE = ClassName.get("java.time", "LocalDate");
    private static final ClassName DATE_TIME_EXCEPTION = ClassName.get("java.time", "DateTimeException");
    private static final ClassName URL_DECODER = ClassName.get("java.net", "URLDecoder");
    private static final ClassName URL_ENCODER = ClassName.get("java.net", "URLEncoder");
    private static final ClassName UTF_8_HOLDER = ClassName.get("java.nio.charset", "StandardCharsets");
    private static final ClassName ILLEGAL_ARGUMENT = ClassName.get(IllegalArgumentException.class);

    private static final String FILTER = "Filter";

    /** {@code <Entity>Page} in the generated repository package. */
    static ClassName pageType(DomainMetadata metadata) {
        return ClassName.get(KernelErrorGenerator.errorPackage(metadata), metadata.entityName() + "Page");
    }

    /** {@code <Entity>ListQuery} in the generated repository package. */
    static ClassName listQueryType(DomainMetadata metadata) {
        return ClassName.get(KernelErrorGenerator.errorPackage(metadata), metadata.entityName() + "ListQuery");
    }

    /** {@code <Entity>ListQuery.Filter}. */
    static ClassName filterType(DomainMetadata metadata) {
        return listQueryType(metadata).nestedClass(FILTER);
    }

    /**
     * The Java type a filter parameter is held as — always a reference type, since {@code null}
     * means "not filtered".
     */
    static TypeName filterValueType(Property property, DomainMetadata metadata) {
        return switch (property.kind()) {
            case UUID -> UUID;
            case STRING -> ClassName.get(String.class);
            case LONG -> TypeName.LONG.box();
            case INT -> TypeName.INT.box();
            case BOOL -> TypeName.BOOLEAN.box();
            case DOUBLE -> TypeName.DOUBLE.box();
            case BIG_DECIMAL -> BIG_DECIMAL;
            case LOCAL_DATE -> LOCAL_DATE;
            case ENUM -> enumClass(property.javaType(), metadata);
            default -> throw new IllegalArgumentException(
                    "not a filterable kind: " + property.kind() + " (" + property.name() + ")");
        };
    }

    /**
     * The class an {@link DomainTypeKind#ENUM} field is read back through — the same resolution
     * the repository's {@code mapRow} uses, so the two name the same type.
     */
    static ClassName enumClass(String type, DomainMetadata metadata) {
        return type.contains(".")
                ? ClassName.bestGuess(type)
                : ClassName.get(metadata.packageName(), type);
    }

    /** The {@code new Filter(...)} with every component {@code null}. */
    static CodeBlock emptyFilter(DomainMetadata metadata) {
        String nulls = ListQuerySupport.filters(metadata).stream().map(p -> "null")
                .collect(Collectors.joining(", "));
        return CodeBlock.of("new $T($L)", filterType(metadata), nulls);
    }

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        return listQuery(metadata);
    }

    @Override
    public List<GeneratedFile> generateMultiple(DomainMetadata metadata) {
        return List.of(listQuery(metadata), page(metadata));
    }

    // ------------------------------------------------------------------ <Entity>Page

    private GeneratedFile page(DomainMetadata metadata) {
        ClassName self = pageType(metadata);
        ClassName entityType = ClassName.get(metadata.packageName(), metadata.entityName());
        TypeName listOfEntity = ParameterizedTypeName.get(LIST, entityType);

        MethodSpec canonical = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(listOfEntity, "content")
                .addParameter(TypeName.LONG, "totalElements")
                .addParameter(TypeName.INT, "totalPages")
                .addParameter(TypeName.INT, "size")
                .addParameter(TypeName.INT, "number")
                .addParameter(TypeName.BOOLEAN, "first")
                .addParameter(TypeName.BOOLEAN, "last")
                .build();

        TypeSpec type = TypeSpec.recordBuilder(self.simpleName())
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("One page of $L rows — what the list route {@code GET $L} answers with.\n",
                        metadata.entityName(), metadata.effectivePath())
                .addJavadoc("\n")
                .addJavadoc("<p>{@code number} is the zero-based page index and {@code size} the page size\n")
                .addJavadoc("that was asked for; {@code content} holds at most {@code size} rows.\n")
                .addJavadoc("{@code totalElements} counts every row the query matched, and\n")
                .addJavadoc("{@code totalPages} the pages of {@code size} they fill — {@code 0} when\n")
                .addJavadoc("nothing matched, in which case the page is both {@code first} and {@code last}.\n")
                .addJavadoc("\n<p>Generated by Exeris Codegen. DO NOT EDIT.\n")
                .addJavadoc("\n")
                .addJavadoc("@param content       the rows of this page, in the query's order\n")
                .addJavadoc("@param totalElements the number of rows the query matched\n")
                .addJavadoc("@param totalPages    the number of pages of {@code size} those rows fill\n")
                .addJavadoc("@param size          the page size\n")
                .addJavadoc("@param number        the zero-based page index\n")
                .addJavadoc("@param first         whether this is the first page\n")
                .addJavadoc("@param last          whether no page follows this one\n")
                .recordConstructor(canonical)
                .addMethod(MethodSpec.compactConstructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("content = content == null ? $T.of() : $T.copyOf(content)", LIST, LIST)
                        .build())
                .addMethod(MethodSpec.methodBuilder("of")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(self)
                        .addJavadoc("The page {@code number} of {@code size} rows, out of {@code totalElements}.\n")
                        .addJavadoc("\n")
                        .addJavadoc("@param content       the rows of this page\n")
                        .addJavadoc("@param totalElements the number of rows the query matched\n")
                        .addJavadoc("@param number        the zero-based page index\n")
                        .addJavadoc("@param size          the page size, at least 1\n")
                        .addJavadoc("@return the page\n")
                        .addParameter(listOfEntity, "content")
                        .addParameter(TypeName.LONG, "totalElements")
                        .addParameter(TypeName.INT, "number")
                        .addParameter(TypeName.INT, "size")
                        .beginControlFlow("if (size < 1)")
                        .addStatement("throw new $T($S + size)", ILLEGAL_ARGUMENT, "size must be at least 1: ")
                        .endControlFlow()
                        .addStatement("int totalPages = (int) ((totalElements + size - 1) / size)")
                        .addStatement("return new $T(content, totalElements, totalPages, size, number, "
                                + "number == 0, number >= totalPages - 1)", self)
                        .build())
                .build();
        return emit(self, type);
    }

    // ------------------------------------------------------------------ <Entity>ListQuery

    private GeneratedFile listQuery(DomainMetadata metadata) {
        ClassName self = listQueryType(metadata);
        ClassName filter = filterType(metadata);
        List<Property> sortable = ListQuerySupport.sortable(metadata);
        List<Property> filters = ListQuerySupport.filters(metadata);

        MethodSpec canonical = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeName.INT, ListQuerySupport.PAGE)
                .addParameter(TypeName.INT, ListQuerySupport.SIZE)
                .addParameter(String.class, ListQuerySupport.SORT)
                .addParameter(TypeName.BOOLEAN, "descending")
                .addParameter(filter, "filter")
                .build();

        String sortableList = sortable.isEmpty()
                ? "none: {@code sort} is refused"
                : sortable.stream().map(p -> "{@code " + p.name() + "}").collect(Collectors.joining(", "));
        String filterList = filters.isEmpty()
                ? "none"
                : filters.stream().map(p -> "{@code " + p.name() + "}").collect(Collectors.joining(", "));

        TypeSpec.Builder type = TypeSpec.recordBuilder(self.simpleName())
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("The page, sort and filters a request to the list route {@code GET $L} asks for.\n",
                        metadata.effectivePath())
                .addJavadoc("\n")
                .addJavadoc("<p>The query parameters, all optional:\n")
                .addJavadoc("<ul>\n")
                .addJavadoc("<li>{@code page} — the zero-based page index, default {@code 0};</li>\n")
                .addJavadoc("<li>{@code size} — the page size, {@code 1} to {@link #MAX_SIZE}, default\n")
                .addJavadoc("{@link #DEFAULT_SIZE};</li>\n")
                .addJavadoc("<li>{@code sort} — {@code <property>,asc} or {@code <property>,desc}, one\n")
                .addJavadoc("property of {@link #SORTABLE}; unsorted, rows come in {@code id} order;</li>\n")
                .addJavadoc("<li>one {@code <property>=<value>} per filter, matched by equality — a\n")
                .addJavadoc("{@link Filter} component.</li>\n")
                .addJavadoc("</ul>\n")
                .addJavadoc("Sortable: $L. Filters: $L.\n", sortableList, filterList)
                .addJavadoc("\n")
                .addJavadoc("<p>Anything else — an unknown or repeated parameter, a property outside the\n")
                .addJavadoc("whitelist, a value that does not parse, a negative page, a size out of range —\n")
                .addJavadoc("is refused with {@link IllegalArgumentException}, which the generated handler\n")
                .addJavadoc("answers {@code 400}. No parameter value ever reaches SQL as text: the\n")
                .addJavadoc("repository maps {@code sort} to a column through a fixed table and binds\n")
                .addJavadoc("every filter value.\n")
                .addJavadoc("\n<p>Generated by Exeris Codegen. DO NOT EDIT.\n")
                .addJavadoc("\n")
                .addJavadoc("@param page       the zero-based page index\n")
                .addJavadoc("@param size       the page size\n")
                .addJavadoc("@param sort       the property to sort on, or {@code null} for {@code id} order\n")
                .addJavadoc("@param descending whether {@code sort} runs descending; ignored without one\n")
                .addJavadoc("@param filter     the equality filters; {@code null} means none\n")
                .recordConstructor(canonical)
                .addField(FieldSpec.builder(TypeName.INT, "DEFAULT_SIZE",
                                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .addJavadoc("The page size when the request names none.\n")
                        .initializer("$L", ListQuerySupport.DEFAULT_SIZE)
                        .build())
                .addField(FieldSpec.builder(TypeName.INT, "MAX_SIZE",
                                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .addJavadoc("The largest page size the route serves; a larger one is refused.\n")
                        .initializer("$L", ListQuerySupport.MAX_SIZE)
                        .build())
                .addField(FieldSpec.builder(ParameterizedTypeName.get(LIST, ClassName.get(String.class)),
                                "SORTABLE", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .addJavadoc("The properties {@code sort} accepts.\n")
                        .initializer(sortable.isEmpty() ? CodeBlock.of("$T.of()", LIST)
                                : CodeBlock.of("$T.of($L)", LIST, sortable.stream()
                                        .map(p -> CodeBlock.of("$S", p.name()).toString())
                                        .collect(Collectors.joining(", "))))
                        .build())
                .addMethod(compactConstructor(metadata))
                .addMethod(MethodSpec.methodBuilder("of")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(self)
                        .addJavadoc("Page {@code page} of {@code size} rows, unsorted and unfiltered.\n")
                        .addJavadoc("\n")
                        .addJavadoc("@param page the zero-based page index\n")
                        .addJavadoc("@param size the page size\n")
                        .addJavadoc("@return the query\n")
                        .addJavadoc("@throws IllegalArgumentException if either is out of range\n")
                        .addParameter(TypeName.INT, "page")
                        .addParameter(TypeName.INT, "size")
                        .addStatement("return new $T(page, size, null, false, null)", self)
                        .build())
                .addMethod(parse(metadata, self, filter, filters))
                .addMethod(toQueryString(filters))
                .addMethod(MethodSpec.methodBuilder("encode")
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(String.class)
                        .addParameter(String.class, "value")
                        .addStatement("return $T.encode(value, $T.UTF_8)", URL_ENCODER, UTF_8_HOLDER)
                        .build());

        if (filters.stream().anyMatch(p -> p.kind() == DomainTypeKind.BOOL)) {
            type.addMethod(MethodSpec.methodBuilder("parseBoolean")
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(TypeName.BOOLEAN)
                    .addJavadoc("{@code true} or {@code false}, exactly — {@code Boolean.parseBoolean}\n")
                    .addJavadoc("reads every other value as {@code false}, which would filter on a typo.\n")
                    .addParameter(String.class, "value")
                    .beginControlFlow("if ($S.equals(value))", "true")
                    .addStatement("return true")
                    .endControlFlow()
                    .beginControlFlow("if ($S.equals(value))", "false")
                    .addStatement("return false")
                    .endControlFlow()
                    .addStatement("throw new $T($S + value)", ILLEGAL_ARGUMENT, "not a boolean: ")
                    .build());
        }
        if (filters.stream().anyMatch(p -> p.kind() == DomainTypeKind.LOCAL_DATE)) {
            type.addMethod(MethodSpec.methodBuilder("parseDate")
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                    .returns(LOCAL_DATE)
                    .addJavadoc("An ISO {@code yyyy-MM-dd} date; anything else is the caller's fault.\n")
                    .addParameter(String.class, "value")
                    .beginControlFlow("try")
                    .addStatement("return $T.parse(value)", LOCAL_DATE)
                    .nextControlFlow("catch ($T e)", DATE_TIME_EXCEPTION)
                    .addStatement("throw new $T($S + value, e)", ILLEGAL_ARGUMENT, "not an ISO date: ")
                    .endControlFlow()
                    .build());
        }

        type.addType(filterRecord(metadata, filters));
        return emit(self, type.build());
    }

    private MethodSpec compactConstructor(DomainMetadata metadata) {
        return MethodSpec.compactConstructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .beginControlFlow("if (page < 0)")
                .addStatement("throw new $T($S + page)", ILLEGAL_ARGUMENT, "page must not be negative: ")
                .endControlFlow()
                .beginControlFlow("if (size < 1 || size > MAX_SIZE)")
                .addStatement("throw new $T($S + MAX_SIZE + $S + size)", ILLEGAL_ARGUMENT,
                        "size must be between 1 and ", ": ")
                .endControlFlow()
                .beginControlFlow("if (sort != null && !SORTABLE.contains(sort))")
                .addStatement("throw new $T($S + sort)", ILLEGAL_ARGUMENT, "not a sortable property: ")
                .endControlFlow()
                .beginControlFlow("if (sort == null)")
                .addStatement("descending = false")
                .endControlFlow()
                .beginControlFlow("if (filter == null)")
                .addStatement("filter = $L", emptyFilter(metadata))
                .endControlFlow()
                .build();
    }

    /**
     * {@code parse(String rawQuery)} — the request's query string, without the {@code ?}, into a
     * query. Each name is decoded, checked against the fixed set, and parsed by the parameter's
     * own type; the switch's {@code default} is the refusal of every name it does not list.
     */
    private MethodSpec parse(DomainMetadata metadata, ClassName self, ClassName filter,
                             List<Property> filters) {
        MethodSpec.Builder parse = MethodSpec.methodBuilder("parse")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(self)
                .addJavadoc("Reads a list request's query string — the part of the request target after\n")
                .addJavadoc("{@code ?}, percent-encoded, possibly empty.\n")
                .addJavadoc("\n")
                .addJavadoc("@param rawQuery the query string\n")
                .addJavadoc("@return the query it asks for\n")
                .addJavadoc("@throws IllegalArgumentException if a parameter is unknown, repeated or malformed,\n")
                .addJavadoc("        or names a property outside the whitelist\n")
                .addParameter(ParameterSpec.builder(String.class, "rawQuery").build())
                .addStatement("int page = 0")
                .addStatement("int size = DEFAULT_SIZE")
                .addStatement("String sort = null")
                .addStatement("boolean descending = false");
        for (Property property : filters) {
            parse.addStatement("$T $L = null", filterValueType(property, metadata), local(property));
        }
        parse.addStatement("$T<String> seen = new $T<>()", SET, HASH_SET)
                .beginControlFlow("for (String pair : rawQuery.split($S))", "&")
                .beginControlFlow("if (pair.isEmpty())")
                .addStatement("continue")
                .endControlFlow()
                .addStatement("int eq = pair.indexOf('=')")
                .addStatement("String name = $T.decode(eq < 0 ? pair : pair.substring(0, eq), $T.UTF_8)",
                        URL_DECODER, UTF_8_HOLDER)
                .addStatement("String value = eq < 0 ? $S : $T.decode(pair.substring(eq + 1), $T.UTF_8)",
                        "", URL_DECODER, UTF_8_HOLDER)
                .beginControlFlow("if (!seen.add(name))")
                .addStatement("throw new $T($S + name)", ILLEGAL_ARGUMENT, "repeated query parameter: ")
                .endControlFlow()
                .beginControlFlow("switch (name)")
                .addStatement("case $S -> page = Integer.parseInt(value)", ListQuerySupport.PAGE)
                .addStatement("case $S -> size = Integer.parseInt(value)", ListQuerySupport.SIZE)
                .beginControlFlow("case $S ->", ListQuerySupport.SORT)
                .addStatement("int comma = value.indexOf(',')")
                .addStatement("sort = comma < 0 ? value : value.substring(0, comma)")
                .addStatement("String direction = comma < 0 ? $S : value.substring(comma + 1)", "asc")
                .beginControlFlow("if (direction.equalsIgnoreCase($S))", "desc")
                .addStatement("descending = true")
                .nextControlFlow("else if (!direction.equalsIgnoreCase($S))", "asc")
                .addStatement("throw new $T($S + direction)", ILLEGAL_ARGUMENT,
                        "sort direction must be asc or desc: ")
                .endControlFlow()
                .endControlFlow();
        for (Property property : filters) {
            parse.addStatement("case $S -> $L = $L", property.name(), local(property),
                    parseValue(property, metadata));
        }
        parse.addStatement("default -> throw new $T($S + name)", ILLEGAL_ARGUMENT, "unknown query parameter: ")
                .endControlFlow()
                .endControlFlow();
        String args = filters.stream().map(KernelListQueryGenerator::local).collect(Collectors.joining(", "));
        return parse.addStatement("return new $T(page, size, sort, descending, new $T($L))", self, filter, args)
                .build();
    }

    /** The expression that parses {@code value} into the parameter's type. */
    private static CodeBlock parseValue(Property property, DomainMetadata metadata) {
        return switch (property.kind()) {
            case UUID -> CodeBlock.of("$T.fromString(value)", UUID);
            case STRING -> CodeBlock.of("value");
            case LONG -> CodeBlock.of("Long.parseLong(value)");
            case INT -> CodeBlock.of("Integer.parseInt(value)");
            case BOOL -> CodeBlock.of("parseBoolean(value)");
            case DOUBLE -> CodeBlock.of("Double.parseDouble(value)");
            case BIG_DECIMAL -> CodeBlock.of("new $T(value)", BIG_DECIMAL);
            case LOCAL_DATE -> CodeBlock.of("parseDate(value)");
            case ENUM -> CodeBlock.of("$T.valueOf(value)", enumClass(property.javaType(), metadata));
            default -> throw new IllegalArgumentException("not a filterable kind: " + property.kind());
        };
    }

    private MethodSpec toQueryString(List<Property> filters) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("toQueryString")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addJavadoc("This query as a query string {@link #parse} reads back to an equal query —\n")
                .addJavadoc("what the generated client sends. An enum filter is written as its constant's\n")
                .addJavadoc("{@code name()}, which {@link #parse} reads with {@code valueOf}; any other filter\n")
                .addJavadoc("value is written with {@code String.valueOf}.\n")
                .addJavadoc("\n")
                .addJavadoc("@return the query string, without a leading {@code ?}\n")
                .addStatement("StringBuilder query = new StringBuilder()")
                .addStatement("query.append($S).append(page).append($S).append(size)", "page=", "&size=")
                .beginControlFlow("if (sort != null)")
                .addStatement("query.append($S).append(encode(sort)).append(descending ? $S : $S)",
                        "&sort=", ",desc", ",asc")
                .endControlFlow();
        for (Property property : filters) {
            String write = property.kind() == DomainTypeKind.ENUM
                    ? "filter.$L().name()"
                    : "String.valueOf(filter.$L())";
            method.beginControlFlow("if (filter.$L() != null)", property.name())
                    .addStatement("query.append($S).append(encode(" + write + "))",
                            "&" + property.name() + "=", property.name())
                    .endControlFlow();
        }
        return method.addStatement("return query.toString()").build();
    }

    private TypeSpec filterRecord(DomainMetadata metadata, List<Property> filters) {
        MethodSpec.Builder canonical = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        TypeSpec.Builder record = TypeSpec.recordBuilder(FILTER)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("The equality filters of a list query, one component per filter parameter;\n")
                .addJavadoc("{@code null} leaves that property unfiltered, and every non-null component\n")
                .addJavadoc("must match.\n");
        if (!filters.isEmpty()) {
            record.addJavadoc("\n");
        }
        for (Property property : filters) {
            canonical.addParameter(filterValueType(property, metadata), property.name());
            record.addJavadoc("@param $L rows whose {@code $L} column equals it\n", property.name(), property.column());
        }
        return record.recordConstructor(canonical.build()).build();
    }

    /** The {@code parse} local a filter value is read into — prefixed, so it cannot shadow another. */
    private static String local(Property property) {
        return "filter" + NameCasing.pascal(property.name());
    }

    private GeneratedFile emit(ClassName self, TypeSpec type) {
        return new GeneratedFile(self.packageName(), self.simpleName(),
                KernelScaffold.render(self.packageName(), type), ArtifactType.LIST_QUERY);
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.LIST_QUERY;
    }
}
