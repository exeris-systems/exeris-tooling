/**
 * Coverage for src/generators/api/type-gen.ts — TypeGenerator (per-domain
 * interface + DTO + aggregate Zod-schema emission) and the generateTypes
 * convenience function.
 *
 * The file is the largest in src/generators/api (~380 LOC) with several
 * non-trivial helpers: collectEnumTypes (12-branch heuristic), buildZodType
 * (validation-chain builder + format-driven overrides), getSystemFieldNames
 * (idField alias + default-set fallback), and the create-DTO field filter
 * (excludes system + lifecycle + inCreate=false).
 */

import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import {
  TypeGenerator,
  auditFieldNames,
  buildZodType,
  generateTypes,
  systemFieldNames,
  viewSystemFieldNames,
} from '../../../src/generators/api/type-gen.js';
import {
  createGeneratorContext,
  type GeneratorContext,
} from '../../../src/core/generator-registry.js';
import {
  DomainMetadataSchema,
  FieldMetadataSchema,
  type DomainMetadata,
  type FieldMetadata,
} from '../../../src/models/domain-model.js';

const CTX: GeneratorContext = createGeneratorContext({});

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

function field(overrides: Partial<FieldMetadata> & { name: string; type: string }): FieldMetadata {
  return FieldMetadataSchema.parse(overrides);
}

// ---------- CodeGenerator contract ----------

describe('TypeGenerator — CodeGenerator metadata', () => {
  const gen = new TypeGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('TypeGenerator');
    expect(gen.artifactType).toBe('TYPE');
    expect(gen.priority).toBe(2);
    expect(gen.supportedBackends).toEqual([]);
  });
});

// ---------- generate — per-domain interface emission ----------

