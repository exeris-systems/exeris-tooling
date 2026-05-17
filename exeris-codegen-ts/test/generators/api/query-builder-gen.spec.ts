/**
 * Coverage for src/generators/api/query-builder-gen.ts — emits a
 * fluent query-builder class per domain plus a barrel-export aggregate
 * file. Assertions focus on the structural markers in the emitted
 * code rather than line-by-line snapshots so cosmetic formatting
 * changes don't false-fail the suite.
 */

import { describe, expect, it } from 'vitest';
import {
  QueryBuilderGenerator,
  generateQueryBuilder,
} from '../../../src/generators/api/query-builder-gen.js';
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
  return DomainMetadataSchema.parse({
    packageName: 'com.shop',
    ...overrides,
  });
}

function field(overrides: Partial<FieldMetadata> & { name: string; type: string }): FieldMetadata {
  return FieldMetadataSchema.parse(overrides);
}

// ---------- CodeGenerator contract ----------

describe('QueryBuilderGenerator — CodeGenerator metadata', () => {
  const gen = new QueryBuilderGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('QueryBuilderGenerator');
    expect(gen.artifactType).toBe('QUERY_BUILDER');
    expect(gen.priority).toBe(5);
    expect(gen.supportedBackends).toEqual([]);
  });
});

// ---------- generate — per-domain ----------

describe('QueryBuilderGenerator.generate — per-domain', () => {
  const gen = new QueryBuilderGenerator();

  it('emits queries/<kebab>.query.ts for a visible domain', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine' }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('queries/order-line.query.ts');
    expect(file!.artifactType).toBe('QUERY_BUILDER');
    expect(file!.overwritable).toBe(true);
  });

  it('returns null for an internalApi.hidden domain', () => {
    const file = gen.generate(
      domain({ entityName: 'Audit', internalApi: { hidden: true, readOnly: false, internal: false } }),
      CTX,
    );
    expect(file).toBeNull();
  });

  it('emits the entity-named QueryBuilder class + the camelCase factory helper', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;

    expect(content).toContain('export class OrderLineQueryBuilder');
    expect(content).toContain('export function orderLineQuery(): OrderLineQueryBuilder');
    expect(content).toContain('return new OrderLineQueryBuilder()');
  });

  it('imports the type module under the kebab-cased file name (alongside HttpParams)', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;

    expect(content).toContain("import { HttpParams } from '@angular/common/http'");
    expect(content).toContain("import type { OrderLine } from '../types/order-line.types'");
  });

  it('SortField type unions every sortable field; falls back to systemFields.idField when none are sortable', () => {
    const sortable = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'createdAt', type: 'Instant', sortable: true }),
        field({ name: 'total', type: 'BigDecimal', sortable: true }),
        field({ name: 'name', type: 'String' }), // not sortable
      ],
    }), CTX)!.content;
    expect(sortable).toContain("export type OrderSortField = 'createdAt' | 'total';");

    const fallback = gen.generate(domain({
      entityName: 'Order',
      systemFields: { idField: 'uuid' },
    }), CTX)!.content;
    expect(fallback).toContain("export type OrderSortField = 'uuid';");
  });

  it('falls back to default idField "id" when systemFields is absent and no sortable fields exist', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    expect(content).toContain("export type OrderSortField = 'id';");
  });

  it('QueryParams interface lists every filterable field with its mapped TS type', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'active', type: 'Boolean', filterable: true }),
        field({ name: 'count', type: 'Integer', filterable: true }),
        field({ name: 'totalLong', type: 'Long', filterable: true }),
        field({ name: 'discountDouble', type: 'Double', filterable: true }),
        field({ name: 'tag', type: 'String', filterable: true }),
        field({ name: 'notFilterable', type: 'String' }),
      ],
    }), CTX)!.content;

    // Boolean → boolean
    expect(content).toContain('active?: boolean;');
    // Number-family types (Integer/Long/Double) → number
    expect(content).toContain('count?: number;');
    expect(content).toContain('totalLong?: number;');
    expect(content).toContain('discountDouble?: number;');
    // Anything else → string fallback
    expect(content).toContain('tag?: string;');
    // Non-filterable fields are NOT in the QueryParams shape.
    expect(content).not.toContain('notFilterable?');
  });

  it('enum-typed filterable fields use the enum simple name (FQN prefix stripped) + emit the enum import', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'status', type: 'OrderStatus', enumType: 'com.shop.OrderStatus', filterable: true }),
        field({ name: 'priority', type: 'Priority', enumType: 'Priority', filterable: true }),
      ],
    }), CTX)!.content;

    // The QueryParams field uses just the simple name, not the FQN.
    expect(content).toContain('status?: OrderStatus;');
    expect(content).toContain('priority?: Priority;');
    // Enum imports are collected from the FQN namespace and emit a SINGLE
    // de-duped import line.
    expect(content).toContain("import { OrderStatus, Priority } from '../types/enums';");
  });

  it('does NOT emit the enum import line when no filterable field declares an enumType', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'tag', type: 'String', filterable: true })],
    }), CTX)!.content;
    expect(content).not.toContain("from '../types/enums'");
  });

  it('builder exposes filterable-field setter methods returning "this" (chainable)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'String', filterable: true })],
    }), CTX)!.content;

    expect(content).toContain('status(value: string): this');
    expect(content).toContain('this.params.status = value');
    expect(content).toContain('return this');
  });

  it('builder always emits the fixed core methods (page/size/sortBy/asc/desc/search/build/toHttpParams/getParams/reset/clone)', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    for (const method of [
      'page(page: number, size: number = 20): this',
      'size(size: number): this',
      "sortBy(field: OrderSortField, direction: SortDirection = 'asc'): this",
      "asc(field: OrderSortField): this",
      "desc(field: OrderSortField): this",
      'search(query: string): this',
      'build(): string',
      'toHttpParams(): HttpParams',
      'getParams(): OrderQueryParams',
      'reset(): this',
      'clone(): OrderQueryBuilder',
    ]) {
      expect(content).toContain(method);
    }
  });
});

