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
 * it is on (`system-columns.ts`, the twin of the Java side's `SystemColumns`). A field named for a
 * role whose flag is off is an ordinary domain field: both bodies carry it.
 */

import { primaryKeyField } from './primary-key.js';
import { activeSystemRoleFields, systemRoleField } from './system-columns.js';
import { effectiveDataScope, type DomainMetadata } from '../models/domain-model.js';
import { storedMember } from './relationships.js';

/** The version field's name, whether or not the entity is versioned. */
function versionFieldName(metadata: DomainMetadata): string {
  return systemRoleField(metadata, 'VERSION');
}

/** The `@SharedScope` field of a UNIVERSE entity, which the update still writes from the body. */
function sharedScopeFieldName(metadata: DomainMetadata): string | undefined {
  if (effectiveDataScope(metadata) !== 'UNIVERSE') return undefined;
  const declared = metadata.systemFields?.sharedScopeField;
  return declared === undefined || declared.trim() === '' ? undefined : declared;
}

/**
 * Every system field of the entity, the version included: the key, the fields of the roles the
 * flags switch on (`activeSystemRoleFields`) and a UNIVERSE entity's shared scope. The TS twin of
 * `ListQuerySupport#systemFieldNames`. Sorted.
 */
export function systemRoleFieldNames(metadata: DomainMetadata): string[] {
  const names = new Set<string>([primaryKeyField(metadata), ...activeSystemRoleFields(metadata)]);
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
 * The domain fields a client does not set on update: those marked `readOnly` or `inUpdate = false`,
 * and those the record carries no value for (`storedMember`: a `blob` facet, an inverse collection),
 * that play no system role. The request update does not write them and
 * reads their stored value back (ADR-090 Amendments 2 and 3, ADR-108). A field in a system role keeps
 * that role's rule: a read-only version is still sent, and a read-only shared scope is still written.
 * Sorted.
 */
export function fixedOnRequestUpdate(metadata: DomainMetadata): string[] {
  const roles = new Set(systemRoleFieldNames(metadata));
  return metadata.fields
    .filter((f) => (f.readOnly || !f.inUpdate || !storedMember(f, metadata)) && !roles.has(f.name))
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
 * entity's flags switch on), every `readOnly` field, every field marked `inCreate = false`
 * (ADR-090 Amendment 4), every `blob` field (ADR-108) and every inverse collection
 * (`storedMember`). The server sets the first kind itself. The TS twin of the Java side's
 * `ServerOwnedFields#notInCreateBody`. Sorted.
 */
export function omittedFromCreate(metadata: DomainMetadata): string[] {
  const names = new Set<string>(systemRoleFieldNames(metadata));
  for (const field of metadata.fields) {
    if (field.readOnly || !field.inCreate || !storedMember(field, metadata)) names.add(field.name);
  }
  return [...names].sort();
}
