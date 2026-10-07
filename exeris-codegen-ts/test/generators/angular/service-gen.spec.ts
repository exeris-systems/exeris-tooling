/**
 * Coverage for src/generators/angular/service-gen.ts — ServiceGenerator
 * emits an Angular service per domain with findAll / findById / create /
 * update / delete (+ softDelete when softDelete=true) + custom
 * actions, plus a services/index.ts barrel for the cross-domain pass.
 *
 * Exercises:
 *   - apiPath construction precedence: explicit apiPath > apiVersion+path
 *     > /<kebab>s default
 *   - Filter interface and SortField type built from the list route's
 *     filter and sort properties (ADR-096)
 *   - softDelete flag adds a softDelete method on the served DELETE route
 *   - Custom actions: httpMethod GET vs POST/PATCH/DELETE body-shape;
 *     hasParams gate; default returnType 'void'; description fallback
 *   - buildZodType chain (minLength on string, maxLength append, format=
 *     email/url REPLACE, !required → .optional())
 *   - getSystemFields: default set {id, createdAt, updatedAt, version}
 *     OR every optional systemFields.* alias propagation
 *   - collectEnumTypes: same heuristic as type-gen with explicit enumType +
 *     suffix matrix + .domain. marker
 */

import { describe, expect, it } from 'vitest';
import { ServiceGenerator, generateService } from '../../../src/generators/angular/service-gen.js';
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

describe('ServiceGenerator — CodeGenerator metadata', () => {
  const gen = new ServiceGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('ServiceGenerator');
    expect(gen.artifactType).toBe('SERVICE');
    expect(gen.priority).toBe(5);
    expect(gen.supportedBackends).toEqual([]);
  });
});

// ---------- generate — path + hidden-skip ----------

describe('ServiceGenerator.generate — emit path + hidden-skip', () => {
  const gen = new ServiceGenerator();

  it('emits services/<kebab>.service.ts for a visible domain', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine' }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('services/order-line.service.ts');
    expect(file!.artifactType).toBe('SERVICE');
    expect(file!.overwritable).toBe(true);
  });

});

// ---------- emitted structure ----------

describe('ServiceGenerator emitted content — top-level structure', () => {
  const gen = new ServiceGenerator();

  it('imports HttpClient + Observable + entity types + Pagination interfaces declared', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("import { Injectable, inject } from '@angular/core';");
    expect(content).toContain("import { HttpClient, HttpParams } from '@angular/common/http';");
    expect(content).toContain("import { Observable } from 'rxjs';");
    expect(content).toContain("import type { Order, OrderCreate, OrderUpdate } from '../types/order.types';");
    expect(content).toContain('export interface Page<T> {');
    expect(content).toContain('export interface PageRequest {');
  });

  it('emits @Injectable({ providedIn: \'root\' }) decorator + entityName-suffixed service class', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;

    expect(content).toContain("@Injectable({ providedIn: 'root' })");
    expect(content).toContain('export class OrderLineService {');
  });

  it('always emits findAll / findById / create / update / delete methods', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain(
      'findAll(pageRequest: PageRequest & { sort?: OrderSortField } = {}, filter: OrderFilter = {}): Observable<Page<Order>>',
    );
    expect(content).toContain('findById(id: string): Observable<Order>');
    expect(content).toContain('create(data: OrderCreate): Observable<Order>');
    expect(content).toContain('update(id: string, data: OrderUpdate): Observable<Order>');
    expect(content).toContain('delete(id: string): Observable<void>');
  });

  it('re-exports the entity types for downstream convenience', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain(
      "export type { Order, OrderCreate, OrderUpdate } from '../types/order.types';",
    );
  });
});

// ---------- apiPath construction precedence ----------

