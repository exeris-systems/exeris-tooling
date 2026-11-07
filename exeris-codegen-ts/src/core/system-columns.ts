/**
 * The system roles of an entity: which are switched on, and the field each one is held in. The TS
 * twin of the Java side's `SystemColumns`, rule for rule.
 *
 * A role is active only when the entity's flag for it is on: the owning tenant when the rows are
 * tenant-partitioned (`TENANT` or `UNIVERSE` data scope), the audit times and actors when
 * `audited`, the soft-delete flag, time and actor when `softDelete`, the version when `versioned`.
 * A field that a `systemFields` block names for a role, or that carries the role's canonical name,
 * on an entity whose flag for that role is off is an ordinary domain field: the request types carry
 * it, the list query sorts and filters on it, and the views render it like any other field.
 *
 * The primary key (`primaryKeyField`) is a system field whatever the flags say, and is not a role.
 */

import { isTenantPartitioned, type DomainMetadata, type SystemFieldsMetadata } from '../models/domain-model.js';

/** A system role, in the order of the Java `SystemColumns.Role` enum. */
export type SystemRole =
  | 'TENANT_ID'
  | 'CREATED_AT'
  | 'CREATED_BY'
  | 'UPDATED_AT'
  | 'UPDATED_BY'
  | 'DELETED'
  | 'DELETED_AT'
  | 'DELETED_BY'
  | 'VERSION';

/** The entity properties the role rules read. */
export type SystemRoleSubject = Pick<
  DomainMetadata,
  'dataScope' | 'tenantScoped' | 'systemFields' | 'audited' | 'softDelete' | 'versioned'
>;

/** Each role's canonical field name and the `systemFields` entry that renames it. */
const ROLES: ReadonlyArray<readonly [SystemRole, string, keyof SystemFieldsMetadata]> = [
  ['TENANT_ID', 'tenantId', 'tenantIdField'],
  ['CREATED_AT', 'createdAt', 'createdAtField'],
  ['CREATED_BY', 'createdBy', 'createdByField'],
  ['UPDATED_AT', 'updatedAt', 'updatedAtField'],
  ['UPDATED_BY', 'updatedBy', 'updatedByField'],
  ['DELETED', 'deleted', 'softDeleteField'],
  ['DELETED_AT', 'deletedAt', 'softDeleteTimestampField'],
  ['DELETED_BY', 'deletedBy', 'softDeletedByField'],
  ['VERSION', 'version', 'versionField'],
];

/** Every system role, in the order of the Java `SystemColumns.Role` enum. */
export const SYSTEM_ROLES: readonly SystemRole[] = ROLES.map(([role]) => role);

/** Whether the entity's flags switch `role` on. */
export function isSystemRoleActive(metadata: SystemRoleSubject, role: SystemRole): boolean {
  switch (role) {
    case 'TENANT_ID':
      return isTenantPartitioned(metadata);
    case 'CREATED_AT':
    case 'CREATED_BY':
    case 'UPDATED_AT':
    case 'UPDATED_BY':
      return metadata.audited === true;
    case 'DELETED':
    case 'DELETED_AT':
    case 'DELETED_BY':
      return metadata.softDelete === true;
    case 'VERSION':
      return metadata.versioned === true;
  }
}

/**
 * The name of the field holding `role`: the name the `systemFields` block declares when present and
 * non-blank, else the canonical name. Whether or not the role is active.
 */
export function systemRoleField(metadata: Pick<DomainMetadata, 'systemFields'>, role: SystemRole): string {
  const [, canonical, entry] = ROLES.find(([r]) => r === role)!;
  const named = metadata.systemFields?.[entry];
  return named === undefined || named.trim() === '' ? canonical : named;
}

/** The fields of every active role, sorted. */
export function activeSystemRoleFields(metadata: SystemRoleSubject): string[] {
  const names = new Set<string>();
  for (const role of SYSTEM_ROLES) {
    if (isSystemRoleActive(metadata, role)) names.add(systemRoleField(metadata, role));
  }
  return [...names].sort();
}
