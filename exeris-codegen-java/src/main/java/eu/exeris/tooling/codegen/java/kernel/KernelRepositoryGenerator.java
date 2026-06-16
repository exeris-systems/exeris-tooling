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
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 *       ({@code tools.jackson.databind.ObjectMapper}); {@code BigDecimal}
 *       is bound as String (no {@code bindBigDecimal} in SPI).</li>
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
    private static final ClassName INSTANT = ClassName.get("java.time", "Instant");
    private static final ClassName LOCAL_DATE = ClassName.get("java.time", "LocalDate");
    private static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");

    private static final String SPI_PERSISTENCE_PKG = "eu.exeris.kernel.spi.persistence";
    private static final ClassName TRANSACTIONAL_EXECUTOR =
            ClassName.get(SPI_PERSISTENCE_PKG, "TransactionalExecutor");
    private static final ClassName PERSISTENCE_STATEMENT =
            ClassName.get(SPI_PERSISTENCE_PKG, "PersistenceStatement");
    private static final ClassName QUERY_RESULT =
            ClassName.get(SPI_PERSISTENCE_PKG, "QueryResult");
    private static final ClassName ROW_CURSOR =
            ClassName.get(SPI_PERSISTENCE_PKG, "RowCursor");

    private static final ClassName OBJECT_MAPPER =
            ClassName.get("tools.jackson.databind", "ObjectMapper");
    private static final ClassName JACKSON_EXCEPTION =
            ClassName.get("tools.jackson.core", "JacksonException");
    private static final ClassName TYPE_REFERENCE =
            ClassName.get("tools.jackson.core.type", "TypeReference");

    // Format-string / code-fragment literals — consolidated so SonarQube
    // S1192 stays quiet and so the SQL shape can evolve in one place.
    private static final String LIST_PREFIX = "List<";
    private static final String ENTITY_SRC = "entity";
    private static final String WHERE_ID_CLAUSE = " WHERE id = ?";
    private static final String SQL_VAR_STMT = "String sql = $S";
    private static final String EXECUTE_MANAGED_LAMBDA = "executor.executeManaged(conn -> ";
    private static final String TRY_PREPARE_STMT = "try ($T stmt = conn.prepare(sql))";
    private static final String RETURN_ENTITY_STMT = "return entity";
    private static final String BIND_STRING_NULL_GUARDED =
            "stmt.bindString($L, $L == null ? null : $L.toString())";

    // Type-name variants accepted in FieldMetadata.type() — consolidated
    // here so the emit-side switch is a single dispatch on DomainTypeKind
    // instead of a chain of equality probes.
    private static final Set<String> UUID_TYPES = Set.of("UUID", "java.util.UUID");
    private static final Set<String> STRING_TYPES = Set.of("String", "java.lang.String");
    private static final Set<String> LONG_TYPES = Set.of("Long", "long", "java.lang.Long");
    private static final Set<String> INT_TYPES = Set.of("Integer", "int", "java.lang.Integer");
    private static final Set<String> BOOL_TYPES = Set.of("Boolean", "boolean", "java.lang.Boolean");
    private static final Set<String> DOUBLE_TYPES = Set.of("Double", "double", "java.lang.Double");
    private static final Set<String> BIG_DECIMAL_TYPES = Set.of("BigDecimal", "java.math.BigDecimal");

    private enum DomainTypeKind {
        LIST, UUID, STRING, LONG, INT, BOOL, DOUBLE, BIG_DECIMAL, INSTANT_LIKE, LOCAL_DATE, ENUM_LIKE
    }

    private static DomainTypeKind classifyDomainType(String type) {
        if (type.startsWith(LIST_PREFIX)) return DomainTypeKind.LIST;
        if (UUID_TYPES.contains(type)) return DomainTypeKind.UUID;
        if (STRING_TYPES.contains(type)) return DomainTypeKind.STRING;
        if (LONG_TYPES.contains(type)) return DomainTypeKind.LONG;
        if (INT_TYPES.contains(type)) return DomainTypeKind.INT;
        if (BOOL_TYPES.contains(type)) return DomainTypeKind.BOOL;
        if (DOUBLE_TYPES.contains(type)) return DomainTypeKind.DOUBLE;
        if (BIG_DECIMAL_TYPES.contains(type)) return DomainTypeKind.BIG_DECIMAL;
        if (type.contains("Instant") || type.contains("LocalDateTime")) return DomainTypeKind.INSTANT_LIKE;
        if (type.contains("LocalDate")) return DomainTypeKind.LOCAL_DATE;
        return DomainTypeKind.ENUM_LIKE;
    }

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".repository";
        String entity = metadata.entityName();
        String className = entity + "Repository";
        String table = KernelTableNaming.effectiveTable(metadata);
        List<FieldMetadata> fields = metadata.fields();
        boolean hasListField = fields.stream().anyMatch(f -> f.type().startsWith(LIST_PREFIX));

        ClassName entityType = ClassName.get(metadata.packageName(), entity);
        ClassName selfType = ClassName.get(packageName, className);
        TypeName optionalOfEntity = ParameterizedTypeName.get(OPTIONAL, entityType);
        TypeName listOfEntity = ParameterizedTypeName.get(LIST_TYPE, entityType);

        // Effective system-field java names (T5 overrides; defaults when no
        // @ExerisDomain override was written). For the default case these are
        // byte-identical to the previously hardcoded literals.
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
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build())
                .addField(FieldSpec.builder(String.class, "TABLE",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", table)
                        .build());

        if (hasListField) {
            repo.addField(FieldSpec.builder(OBJECT_MAPPER, "MAPPER",
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("new $T()", OBJECT_MAPPER)
                    .build());
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
                .addMethod(buildSave(ctx))
                .addMethod(buildUpdate(ctx))
                .addMethod(buildDeleteById(ctx))
                .addMethod(buildCount(ctx))
                .addMethod(buildMapRow(ctx));

        if (hasListField) {
            repo.addMethod(buildParseList())
                .addMethod(buildToJson());
        }

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, repo.build()), ArtifactType.REPOSITORY);
    }

    /** Column descriptor — accessed by both SELECT clause builder and mapRow. */
    private record Column(String sqlName, String javaName, String javaType, ColumnKind kind) {}

    private enum ColumnKind { DOMAIN, TENANT_ID, CREATED_AT, UPDATED_AT, DELETED, VERSION }

    /** Carrier for repeated build-time state — keeps signatures readable. */
    private record Context(String entity, ClassName entityType, List<FieldMetadata> fields,
                           List<Column> columns, DomainMetadata metadata, String table,
                           SystemFieldNames sys) {}

    /**
     * Effective java field names for the system fields the repository emits
     * (T5). Resolved from {@link DomainMetadata#systemFields()} when present,
     * else the canonical defaults. Defaults are deliberately the same literals
     * the generator hardcoded before T5, so default-case output is unchanged.
     */
    private record SystemFieldNames(String tenantId, String createdAt, String updatedAt,
                                    String deleted, String version) {}

    private SystemFieldNames resolveSystemFieldNames(DomainMetadata metadata) {
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

    private List<Column> buildColumnLayout(List<FieldMetadata> fields, DomainMetadata metadata,
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
        if (metadata.tenantScoped()) {
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
        if (metadata.tenantScoped()) {
            cols.add(new Column(toSnakeCase(sys.tenantId()), sys.tenantId(), "UUID", ColumnKind.TENANT_ID));
        }
        if (metadata.audited()) {
            cols.add(new Column(toSnakeCase(sys.createdAt()), sys.createdAt(), "Instant", ColumnKind.CREATED_AT));
            cols.add(new Column(toSnakeCase(sys.updatedAt()), sys.updatedAt(), "Instant", ColumnKind.UPDATED_AT));
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

    private MethodSpec buildSave(Context ctx) {
        String columnsJoined = String.join(", ", ctx.columns().stream().map(Column::sqlName).toList());
        String placeholders = String.join(", ", ctx.columns().stream().map(c -> "?").toList());
        String sql = "INSERT INTO " + ctx.table() + " (" + columnsJoined + ") VALUES (" + placeholders + ")";

        MethodSpec.Builder save = MethodSpec.methodBuilder("save")
                .addModifiers(Modifier.PUBLIC)
                .returns(ctx.entityType())
                .addParameter(ctx.entityType(), "entity")
                .addJavadoc("Inserts {@code entity}. <b>Mutates the input:</b> a missing\n")
                .addJavadoc("{@code id} is filled with a random UUID");
        if (ctx.metadata().audited()) {
            save.addJavadoc(", and {@code createdAt} /\n")
                .addJavadoc("{@code updatedAt} are stamped with {@code Instant.now()}");
        }
        save.addJavadoc(" before the INSERT.\n")
                .addStatement("if (entity.getId() == null) entity.setId($T.randomUUID())", UUID_TYPE);
        if (ctx.metadata().audited()) {
            save.addStatement("$T now = $T.now()", INSTANT, INSTANT);
            save.addStatement("entity.set$L(now)", capitalize(ctx.sys().createdAt()));
            save.addStatement("entity.set$L(now)", capitalize(ctx.sys().updatedAt()));
        }
        save.addStatement(SQL_VAR_STMT, sql);

        CodeBlock.Builder body = CodeBlock.builder()
                .beginControlFlow(EXECUTE_MANAGED_LAMBDA)
                .beginControlFlow(TRY_PREPARE_STMT, PERSISTENCE_STATEMENT);
        emitInsertBinds(body, ctx);
        body.addStatement("stmt.executeUpdate()");
        body.endControlFlow();
        body.endControlFlow(")");

        save.addCode(body.build());
        save.addStatement("LOG.info($S, entity.getId())", "Created " + ctx.entity() + ": {}");
        save.addStatement(RETURN_ENTITY_STMT);
        return save.build();
    }

    private MethodSpec buildUpdate(Context ctx) {
        // SET clause: every column except id (id is in WHERE)
        List<Column> updatable = ctx.columns().stream()
                .filter(c -> !"id".equals(c.sqlName()))
                .toList();
        String setClause = String.join(", ", updatable.stream().map(c -> c.sqlName() + " = ?").toList());
        boolean versioned = ctx.metadata().versioned();
        String whereClause = versioned
                ? WHERE_ID_CLAUSE + " AND " + toSnakeCase(ctx.sys().version()) + " = ?" : WHERE_ID_CLAUSE;
        String sql = "UPDATE " + ctx.table() + " SET " + setClause + whereClause;
        String notFoundMessage = versioned
                ? ctx.entity() + " not found or stale version: "
                : ctx.entity() + " not found: ";

        MethodSpec.Builder update = MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.PUBLIC)
                .returns(ctx.entityType())
                .addParameter(UUID_TYPE, "id")
                .addParameter(ctx.entityType(), "entity");
        if (ctx.metadata().audited()) {
            update.addStatement("entity.set$L($T.now())", capitalize(ctx.sys().updatedAt()), INSTANT);
        }
        if (versioned) {
            update.addJavadoc("Optimistic-lock update. The caller-supplied {@code entity.version}\n");
            update.addJavadoc("is the <i>expected</i> row version; this method increments it before\n");
            update.addJavadoc("writing and rejects the update if no row matches the expected\n");
            update.addJavadoc("version (stale read).\n");
            update.addStatement("long expectedVersion = entity.get$L()", capitalize(ctx.sys().version()));
            update.addStatement("entity.set$L(expectedVersion + 1L)", capitalize(ctx.sys().version()));
        }
        update.addStatement(SQL_VAR_STMT, sql);
        update.addStatement("long[] rowsAffected = {0L}");

        CodeBlock.Builder body = CodeBlock.builder()
                .beginControlFlow(EXECUTE_MANAGED_LAMBDA)
                .beginControlFlow(TRY_PREPARE_STMT, PERSISTENCE_STATEMENT);
        emitUpdateBinds(body, updatable, versioned);
        body.addStatement("rowsAffected[0] = stmt.executeUpdate()");
        body.endControlFlow();
        body.endControlFlow(")");

        update.addCode(body.build());
        update.beginControlFlow("if (rowsAffected[0] == 0L)")
                .addStatement("throw new $T($S + id)",
                        RuntimeException.class, notFoundMessage)
                .endControlFlow();
        update.addStatement("entity.setId(id)");
        update.addStatement("LOG.info($S, id)", "Updated " + ctx.entity() + ": {}");
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
                .addStatement("throw new $T($S + id)",
                        RuntimeException.class, ctx.entity() + " not found: ")
                .endControlFlow()
                .addStatement("LOG.info($S, id)", "Deleted " + ctx.entity() + ": {}")
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
        String setter = "entity.set" + capitalize(col.javaName());
        switch (col.kind()) {
            case TENANT_ID -> map.addStatement("$L(row.getUuid($L))", setter, idx);
            // SPI 0.7.0 has no getInstant — round-trip via ISO-8601 String.
            case CREATED_AT, UPDATED_AT -> map.addCode(CodeBlock.of(
                    "{ String v = row.getString($L); if (v != null) $L($T.parse(v)); }\n",
                    idx, setter, INSTANT));
            case DELETED -> map.addStatement("$L(row.getBoolean($L))", setter, idx);
            case VERSION -> map.addStatement("$L(row.getLong($L))", setter, idx);
            case DOMAIN -> emitReadDomain(map, col, idx, ctx);
        }
    }

    private void emitReadDomain(MethodSpec.Builder map, Column col, int idx, Context ctx) {
        String setter = "id".equals(col.javaName()) ? "entity.setId" : "entity.set" + capitalize(col.javaName());
        String type = col.javaType();
        switch (classifyDomainType(type)) {
            case LIST -> emitReadList(map, type, setter, idx);
            case UUID -> map.addStatement("$L(row.getUuid($L))", setter, idx);
            case STRING -> map.addStatement("$L(row.getString($L))", setter, idx);
            case LONG -> map.addStatement("$L(row.getLong($L))", setter, idx);
            case INT -> map.addStatement("$L(row.getInt($L))", setter, idx);
            case BOOL -> map.addStatement("$L(row.getBoolean($L))", setter, idx);
            case DOUBLE -> map.addStatement("$L(row.getDouble($L))", setter, idx);
            case BIG_DECIMAL -> map.addCode(CodeBlock.of(
                    // No bindBigDecimal in SPI — round-trip via String.
                    "{ String v = row.getString($L); if (v != null) $L(new $T(v)); }\n",
                    idx, setter, BIG_DECIMAL));
            case INSTANT_LIKE -> map.addCode(CodeBlock.of(
                    // SPI 0.7.0 has no getInstant — round-trip via ISO-8601 String.
                    "{ String v = row.getString($L); if (v != null) $L($T.parse(v)); }\n",
                    idx, setter, INSTANT));
            case LOCAL_DATE -> map.addCode(CodeBlock.of(
                    "{ String v = row.getString($L); if (v != null) $L($T.parse(v)); }\n",
                    idx, setter, LOCAL_DATE));
            case ENUM_LIKE -> emitReadEnumLike(map, type, setter, idx, ctx);
        }
    }

    private void emitReadList(MethodSpec.Builder map, String type, String setter, int idx) {
        String genericType = type.substring(LIST_PREFIX.length(), type.length() - 1);
        ClassName elementType = ClassName.bestGuess(genericType);
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
            emitBindCol(body, col, idx++, ENTITY_SRC);
        }
    }

    private void emitUpdateBinds(CodeBlock.Builder body, List<Column> updatable, boolean versioned) {
        int idx = 0;
        for (Column col : updatable) {
            emitBindCol(body, col, idx++, ENTITY_SRC);
        }
        // id bind terminates WHERE clause; for versioned entities, the
        // expectedVersion bind enforces the optimistic-lock guard.
        body.addStatement("stmt.bindUuid($L, id)", idx++);
        if (versioned) {
            body.addStatement("stmt.bindLong($L, expectedVersion)", idx);
        }
    }

    private void emitBindCol(CodeBlock.Builder body, Column col, int idx, String src) {
        String cap = capitalize(col.javaName());
        switch (col.kind()) {
            case TENANT_ID -> body.addStatement("stmt.bindUuid($L, $L.get$L())", idx, src, cap);
            // SPI 0.7.0 has no bindInstant — round-trip via ISO-8601 String. Null-
            // guarded because update() is also bound to caller-supplied entities
            // where createdAt may legitimately be null (e.g.\ partial update DTO).
            case CREATED_AT, UPDATED_AT -> body.addStatement(
                    "stmt.bindString($L, $L.get$L() == null ? null : $L.get$L().toString())",
                    idx, src, cap, src, cap);
            // boolean accessor is `is<Name>()` per the SDK getter convention.
            case DELETED -> body.addStatement("stmt.bindBoolean($L, $L.is$L())", idx, src, cap);
            case VERSION -> body.addStatement("stmt.bindLong($L, $L.get$L())", idx, src, cap);
            case DOMAIN -> emitBindDomain(body, col, idx, src);
        }
    }

    private void emitBindDomain(CodeBlock.Builder body, Column col, int idx, String src) {
        // T15: a primitive `boolean` field's JavaBean accessor is `isX()`, not `getX()`
        // (matching the system DELETED column above and the entity the repository binds
        // against). `Boolean` wrappers keep `getX()` per the Lombok/JavaBean convention.
        String prefix = "boolean".equals(col.javaType()) ? "is" : "get";
        String getter = "id".equals(col.javaName())
                ? src + ".getId()"
                : src + "." + prefix + capitalize(col.javaName()) + "()";
        String type = col.javaType();
        switch (classifyDomainType(type)) {
            case LIST -> body.addStatement("stmt.bindString($L, toJson($L))", idx, getter);
            case UUID -> body.addStatement("stmt.bindUuid($L, $L)", idx, getter);
            case STRING -> body.addStatement("stmt.bindString($L, $L)", idx, getter);
            case LONG -> body.addStatement("stmt.bindLong($L, $L)", idx, getter);
            case INT -> body.addStatement("stmt.bindInt($L, $L)", idx, getter);
            case BOOL -> body.addStatement("stmt.bindBoolean($L, $L)", idx, getter);
            case DOUBLE -> body.addStatement("stmt.bindDouble($L, $L)", idx, getter);
            // SPI has no bindBigDecimal — encode as plain string.
            case BIG_DECIMAL -> body.addStatement(
                    "stmt.bindString($L, $L == null ? null : $L.toPlainString())",
                    idx, getter, getter);
            // SPI 0.7.0 has no bindInstant / bindLocalDate / enum binds —
            // round-trip via String.toString(); null-guarded.
            case INSTANT_LIKE, LOCAL_DATE, ENUM_LIKE ->
                    body.addStatement(BIND_STRING_NULL_GUARDED, idx, getter, getter);
        }
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

    private String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.REPOSITORY;
    }
}