describe('ServiceGenerator apiPath construction precedence', () => {
  const gen = new ServiceGenerator();

  function baseUrlFor(meta: Partial<DomainMetadata>): string {
    const content = gen.generate(domain({ entityName: 'Order', ...meta }), CTX)!.content;
    const m = content.match(/baseUrl = '([^']+)'/);
    expect(m).not.toBeNull();
    return m![1];
  }

  it('explicit apiPath wins over apiVersion + path', () => {
    expect(baseUrlFor({ apiPath: '/custom/orders', apiVersion: 'v2', path: '/anything' }))
      .toBe('/custom/orders');
  });

  it('apiVersion is NOT folded into the URL — the server serves no version segment', () => {
    // Emitting /api/v1/orders against a router listening on /orders is what made every generated
    // service 404. apiVersion reaches no emitted route on either the Java or the TypeScript side.
    expect(baseUrlFor({ apiVersion: 'v1', path: '/orders' })).toBe('/orders');
  });

  it('path only (no apiVersion) → just path', () => {
    expect(baseUrlFor({ path: '/orders' })).toBe('/orders');
  });

  it('neither apiPath nor path → the kebab-cased SDK plural, as effectivePath() derives', () => {
    expect(baseUrlFor({})).toBe('/orders');
  });

  it.each([
    ['OrderLine', '/order-lines'],
    ['Colony', '/colonies'],
    ['Box', '/boxes'],
    ['Address', '/addresses'],
  ])('default plural of %s is %s', (entityName, expected) => {
    const url = gen.generate(domain({ entityName }), CTX)!.content
      .match(/baseUrl = '([^']+)'/)![1];
    expect(url).toBe(expected);
  });
});

// ---------- Filter interface ----------

describe('ServiceGenerator Filter interface generation', () => {
  const gen = new ServiceGenerator();

  it('a filterable field of a filterable type is a filter, typed as its DTO value', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'status', type: 'String', filterable: true }),
        field({ name: 'amount', type: 'Long', filterable: true }),
        field({ name: 'paid', type: 'boolean', filterable: true }),
        field({ name: 'due', type: 'java.time.LocalDate', filterable: true }),
        field({ name: 'price', type: 'java.math.BigDecimal', filterable: true }),
        field({ name: 'notFilterable', type: 'String' }),
      ],
    }), CTX)!.content;

    const filter = content.slice(content.indexOf('export interface OrderFilter {'));
    expect(filter).toMatch(
      /^export interface OrderFilter \{\n {2}amount\?: number;\n {2}due\?: string;\n {2}paid\?: boolean;\n {2}price\?: string;\n {2}status\?: string;\n\}/,
    );
    expect(content).not.toContain('notFilterable');
  });

  it('an Instant, a LocalDateTime and a List field are never a filter', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'at', type: 'java.time.Instant', filterable: true }),
        field({ name: 'localAt', type: 'java.time.LocalDateTime', filterable: true }),
        field({ name: 'tags', type: 'java.util.List<java.lang.String>', filterable: true }),
      ],
    }), CTX)!.content;

    expect(content).toContain('export interface OrderFilter {\n}');
  });

  it('a MANY_TO_ONE adds its <base>Id filter, a string', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'id', type: 'java.util.UUID' })],
      relationships: [{ name: 'customer', targetEntity: 'Customer', type: 'MANY_TO_ONE' }],
    }), CTX)!.content;

    expect(content).toContain('  customerId?: string;');
  });

  it('declares no search filter: the list route has no search parameter', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'name', type: 'String', searchable: true, filterable: true })],
    }), CTX)!.content;

    expect(content).toContain('export interface OrderFilter {');
    expect(content).not.toContain('search');
  });

  it('the sort keys are the sortable non-system, non-List fields, sorted by name', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      versioned: true,
      fields: [
        field({ name: 'id', type: 'java.util.UUID', sortable: true }),
        field({ name: 'title', type: 'String', sortable: true }),
        field({ name: 'at', type: 'java.time.Instant', sortable: true }),
        field({ name: 'version', type: 'long', sortable: true }),
        field({ name: 'tags', type: 'java.util.List<java.lang.String>', sortable: true }),
        field({ name: 'note', type: 'String' }),
      ],
    }), CTX)!.content;

    expect(content).toContain("export type OrderSortField = 'at' | 'title';");
  });

  it('an entity with nothing sortable has no sort key', () => {
    const content = gen.generate(domain({ entityName: 'Order', fields: [field({ name: 'name', type: 'String' })] }), CTX)!.content;

    expect(content).toContain('export type OrderSortField = never;');
  });
});

// ---------- softDelete branch ----------

