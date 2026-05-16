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

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

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
    private static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");

    private static final ClassName TRANSACTIONAL_EXECUTOR =
            ClassName.get("eu.exeris.kernel.spi.persistence", "TransactionalExecutor");
    private static final ClassName PERSISTENCE_STATEMENT =
            ClassName.get("eu.exeris.kernel.spi.persistence", "PersistenceStatement");
    private static final ClassName QUERY_RESULT =
            ClassName.get("eu.exeris.kernel.spi.persistence", "QueryResult");
    private static final ClassName ROW_CURSOR =
            ClassName.get("eu.exeris.kernel.spi.persistence", "RowCursor");

    private static final ClassName OBJECT_MAPPER =
            ClassName.get("tools.jackson.databind", "ObjectMapper");
    private static final ClassName JACKSON_EXCEPTION =
            ClassName.get("tools.jackson.core", "JacksonException");
    private static final ClassName TYPE_REFERENCE =
            ClassName.get("tools.jackson.core.type", "TypeReference");

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".repository";
        String entity = metadata.entityName();
        String className = entity + "Repository";
        String table = toSnakeCase(entity) + "s";
        List<FieldMetadata> fields = metadata.fields();
        boolean hasListField = fields.stream().anyMatch(f -> f.type().startsWith("List<"));

        ClassName entityType = ClassName.get(metadata.packageName(), entity);
        ClassName selfType = ClassName.get(packageName, className);
        TypeName optionalOfEntity = ParameterizedTypeName.get(OPTIONAL, entityType);
        TypeName listOfEntity = ParameterizedTypeName.get(LIST_TYPE, entityType);

        // Stable column layout — both SELECT clauses and mapRow consume it
        // in the same order, so column indices line up by construction.
        List<Column> columns = buildColumnLayout(fields, metadata);

        Context ctx = new Context(entity, entityType, fields, columns, metadata, table);

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
                           List<Column> columns, DomainMetadata metadata, String table) {}

    private List<Column> buildColumnLayout(List<FieldMetadata> fields, DomainMetadata metadata) {
        List<Column> cols = new ArrayList<>();
        cols.add(new Column("id", "id", "UUID", ColumnKind.DOMAIN));
        for (FieldMetadata field : fields) {
            cols.add(new Column(toSnakeCase(field.name()), field.name(), field.type(), ColumnKind.DOMAIN));
        }
        if (metadata.tenantScoped()) {
            cols.add(new Column("tenant_id", "tenantId", "UUID", ColumnKind.TENANT_ID));
        }
        if (metadata.audited()) {
            cols.add(new Column("created_at", "createdAt", "Instant", ColumnKind.CREATED_AT));
            cols.add(new Column("updated_at", "updatedAt", "Instant", ColumnKind.UPDATED_AT));
        }
        if (metadata.softDelete()) {
            cols.add(new Column("deleted", "deleted", "boolean", ColumnKind.DELETED));
        }
        if (metadata.versioned()) {
            cols.add(new Column("version", "version", "Long", ColumnKind.VERSION));
        }
        return cols;
    }

    private MethodSpec buildFindById(Context ctx, TypeName optionalOfEntity) {
        String selectCols = String.join(", ", ctx.columns().stream().map(Column::sqlName).toList());
        String softDeleteFilter = ctx.metadata().softDelete() ? " AND deleted = false" : "";
        String sql = "SELECT " + selectCols + " FROM " + ctx.table() + " WHERE id = ?" + softDeleteFilter;

        return MethodSpec.methodBuilder("findById")
                .addModifiers(Modifier.PUBLIC)
                .returns(optionalOfEntity)
                .addParameter(UUID_TYPE, "id")
                .addStatement("String sql = $S", sql)
                .addStatement("return executor.query(conn -> {\n"
                        + "    try ($T stmt = conn.prepare(sql)) {\n"
                        + "        stmt.bindUuid(0, id);\n"
                        + "        try ($T qr = stmt.executeQuery()) {\n"
                        + "            return qr.next() ? $T.of(mapRow(qr.row())) : $T.empty();\n"
                        + "        }\n"
                        + "    }\n"
                        + "})",
                        PERSISTENCE_STATEMENT, QUERY_RESULT, OPTIONAL, OPTIONAL)
                .build();
    }

    private MethodSpec buildFindAll(Context ctx, TypeName listOfEntity) {
        String selectCols = String.join(", ", ctx.columns().stream().map(Column::sqlName).toList());
        String softDeleteFilter = ctx.metadata().softDelete() ? " WHERE deleted = false" : "";
        String sql = "SELECT " + selectCols + " FROM " + ctx.table() + softDeleteFilter;

        return MethodSpec.methodBuilder("findAll")
                .addModifiers(Modifier.PUBLIC)
                .returns(listOfEntity)
                .addStatement("String sql = $S", sql)
                .addStatement("return executor.query(conn -> {\n"
                        + "    try ($T stmt = conn.prepare(sql);\n"
                        + "         $T qr = stmt.executeQuery()) {\n"
                        + "        $T<$T> result = new $T<>();\n"
                        + "        while (qr.next()) {\n"
                        + "            result.add(mapRow(qr.row()));\n"
                        + "        }\n"
                        + "        return result;\n"
                        + "    }\n"
                        + "})",
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
                .addStatement("if (entity.getId() == null) entity.setId($T.randomUUID())", UUID_TYPE);
        if (ctx.metadata().audited()) {
            save.addStatement("$T now = $T.now()", INSTANT, INSTANT);
            save.addStatement("entity.setCreatedAt(now)");
            save.addStatement("entity.setUpdatedAt(now)");
        }
        save.addStatement("String sql = $S", sql);

        CodeBlock.Builder body = CodeBlock.builder()
                .beginControlFlow("executor.executeManaged(conn -> ")
                .beginControlFlow("try ($T stmt = conn.prepare(sql))", PERSISTENCE_STATEMENT);
        emitInsertBinds(body, ctx);
        body.addStatement("stmt.executeUpdate()");
        body.endControlFlow();
        body.endControlFlow(")");

        save.addCode(body.build());
        save.addStatement("LOG.info($S, entity.getId())", "Created " + ctx.entity() + ": {}");
        save.addStatement("return entity");
        return save.build();
    }

    private MethodSpec buildUpdate(Context ctx) {
        // SET clause: every column except id (id is in WHERE)
        List<Column> updatable = ctx.columns().stream()
                .filter(c -> !"id".equals(c.sqlName()))
                .toList();
        String setClause = String.join(", ", updatable.stream().map(c -> c.sqlName() + " = ?").toList());
        String sql = "UPDATE " + ctx.table() + " SET " + setClause + " WHERE id = ?";

        MethodSpec.Builder update = MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.PUBLIC)
                .returns(ctx.entityType())
                .addParameter(UUID_TYPE, "id")
                .addParameter(ctx.entityType(), "entity");
        if (ctx.metadata().audited()) {
            update.addStatement("entity.setUpdatedAt($T.now())", INSTANT);
        }
        update.addStatement("String sql = $S", sql);
        update.addStatement("long[] rowsAffected = {0L}");

        CodeBlock.Builder body = CodeBlock.builder()
                .beginControlFlow("executor.executeManaged(conn -> ")
                .beginControlFlow("try ($T stmt = conn.prepare(sql))", PERSISTENCE_STATEMENT);
        emitUpdateBinds(body, ctx, updatable);
        body.addStatement("rowsAffected[0] = stmt.executeUpdate()");
        body.endControlFlow();
        body.endControlFlow(")");

        update.addCode(body.build());
        update.beginControlFlow("if (rowsAffected[0] == 0L)")
                .addStatement("throw new $T($S + id)",
                        RuntimeException.class, ctx.entity() + " not found: ")
                .endControlFlow();
        update.addStatement("entity.setId(id)");
        update.addStatement("LOG.info($S, id)", "Updated " + ctx.entity() + ": {}");
        update.addStatement("return entity");
        return update.build();
    }

    private MethodSpec buildDeleteById(Context ctx) {
        String sql = ctx.metadata().softDelete()
                ? "UPDATE " + ctx.table() + " SET deleted = true WHERE id = ?"
                : "DELETE FROM " + ctx.table() + " WHERE id = ?";

        CodeBlock.Builder body = CodeBlock.builder()
                .addStatement("String sql = $S", sql)
                .beginControlFlow("executor.executeManaged(conn -> ")
                .beginControlFlow("try ($T stmt = conn.prepare(sql))", PERSISTENCE_STATEMENT)
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
        String filter = ctx.metadata().softDelete() ? " WHERE deleted = false" : "";
        String sql = "SELECT COUNT(*) FROM " + ctx.table() + filter;

        return MethodSpec.methodBuilder("count")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.LONG)
                .addStatement("String sql = $S", sql)
                .addStatement("return executor.query(conn -> {\n"
                        + "    try ($T stmt = conn.prepare(sql);\n"
                        + "         $T qr = stmt.executeQuery()) {\n"
                        + "        return qr.next() ? qr.row().getLong(0) : 0L;\n"
                        + "    }\n"
                        + "})",
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
            emitReadCol(map, col, idx++);
        }
        return map.addStatement("return entity").build();
    }

    private void emitReadCol(MethodSpec.Builder map, Column col, int idx) {
        switch (col.kind()) {
            case TENANT_ID -> map.addStatement("entity.setTenantId(row.getUuid($L))", idx);
            // SPI 0.7.0 has no getInstant — round-trip via ISO-8601 String.
            case CREATED_AT -> map.addCode(CodeBlock.of(
                    "{ String v = row.getString($L); if (v != null) entity.setCreatedAt($T.parse(v)); }\n",
                    idx, INSTANT));
            case UPDATED_AT -> map.addCode(CodeBlock.of(
                    "{ String v = row.getString($L); if (v != null) entity.setUpdatedAt($T.parse(v)); }\n",
                    idx, INSTANT));
            case DELETED -> map.addStatement("entity.setDeleted(row.getBoolean($L))", idx);
            case VERSION -> map.addStatement("entity.setVersion(row.getLong($L))", idx);
            case DOMAIN -> emitReadDomain(map, col, idx);
        }
    }

    private void emitReadDomain(MethodSpec.Builder map, Column col, int idx) {
        String setter = "id".equals(col.javaName()) ? "entity.setId" : "entity.set" + capitalize(col.javaName());
        String type = col.javaType();

        if (type.startsWith("List<")) {
            String genericType = type.substring(5, type.length() - 1);
            ClassName elementType = ClassName.bestGuess(genericType);
            map.addCode(CodeBlock.of(
                    "{ String v = row.getString($L); if (v != null) $L(parseList(v, new $T<$T<$T>>() {})); }\n",
                    idx, setter, TYPE_REFERENCE, LIST_TYPE, elementType));
        } else if ("UUID".equals(type) || "java.util.UUID".equals(type)) {
            map.addStatement("$L(row.getUuid($L))", setter, idx);
        } else if ("String".equals(type) || "java.lang.String".equals(type)) {
            map.addStatement("$L(row.getString($L))", setter, idx);
        } else if ("Long".equals(type) || "long".equals(type) || "java.lang.Long".equals(type)) {
            map.addStatement("$L(row.getLong($L))", setter, idx);
        } else if ("Integer".equals(type) || "int".equals(type) || "java.lang.Integer".equals(type)) {
            map.addStatement("$L(row.getInt($L))", setter, idx);
        } else if ("Boolean".equals(type) || "boolean".equals(type) || "java.lang.Boolean".equals(type)) {
            map.addStatement("$L(row.getBoolean($L))", setter, idx);
        } else if ("Double".equals(type) || "double".equals(type) || "java.lang.Double".equals(type)) {
            map.addStatement("$L(row.getDouble($L))", setter, idx);
        } else if ("BigDecimal".equals(type) || "java.math.BigDecimal".equals(type)) {
            // No bindBigDecimal in SPI — round-trip via String.
            map.addCode(CodeBlock.of("{ String v = row.getString($L); if (v != null) $L(new $T(v)); }\n",
                    idx, setter, BIG_DECIMAL));
        } else if (type.contains("Instant") || type.contains("LocalDateTime")) {
            // SPI 0.7.0 has no getInstant — round-trip via ISO-8601 String.
            map.addCode(CodeBlock.of(
                    "{ String v = row.getString($L); if (v != null) $L($T.parse(v)); }\n",
                    idx, setter, INSTANT));
        } else if (type.contains("LocalDate")) {
            // No bindLocalDate in SPI — round-trip via String (ISO-8601).
            map.addCode(CodeBlock.of(
                    "{ String v = row.getString($L); if (v != null) $L(java.time.LocalDate.parse(v)); }\n",
                    idx, setter));
        } else {
            // Enum or other — read as string, valueOf on the entity-typed enum.
            map.addCode(CodeBlock.of("{ String v = row.getString($L); if (v != null) $L($L.valueOf(v)); }\n",
                    idx, setter, type));
        }
    }

    private void emitInsertBinds(CodeBlock.Builder body, Context ctx) {
        int idx = 0;
        for (Column col : ctx.columns()) {
            emitBindCol(body, col, idx++, "entity");
        }
    }

    private void emitUpdateBinds(CodeBlock.Builder body, Context ctx, List<Column> updatable) {
        int idx = 0;
        for (Column col : updatable) {
            emitBindCol(body, col, idx++, "entity");
        }
        // id bind is last — terminates the WHERE clause.
        body.addStatement("stmt.bindUuid($L, id)", idx);
    }

    private void emitBindCol(CodeBlock.Builder body, Column col, int idx, String src) {
        switch (col.kind()) {
            case TENANT_ID -> body.addStatement("stmt.bindUuid($L, $L.getTenantId())", idx, src);
            // SPI 0.7.0 has no bindInstant — round-trip via ISO-8601 String. Audited
            // timestamps are non-null by construction (set in save() before bind).
            case CREATED_AT -> body.addStatement("stmt.bindString($L, $L.getCreatedAt().toString())", idx, src);
            case UPDATED_AT -> body.addStatement("stmt.bindString($L, $L.getUpdatedAt().toString())", idx, src);
            case DELETED -> body.addStatement("stmt.bindBoolean($L, $L.isDeleted())", idx, src);
            case VERSION -> body.addStatement("stmt.bindLong($L, $L.getVersion())", idx, src);
            case DOMAIN -> emitBindDomain(body, col, idx, src);
        }
    }

    private void emitBindDomain(CodeBlock.Builder body, Column col, int idx, String src) {
        String getter = "id".equals(col.javaName()) ? src + ".getId()" : src + ".get" + capitalize(col.javaName()) + "()";
        String type = col.javaType();

        if (type.startsWith("List<")) {
            body.addStatement("stmt.bindString($L, toJson($L))", idx, getter);
        } else if ("UUID".equals(type) || "java.util.UUID".equals(type)) {
            body.addStatement("stmt.bindUuid($L, $L)", idx, getter);
        } else if ("String".equals(type) || "java.lang.String".equals(type)) {
            body.addStatement("stmt.bindString($L, $L)", idx, getter);
        } else if ("Long".equals(type) || "long".equals(type) || "java.lang.Long".equals(type)) {
            body.addStatement("stmt.bindLong($L, $L)", idx, getter);
        } else if ("Integer".equals(type) || "int".equals(type) || "java.lang.Integer".equals(type)) {
            body.addStatement("stmt.bindInt($L, $L)", idx, getter);
        } else if ("Boolean".equals(type) || "boolean".equals(type) || "java.lang.Boolean".equals(type)) {
            body.addStatement("stmt.bindBoolean($L, $L)", idx, getter);
        } else if ("Double".equals(type) || "double".equals(type) || "java.lang.Double".equals(type)) {
            body.addStatement("stmt.bindDouble($L, $L)", idx, getter);
        } else if ("BigDecimal".equals(type) || "java.math.BigDecimal".equals(type)) {
            // SPI has no bindBigDecimal — encode as plain string.
            body.addStatement("stmt.bindString($L, $L == null ? null : $L.toPlainString())",
                    idx, getter, getter);
        } else if (type.contains("Instant") || type.contains("LocalDateTime")) {
            // SPI 0.7.0 has no bindInstant — round-trip via ISO-8601 String.
            body.addStatement("stmt.bindString($L, $L == null ? null : $L.toString())",
                    idx, getter, getter);
        } else if (type.contains("LocalDate")) {
            body.addStatement("stmt.bindString($L, $L == null ? null : $L.toString())",
                    idx, getter, getter);
        } else {
            // Enum or other — bind as string via toString().
            body.addStatement("stmt.bindString($L, $L == null ? null : $L.toString())",
                    idx, getter, getter);
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
