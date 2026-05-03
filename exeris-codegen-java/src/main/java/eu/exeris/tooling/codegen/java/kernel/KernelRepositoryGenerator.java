package eu.exeris.tooling.codegen.java.kernel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Kernel Repository Generator.
 * <p>
 * Generates JDBC-based repository for Exeris Kernel.
 * Uses pure JDBC, no ORM, optimized for performance.
 *
 * @author Exeris Team
 * @since 0.2.0
 */
public class KernelRepositoryGenerator implements KernelArtifactGenerator {

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".repository";
        String className = metadata.entityName() + "Repository";
        String entity = metadata.entityName();
        String table = toSnakeCase(entity) + "s";
        List<FieldMetadata> fields = metadata.fields();

        StringBuilder code = new StringBuilder();

        // Package and imports
        code.append("package ").append(packageName).append(";\n\n");
        code.append("import ").append(metadata.packageName()).append(".").append(entity).append(";\n");
        code.append("import org.slf4j.Logger;\n");
        code.append("import org.slf4j.LoggerFactory;\n\n");
        code.append("import javax.sql.DataSource;\n");
        code.append("import java.sql.*;\n");
        code.append("import java.time.Instant;\n");
        code.append("import java.util.*;\n\n");

        // Javadoc
        code.append("/**\n");
        code.append(" * Generated Repository for ").append(entity).append(".\n");
        code.append(" * <p>Source: {@link ").append(metadata.packageName()).append(".").append(entity).append("}\n");
        code.append(" * <p>Table: ").append(table).append("\n");
        code.append(" * <p><b>DO NOT EDIT</b> - Regenerate from domain model.\n");
        code.append(" */\n");

        // Class
        code.append("public class ").append(className).append(" {\n\n");
        code.append("    private static final Logger LOG = LoggerFactory.getLogger(").append(className).append(".class);\n");
        code.append("    private static final String TABLE = \"").append(table).append("\";\n\n");
        code.append("    private final DataSource dataSource;\n\n");

        // Constructor
        code.append("    public ").append(className).append("(DataSource dataSource) {\n");
        code.append("        this.dataSource = dataSource;\n");
        code.append("    }\n\n");

