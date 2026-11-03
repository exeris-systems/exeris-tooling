package eu.exeris.tooling.processor;

import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphPropertyMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The accessors the generated kernel application calls on an {@code @ExerisDomain} entity,
 * derived from the entity's {@link DomainMetadata} by the rules the Java emitters of
 * {@code exeris-codegen-java} apply when they name a getter or a setter.
 *
 * <p>The generated main sources call, on the entity:
 * <ul>
 *   <li>a getter and a setter for every column of its table: the primary key, every field that is
 *       a column, and the owning tenant, audit times, soft-delete flag and version the entity's
 *       flags enable. The repository binds each column from the getter and reads it back through
 *       the setter. A primitive {@code boolean} field and the soft-delete flag read as
 *       {@code is<Name>()}, every other column as {@code get<Name>()};</li>
 *   <li>a setter for every property the create and update handlers reset after decoding a body:
 *       the audit, version and soft-delete fields the flags enable, the audit actors and the
 *       deletion time and actor where declared, and every {@code @Blob} field;</li>
 *   <li>a getter for every field the create or update handler validates, and for every required,
 *       read-only field the repository checks before an insert;</li>
 *   <li>a getter for every field an event payload carries, and for the key, edge, weight and
 *       property fields the graph projection reads.</li>
 * </ul>
 * A field outside every list, such as a {@code @Blob} field no event or graph names, needs no
 * getter, and a field that is only read needs no setter. A static or transient field is not in
 * the metadata and needs nothing.
 *
 * <p>The generated tests ({@code exeris.tests}) call the accessors of the same columns and fields;
 * the names they form agree with the list above for every field whose name is a camel-case Java
 * identifier.
 */
final class KernelAccessors {

    private static final String ID_FIELD = "id";

    private static final Set<String> PRIMITIVE_TYPES =
            Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char");

    private static final Set<String> PRIMITIVE_NUMERIC_TYPES =
            Set.of("byte", "short", "int", "long", "float", "double");

    private static final Set<String> BOXED_NUMERIC_TYPES =
            Set.of("Integer", "Long", "Short", "Byte", "Float", "Double");

    private KernelAccessors() {
    }

    /**
     * One accessor the generated application calls.
     *
     * @param field  the entity property the accessor reads or writes
     * @param method the method name
     * @param setter {@code true} for a one-argument setter, {@code false} for a no-argument getter
     */
    record Accessor(String field, String method, boolean setter) {
    }

    /**
     * The accessors the generated main sources call on the entity, each once, in a fixed order:
     * the columns in table order, then the handler resets, the validated and server-set fields,
     * the event payloads and the graph projection.
     *
     * @param metadata the entity's metadata, as the processor writes it
     * @return the accessors; never empty, since every entity has a primary key
     */
    static List<Accessor> required(DomainMetadata metadata) {
        Map<String, Accessor> accessors = new LinkedHashMap<>();
        SystemFieldsMetadata declared = metadata.systemFields();
        List<FieldMetadata> fields = metadata.hasFields() ? metadata.fields() : List.of();
        String key = primaryKeyField(metadata);

        columns(metadata, fields, key, accessors);
        resets(metadata, fields, declared, accessors);

        Set<String> systemRoles = systemFieldNames(metadata, fields, key);
        Set<String> notInCreate = notInCreateBody(fields, systemRoles);
        Set<String> notInUpdate = notInUpdateBody(metadata, fields, systemRoles);
        for (FieldMetadata field : fields) {
            String name = field.name();
            if (hasRules(field) && (!notInCreate.contains(name) || !notInUpdate.contains(name))) {
                add(accessors, name, "get" + pascal(name), false);
            }
            if (field.required() && field.readOnly() && !systemRoles.contains(name)
                    && !PRIMITIVE_TYPES.contains(field.type()) && !field.hasBlob()) {
                add(accessors, name, "get" + pascal(name), false);
            }
        }

        eventPayloads(metadata, fields, accessors);
        graph(metadata, fields, key, accessors);
        return List.copyOf(accessors.values());
    }

