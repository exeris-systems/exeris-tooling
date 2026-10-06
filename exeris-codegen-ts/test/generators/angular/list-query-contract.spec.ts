/**
 * The list route's query and page envelope (ADR-096) — the TypeScript half of a cross-build check.
 *
 * The route answers `400` for any parameter it does not read, so a front that sends one more name,
 * one larger size or one filter the server does not parse fails at run time. The generated route
 * is built by Maven and this package by npm, and the two never run together, so the contract lives
 * in one committed file both sides test against: `ListQueryContractE2ETest` (exeris-e2e-tests)
 * holds the generated list query, page record and OpenAPI document to
 * `contract/list-query.json`, and this spec holds the emitted service, store, list, picker and
 * related-records panels, and the sortable / filterable rules of `list-query.ts`, to the same file.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { ServiceGenerator } from '../../../src/generators/angular/service-gen.js';
import { StoreGenerator } from '../../../src/generators/angular/store-gen.js';
import { ListGenerator } from '../../../src/generators/angular/list-gen.js';
import { createGeneratorContext } from '../../../src/core/generator-registry.js';
import {
  DEFAULT_PAGE_SIZE,
  FILTERABLE_SCALAR_TYPES,
  MAX_PAGE_SIZE,
  PAGE_ENVELOPE,
  PAGE_PARAM,
  RESERVED_PARAMS,
  SIZE_PARAM,
  SORT_PARAM,
  filterProperties,
  listQuerySystemFieldNames,
  sortableProperties,
} from '../../../src/generators/angular/list-query.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../../src/models/domain-model.js';

interface ListQueryContract {
  route: { operation: string; method: string; path: string };
  parameters: {
    page: { default: number; minimum: number };
    size: { default: number; minimum: number; maximum: number };
    sort: { format: string; directions: string[] };
    filter: { format: string; booleanValues: string[]; foreignKey: string };
  };
  reserved: string[];
  filterableScalarTypes: string[];
  envelope: string[];
}

// Repo root is four levels up from this file (test/generators/angular/). A missing file must fail
// the suite, not skip it — a check that cannot find its contract proves nothing.
const CONTRACT_URL = new URL(
  '../../../../exeris-e2e-tests/src/test/resources/contract/list-query.json',
  import.meta.url,
);
const contract = JSON.parse(readFileSync(fileURLToPath(CONTRACT_URL), 'utf8')) as ListQueryContract;

const ENUMS = [{
  name: 'OrderStatus',
  qualifiedName: 'com.shop.OrderStatus',
  packageName: 'com.shop',
  values: [{ name: 'NEW', displayName: 'New', ordinal: 0 }, { name: 'PAID', displayName: 'Paid', ordinal: 1 }],
}];

/**
 * Every rule of the contract's `properties` on one entity: system fields under canonical and
 * declared names, a List field, the temporal kinds, an enum, the reserved names, a field the
 * metadata marks neither sortable nor filterable, and two MANY_TO_ONE keys, one carried by a field.
 */
const order: DomainMetadata = DomainMetadataSchema.parse({
  packageName: 'com.shop',
  entityName: 'Order',
  dataScope: 'TENANT',
  audited: true,
  versioned: true,
  softDelete: true,
  systemFields: { primaryKeyField: 'id', updatedByField: 'editor', softDeleteField: 'archived' },
  fields: [
    { name: 'id', type: 'java.util.UUID', sortable: true, filterable: true },
    { name: 'tenantId', type: 'java.util.UUID', sortable: true, filterable: true },
    { name: 'createdAt', type: 'java.time.Instant', sortable: true, filterable: true },
    { name: 'createdBy', type: 'String', sortable: true, filterable: true },
    { name: 'editor', type: 'String', sortable: true, filterable: true },
    { name: 'updatedBy', type: 'String', sortable: true, filterable: true },
    { name: 'version', type: 'long', sortable: true, filterable: true },
    { name: 'archived', type: 'boolean', sortable: true, filterable: true },
    { name: 'deletedAt', type: 'java.time.Instant', sortable: true, filterable: true },
    { name: 'code', type: 'java.lang.String', sortable: true, filterable: true },
    { name: 'paid', type: 'boolean', sortable: true, filterable: true },
    { name: 'quantity', type: 'java.lang.Integer', sortable: true, filterable: true },
    { name: 'total', type: 'java.math.BigDecimal', sortable: true, filterable: true },
    { name: 'deliveryDate', type: 'java.time.LocalDate', sortable: true, filterable: true },
    { name: 'placedAt', type: 'java.time.Instant', sortable: true, filterable: true },
    { name: 'pickupAt', type: 'java.time.LocalDateTime', sortable: true, filterable: true },
    { name: 'labels', type: 'java.util.List<java.lang.String>', sortable: true, filterable: true },
    { name: 'status', type: 'com.shop.OrderStatus', sortable: true, filterable: true },
    { name: 'page', type: 'int', sortable: true, filterable: true },
    { name: 'note', type: 'String' },
    { name: 'customerId', type: 'java.util.UUID' },
  ],
  relationships: [
    { name: 'customerId', targetEntity: 'Customer', type: 'MANY_TO_ONE' },
    { name: 'warehouse', targetEntity: 'Warehouse', type: 'MANY_TO_ONE' },
    { name: 'lines', targetEntity: 'OrderLine', type: 'ONE_TO_MANY' },
  ],
});