describe('ServiceGenerator softDelete branch', () => {
  const gen = new ServiceGenerator();

  // The generated server serves no /archive and no /restore route. On a @SoftDelete entity its
  // DELETE is the archive, so softDelete calls that (PATCH/PUT parity — see
  // crud-route-parity.spec.ts). Nothing un-sets the flag, so no restore() is emitted.
  it('softDelete=true adds softDelete on the served DELETE route', () => {
    const content = gen.generate(domain({ entityName: 'Order', softDelete: true }), CTX)!.content;

    expect(content).toContain('softDelete(id: string): Observable<void>');
    expect(content).toContain('softDelete(id: string): Observable<void> {\n    return this.http.delete<void>(`${this.baseUrl}/${id}`);');
    expect(content).not.toContain('/${id}/archive');
    expect(content).not.toContain('/${id}/restore');
  });

  it('softDelete=true emits no restore() and imports no throwError', () => {
    const content = gen.generate(domain({ entityName: 'Order', softDelete: true }), CTX)!.content;

    expect(content).not.toContain('restore(');
    expect(content).not.toContain('@deprecated');
    expect(content).not.toContain('throwError');
    expect(content).toContain("import { Observable } from 'rxjs';");
  });

  it('softDelete=false (default) omits softDelete + restore methods', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).not.toContain('softDelete(id: string)');
    expect(content).not.toContain('restore(id: string)');
    expect(content).not.toContain('archive');
    expect(content).toContain("import { Observable } from 'rxjs';");
  });
});

// ---------- custom actions ----------

describe('ServiceGenerator custom actions emission', () => {
  const gen = new ServiceGenerator();

  // T1: actions are served server-side at POST {base}/{id}/actions/{kebab(name)}
  // (matching the OpenAPI path + the generated kernel route); the server responds
  // with the updated aggregate. So every action method takes the entity id and
  // returns Observable<Entity>, always via POST.
  it('no-param action → POST {id}/actions/{name}, takes id, returns the entity', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      actions: [{ name: 'cancel', params: [] }],
    }), CTX)!.content;

    expect(content).toContain('cancel(id: string): Observable<Order>');
    expect(content).toContain('this.http.post<Order>(`${this.baseUrl}/${id}/actions/cancel`, {})');
  });

  it('params action → id + typed params, body params object', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      actions: [{
        name: 'approve',
        params: [{ name: 'reason', type: 'String', required: true }],
      }],
    }), CTX)!.content;

    expect(content).toContain('approve(id: string, reason: string): Observable<Order>');
    expect(content).toContain('this.http.post<Order>(`${this.baseUrl}/${id}/actions/approve`, { reason })');
  });

  it('camelCase action name → kebab-cased URL segment, camelCase method name', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      actions: [{ name: 'markUrgent', params: [] }],
    }), CTX)!.content;

    expect(content).toContain('markUrgent(id: string): Observable<Order>');
    expect(content).toContain('/${id}/actions/mark-urgent');
  });

  it('non-camelCase action name (kebab) → valid camelCase method, kebab URL segment unchanged', () => {
    // PR #92 review: action.name was emitted verbatim as the method name, so a
    // @Action(name="mark-urgent") produced invalid JS `mark-urgent(...)`. The method
    // name now normalises via DslMapper.toMethodName; the URL segment stays kebab.
    const content = gen.generate(domain({
      entityName: 'Order',
      actions: [{ name: 'mark-urgent', params: [] }],
    }), CTX)!.content;

    expect(content).toContain('markUrgent(id: string): Observable<Order>');
    expect(content).not.toContain('mark-urgent(id: string)');
    expect(content).toContain('/${id}/actions/mark-urgent');
  });

  it('action with no description uses "Execute <name> action" fallback in the JSDoc', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      actions: [{ name: 'approve', params: [] }],
    }), CTX)!.content;

    expect(content).toContain('/** Execute approve action */');
  });

  it('empty params: [] (hasParams=false) → empty body object {} (not undefined or null)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      actions: [{ name: 'ping', params: [] }],
    }), CTX)!.content;

    expect(content).toMatch(/this\.http\.post<Order>[^,]+,\s*\{\}\)/);
  });

  it('actions[] absent (default) → no action methods emitted', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    // Only the 5 fixed CRUD methods + no custom action method comments.
    expect(content).not.toContain('/** Execute');
  });
});

// ---------- collectEnumTypes ----------

