package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The fields an update ({@code PUT {base}/{id}}) takes from the server rather than from the
 * request body.
 *
 * <p>An update replaces every domain field with the body's value. The fields that play a system
 * role are not the body's to write: the primary key, the owning tenant, the audit fields (created
 * and updated at and by), the soft-delete fields and a UNIVERSE entity's shared scope — every field
 * {@link ListQuerySupport#systemFieldNames} names except the version, which the body carries as the
 * expected version an optimistic-lock update matches on.
 *
 * <p>Two sets follow from that, and they differ by design:
 * <ul>
 *   <li>{@link #notInUpdateBody} — what the published update schema leaves out;</li>
 *   <li>{@link #keptOnUpdate} — what the {@code UPDATE} statement does not write, so the stored
 *       value stays. It is the first set without the fields the statement writes from a server
 *       value or by ADR-090's rule: the update stamp of an audited entity ({@code Instant.now()})
 *       and the shared scope, which an owner may move its own row between.</li>
 * </ul>
 *
 * <p>The version is in neither set: the update matches on the value the body carries and writes
 * that value plus one.
 *
 * @since 0.10
 */
public final class ServerOwnedFields {

    private static final String UPDATED_AT_DEFAULT = "updatedAt";

    private ServerOwnedFields() {}

    /**
     * The fields the update request body does not carry.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> notInUpdateBody(DomainMetadata metadata) {
        Set<String> names = new TreeSet<>(ListQuerySupport.systemFieldNames(metadata));
        names.remove(versionField(metadata));
        return Collections.unmodifiableSet(names);
    }

    /**
     * The fields whose stored value an update keeps: it neither writes them from the body nor sets
     * them itself.
     *
     * @param metadata the entity
     * @return the field names, sorted
     */
    public static Set<String> keptOnUpdate(DomainMetadata metadata) {
        Set<String> names = new TreeSet<>(notInUpdateBody(metadata));
        if (metadata.audited()) {
            names.remove(updatedAtField(metadata));
        }
        DataScopeSupport.sharedScopeField(metadata).ifPresent(field -> names.remove(field.name()));
        return Collections.unmodifiableSet(names);
    }

    private static String versionField(DomainMetadata metadata) {
        SystemFieldsMetadata declared = metadata.systemFields();
        String named = declared == null ? null : declared.versionField();
        return named == null || named.isBlank() ? "version" : named;
    }

    private static String updatedAtField(DomainMetadata metadata) {
        SystemFieldsMetadata declared = metadata.systemFields();
        String named = declared == null ? null : declared.updatedAtField();
        return named == null || named.isBlank() ? UPDATED_AT_DEFAULT : named;
    }
}
