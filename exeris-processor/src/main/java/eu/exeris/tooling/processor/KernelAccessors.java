package eu.exeris.tooling.processor;

import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphPropertyMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
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
 *       flags enable. A {@code @Blob} field is no column, and neither is an inverse collection: a
 *       {@code List} field whose {@code ONE_TO_MANY} relationship names {@code mappedBy}. The
 *       repository binds each column from the getter and reads it back through the setter. A
 *       primitive {@code boolean} field and the soft-delete flag read as {@code is<Name>()}, every
 *       other column as {@code get<Name>()};</li>
 *   <li>a setter for every property the create and update handlers reset after decoding a body:
 *       the audit, version and soft-delete fields the flags enable, the audit actors and the
 *       deletion time and actor where declared, every {@code @Blob} field and every inverse
 *       collection;</li>
 *   <li>a getter for every field the create or update handler validates, and for every required,
 *       read-only field the repository checks before an insert;</li>
 *   <li>a getter for every field an event payload carries, and for the key, edge, weight and
 *       property fields the graph projection reads.</li>
 * </ul>
 * A field outside every list, such as a {@code @Blob} field or an inverse collection no event or
 * graph names, needs no getter, and a field that is only read needs no setter. A static or
 * transient field is not in the metadata and needs nothing.
 *
 * <p>The generated tests ({@code exeris.tests}) call the accessors of the same columns and fields;
 * the names they form agree with the list above for every field whose name is a camel-case Java
 * identifier.
 */
final class KernelAccessors {

    private static final String ID_FIELD = "id";
    private static final String BOOLEAN = "boolean";
    private static final String GET = "get";
    private static final String IS = "is";
    private static final String SET = "set";

    /** The default names of the system roles, when the {@code systemFields} block names none. */
    private static final String TENANT_ID = "tenantId";
    private static final String CREATED_AT = "createdAt";
    private static final String CREATED_BY = "createdBy";
    private static final String UPDATED_AT = "updatedAt";
    private static final String UPDATED_BY = "updatedBy";
    private static final String DELETED = "deleted";
    private static final String DELETED_AT = "deletedAt";
    private static final String DELETED_BY = "deletedBy";
    private static final String VERSION = "version";

    private static final Set<String> PRIMITIVE_TYPES =
            Set.of(BOOLEAN, "byte", "short", "int", "long", "float", "double", "char");

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
     * The name of each system role: the one the {@code systemFields} block declares, else the
     * default.
     */
    private record Roles(String key, String tenantId, String createdAt, String createdBy, String updatedAt,
                         String updatedBy, String deleted, String deletedAt, String deletedBy, String version) {

        static Roles of(SystemFieldsMetadata declared) {
            if (declared == null) {
                return new Roles(ID_FIELD, TENANT_ID, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY, DELETED,
                        DELETED_AT, DELETED_BY, VERSION);
            }
            return new Roles(role(declared.primaryKeyField(), ID_FIELD), role(declared.tenantIdField(), TENANT_ID),
                    role(declared.createdAtField(), CREATED_AT), role(declared.createdByField(), CREATED_BY),
                    role(declared.updatedAtField(), UPDATED_AT), role(declared.updatedByField(), UPDATED_BY),
                    role(declared.softDeleteField(), DELETED), role(declared.softDeleteTimestampField(), DELETED_AT),
                    role(declared.softDeletedByField(), DELETED_BY), role(declared.versionField(), VERSION));
        }
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
        List<FieldMetadata> fields = metadata.hasFields() ? metadata.fields() : List.of();
        Roles roles = Roles.of(metadata.systemFields());

        columns(metadata, fields, roles, accessors);
        resets(metadata, fields, roles, accessors);
        readFields(metadata, fields, roles, accessors);
        eventPayloads(metadata, fields, accessors);
        graph(metadata, fields, roles.key(), accessors);
        return List.copyOf(accessors.values());
    }

    /** The getters the handlers validate a body through, and the repository checks a server-set field by. */
    private static void readFields(DomainMetadata metadata, List<FieldMetadata> fields, Roles roles,
                                   Map<String, Accessor> accessors) {
        Set<String> systemRoles = systemFieldNames(metadata, fields, roles);
        Set<String> notInCreate = notInCreateBody(metadata, fields, systemRoles);
        Set<String> notInUpdate = notInUpdateBody(metadata, fields, roles, systemRoles);
        for (FieldMetadata field : fields) {
            String name = field.name();
            boolean validated = hasRules(field) && (!notInCreate.contains(name) || !notInUpdate.contains(name));
            if (validated || isSetByServer(metadata, field, systemRoles)) {
                add(accessors, name, GET + pascal(name), false);
            }
        }
    }