describe('TypeGenerator.generate — per-domain interface emission', () => {
  const gen = new TypeGenerator();

  it('emits types/<kebab>.types.ts for a visible domain', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine' }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('types/order-line.types.ts');
    expect(file!.artifactType).toBe('TYPE');
    expect(file!.overwritable).toBe(true);
  });

  it('declares the entity under its own name, "Entity" suffix and all (T40)', () => {
    const content = gen.generate(domain({ entityName: 'CustomerEntity' }), CTX)!.content;
    // This spec used to pin the opposite: `toInterfaceName` stripped the suffix here, so the
    // types module declared `Customer` while every other emitter imported `CustomerEntity` from
    // it. The strip was only ever visible to this test — an app with a `*Entity` domain did not
    // compile — so the fix is one name, decided in one place, for declarer and importer alike.
    expect(content).toContain('export interface CustomerEntity {');
    expect(content).toContain('export interface CustomerEntityCreate {');
    expect(content).toContain('export type CustomerEntityUpdate = Partial<CustomerEntityCreate>;');
    expect(content).toContain('export interface CustomerEntityListResponse {');
  });

  it('embeds displayName in the doc header when present, falls back to entityName otherwise', () => {
    const withDisplay = gen.generate(
      domain({ entityName: 'Order', displayName: 'Sales Order' }),
      CTX,
    )!.content;
    expect(withDisplay).toContain(' * Sales Order Interface');

    const withoutDisplay = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    expect(withoutDisplay).toContain(' * Order Interface');
  });

  it('each field appears with TS type from DslMapper.mapType + optional marker iff not required', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'id', type: 'UUID', required: true }),
        field({ name: 'total', type: 'BigDecimal' }),     // optional → ?:
        field({ name: 'active', type: 'boolean' }),        // optional + non-string TS type
      ],
    }), CTX)!.content;

    expect(content).toContain('id: string;');           // UUID → string (required, no ?)
    expect(content).toContain('total?: string;');       // BigDecimal → string (optional)
    expect(content).toContain('active?: boolean;');     // boolean (optional)
  });

  it('field.description is emitted as a trailing line comment', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String', description: 'Business order ID' })],
    }), CTX)!.content;

    expect(content).toContain('orderNumber?: string; // Business order ID');
  });

  it('Create DTO excludes system fields ("id" by default), lifecycle fields, and inCreate=false fields', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'id', type: 'UUID' }),                          // system (default 'id')
        field({ name: 'createdAt', type: 'Instant' }),                // lifecycle constant
        field({ name: 'updatedAt', type: 'Instant' }),                // lifecycle constant
        field({ name: 'version', type: 'Long' }),                     // lifecycle constant
        field({ name: 'active', type: 'boolean' }),                   // lifecycle constant
        field({ name: 'parentTenantId', type: 'UUID' }),              // lifecycle constant
        field({ name: 'auditTrail', type: 'String', inCreate: false }), // inCreate excluded
        field({ name: 'name', type: 'String' }),                      // SHOULD be in Create
      ],
    }), CTX)!.content;

    // Pick the Create-DTO slice (between "interface ...Create {" and the next "}\n")
    const createBlockStart = content.indexOf('export interface OrderCreate {');
    const createBlockEnd = content.indexOf('}', createBlockStart);
    const createSlice = content.slice(createBlockStart, createBlockEnd);

    expect(createSlice).toContain('name?: string;');
    expect(createSlice).not.toContain('id?:');
    expect(createSlice).not.toContain('createdAt?:');
    expect(createSlice).not.toContain('updatedAt?:');
    expect(createSlice).not.toContain('version?:');
    expect(createSlice).not.toContain('active?:');
    expect(createSlice).not.toContain('parentTenantId?:');
    expect(createSlice).not.toContain('auditTrail?:');
  });

  it('KNOWN DIVERGENCE: Filter interface ignores field.enumType, uses DslMapper.mapType(field.type) — inconsistent with QueryBuilderGenerator', () => {
    // TypeGenerator.generateInterface builds the Filter interface by
    // calling DslMapper.mapType(field.type).tsType for every
    // filterable field. It does NOT consult field.enumType.
    // QueryBuilderGenerator.getFilterType DOES consult field.enumType
    // first. Result: for a filterable field with
    //   type: 'String', enumType: 'OrderStatus', filterable: true
    // the Filter interface emits `status?: string` while the
    // QueryParams (from query-builder-gen) emits `status?: OrderStatus`.
    // The two surfaces disagree on the same field's filter type.
    //
    // This test PINS the current TypeGenerator behaviour so a
    // future fix that makes Filter enum-aware will fail here and
    // surface the intentional change; meanwhile downstream
    // consumers know to prefer QueryBuilder for enum-typed filter
    // params. Flagged for follow-up production decision (the
    // reviewer of #54 suggested an issue; tracking inline in the
    // test until that decision lands).
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({
        name: 'status',
        type: 'String',
        enumType: 'com.shop.OrderStatus',
        filterable: true,
      })],
    }), CTX)!.content;

    expect(content).toContain('export interface OrderFilter {');
    // CURRENT (divergent) behaviour: Filter uses the type-derived
    // mapping, NOT the enum simple name.
    expect(content).toContain('status?: string;');
    // If/when this test starts failing, decide: (a) make
    // generateInterface enum-aware (call collectEnumTypes-style
    // resolution on filterable fields too) and update QueryBuilder
    // to match, OR (b) explicitly document the divergence as
    // intentional and split the assertion to expect 'OrderStatus'.
  });

  it('Filter type is emitted ONLY when at least one field is filterable; absent otherwise', () => {
    const withFilter = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'String', filterable: true })],
    }), CTX)!.content;
    expect(withFilter).toContain('export interface OrderFilter {');
    expect(withFilter).toContain('status?: string;');
    expect(withFilter).not.toContain('search');

    const withoutFilter = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'name', type: 'String' })], // not filterable
    }), CTX)!.content;
    expect(withoutFilter).not.toContain('export interface OrderFilter');
  });

  it('ListResponse interface always emitted with the canonical Spring-pagination shape', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('content: Order[];');
    expect(content).toContain('totalElements: number;');
    expect(content).toContain('totalPages: number;');
    expect(content).toContain('size: number;');
    expect(content).toContain('number: number;');
    expect(content).toContain('first: boolean;');
    expect(content).toContain('last: boolean;');
  });
});

// ---------- collectEnumTypes (12-branch heuristic via the interface header) ----------

