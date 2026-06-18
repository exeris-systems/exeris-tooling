/**
 * Coverage for src/generators/angular/detail-gen.ts — DetailGenerator
 * emits an Angular 21 standalone component with Signal-based state +
 * the `rxResource()` API for data fetching. Exercises:
 *   - getSystemFieldNames merge (default set + idField alias + every
 *     optional systemFields.* propagation)
 *   - getDisplayType matrix (enum / boolean / date / datetime / number /
 *     text fallback)
 *   - isEnumType heuristic (suffix Status/Type/Role/State + known-types
 *     skip + generic/array skip + java.* prefix skip + lowercase skip)
 *   - getEnumTypeName precedence (explicit enumType > isEnumType heuristic)
 *   - collectEnumTypes dedup
 *   - getTitle fallback (name/title field → idField)
 */

import { describe, expect, it } from 'vitest';
import { DetailGenerator, generateDetail } from '../../../src/generators/angular/detail-gen.js';
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

function hiddenDomain(entityName: string): DomainMetadata {
  return domain({
    entityName,
    internalApi: { hidden: true, readOnly: false, internal: false },
  });
}

// ---------- CodeGenerator contract ----------

describe('DetailGenerator — CodeGenerator metadata', () => {
  const gen = new DetailGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('DetailGenerator');
    expect(gen.artifactType).toBe('DETAIL');
    expect(gen.priority).toBe(20);
    expect(gen.supportedBackends).toEqual([]);
  });
});

// ---------- generate — path + hidden-skip ----------

describe('DetailGenerator.generate — emit path + hidden-skip', () => {
  const gen = new DetailGenerator();

  it('emits components/<kebab>-detail.component.ts for a visible domain', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine' }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-line-detail.component.ts');
    expect(file!.artifactType).toBe('DETAIL');
    expect(file!.overwritable).toBe(true);
  });

  it('returns null for an internalApi.hidden domain', () => {
    expect(gen.generate(hiddenDomain('Audit'), CTX)).toBeNull();
  });
});

// ---------- emitted content structure ----------

describe('DetailGenerator emitted content — structural markers', () => {
  const gen = new DetailGenerator();

  it('imports Component / signals / rxResource / RouterModule + the entity Service + the entity type', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("import {");
    expect(content).toContain('Component,');
    expect(content).toContain('signal,');
    expect(content).toContain('computed,');
    expect(content).toContain('input,');
    expect(content).toContain("from '@angular/core';");
    // B3: rxResource comes from rxjs-interop, not the core barrel.
    expect(content).toContain("import { rxResource } from '@angular/core/rxjs-interop';");
    expect(content).toContain("import { OrderService } from '../services/order.service';");
    expect(content).toContain("import type { Order } from '../types/order.types';");
  });

  it('Component decorator includes app-<kebab>-detail selector + standalone + OnPush', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;

    expect(content).toContain("selector: 'app-order-line-detail'");
    expect(content).toContain('standalone: true');
    expect(content).toContain('ChangeDetectionStrategy.OnPush');
  });

  it('DetailComponent class wires id input + resource loader + entity/isLoading/error computed signals', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('export class OrderDetailComponent {');
    expect(content).toContain('readonly id = input.required<string>();');
    // B3: rxResource (stable v22) bridges the Observable-returning service — v22 keys are
    // `params`/`stream`, not `request`/`loader` (the import is asserted in the imports test).
    expect(content).toContain('private readonly entityResource = rxResource({');
    expect(content).toContain('params: () => this.id()');
    expect(content).toContain('stream: ({ params }) => this.service.findById(params)');
    expect(content).toContain('readonly entity = computed');
    expect(content).toContain('readonly isLoading = computed');
    expect(content).toContain('readonly error = computed');
  });

  it('onDelete uses displayName fallback in the confirm prompt, lowercased', () => {
    const withDisplay = gen.generate(
      domain({ entityName: 'Order', displayName: 'Sales Order' }),
      CTX,
    )!.content;
    expect(withDisplay).toContain("confirm('Are you sure you want to delete this sales order?')");

    const withoutDisplay = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    expect(withoutDisplay).toContain("confirm('Are you sure you want to delete this order?')");
  });

  it('after successful delete navigates to /<kebab>s pluralised list', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;
    expect(content).toContain("this.router.navigate(['/order-lines'])");
  });
});

// ---------- system-field filtering (display fields) ----------