// ---------- generateAggregate — barrel export ----------

describe('QueryBuilderGenerator.generateAggregate — barrel export', () => {
  const gen = new QueryBuilderGenerator();

  it('emits queries/index.ts re-exporting every visible domain query module under kebab path', () => {
    const files = gen.generateAggregate(
      [domain({ entityName: 'Order' }), domain({ entityName: 'OrderLine' })],
      CTX,
    );

    expect(files).toHaveLength(1);
    expect(files[0].path).toBe('queries/index.ts');
    expect(files[0].content).toContain("export * from './order.query';");
    expect(files[0].content).toContain("export * from './order-line.query';");
  });

  it('hidden domains are filtered out of the barrel', () => {
    const files = gen.generateAggregate([
      domain({ entityName: 'Order' }),
      domain({ entityName: 'Audit', internalApi: { hidden: true, readOnly: false, internal: false } }),
    ], CTX);

    expect(files[0].content).toContain("./order.query");
    expect(files[0].content).not.toContain("./audit.query");
  });

  it('empty visible-domain list still emits the barrel header + zero exports', () => {
    const files = gen.generateAggregate([], CTX);
    expect(files[0].path).toBe('queries/index.ts');
    expect(files[0].content).toContain('Query Builders - Barrel Export');
    expect(files[0].content).not.toMatch(/export \* from/);
  });
});

// ---------- generateQueryBuilder convenience ----------

describe('generateQueryBuilder — top-level convenience function', () => {
  it('routes through QueryBuilderGenerator and returns the per-domain file', () => {
    const file = generateQueryBuilder(domain({ entityName: 'Order' }), CTX.config);

    expect(file.path).toBe('queries/order.query.ts');
    expect(file.content).toContain('export class OrderQueryBuilder');
  });

  it('falls back to KERNEL backend when config.backend is undefined', () => {
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    expect(() => generateQueryBuilder(domain({ entityName: 'Order' }), partialConfig)).not.toThrow();
  });
});
