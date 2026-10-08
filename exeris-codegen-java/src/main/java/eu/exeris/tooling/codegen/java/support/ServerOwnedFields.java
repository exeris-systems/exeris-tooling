package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The fields an update does not write from the entity it is handed.
 *
 * <p>An update replaces every domain field with the entity's value, except two kinds of field:
 * <ul>
 *   <li>the fields that play a system role — the primary key, the owning tenant, the audit fields
 *       (created and updated at and by) and the soft-delete fields: every field
 *       {@link ListQuerySupport#systemFieldNames} names except the version, which the caller passes
 *       as the expected version an optimistic-lock update matches on, and a UNIVERSE entity's
 *       shared scope, which the update writes (ADR-090 §3). No update path writes them from the
 *       entity;</li>
 *   <li>the domain fields marked {@code @Field(readOnly = true)}: a client does not set them, so the
 *       update a request body drives ({@code PUT {base}/{id}}) keeps their stored value, while the
 *       update an action's entity method drives writes them (ADR-090, Amendment 2). A field in a
 *       system role keeps that role's rule whether or not it is also marked read-only.</li>
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
 *       and the read-only fields ({@link #readOnlyFields}).</li>
 * </ul>
 *
 * <p>The version and the shared scope are in none of them: every update writes both, the version as
 * the value the caller passes plus one.
 *
 * @since 0.10
 */
public final class ServerOwnedFields {

    private static final String UPDATED_AT_DEFAULT = "updatedAt";
    private static final String VERSION_DEFAULT = "version";

    private ServerOwnedFields() {}

    /**
     * The fields the update request body does not carry.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> notInUpdateBody(DomainMetadata metadata) {
        Set<String> names = systemFieldsNotWritten(metadata);
        names.addAll(readOnlyFields(metadata));
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
     * and the read-only fields.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> keptOnRequestUpdate(DomainMetadata metadata) {
        Set<String> names = new TreeSet<>(keptOnUpdate(metadata));
        names.addAll(readOnlyFields(metadata));
        return Collections.unmodifiableSet(names);
    }

    /**
     * The domain fields marked {@code @Field(readOnly = true)} that play no system role.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> readOnlyFields(DomainMetadata metadata) {
        Set<String> systemRoles = ListQuerySupport.systemFieldNames(metadata);
        Set<String> names = new TreeSet<>();
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (field.readOnly() && !systemRoles.contains(field.name())) {
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
