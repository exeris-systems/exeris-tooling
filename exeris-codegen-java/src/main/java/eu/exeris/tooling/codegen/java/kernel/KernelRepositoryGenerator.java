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
import java.util.List;
import java.util.Set;

/**
 * Kernel Repository Generator.
 *
 * <p>Generates a JDBC-based repository (no ORM, hand-rolled SQL) for the
 * Exeris Kernel runtime. Branches on metadata flags (tenantScoped, audited,
 * softDelete, versioned) to extend SELECT/INSERT/UPDATE/DELETE shape.
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
    private static final ClassName TIMESTAMP = ClassName.get("java.sql", "Timestamp");
    private static final ClassName CONNECTION = ClassName.get("java.sql", "Connection");
    private static final ClassName PREPARED_STATEMENT = ClassName.get("java.sql", "PreparedStatement");
    private static final ClassName STATEMENT = ClassName.get("java.sql", "Statement");
    private static final ClassName RESULT_SET = ClassName.get("java.sql", "ResultSet");
    private static final ClassName SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
    private static final ClassName DATA_SOURCE = ClassName.get("javax.sql", "DataSource");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
    private static final ClassName TYPE_REFERENCE =
            ClassName.get("com.fasterxml.jackson.core.type", "TypeReference");
    private static final ClassName OBJECT_MAPPER =
            ClassName.get("com.fasterxml.jackson.databind", "ObjectMapper");
    private static final ClassName COLLECTIONS = ClassName.get("java.util", "Collections");

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".repository";
        String entity = metadata.entityName();
        String className = entity + "Repository";
        String table = toSnakeCase(entity) + "s";
        List<FieldMetadata> fields = metadata.fields();

        ClassName entityType = ClassName.get(metadata.packageName(), entity);
        ClassName selfType = ClassName.get(packageName, className);
        TypeName optionalOfEntity = ParameterizedTypeName.get(OPTIONAL, entityType);
        TypeName listOfEntity = ParameterizedTypeName.get(LIST_TYPE, entityType);
        TypeVariableContext ctx = new TypeVariableContext(entity, entityType, fields, metadata);

        TypeSpec repo = KernelScaffold.publicClass(className)
                .addJavadoc("Generated Repository for $L.\n", entity)
                .addJavadoc("<p>Source: {@link $T}\n", entityType)
                .addJavadoc("<p>Table: $L\n", table)
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build())
                .addField(FieldSpec.builder(String.class, "TABLE",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", table)
                        .build())
                .addField(FieldSpec.builder(OBJECT_MAPPER, "MAPPER",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T()", OBJECT_MAPPER)
                        .build())
                .addField(FieldSpec.builder(DATA_SOURCE, "dataSource",
                        Modifier.PRIVATE, Modifier.FINAL).build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(DATA_SOURCE, "dataSource")
                        .addStatement("this.dataSource = dataSource")
                        .build())
                .addMethod(buildFindById(ctx, optionalOfEntity))
                .addMethod(buildFindAll(ctx, listOfEntity))
                .addMethod(buildSave(ctx))
                .addMethod(buildUpdate(ctx))
                .addMethod(buildDeleteById(ctx))
                .addMethod(buildCount(ctx))
                .addMethod(buildMapRow(ctx))
                .addMethod(buildParseList())
                .addMethod(buildToJson())
                .build();

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, repo), ArtifactType.REPOSITORY);
    }

    /** Carrier for repeated build-time state — keeps signatures readable. */
    private record TypeVariableContext(String entity, ClassName entityType,
                                       List<FieldMetadata> fields, DomainMetadata metadata) {}

    private MethodSpec buildFindById(TypeVariableContext ctx, TypeName optionalOfEntity) {
        String softDeleteFilter = ctx.metadata().softDelete() ? " AND deleted = false" : "";
        String sqlTail = " WHERE id = ?" + softDeleteFilter;
        return MethodSpec.methodBuilder("findById")
                .addModifiers(Modifier.PUBLIC)
                .returns(optionalOfEntity)
                .addParameter(UUID_TYPE, "id")
                .addStatement("String sql = $S + TABLE + $S", "SELECT * FROM ", sqlTail)
                .beginControlFlow("try ($T conn = dataSource.getConnection();\n     $T ps = conn.prepareStatement(sql))",
                        CONNECTION, PREPARED_STATEMENT)
                .addStatement("ps.setObject(1, id)")
                .beginControlFlow("try ($T rs = ps.executeQuery())", RESULT_SET)
                .addStatement("return rs.next() ? $T.of(mapRow(rs)) : $T.empty()", OPTIONAL, OPTIONAL)
                .endControlFlow()
                .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
                .addStatement("LOG.error($S, id, e)", "Failed to find " + ctx.entity() + " by id: {}")
                .addStatement("throw new RuntimeException($S, e)", "Database error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildFindAll(TypeVariableContext ctx, TypeName listOfEntity) {
        String softDeleteFilter = ctx.metadata().softDelete() ? " WHERE deleted = false" : "";
        return MethodSpec.methodBuilder("findAll")
                .addModifiers(Modifier.PUBLIC)
                .returns(listOfEntity)
                .addStatement("String sql = $S + TABLE + $S", "SELECT * FROM ", softDeleteFilter)
                .beginControlFlow("try ($T conn = dataSource.getConnection();\n     $T st = conn.createStatement();\n     $T rs = st.executeQuery(sql))",
                        CONNECTION, STATEMENT, RESULT_SET)
                .addStatement("$T<$T> result = new $T<>()", LIST_TYPE, ctx.entityType(), ARRAY_LIST)
                .beginControlFlow("while (rs.next())")
                .addStatement("result.add(mapRow(rs))")
                .endControlFlow()
                .addStatement("return result")
                .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to find all " + ctx.entity())
                .addStatement("throw new RuntimeException($S, e)", "Database error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildSave(TypeVariableContext ctx) {
        String columns = buildInsertColumns(ctx.fields(), ctx.metadata());
        String placeholders = buildInsertPlaceholders(ctx.fields(), ctx.metadata());
        String sqlTail = " (" + columns + ") VALUES (" + placeholders + ")";

        MethodSpec.Builder save = MethodSpec.methodBuilder("save")
                .addModifiers(Modifier.PUBLIC)
                .returns(ctx.entityType())
                .addParameter(ctx.entityType(), "entity")
                .addStatement("String sql = $S + TABLE + $S", "INSERT INTO ", sqlTail)
                .beginControlFlow("try ($T conn = dataSource.getConnection();\n     $T ps = conn.prepareStatement(sql))",
                        CONNECTION, PREPARED_STATEMENT)
                .addStatement("if (entity.getId() == null) entity.setId($T.randomUUID())", UUID_TYPE);
        if (ctx.metadata().audited()) {
            save.addStatement("entity.setCreatedAt($T.now())", INSTANT);
            save.addStatement("entity.setUpdatedAt($T.now())", INSTANT);
        }
        emitInsertSetters(save, ctx.fields(), ctx.metadata());
        return save
                .addStatement("ps.executeUpdate()")
                .addStatement("LOG.info($S, entity.getId())", "Created " + ctx.entity() + ": {}")
                .addStatement("return entity")
                .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to save " + ctx.entity())
                .addStatement("throw new RuntimeException($S, e)", "Database error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildUpdate(TypeVariableContext ctx) {
        String setClause = buildUpdateSetClause(ctx.fields(), ctx.metadata());
        String sqlTail = " SET " + setClause + " WHERE id = ?";

        MethodSpec.Builder update = MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.PUBLIC)
                .returns(ctx.entityType())
                .addParameter(UUID_TYPE, "id")
                .addParameter(ctx.entityType(), "entity")
                .addStatement("String sql = $S + TABLE + $S", "UPDATE ", sqlTail)
                .beginControlFlow("try ($T conn = dataSource.getConnection();\n     $T ps = conn.prepareStatement(sql))",
                        CONNECTION, PREPARED_STATEMENT);
        if (ctx.metadata().audited()) {
            update.addStatement("entity.setUpdatedAt($T.now())", INSTANT);
        }
        emitUpdateSetters(update, ctx.fields(), ctx.metadata());
        return update
                .addStatement("int updated = ps.executeUpdate()")
                .addStatement("if (updated == 0) throw new RuntimeException($S + id)",
                        ctx.entity() + " not found: ")
                .addStatement("entity.setId(id)")
                .addStatement("LOG.info($S, id)", "Updated " + ctx.entity() + ": {}")
                .addStatement("return entity")
                .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
                .addStatement("LOG.error($S, id, e)", "Failed to update " + ctx.entity() + ": {}")
                .addStatement("throw new RuntimeException($S, e)", "Database error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildDeleteById(TypeVariableContext ctx) {
        MethodSpec.Builder delete = MethodSpec.methodBuilder("deleteById")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(UUID_TYPE, "id");
        if (ctx.metadata().softDelete()) {
            delete.addStatement("String sql = $S + TABLE + $S",
                            "UPDATE ", " SET deleted = true, deleted_at = ? WHERE id = ?")
                    .beginControlFlow("try ($T conn = dataSource.getConnection();\n     $T ps = conn.prepareStatement(sql))",
                            CONNECTION, PREPARED_STATEMENT)
                    .addStatement("ps.setTimestamp(1, $T.from($T.now()))", TIMESTAMP, INSTANT)
                    .addStatement("ps.setObject(2, id)");
        } else {
            delete.addStatement("String sql = $S + TABLE + $S", "DELETE FROM ", " WHERE id = ?")
                    .beginControlFlow("try ($T conn = dataSource.getConnection();\n     $T ps = conn.prepareStatement(sql))",
                            CONNECTION, PREPARED_STATEMENT)
                    .addStatement("ps.setObject(1, id)");
        }
        return delete
                .addStatement("int deleted = ps.executeUpdate()")
                .addStatement("if (deleted == 0) throw new RuntimeException($S + id)",
                        ctx.entity() + " not found: ")
                .addStatement("LOG.info($S, id)", "Deleted " + ctx.entity() + ": {}")
                .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
                .addStatement("LOG.error($S, id, e)", "Failed to delete " + ctx.entity() + ": {}")
                .addStatement("throw new RuntimeException($S, e)", "Database error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildCount(TypeVariableContext ctx) {
        String sqlTail = ctx.metadata().softDelete() ? " WHERE deleted = false" : "";
        return MethodSpec.methodBuilder("count")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.LONG)
                .addStatement("String sql = $S + TABLE + $S", "SELECT COUNT(*) FROM ", sqlTail)
                .beginControlFlow("try ($T conn = dataSource.getConnection();\n     $T st = conn.createStatement();\n     $T rs = st.executeQuery(sql))",
                        CONNECTION, STATEMENT, RESULT_SET)
                .addStatement("return rs.next() ? rs.getLong(1) : 0")
                .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to count " + ctx.entity())
                .addStatement("throw new RuntimeException($S, e)", "Database error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildMapRow(TypeVariableContext ctx) {
        MethodSpec.Builder map = MethodSpec.methodBuilder("mapRow")
                .addModifiers(Modifier.PRIVATE)
                .returns(ctx.entityType())
                .addParameter(RESULT_SET, "rs")
                .addException(SQL_EXCEPTION)
                .addStatement("$T entity = new $T()", ctx.entityType(), ctx.entityType())
                .addStatement("entity.setId(rs.getObject($S, $T.class))", "id", UUID_TYPE);

        for (FieldMetadata field : ctx.fields()) {
            if (isSystemField(field.name())) continue;
            emitMapRowFieldSetter(map, field);
        }
        if (ctx.metadata().tenantScoped()) {
            map.addStatement("entity.setTenantId(rs.getObject($S, $T.class))", "tenant_id", UUID_TYPE);
        }
        if (ctx.metadata().audited()) {
            map.addCode(CodeBlock.of("{ $T ts = rs.getTimestamp($S); if (ts != null) entity.setCreatedAt(ts.toInstant()); }\n",
                    TIMESTAMP, "created_at"));
            map.addCode(CodeBlock.of("{ $T ts = rs.getTimestamp($S); if (ts != null) entity.setUpdatedAt(ts.toInstant()); }\n",
                    TIMESTAMP, "updated_at"));
        }
        if (ctx.metadata().softDelete()) {
            map.addStatement("entity.setDeleted(rs.getBoolean($S))", "deleted");
        }
        if (ctx.metadata().versioned()) {
            map.addStatement("entity.setVersion(rs.getLong($S))", "version");
        }
        return map.addStatement("return entity").build();
    }

    private void emitMapRowFieldSetter(MethodSpec.Builder map, FieldMetadata field) {
        String col = toSnakeCase(field.name());
        String setter = "entity.set" + capitalize(field.name());
        String type = field.type();

        if (type.startsWith("List<")) {
            String genericType = type.substring(5, type.length() - 1);
            // bestGuess only tracks the import when genericType is fully qualified.
            ClassName elementType = ClassName.bestGuess(genericType);
            map.addCode(CodeBlock.of(
                    "{ String v = rs.getString($S); if (v != null) $L(parseList(v, new $T<$T<$T>>() {})); }\n",
                    col, setter, TYPE_REFERENCE, LIST_TYPE, elementType));
        } else if ("UUID".equals(type) || "java.util.UUID".equals(type)) {
            map.addStatement("$L(rs.getObject($S, $T.class))", setter, col, UUID_TYPE);
        } else if ("String".equals(type) || "java.lang.String".equals(type)) {
            map.addStatement("$L(rs.getString($S))", setter, col);
        } else if ("Long".equals(type) || "long".equals(type) || "java.lang.Long".equals(type)) {
            map.addStatement("$L(rs.getLong($S))", setter, col);
        } else if ("Integer".equals(type) || "int".equals(type) || "java.lang.Integer".equals(type)) {
            map.addStatement("$L(rs.getInt($S))", setter, col);
        } else if ("Boolean".equals(type) || "boolean".equals(type) || "java.lang.Boolean".equals(type)) {
            map.addStatement("$L(rs.getBoolean($S))", setter, col);
        } else if ("BigDecimal".equals(type) || "java.math.BigDecimal".equals(type)) {
            map.addStatement("$L(rs.getBigDecimal($S))", setter, col);
        } else if (type.contains("Instant") || type.contains("LocalDateTime")) {
            map.addCode(CodeBlock.of("{ $T ts = rs.getTimestamp($S); if (ts != null) $L(ts.toInstant()); }\n",
                    TIMESTAMP, col, setter));
        } else if (type.contains("LocalDate")) {
            map.addCode(CodeBlock.of("{ java.sql.Date d = rs.getDate($S); if (d != null) $L(d.toLocalDate()); }\n",
                    col, setter));
        } else {
            // Enum or other — get as string and convert
            map.addCode(CodeBlock.of("{ String v = rs.getString($S); if (v != null) $L($L.valueOf(v)); }\n",
                    col, setter, type));
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
                .addStatement("if (json == null || json.isEmpty()) return $T.emptyList()", COLLECTIONS)
                .beginControlFlow("try")
                .addStatement("return MAPPER.readValue(json, typeRef)")
                .nextControlFlow("catch (Exception e)")
                .addStatement("throw new RuntimeException($S, e)", "Failed to parse list JSON")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildToJson() {
        // JDBC drivers don't know how to serialize collections; route through MAPPER.
        return MethodSpec.methodBuilder("toJson")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(Object.class, "value")
                .addStatement("if (value == null) return null")
                .beginControlFlow("try")
                .addStatement("return MAPPER.writeValueAsString(value)")
                .nextControlFlow("catch (Exception e)")
                .addStatement("throw new RuntimeException($S, e)", "Failed to serialize to JSON")
                .endControlFlow()
                .build();
    }

    private void emitInsertSetters(MethodSpec.Builder save, List<FieldMetadata> fields, DomainMetadata metadata) {
        int idx = 1;
        save.addStatement("ps.setObject($L, entity.getId())", idx++);
        for (FieldMetadata field : fields) {
            if (field.type().startsWith("List<")) {
                save.addStatement("ps.setString($L, toJson(entity.get$L()))", idx++, capitalize(field.name()));
            } else {
                save.addStatement("ps.setObject($L, entity.get$L())", idx++, capitalize(field.name()));
            }
        }
        if (metadata.tenantScoped()) {
            save.addStatement("ps.setObject($L, entity.getTenantId())", idx++);
        }
        if (metadata.audited()) {
            save.addStatement("ps.setTimestamp($L, $T.from(entity.getCreatedAt()))", idx++, TIMESTAMP);
            save.addStatement("ps.setTimestamp($L, $T.from(entity.getUpdatedAt()))", idx++, TIMESTAMP);
        }
        if (metadata.softDelete()) {
            save.addStatement("ps.setBoolean($L, false)", idx);
        }
    }

    private void emitUpdateSetters(MethodSpec.Builder update, List<FieldMetadata> fields, DomainMetadata metadata) {
        int idx = 1;
        for (FieldMetadata field : fields) {
            if (field.type().startsWith("List<")) {
                update.addStatement("ps.setString($L, toJson(entity.get$L()))", idx++, capitalize(field.name()));
            } else {
                update.addStatement("ps.setObject($L, entity.get$L())", idx++, capitalize(field.name()));
            }
        }
        if (metadata.audited()) {
            update.addStatement("ps.setTimestamp($L, $T.from(entity.getUpdatedAt()))", idx++, TIMESTAMP);
        }
        update.addStatement("ps.setObject($L, id)", idx);
    }

    // --- SQL fragment builders (string concatenation, embedded into Java string literals) ---

    private String buildInsertColumns(List<FieldMetadata> fields, DomainMetadata metadata) {
        StringBuilder sb = new StringBuilder("id");
        for (FieldMetadata field : fields) {
            sb.append(", ").append(toSnakeCase(field.name()));
        }
        if (metadata.tenantScoped()) sb.append(", tenant_id");
        if (metadata.audited()) sb.append(", created_at, updated_at");
        if (metadata.softDelete()) sb.append(", deleted");
        return sb.toString();
    }

    private String buildInsertPlaceholders(List<FieldMetadata> fields, DomainMetadata metadata) {
        StringBuilder sb = new StringBuilder("?");
        for (int i = 0; i < fields.size(); i++) {
            sb.append(", ?");
        }
        if (metadata.tenantScoped()) sb.append(", ?");
        if (metadata.audited()) sb.append(", ?, ?");
        if (metadata.softDelete()) sb.append(", ?");
        return sb.toString();
    }

    private String buildUpdateSetClause(List<FieldMetadata> fields, DomainMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (FieldMetadata field : fields) {
            if (!first) sb.append(", ");
            sb.append(toSnakeCase(field.name())).append(" = ?");
            first = false;
        }
        if (metadata.audited()) {
            sb.append(", updated_at = ?");
        }
        return sb.toString();
    }

    private boolean isSystemField(String fieldName) {
        return Set.of("id", "tenantId", "createdAt", "createdBy", "updatedAt", "updatedBy",
                "deleted", "deletedAt", "deletedBy", "version").contains(fieldName);
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
