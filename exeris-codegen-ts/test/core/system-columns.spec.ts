/**
 * The system roles of an entity: a role is active only while the entity's flag for it is on, and
 * its field is the name the `systemFields` block declares, else the canonical one. The rules of the
 * Java side's `SystemColumns`.
 */

import { describe, expect, it } from 'vitest';
import {
  SYSTEM_ROLES,
  activeSystemRoleFields,
  isSystemRoleActive,
  systemRoleField,
  type SystemRole,
} from '../../src/core/system-columns.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../src/models/domain-model.js';

function entity(overrides: Partial<DomainMetadata> = {}): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', entityName: 'Order', ...overrides });
}

const NAMED = {
  primaryKeyField: 'id',
  tenantIdField: 'owner',
  createdAtField: 'openedAt',
  createdByField: 'openedBy',
  updatedAtField: 'touchedAt',
  updatedByField: 'touchedBy',
  softDeleteField: 'closed',
  softDeleteTimestampField: 'closedAt',
  softDeletedByField: 'closedBy',
  versionField: 'revision',
};

describe('system-columns — the role order', () => {
  it('lists the roles in the order of the Java enum', () => {
    expect(SYSTEM_ROLES).toEqual([
      'TENANT_ID', 'CREATED_AT', 'CREATED_BY', 'UPDATED_AT', 'UPDATED_BY', 'DELETED', 'DELETED_AT', 'DELETED_BY', 'VERSION',
    ]);
  });
});

describe('system-columns — flags off', () => {
  it('switches no role on for a GLOBAL entity without flags, whatever the block names', () => {
    const metadata = entity({ systemFields: NAMED });
    for (const role of SYSTEM_ROLES) expect(isSystemRoleActive(metadata, role)).toBe(false);
    expect(activeSystemRoleFields(metadata)).toEqual([]);
    expect(activeSystemRoleFields(entity())).toEqual([]);
  });

  it('still names the field of an inactive role', () => {
    expect(systemRoleField(entity({ systemFields: NAMED }), 'VERSION')).toBe('revision');
    expect(systemRoleField(entity(), 'VERSION')).toBe('version');
  });
});

describe('system-columns — flags on', () => {
  const rows: Array<[string, Partial<DomainMetadata>, SystemRole[]]> = [
    ['TENANT data scope', { dataScope: 'TENANT' }, ['TENANT_ID']],
    ['UNIVERSE data scope', { dataScope: 'UNIVERSE' }, ['TENANT_ID']],
    ['tenantScoped without a data scope', { tenantScoped: true }, ['TENANT_ID']],
    ['audited', { audited: true }, ['CREATED_AT', 'CREATED_BY', 'UPDATED_AT', 'UPDATED_BY']],
    ['softDelete', { softDelete: true }, ['DELETED', 'DELETED_AT', 'DELETED_BY']],
    ['versioned', { versioned: true }, ['VERSION']],
  ];
  it.each(rows)('%s switches exactly its roles on', (_title, flags, active) => {
    const metadata = entity(flags);
    for (const role of SYSTEM_ROLES) expect(isSystemRoleActive(metadata, role)).toBe(active.includes(role));
  });

  it('holds the declared fields of the active roles, sorted', () => {
    const metadata = entity({ dataScope: 'TENANT', audited: true, softDelete: true, versioned: true, systemFields: NAMED });
    expect(activeSystemRoleFields(metadata)).toEqual([
      'closed', 'closedAt', 'closedBy', 'openedAt', 'openedBy', 'owner', 'revision', 'touchedAt', 'touchedBy',
    ]);
  });

  it('falls back to the canonical name for a role the block leaves undeclared or blank', () => {
    const metadata = entity({ audited: true, versioned: true, systemFields: { primaryKeyField: 'id', versionField: '  ' } });
    expect(activeSystemRoleFields(metadata)).toEqual(['createdAt', 'createdBy', 'updatedAt', 'updatedBy', 'version']);
  });

  it('never counts the primary key, which is not a role', () => {
    expect(activeSystemRoleFields(entity({ versioned: true }))).not.toContain('id');
  });
});
