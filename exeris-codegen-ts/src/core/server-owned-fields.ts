/**
 * The fields an update (`PUT {base}/{id}`) takes from the server rather than from the request body.
 *
 * The TS twin of the Java side's `ServerOwnedFields#notInUpdateBody` over
 * `ListQuerySupport#systemFieldNames`: the key, the owner, a UNIVERSE entity's shared scope, the
 * audit fields (created and updated at and by) and the soft-delete fields (the flag, the timestamp,
 * the actor), except the version. The version stays in the body because an optimistic-lock update
 * matches on the value the client loaded.
 *
 * A role counts under its declared name, else its default one, and only when the entity's flag for
 * it is on: the audit roles on an `audited` entity, the soft-delete roles on a `softDelete` one, the
 * version on a `versioned` one, as on the Java side (`SystemColumns`). A field named for a role
 * whose flag is off is an ordinary domain field: both bodies carry it.
 */

import { primaryKeyField } from './primary-key.js';
import { effectiveDataScope, ownerFieldName, type DomainMetadata } from '../models/domain-model.js';

/** A role's name: the declared one, or the default when none or a blank one is declared. */
function role(declared: string | undefined, fallback: string): string {
  return declared === undefined || declared.trim() === '' ? fallback : declared;
}

/** The version field's name, whether or not the entity is versioned. */
function versionFieldName(metadata: DomainMetadata): string {
  return role(metadata.systemFields?.versionField, 'version');
}

/** The `@SharedScope` field of a UNIVERSE entity, which the update still writes from the body. */
function sharedScopeFieldName(metadata: DomainMetadata): string | undefined {
  if (effectiveDataScope(metadata) !== 'UNIVERSE') return undefined;
  const declared = metadata.systemFields?.sharedScopeField;
  return declared === undefined || declared.trim() === '' ? undefined : declared;
}

/**
 * Every system-role field of the entity, the version included: the TS twin of
 * `ListQuerySupport#systemFieldNames`. Sorted.
 */
export function systemRoleFieldNames(metadata: DomainMetadata): string[] {
  const sf = metadata.systemFields;
  const names = new Set<string>([primaryKeyField(metadata)]);
  const owner = ownerFieldName(metadata);
  if (owner) names.add(owner);
  if (metadata.audited) {
    names.add(role(sf?.createdAtField, 'createdAt'));
    names.add(role(sf?.createdByField, 'createdBy'));
    names.add(role(sf?.updatedAtField, 'updatedAt'));
    names.add(role(sf?.updatedByField, 'updatedBy'));
  }
  if (metadata.versioned) names.add(versionFieldName(metadata));
  if (metadata.softDelete) {
    names.add(role(sf?.softDeleteField, 'deleted'));
    names.add(role(sf?.softDeleteTimestampField, 'deletedAt'));
    names.add(role(sf?.softDeletedByField, 'deletedBy'));
  }
  const shared = sharedScopeFieldName(metadata);
  if (shared) names.add(shared);
  return [...names].sort();
}

/**
 * The fields the update body does not carry, as the Java side's `notInUpdateBody` names them:
 * every system-role field but the version. Sorted.
 */
export function notInUpdateBody(metadata: DomainMetadata): string[] {
  const version = versionFieldName(metadata);
  return systemRoleFieldNames(metadata).filter((name) => name !== version);
}

/**
 * The domain fields a client does not set on update: those marked `readOnly`, `inUpdate = false`
 * or carrying a `blob` facet that play no system role. The request update does not write them and
 * reads their stored value back (ADR-090 Amendments 2 and 3, ADR-108). A field in a system role keeps
 * that role's rule: a read-only version is still sent, and a read-only shared scope is still written.
 * Sorted.
 */
export function fixedOnRequestUpdate(metadata: DomainMetadata): string[] {
  const roles = new Set(systemRoleFieldNames(metadata));
  return metadata.fields
    .filter((f) => (f.readOnly || !f.inUpdate || f.blob !== undefined) && !roles.has(f.name))
    .map((f) => f.name)
    .sort();
}

/**
 * The fields the update body does not carry: `notInUpdateBody` less the UNIVERSE shared scope,
 * which the update writes from the body (ADR-090 section 3), plus the fields a client does not set
 * on update. Sorted.
 */
export function omittedFromUpdate(metadata: DomainMetadata): string[] {
  const shared = sharedScopeFieldName(metadata);
  const names = new Set([...notInUpdateBody(metadata).filter((name) => name !== shared), ...fixedOnRequestUpdate(metadata)]);
  return [...names].sort();
}

/**
 * The fields the create body does not carry: every field that plays a system role (the key, the
 * owner, a UNIVERSE entity's shared scope, and the audit, version and soft-delete fields the
 * entity's flags switch on), every `readOnly` field, every field
 * marked `inCreate = false` (ADR-090 Amendment 4) and every `blob` field (ADR-108). The server sets
 * the first kind itself. The TS twin of the Java side's `ServerOwnedFields#notInCreateBody`. Sorted.
 */
export function omittedFromCreate(metadata: DomainMetadata): string[] {
  const names = new Set<string>(systemRoleFieldNames(metadata));
  for (const field of metadata.fields) {
    if (field.readOnly || !field.inCreate || field.blob !== undefined) names.add(field.name);
  }
  return [...names].sort();
}
