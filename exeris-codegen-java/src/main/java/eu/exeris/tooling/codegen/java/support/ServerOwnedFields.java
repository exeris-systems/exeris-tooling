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
 * {@link #notInCreateBody}, since the create stamps it from the bound scope.
 *
 * @since 0.10
 */
public final class ServerOwnedFields {

    private static final String UPDATED_AT_DEFAULT = "updatedAt";
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
     * The fields the create request body does not carry: the key, which the repository fills, the
     * owner and a UNIVERSE entity's shared scope, which it stamps from the bound storage context,
     * the read-only fields and the fields marked {@code @Field(inCreate = false)}.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> notInCreateBody(DomainMetadata metadata) {
        Set<String> names = new TreeSet<>();
        names.add(PrimaryKeys.field(metadata));
        DataScopeSupport.ownerFieldName(metadata).ifPresent(names::add);
        DataScopeSupport.sharedScopeField(metadata).ifPresent(field -> names.add(field.name()));
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

    private static String updatedAtField(DomainMetadata metadata) {
        SystemFieldsMetadata declared = metadata.systemFields();
        String named = declared == null ? null : declared.updatedAtField();
        return named == null || named.isBlank() ? UPDATED_AT_DEFAULT : named;
    }
}