    /** A required, read-only, non-system reference-typed column, which the server sets before insert. */
    private static boolean isSetByServer(DomainMetadata metadata, FieldMetadata field, Set<String> systemRoles) {
        return field.required() && field.readOnly() && !systemRoles.contains(field.name())
                && !PRIMITIVE_TYPES.contains(field.type()) && !heldOutsideTheRow(metadata, field);
    }

    /**
     * Whether the entity's row holds nothing for {@code field}: a {@code @Blob} field, whose bytes
     * are in the blob store, or an inverse collection, whose rows are the target's. Neither is a
     * column nor a member of a request body, and the handlers reset both after decoding.
     */
    private static boolean heldOutsideTheRow(DomainMetadata metadata, FieldMetadata field) {
        return field.hasBlob() || isInverseCollection(metadata, field);
    }

    /**
     * Whether {@code field} is an inverse collection: a {@code List} field named by a
     * {@code ONE_TO_MANY} relationship with a non-blank {@code mappedBy}. The rule is the one the
     * Java emitters classify the field by.
     */
    static boolean isInverseCollection(DomainMetadata metadata, FieldMetadata field) {
        if (!metadata.hasRelationships() || !isList(field.type())) {
            return false;
        }
        for (RelationshipMetadata rel : metadata.relationships()) {
            if (rel.fieldName().equals(field.name())
                    && rel.type() == RelationshipMetadata.RelationType.ONE_TO_MANY
                    && rel.mappedBy() != null && !rel.mappedBy().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code type} is {@code List<X>} or {@code java.util.List<X>}. */
    private static boolean isList(String type) {
        return (type.startsWith("List<") || type.startsWith("java.util.List<")) && type.endsWith(">");
    }

    /** The repository's column layout: the key, the domain columns, then the system columns. */
    private static void columns(DomainMetadata metadata, List<FieldMetadata> fields, Roles roles,
                                Map<String, Accessor> accessors) {
        List<String[]> systemColumns = systemColumns(metadata, roles);
        Set<String> systemColumnNames = new HashSet<>();
        for (String[] column : systemColumns) {
            systemColumnNames.add(snakeCase(column[0]));
        }

        addColumn(accessors, roles.key(), GET);
        Set<String> seen = new HashSet<>();
        seen.add(snakeCase(roles.key()));
        for (FieldMetadata field : fields) {
            String column = snakeCase(field.name());
            if (!heldOutsideTheRow(metadata, field) && !seen.contains(column)
                    && !systemColumnNames.contains(column)) {
                seen.add(column);
                addColumn(accessors, field.name(), getterPrefix(field.type()));
            }
        }
        for (String[] column : systemColumns) {
            addColumn(accessors, column[0], column[1]);
        }
    }

    /** The system columns the flags enable, in table order: each its field name and getter prefix. */
    private static List<String[]> systemColumns(DomainMetadata metadata, Roles roles) {
        List<String[]> columns = new ArrayList<>();
        if (metadata.effectiveDataScope() != DataScope.GLOBAL) {
            columns.add(new String[] {roles.tenantId(), GET});
        }
        if (metadata.audited()) {
            columns.add(new String[] {roles.createdAt(), GET});
            columns.add(new String[] {roles.updatedAt(), GET});
        }
        if (metadata.softDelete()) {
            columns.add(new String[] {roles.deleted(), IS});
        }
        if (metadata.versioned()) {
            columns.add(new String[] {roles.version(), GET});
        }
        return columns;
    }

    /** The properties the create and update handlers set on the entity they decoded. */
    private static void resets(DomainMetadata metadata, List<FieldMetadata> fields, Roles roles,
                               Map<String, Accessor> accessors) {
        List<String> reset = new ArrayList<>();
        if (metadata.audited()) {
            reset.add(roles.createdAt());
            reset.add(roles.updatedAt());
            addIfDeclared(reset, fields, roles.createdBy());
            addIfDeclared(reset, fields, roles.updatedBy());
        }
        if (metadata.softDelete()) {
            reset.add(roles.deleted());
            addIfDeclared(reset, fields, roles.deletedAt());
            addIfDeclared(reset, fields, roles.deletedBy());
        }
        if (metadata.versioned()) {
            reset.add(roles.version());
        }
        fields.stream().filter(FieldMetadata::hasBlob).forEach(field -> reset.add(field.name()));
        fields.stream().filter(field -> isInverseCollection(metadata, field))
                .forEach(field -> reset.add(field.name()));
        for (String name : reset) {
            add(accessors, name, SET + pascal(name), true);
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
            event.payloadFields().stream()
                    .filter(name -> !sensitive.contains(name))
                    .forEach(name -> findField(fields, name).ifPresent(field -> add(accessors, name,
                            getterPrefix(field.type()) + capitalize(name), false)));
        }
    }

    /** The getters the graph projection reads the key, the edges, their weights and the properties through. */
    private static void graph(DomainMetadata metadata, List<FieldMetadata> fields, String key,
                              Map<String, Accessor> accessors) {
        GraphMetadata graph = metadata.graphMetadata();
        if (graph == null) {
            return;
        }
        add(accessors, key, GET + capitalize(key), false);
        if (graph.properties() != null) {
            for (GraphPropertyMetadata property : graph.properties()) {
                addTypedGetter(accessors, fields, property.effectiveField());
            }
        }
        if (graph.edges() != null) {
            for (GraphEdgeMetadata edge : graph.edges()) {
                String name = edge.effectiveField();
                if (name != null && !name.isEmpty()) {
                    add(accessors, name, GET + capitalize(name), false);
                }
                addTypedGetter(accessors, fields, edge.weightField());
            }
        }
    }

    /** The getter of {@code name}, typed by its declared field, unless {@code name} is absent. */
    private static void addTypedGetter(Map<String, Accessor> accessors, List<FieldMetadata> fields, String name) {
        if (name != null && !name.isEmpty()) {
            add(accessors, name, typedGetterPrefix(fields, name) + capitalize(name), false);
        }
    }

    /**
     * Every field that plays a system role: the key, the owner, a UNIVERSE entity's shared scope,
     * and the audit, version and soft-delete fields the flags enable. A field named for a role whose
     * flag is off is an ordinary domain field.
     */
    private static Set<String> systemFieldNames(DomainMetadata metadata, List<FieldMetadata> fields, Roles roles) {
        Set<String> names = new TreeSet<>();
        names.add(roles.key());
        if (metadata.effectiveDataScope() != DataScope.GLOBAL) {
            names.add(roles.tenantId());
        }
        sharedScopeField(metadata, fields).ifPresent(names::add);
        if (metadata.audited()) {
            names.addAll(List.of(roles.createdAt(), roles.createdBy(), roles.updatedAt(), roles.updatedBy()));
        }
        if (metadata.versioned()) {
            names.add(roles.version());
        }
        if (metadata.softDelete()) {
            names.addAll(List.of(roles.deleted(), roles.deletedAt(), roles.deletedBy()));
        }
        return names;
    }

    /** The fields the create body does not carry, so the create handler validates none of them. */
    private static Set<String> notInCreateBody(DomainMetadata metadata, List<FieldMetadata> fields,
                                               Set<String> systemRoles) {
        Set<String> names = new HashSet<>(systemRoles);
        for (FieldMetadata field : fields) {
            if (field.readOnly() || !field.inCreate() || heldOutsideTheRow(metadata, field)) {
                names.add(field.name());
            }
        }
        return names;
    }

    /** The fields the update body does not carry, so the update handler validates none of them. */
    private static Set<String> notInUpdateBody(DomainMetadata metadata, List<FieldMetadata> fields, Roles roles,
                                               Set<String> systemRoles) {
        Set<String> names = new HashSet<>(systemRoles);
        names.remove(roles.version());
        sharedScopeField(metadata, fields).ifPresent(names::remove);
        for (FieldMetadata field : fields) {
            boolean fixedOnUpdate = (field.readOnly() || !field.inUpdate()) && !systemRoles.contains(field.name());
            if (fixedOnUpdate || heldOutsideTheRow(metadata, field)) {
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
        return findField(fields, name).map(field -> getterPrefix(field.type())).orElse(GET);
    }

    /** {@code is} for the primitive {@code boolean} type, {@code get} for any other. */
    private static String getterPrefix(String type) {
        return BOOLEAN.equals(type) ? IS : GET;
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
        add(accessors, field, SET + capitalize(field), true);
    }

    private static void add(Map<String, Accessor> accessors, String field, String method, boolean setter) {
        accessors.putIfAbsent(method + (setter ? "/1" : "/0"), new Accessor(field, method, setter));
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