describe('TypeGenerator.collectEnumTypes — exercised via the emitted import line', () => {
  const gen = new TypeGenerator();

  it('explicit enumType: imports the simple name (strips FQN package prefix)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'OrderStatus', enumType: 'com.shop.OrderStatus' })],
    }), CTX)!.content;

    expect(content).toContain("import { OrderStatus } from './enums';");
  });

  it.each([
    ['UserRole', 'Role-suffix → enum'],
    ['OrderStatus', 'Status-suffix → enum'],
    ['PaymentType', 'Type-suffix → enum'],
    ['BillingPlan', 'Plan-suffix → enum'],
    ['WorkflowState', 'State-suffix → enum'],
    ['AccessLevel', 'Level-suffix → enum'],
    ['ResourceKind', 'Kind-suffix → enum'],
    ['DeploymentMode', 'Mode-suffix → enum'],
    ['ProductCategory', 'Category-suffix → enum'],
  ])('heuristic: PascalCase ending in %s is treated as an enum (%s)', (typeName) => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: typeName })],
    }), CTX)!.content;

    expect(content).toContain(`import { ${typeName} } from './enums';`);
  });

  it('heuristic: FQN containing ".domain." is treated as an enum even without a known suffix', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: 'com.shop.domain.Verbatim' })],
    }), CTX)!.content;
    expect(content).toContain("import { Verbatim } from './enums';");
  });

  it('PascalCase without a known suffix and without .domain. is NOT treated as an enum (no import emitted)', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: 'Customer' })],
    }), CTX)!.content;

    expect(content).not.toContain("from './enums'");
  });

  it('known Java types are not enums', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'name', type: 'String' }),
        field({ name: 'amount', type: 'BigDecimal' }),
        field({ name: 'id', type: 'UUID' }),
        field({ name: 'at', type: 'Instant' }),
        field({ name: 'flag', type: 'Boolean' }),
        field({ name: 'count', type: 'Integer' }),
      ],
    }), CTX)!.content;

    expect(content).not.toContain("from './enums'");
  });

  it('generic + array types are skipped from enum detection', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'tags', type: 'List<String>' }),
        field({ name: 'matrix', type: 'String[]' }),
      ],
    }), CTX)!.content;
    expect(content).not.toContain("from './enums'");
  });

  it('FQN-stripped name that matches a known type is also skipped (no false-positive enum)', () => {
    // "java.util.UUID" → simpleName "UUID" → known type → skip even
    // though java.util.UUID isn't in the direct knownJavaTypes set
    // until simple-name check fires.
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'id', type: 'java.util.UUID' })],
    }), CTX)!.content;
    expect(content).not.toContain("from './enums'");
  });

  it('all-lowercase or non-PascalCase names are not treated as enums', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: 'lowercaseThing' })],
    }), CTX)!.content;
    expect(content).not.toContain("from './enums'");
  });

  it('multiple enum-typed fields are de-duped into a single import line', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'status1', type: 'OrderStatus' }),
        field({ name: 'status2', type: 'OrderStatus' }),
        field({ name: 'role', type: 'UserRole' }),
      ],
    }), CTX)!.content;

    // Single import line; both names appear; OrderStatus listed only once.
    const importMatches = content.match(/import \{ ([^}]+) \} from '\.\/enums';/);
    expect(importMatches).not.toBeNull();
    const importedNames = importMatches![1].split(',').map(s => s.trim());
    expect(importedNames).toContain('OrderStatus');
    expect(importedNames).toContain('UserRole');
    expect(importedNames.filter(n => n === 'OrderStatus')).toHaveLength(1);
  });
});

// ---------- generateAggregate — Zod schemas + barrel exports ----------

describe('TypeGenerator.generateAggregate — schemas + barrels', () => {
  const gen = new TypeGenerator();

  it('with generateZod=true → emits a schema file per visible domain + both barrels', () => {
    const ctx = createGeneratorContext({ generateZod: true });
    const files = gen.generateAggregate(
      [domain({ entityName: 'Order' }), domain({ entityName: 'Customer' })],
      ctx,
    );

    const paths = files.map(f => f.path);
    expect(paths).toContain('schemas/order.schema.ts');
    expect(paths).toContain('schemas/customer.schema.ts');
    expect(paths).toContain('types/index.ts');
    expect(paths).toContain('schemas/index.ts');
  });

  it('with generateZod=false → emits ONLY the types barrel (no schema files, no schemas barrel)', () => {
    const ctx = createGeneratorContext({ generateZod: false });
    const files = gen.generateAggregate([domain({ entityName: 'Order' })], ctx);

    expect(files).toHaveLength(1);
    expect(files[0].path).toBe('types/index.ts');
    expect(files.find(f => f.path.startsWith('schemas/'))).toBeUndefined();
  });

  it('types barrel always re-exports ./enums + every visible-domain kebab-path', () => {
    const ctx = createGeneratorContext({ generateZod: false });
    const files = gen.generateAggregate(
      [domain({ entityName: 'OrderLine' })],
      ctx,
    );

    const barrel = files.find(f => f.path === 'types/index.ts')!.content;
    expect(barrel).toContain("export * from './enums';");
    expect(barrel).toContain("export * from './order-line.types';");
  });

  it('schemas barrel re-exports every visible-domain schema (under .schema suffix)', () => {
    const ctx = createGeneratorContext({ generateZod: true });
    const files = gen.generateAggregate(
      [domain({ entityName: 'OrderLine' })],
      ctx,
    );

    const barrel = files.find(f => f.path === 'schemas/index.ts')!.content;
    expect(barrel).toContain("export * from './order-line.schema';");
  });
});