    /** The repository's column layout: the key, the domain columns, then the system columns. */
    private static void columns(DomainMetadata metadata, List<FieldMetadata> fields, String key,
                                Map<String, Accessor> accessors) {
        SystemFieldsMetadata declared = metadata.systemFields();
        String tenantId = role(declared == null ? null : declared.tenantIdField(), "tenantId");
        String createdAt = role(declared == null ? null : declared.createdAtField(), "createdAt");
        String updatedAt = role(declared == null ? null : declared.updatedAtField(), "updatedAt");
        String deleted = role(declared == null ? null : declared.softDeleteField(), "deleted");
        String version = role(declared == null ? null : declared.versionField(), "version");
        boolean tenantPartitioned = metadata.effectiveDataScope() != DataScope.GLOBAL;

        Set<String> systemColumns = new HashSet<>();
        if (tenantPartitioned) {
            systemColumns.add(snakeCase(tenantId));
        }
        if (metadata.audited()) {
            systemColumns.add(snakeCase(createdAt));
            systemColumns.add(snakeCase(updatedAt));
        }
        if (metadata.softDelete()) {
            systemColumns.add(snakeCase(deleted));
        }
        if (metadata.versioned()) {
            systemColumns.add(snakeCase(version));
        }

        addColumn(accessors, key, "get");
        Set<String> seen = new HashSet<>();
        seen.add(snakeCase(key));
        for (FieldMetadata field : fields) {
            String column = snakeCase(field.name());
            if (field.hasBlob() || seen.contains(column) || systemColumns.contains(column)) {
                continue;
            }
            seen.add(column);
            addColumn(accessors, field.name(), "boolean".equals(field.type()) ? "is" : "get");
        }
        if (tenantPartitioned) {
            addColumn(accessors, tenantId, "get");
        }
        if (metadata.audited()) {
            addColumn(accessors, createdAt, "get");
            addColumn(accessors, updatedAt, "get");
        }
        if (metadata.softDelete()) {
            addColumn(accessors, deleted, "is");
        }
        if (metadata.versioned()) {
            addColumn(accessors, version, "get");
        }
    }

    /** The properties the create and update handlers set on the entity they decoded. */
    private static void resets(DomainMetadata metadata, List<FieldMetadata> fields,
                               SystemFieldsMetadata declared, Map<String, Accessor> accessors) {
        List<String> reset = new ArrayList<>();
        if (metadata.audited()) {
            reset.add(role(declared == null ? null : declared.createdAtField(), "createdAt"));
            reset.add(role(declared == null ? null : declared.updatedAtField(), "updatedAt"));
            addIfDeclared(reset, fields, role(declared == null ? null : declared.createdByField(), "createdBy"));
            addIfDeclared(reset, fields, role(declared == null ? null : declared.updatedByField(), "updatedBy"));
        }
        if (metadata.softDelete()) {
            reset.add(role(declared == null ? null : declared.softDeleteField(), "deleted"));
            addIfDeclared(reset, fields,
                    role(declared == null ? null : declared.softDeleteTimestampField(), "deletedAt"));
            addIfDeclared(reset, fields,
                    role(declared == null ? null : declared.softDeletedByField(), "deletedBy"));
        }
        if (metadata.versioned()) {
            reset.add(role(declared == null ? null : declared.versionField(), "version"));
        }
        for (FieldMetadata field : fields) {
            if (field.hasBlob()) {
                reset.add(field.name());
            }
        }
        for (String name : reset) {
            add(accessors, name, "set" + pascal(name), true);
        }
    }