        // findById
        code.append("    public Optional<").append(entity).append("> findById(UUID id) {\n");
        code.append("        String sql = \"SELECT * FROM \" + TABLE + \" WHERE id = ?");
        if (metadata.softDelete()) {
            code.append(" AND deleted = false");
        }
        code.append("\";\n");
        code.append("        try (Connection conn = dataSource.getConnection();\n");
        code.append("             PreparedStatement ps = conn.prepareStatement(sql)) {\n");
        code.append("            ps.setObject(1, id);\n");
        code.append("            ResultSet rs = ps.executeQuery();\n");
        code.append("            return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();\n");
        code.append("        } catch (SQLException e) {\n");
        code.append("            LOG.error(\"Failed to find ").append(entity).append(" by id: {}\", id, e);\n");
        code.append("            throw new RuntimeException(\"Database error\", e);\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // findAll
        code.append("    public List<").append(entity).append("> findAll() {\n");
        code.append("        String sql = \"SELECT * FROM \" + TABLE");
        if (metadata.softDelete()) {
            code.append(" + \" WHERE deleted = false\"");
        }
        code.append(";\n");
        code.append("        try (Connection conn = dataSource.getConnection();\n");
        code.append("             Statement st = conn.createStatement()) {\n");
        code.append("            ResultSet rs = st.executeQuery(sql);\n");
        code.append("            List<").append(entity).append("> result = new ArrayList<>();\n");
        code.append("            while (rs.next()) result.add(mapRow(rs));\n");
        code.append("            return result;\n");
        code.append("        } catch (SQLException e) {\n");
        code.append("            LOG.error(\"Failed to find all ").append(entity).append("\", e);\n");
        code.append("            throw new RuntimeException(\"Database error\", e);\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // save
        code.append("    public ").append(entity).append(" save(").append(entity).append(" entity) {\n");
        code.append("        String sql = \"INSERT INTO \" + TABLE + \" (").append(buildInsertColumns(fields, metadata)).append(") VALUES (").append(buildInsertPlaceholders(fields, metadata)).append(")\";\n");
        code.append("        try (Connection conn = dataSource.getConnection();\n");
        code.append("             PreparedStatement ps = conn.prepareStatement(sql)) {\n");
        code.append("            if (entity.getId() == null) entity.setId(UUID.randomUUID());\n");
        if (metadata.audited()) {
            code.append("            entity.setCreatedAt(Instant.now());\n");
            code.append("            entity.setUpdatedAt(Instant.now());\n");
        }
        code.append(buildInsertSetters(fields, metadata));
        code.append("            ps.executeUpdate();\n");
        code.append("            LOG.info(\"Created ").append(entity).append(": {}\", entity.getId());\n");
        code.append("            return entity;\n");
        code.append("        } catch (SQLException e) {\n");
        code.append("            LOG.error(\"Failed to save ").append(entity).append("\", e);\n");
        code.append("            throw new RuntimeException(\"Database error\", e);\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // update
        code.append("    public ").append(entity).append(" update(UUID id, ").append(entity).append(" entity) {\n");
        code.append("        String sql = \"UPDATE \" + TABLE + \" SET ").append(buildUpdateSetClause(fields, metadata)).append(" WHERE id = ?\";\n");
        code.append("        try (Connection conn = dataSource.getConnection();\n");
        code.append("             PreparedStatement ps = conn.prepareStatement(sql)) {\n");
        if (metadata.audited()) {
            code.append("            entity.setUpdatedAt(Instant.now());\n");
        }
        code.append(buildUpdateSetters(fields, metadata));
        code.append("            int updated = ps.executeUpdate();\n");
        code.append("            if (updated == 0) throw new RuntimeException(\"").append(entity).append(" not found: \" + id);\n");
        code.append("            entity.setId(id);\n");
        code.append("            LOG.info(\"Updated ").append(entity).append(": {}\", id);\n");
        code.append("            return entity;\n");
        code.append("        } catch (SQLException e) {\n");
        code.append("            LOG.error(\"Failed to update ").append(entity).append(": {}\", id, e);\n");
        code.append("            throw new RuntimeException(\"Database error\", e);\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // deleteById (soft delete if enabled)
        code.append("    public void deleteById(UUID id) {\n");
        if (metadata.softDelete()) {
            code.append("        String sql = \"UPDATE \" + TABLE + \" SET deleted = true, deleted_at = ? WHERE id = ?\";\n");
            code.append("        try (Connection conn = dataSource.getConnection();\n");
            code.append("             PreparedStatement ps = conn.prepareStatement(sql)) {\n");
            code.append("            ps.setTimestamp(1, Timestamp.from(Instant.now()));\n");
            code.append("            ps.setObject(2, id);\n");
        } else {
            code.append("        String sql = \"DELETE FROM \" + TABLE + \" WHERE id = ?\";\n");
            code.append("        try (Connection conn = dataSource.getConnection();\n");
            code.append("             PreparedStatement ps = conn.prepareStatement(sql)) {\n");
            code.append("            ps.setObject(1, id);\n");
        }
        code.append("            int deleted = ps.executeUpdate();\n");
        code.append("            if (deleted == 0) throw new RuntimeException(\"").append(entity).append(" not found: \" + id);\n");
        code.append("            LOG.info(\"Deleted ").append(entity).append(": {}\", id);\n");
        code.append("        } catch (SQLException e) {\n");
        code.append("            LOG.error(\"Failed to delete ").append(entity).append(": {}\", id, e);\n");
        code.append("            throw new RuntimeException(\"Database error\", e);\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // count
        code.append("    public long count() {\n");
        code.append("        String sql = \"SELECT COUNT(*) FROM \" + TABLE");
        if (metadata.softDelete()) {
            code.append(" + \" WHERE deleted = false\"");
        }
        code.append(";\n");
        code.append("        try (Connection conn = dataSource.getConnection();\n");
        code.append("             Statement st = conn.createStatement()) {\n");
        code.append("            ResultSet rs = st.executeQuery(sql);\n");
        code.append("            return rs.next() ? rs.getLong(1) : 0;\n");
        code.append("        } catch (SQLException e) {\n");
        code.append("            LOG.error(\"Failed to count ").append(entity).append("\", e);\n");
        code.append("            throw new RuntimeException(\"Database error\", e);\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // mapRow
        code.append("    private ").append(entity).append(" mapRow(ResultSet rs) throws SQLException {\n");
        code.append("        ").append(entity).append(" entity = new ").append(entity).append("();\n");
        code.append("        entity.setId(rs.getObject(\"id\", UUID.class));\n");
        code.append(buildMapRowSetters(fields, metadata));
        code.append("        return entity;\n");
        code.append("    }\n");

        code.append("}\n");

        return new GeneratedFile(packageName, className, code.toString(), ArtifactType.REPOSITORY);
    }

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

    private String buildInsertSetters(List<FieldMetadata> fields, DomainMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        sb.append("            ps.setObject(").append(idx++).append(", entity.getId());\n");
        for (FieldMetadata field : fields) {
            sb.append("            ps.setObject(").append(idx++).append(", entity.get").append(capitalize(field.name())).append("());\n");
        }
        if (metadata.tenantScoped()) {
            sb.append("            ps.setObject(").append(idx++).append(", entity.getTenantId());\n");
        }
        if (metadata.audited()) {
            sb.append("            ps.setTimestamp(").append(idx++).append(", Timestamp.from(entity.getCreatedAt()));\n");
            sb.append("            ps.setTimestamp(").append(idx++).append(", Timestamp.from(entity.getUpdatedAt()));\n");
        }
        if (metadata.softDelete()) {
            sb.append("            ps.setBoolean(").append(idx).append(", false);\n");
        }
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

    private String buildUpdateSetters(List<FieldMetadata> fields, DomainMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (FieldMetadata field : fields) {
            sb.append("            ps.setObject(").append(idx++).append(", entity.get").append(capitalize(field.name())).append("());\n");
        }
        if (metadata.audited()) {
            sb.append("            ps.setTimestamp(").append(idx++).append(", Timestamp.from(entity.getUpdatedAt()));\n");
        }
        sb.append("            ps.setObject(").append(idx).append(", id);\n");
        return sb.toString();
    }

    private String buildMapRowSetters(List<FieldMetadata> fields, DomainMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        for (FieldMetadata field : fields) {
            // Skip system fields handled separately
            if (isSystemField(field.name())) continue;

            String col = toSnakeCase(field.name());
            String setter = "entity.set" + capitalize(field.name());
            String type = field.type();

            if (type.startsWith("List<")) {
                // List mapping: use helper method
                String genericType = type.substring(5, type.length() - 1);
                sb.append("        { String v = rs.getString(\"").append(col).append("\"); if (v != null) ")
                  .append(setter).append("(parseList(v, new TypeReference<List<").append(genericType).append(">( )>() {})); }\n");
            } else if ("UUID".equals(type) || "java.util.UUID".equals(type)) {
                sb.append("        ").append(setter).append("(rs.getObject(\"").append(col).append("\", UUID.class));\n");
            } else if ("String".equals(type) || "java.lang.String".equals(type)) {
                sb.append("        ").append(setter).append("(rs.getString(\"").append(col).append("\"));\n");
            } else if ("Long".equals(type) || "long".equals(type) || "java.lang.Long".equals(type)) {
                sb.append("        ").append(setter).append("(rs.getLong(\"").append(col).append("\"));\n");
            } else if ("Integer".equals(type) || "int".equals(type) || "java.lang.Integer".equals(type)) {
                sb.append("        ").append(setter).append("(rs.getInt(\"").append(col).append("\"));\n");
            } else if ("Boolean".equals(type) || "boolean".equals(type) || "java.lang.Boolean".equals(type)) {
                sb.append("        ").append(setter).append("(rs.getBoolean(\"").append(col).append("\"));\n");
            } else if ("BigDecimal".equals(type) || "java.math.BigDecimal".equals(type)) {
                sb.append("        ").append(setter).append("(rs.getBigDecimal(\"").append(col).append("\"));\n");
            } else if (type.contains("Instant") || type.contains("LocalDateTime")) {
                sb.append("        { Timestamp ts = rs.getTimestamp(\"").append(col).append("\"); ");
                sb.append("if (ts != null) ").append(setter).append("(ts.toInstant()); }\n");
            } else if (type.contains("LocalDate")) {
                sb.append("        { java.sql.Date d = rs.getDate(\"").append(col).append("\"); ");
                sb.append("if (d != null) ").append(setter).append("(d.toLocalDate()); }\n");
            } else {
                // Enum or other - get as string and convert
                sb.append("        { String v = rs.getString(\"").append(col).append("\"); ");
                sb.append("if (v != null) ").append(setter).append("(").append(type).append(".valueOf(v)); }\n");
            }
        }
        if (metadata.tenantScoped()) {
            sb.append("        entity.setTenantId(rs.getObject(\"tenant_id\", UUID.class));\n");
        }
        if (metadata.audited()) {
            sb.append("        { Timestamp ts = rs.getTimestamp(\"created_at\"); if (ts != null) entity.setCreatedAt(ts.toInstant()); }\n");
            sb.append("        { Timestamp ts = rs.getTimestamp(\"updated_at\"); if (ts != null) entity.setUpdatedAt(ts.toInstant()); }\n");
        }
        if (metadata.softDelete()) {
            sb.append("        entity.setDeleted(rs.getBoolean(\"deleted\"));\n");
        }
        if (metadata.versioned()) {
            sb.append("        entity.setVersion(rs.getLong(\"version\"));\n");
        }
        return sb.toString();
    }

    // Add helper for list parsing
    private static <T> List<T> parseList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return new ObjectMapper().readValue(json, typeRef);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse list JSON", e);
        }
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
    public ArtifactType artifactType() { return ArtifactType.REPOSITORY; }
}