// ---------- buildZodType: validation-chain builder ----------

describe('TypeGenerator buildZodType — validation chain (exercised via emitted schema)', () => {
  const gen = new TypeGenerator();
  const ctx = createGeneratorContext({ generateZod: true });

  function schemaFor(fields: FieldMetadata[]): string {
    const files = gen.generateAggregate(
      [domain({ entityName: 'Thing', fields })],
      ctx,
    );
    return files.find(f => f.path === 'schemas/thing.schema.ts')!.content;
  }

  it('field.minLength on a string → emits z.string().min(N)', () => {
    expect(schemaFor([field({ name: 'name', type: 'String', minLength: 3 })]))
      .toContain('z.string().min(3)');
  });

  it('field.maxLength on a string → appends .max(N) to the chain', () => {
    expect(schemaFor([field({ name: 'name', type: 'String', maxLength: 50 })]))
      .toContain('z.string().max(50)');
  });

  it('field.min on a number → appends .min(N)', () => {
    expect(schemaFor([field({ name: 'count', type: 'int', min: 0 })]))
      .toContain('z.number().int().min(0)');
  });

  it('field.max on a number → appends .max(N)', () => {
    expect(schemaFor([field({ name: 'count', type: 'int', max: 100 })]))
      .toContain('z.number().int().max(100)');
  });

  it('format=email REPLACES the entire zodType with z.string().email()', () => {
    const content = schemaFor([field({ name: 'addr', type: 'String', format: 'email' })]);
    expect(content).toContain('z.string().email()');
  });

  it('format=url REPLACES the entire zodType with z.string().url()', () => {
    const content = schemaFor([field({ name: 'site', type: 'String', format: 'url' })]);
    expect(content).toContain('z.string().url()');
  });

  it('field.pattern REPLACES the entire zodType with z.string().regex(/<pattern>/)', () => {
    const content = schemaFor([field({ name: 'code', type: 'String', pattern: '^[A-Z]+$' })]);
    expect(content).toContain('z.string().regex(/^[A-Z]+$/)');
  });

  it('non-required fields get .optional() appended at the end of the chain', () => {
    const content = schemaFor([field({ name: 'maybe', type: 'String' })]);
    expect(content).toContain('z.string().optional()');
  });

  it('required fields do NOT get .optional() suffix', () => {
    const content = schemaFor([field({ name: 'must', type: 'String', required: true })]);
    expect(content).toMatch(/must: z\.string\(\)[^,]*,/); // no .optional() before the comma
    expect(content).not.toContain('must: z.string().optional()');
  });

  it('CreateSchema uses .omit({ ...systemFields: true }) per the discovered system-field set', () => {
    const content = schemaFor([
      field({ name: 'id', type: 'UUID' }),
      field({ name: 'version', type: 'Long' }),
      field({ name: 'name', type: 'String' }),
    ]);
    expect(content).toContain('ThingCreateSchema = ThingSchema.omit({');
    expect(content).toContain('id: true');
    expect(content).toContain('version: true');
  });

  it('UpdateSchema is CreateSchema.partial()', () => {
    expect(schemaFor([field({ name: 'name', type: 'String' })]))
      .toContain('ThingUpdateSchema = ThingCreateSchema.partial();');
  });

  it('schema imports its enum dependencies under <name>Schema suffix from ../types/enums', () => {
    const content = schemaFor([
      field({ name: 'status', type: 'OrderStatus', enumType: 'OrderStatus' }),
    ]);
    expect(content).toContain("import { OrderStatusSchema } from '../types/enums';");
  });
});

// ---------- getSystemFieldNames: default vs custom systemFields ----------

