/**
 * No emitted file names a permission the author did not declare, and none admits a caller by role.
 * The backend route policy has no role kind, so a frontend role bypass admits a user whom the edge
 * refuses; a permission constant derived from an entity name is a name no declaration backs.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../src/orchestrator.js';
import { DomainMetadataSchema } from '../src/models/domain-model.js';
import { DEFAULT_CONFIG } from '../src/config.js';

const order = DomainMetadataSchema.parse({
  entityName: 'Order',
  packageName: 'com.shop',
  realTimeApi: true,
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'note', type: 'String', required: true },
  ],
  actions: [{ name: 'markPaid', methodName: 'markPaid' }],
});

const audit = DomainMetadataSchema.parse({
  entityName: 'AuditLog',
  packageName: 'com.shop',
  internalApi: { readOnly: true, internal: false },
  fields: [{ name: 'id', type: 'java.util.UUID' }],
});

describe('emitted output', () => {
  const files = buildGeneratedFiles([order, audit], [], { ...DEFAULT_CONFIG, generateTests: true });

  it.each([
    ['a <ENTITY>_PERMISSIONS constant', /[A-Z]+_PERMISSIONS\b/],
    ['an entity-derived permission string', /'[a-z_]+:(read|create|update|delete)'/],
    ['a role check', /\bhasRole\s*\(/],
    ['the ADMIN role bypass', /'ADMIN'/],
  ])('no generated file contains %s', (_label, pattern) => {
    expect(files.length).toBeGreaterThan(0);
    expect(files.filter((f) => pattern.test(f.content)).map((f) => f.path)).toEqual([]);
  });
});