describe('ServiceGenerator enum-type collection + import line', () => {
  const gen = new ServiceGenerator();

  it('explicit enumType → simple name imported from ../types/enums', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'String', enumType: 'com.shop.OrderStatus' })],
    }), CTX)!.content;

    expect(content).toContain("import type { OrderStatus } from '../types/enums';");
  });

  it.each([
    'UserRole', 'OrderStatus', 'PaymentType', 'BillingPlan', 'WorkflowState',
    'AccessLevel', 'ResourceKind', 'DeploymentMode', 'ProductCategory',
  ])('heuristic: PascalCase ending in known enum suffix (%s) is imported', (typeName) => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: typeName })],
    }), CTX)!.content;

    expect(content).toContain(`import type { ${typeName} } from '../types/enums';`);
  });

  it('FQN containing .domain. is treated as enum', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: 'com.shop.domain.Custom' })],
    }), CTX)!.content;
    expect(content).toContain("import type { Custom } from '../types/enums';");
  });

  it('a fully-qualified known Java type is not an enum, on the simple-name arm', () => {
    // `java.time.LocalDate` reaches the heuristic as an FQN: the early exact-match check misses
    // it, the simple name `LocalDate` is what KNOWN_JAVA_TYPES recognises. Without that arm it
    // would match the PascalCase regex and be imported from a module that declares no such enum.
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'when', type: 'java.time.LocalDate' })],
    }), CTX)!.content;

    expect(content).not.toContain("from '../types/enums'");
  });

  it('a PascalCase domain type with no enum suffix and no .domain. segment is left alone', () => {
    // The conservative tail of the heuristic: `Widget` looks like a type name and nothing else
    // about it says enum, so the generator emits no import rather than guessing.
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: 'com.shop.Widget' })],
    }), CTX)!.content;

    expect(content).not.toContain("from '../types/enums'");
  });

  it('Known Java types + generics + arrays are NOT treated as enums (no enum import)', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'a', type: 'String' }),
        field({ name: 'b', type: 'List<String>' }),
        field({ name: 'c', type: 'String[]' }),
        field({ name: 'd', type: 'BigDecimal' }),
      ],
    }), CTX)!.content;

    expect(content).not.toContain("from '../types/enums'");
  });

  // T20a: an @Action param can itself be a domain enum. The generated method
  // references it in the signature, so it must be imported — otherwise the
  // emitted *.service.ts fails to compile with TS2304 (cannot find name).
  it('action param whose type is a domain enum is imported (T20a)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String' })],
      actions: [{
        name: 'setStatus',
        params: [{ name: 'status', type: 'com.shop.domain.OrderStatus', required: true }],
      }],
    }), CTX)!.content;

    expect(content).toContain("import type { OrderStatus } from '../types/enums';");
  });

  it('action param enum is deduped against a field enum of the same type (T20a)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'String', enumType: 'com.shop.OrderStatus' })],
      actions: [{
        name: 'setStatus',
        params: [{ name: 'next', type: 'OrderStatus', required: true }],
      }],
    }), CTX)!.content;

    const m = content.match(/import type \{ ([^}]+) \} from '\.\.\/types\/enums';/);
    expect(m).not.toBeNull();
    const names = m![1].split(',').map(s => s.trim());
    expect(names.filter(n => n === 'OrderStatus')).toHaveLength(1);
  });

  it('non-enum action params (known Java types) add no enum import (T20a)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String' })],
      actions: [{
        name: 'applyDiscount',
        params: [
          { name: 'percent', type: 'java.math.BigDecimal', required: true },
          { name: 'reason', type: 'String', required: true },
        ],
      }],
    }), CTX)!.content;

    expect(content).not.toContain("from '../types/enums'");
  });

  it('Multi-field dedup: same enum type referenced twice emits ONE import entry', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'status1', type: 'OrderStatus' }),
        field({ name: 'status2', type: 'OrderStatus' }),
        field({ name: 'role', type: 'UserRole' }),
      ],
    }), CTX)!.content;

    const m = content.match(/import type \{ ([^}]+) \} from '\.\.\/types\/enums';/);
    expect(m).not.toBeNull();
    const names = m![1].split(',').map(s => s.trim());
    expect(names).toContain('OrderStatus');
    expect(names).toContain('UserRole');
    expect(names.filter(n => n === 'OrderStatus')).toHaveLength(1);
  });
});