describe('TypeGenerator system-field resolution (exercised via .omit set in the schema)', () => {
  const gen = new TypeGenerator();
  const ctx = createGeneratorContext({ generateZod: true });

  it('default system fields present on the entity → all omitted from CreateSchema', () => {
    const files = gen.generateAggregate([domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'id', type: 'UUID' }),
        field({ name: 'version', type: 'long' }),
        field({ name: 'createdAt', type: 'Instant' }),
        field({ name: 'updatedAt', type: 'Instant' }),
      ],
    })], ctx);
    const schema = files.find(f => f.path === 'schemas/thing.schema.ts')!.content;

    for (const sf of ['id: true', 'version: true', 'createdAt: true', 'updatedAt: true']) {
      expect(schema).toContain(sf);
    }
  });

  it('a system field the entity does NOT declare is NOT omitted (z.omit rejects absent keys → T20 compile break)', () => {
    const files = gen.generateAggregate([domain({
      entityName: 'Thing',
      fields: [field({ name: 'id', type: 'UUID' })],
    })], ctx);
    const schema = files.find(f => f.path === 'schemas/thing.schema.ts')!.content;

    expect(schema).toContain('id: true');
    // version/createdAt/updatedAt are not fields of Thing → must not appear in .omit()
    expect(schema).not.toContain('version: true');
    expect(schema).not.toContain('createdAt: true');
    expect(schema).not.toContain('updatedAt: true');
  });

  it('a primaryKeyField override does not make the field it names server-owned', () => {
    // The override must NOT move the emitted identity. Nothing in the pipeline honours
    // `primaryKeyField`: Flyway emits `id UUID PRIMARY KEY`, the repository's clause is the
    // constant " WHERE id = ?", every by-id handler binds `{id}`, and the processor records the
    // same ("generators leave the primary key as the literal id"). An emitted app that honoured
    // it here would be the only layer doing so, and would request the wrong REST identifier.
    // So the named field stays in the create DTO: the backend accepts it as an ordinary
    // column and generates the row's real `id` itself.
    const files = gen.generateAggregate([domain({
      entityName: 'Thing',
      systemFields: { primaryKeyField: 'uuid' },
      fields: [field({ name: 'id', type: 'UUID' }), field({ name: 'uuid', type: 'UUID' })],
    })], ctx);
    const schema = files.find(f => f.path === 'schemas/thing.schema.ts')!.content;

    expect(schema).toContain('id: true');
    expect(schema).not.toContain('uuid: true');
  });

  it('every optional systemFields.* alias, when declared as a field, flows into the omit set', () => {
    const files = gen.generateAggregate([domain({
      entityName: 'Thing',
      // tenantIdField names the owner only on a tenant-partitioned entity.
      dataScope: 'TENANT',
      systemFields: {
        primaryKeyField: 'id',
        versionField: 'rev',
        createdAtField: 'ct',
        updatedAtField: 'ut',
        createdByField: 'cb',
        updatedByField: 'ub',
        tenantIdField: 'tid',
        softDeleteField: 'gone',
        softDeleteTimestampField: 'dt',
        softDeletedByField: 'db',
      },
      // The aliases must be present on the entity to be omittable (z.omit rejects
      // absent keys); a real audited/tenant entity declares them.
      fields: [
        field({ name: 'id', type: 'UUID' }),
        field({ name: 'rev', type: 'long' }),
        field({ name: 'ct', type: 'Instant' }),
        field({ name: 'ut', type: 'Instant' }),
        field({ name: 'cb', type: 'String' }),
        field({ name: 'ub', type: 'String' }),
        field({ name: 'tid', type: 'UUID' }),
        field({ name: 'gone', type: 'boolean' }),
        field({ name: 'dt', type: 'Instant' }),
        field({ name: 'db', type: 'String' }),
      ],
    })], ctx);
    const schema = files.find(f => f.path === 'schemas/thing.schema.ts')!.content;

    // All nine non-primary-key components of SystemFieldsMetadata. The three soft-delete
    // ones had no declaration in the TS schema at all until 0.9.0, so this loop could not
    // have covered them however it was written.
    for (const f of ['rev', 'ct', 'ut', 'cb', 'ub', 'tid', 'gone', 'dt', 'db']) {
      expect(schema).toContain(`${f}: true`);
    }
  });
});

// ---------- the shared-scope key is server-owned ----------