describe('DetailGenerator system-field filtering', () => {
  const gen = new DetailGenerator();

  it('default system-field set hides id / version / createdAt / updatedAt / createdBy / updatedBy / tenantId / deletedAt / deleted from the displayed DISPLAY_FIELDS table', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'id', type: 'UUID' }),
        field({ name: 'version', type: 'Long' }),
        field({ name: 'createdAt', type: 'Instant' }),
        field({ name: 'updatedAt', type: 'Instant' }),
        field({ name: 'createdBy', type: 'String' }),
        field({ name: 'tenantId', type: 'UUID' }),
        field({ name: 'orderNumber', type: 'String' }), // VISIBLE
      ],
    }), CTX)!.content;

    // The DISPLAY_FIELDS array entries use { name: '<fieldName>' as keyof Order, ...
    expect(content).toContain("name: 'orderNumber' as keyof Order");
    expect(content).not.toContain("name: 'id' as keyof Order");
    expect(content).not.toContain("name: 'version' as keyof Order");
    expect(content).not.toContain("name: 'createdAt' as keyof Order");
    expect(content).not.toContain("name: 'updatedAt' as keyof Order");
  });

  it('explicit systemFields.idField alias is appended to the hidden set alongside default "id"', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      systemFields: { idField: 'uuid' },
      fields: [
        field({ name: 'uuid', type: 'UUID' }),       // hidden by the alias
        field({ name: 'orderNumber', type: 'String' }), // visible
      ],
    }), CTX)!.content;

    expect(content).not.toContain("name: 'uuid' as keyof Order");
    expect(content).toContain("name: 'orderNumber' as keyof Order");
  });

  it('field.hidden=true is filtered out regardless of system-field membership', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'secretCode', type: 'String', hidden: true }),
        field({ name: 'orderNumber', type: 'String' }),
      ],
    }), CTX)!.content;

    expect(content).not.toContain("name: 'secretCode' as keyof Order");
    expect(content).toContain("name: 'orderNumber' as keyof Order");
  });
});

// ---------- getDisplayType matrix ----------

describe('DetailGenerator field display-type matrix', () => {
  const gen = new DetailGenerator();

  function displayTypeFor(fieldType: string, extras: Partial<FieldMetadata> = {}): string {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: fieldType, ...extras })],
    }), CTX)!.content;
    // Pull the type out of the DISPLAY_FIELDS row: type: '<type>'
    const match = content.match(/name: 'attr' as keyof Thing, label: [^,]+, type: '([^']+)'/);
    expect(match, `should find an attr row in DISPLAY_FIELDS for type=${fieldType}`).not.toBeNull();
    return match![1];
  }

  it.each([
    ['Boolean', 'boolean'],
    ['boolean', 'boolean'],
    ['LocalDate', 'date'],
    ['Instant', 'datetime'],
    ['LocalDateTime', 'datetime'],
    ['Integer', 'number'],
    ['Long', 'number'],
    ['number', 'number'],
    ['String', 'text'],
    ['BigDecimal', 'text'], // not in the special-case list → text fallback
  ])('field type %s → display type %s', (fieldType, expected) => {
    expect(displayTypeFor(fieldType)).toBe(expected);
  });

  it('explicit enumType promotes the display type to "enum" regardless of base type', () => {
    expect(displayTypeFor('String', { enumType: 'OrderStatus' })).toBe('enum');
  });

  it('PascalCase type ending in Status / Type / Role / State is auto-detected as enum (isEnumType heuristic)', () => {
    expect(displayTypeFor('OrderStatus')).toBe('enum');
    expect(displayTypeFor('PaymentType')).toBe('enum');
    expect(displayTypeFor('UserRole')).toBe('enum');
    expect(displayTypeFor('WorkflowState')).toBe('enum');
  });

  it('PascalCase type WITHOUT a known suffix is NOT auto-detected as enum (fallback "text")', () => {
    expect(displayTypeFor('Customer')).toBe('text');
  });

  it('java.* FQN is rejected by isEnumType (returns text via the unknown-type fallback)', () => {
    expect(displayTypeFor('java.util.UUID')).toBe('text');
  });

  it('generic + array types are rejected by isEnumType', () => {
    expect(displayTypeFor('List<String>')).toBe('text');
    expect(displayTypeFor('String[]')).toBe('text');
  });
});

// ---------- enum collection + import line ----------