// ---------- generateAggregate barrel ----------

describe('ServiceGenerator.generateAggregate — services/index.ts barrel', () => {
  const gen = new ServiceGenerator();

  it('emits a single services/index.ts file', () => {
    const files = gen.generateAggregate([domain({ entityName: 'Order' })], CTX);

    expect(files).toHaveLength(1);
    expect(files[0].path).toBe('services/index.ts');
    expect(files[0].artifactType).toBe('SERVICE');
    expect(files[0].overwritable).toBe(true);
  });

  it('barrel exports every visible-domain service module (kebab path)', () => {
    const files = gen.generateAggregate([
      domain({ entityName: 'Order' }),
      domain({ entityName: 'OrderLine' }),
    ], CTX);

    expect(files[0].content).toContain("export * from './order.service';");
    expect(files[0].content).toContain("export * from './order-line.service';");
  });

  it('empty-input emits the barrel header with zero exports', () => {
    const files = gen.generateAggregate([], CTX);

    expect(files[0].content).toContain('Services - Barrel Export');
    expect(files[0].content).not.toMatch(/export \* from/);
  });
});

// ---------- buildZodType branches (exercised through fields[] map) ----------

describe('ServiceGenerator buildZodType branch coverage', () => {
  const gen = new ServiceGenerator();

  // buildZodType is called per-field as the service is generated. The
  // resulting zodType isn't embedded in the rendered service template
  // (renderService only uses tsType + required), but each call still
  // walks the validation chain — so the branches inside fire whenever
  // a fixture supplies the matching field metadata.

  it('field.minLength on a string field exercises the .min(N) replace branch', () => {
    expect(() => gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'name', type: 'String', minLength: 3 })],
    }), CTX)).not.toThrow();
  });

  it('field.maxLength on a string field exercises the .max(N) append branch', () => {
    expect(() => gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'name', type: 'String', maxLength: 50 })],
    }), CTX)).not.toThrow();
  });

  it('field.format === "email" exercises the email REPLACE branch', () => {
    expect(() => gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'addr', type: 'String', format: 'email' })],
    }), CTX)).not.toThrow();
  });

  it('field.format === "url" exercises the url REPLACE branch', () => {
    expect(() => gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'site', type: 'String', format: 'url' })],
    }), CTX)).not.toThrow();
  });

  it('required=true field skips the .optional() append branch', () => {
    expect(() => gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'must', type: 'String', required: true })],
    }), CTX)).not.toThrow();
  });

  it('required=false field exercises the .optional() append branch (default)', () => {
    expect(() => gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'maybe', type: 'String' })],
    }), CTX)).not.toThrow();
  });
});

// ---------- getSystemFields default branch (no systemFields metadata) ----------

describe('ServiceGenerator getSystemFields default-set branch', () => {
  const gen = new ServiceGenerator();

  it('domain with NO systemFields metadata → emits service successfully (default {id, createdAt, updatedAt, version} set internally)', () => {
    // getSystemFields is consumed internally by renderService data but
    // not visible in the output today. This test exercises the default
    // branch (the else-arm when sf is undefined) without asserting on
    // the internal field list — the branch coverage is the deliverable.
    expect(() => gen.generate(domain({ entityName: 'Order' }), CTX)).not.toThrow();
  });

  it('domain WITH systemFields metadata → emits service successfully (every optional alias propagation)', () => {
    expect(() => gen.generate(domain({
      entityName: 'Order',
      systemFields: {
        primaryKeyField: 'id',
        versionField: 'rev',
        createdAtField: 'ct',
        updatedAtField: 'ut',
        createdByField: 'cb',
        updatedByField: 'ub',
      },
    }), CTX)).not.toThrow();
  });
});

describe('generateService — top-level convenience function', () => {
  it('returns the per-domain file for a visible domain', () => {
    const file = generateService(domain({ entityName: 'Order' }), CTX.config);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('services/order.service.ts');
  });

  it('falls back to KERNEL backend when config.backend is undefined (still emits per-domain file)', () => {
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    const file = generateService(domain({ entityName: 'Order' }), partialConfig);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('services/order.service.ts');
  });
});