describe('TypeGenerator — a UNIVERSE entity\'s sharedScopeField is server-owned', () => {
  const universe = () => domain({
    entityName: 'Species',
    dataScope: 'UNIVERSE',
    systemFields: { primaryKeyField: 'id', tenantIdField: 'organizationId', sharedScopeField: 'worldId' },
    fields: [
      field({ name: 'id', type: 'UUID' }),
      field({ name: 'name', type: 'String' }),
      field({ name: 'organizationId', type: 'UUID' }),
      field({ name: 'worldId', type: 'UUID' }),
    ],
  });

  it('is omitted from the create schema, exactly like the tenant field', () => {
    const files = new TypeGenerator().generateAggregate([universe()], CTX);
    const schema = files.find(f => f.path === 'schemas/species.schema.ts')!.content;
    const createSchema = schema.slice(schema.indexOf('SpeciesCreateSchema'));

    expect(createSchema).toContain('worldId: true');
    expect(createSchema).toContain('organizationId: true');
  });

  it('is absent from the Create DTO interface, while the entity interface still carries it', () => {
    const content = new TypeGenerator().generate(universe(), CTX)!.content;
    const createStart = content.indexOf('export interface SpeciesCreate {');
    const createSlice = content.slice(createStart, content.indexOf('}', createStart));
    const entityStart = content.indexOf('export interface Species {');
    const entitySlice = content.slice(entityStart, content.indexOf('}', entityStart));

    expect(createSlice).toContain('name?: string;');
    expect(createSlice).not.toContain('worldId');
    expect(createSlice).not.toContain('organizationId');
    expect(entitySlice).toContain('worldId');
  });
});

describe('TypeGenerator — a tenant-partitioned owner without a systemFields block is server-owned', () => {
  const entity = (dataScope: 'GLOBAL' | 'TENANT') => domain({
    entityName: 'Fleet',
    dataScope,
    fields: [
      field({ name: 'id', type: 'UUID' }),
      field({ name: 'name', type: 'String' }),
      field({ name: 'tenantId', type: 'UUID' }),
    ],
  });
  const createSliceOf = (content: string) => {
    const start = content.indexOf('export interface FleetCreate {');
    return content.slice(start, content.indexOf('}', start));
  };

  // The server owns it (stamped, a foreign one refused with 400, never updated), and the emitted
  // OpenAPI leaves it out of both DTOs (ADR-090), so the TS DTOs and the create schema do too.
  it('omits tenantId from the TENANT Create DTO and create schema, keeping it on the entity', () => {
    const schema = new TypeGenerator().generateAggregate([entity('TENANT')], CTX)
      .find(f => f.path === 'schemas/fleet.schema.ts')!.content;
    const content = new TypeGenerator().generate(entity('TENANT'), CTX)!.content;
    const createSlice = createSliceOf(content);
    const entityStart = content.indexOf('export interface Fleet {');

    expect(systemFieldNames(entity('TENANT'))).toContain('tenantId');
    expect(schema.slice(schema.indexOf('FleetCreateSchema'))).toContain('  tenantId: true,');
    expect(createSlice).toContain('name?: string;');
    expect(createSlice).not.toContain('tenantId');
    expect(content).toContain('export type FleetUpdate = Partial<FleetCreate>;');
    expect(content).not.toContain('@deprecated');
    expect(content.slice(entityStart, content.indexOf('}', entityStart))).toContain('tenantId?: string;');
  });

  it('leaves a GLOBAL entity\'s tenantId-named field writable — it is not an owner there', () => {
    const schema = new TypeGenerator().generateAggregate([entity('GLOBAL')], CTX)
      .find(f => f.path === 'schemas/fleet.schema.ts')!.content;
    const createSlice = createSliceOf(new TypeGenerator().generate(entity('GLOBAL'), CTX)!.content);

    expect(systemFieldNames(entity('GLOBAL'))).not.toContain('tenantId');
    expect(schema.slice(schema.indexOf('FleetCreateSchema'))).not.toContain('tenantId: true');
    expect(createSlice).toContain('  tenantId?: string;');
  });

  it('omits the owner a systemFields block names', () => {
    const declared = domain({
      entityName: 'Fleet',
      dataScope: 'TENANT',
      systemFields: { primaryKeyField: 'id', tenantIdField: 'tenantId' },
      fields: [field({ name: 'id', type: 'UUID' }), field({ name: 'tenantId', type: 'UUID' })],
    });

    expect(createSliceOf(new TypeGenerator().generate(declared, CTX)!.content)).not.toContain('tenantId');
  });

  // The owner is DataScopeSupport.ownerFieldName on the Java side, which the emitted OpenAPI
  // reads: tenant-partitioned only, the declared tenantIdField when non-blank, else tenantId.
  const ownerRows: Array<[string, Partial<DomainMetadata>, boolean]> = [
    ['GLOBAL with a block naming tenantIdField keeps it', { dataScope: 'GLOBAL', systemFields: { primaryKeyField: 'id', tenantIdField: 'tenantId', versionField: 'version' } }, true],
    ['TENANT with a block and no tenantIdField omits it', { dataScope: 'TENANT', systemFields: { primaryKeyField: 'id', versionField: 'version' } }, false],
    ['TENANT with a block and a blank tenantIdField omits it', { dataScope: 'TENANT', systemFields: { primaryKeyField: 'id', tenantIdField: '  ' } }, false],
    ['UNIVERSE with no block omits it', { dataScope: 'UNIVERSE' }, false],
    ['tenantScoped: true with no block omits it', { tenantScoped: true }, false],
    ['GLOBAL with no block keeps it', { dataScope: 'GLOBAL' }, true],
  ];
  it.each(ownerRows)('%s', (_title, overrides, keeps) => {
    const metadata = domain({
      entityName: 'Fleet',
      ...overrides,
      fields: [
        field({ name: 'id', type: 'UUID' }),
        field({ name: 'name', type: 'String' }),
        field({ name: 'tenantId', type: 'UUID' }),
      ],
    });
    const createSlice = createSliceOf(new TypeGenerator().generate(metadata, CTX)!.content);
    const schema = new TypeGenerator().generateAggregate([metadata], CTX)
      .find(f => f.path === 'schemas/fleet.schema.ts')!.content;
    const createSchema = schema.slice(schema.indexOf('FleetCreateSchema'));

    expect(createSlice).toContain('name?: string;');
    if (keeps) {
      expect(systemFieldNames(metadata)).not.toContain('tenantId');
      expect(createSlice).toContain('  tenantId?: string;');
      expect(createSchema).not.toContain('tenantId: true');
    } else {
      expect(systemFieldNames(metadata)).toContain('tenantId');
      expect(createSlice).not.toContain('tenantId');
      expect(createSchema).toContain('  tenantId: true,');
    }
  });
});

