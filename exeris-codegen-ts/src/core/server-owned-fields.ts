/**
 * The fields an update (`PUT {base}/{id}`) takes from the server rather than from the request body.
 *
 * The TS twin of the Java side's `ServerOwnedFields#notInUpdateBody` over
 * `ListQuerySupport#systemFieldNames`: the key, the owner, the shared scope, the audit fields
 * (created and updated at and by), the soft-delete fields (the flag, the timestamp, the actor) and
 * every field a `systemFields` role names, except the version. The version stays in the body
 * because an optimistic-lock update matches on the value the client loaded.
 *
 * A role counts under its declared name; the audit roles take their default name only on an
 * `audited` entity and the soft-delete roles only on a `softDelete` one, as on the Java side.
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
  if (sf) {
    for (const declared of [
      sf.primaryKeyField, sf.createdAtField, sf.createdByField, sf.updatedAtField, sf.updatedByField,
      sf.tenantIdField, sf.versionField, sf.softDeleteField, sf.softDeleteTimestampField,
      sf.softDeletedByField, sf.sharedScopeField,
    ]) {
      if (declared !== undefined && declared.trim() !== '') names.add(declared);
    }
  }
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
 * The declared `readOnly` fields that play no system role: the client does not set them, so the
 * request update does not write them and reads their stored value back (ADR-090 Amendment 2). A
 * read-only field in a system role keeps that role's rule: a read-only version is still sent, and
 * a read-only shared scope is still written. Sorted.
 */
export function readOnlyFields(metadata: DomainMetadata): string[] {
  const roles = new Set(systemRoleFieldNames(metadata));
  return metadata.fields.filter((f) => f.readOnly && !roles.has(f.name)).map((f) => f.name).sort();
}

/**
 * The fields the update body does not carry: `notInUpdateBody` less the UNIVERSE shared scope,
 * which the update writes from the body (ADR-090 section 3), plus the read-only fields. Sorted.
 */
export function omittedFromUpdate(metadata: DomainMetadata): string[] {
  const shared = sharedScopeFieldName(metadata);
  const names = new Set([...notInUpdateBody(metadata).filter((name) => name !== shared), ...readOnlyFields(metadata)]);
  return [...names].sort();
}