    /** The getters the event publisher reads each payload field through. */
    private static void eventPayloads(DomainMetadata metadata, List<FieldMetadata> fields,
                                      Map<String, Accessor> accessors) {
        if (metadata.events() == null) {
            return;
        }
        for (DomainEventMetadata event : metadata.events()) {
            Set<String> sensitive = new HashSet<>(event.sensitiveFields());
            for (String name : event.payloadFields()) {
                if (sensitive.contains(name)) {
                    continue;
                }
                findField(fields, name).ifPresent(field -> add(accessors, name,
                        ("boolean".equals(field.type()) ? "is" : "get") + capitalize(name), false));
            }
        }
    }

    /** The getters the graph projection reads the key, the edges, their weights and the properties through. */
    private static void graph(DomainMetadata metadata, List<FieldMetadata> fields, String key,
                              Map<String, Accessor> accessors) {
        GraphMetadata graph = metadata.graphMetadata();
        if (graph == null) {
            return;
        }
        add(accessors, key, "get" + capitalize(key), false);
        if (graph.properties() != null) {
            for (GraphPropertyMetadata property : graph.properties()) {
                String name = property.effectiveField();
                if (name != null && !name.isEmpty()) {
                    add(accessors, name, typedGetterPrefix(fields, name) + capitalize(name), false);
                }
            }
        }
        if (graph.edges() != null) {
            for (GraphEdgeMetadata edge : graph.edges()) {
                String name = edge.effectiveField();
                if (name != null && !name.isEmpty()) {
                    add(accessors, name, "get" + capitalize(name), false);
                }
                String weight = edge.weightField();
                if (weight != null && !weight.isEmpty()) {
                    add(accessors, weight, typedGetterPrefix(fields, weight) + capitalize(weight), false);
                }
            }
        }
    }