// ---------- generateTypes convenience ----------

describe('generateTypes — top-level convenience function', () => {
  it('returns a 1-element array for a visible domain', () => {
    const files = generateTypes(domain({ entityName: 'Order' }), CTX.config);

    expect(files).toHaveLength(1);
    expect(files[0].path).toBe('types/order.types.ts');
  });

  it('falls back to KERNEL backend when config.backend is undefined (still emits the per-domain file)', () => {
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    const files = generateTypes(domain({ entityName: 'Order' }), partialConfig);
    expect(files).toHaveLength(1);
    expect(files[0].path).toBe('types/order.types.ts');
    expect(files[0].content).toContain('export interface Order {');
  });
});

// ---------- versioned: the update carries the optimistic-lock version ----------

describe('TypeGenerator — versioned update DTO and schema', () => {
  const gen = new TypeGenerator();
  const ctx = createGeneratorContext({ generateZod: true });

  function emit(overrides: Partial<DomainMetadata>): { types: string; schema: string } {
    const metadata = domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'id', type: 'UUID' }),
        field({ name: 'name', type: 'String' }),
        field({ name: 'version', type: 'java.lang.Long' }),
      ],
      ...overrides,
    });
    return {
      types: gen.generate(metadata, ctx)!.content,
      schema: gen.generateAggregate([metadata], ctx).find(f => f.path === 'schemas/thing.schema.ts')!.content,
    };
  }

  it('a versioned entity requires the version on update, typed as the entity declares it', () => {
    const { types, schema } = emit({ versioned: true });
    expect(types).toContain('export type ThingUpdate = Partial<ThingCreate> & { version: number | null };');
    expect(schema).toContain('export const ThingUpdateSchema = ThingCreateSchema.partial().extend({ version: z.number().nullable() });');
  });

  it('the create DTO and schema still leave the version out (the server owns the initial one)', () => {
    const { types, schema } = emit({ versioned: true });
    const start = types.indexOf('export interface ThingCreate {');
    const create = types.slice(start, types.indexOf('}', start));
    expect(create).not.toContain('version');
    expect(schema).toContain('ThingCreateSchema = ThingSchema.omit({\n  id: true,\n  version: true,\n});');
  });

  it('honours systemFields.versionField as the key', () => {
    const { types, schema } = emit({
      versioned: true,
      fields: [field({ name: 'id', type: 'UUID' }), field({ name: 'rev', type: 'long' })],
      systemFields: { versionField: 'rev' } as DomainMetadata['systemFields'],
    });
    expect(types).toContain('export type ThingUpdate = Partial<ThingCreate> & { rev: number };');
    expect(schema).toContain('.partial().extend({ rev: z.number() });');
    // T20: `rev` is a key of ThingSchema, so it is omitted from create; `version` is not, so it is not.
    expect(schema).toContain('  rev: true,');
    expect(schema).not.toContain('  version: true,');
  });

  it('an undeclared version field still goes on the update, as a number', () => {
    const { types, schema } = emit({
      versioned: true,
      fields: [field({ name: 'id', type: 'UUID' }), field({ name: 'name', type: 'String' })],
    });
    expect(types).toContain('export type ThingUpdate = Partial<ThingCreate> & { version: number };');
    expect(schema).toContain('.partial().extend({ version: z.number() });');
    expect(schema).not.toContain('  version: true,');
  });

  it('an unversioned entity keeps the plain partial update', () => {
    const { types, schema } = emit({ versioned: false });
    expect(types).toContain('export type ThingUpdate = Partial<ThingCreate>;');
    expect(schema).toContain('export const ThingUpdateSchema = ThingCreateSchema.partial();');
    expect(schema).not.toContain('.extend(');
  });
});