describe('DetailGenerator enum collection + import line', () => {
  const gen = new DetailGenerator();

  it('enum-typed fields produce an import { Enum, EnumDisplayNames } line from ../types/enums', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'OrderStatus' })], // matches isEnumType
    }), CTX)!.content;

    expect(content).toContain("import { OrderStatus, OrderStatusDisplayNames } from '../types/enums';");
  });

  it('explicit FQN enumType is stripped to the simple name in the import + DISPLAY_FIELDS entry', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'priority', type: 'String', enumType: 'com.shop.Priority' })],
    }), CTX)!.content;

    expect(content).toContain("import { Priority, PriorityDisplayNames } from '../types/enums';");
    expect(content).toContain("enumType: 'Priority'");
  });

  it('multiple enum-typed fields → single import line listing each enum + its DisplayNames map (de-duped)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'status', type: 'OrderStatus' }),
        field({ name: 'status2', type: 'OrderStatus' }), // duplicate → must not double-import
        field({ name: 'role', type: 'UserRole' }),
      ],
    }), CTX)!.content;

    // One import line, OrderStatus + OrderStatusDisplayNames + UserRole + UserRoleDisplayNames all in it.
    const importMatch = content.match(/import \{ ([^}]+) \} from '\.\.\/types\/enums';/);
    expect(importMatch).not.toBeNull();
    const names = importMatch![1].split(',').map(s => s.trim());
    expect(names).toContain('OrderStatus');
    expect(names).toContain('OrderStatusDisplayNames');
    expect(names).toContain('UserRole');
    expect(names).toContain('UserRoleDisplayNames');
    // de-duped — OrderStatus appears exactly once in the import set.
    expect(names.filter(n => n === 'OrderStatus')).toHaveLength(1);
  });

  it('no enum-typed fields → no ../types/enums import emitted', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'name', type: 'String' })],
    }), CTX)!.content;

    expect(content).not.toContain("from '../types/enums'");
  });

  it('per-enum private DisplayNames field uses camelCase (first-char lowered, rest preserved) — NOT all-lowercase', () => {
    // The earlier source did `enumType.toLowerCase()` which produced
    // identifiers like `orderstatusDisplayNames` for OrderStatus.
    // After the casing fix, the first char is lowered but subsequent
    // capitals survive: orderStatus, oAuthClient, etc.
    const single = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'OrderStatus' })],
    }), CTX)!.content;
    expect(single).toContain('private readonly orderStatusDisplayNames = OrderStatusDisplayNames;');
    expect(single).not.toContain('orderstatusDisplayNames');

    // Multi-cap enum name preserves its internal capitals.
    const multiCap = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'client', type: 'String', enumType: 'OAuthClient' })],
    }), CTX)!.content;
    expect(multiCap).toContain('private readonly oAuthClientDisplayNames = OAuthClientDisplayNames;');
    expect(multiCap).not.toContain('oauthclientDisplayNames');
  });

  it('getEnumDisplayName switch dispatch references the same camelCase field name', () => {
    // The switch body at the bottom of the generated class reads
    // `this.<fieldName>[value]`. It must use the SAME camelCase
    // identifier the class field was declared under — otherwise
    // the lookup is undefined at runtime.
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'OrderStatus' })],
    }), CTX)!.content;

    expect(content).toContain('this.orderStatusDisplayNames[value as keyof typeof this.orderStatusDisplayNames]');
  });
});

// ---------- getTitle fallback ----------

describe('DetailGenerator getTitle fallback', () => {
  const gen = new DetailGenerator();

  it("emits entity.<name> when a display field named 'name' is present", () => {
    const content = gen.generate(domain({
      entityName: 'Customer',
      fields: [field({ name: 'name', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('return entity.name ?? String(entity.id);');
  });

  it("emits entity.<title> when a display field named 'title' is present (no 'name')", () => {
    const content = gen.generate(domain({
      entityName: 'Article',
      fields: [field({ name: 'title', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('return entity.title ?? String(entity.id);');
  });

  it('falls back to String(entity.id) when no name/title field exists', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'description', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('return String(entity.id);');
  });

  it('uses systemFields.idField alias in the fallback when configured', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      systemFields: { idField: 'uuid' },
    }), CTX)!.content;

    expect(content).toContain('return String(entity.uuid);');
  });
});

// ---------- generateDetail convenience ----------

describe('generateDetail — top-level convenience function', () => {
  it('routes through DetailGenerator and returns the per-domain file', () => {
    const file = generateDetail(domain({ entityName: 'Order' }), CTX.config);

    expect(file.path).toBe('components/order-detail.component.ts');
    expect(file.content).toContain('export class OrderDetailComponent');
  });

  it('falls back to KERNEL backend when config.backend is undefined (still emits the per-domain file)', () => {
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    const file = generateDetail(domain({ entityName: 'Order' }), partialConfig);

    expect(file.path).toBe('components/order-detail.component.ts');
    expect(file.content).toContain('export class OrderDetailComponent');
  });
});
