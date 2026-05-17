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
import {
  TypeGenerator,
  generateTypes,
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

  it('returns null for an internalApi.hidden domain', () => {
    expect(gen.generate(
      domain({ entityName: 'Audit', internalApi: { hidden: true, readOnly: false, internal: false } }),
      CTX,
    )).toBeNull();
  });

  it('strips the "Entity" suffix from interface names (toInterfaceName behavior)', () => {
    const content = gen.generate(domain({ entityName: 'CustomerEntity' }), CTX)!.content;
    // Interface declarations use the stripped name.
    expect(content).toContain('export interface Customer {');
    expect(content).toContain('export interface CustomerCreate {');
    expect(content).toContain('export type CustomerUpdate = Partial<CustomerCreate>;');
    // ListResponse type uses the stripped name too.
    expect(content).toContain('export interface CustomerListResponse {');
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

  it('Filter type is emitted ONLY when at least one field is filterable; absent otherwise', () => {
    const withFilter = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'String', filterable: true })],
    }), CTX)!.content;
    expect(withFilter).toContain('export interface OrderFilter {');
    expect(withFilter).toContain('status?: string;');
    expect(withFilter).toContain('search?: string;'); // always last in filter block

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

  it('hidden domains are filtered out of both schemas and barrels', () => {
    const ctx = createGeneratorContext({ generateZod: true });
    const files = gen.generateAggregate([
      domain({ entityName: 'Order' }),
      domain({ entityName: 'Audit', internalApi: { hidden: true, readOnly: false, internal: false } }),
    ], ctx);

    expect(files.find(f => f.path === 'schemas/audit.schema.ts')).toBeUndefined();
    const typesBarrel = files.find(f => f.path === 'types/index.ts')!;
    expect(typesBarrel.content).toContain("./order.types");
    expect(typesBarrel.content).not.toContain("./audit.types");
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

  it('with NO systemFields metadata → default set is { id, version, createdAt, updatedAt }', () => {
    const files = gen.generateAggregate([domain({
      entityName: 'Thing',
      fields: [field({ name: 'id', type: 'UUID' })],
    })], ctx);
    const schema = files.find(f => f.path === 'schemas/thing.schema.ts')!.content;

    for (const sf of ['id: true', 'version: true', 'createdAt: true', 'updatedAt: true']) {
      expect(schema).toContain(sf);
    }
  });

  it('explicit systemFields.idField !== "id" adds the alias alongside "id"', () => {
    const files = gen.generateAggregate([domain({
      entityName: 'Thing',
      systemFields: { idField: 'uuid' },
      fields: [field({ name: 'uuid', type: 'UUID' })],
    })], ctx);
    const schema = files.find(f => f.path === 'schemas/thing.schema.ts')!.content;

    expect(schema).toContain('id: true');
    expect(schema).toContain('uuid: true');
  });

  it('every optional systemFields.* aliases (createdAtField, updatedAtField, createdByField, updatedByField, tenantIdField, deletedAtField, versionField) flows into the omit set when set', () => {
    const files = gen.generateAggregate([domain({
      entityName: 'Thing',
      systemFields: {
        idField: 'id',
        versionField: 'rev',
        createdAtField: 'ct',
        updatedAtField: 'ut',
        createdByField: 'cb',
        updatedByField: 'ub',
        tenantIdField: 'tid',
        deletedAtField: 'dt',
      },
    })], ctx);
    const schema = files.find(f => f.path === 'schemas/thing.schema.ts')!.content;

    for (const f of ['rev', 'ct', 'ut', 'cb', 'ub', 'tid', 'dt']) {
      expect(schema).toContain(`${f}: true`);
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

  it('returns an EMPTY array for an internalApi.hidden domain', () => {
    const files = generateTypes(
      domain({ entityName: 'Audit', internalApi: { hidden: true, readOnly: false, internal: false } }),
      CTX.config,
    );
    expect(files).toEqual([]);
  });

  it('falls back to KERNEL backend when config.backend is undefined', () => {
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    expect(() => generateTypes(domain({ entityName: 'Order' }), partialConfig)).not.toThrow();
  });
});