describe('auditFieldNames / viewSystemFieldNames — the views\' system-field classification', () => {
  it('names the audit stamps from systemFields, falling back to createdAt / updatedAt', () => {
    expect(auditFieldNames(domain({ entityName: 'Order' }))).toEqual({ createdAt: 'createdAt', updatedAt: 'updatedAt' });
    expect(auditFieldNames(domain({
      entityName: 'Order',
      systemFields: { createdAtField: 'openedAt', updatedAtField: '' },
    }))).toEqual({ createdAt: 'openedAt', updatedAt: 'updatedAt' });
  });

  it('keeps the conventional names for an entity without a systemFields block', () => {
    expect(viewSystemFieldNames(domain({ entityName: 'Order' }))).toEqual([
      'id', 'version', 'createdAt', 'updatedAt', 'tenantId', 'createdBy', 'updatedBy', 'deleted', 'deletedAt',
    ]);
  });

  it('adds what the flags make server-owned, under the names systemFields gives them', () => {
    expect(viewSystemFieldNames(domain({
      entityName: 'Ticket',
      audited: true,
      versioned: true,
      softDelete: true,
      systemFields: { createdAtField: 'openedAt', updatedAtField: 'touchedAt', versionField: 'revision', softDeleteField: 'closed' },
    }))).toEqual(['id', 'revision', 'openedAt', 'touchedAt', 'closed', 'tenantId']);
  });

  it('adds the default stamp and version names for flags on a block that names neither', () => {
    expect(viewSystemFieldNames(domain({
      entityName: 'Ticket',
      audited: true,
      versioned: true,
      systemFields: { primaryKeyField: 'id' },
    }))).toEqual(['id', 'createdAt', 'updatedAt', 'version', 'tenantId']);
  });
});

describe('buildZodType — numeric bounds on a boxed type', () => {
  /** The emitted expression, evaluated against the real Zod the emitted app installs. */
  const schemaOf = (f: Partial<FieldMetadata> & { name: string; type: string }) =>
    new Function('z', `return ${buildZodType(FieldMetadataSchema.parse(f))};`)(z) as z.ZodTypeAny;

  it('puts min and max on the number and keeps it nullable', () => {
    expect(buildZodType(FieldMetadataSchema.parse({ name: 'qty', type: 'Integer', required: true, min: 1, max: 9 })))
      .toBe('z.number().int().min(1).max(9).nullable()');
    const qty = schemaOf({ name: 'qty', type: 'Integer', required: true, min: 1, max: 9 });
    expect(qty.safeParse(5).success).toBe(true);
    expect(qty.safeParse(null).success).toBe(true);
    expect(qty.safeParse(0).success).toBe(false);
    expect(qty.safeParse(10).success).toBe(false);
  });

  it('bounds an optional boxed decimal and an unboxed primitive alike', () => {
    const rate = schemaOf({ name: 'rate', type: 'Double', min: 0.5 });
    expect(rate.safeParse(undefined).success).toBe(true);
    expect(rate.safeParse(0.4).success).toBe(false);
    const count = schemaOf({ name: 'count', type: 'int', required: true, max: 3 });
    expect(buildZodType(FieldMetadataSchema.parse({ name: 'count', type: 'int', required: true, max: 3 })))
      .toBe('z.number().int().max(3)');
    expect(count.safeParse(4).success).toBe(false);
  });
});
