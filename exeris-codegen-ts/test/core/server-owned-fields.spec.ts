/**
 * The TS request bodies against the Java ones. Each `java-*-properties.json` holds, per fixture, the
 * sorted property names of the OpenAPI schema the Java `OpenApiComponentsBuilder` produces over the
 * metadata files of `exeris-metadata/`: `java-entity-properties.json` the entity schema,
 * `java-create-dto-properties.json` its `<Entity>CreateDto` and `java-update-dto-properties.json`
 * its `<Entity>UpdateDto`. The emitted TS `<Entity>`, `<Entity>Create` and `<Entity>Update` types and
 * their Zod schemas carry exactly those properties, for a local entity and for a peer's.
 *
 * To regenerate a Java file, run `OpenApiComponentsBuilder.buildComponents` over each metadata file
 * (loaded with `MetadataLoader`) and write the sorted property names of the schema.
 */

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { DEFAULT_CONFIG } from '../../src/config.js';
import { createGeneratorContext } from '../../src/core/generator-registry.js';
import { withPrimaryKey } from '../../src/core/primary-key.js';
import { fixedOnRequestUpdate, notInUpdateBody, omittedFromCreate, omittedFromUpdate } from '../../src/core/server-owned-fields.js';
import { generatePeerTypes } from '../../src/generators/api/peer-type-gen.js';
import { TypeGenerator, updateOmittedFields } from '../../src/generators/api/type-gen.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../src/models/domain-model.js';
import { DslMapper } from '../../src/models/dsl-mapper.js';

const FIXTURES = join(dirname(fileURLToPath(import.meta.url)), '../fixtures/update-parity');
const javaProperties = (file: string): Record<string, string[]> =>
  JSON.parse(readFileSync(join(FIXTURES, file), 'utf8'));
const javaEntity = javaProperties('java-entity-properties.json');
const javaCreateDto = javaProperties('java-create-dto-properties.json');
const javaUpdateDto = javaProperties('java-update-dto-properties.json');
const fixtures: DomainMetadata[] = readdirSync(join(FIXTURES, 'exeris-metadata'))
  .sort()
  .map((file) => DomainMetadataSchema.parse(JSON.parse(readFileSync(join(FIXTURES, 'exeris-metadata', file), 'utf8'))));
const named = fixtures.map((m) => [m.entityName, m] as const);

const generator = new TypeGenerator();
const context = createGeneratorContext({ generateZod: true });

/** An entity's `.types.ts` and `.schema.ts` text. */
interface Emitted {
  readonly types: string;
  readonly schema: string;
}

/** What the local generator writes for the entity, its key added as the orchestrator adds it. */
function emitLocal(metadata: DomainMetadata): Emitted {
  const domain = withPrimaryKey(metadata);
  const schemaPath = `schemas/${DslMapper.toKebabCase(domain.entityName)}.schema.ts`;
  return {
    types: generator.generate(domain, context)!.content,
    schema: generator.generateAggregate([domain], context).find((f) => f.path === schemaPath)!.content,
  };
}

/** What the peer generator writes for the entity. */
function emitPeer(metadata: DomainMetadata): Emitted {
  const domain = withPrimaryKey(metadata);
  const kebab = DslMapper.toKebabCase(domain.entityName);
  const files = generatePeerTypes(
    { name: 'parity', domains: [domain], enums: [] },
    { ...DEFAULT_CONFIG, generateZod: true },
  );
  return {
    types: files.find((f) => f.path.endsWith(`types/${kebab}.types.ts`))!.content,
    schema: files.find((f) => f.path.endsWith(`schemas/${kebab}.schema.ts`))!.content,
  };
}

/** The member names of `export interface <name> {`. */
function interfaceMembers(types: string, name: string): string[] {
  const start = types.indexOf(`export interface ${name} {`);
  expect(start, `interface ${name}`).toBeGreaterThan(-1);
  const body = types.slice(start, types.indexOf('\n}', start));
  return [...body.matchAll(/^ {2}(\w+)\??:/gm)].map((m) => m[1]!);
}

/** The member names of `export const <name> = z.object({`. */
function schemaMembers(schema: string, name: string): string[] {
  const start = schema.indexOf(`export const ${name} = z.object({`);
  expect(start, `schema ${name}`).toBeGreaterThan(-1);
  const body = schema.slice(start, schema.indexOf('\n});', start));
  return [...body.matchAll(/^ {2}(\w+):/gm)].map((m) => m[1]!);
}