    /**
     * Every field that plays a system role: the key, the owner, a UNIVERSE entity's shared scope,
     * the audit, version and soft-delete fields the flags enable, and every name the
     * {@code systemFields} block declares.
     */
    private static Set<String> systemFieldNames(DomainMetadata metadata, List<FieldMetadata> fields, String key) {
        SystemFieldsMetadata declared = metadata.systemFields();
        Set<String> names = new TreeSet<>();
        names.add(key);
        if (metadata.effectiveDataScope() != DataScope.GLOBAL) {
            names.add(role(declared == null ? null : declared.tenantIdField(), "tenantId"));
        }
        sharedScopeField(metadata, fields).ifPresent(names::add);
        if (metadata.audited()) {
            names.add(role(declared == null ? null : declared.createdAtField(), "createdAt"));
            names.add(role(declared == null ? null : declared.createdByField(), "createdBy"));
            names.add(role(declared == null ? null : declared.updatedAtField(), "updatedAt"));
            names.add(role(declared == null ? null : declared.updatedByField(), "updatedBy"));
        }
        if (metadata.versioned()) {
            names.add(role(declared == null ? null : declared.versionField(), "version"));
        }
        if (metadata.softDelete()) {
            names.add(role(declared == null ? null : declared.softDeleteField(), "deleted"));
            names.add(role(declared == null ? null : declared.softDeleteTimestampField(), "deletedAt"));
            names.add(role(declared == null ? null : declared.softDeletedByField(), "deletedBy"));
        }
        if (declared != null) {
            for (String name : new String[] {declared.primaryKeyField(), declared.createdAtField(),
                    declared.createdByField(), declared.updatedAtField(), declared.updatedByField(),
                    declared.tenantIdField(), declared.versionField(), declared.softDeleteField(),
                    declared.softDeleteTimestampField(), declared.softDeletedByField(),
                    declared.sharedScopeField()}) {
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** The fields the create body does not carry, so the create handler validates none of them. */
    private static Set<String> notInCreateBody(List<FieldMetadata> fields, Set<String> systemRoles) {
        Set<String> names = new HashSet<>(systemRoles);
        for (FieldMetadata field : fields) {
            if (field.readOnly() || !field.inCreate() || field.hasBlob()) {
                names.add(field.name());
            }
        }
        return names;
    }

    /** The fields the update body does not carry, so the update handler validates none of them. */
    private static Set<String> notInUpdateBody(DomainMetadata metadata, List<FieldMetadata> fields,
                                               Set<String> systemRoles) {
        SystemFieldsMetadata declared = metadata.systemFields();
        Set<String> names = new HashSet<>(systemRoles);
        names.remove(role(declared == null ? null : declared.versionField(), "version"));
        sharedScopeField(metadata, fields).ifPresent(names::remove);
        for (FieldMetadata field : fields) {
            boolean fixedOnUpdate = (field.readOnly() || !field.inUpdate()) && !systemRoles.contains(field.name());
            if (fixedOnUpdate || field.hasBlob()) {
                names.add(field.name());
            }
        }
        return names;
    }

    /** A UNIVERSE entity's shared-scope field, when the {@code systemFields} block names one it declares. */
    private static Optional<String> sharedScopeField(DomainMetadata metadata, List<FieldMetadata> fields) {
        if (metadata.effectiveDataScope() != DataScope.UNIVERSE || metadata.systemFields() == null) {
            return Optional.empty();
        }
        String name = metadata.systemFields().sharedScopeField();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return findField(fields, name).map(FieldMetadata::name);
    }

    /** Whether the generated handler enforces at least one rule on the field. */
    private static boolean hasRules(FieldMetadata field) {
        String type = field.type();
        boolean nullCheck = field.required() && !PRIMITIVE_TYPES.contains(type);
        boolean stringChecks = "String".equals(simpleTypeName(type))
                && (field.minLength() != null || field.maxLength() != null || field.pattern() != null);
        boolean numericChecks = (field.min() != null || field.max() != null) && isNumeric(type);
        return nullCheck || stringChecks || numericChecks;
    }

    private static boolean isNumeric(String type) {
        String simple = simpleTypeName(type);
        return "BigDecimal".equals(simple) || PRIMITIVE_NUMERIC_TYPES.contains(type)
                || BOXED_NUMERIC_TYPES.contains(simple);
    }

    private static String simpleTypeName(String type) {
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    /** {@code is} for a declared primitive {@code boolean} field, {@code get} for anything else. */
    private static String typedGetterPrefix(List<FieldMetadata> fields, String name) {
        return findField(fields, name).filter(field -> "boolean".equals(field.type())).isPresent() ? "is" : "get";
    }

    private static void addIfDeclared(List<String> reset, List<FieldMetadata> fields, String name) {
        if (findField(fields, name).isPresent()) {
            reset.add(name);
        }
    }

    private static Optional<FieldMetadata> findField(List<FieldMetadata> fields, String name) {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst();
    }

    private static void addColumn(Map<String, Accessor> accessors, String field, String getterPrefix) {
        add(accessors, field, getterPrefix + capitalize(field), false);
        add(accessors, field, "set" + capitalize(field), true);
    }

    private static void add(Map<String, Accessor> accessors, String field, String method, boolean setter) {
        accessors.putIfAbsent(method + (setter ? "/1" : "/0"), new Accessor(field, method, setter));
    }

    /** The primary key: the field {@code systemFields.primaryKeyField} names, else {@code id}. */
    private static String primaryKeyField(DomainMetadata metadata) {
        SystemFieldsMetadata declared = metadata.systemFields();
        return role(declared == null ? null : declared.primaryKeyField(), ID_FIELD);
    }

    private static String role(String declared, String fallback) {
        return declared == null || declared.isBlank() ? fallback : declared;
    }

    /** The column name the repository gives a field: camel case split on case changes, lower-cased. */
    private static String snakeCase(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** The first character upper-cased: the accessor suffix the repository, events and graph form. */
    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Upper camel case with every non-alphanumeric character dropped and the next one upper-cased:
     * the accessor suffix the handlers form.
     */
    private static String pascal(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        boolean upper = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }
}
