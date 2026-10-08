package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The fields the create and update bodies leave out, the fields an update does not write from the
 * entity it is handed, and the fields the server must set before a row is written.
 *
 * <p>An update replaces every domain field with the entity's value, except two kinds of field:
 * <ul>
 *   <li>the fields that play a system role — the primary key, the owning tenant, the audit fields
 *       (created and updated at and by) and the soft-delete fields: every field
 *       {@link ListQuerySupport#systemFieldNames} names except the version, which the caller passes
 *       as the expected version an optimistic-lock update matches on, and a UNIVERSE entity's
 *       shared scope, which the update writes (ADR-090 §3). No update path writes them from the
 *       entity;</li>
 *   <li>the domain fields a client does not set on update — those marked
 *       {@code @Field(readOnly = true)} or {@code @Field(inUpdate = false)}: the update a request
 *       body drives ({@code PUT {base}/{id}}) keeps their stored value, while the update an
 *       action's entity method drives writes them (ADR-090, Amendments 2 and 3). A field in a
 *       system role keeps that role's rule whether or not it is also marked.</li>
 * </ul>
 *
 * <p>The sets that follow from that:
 * <ul>
 *   <li>{@link #notInUpdateBody} — what the published update schema leaves out and the update
 *       handler does not validate: both kinds;</li>
 *   <li>{@link #keptOnUpdate} — what no {@code UPDATE} statement writes, so the stored value stays:
 *       the system-role fields without the update stamp of an audited entity, which every update
 *       sets from a server value ({@code Instant.now()});</li>
 *   <li>{@link #keptOnRequestUpdate} — what the request-body update keeps: {@link #keptOnUpdate}
 *       and the fields a client does not set on update ({@link #fixedOnRequestUpdate});</li>
 *   <li>{@link #notInCreateBody} — what the published create schema leaves out and the create
 *       handler does not validate.</li>
 * </ul>
 *
 * <p>The version and a UNIVERSE entity's shared scope are in no update set: every update writes
 * both, the version as the value the caller passes plus one. The shared scope is in
 * {@link #notInCreateBody}, since the create stamps it from the bound scope, as the version is,
 * since the create starts it.
 *
 * @since 0.10
 */
public final class ServerOwnedFields {

    private static final String NULL = "null";
    private static final String VERSION_DEFAULT = "version";

    private static final Set<String> PRIMITIVE_TYPES =
            Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char");

    private ServerOwnedFields() {}

    /**
     * The fields the update request body does not carry.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> notInUpdateBody(DomainMetadata metadata) {
        Set<String> names = systemFieldsNotWritten(metadata);
        names.addAll(fixedOnRequestUpdate(metadata));
        return Collections.unmodifiableSet(names);
    }

    /**
     * The fields the create request body does not carry: every field that plays a system role (the
     * key, the owner, a UNIVERSE entity's shared scope, the audit, version and soft-delete fields
     * the entity enables, and every name a {@code systemFields} block declares), the read-only
     * fields and the fields marked {@code @Field(inCreate = false)}. The server sets them all: the
     * repository fills the key, the owner and the shared scope, stamps the audit times, starts the
     * version and the soft-delete flag ({@link #resetOnCreate}).
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> notInCreateBody(DomainMetadata metadata) {
        Set<String> names = new TreeSet<>(ListQuerySupport.systemFieldNames(metadata));
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (field.readOnly() || !field.inCreate()) {
                    names.add(field.name());
                }
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * One property the create handler sets on the entity it decoded from the body, so a value the
     * body carried never reaches the service.
     *
     * @param field   the entity property
     * @param literal the Java literal the property is set to
     */
    public record Reset(String field, String literal) {}

    /**
     * The properties the create handler resets on the decoded entity, before the service sees it:
     * the audit times and actors, the version and the soft-delete flag, time and actor. A value a
     * service sets afterwards is kept: the repository writes the audit times, version and flag it
     * is handed, and the actors and the deletion time and actor are no column of the insert. The
     * actors and the deletion time and actor are reset only when the entity declares them as
     * fields.
     *
     * @param metadata the entity
     * @return the resets, in a fixed order
     */
    public static List<Reset> resetOnCreate(DomainMetadata metadata) {
        SystemFieldsMetadata declared = metadata.systemFields();
        List<Reset> resets = new ArrayList<>();
        if (metadata.audited()) {
            resets.add(new Reset(ListQuerySupport.role(declared == null ? null : declared.createdAtField(),
                    "createdAt"), NULL));
            resets.add(new Reset(ListQuerySupport.role(declared == null ? null : declared.updatedAtField(),
                    "updatedAt"), NULL));
            addIfDeclared(resets, metadata,
                    ListQuerySupport.role(declared == null ? null : declared.createdByField(), "createdBy"));
            addIfDeclared(resets, metadata,
                    ListQuerySupport.role(declared == null ? null : declared.updatedByField(), "updatedBy"));
        }
        if (metadata.softDelete()) {
            resets.add(new Reset(ListQuerySupport.role(declared == null ? null : declared.softDeleteField(),
                    "deleted"), "false"));
            addIfDeclared(resets, metadata, ListQuerySupport.role(
                    declared == null ? null : declared.softDeleteTimestampField(), "deletedAt"));
            addIfDeclared(resets, metadata, ListQuerySupport.role(
                    declared == null ? null : declared.softDeletedByField(), "deletedBy"));
        }
        if (metadata.versioned()) {
            String version = versionField(metadata);
            resets.add(new Reset(version, versionStart(metadata, version)));
        }
        return List.copyOf(resets);
    }

    private static void addIfDeclared(List<Reset> resets, DomainMetadata metadata, String name) {
        if (metadata.hasFields() && metadata.fields().stream().anyMatch(f -> f.name().equals(name))) {
            resets.add(new Reset(name, NULL));
        }
    }

    /** The version's initial value, as a literal of the declared field's integer width. */
    private static String versionStart(DomainMetadata metadata, String version) {
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (field.name().equals(version) && ("int".equals(field.type())
                        || "Integer".equals(field.type()) || "java.lang.Integer".equals(field.type()))) {
                    return "0";
                }
            }
        }
        return "0L";
    }

    /**
     * The fields whose stored value every update keeps: it neither writes them from the entity nor
     * sets them itself.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> keptOnUpdate(DomainMetadata metadata) {
        Set<String> names = systemFieldsNotWritten(metadata);
        if (metadata.audited()) {
            names.remove(updatedAtField(metadata));
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * The fields whose stored value the update a request body drives keeps: {@link #keptOnUpdate}
     * and the fields a client does not set on update.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> keptOnRequestUpdate(DomainMetadata metadata) {
        Set<String> names = new TreeSet<>(keptOnUpdate(metadata));
        names.addAll(fixedOnRequestUpdate(metadata));
        return Collections.unmodifiableSet(names);
    }

    /**
     * The domain fields the server must set before a row is written: those marked
     * {@code @Field(required = true, readOnly = true)} that play no system role and hold a
     * reference type. A read-only field is out of the create body, so the code that creates the row
     * (a service of the consumer's own) sets it; a null one would reach the database as a
     * {@code NOT NULL} violation. A primitive always holds a value and is not listed.
     *
     * @param metadata the entity
     * @return the fields, in declaration order
     */
    public static List<FieldMetadata> setByServerOnCreate(DomainMetadata metadata) {
        Set<String> systemRoles = ListQuerySupport.systemFieldNames(metadata);
        List<FieldMetadata> fields = new ArrayList<>();
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (field.required() && field.readOnly() && !systemRoles.contains(field.name())
                        && !PRIMITIVE_TYPES.contains(field.type())) {
                    fields.add(field);
                }
            }
        }
        return List.copyOf(fields);
    }

    /**
     * The domain fields a client does not set on update: those marked
     * {@code @Field(readOnly = true)} or {@code @Field(inUpdate = false)} that play no system role.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> fixedOnRequestUpdate(DomainMetadata metadata) {
        Set<String> systemRoles = ListQuerySupport.systemFieldNames(metadata);
        Set<String> names = new TreeSet<>();
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if ((field.readOnly() || !field.inUpdate()) && !systemRoles.contains(field.name())) {
                    names.add(field.name());
                }
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * The name of the version field: the one the {@code systemFields} block declares, else
     * {@code version}. Meaningful for a versioned entity only.
     *
     * @param metadata the entity
     * @return the version field's name
     */
    public static String versionField(DomainMetadata metadata) {
        SystemFieldsMetadata declared = metadata.systemFields();
        String named = declared == null ? null : declared.versionField();
        return named == null || named.isBlank() ? VERSION_DEFAULT : named;
    }

    /** The system-role fields no update writes from the entity: all but the version and shared scope. */
    private static Set<String> systemFieldsNotWritten(DomainMetadata metadata) {
        Set<String> names = new TreeSet<>(ListQuerySupport.systemFieldNames(metadata));
        names.remove(versionField(metadata));
        DataScopeSupport.sharedScopeField(metadata).ifPresent(field -> names.remove(field.name()));
        return names;
    }

    /**
     * The name of the field holding the time of the last write: the one the {@code systemFields}
     * block declares, else {@code updatedAt}. Meaningful for an audited entity only.
     *
     * @param metadata the entity
     * @return the field's name
     */
    public static String updatedAtField(DomainMetadata metadata) {
        SystemFieldsMetadata declared = metadata.systemFields();
        return ListQuerySupport.role(declared == null ? null : declared.updatedAtField(), "updatedAt");
    }

    /**
     * The name of the field holding the creation time: the one the {@code systemFields} block
     * declares, else {@code createdAt}. Meaningful for an audited entity only.
     *
     * @param metadata the entity
     * @return the field's name
     */
    public static String createdAtField(DomainMetadata metadata) {
        SystemFieldsMetadata declared = metadata.systemFields();
        return ListQuerySupport.role(declared == null ? null : declared.createdAtField(), "createdAt");
    }
}
