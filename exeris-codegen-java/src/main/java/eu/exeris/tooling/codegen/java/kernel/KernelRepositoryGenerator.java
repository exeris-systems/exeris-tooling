package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.ColumnNaming;
import eu.exeris.tooling.codegen.java.support.DataScopeSupport;
import eu.exeris.tooling.codegen.java.support.DomainTypeKind;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;
import eu.exeris.tooling.codegen.java.support.SqlColumnTypes;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import static eu.exeris.tooling.codegen.java.support.DataScopeSupport.isTenantPartitioned;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Kernel Repository Generator.
 *
 * <p>Emits a thin persistence adapter wired against the Open-Core SPI
 * {@link eu.exeris.kernel.spi.persistence.TransactionalExecutor} —
 * {@code conn.prepare(sql)} + typed {@code bind*} / {@code RowCursor} index
 * accessors. No JDBC, no {@code DataSource}, no by-name column lookups.
 *
 * <p>Reference pattern: {@code targets/exeris-community-app/.../persistence/
 * ProductRepository.java} and {@code OrderRepository.java} in
 * {@code exeris-benchmarks}.
 *
 * <h2>Shape</h2>
 * <ul>
 *   <li>{@code SELECT} statements list columns <b>explicitly</b> — the
 *       {@code RowCursor} contract is zero-based <i>index</i> only.</li>
 *   <li>Read paths use {@code executor.query(conn -> ...)}; write paths use
 *       {@code executor.executeManaged(conn -> ...)} — managed transaction
 *       boundary, retry on serialisation failure handled by the kernel.</li>
 *   <li>{@code List<X>} fields are persisted as JSON via Jackson 3
 *       ({@code tools.jackson.databind.ObjectMapper}) and get no finder: equality
 *       on JSON text is not equality on the list.</li>
 *   <li>{@code BigDecimal} and {@code LocalDate} have no typed SPI bind, so they
 *       are bound as a string and every placeholder they fill is cast to the
 *       column's SQL type ({@code CAST(? AS DECIMAL(19,4))}, {@code CAST(? AS DATE)})
 *       — read from {@link SqlColumnTypes}, the mapping the migration declares the
 *       column with. PostgreSQL types a string parameter {@code character varying}
 *       and refuses it against a numeric or date column without the cast.</li>
 *   <li>{@code Short} and {@code Byte} bind through {@code bindShort} into a
 *       {@code SMALLINT} column, {@code Float} through {@code bindFloat} into
 *       {@code REAL}. A wrapper-typed scalar ({@code Long}, {@code Integer},
 *       {@code Short}, {@code Byte}, {@code Boolean}, {@code Float}, {@code Double})
 *       binds {@code NULL} when it is {@code null} and reads back {@code null} from
 *       a SQL {@code NULL}; a primitive is bound and read as it is.</li>
 *   <li>{@code Instant} binds/reads natively via {@code bindInstant} /
 *       {@code RowCursor.getInstant} (TIMESTAMPTZ). {@code LocalDateTime}
 *       has no typed SPI accessor, so it is bridged through that native
 *       {@code Instant} path <b>at {@code ZoneOffset.UTC}</b> — the column is
 *       TIMESTAMPTZ and the stored value is the UTC-offset instant of the
 *       wall-clock time, reversed symmetrically on read. Callers must treat
 *       persisted {@code LocalDateTime} values as UTC.</li>
 *   <li>{@code OffsetDateTime} and {@code ZonedDateTime} bind their instant
 *       through {@code bindInstant} into a TIMESTAMPTZ column and read back
 *       through {@code getInstant} <b>at {@code ZoneOffset.UTC}</b>: the instant
 *       is preserved, the offset or zone the value carried is not.</li>
 *   <li>A field whose type has no column encoding ({@link DomainTypeKind#UNSTORABLE}:
 *       a parameterised type other than {@code List<X>}, an array, {@code char},
 *       {@code BigInteger}, or a JDK value type with no {@code valueOf(String)}
 *       such as {@code LocalTime} or {@code Duration}), and a {@code List} whose
 *       element is not a plain type ({@code List<Map<String, String>>},
 *       {@code List<List<X>>}), is refused before anything is emitted
 *       ({@link UnpersistableFieldTypeException}, {@code EXT-GEN-3003}).</li>
 * </ul>
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelRepositoryGenerator implements KernelArtifactGenerator {

    private static final ClassName UUID_TYPE = ClassName.get("java.util", "UUID");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName LIST_TYPE = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    /** Simple JVM type name for {@link java.time.Instant}; also the domain-type tag matched in {@link #classifyDomainType}. */
    private static final String INSTANT_TYPE = "Instant";
    private static final ClassName INSTANT = ClassName.get("java.time", INSTANT_TYPE);
    private static final ClassName LOCAL_DATE = ClassName.get("java.time", "LocalDate");
    private static final ClassName LOCAL_DATE_TIME = ClassName.get("java.time", "LocalDateTime");
    private static final ClassName OFFSET_DATE_TIME = ClassName.get("java.time", "OffsetDateTime");
    private static final ClassName ZONED_DATE_TIME = ClassName.get("java.time", "ZonedDateTime");
    private static final ClassName ZONE_OFFSET = ClassName.get("java.time", "ZoneOffset");
    private static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");
    /**
     * {@code java.lang.Long} — the boxed read of a version field. Deliberately the wrapper: the
     * entity may declare {@code version} either way, and a boxed local accepts both (a primitive
     * autoboxes, a wrapper does not) while a primitive local would NPE on a null wrapper.
     */
    private static final ClassName BOXED_LONG = ClassName.get("java.lang", "Long");

    private static final String SPI_PERSISTENCE_PKG = "eu.exeris.kernel.spi.persistence";
    private static final ClassName TRANSACTIONAL_EXECUTOR =
            ClassName.get(SPI_PERSISTENCE_PKG, "TransactionalExecutor");
    private static final ClassName PERSISTENCE_STATEMENT =
            ClassName.get(SPI_PERSISTENCE_PKG, "PersistenceStatement");
    private static final ClassName QUERY_RESULT =
            ClassName.get(SPI_PERSISTENCE_PKG, "QueryResult");
    private static final ClassName ROW_CURSOR =
            ClassName.get(SPI_PERSISTENCE_PKG, "RowCursor");

    /**
     * {@code KernelProviders} — the SPI's ambient-context accessor. The repository reads the bound
     * {@code StorageContext} from it and nothing else: the acting tenant, when a tenant-partitioned
     * row arrives with no owner set, and on a UNIVERSE entity the acting shared scope. Already a
     * compile-time requirement of every emitted repository via
     * {@code TransactionalExecutor}, so this adds no dependency to the consumer's build.
     */
    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName ILLEGAL_STATE_EXCEPTION =
            ClassName.get(IllegalStateException.class);
    private static final ClassName ILLEGAL_ARGUMENT_EXCEPTION =
            ClassName.get(IllegalArgumentException.class);

    /** Name of the emitted acting-tenant resolver — see {@link #buildActingTenantId}. */
    static final String ACTING_TENANT_METHOD = "actingTenantId";
    /** Name of the emitted acting-shared-scope resolver — see {@link #buildActingSharedScope}. */
    static final String ACTING_SHARED_SCOPE_METHOD = "actingSharedScope";
    /** Name of the emitted foreign-tenant refusal — see {@link #buildRefuseForeignTenant}. */
    static final String REFUSE_FOREIGN_TENANT_METHOD = "refuseForeignTenant";
    /** Name of the emitted foreign-shared-scope refusal — see {@link #buildRefuseForeignSharedScope}. */
    static final String REFUSE_FOREIGN_SHARED_SCOPE_METHOD = "refuseForeignSharedScope";
    /** Emitted message constants, so the resolver's own body stays one readable line per step. */
    private static final String SYSTEM_SCOPE_FIELD = "TENANT_SCOPE_REQUIRED";
    private static final String NOT_A_UUID_FIELD = "TENANT_KEY_NOT_A_UUID";
    private static final String SHARED_SCOPE_NOT_A_UUID_FIELD = "SHARED_SCOPE_KEY_NOT_A_UUID";

    private static final ClassName OBJECT_MAPPER =
            ClassName.get("tools.jackson.databind", "ObjectMapper");
    private static final ClassName JACKSON_EXCEPTION =
            ClassName.get("tools.jackson.core", "JacksonException");
    private static final ClassName TYPE_REFERENCE =
            ClassName.get("tools.jackson.core.type", "TypeReference");

    // Format-string / code-fragment literals — consolidated so SonarQube
    // S1192 stays quiet and so the SQL shape can evolve in one place.
    private static final String ENTITY_SRC = "entity";
    private static final String WHERE_ID_CLAUSE = " WHERE id = ?";
    private static final String SQL_VAR_STMT = "String sql = $S";
    private static final String EXECUTE_MANAGED_LAMBDA = "executor.executeManaged(conn -> ";
    private static final String TRY_PREPARE_STMT = "try ($T stmt = conn.prepare(sql))";
    private static final String RETURN_ENTITY_STMT = "return entity";

    /**
     * Whether the emitted repository for {@code metadata} imports Jackson 3 — true exactly when a
     * field is a {@code List<X>}, which is persisted as a JSON column — a compile requirement
     * the kernel SPI and core do not satisfy.
     *
     * <p>The ADR-060-shaped alternative — encoding these columns through a kernel-provided codec, so
     * the repository names no JSON library — is an open question tracked in {@code ROADMAP.md}.
     *
     * @param metadata the entity
     * @return whether the emitted repository imports {@code tools.jackson}
     */
    static boolean importsJackson(DomainMetadata metadata) {
        return metadata.fields().stream().anyMatch(f -> listElementType(f.type()) != null);
    }

    /** The element type of a {@code List}-typed field, or {@code null} — see {@link DomainTypeKind}. */
    private static String listElementType(String type) {
        return DomainTypeKind.listElementType(type);
    }

    private static DomainTypeKind classifyDomainType(String type) {
        return DomainTypeKind.of(type);
    }

    /**
     * Refuses the entity set when any entity has a domain column whose type the repository cannot
     * store and read back — a {@link DomainTypeKind#UNSTORABLE} field, or a {@code List} whose
     * element type is not a plain type name (a parameterised type, an array, a wildcard). An enum
     * field is never refused, whatever its type is named, when its {@code enumType} is set.
     * A field that is not a column — one shadowing the primary key or an active system column — is
     * not checked, since nothing binds or reads it.
     *
     * <p>Called by the pipeline before it writes anything, so a refused build leaves the generated
     * tree as it was; {@link #generate} applies the same check to its one entity.
     *
     * @param domains the entities of one generation run
     * @throws UnpersistableFieldTypeException naming every refused field, sorted
     */
    public static void requirePersistableFields(List<DomainMetadata> domains) {
        List<String> refused = new ArrayList<>();
        for (DomainMetadata metadata : domains) {
            refused.addAll(unpersistableFields(metadata));
        }
        if (!refused.isEmpty()) {
            throw new UnpersistableFieldTypeException(refused.stream().sorted().toList());
        }
    }

    private static List<String> unpersistableFields(DomainMetadata metadata) {
        List<String> refused = new ArrayList<>();
        for (Column col : buildColumnLayout(metadata.fields(), metadata, resolveSystemFieldNames(metadata))) {
            if (col.kind() == ColumnKind.DOMAIN && !storable(kindOf(col, metadata), col.javaType())) {
                refused.add(metadata.packageName() + "." + metadata.entityName() + "." + col.javaName()
                        + " : " + col.javaType() + " (" + refusalReason(col.javaType()) + ")");
            }
        }
        return refused;
    }

    /**
     * Whether a domain column of this kind has a column encoding: not
     * {@link DomainTypeKind#UNSTORABLE}, and, for a {@code List}, an element type the JSON read can
     * name as a class — a plain, possibly qualified type name, not a parameterised type, an array or
     * a wildcard.
     */

    /**
     * Whether {@code name} is a type name {@code ClassName.bestGuess} accepts: dot-separated Java
     * identifiers, lower-case package segments first, then a class name that starts upper-case and
     * any nested class names. A parameterised name, an array or a wildcard is not.
     */
    static boolean isPlainTypeName(String name) {
        boolean inClass = false;
        for (String segment : name.split("\\.", -1)) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))
                    || !segment.chars().skip(1).allMatch(Character::isJavaIdentifierPart)) {
                return false;
            }
            inClass = inClass || Character.isUpperCase(segment.charAt(0));
            if (!inClass && !Character.isLowerCase(segment.charAt(0)) && segment.charAt(0) != '_'
                    && segment.charAt(0) != '$') {
                return false;
            }
        }
        return inClass;
    }
    private static boolean storable(DomainTypeKind kind, String type) {
        return switch (kind) {
            case UNSTORABLE -> false;
            case LIST -> isPlainTypeName(listElementType(type));
            default -> true;
        };
    }

    private static String refusalReason(String type) {
        if (listElementType(type) != null) {
            return "a List element must be a plain type";
        }
        if (type.contains("<")) {
            return "a parameterised type other than List<…>";
        }
        if (type.endsWith("[]")) {
            return "an array";
        }
        if (DomainTypeKind.of(type) == DomainTypeKind.UNSTORABLE && type.endsWith("BigInteger")) {
            return "no typed SPI accessor and no valueOf(String); BigDecimal is stored";
        }
        return "no typed SPI accessor and no valueOf(String)";
    }

    /**
     * The kind a layout column is bound and read as: a domain column takes its field's
     * {@link DomainTypeKind#of(FieldMetadata) kind}, so an enum is {@code ENUM} whatever its type is
     * named; a system column, and the primary key, the kind of its type.
     */
    private static DomainTypeKind kindOf(Column col, DomainMetadata metadata) {
        if (col.kind() == ColumnKind.DOMAIN) {
            for (FieldMetadata field : metadata.fields()) {
                if (field.name().equals(col.javaName()) && field.type().equals(col.javaType())) {
                    return DomainTypeKind.of(field);
                }
            }
        }
        return classifyDomainType(col.javaType());
    }

    /** The placeholder a column's value is bound through — see {@link SqlColumnTypes#placeholder}. */
    private static String placeholder(Column col, DomainMetadata metadata) {
        return col.kind() == ColumnKind.DOMAIN
                ? SqlColumnTypes.placeholder(kindOf(col, metadata), col.javaType())
                : "?";
    }

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        requirePersistableFields(List.of(metadata));
        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".repository";
        String entity = metadata.entityName();
        String className = entity + "Repository";
        String table = KernelTableNaming.effectiveTable(metadata);
        List<FieldMetadata> fields = metadata.fields();
        boolean hasListField = importsJackson(metadata);

        ClassName entityType = ClassName.get(metadata.packageName(), entity);
        ClassName selfType = ClassName.get(packageName, className);
        TypeName optionalOfEntity = ParameterizedTypeName.get(OPTIONAL, entityType);
        TypeName listOfEntity = ParameterizedTypeName.get(LIST_TYPE, entityType);

        // Effective system-field java names (T5 overrides; defaults when no
        // @ExerisDomain override was written).
        SystemFieldNames sys = resolveSystemFieldNames(metadata);

        // Stable column layout — both SELECT clauses and mapRow consume it
        // in the same order, so column indices line up by construction.
        List<Column> columns = buildColumnLayout(fields, metadata, sys);

        Context ctx = new Context(entity, entityType, fields, columns, metadata, table, sys);

        TypeSpec.Builder repo = KernelScaffold.publicClass(className)
                .addJavadoc("Generated Repository for $L.\n", entity)
                .addJavadoc("<p>Source: {@link $T}\n", entityType)
                .addJavadoc("<p>Table: $L\n", table)
                .addJavadoc("<p>Persistence: Open-Core SPI {@code TransactionalExecutor}\n")
                .addJavadoc("(reads via {@code executor.query(...)}, writes via\n")
                .addJavadoc("{@code executor.executeManaged(...)} with managed transaction\n")
                .addJavadoc("boundary). No JDBC, no {@code DataSource}.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(KernelScaffold.loggerField(selfType))
                .addField(FieldSpec.builder(String.class, "TABLE",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", table)
                        .build());

        if (hasListField) {
            repo.addField(FieldSpec.builder(OBJECT_MAPPER, "MAPPER",
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T()", OBJECT_MAPPER)
                    .build());
            // The import above is a compile requirement no emitted pom declares. Appended after
            // DO NOT EDIT, and only for an entity with a List<X> field.
            repo.addJavadoc("<p>Compile requirement: {@code List<X>} fields are persisted as JSON\n")
                    .addJavadoc("through Jackson 3 ({@code tools.jackson.databind} /\n")
                    .addJavadoc("{@code tools.jackson.core}). Neither {@code exeris-kernel-spi} nor\n")
                    .addJavadoc("{@code -core} depends on it; only the Community driver does, and a\n")
                    .addJavadoc("driver on the runtime classpath alone does not put it on the compile\n")
                    .addJavadoc("classpath. Declare {@code tools.jackson.core:jackson-databind}.\n");
        }

        if (isTenantPartitioned(metadata)) {
            repo.addField(messageField(SYSTEM_SCOPE_FIELD,
                            "The bound StorageContext carries no isolation key, which is the "
                                    + "system/global scope. A tenant-partitioned row needs an owner: "
                                    + "this table's row-level-security policy compares its tenant "
                                    + "column against the session key the kernel publishes from that "
                                    + "context, so a row written without one is refused. Bind a "
                                    + "tenant-scoped StorageContext around this write."))
                    .addField(messageField(NOT_A_UUID_FIELD,
                            "The bound StorageContext's isolation key is not a UUID. The generated "
                                    + "migration for this table casts the session key to uuid inside "
                                    + "its RLS policy, so a non-UUID key matches no row on read "
                                    + "either. This is a deployment fault, not a malformed request."));
        }
        if (sharedScopeColumn(ctx).filter(c -> classifyDomainType(c.javaType()) == DomainTypeKind.UUID)
                .isPresent()) {
            repo.addField(messageField(SHARED_SCOPE_NOT_A_UUID_FIELD,
                    "The bound StorageContext's shared-scope key is not a UUID. The generated "
                            + "shared-scope migration for this table casts that session key to uuid "
                            + "inside its RLS policy, so a non-UUID key fails every read of the table. "
                            + "This is a deployment fault, not a malformed request."));
        }

        repo.addField(FieldSpec.builder(TRANSACTIONAL_EXECUTOR, "executor",
                        Modifier.PRIVATE, Modifier.FINAL).build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(TRANSACTIONAL_EXECUTOR, "executor")
                        .addStatement("this.executor = executor")
                        .build())
                .addMethod(buildFindById(ctx, optionalOfEntity))
                .addMethod(buildFindAll(ctx, listOfEntity))
                .addMethod(buildFindPage(ctx));

        // T8: cross-aggregate finders for filterable fields and MANY_TO_ONE FK
        // columns, so callers stop doing O(n) findAll().stream().filter(...).
        // Emitted in a stable sorted order (filterable fields by name, then FK
        // finders by relationship name) so the surface is byte-deterministic.
        for (MethodSpec finder : buildFinders(ctx, listOfEntity)) {
            repo.addMethod(finder);
        }

        if (!ListQuerySupport.sortable(metadata).isEmpty()) {
            repo.addMethod(buildSortColumn(ctx));
        }
        repo.addMethod(buildBindListFilter(ctx));

        repo.addMethod(buildSave(ctx))
                .addMethod(buildUpdate(ctx))
                .addMethod(buildDeleteById(ctx))
                .addMethod(buildCount(ctx))
                .addMethod(buildMapRow(ctx));

        if (isTenantPartitioned(metadata)) {
            repo.addMethod(buildActingTenantId(ctx))
                    .addMethod(buildRefuseForeignTenant(ctx));
        }
        sharedScopeColumn(ctx).ifPresent(column -> repo.addMethod(buildActingSharedScope(column))
                .addMethod(buildRefuseForeignSharedScope(ctx, column)));

        if (hasListField) {
            repo.addMethod(buildParseList())
                .addMethod(buildToJson());
        }

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, repo.build()), ArtifactType.REPOSITORY);
    }

    /** Column descriptor — accessed by both SELECT clause builder and mapRow. */
    record Column(String sqlName, String javaName, String javaType, ColumnKind kind) {}

    enum ColumnKind { DOMAIN, TENANT_ID, CREATED_AT, UPDATED_AT, DELETED, VERSION }

    /**
     * The emitted column layout for an entity, in the one order that matters: the INSERT's column
     * list, the SELECT's column list and {@code mapRow}'s zero-based cursor indices are all this
     * list walked in sequence, so bind index <em>i</em> and read index <em>i</em> are the same
     * column by construction.
     *
     * <p>Exposed so the generated {@code *RepositoryTest} (T2/ADR-058) can assert that the
     * construction holds at <em>runtime</em> — it saves an entity, replays the recorded binds back
     * as the query result, and checks every column survives. Deriving the test's expectations from
     * the same layout is what makes that non-circular: the test asserts behaviour, not emitted
     * text, so an off-by-one between {@code emitInsertBinds} and {@code emitReadCol} shows up as a
     * failed round-trip even though both generators read this list.
     */
    static List<Column> columnLayout(DomainMetadata metadata) {
        return buildColumnLayout(metadata.fields(), metadata, resolveSystemFieldNames(metadata));
    }

    /**
     * The JavaBean accessor the emitted repository binds a column through — the {@code is}/{@code
     * get} split included, since a primitive {@code boolean} field and the soft-delete flag both
     * read as {@code is<Name>()}. Shared with the repository-test emitter so its assertions cannot
     * name an accessor the bind path does not use.
     */
    static String getterFor(Column col) {
        if ("id".equals(col.javaName())) {
            return "getId";
        }
        String prefix = col.kind() == ColumnKind.DELETED || "boolean".equals(col.javaType())
                ? "is" : "get";
        return prefix + capitalize(col.javaName());
    }

    /** The matching mutator; {@code id} is {@code setId} regardless of the {@code is}/{@code get} split. */
    static String setterFor(Column col) {
        return "id".equals(col.javaName()) ? "setId" : "set" + capitalize(col.javaName());
    }

    /**
     * The layout column carrying a system kind. Only called under the metadata flag that emits it,
     * so the column is always present — reaching for a system column the flag did not emit is a
     * generator bug, not a runtime condition.
     */
    private static Column systemColumn(Context ctx, ColumnKind kind) {
        return ctx.columns().stream()
                .filter(c -> c.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no " + kind + " column in the layout for " + ctx.entity()));
    }

    /** Carrier for repeated build-time state — keeps signatures readable. */
    record Context(String entity, ClassName entityType, List<FieldMetadata> fields,
                           List<Column> columns, DomainMetadata metadata, String table,
                           SystemFieldNames sys) {}

    /**
     * Effective java field names for the system fields the repository emits
     * (T5). Resolved from {@link DomainMetadata#systemFields()} when present,
     * else the canonical defaults.
     */
    record SystemFieldNames(String tenantId, String createdAt, String updatedAt,
                                    String deleted, String version) {}

    private static SystemFieldNames resolveSystemFieldNames(DomainMetadata metadata) {
        SystemFieldsMetadata sf = metadata.systemFields();
        return new SystemFieldNames(
                resolve(sf == null ? null : sf.tenantIdField(), "tenantId"),
                resolve(sf == null ? null : sf.createdAtField(), "createdAt"),
                resolve(sf == null ? null : sf.updatedAtField(), "updatedAt"),
                resolve(sf == null ? null : sf.softDeleteField(), "deleted"),
                resolve(sf == null ? null : sf.versionField(), "version"));
    }

    private static String resolve(String override, String fallback) {
        return (override != null && !override.isBlank()) ? override : fallback;
    }

    private static List<Column> buildColumnLayout(List<FieldMetadata> fields, DomainMetadata metadata,
                                                  SystemFieldNames sys) {
        List<Column> cols = new ArrayList<>();
        // T14: de-dupe by SQL column name. The processor emits EVERY instance field,
        // including ones that shadow the primary key or a system column (e.g. an
        // explicit `id`, or a declared `version`/`createdAt` on an audited/versioned
        // entity). Without this, buildColumnLayout emitted the same column twice —
        // an invalid SELECT/INSERT and a double bind. System semantics win: a domain
        // field whose column collides with the PK or an active system column is dropped.
        Set<String> seen = new HashSet<>();
        Set<String> systemCols = new HashSet<>();
        if (isTenantPartitioned(metadata)) {
            systemCols.add(toSnakeCase(sys.tenantId()));
        }
        if (metadata.audited()) {
            systemCols.add(toSnakeCase(sys.createdAt()));
            systemCols.add(toSnakeCase(sys.updatedAt()));
        }
        if (metadata.softDelete()) {
            systemCols.add(toSnakeCase(sys.deleted()));
        }
        if (metadata.versioned()) {
            systemCols.add(toSnakeCase(sys.version()));
        }

        cols.add(new Column("id", "id", "UUID", ColumnKind.DOMAIN));
        seen.add("id");
        for (FieldMetadata field : fields) {
            String sqlName = toSnakeCase(field.name());
            // skip a duplicate of the PK / an earlier field, or a shadow of a system column
            // (membership check before mutating `seen`, so the guard has no side effect)
            if (seen.contains(sqlName) || systemCols.contains(sqlName)) {
                continue;
            }
            seen.add(sqlName);
            cols.add(new Column(sqlName, field.name(), field.type(), ColumnKind.DOMAIN));
        }
        if (isTenantPartitioned(metadata)) {
            cols.add(new Column(toSnakeCase(sys.tenantId()), sys.tenantId(), "UUID", ColumnKind.TENANT_ID));
        }
        if (metadata.audited()) {
            cols.add(new Column(toSnakeCase(sys.createdAt()), sys.createdAt(), INSTANT_TYPE, ColumnKind.CREATED_AT));
            cols.add(new Column(toSnakeCase(sys.updatedAt()), sys.updatedAt(), INSTANT_TYPE, ColumnKind.UPDATED_AT));
        }
        if (metadata.softDelete()) {
            cols.add(new Column(toSnakeCase(sys.deleted()), sys.deleted(), "boolean", ColumnKind.DELETED));
        }
        if (metadata.versioned()) {
            cols.add(new Column(toSnakeCase(sys.version()), sys.version(), "Long", ColumnKind.VERSION));
        }
        return cols;
    }

    private MethodSpec buildFindById(Context ctx, TypeName optionalOfEntity) {
        String selectCols = String.join(", ", ctx.columns().stream().map(Column::sqlName).toList());
        String softDeleteFilter = ctx.metadata().softDelete()
                ? " AND " + toSnakeCase(ctx.sys().deleted()) + " = false" : "";
        String sql = "SELECT " + selectCols + " FROM " + ctx.table() + WHERE_ID_CLAUSE + softDeleteFilter;

        return MethodSpec.methodBuilder("findById")
                .addModifiers(Modifier.PUBLIC)
                .returns(optionalOfEntity)
                .addParameter(UUID_TYPE, "id")
                .addStatement(SQL_VAR_STMT, sql)
                .addStatement("""
                        return executor.query(conn -> {
                            try ($T stmt = conn.prepare(sql)) {
                                stmt.bindUuid(0, id);
                                try ($T qr = stmt.executeQuery()) {
                                    return qr.next() ? $T.of(mapRow(qr.row())) : $T.empty();
                                }
                            }
                        })""",
                        PERSISTENCE_STATEMENT, QUERY_RESULT, OPTIONAL, OPTIONAL)
                .build();
    }

    private MethodSpec buildFindAll(Context ctx, TypeName listOfEntity) {
        String selectCols = String.join(", ", ctx.columns().stream().map(Column::sqlName).toList());
        String softDeleteFilter = ctx.metadata().softDelete()
                ? " WHERE " + toSnakeCase(ctx.sys().deleted()) + " = false" : "";
        String sql = "SELECT " + selectCols + " FROM " + ctx.table() + softDeleteFilter;

        // Combined try-with-resources for stmt + executeQuery() is safe here
        // because findAll has no parameter binds — never copy this shape into
        // a method that needs bindXxx(...) calls between prepare() and
        // executeQuery(); use the nested form findById uses instead.
        return MethodSpec.methodBuilder("findAll")
                .addModifiers(Modifier.PUBLIC)
                .returns(listOfEntity)
                .addStatement(SQL_VAR_STMT, sql)
                .addStatement("""
                        return executor.query(conn -> {
                            try ($T stmt = conn.prepare(sql);
                                 $T qr = stmt.executeQuery()) {
                                $T<$T> result = new $T<>();
                                while (qr.next()) {
                                    result.add(mapRow(qr.row()));
                                }
                                return result;
                            }
                        })""",
                        PERSISTENCE_STATEMENT, QUERY_RESULT,
                        LIST_TYPE, ctx.entityType(), ARRAY_LIST)
                .build();
    }

    /**
     * The list route's query: one page of the rows the {@code <Entity>ListQuery} filters select, in
     * its sort order, and the number of rows matched — two statements inside one
     * {@code executor.query}, so both read through the same connection.
     *
     * <p><b>Nothing from the request reaches the SQL as text.</b> The statement is assembled from
     * fragments emitted here: a predicate per non-null filter component, each a fixed
     * {@code <column> = ?} (the placeholder cast to the column's type for a value bound as text,
     * as the writes do), the {@code ORDER BY} column from {@code sortColumn}'s fixed table, and
     * {@code LIMIT ? OFFSET ?}. Filter values, the limit and the offset are bound. The predicates are
     * appended and bound by walking the filter components in the same emitted order, so bind index
     * <em>i</em> is predicate <em>i</em> by construction.
     *
     * <p>{@code id} closes every {@code ORDER BY}, so rows that tie on the sort column keep one order
     * from page to page and a row cannot appear on two pages or on none.
     *
     * <p>The soft-delete predicate is the one {@code findAll} applies. Tenant and shared-scope
     * visibility are left to row-level security exactly as on {@code findAll}: nothing here binds a
     * tenant, so the list reads what the session's policy shows and nothing else.
     */
    private MethodSpec buildFindPage(Context ctx) {
        DomainMetadata metadata = ctx.metadata();
        ClassName queryType = KernelListQueryGenerator.listQueryType(metadata);
        ClassName pageType = KernelListQueryGenerator.pageType(metadata);
        ClassName filterType = KernelListQueryGenerator.filterType(metadata);
        String selectCols = String.join(", ", ctx.columns().stream().map(Column::sqlName).toList());

        MethodSpec.Builder method = MethodSpec.methodBuilder("findPage")
                .addModifiers(Modifier.PUBLIC)
                .returns(pageType)
                .addParameter(queryType, "query")
                .addJavadoc("One page of rows matching {@code query}'s filters, in its sort order, with the\n")
                .addJavadoc("total number of matching rows.\n")
                .addJavadoc("\n")
                .addJavadoc("@param query the page, sort and filters; already validated by its constructor\n")
                .addJavadoc("@return the page\n")
                .addStatement("$T filter = query.filter()", filterType)
                .addStatement("$T<String> predicates = new $T<>()", LIST_TYPE, ARRAY_LIST);
        if (metadata.softDelete()) {
            method.addStatement("predicates.add($S)", toSnakeCase(ctx.sys().deleted()) + " = false");
        }
        for (ListQuerySupport.Property filter : ListQuerySupport.filters(metadata)) {
            method.beginControlFlow("if (filter.$L() != null)", filter.name())
                    .addStatement("predicates.add($S)", filter.column() + " = "
                            + SqlColumnTypes.placeholder(filter.kind(), filter.javaType()))
                    .endControlFlow();
        }
        method.addStatement("String where = predicates.isEmpty() ? $S : $S + String.join($S, predicates)",
                "", " WHERE ", " AND ");
        if (ListQuerySupport.sortable(metadata).isEmpty()) {
            method.addStatement("String order = $S", " ORDER BY id");
        } else {
            method.addStatement("String order = query.sort() == null ? $S : $S + sortColumn(query.sort())"
                            + " + (query.descending() ? $S : $S)",
                    " ORDER BY id", " ORDER BY ", " DESC, id", " ASC, id");
        }
        method.addStatement("String countSql = $S + where", "SELECT COUNT(*) FROM " + ctx.table())
                .addStatement("String pageSql = $S + where + order + $S",
                        "SELECT " + selectCols + " FROM " + ctx.table(), " LIMIT ? OFFSET ?")
                .addStatement("""
                        return executor.query(conn -> {
                            long total;
                            try ($T stmt = conn.prepare(countSql)) {
                                bindListFilter(stmt, filter);
                                try ($T qr = stmt.executeQuery()) {
                                    total = qr.next() ? qr.row().getLong(0) : 0L;
                                }
                            }
                            $T<$T> content = new $T<>();
                            try ($T stmt = conn.prepare(pageSql)) {
                                int next = bindListFilter(stmt, filter);
                                stmt.bindInt(next, query.size());
                                stmt.bindLong(next + 1, (long) query.page() * query.size());
                                try ($T qr = stmt.executeQuery()) {
                                    while (qr.next()) {
                                        content.add(mapRow(qr.row()));
                                    }
                                }
                            }
                            return $T.of(content, total, query.page(), query.size());
                        })""",
                        PERSISTENCE_STATEMENT, QUERY_RESULT,
                        LIST_TYPE, ctx.entityType(), ARRAY_LIST,
                        PERSISTENCE_STATEMENT, QUERY_RESULT, pageType);
        return method.build();
    }

    /**
     * The fixed table from a sortable property to its column — the only path by which {@code sort}
     * reaches SQL. The query's constructor has already refused any other property, so the
     * {@code default} is unreachable from a request; it throws rather than fall back, so a query
     * built around the constructor cannot sort on text.
     */
    private MethodSpec buildSortColumn(Context ctx) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("sortColumn")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(String.class, "property")
                .addCode("return switch (property) {\n$>");
        for (ListQuerySupport.Property property : ListQuerySupport.sortable(ctx.metadata())) {
            method.addCode("case $S -> $S;\n", property.name(), property.column());
        }
        return method.addCode("default -> throw new $T($S + property);\n$<};\n",
                        ILLEGAL_ARGUMENT_EXCEPTION, "not a sortable property: ")
                .build();
    }

    /**
     * Binds the non-null filter components from index 0, in the order {@code findPage} appended
     * their predicates, and returns the next free index. Each value goes through the same bind its
     * column's writes use — a {@code BigDecimal} as its plain string, an enum or a {@code LocalDate}
     * as its {@code toString()}, a {@code Byte} widened to {@code bindShort}.
     */
    private MethodSpec buildBindListFilter(Context ctx) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("bindListFilter")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.INT)
                .addParameter(PERSISTENCE_STATEMENT, "stmt")
                .addParameter(KernelListQueryGenerator.filterType(ctx.metadata()), "filter")
                .addStatement("int index = 0");
        for (ListQuerySupport.Property filter : ListQuerySupport.filters(ctx.metadata())) {
            String value = "filter." + filter.name() + "()";
            method.beginControlFlow("if ($L != null)", value);
            switch (filter.kind()) {
                case UUID -> method.addStatement("stmt.bindUuid(index++, $L)", value);
                case STRING -> method.addStatement("stmt.bindString(index++, $L)", value);
                case LONG -> method.addStatement("stmt.bindLong(index++, $L)", value);
                case INT -> method.addStatement("stmt.bindInt(index++, $L)", value);
                case SHORT -> method.addStatement("stmt.bindShort(index++, $L)", value);
                case BYTE -> method.addStatement("stmt.bindShort(index++, $L.shortValue())", value);
                case BOOL -> method.addStatement("stmt.bindBoolean(index++, $L)", value);
                case FLOAT -> method.addStatement("stmt.bindFloat(index++, $L)", value);
                case DOUBLE -> method.addStatement("stmt.bindDouble(index++, $L)", value);
                case BIG_DECIMAL -> method.addStatement("stmt.bindString(index++, $L.toPlainString())", value);
                default -> method.addStatement("stmt.bindString(index++, $L.toString())", value);
            }
            method.endControlFlow();
        }
        return method.addStatement("return index").build();
    }

    /**
     * T8: builds the cross-aggregate finders in a stable, byte-deterministic
     * order — filterable-field finders first (sorted by field name), then
     * MANY_TO_ONE FK finders (sorted by relationship name). Each returns
     * {@code List<Entity>}, applies the same soft-delete filter the CRUD reads
     * use, and ends with {@code ORDER BY id} so the row order is deterministic.
     */
    /** The primary-key lookup every generated repository and service already declares. */
    private static final String PRIMARY_KEY_LOOKUP = "findById";

    /**
     * Finder name a filterable field emits — on the repository and, in lock-step, on the service.
     * Both call this rather than rebuilding the string, so {@link #shadowsPrimaryKeyLookup} can be
     * expressed against the name that is actually emitted.
     */
    static String fieldFinderName(String fieldName) {
        return "findBy" + capitalize(fieldName);
    }

    /** Finder name a {@code MANY_TO_ONE} relationship emits over its FK column. */
    static String foreignKeyFinderName(String relationshipName) {
        return "findBy" + capitalize(KernelTableNaming.foreignKeyBase(relationshipName)) + "Id";
    }

    /**
     * Whether a filterable field's finder would collide with the built-in primary-key lookup.
     * A field named {@code id} emits {@code findById(UUID)}, which the repository and service
     * already declare — {@code method findById(UUID) is already defined}. It is easy to hit by
     * accident: the processor records a field with no {@code @Field} annotation via
     * {@code FieldMetadata.simple(...)}, which sets {@code filterable(true)}, so a plain
     * {@code private UUID id;} on an entity was enough to make the emitted tree uncompilable.
     * Skipping is right rather than renaming: the built-in lookup already covers exactly this
     * query, and it returns the {@code Optional} a primary-key lookup should.
     *
     * <p>The test is a comparison of <em>derived names</em>, not of the field name. Neither
     * shortcut is correct: {@code "id".equals(name)} misses {@code Id}, whose finder is also
     * {@code findById} because only the first character is capitalized; and
     * {@code "id".equalsIgnoreCase(name)} over-matches {@code ID} and {@code iD}, whose finder is
     * {@code findByID} and collides with nothing. Deriving through {@link #fieldFinderName} keeps
     * the predicate true by construction if the naming scheme ever changes.
     */
    static boolean shadowsPrimaryKeyLookup(FieldMetadata field) {
        return PRIMARY_KEY_LOOKUP.equals(fieldFinderName(field.name()));
    }

    /**
     * One emitted finder, resolved from the metadata once.
     *
     * <p>Three surfaces emit against the same finder set — the repository declares them, the
     * service delegates to them, and the generated {@code *ServiceTest} (T2/ADR-058) overrides
     * them on its repository double. Each carrying its own copy of "which fields get a finder, in
     * what order, with what parameter type" is three places to drift; a service method with no
     * repository method behind it does not compile, and a double that overrides a method the
     * service never calls silently tests nothing.
     *
     * @param methodName    the emitted method name, e.g. {@code findByOrderNumber}
     * @param paramType     the JavaPoet parameter type
     * @param paramTypeName the metadata type string, for the SPI bind dispatch
     * @param paramName     the emitted parameter name
     * @param column        the SQL column the predicate filters on (repository-only)
     * @param kind          the parameter's kind, which picks its bind and placeholder
     */
    record FinderSpec(String methodName, TypeName paramType, String paramTypeName,
                      String paramName, String column, DomainTypeKind kind) {}

    /**
     * The finder set for an entity, in the byte-deterministic emission order: filterable fields
     * sorted by name, then MANY_TO_ONE FK finders sorted by relationship name.
     *
     * <p>Every filterable field gets a finder; the WHERE column always exists — either as a domain
     * column or, in the rare shadow case, as the system column it collides with. Two exclusions: a
     * field whose finder would shadow the primary-key lookup (see {@link #shadowsPrimaryKeyLookup}),
     * and a {@code List} field, whose column is JSON text — equality on it compares a rendering, not
     * the list, so such a finder could not find the row it was written from.
     */
    static List<FinderSpec> finderSpecs(DomainMetadata metadata) {
        List<FinderSpec> specs = new ArrayList<>();

        metadata.fields().stream()
                .filter(FieldMetadata::filterable)
                .filter(f -> !shadowsPrimaryKeyLookup(f))
                .filter(f -> DomainTypeKind.of(f) != DomainTypeKind.LIST)
                .sorted(Comparator.comparing(FieldMetadata::name))
                .forEach(f -> specs.add(new FinderSpec(
                        fieldFinderName(f.name()),
                        KernelTypeMapping.typeNameOf(f.type()),
                        f.type(),
                        f.name(),
                        toSnakeCase(f.name()),
                        DomainTypeKind.of(f))));

        if (metadata.hasRelationships()) {
            // An explicit-UUID foreign key (@Relationship UUID customerId) is also a field, and the
            // processor records a field without @Field as filterable, so its field finder is
            // already findByCustomerId over customer_id: the same method on the same column. The
            // FK finder is emitted only when no field finder took its name.
            Set<String> taken = new HashSet<>();
            specs.forEach(spec -> taken.add(spec.methodName()));
            metadata.relationships().stream()
                    .filter(r -> r.type() == RelationshipMetadata.RelationType.MANY_TO_ONE)
                    .filter(r -> !taken.contains(foreignKeyFinderName(r.name())))
                    .sorted(Comparator.comparing(RelationshipMetadata::name))
                    .forEach(r -> specs.add(new FinderSpec(
                            foreignKeyFinderName(r.name()),
                            UUID_TYPE,
                            "UUID",
                            KernelTableNaming.foreignKeyBase(r.name()) + "Id",
                            KernelTableNaming.foreignKeyColumn(r.name()),
                            DomainTypeKind.UUID)));
        }
        return specs;
    }

    private List<MethodSpec> buildFinders(Context ctx, TypeName listOfEntity) {
        return finderSpecs(ctx.metadata()).stream()
                .map(spec -> buildFinder(ctx, listOfEntity, spec))
                .toList();
    }

    /**
     * Shared finder body — mirrors {@link #buildFindAll} (read via
     * {@code executor.query}, accumulate {@code mapRow(qr.row())} into a
     * {@code List}) but with a single bound {@code WHERE <column> = ?} predicate.
     * Uses the nested try-with-resources form (a parameter bind sits between
     * {@code prepare} and {@code executeQuery}). The soft-delete filter and a
     * trailing {@code ORDER BY id} keep results consistent and deterministic.
     */
    private MethodSpec buildFinder(Context ctx, TypeName listOfEntity, FinderSpec spec) {
        String selectCols = String.join(", ", ctx.columns().stream().map(Column::sqlName).toList());
        String softDeleteFilter = ctx.metadata().softDelete()
                ? " AND " + toSnakeCase(ctx.sys().deleted()) + " = false" : "";
        String sql = "SELECT " + selectCols + " FROM " + ctx.table()
                + " WHERE " + spec.column() + " = " + SqlColumnTypes.placeholder(spec.kind(), spec.paramTypeName())
                + softDeleteFilter + " ORDER BY id";

        return MethodSpec.methodBuilder(spec.methodName())
                .addModifiers(Modifier.PUBLIC)
                .returns(listOfEntity)
                .addParameter(spec.paramType(), spec.paramName())
                .addStatement(SQL_VAR_STMT, sql)
                .addStatement("""
                        return executor.query(conn -> {
                            try ($T stmt = conn.prepare(sql)) {
                                $L
                                try ($T qr = stmt.executeQuery()) {
                                    $T<$T> result = new $T<>();
                                    while (qr.next()) {
                                        result.add(mapRow(qr.row()));
                                    }
                                    return result;
                                }
                            }
                        })""",
                        PERSISTENCE_STATEMENT, finderBind(spec),
                        QUERY_RESULT, LIST_TYPE, ctx.entityType(), ARRAY_LIST)
                .build();
    }

    /**
     * Binds the single finder parameter at index 0 through the bind the write path uses for the
     * same kind, so the parameter compares against the column in the encoding the column holds: a
     * timestamp kind as its instant, {@code Short}/{@code Byte}/{@code Float} through their typed
     * binds, {@code BigDecimal} as its plain string and {@code LocalDate} or an enum as its
     * {@code toString()} (the first two into a cast placeholder). A {@code null} argument binds
     * {@code NULL}, which matches no row.
     */
    private CodeBlock finderBind(FinderSpec spec) {
        return bindValue(spec.kind(), spec.paramTypeName(), "0", spec.paramName());
    }

    /**
     * One {@code stmt.bind…} statement for {@code value} at {@code index}, by kind — the single
     * dispatch the writes and the finders share. A reference-typed value is null-guarded with
     * {@code bindNull}; a primitive one ({@link DomainTypeKind#isPrimitive}) is bound as it is.
     * {@code value} is an expression and may be evaluated twice, so it must have no side effect.
     * The block ends with {@code ;} and no line break.
     */
    private static CodeBlock bindValue(DomainTypeKind kind, String type, String index, String value) {
        boolean primitive = DomainTypeKind.isPrimitive(type);
        return switch (kind) {
            case LIST -> CodeBlock.of("stmt.bindString($L, toJson($L));", index, value);
            case UUID -> CodeBlock.of("stmt.bindUuid($L, $L);", index, value);
            case STRING -> CodeBlock.of("stmt.bindString($L, $L);", index, value);
            case LONG -> guarded(primitive, index, value, CodeBlock.of("stmt.bindLong($L, $L)", index, value));
            case INT -> guarded(primitive, index, value, CodeBlock.of("stmt.bindInt($L, $L)", index, value));
            case SHORT -> guarded(primitive, index, value, CodeBlock.of("stmt.bindShort($L, $L)", index, value));
            // SMALLINT: a byte widens to short; a Byte unboxes through shortValue().
            case BYTE -> guarded(primitive, index, value, CodeBlock.of(
                    primitive ? "stmt.bindShort($L, $L)" : "stmt.bindShort($L, $L.shortValue())", index, value));
            case BOOL -> guarded(primitive, index, value, CodeBlock.of("stmt.bindBoolean($L, $L)", index, value));
            case FLOAT -> guarded(primitive, index, value, CodeBlock.of("stmt.bindFloat($L, $L)", index, value));
            case DOUBLE -> guarded(primitive, index, value, CodeBlock.of("stmt.bindDouble($L, $L)", index, value));
            // SPI has no bindBigDecimal — encoded as the plain string, into a cast placeholder.
            case BIG_DECIMAL -> CodeBlock.of("stmt.bindString($L, $L == null ? null : $L.toPlainString());",
                    index, value, value);
            // T19: native bindInstant (kernel 0.10 SPI) for TIMESTAMPTZ columns.
            case INSTANT_LIKE -> guarded(false, index, value,
                    CodeBlock.of("stmt.bindInstant($L, $L)", index, value));
            // T19b: LocalDateTime → TIMESTAMPTZ at the UTC offset (the read reverses it).
            case LOCAL_DATE_TIME -> guarded(false, index, value,
                    CodeBlock.of("stmt.bindInstant($L, $L.toInstant($T.UTC))", index, value, ZONE_OFFSET));
            // The instant is what is stored; the offset or zone is not (read back at UTC).
            case OFFSET_DATE_TIME, ZONED_DATE_TIME -> guarded(false, index, value,
                    CodeBlock.of("stmt.bindInstant($L, $L.toInstant())", index, value));
            case UNSTORABLE -> throw unsupported(type);
            // No bindLocalDate and no enum bind: the string form (a LocalDate into a cast placeholder).
            case LOCAL_DATE, ENUM, OPAQUE -> CodeBlock.of(
                    "stmt.bindString($L, $L == null ? null : $L.toString());", index, value, value);
        };
    }

    /** {@code bind;} for a primitive, else {@code if (value == null) stmt.bindNull(index); else bind;}. */
    private static CodeBlock guarded(boolean primitive, String index, String value, CodeBlock bind) {
        return primitive
                ? CodeBlock.of("$L;", bind)
                : CodeBlock.of("if ($L == null) stmt.bindNull($L); else $L;", value, index, bind);
    }


    /**
     * Emits the tenant stamp, for a tenant-partitioned entity only (T36).
     *
     * <p>Both write paths bind the tenant column from the entity, and nothing upstream fills it:
     * the emitted handler decodes the request body straight into the entity, and the emitted
     * Angular form treats the tenant as a system field and never sends one. So the value bound was
     * {@code null} on every create and on every full-body update — and the row-level-security
     * policy this generator's own migration installs refuses a row whose tenant does not match the
     * session key. The write failed as a security violation rather than as the missing stamp it was.
     *
     * <p>Filled only when absent, exactly like {@code id} one line above: {@code save} already has
     * a "fills what the caller left out" contract, and the tenant is the fourth system field it was
     * not honouring.
     *
     * <p><b>A <em>contradicted</em> tenant is refused here</b> — the statement after the stamp —
     * rather than left to the RLS {@code WITH CHECK} predicate alone, because the database does not
     * always enforce it: a superuser or {@code BYPASSRLS} role skips even a forced policy, an engine
     * without row-level security has none, and where the policy does fire its violation reaches the
     * handler's {@code catch (RuntimeException)} as a {@code 500} — a server fault reported for a
     * request the caller got wrong. The refusal applies only while a tenant is bound; with none bound
     * the row is left to the database, so a seeder that writes owners explicitly keeps working.
     */
    private static void appendTenantStamp(MethodSpec.Builder method, Context ctx) {
        if (!isTenantPartitioned(ctx.metadata())) {
            return;
        }
        Column tenant = systemColumn(ctx, ColumnKind.TENANT_ID);
        method.addStatement("if (entity.$L() == null) entity.$L($L())",
                getterFor(tenant), setterFor(tenant), ACTING_TENANT_METHOD);
        method.addStatement("$L(entity.$L())", REFUSE_FOREIGN_TENANT_METHOD, getterFor(tenant));
    }

    /**
     * Emits the foreign-tenant refusal: a written row naming a tenant other than the
     * bound one is refused with {@code <Entity>TenantMismatchException}, which the generated handler
     * answers {@code 400}.
     *
     * <p>"Bound" means a {@code StorageContext} is bound <em>and</em> carries an isolation key. An
     * unbound slot and the system scope both mean "no tenant to compare against", and the row is
     * left to row-level security unchanged. It reads {@code STORAGE_CONTEXT.isBound()} before
     * {@code storageContext()} for that reason: the request-scoped accessor throws on an unbound
     * slot, and a refusal that only applies while a tenant is bound must not turn "none bound" into
     * a failure. A non-UUID isolation key is the same deployment fault the stamp reports, and is
     * reported the same way — never as the caller's.
     */
    private static MethodSpec buildRefuseForeignTenant(Context ctx) {
        Column tenant = systemColumn(ctx, ColumnKind.TENANT_ID);
        ClassName mismatch = KernelErrorGenerator.tenantMismatchType(ctx.metadata());
        return MethodSpec.methodBuilder(REFUSE_FOREIGN_TENANT_METHOD)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(UUID_TYPE, tenant.javaName())
                .addJavadoc("Refuses a written row whose {@code $L} is not the tenant this request is\n",
                        tenant.javaName())
                .addJavadoc("bound to. With no tenant bound — no {@code StorageContext}, or\n")
                .addJavadoc("the system scope — the row is left to row-level security, unchanged.\n")
                .addJavadoc("\n")
                .addJavadoc("@throws $T if a tenant is bound and {@code $L} names another\n",
                        mismatch, tenant.javaName())
                .addJavadoc("@throws IllegalStateException if the bound isolation key is not a UUID — a\n")
                .addJavadoc("        deployment fault\n")
                .beginControlFlow("if ($L == null || !$T.STORAGE_CONTEXT.isBound())",
                        tenant.javaName(), KERNEL_PROVIDERS)
                .addStatement("return")
                .endControlFlow()
                .addStatement("$T<$T> isolationKey = $T.storageContext().isolationKey()",
                        OPTIONAL, String.class, KERNEL_PROVIDERS)
                .beginControlFlow("if (isolationKey.isEmpty())")
                .addStatement("return")
                .endControlFlow()
                .addStatement("$T bound", UUID_TYPE)
                .beginControlFlow("try")
                .addStatement("bound = $T.fromString(isolationKey.get())", UUID_TYPE)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("throw new $T($L, e)", ILLEGAL_STATE_EXCEPTION, NOT_A_UUID_FIELD)
                .endControlFlow()
                .beginControlFlow("if (!bound.equals($L))", tenant.javaName())
                .addStatement("throw new $T($L)", mismatch, tenant.javaName())
                .endControlFlow()
                .build();
    }

    /**
     * The layout column of a UNIVERSE entity's {@code @SharedScope} field; empty for
     * every other entity. It is an ordinary domain column — bound and read through
     * {@link #classifyDomainType} like any UUID or String field — and only the stamp is special.
     */
    private static Optional<Column> sharedScopeColumn(Context ctx) {
        return sharedScopeColumn(ctx.metadata(), ctx.columns());
    }

    /**
     * {@link #sharedScopeColumn(Context)} over an explicit layout — shared with the repository-test
     * emitter, so its assertions index the column this repository binds.
     */
    static Optional<Column> sharedScopeColumn(DomainMetadata metadata, List<Column> columns) {
        return DataScopeSupport.sharedScopeField(metadata).flatMap(field -> columns.stream()
                .filter(c -> c.kind() == ColumnKind.DOMAIN && c.javaName().equals(field.name()))
                .findFirst());
    }

    /**
     * Emits the shared-scope stamp, for a UNIVERSE entity only: a row the caller left
     * untagged is tagged with the acting shared scope, the same fill-if-absent contract as the
     * tenant stamp beside it. A caller-supplied value is kept.
     */
    private static void appendSharedScopeStamp(MethodSpec.Builder method, Context ctx) {
        sharedScopeColumn(ctx).ifPresent(column -> method
                .addStatement("if (entity.$L() == null) entity.$L($L())",
                        getterFor(column), setterFor(column), ACTING_SHARED_SCOPE_METHOD)
                .addStatement("$L(entity.$L())", REFUSE_FOREIGN_SHARED_SCOPE_METHOD, getterFor(column)));
    }

    /**
     * Emits the foreign-shared-scope refusal — the tenant rule applied to the caller-writable
     * {@code @SharedScope} field. A row tagged with a scope other than the bound one is
     * refused with {@code <Entity>SharedScopeMismatchException}, answered {@code 400}. With no scope
     * bound the caller's tag is kept.
     *
     * <p>It compares against {@link #buildActingSharedScope}'s answer, so "bound" means exactly what
     * the stamp means by it — a non-blank key on the bound context — and a non-UUID key for a UUID
     * column is the same deployment fault there as here.
     */
    private static MethodSpec buildRefuseForeignSharedScope(Context ctx, Column column) {
        boolean uuid = classifyDomainType(column.javaType()) == DomainTypeKind.UUID;
        ClassName mismatch = KernelErrorGenerator.sharedScopeMismatchType(ctx.metadata());
        return MethodSpec.methodBuilder(REFUSE_FOREIGN_SHARED_SCOPE_METHOD)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(uuid ? UUID_TYPE : ClassName.get(String.class), column.javaName())
                .addJavadoc("Refuses a written row whose {@code $L} is not the shared scope this request\n",
                        column.javaName())
                .addJavadoc("is bound to. With no scope bound, the caller's tag is kept.\n")
                .addJavadoc("\n")
                .addJavadoc("@throws $T if a scope is bound and {@code $L} names another\n",
                        mismatch, column.javaName())
                .beginControlFlow("if ($L == null)", column.javaName())
                .addStatement("return")
                .endControlFlow()
                .addStatement("$T bound = $L()", uuid ? UUID_TYPE : ClassName.get(String.class),
                        ACTING_SHARED_SCOPE_METHOD)
                .beginControlFlow("if (bound != null && !bound.equals($L))", column.javaName())
                .addStatement(uuid ? "throw new $T($L.toString())" : "throw new $T($L)",
                        mismatch, column.javaName())
                .endControlFlow()
                .build();
    }

    /**
     * Emits the acting-shared-scope resolver: the tag stamped on a UNIVERSE row whose caller left
     * the shared-scope field unset.
     *
     * <p><b>It reads {@code storageContextOrSystem()}, where the tenant resolver deliberately reads
     * {@code storageContext()}.</b> The asymmetry follows the failure each one guards. A row with no
     * owner cannot be written at all — the owner-pinned policy refuses it — so an unbound context is
     * worth an exception naming the wiring. A row with no shared scope is simply owner-private: the
     * narrower of the two answers, and a perfectly writable row. Throwing here would break exactly
     * the writes the tenant rule keeps working — a seeder that sets the owner explicitly and binds no
     * context — while falling back cannot widen anything, because the system context carries no
     * shared scope.
     *
     * <p>A blank key is treated as absent: the kernel publishes the absent key as {@code ''}, and the
     * policy's {@code NULLIF} reads {@code ''} as "no scope", so a row stamped {@code ''} would be one
     * no session could ever widen onto. A non-UUID key for a UUID column is an
     * {@code IllegalStateException} for the same reason as the tenant resolver's: the policy casts it,
     * so it is a deployment fault, and escaping as {@code IllegalArgumentException} would be misread
     * as the caller's.
     */
    private static MethodSpec buildActingSharedScope(Column column) {
        boolean uuid = classifyDomainType(column.javaType()) == DomainTypeKind.UUID;
        MethodSpec.Builder resolver = MethodSpec.methodBuilder(ACTING_SHARED_SCOPE_METHOD)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(uuid ? UUID_TYPE : ClassName.get(String.class))
                .addJavadoc("The acting shared scope — the tag stamped on a written row when the\n")
                .addJavadoc("caller left {@code $L} unset.\n", column.javaName())
                .addJavadoc("\n")
                .addJavadoc("<p>This is the bound {@code StorageContext}'s shared-scope key, which is\n")
                .addJavadoc("also what the kernel publishes as {@code exeris.shared_scope} and what\n")
                .addJavadoc("this table's shared-scope policy compares against. No bound context, or\n")
                .addJavadoc("one that declares no scope, leaves the row owner-private.\n")
                .addJavadoc("\n")
                .addJavadoc("@return the acting shared scope, or {@code null} when none is bound\n");
        if (!uuid) {
            return resolver
                    .addStatement("return $T.storageContextOrSystem().sharedScopeKey()$W"
                                    + ".filter(key -> !key.isBlank()).orElse(null)",
                            KERNEL_PROVIDERS)
                    .build();
        }
        return resolver
                .addJavadoc("@throws IllegalStateException if the bound shared-scope key is not a UUID —\n")
                .addJavadoc("        a deployment fault\n")
                .addStatement("$T<$T> sharedScopeKey = $T.storageContextOrSystem().sharedScopeKey()$W"
                                + ".filter(key -> !key.isBlank())",
                        OPTIONAL, String.class, KERNEL_PROVIDERS)
                .beginControlFlow("if (sharedScopeKey.isEmpty())")
                .addStatement("return null")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("return $T.fromString(sharedScopeKey.get())", UUID_TYPE)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("throw new $T($L, e)", ILLEGAL_STATE_EXCEPTION, SHARED_SCOPE_NOT_A_UUID_FIELD)
                .endControlFlow()
                .build();
    }

    /** A {@code private static final String} in the emitted class, so a long diagnostic message
     *  does not push the statement that throws it off the readable width. */
    private static FieldSpec messageField(String name, String message) {
        return FieldSpec.builder(String.class, name,
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", message)
                .build();
    }

    /**
     * Emits the acting-tenant resolver: the owner stamped on a row whose caller left the tenant
     * unset.
     *
     * <p>Reads {@code KernelProviders.storageContext()} — the SPI accessor documented for
     * request-scoped code, which throws rather than falling back to the system scope. The
     * fallback accessor ({@code storageContextOrSystem}) would be the wrong one here by its own
     * Javadoc: it silently disables tenant isolation, and a row stamped from it would be refused
     * one layer down with no trace of why.
     *
     * <p>Both failures raised here are {@code IllegalStateException}, never
     * {@code IllegalArgumentException}. An unbound context and a non-UUID isolation key are
     * deployment faults, and the emitted handler maps a {@code RuntimeException} out of the service
     * call to 500 — while {@code UUID.fromString}'s own {@code IllegalArgumentException}, escaping
     * unwrapped, is the shape that ADR-036 §2 maps to a caller's 400. Wrapping it is what keeps a
     * misconfigured deployment from being reported as a malformed request (T43, same lesson).
     */
    private static MethodSpec buildActingTenantId(Context ctx) {
        Column tenant = systemColumn(ctx, ColumnKind.TENANT_ID);
        return MethodSpec.methodBuilder(ACTING_TENANT_METHOD)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(UUID_TYPE)
                .addJavadoc("The acting tenant — the owner stamped on a written row when the\n")
                .addJavadoc("caller left {@code $L} unset.\n", tenant.javaName())
                .addJavadoc("\n")
                .addJavadoc("<p>This is the bound {@code StorageContext}'s isolation key, which is\n")
                .addJavadoc("also what the kernel publishes as {@code exeris.tenant_id} and what\n")
                .addJavadoc("this table's row-level-security policy compares against — so a row\n")
                .addJavadoc("stamped here is a row that policy accepts.\n")
                .addJavadoc("\n")
                .addJavadoc("@return the acting tenant's id, never {@code null}\n")
                .addJavadoc("@throws IllegalStateException if the bound context carries no isolation\n")
                .addJavadoc("        key, or carries one that is not a UUID — both deployment faults\n")
                .addStatement("$T isolationKey = $T.storageContext().isolationKey()$W"
                                + ".orElseThrow(() -> new $T($L))",
                        String.class, KERNEL_PROVIDERS, ILLEGAL_STATE_EXCEPTION, SYSTEM_SCOPE_FIELD)
                .beginControlFlow("try")
                .addStatement("return $T.fromString(isolationKey)", UUID_TYPE)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("throw new $T($L, e)", ILLEGAL_STATE_EXCEPTION, NOT_A_UUID_FIELD)
                .endControlFlow()
                .build();
    }

    private MethodSpec buildSave(Context ctx) {
        String columnsJoined = String.join(", ", ctx.columns().stream().map(Column::sqlName).toList());
        String placeholders = String.join(", ",
                ctx.columns().stream().map(c -> placeholder(c, ctx.metadata())).toList());
        String sql = "INSERT INTO " + ctx.table() + " (" + columnsJoined + ") VALUES (" + placeholders + ")";

        MethodSpec.Builder save = MethodSpec.methodBuilder("save")
                .addModifiers(Modifier.PUBLIC)
                .returns(ctx.entityType())
                .addParameter(ctx.entityType(), "entity")
                .addJavadoc("Inserts {@code entity}. <b>Mutates the input:</b> a missing\n")
                .addJavadoc("{@code id} is filled with a random UUID");
        boolean tenantPartitioned = isTenantPartitioned(ctx.metadata());
        Optional<Column> sharedScope = sharedScopeColumn(ctx);
        if (ctx.metadata().audited()) {
            // "and" belongs to whichever clause ends the list, so it moves when a tenant
            // clause follows this one.
            save.addJavadoc(tenantPartitioned ? ", {@code createdAt} /\n" : ", and {@code createdAt} /\n")
                .addJavadoc("{@code updatedAt} are stamped with {@code Instant.now()}");
        }
        if (tenantPartitioned) {
            save.addJavadoc(sharedScope.isPresent() ? ", a missing\n" : ", and a missing\n")
                .addJavadoc("{@code $L} is filled with the acting tenant",
                        systemColumn(ctx, ColumnKind.TENANT_ID).javaName());
        }
        sharedScope.ifPresent(column -> save.addJavadoc(", and a missing\n")
                .addJavadoc("{@code $L} with the acting shared scope, if one is bound",
                        column.javaName()));
        save.addJavadoc(" before the INSERT.\n");
        if (tenantPartitioned) {
            save.addJavadoc("<p>While a tenant is bound, a row naming another tenant is refused with\n")
                    .addJavadoc("{@link $T}", KernelErrorGenerator.tenantMismatchType(ctx.metadata()));
            sharedScope.ifPresent(column -> save
                    .addJavadoc(", and while a shared scope is bound, a row tagged with another is\n")
                    .addJavadoc("refused with {@link $T}",
                            KernelErrorGenerator.sharedScopeMismatchType(ctx.metadata())));
            save.addJavadoc(".\n");
        }
        save.addStatement("if (entity.getId() == null) entity.setId($T.randomUUID())", UUID_TYPE);
        if (ctx.metadata().audited()) {
            save.addStatement("$T now = $T.now()", INSTANT, INSTANT);
            save.addStatement("entity.$L(now)", setterFor(systemColumn(ctx, ColumnKind.CREATED_AT)));
            save.addStatement("entity.$L(now)", setterFor(systemColumn(ctx, ColumnKind.UPDATED_AT)));
        }
        appendTenantStamp(save, ctx);
        appendSharedScopeStamp(save, ctx);
        save.addStatement(SQL_VAR_STMT, sql);

        CodeBlock.Builder body = CodeBlock.builder()
                .beginControlFlow(EXECUTE_MANAGED_LAMBDA)
                .beginControlFlow(TRY_PREPARE_STMT, PERSISTENCE_STATEMENT);
        emitInsertBinds(body, ctx);
        body.addStatement("stmt.executeUpdate()");
        body.endControlFlow();
        body.endControlFlow(")");

        save.addCode(body.build());
        save.addStatement("LOG.log($T.INFO, $S, entity.getId())", KernelScaffold.LOGGER_LEVEL,
                "Created " + ctx.entity() + ": {0}");
        save.addStatement(RETURN_ENTITY_STMT);
        return save.build();
    }

    /**
     * The columns an {@code UPDATE} writes, in bind order: every layout column except {@code id},
     * which closes the WHERE clause, and except the owning tenant.
     *
     * <p>The owner is not written on update, so no update can move a row to another tenant — not
     * with a tenant bound (where a foreign one is refused before this statement anyway), and not
     * without one, where nothing else would stop it on an engine or role that row-level security
     * does not bind. Dropping the column rather than refusing a change is deliberate: detecting a
     * change needs the stored owner, which the repository only learns through a predicate that
     * turns "you tried to move a row" into zero rows — a 404/409 that misreports a caller fault as a
     * missing row. On a UNIVERSE table it also closes a hole in the policy the kernel documents:
     * with {@code SET owner = self}, a partition-mate could re-own a shared row it can only read.
     *
     * <p>Shared with the repository-test emitter, whose WHERE-id index is one past this list.
     */
    static List<Column> updateColumns(DomainMetadata metadata) {
        return updateColumns(columnLayout(metadata));
    }

    private static List<Column> updateColumns(List<Column> layout) {
        return layout.stream()
                .filter(c -> !"id".equals(c.sqlName()))
                .filter(c -> c.kind() != ColumnKind.TENANT_ID)
                .toList();
    }

    private MethodSpec buildUpdate(Context ctx) {
        // SET clause: every column except id (id is in WHERE) and the owner
        List<Column> updatable = updateColumns(ctx.columns());
        // An entity with nothing to write — no domain field, no audit or version column, and the
        // owner never written — still needs a valid statement whose row count answers
        // "did the row exist": SET id = id writes nothing and binds nothing.
        String setClause = updatable.isEmpty()
                ? "id = id"
                : String.join(", ", updatable.stream()
                        .map(c -> c.sqlName() + " = " + placeholder(c, ctx.metadata())).toList());
        boolean versioned = ctx.metadata().versioned();
        String whereClause = versioned
                ? WHERE_ID_CLAUSE + " AND " + toSnakeCase(ctx.sys().version()) + " = ?" : WHERE_ID_CLAUSE;
        String sql = "UPDATE " + ctx.table() + " SET " + setClause + whereClause;
        // ADR-076: the rejection carries a type, and the message moved into it. A versioned
        // update matches on id AND on the expected version in one statement, so a zero row
        // count means "gone or stale" and cannot be split without a second query — hence a
        // distinct type the handler maps to 409, rather than a 404 that would be a lie about
        // one of the two.
        ClassName rejection = versioned
                ? KernelErrorGenerator.versionConflictType(ctx.metadata())
                : KernelErrorGenerator.notFoundType(ctx.metadata());

        MethodSpec.Builder update = MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.PUBLIC)
                .returns(ctx.entityType())
                .addParameter(UUID_TYPE, "id")
                .addParameter(ctx.entityType(), "entity");
        if (ctx.metadata().audited()) {
            update.addStatement("entity.$L($T.now())",
                    setterFor(systemColumn(ctx, ColumnKind.UPDATED_AT)), INSTANT);
        }
        appendTenantStamp(update, ctx);
        appendSharedScopeStamp(update, ctx);
        if (versioned) {
            update.addJavadoc("Optimistic-lock update. The caller-supplied {@code entity.version}\n");
            update.addJavadoc("is the <i>expected</i> row version; this method increments it before\n");
            update.addJavadoc("writing and rejects the update if no row matches the expected\n");
            update.addJavadoc("version (stale read).\n");
            Column versionColumn = systemColumn(ctx, ColumnKind.VERSION);
            // Read into a boxed local first. The entity may declare `version` as `long` or as
            // `Long`; assigning straight into a `long` NPEs on a null wrapper, and a null guard is
            // not expressible on a primitive. Boxing accepts both, and treating a null as 0 makes
            // a wrapper-typed field behave exactly like the primitive it shadows.
            update.addStatement("$T currentVersion = entity.$L()", BOXED_LONG, getterFor(versionColumn));
            update.addStatement("long expectedVersion = currentVersion == null ? 0L : currentVersion");
            update.addStatement("entity.$L(expectedVersion + 1L)", setterFor(versionColumn));
        }
        if (isTenantPartitioned(ctx.metadata())) {
            if (!versioned) {
                // The versioned branch above already opened the doc comment with a summary
                // sentence; without it, this paragraph would be a <p> with nothing before it.
                update.addJavadoc("Updates the row identified by {@code id}.\n");
            }
            String tenantField = systemColumn(ctx, ColumnKind.TENANT_ID).javaName();
            update.addJavadoc("<p>The owner is never written: {@code $L} is not in the SET list, so\n",
                            tenantField)
                    .addJavadoc("an update cannot move a row to another tenant. A missing\n")
                    .addJavadoc("{@code $L} is filled with the acting tenant, so the returned entity\n",
                            tenantField)
                    .addJavadoc("names its owner, and while a tenant is bound a different one is\n")
                    .addJavadoc("refused with {@link $T}.\n",
                            KernelErrorGenerator.tenantMismatchType(ctx.metadata()));
        }
        sharedScopeColumn(ctx).ifPresent(column -> update
                .addJavadoc("<p>A missing {@code $L} is filled with the acting shared scope in the same\n",
                        column.javaName())
                .addJavadoc("way. The SET list writes that column too, so when no scope is bound a body\n")
                .addJavadoc("without it leaves the row owner-private.\n"));
        update.addStatement(SQL_VAR_STMT, sql);
        update.addStatement("long[] rowsAffected = {0L}");

        CodeBlock.Builder body = CodeBlock.builder()
                .beginControlFlow(EXECUTE_MANAGED_LAMBDA)
                .beginControlFlow(TRY_PREPARE_STMT, PERSISTENCE_STATEMENT);
        emitUpdateBinds(body, updatable, versioned, ctx.metadata());
        body.addStatement("rowsAffected[0] = stmt.executeUpdate()");
        body.endControlFlow();
        body.endControlFlow(")");

        update.addCode(body.build());
        update.beginControlFlow("if (rowsAffected[0] == 0L)")
                .addStatement("throw new $T(id)", rejection)
                .endControlFlow();
        update.addStatement("entity.setId(id)");
        update.addStatement("LOG.log($T.INFO, $S, id)", KernelScaffold.LOGGER_LEVEL,
                "Updated " + ctx.entity() + ": {0}");
        update.addStatement(RETURN_ENTITY_STMT);
        return update.build();
    }

    private MethodSpec buildDeleteById(Context ctx) {
        // Soft delete excludes already-tombstoned rows so a double-delete
        // raises "not found" — consistent with the findById/findAll filter
        // and with the hard-delete branch's behaviour.
        String deletedCol = toSnakeCase(ctx.sys().deleted());
        String sql = ctx.metadata().softDelete()
                ? "UPDATE " + ctx.table() + " SET " + deletedCol + " = true" + WHERE_ID_CLAUSE
                        + " AND " + deletedCol + " = false"
                : "DELETE FROM " + ctx.table() + WHERE_ID_CLAUSE;

        CodeBlock.Builder body = CodeBlock.builder()
                .addStatement(SQL_VAR_STMT, sql)
                .beginControlFlow(EXECUTE_MANAGED_LAMBDA)
                .beginControlFlow(TRY_PREPARE_STMT, PERSISTENCE_STATEMENT)
                .addStatement("rowsAffected[0] = stmt.bindUuid(0, id).executeUpdate()")
                .endControlFlow()
                .endControlFlow(")");

        return MethodSpec.methodBuilder("deleteById")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(UUID_TYPE, "id")
                .addStatement("long[] rowsAffected = {0L}")
                .addCode(body.build())
                .beginControlFlow("if (rowsAffected[0] == 0L)")
                .addStatement("throw new $T(id)", KernelErrorGenerator.notFoundType(ctx.metadata()))
                .endControlFlow()
                .addStatement("LOG.log($T.INFO, $S, id)", KernelScaffold.LOGGER_LEVEL,
                        "Deleted " + ctx.entity() + ": {0}")
                .build();
    }

    private MethodSpec buildCount(Context ctx) {
        String filter = ctx.metadata().softDelete()
                ? " WHERE " + toSnakeCase(ctx.sys().deleted()) + " = false" : "";
        String sql = "SELECT COUNT(*) FROM " + ctx.table() + filter;

        return MethodSpec.methodBuilder("count")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.LONG)
                .addStatement(SQL_VAR_STMT, sql)
                .addStatement("""
                        return executor.query(conn -> {
                            try ($T stmt = conn.prepare(sql);
                                 $T qr = stmt.executeQuery()) {
                                return qr.next() ? qr.row().getLong(0) : 0L;
                            }
                        })""",
                        PERSISTENCE_STATEMENT, QUERY_RESULT)
                .build();
    }

    private MethodSpec buildMapRow(Context ctx) {
        MethodSpec.Builder map = MethodSpec.methodBuilder("mapRow")
                .addModifiers(Modifier.PRIVATE)
                .returns(ctx.entityType())
                .addParameter(ROW_CURSOR, "row")
                .addStatement("$T entity = new $T()", ctx.entityType(), ctx.entityType());

        int idx = 0;
        for (Column col : ctx.columns()) {
            emitReadCol(map, col, idx++, ctx);
        }
        return map.addStatement("return entity").build();
    }

    private void emitReadCol(MethodSpec.Builder map, Column col, int idx, Context ctx) {
        String setter = "entity." + setterFor(col);
        switch (col.kind()) {
            case TENANT_ID -> map.addStatement("$L(row.getUuid($L))", setter, idx);
            // T19: read the timestamp natively. The kernel 0.10 SPI added
            // RowCursor.getInstant (returning null for a SQL NULL column), so the
            // TIMESTAMPTZ column round-trips as an Instant through the driver — no
            // brittle getString + Instant.parse, which broke on Postgres's non-ISO
            // text rendering of timestamptz.
            case CREATED_AT, UPDATED_AT -> map.addStatement("$L(row.getInstant($L))", setter, idx);
            case DELETED -> map.addStatement("$L(row.getBoolean($L))", setter, idx);
            case VERSION -> map.addStatement("$L(row.getLong($L))", setter, idx);
            case DOMAIN -> emitReadDomain(map, col, idx, ctx);
        }
    }

    private void emitReadDomain(MethodSpec.Builder map, Column col, int idx, Context ctx) {
        String setter = "entity." + setterFor(col);
        String type = col.javaType();
        boolean primitive = DomainTypeKind.isPrimitive(type);
        switch (kindOf(col, ctx.metadata())) {
            case LIST -> emitReadList(map, type, setter, idx);
            case UUID -> map.addStatement("$L(row.getUuid($L))", setter, idx);
            case STRING -> map.addStatement("$L(row.getString($L))", setter, idx);
            case LONG -> emitReadScalar(map, primitive, setter, idx, CodeBlock.of("row.getLong($L)", idx));
            case INT -> emitReadScalar(map, primitive, setter, idx, CodeBlock.of("row.getInt($L)", idx));
            case SHORT -> emitReadScalar(map, primitive, setter, idx, CodeBlock.of("row.getShort($L)", idx));
            case BYTE -> emitReadScalar(map, primitive, setter, idx, CodeBlock.of("(byte) row.getShort($L)", idx));
            case BOOL -> emitReadScalar(map, primitive, setter, idx, CodeBlock.of("row.getBoolean($L)", idx));
            case FLOAT -> emitReadScalar(map, primitive, setter, idx, CodeBlock.of("row.getFloat($L)", idx));
            case DOUBLE -> emitReadScalar(map, primitive, setter, idx, CodeBlock.of("row.getDouble($L)", idx));
            case BIG_DECIMAL -> map.addCode(CodeBlock.of(
                    // No bindBigDecimal in SPI — round-trip via String.
                    "{ String v = row.getString($L); if (v != null) $L(new $T(v)); }\n",
                    idx, setter, BIG_DECIMAL));
            // T19: native getInstant (kernel 0.10 SPI) — TIMESTAMPTZ ↔ Instant via
            // the driver, replacing the getString + Instant.parse round-trip that
            // failed on Postgres's non-ISO timestamptz text rendering.
            case INSTANT_LIKE -> map.addStatement("$L(row.getInstant($L))", setter, idx);
            // T19b: LocalDateTime has no typed SPI accessor; the column is TIMESTAMPTZ,
            // so bridge through the native getInstant at the UTC offset (null-guarded —
            // getInstant returns null for a SQL NULL column).
            case LOCAL_DATE_TIME -> map.addCode(CodeBlock.of(
                    "{ $T v = row.getInstant($L); if (v != null) $L($T.ofInstant(v, $T.UTC)); }\n",
                    INSTANT, idx, setter, LOCAL_DATE_TIME, ZONE_OFFSET));
            case LOCAL_DATE -> map.addCode(CodeBlock.of(
                    "{ String v = row.getString($L); if (v != null) $L($T.parse(v)); }\n",
                    idx, setter, LOCAL_DATE));
            // The column holds the instant; the offset or zone the value was written with is not
            // stored, so it is read back at UTC (null-guarded, as for LocalDateTime).
            case OFFSET_DATE_TIME -> map.addCode(CodeBlock.of(
                    "{ $T v = row.getInstant($L); if (v != null) $L($T.ofInstant(v, $T.UTC)); }\n",
                    INSTANT, idx, setter, OFFSET_DATE_TIME, ZONE_OFFSET));
            case ZONED_DATE_TIME -> map.addCode(CodeBlock.of(
                    "{ $T v = row.getInstant($L); if (v != null) $L($T.ofInstant(v, $T.UTC)); }\n",
                    INSTANT, idx, setter, ZONED_DATE_TIME, ZONE_OFFSET));
            case UNSTORABLE -> throw unsupported(type);
            // An enum, and a type nothing recognises, are both read back through the type's
            // valueOf(String).
            case ENUM, OPAQUE -> emitReadEnumLike(map, type, setter, idx, ctx);
        }
    }

    /**
     * A typed scalar read. A primitive field takes the accessor's value as it is; a wrapper field is
     * left {@code null} for a SQL {@code NULL}, which the primitive accessors cannot report — they
     * answer a default or throw.
     */
    private static void emitReadScalar(MethodSpec.Builder map, boolean primitive, String setter, int idx,
                                       CodeBlock read) {
        if (primitive) {
            map.addStatement("$L($L)", setter, read);
        } else {
            map.addStatement("if (!row.isNull($L)) $L($L)", idx, setter, read);
        }
    }

    /**
     * What an emit switch throws on an {@code UNSTORABLE} column. {@link #generate} refuses such an
     * entity before emitting anything, so no switch reaches it; one that did would otherwise emit a
     * column with no encoding.
     */
    private static IllegalStateException unsupported(String type) {
        return new IllegalStateException("no column encoding for " + type
                + "; generate() refuses it through requirePersistableFields");
    }

    private void emitReadList(MethodSpec.Builder map, String type, String setter, int idx) {
        ClassName elementType = ClassName.bestGuess(listElementType(type));
        map.addCode(CodeBlock.of(
                "{ String v = row.getString($L); if (v != null) $L(parseList(v, new $T<$T<$T>>() {})); }\n",
                idx, setter, TYPE_REFERENCE, LIST_TYPE, elementType));
    }

    private void emitReadEnumLike(MethodSpec.Builder map, String type, String setter, int idx, Context ctx) {
        // Use $T (not $L) so JavaPoet emits the import; fall back to the
        // entity's domain package when the field type is given unqualified.
        ClassName enumClass = type.contains(".")
                ? ClassName.bestGuess(type)
                : ClassName.get(ctx.metadata().packageName(), type);
        map.addCode(CodeBlock.of("{ String v = row.getString($L); if (v != null) $L($T.valueOf(v)); }\n",
                idx, setter, enumClass));
    }

    private void emitInsertBinds(CodeBlock.Builder body, Context ctx) {
        int idx = 0;
        for (Column col : ctx.columns()) {
            emitBindCol(body, col, idx++, ENTITY_SRC, ctx.metadata());
        }
    }

    private void emitUpdateBinds(CodeBlock.Builder body, List<Column> updatable, boolean versioned,
                                 DomainMetadata metadata) {
        int idx = 0;
        for (Column col : updatable) {
            emitBindCol(body, col, idx++, ENTITY_SRC, metadata);
        }
        // id bind terminates WHERE clause; for versioned entities, the
        // expectedVersion bind enforces the optimistic-lock guard.
        body.addStatement("stmt.bindUuid($L, id)", idx++);
        if (versioned) {
            body.addStatement("stmt.bindLong($L, expectedVersion)", idx);
        }
    }

    private void emitBindCol(CodeBlock.Builder body, Column col, int idx, String src, DomainMetadata metadata) {
        // The is/get split (DELETED, and a primitive boolean domain field) lives in getterFor and
        // nowhere else — the generated repository test asserts through the same helper, so a
        // divergence here would silently make its assertions name an accessor nothing binds.
        String accessor = getterFor(col);
        switch (col.kind()) {
            case TENANT_ID -> body.addStatement("stmt.bindUuid($L, $L.$L())", idx, src, accessor);
            // T19: bind the timestamp natively (kernel 0.10 SPI bindInstant), so the
            // TIMESTAMPTZ column round-trips via the driver instead of an ISO-8601
            // String. Null-guarded via bindNull because update() is also bound to
            // caller-supplied entities where createdAt may legitimately be null
            // (e.g. a partial update DTO).
            case CREATED_AT, UPDATED_AT -> body.add(
                    "if ($L.$L() == null) stmt.bindNull($L); else stmt.bindInstant($L, $L.$L());\n",
                    src, accessor, idx, idx, src, accessor);
            case DELETED -> body.addStatement("stmt.bindBoolean($L, $L.$L())", idx, src, accessor);
            // Same boxing as update()'s expected-version read, for the same reason — a
            // `Long version` on a freshly constructed entity is null, and bindLong takes a
            // primitive, so unboxing it would throw before the row is written.
            case VERSION -> {
                String local = col.javaName() + "Value";
                body.addStatement("$T $L = $L.$L()", BOXED_LONG, local, src, accessor);
                body.addStatement("stmt.bindLong($L, $L == null ? 0L : $L)", idx, local, local);
            }
            case DOMAIN -> emitBindDomain(body, col, idx, src, metadata);
        }
    }

    private void emitBindDomain(CodeBlock.Builder body, Column col, int idx, String src, DomainMetadata metadata) {
        // T15 lives in getterFor now: a primitive `boolean` field's JavaBean accessor is `isX()`,
        // not `getX()` (matching the system DELETED column), while `Boolean` wrappers keep `getX()`
        // per the Lombok/JavaBean convention.
        String getter = src + "." + getterFor(col) + "()";
        body.add("$L\n", bindValue(kindOf(col, metadata), col.javaType(), String.valueOf(idx), getter));
    }

    private MethodSpec buildParseList() {
        return MethodSpec.methodBuilder("parseList")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addTypeVariable(TypeVariableName.get("T"))
                .returns(ParameterizedTypeName.get(LIST_TYPE,
                        TypeVariableName.get("T")))
                .addParameter(String.class, "json")
                .addParameter(ParameterizedTypeName.get(TYPE_REFERENCE,
                        ParameterizedTypeName.get(LIST_TYPE,
                                TypeVariableName.get("T"))), "typeRef")
                .addStatement("if (json == null || json.isEmpty()) return $T.of()", LIST_TYPE)
                .beginControlFlow("try")
                .addStatement("return MAPPER.readValue(json, typeRef)")
                .nextControlFlow("catch ($T e)", JACKSON_EXCEPTION)
                .addStatement("throw new $T($S, e)",
                        RuntimeException.class, "Failed to parse list JSON")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildToJson() {
        return MethodSpec.methodBuilder("toJson")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(Object.class, "value")
                .addStatement("if (value == null) return null")
                .beginControlFlow("try")
                .addStatement("return MAPPER.writeValueAsString(value)")
                .nextControlFlow("catch ($T e)", JACKSON_EXCEPTION)
                .addStatement("throw new $T($S, e)",
                        RuntimeException.class, "Failed to serialize to JSON")
                .endControlFlow()
                .build();
    }

    private static String toSnakeCase(String camelCase) {
        return ColumnNaming.snakeCase(camelCase);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.REPOSITORY;
    }
}