const isEnum = (field: { type: string; enumType?: string }) =>
  field.enumType !== undefined || ENUMS.some((e) => e.qualifiedName === field.type);
const CTX = createGeneratorContext({}, [order], ENUMS);
const service = new ServiceGenerator().generate(order, CTX)!.content;
const store = new StoreGenerator().generate(order, CTX)!.content;
const list = new ListGenerator().generate(order, CTX)!.content;

describe('contract/list-query.json — the query the front sends', () => {
  it('names the list route the service calls', () => {
    expect(contract.route).toEqual({ operation: 'list', method: 'GET', path: '{base}' });
    expect(service).toContain('return this.http.get<Page<Order>>(this.baseUrl, { params });');
  });

  it('sends page, size and sort under the contract\'s names, and reserves exactly those', () => {
    expect([PAGE_PARAM, SIZE_PARAM, SORT_PARAM]).toEqual(['page', 'size', 'sort']);
    expect(Object.keys(contract.parameters)).toEqual([PAGE_PARAM, SIZE_PARAM, SORT_PARAM, 'filter']);
    expect([...RESERVED_PARAMS]).toEqual(contract.reserved);
    expect(service).toContain(`params = params.set('${PAGE_PARAM}', String(pageRequest.page));`);
    expect(service).toContain(`params = params.set('${SIZE_PARAM}', String(pageRequest.size));`);
  });

  it('sends sort as <property>,<direction>, only when a property is sorted, in a direction the contract lists', () => {
    expect(contract.parameters.sort.format).toBe('<property>,<direction>');
    expect(service).toContain(
      "if (pageRequest.sort) params = params.set('sort', `${pageRequest.sort},${pageRequest.direction ?? 'asc'}`);",
    );
    expect(service).toContain("  direction?: 'asc' | 'desc';");
    expect(contract.parameters.sort.directions).toEqual(['asc', 'desc']);
    expect(store).toContain('...(sort !== null ? { sort, direction: this._sortDirection() } : {}),');
    expect(list).toContain('...(sort !== null ? { sort, direction: this.sortDirection() } : {}),');
  });

  it('starts at the default page and size, and never requests a size outside the bounds', () => {
    const { page, size } = contract.parameters;
    expect(page).toMatchObject({ default: 0, minimum: 0 });
    expect(DEFAULT_PAGE_SIZE).toBe(size.default);
    expect(MAX_PAGE_SIZE).toBe(size.maximum);
    expect(size.minimum).toBe(1);

    expect(list).toContain(`readonly currentPage = signal(${page.default});`);
    expect(list).toContain(`readonly pageSize = signal(${size.default});`);
    expect(list).toContain(`const MAX_PAGE_SIZE = ${size.maximum};`);
    expect(list).toContain(`value >= ${size.minimum} && value <= MAX_PAGE_SIZE`);
    const offered = [...list.matchAll(/<option value="(\d+)" \[selected\]="pageSize\(\) === \d+">/g)].map((m) => Number(m[1]));
    expect(offered).toContain(size.default);
    for (const n of offered) {
      expect(n).toBeGreaterThanOrEqual(size.minimum);
      expect(n).toBeLessThanOrEqual(size.maximum);
    }

    expect(store).toContain(`private readonly _size = signal(${size.default});`);
    expect(store).toContain(`const MIN_PAGE_SIZE = ${size.minimum};`);
    expect(store).toContain(`const MAX_PAGE_SIZE = ${size.maximum};`);
  });

  it('the picker and the related-records panels ask for a size within the bounds', () => {
    const product = DomainMetadataSchema.parse({
      packageName: 'com.shop',
      entityName: 'Product',
      fields: [{ name: 'id', type: 'java.util.UUID' }],
      relationships: [{ name: 'lines', targetEntity: 'Line', type: 'ONE_TO_MANY' }],
    });
    const line = DomainMetadataSchema.parse({
      packageName: 'com.shop',
      entityName: 'Line',
      fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'productId', type: 'java.util.UUID', required: true }],
      relationships: [{ name: 'productId', targetEntity: 'Product', type: 'MANY_TO_ONE' }],
    });
    const files = buildGeneratedFiles([product, line], [], DEFAULT_CONFIG);
    const sizes = files
      .filter((f) => f.path.endsWith('-form.component.ts') || f.path.endsWith('-detail.component.ts'))
      .flatMap((f) => [...f.content.matchAll(/findAll\(\{ size: (\d+) \}/g)].map((m) => Number(m[1])));
    expect(sizes).toContain(contract.parameters.size.maximum);
    expect(sizes.length).toBeGreaterThanOrEqual(2);
    for (const n of sizes) {
      expect(n).toBeGreaterThanOrEqual(contract.parameters.size.minimum);
      expect(n).toBeLessThanOrEqual(contract.parameters.size.maximum);
    }
  });

  it('sends a boolean filter as one of the contract\'s values', () => {
    expect(contract.parameters.filter.booleanValues).toEqual(['true', 'false']);
    expect(list).toContain('<option value="true">Yes</option>');
    expect(list).toContain('<option value="false">No</option>');
  });

  it('never sends search: no emitted module declares or sets it', () => {
    const files = buildGeneratedFiles([order], ENUMS, DEFAULT_CONFIG);
    const sent = files.filter((f) => /['"`]search['"`]|\bsearch\??:|\.search\b|setSearch|onSearch/.test(f.content));
    expect(sent.map((f) => f.path)).toEqual([]);
  });
});

describe('contract/list-query.json — the page envelope', () => {
  it('reads exactly the envelope\'s members, in order', () => {
    expect([...PAGE_ENVELOPE]).toEqual(contract.envelope);
    const page = service.match(/export interface Page<T> \{\n([\s\S]*?)\n\}/)![1];
    const members = page.split('\n').map((line) => line.trim().split('?')[0].split(':')[0]);
    expect(members).toEqual(contract.envelope);
  });

  it('the store and the list read the rows and totals from it', () => {
    expect(store).toContain('this._entities.set(response.content);');
    expect(store).toContain('this._totalElements.set(response.totalElements);');
    expect(store).toContain('this._totalPages.set(response.totalPages);');
    for (const member of contract.envelope) {
      expect(list, member).toMatch(new RegExp(`result(\\(\\)\\?)?\\.${member}\\b`));
    }
  });
});

describe('contract/list-query.json — which properties sort and filter', () => {
  it('filters on exactly the contract\'s scalar types', () => {
    expect([...FILTERABLE_SCALAR_TYPES]).toEqual(contract.filterableScalarTypes);
    for (const type of contract.filterableScalarTypes) {
      const d = DomainMetadataSchema.parse({
        packageName: 'com.shop',
        entityName: 'Probe',
        fields: [{ name: 'value', type, filterable: true }],
      });
      expect(filterProperties(d).map((p) => p.name), type).toEqual(['value']);
    }
  });

  it('never offers the primary key, the tenant owner, the audit, version or soft-delete fields', () => {
    expect([...listQuerySystemFieldNames(order)].sort()).toEqual([
      'archived', 'createdAt', 'createdBy', 'deletedAt', 'deletedBy', 'editor', 'id', 'tenantId', 'updatedAt',
      'version',
    ]);
    // A UNIVERSE entity's shared-scope field is server-owned too.
    const universe = DomainMetadataSchema.parse({
      packageName: 'com.shop',
      entityName: 'Doc',
      dataScope: 'UNIVERSE',
      systemFields: { sharedScopeField: 'circle' },
      fields: [{ name: 'circle', type: 'java.util.UUID', sortable: true, filterable: true }],
    });
    expect(sortableProperties(universe)).toEqual([]);
    expect(filterProperties(universe)).toEqual([]);
  });

  it('sorts on every sortable field but a List or a system field', () => {
    // `updatedBy` is an ordinary field here: the entity names its updated-by role `editor`.
    const expected = [
      'code', 'deliveryDate', 'page', 'paid', 'pickupAt', 'placedAt', 'quantity', 'status', 'total', 'updatedBy',
    ];
    expect(sortableProperties(order).map((p) => p.name)).toEqual(expected);
    expect(service).toContain(`export type OrderSortField = ${expected.map((n) => `'${n}'`).join(' | ')};`);
  });

  it('filters on the filterable fields of a scalar or enum type, never a reserved name, plus <base>Id per MANY_TO_ONE', () => {
    expect(contract.parameters.filter.foreignKey).toBe('<relationship>Id=<uuid>, for every MANY_TO_ONE relationship');
    // customerId is the relationship `customerId` with its trailing Id removed, then `Id`; the
    // relationship `warehouse` adds warehouseId. Instant, LocalDateTime, the List and `page` are absent.
    const expected = [
      'code', 'customerId', 'deliveryDate', 'paid', 'quantity', 'status', 'total', 'updatedBy', 'warehouseId',
    ];
    expect(filterProperties(order, isEnum).map((p) => p.name)).toEqual(expected);
    const filter = service.match(/export interface OrderFilter \{\n([\s\S]*?)\n\}/)![1];
    expect(filter.split('\n').map((line) => line.trim().split('?')[0])).toEqual(expected);
    for (const name of expected) {
      expect(list, name).toContain(`data-testid="filter-${name}"`);
    }
    for (const name of ['placedAt', 'pickupAt', 'labels', 'page', 'note', 'createdAt', 'tenantId', 'archived', 'id']) {
      expect(list, name).not.toContain(`data-testid="filter-${name}"`);
    }
  });
});