/** The declaration of `export const <name> = …;`, from its first line to its closing `;`. */
function schemaDeclaration(schema: string, name: string): string {
  const start = schema.indexOf(`export const ${name} = `);
  expect(start, `schema ${name}`).toBeGreaterThan(-1);
  return schema.slice(start, schema.indexOf(';\n', start));
}

/** The keys of the `.omit({ … })` in a schema declaration. */
function omittedKeys(declaration: string): string[] {
  const omit = /\.omit\(\{([^}]*)\}\)/.exec(declaration);
  return omit ? [...omit[1]!.matchAll(/(\w+): true/g)].map((m) => m[1]!) : [];
}

/** The key the `.extend({ … })` of a schema declaration adds, if any. */
function extendedKey(declaration: string): string[] {
  const extend = /\.extend\(\{ (\w+):/.exec(declaration);
  return extend ? [extend[1]!] : [];
}

/** The property names `<Entity>Update` declares: the record less its `Omit`, plus the `& { version: … }`. */
function updateTypeProperties(types: string, entity: string, record: string[]): string[] {
  const declaration = new RegExp(`export type ${entity}Update = ([^;]*);`).exec(types)![1]!;
  const omit = /^Omit<\w+, ([^>]*)>/.exec(declaration);
  const dropped = omit ? [...omit[1]!.matchAll(/'(\w+)'/g)].map((m) => m[1]!) : [];
  const added = /& \{ (\w+):/.exec(declaration);
  return [...new Set([...record.filter((name) => !dropped.includes(name)), ...(added ? [added[1]!] : [])])];
}

function expectSameSet(ts: string[], java: string[]): void {
  expect(ts.filter((p) => !java.includes(p)), 'in TS, not in Java').toEqual([]);
  expect(java.filter((p) => !ts.includes(p)), 'in Java, not in TS').toEqual([]);
  expect(new Set(ts).size, 'a property listed twice').toBe(ts.length);
}

describe('every fixture has a Java property set for each schema', () => {
  it.each([
    ['entity', javaEntity],
    ['create', javaCreateDto],
    ['update', javaUpdateDto],
  ] as const)('%s', (_kind, java) => {
    expect(fixtures.map((m) => m.entityName).sort()).toEqual(Object.keys(java).sort());
  });
});

describe.each([
  ['local', emitLocal],
  ['peer', emitPeer],
] as const)('the %s TS types and schemas against the Java ones', (_scope, emit) => {
  describe('the update carries exactly the properties of the Java UpdateDto', () => {
    it.each(named)('%s', (name, metadata) => {
      const { types, schema } = emit(metadata);
      const declaration = schemaDeclaration(schema, `${name}UpdateSchema`);
      const ofSchema = [...new Set([
        ...schemaMembers(schema, `${name}Schema`).filter((p) => !omittedKeys(declaration).includes(p)),
        ...extendedKey(declaration),
      ])];

      expectSameSet(updateTypeProperties(types, name, interfaceMembers(types, name)), javaUpdateDto[name]!);
      expectSameSet(ofSchema, javaUpdateDto[name]!);
    });
  });

  describe('the create carries exactly the properties of the Java CreateDto', () => {
    it.each(named)('%s', (name, metadata) => {
      const { types, schema } = emit(metadata);
      const omitted = omittedKeys(schemaDeclaration(schema, `${name}CreateSchema`));

      expectSameSet(interfaceMembers(types, `${name}Create`), javaCreateDto[name]!);
      expectSameSet(schemaMembers(schema, `${name}Schema`).filter((p) => !omitted.includes(p)), javaCreateDto[name]!);
    });
  });

  describe('the entity read type carries the properties of the Java entity schema', () => {
    it.each(named)('%s', (name, metadata) => {
      const { types, schema } = emit(metadata);
      expectSameSet(interfaceMembers(types, name), javaEntity[name]!);
      expectSameSet(schemaMembers(schema, `${name}Schema`), javaEntity[name]!);
    });
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
    expect(fixedOnRequestUpdate(byName('ReadOnlyFields'))).toEqual(['lockedUntil', 'status']);
  });

  it('keep a read-only version and a read-only shared scope in the body, under their roles', () => {
    expect(javaUpdateDto['ReadOnlyFields']).toEqual(['scopeId', 'title', 'version']);
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

describe('the fields an update takes from the server, by flag', () => {
  const byName = (name: string): DomainMetadata => fixtures.find((m) => m.entityName === name)!;
  const omitted = (name: string): string[] => [...updateOmittedFields(byName(name))].sort();

  it('are the inUpdate = false fields, as the read-only ones', () => {
    expect(omitted('InUpdateFalse')).toEqual(['id', 'sku']);
    expect(fixedOnRequestUpdate(byName('InUpdateFalse'))).toEqual(['sku']);
    expect(omitted('BothFlagsFalse')).toEqual(['id', 'sealed']);
  });

  it('leave an inCreate = false field in the update, and a field with explicit true flags', () => {
    expect(omitted('InCreateFalse')).toEqual(['id']);
    expect(omitted('FlagsExplicitTrue')).toEqual(['id']);
  });

  it('leave the version and the UNIVERSE shared scope in the body whatever their flags', () => {
    expect(omitted('KeyAndRolesWithFlags')).toEqual(['createdAt', 'id', 'tenantId', 'updatedAt']);
    expect(omitted('UniverseWithFlags')).toEqual(['id', 'sku', 'tenantId']);
  });

  it('read an absent flag as true', () => {
    expect(omitted('Plain')).toEqual(['id']);
  });
});

describe('the fields a create takes from the server', () => {
  const byName = (name: string): DomainMetadata => fixtures.find((m) => m.entityName === name)!;
  const omitted = (name: string): string[] => omittedFromCreate(byName(name));

  it('are the key alone for an entity with no flag and no role', () => {
    expect(omitted('Plain')).toEqual(['id']);
    expect(omitted('FlagsExplicitTrue')).toEqual(['id']);
  });

  it('are the owner and the UNIVERSE shared scope besides the key', () => {
    expect(omitted('TenantDefault')).toEqual(['id', 'tenantId']);
    expect(omitted('TenantRenamed')).toEqual(['id', 'ownerId']);
    expect(omitted('UniverseDefault')).toEqual(['id', 'scopeId', 'tenantId']);
  });

  it('are the read-only fields and the inCreate = false fields', () => {
    expect(omitted('InCreateFalse')).toEqual(['id', 'trackingCode']);
    expect(omitted('BothFlagsFalse')).toEqual(['id', 'sealed']);
    expect(omitted('RequiredReadOnly')).toEqual(['id', 'status']);
    expect(omitted('ReadOnlyFields')).toEqual(['id', 'lockedUntil', 'scopeId', 'status', 'tenantId', 'version']);
  });

  it('leave an inUpdate = false field in the create', () => {
    expect(omitted('InUpdateFalse')).toEqual(['id']);
  });

  it('are the audit, version and soft-delete fields, which the server sets, under their role names', () => {
    expect(omitted('AuditedDefault')).toEqual(['createdAt', 'createdBy', 'id', 'updatedAt', 'updatedBy']);
    expect(omitted('AuditedRenamed')).toEqual(['author', 'born', 'editor', 'id', 'touched']);
    expect(omitted('VersionedDefault')).toEqual(['id', 'version']);
    expect(omitted('VersionedRenamed')).toEqual(['id', 'rev']);
    expect(omitted('SoftDeleteDefault')).toEqual(['deleted', 'deletedAt', 'deletedBy', 'id']);
    expect(omitted('KeyAndRolesWithFlags')).toEqual(['createdAt', 'createdBy', 'id', 'tenantId', 'updatedAt', 'updatedBy', 'version']);
  });

  it('keep a field that only has a role\'s name, and a stamp of an entity that is not audited', () => {
    expect(omitted('UnauditedDeclaresStamps')).toEqual(['id']);
    expect(omitted('SoftDeleteRenamed')).toEqual(['gone', 'goneAt', 'goneBy', 'id']);
    expect(omitted('VersionedOpenCreate')).toEqual(['id', 'version']);
  });

  it('follow a renamed key', () => {
    expect(omitted('RenamedKey')).toEqual(['orderNo']);
  });
});
