/**
 * The TS update contract against the Java one. `java-update-dto-properties.json` holds the property
 * set of each fixture's OpenAPI `<Entity>UpdateDto`, produced by the Java `OpenApiComponentsBuilder`
 * over the metadata files beside it; the TS `<Entity>Update` carries exactly those properties.
 *
 * To regenerate the Java file, run `OpenApiComponentsBuilder.buildComponents` over each metadata file
 * of `exeris-metadata/` (loaded with `MetadataLoader`) and write the sorted property names of its
 * `<Entity>UpdateDto` schema.
 */

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { notInUpdateBody, omittedFromUpdate, readOnlyFields } from '../../src/core/server-owned-fields.js';
import { updateOmittedFields, updateVersionField } from '../../src/generators/api/type-gen.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../src/models/domain-model.js';

const FIXTURES = join(dirname(fileURLToPath(import.meta.url)), '../fixtures/update-parity');
const javaUpdateDto: Record<string, string[]> = JSON.parse(
  readFileSync(join(FIXTURES, 'java-update-dto-properties.json'), 'utf8'),
);
const fixtures: DomainMetadata[] = readdirSync(join(FIXTURES, 'exeris-metadata'))
  .sort()
  .map((file) => DomainMetadataSchema.parse(JSON.parse(readFileSync(join(FIXTURES, 'exeris-metadata', file), 'utf8'))));

/** The properties of the emitted `<Entity>Update`: the declared fields not omitted, plus the version. */
function updateProperties(metadata: DomainMetadata): string[] {
  const omitted = new Set(updateOmittedFields(metadata));
  const names = new Set(metadata.fields.map((f) => f.name).filter((name) => !omitted.has(name)));
  const version = updateVersionField(metadata);
  if (version) names.add(version.name);
  return [...names].sort();
}

describe('the TS update carries exactly the properties of the Java UpdateDto', () => {
  it('has a Java property set for every fixture', () => {
    expect(fixtures.map((m) => m.entityName).sort()).toEqual(Object.keys(javaUpdateDto).sort());
  });

  it.each(fixtures.map((m) => [m.entityName, m] as const))('%s', (name, metadata) => {
    const java = javaUpdateDto[name]!;
    const ts = updateProperties(metadata);

    expect(ts.filter((p) => !java.includes(p))).toEqual([]);
    expect(java.filter((p) => !ts.includes(p))).toEqual([]);
  });
});

describe('the fields an update takes from the server', () => {
  const byName = (name: string): DomainMetadata => fixtures.find((m) => m.entityName === name)!;
  const omitted = (name: string): string[] => [...updateOmittedFields(byName(name))].sort();

  it('are the key alone for an entity with no system role', () => {
    expect(omitted('Plain')).toEqual(['id']);
  });

  it('are the audit fields of an audited entity, under their declared names', () => {
    expect(omitted('AuditedDefault')).toEqual(['createdAt', 'createdBy', 'id', 'updatedAt', 'updatedBy']);
    expect(omitted('AuditedRenamed')).toEqual(['author', 'born', 'editor', 'id', 'touched']);
  });

  it('are the soft-delete fields of a soft-delete entity, under their declared names', () => {
    expect(omitted('SoftDeleteDefault')).toEqual(['deleted', 'deletedAt', 'deletedBy', 'id']);
    expect(omitted('SoftDeleteRenamed')).toEqual(['gone', 'goneAt', 'goneBy', 'id']);
  });

  it('leave the version in the body, under its declared name', () => {
    expect(omitted('VersionedDefault')).toEqual(['id']);
    expect(omitted('VersionedRenamed')).toEqual(['id']);
  });

  it('are the owner of a tenant-partitioned entity, under its declared name', () => {
    expect(omitted('TenantDefault')).toEqual(['id', 'tenantId']);
    expect(omitted('TenantRenamed')).toEqual(['id', 'ownerId']);
  });

  it('leave a UNIVERSE entity\'s shared scope in the body, which the update writes', () => {
    expect(omitted('UniverseDefault')).toEqual(['id', 'tenantId']);
    expect(omitted('UniverseRenamed')).toEqual(['id', 'ownerId']);
    expect(notInUpdateBody(byName('UniverseDefault'))).toContain('scopeId');
    expect(omittedFromUpdate(byName('UniverseDefault'))).not.toContain('scopeId');
  });

  it('are the read-only fields that play no system role', () => {
    expect(omitted('ReadOnlyFields')).toEqual(['id', 'lockedUntil', 'status', 'tenantId']);
    expect(readOnlyFields(byName('ReadOnlyFields'))).toEqual(['lockedUntil', 'status']);
  });

  it('keep a read-only version and a read-only shared scope in the body, under their roles', () => {
    expect(updateProperties(byName('ReadOnlyFields'))).toEqual(['scopeId', 'title', 'version']);
  });

  it('follow a renamed key', () => {
    expect(omitted('RenamedKey')).toEqual(['orderNo']);
  });

  it('are every role at once', () => {
    expect(omitted('Everything')).toEqual([
      'createdAt', 'createdBy', 'deleted', 'deletedAt', 'deletedBy', 'orderNo', 'ownerId', 'updatedAt', 'updatedBy',
    ]);
    expect(omitted('EverythingRenamed')).toEqual(['c1', 'c2', 'd0', 'd1', 'd2', 'org', 'pk', 'u1', 'u2']);
  });

  it('are the stamps a block renames even when the entity is not audited, and a GLOBAL entity\'s declared tenant field', () => {
    expect(omitted('UnauditedDeclaresStamps')).toEqual(['id']);
    expect(omitted('GlobalDeclaresTenantId')).toEqual(['id']);
    expect(omitted('GlobalNamesTenantIdField')).toEqual(['id', 'tenantId']);
  });
});
