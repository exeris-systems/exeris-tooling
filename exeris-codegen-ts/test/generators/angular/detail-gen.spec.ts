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
import { DEFAULT_CONFIG } from '../../../src/config.js';
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

  it('explicit systemFields.primaryKeyField alias is appended to the hidden set alongside default "id"', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      systemFields: { primaryKeyField: 'uuid' },
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

  it('uses systemFields.primaryKeyField alias in the fallback when configured', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      systemFields: { primaryKeyField: 'uuid' },
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

// ---------- @Field.dataType render facets (Wave 1A) ----------

describe('DetailGenerator @Field.dataType render facets', () => {
  const gen = new DetailGenerator();

  it("dataType 'currency' tags the FieldDisplay and renders a | currency switch arm", () => {
    const content = gen.generate(domain({
      entityName: 'Invoice',
      fields: [field({ name: 'amount', type: 'BigDecimal', dataType: 'currency' })],
    }), CTX)!.content;

    expect(content).toContain("dataType: 'currency'");
    // numericValue, not rawValue: the currency/percent pipes reject `unknown`, and Angular
    // type-checks every @switch branch whether or not a field selects it.
    expect(content).toContain("@case ('currency') { {{ numericValue(field, entity()) | currency }} }");
    expect(content).toContain('rawValue(field: FieldDisplay');
  });

  it("dataType 'percent' renders a | percent switch arm", () => {
    const content = gen.generate(domain({
      entityName: 'Stat',
      fields: [field({ name: 'rate', type: 'Double', dataType: 'percent' })],
    }), CTX)!.content;

    expect(content).toContain("dataType: 'percent'");
    expect(content).toContain("@case ('percent') { {{ numericValue(field, entity()) | percent }} }");
  });

  it("dataType 'url' renders an <a [href]> switch arm", () => {
    const content = gen.generate(domain({
      entityName: 'Site',
      fields: [field({ name: 'homepage', type: 'String', dataType: 'url' })],
    }), CTX)!.content;

    expect(content).toContain("dataType: 'url'");
    expect(content).toContain('<a [href]="rawValue(field, entity())"');
  });

  it('unknown / absent dataType is not tagged and keeps the formatValue default arm', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'plain', type: 'String' }),
        field({ name: 'weird', type: 'String', dataType: 'rainbow' }),
      ],
    }), CTX)!.content;

    // Neither field carries a dataType tag in DISPLAY_FIELDS…
    expect(content).not.toContain("dataType: 'rainbow'");
    // …and the default switch arm (formatValue) is always present.
    expect(content).toContain('@default { {{ formatValue(field, entity()) }} }');
  });
});

// ---------------------------------------------------------------------------
// Wired into the orchestrator (0.8.0). Everything below is a defect the generator
// carried while `generateDetails` defaulted to true and was read by nobody — so its
// output had never been compiled. Each one failed the `ng build` gate.
// ---------------------------------------------------------------------------

describe('DetailGenerator — defects exposed by wiring', () => {
  const withFields = (fields: Array<Record<string, unknown>>) =>
    DomainMetadataSchema.parse({ packageName: 'com.shop', entityName: 'Order', fields });

  const idOnly = withFields([{ name: 'id', type: 'java.util.UUID' }]);
  const stamped = withFields([
    { name: 'id', type: 'java.util.UUID' },
    { name: 'createdAt', type: 'java.time.Instant' },
    { name: 'updatedAt', type: 'java.time.Instant' },
  ]);

  // `$localize` is a global that exists only once the consumer adds @angular/localize and a
  // polyfill entry; the emitted app declares neither. Same rule store-gen recorded, and the
  // same rule ADR-060 applied to slf4j on the Java side.
  it('emits no $localize — it would be an undeclared requirement on the consumer build', () => {
    expect(generateDetail(idOnly, DEFAULT_CONFIG).content).not.toContain('$localize');
  });

  it('emits field labels as plain quoted strings', () => {
    const content = generateDetail(
      withFields([{ name: 'id', type: 'java.util.UUID' }, { name: 'total', type: 'java.math.BigDecimal' }]),
      DEFAULT_CONFIG,
    ).content;
    expect(content).toContain("label: 'Total'");
  });

  // TS2339 before: emitted unconditionally against entities that declare neither field.
  it('renders the audit stamps only for an entity that declares them', () => {
    const bare = generateDetail(idOnly, DEFAULT_CONFIG).content;
    expect(bare).not.toContain('createdAt');
    expect(bare).not.toContain('updatedAt');

    const withStamps = generateDetail(stamped, DEFAULT_CONFIG).content;
    expect(withStamps).toContain("entity()?.createdAt | date:'medium'");
    expect(withStamps).toContain("entity()?.updatedAt | date:'medium'");
  });

  // Angular reports an unused standalone import against the template, so DatePipe may only be
  // brought in when something actually renders a date.
  it('imports DatePipe only when a stamp is rendered', () => {
    expect(generateDetail(idOnly, DEFAULT_CONFIG).content).not.toContain('DatePipe');
    const withStamps = generateDetail(stamped, DEFAULT_CONFIG).content;
    expect(withStamps).toContain("import { CommonModule, DatePipe } from '@angular/common';");
    expect(withStamps).toContain('imports: [CommonModule, RouterModule, DatePipe],');
  });

  // TS2769 before: the currency/percent pipes accept string|number|null|undefined, never
  // `unknown`, and Angular type-checks every @switch branch whether or not a field selects it —
  // so this failed for EVERY entity, with or without a currency field.
  it('feeds the currency and percent pipes a number, not unknown', () => {
    const content = generateDetail(idOnly, DEFAULT_CONFIG).content;
    expect(content).toContain('numericValue(field: FieldDisplay, entity: Order | null): number | null');
    expect(content).toContain("@case ('currency') { {{ numericValue(field, entity()) | currency }} }");
    expect(content).not.toContain('rawValue(field, entity()) | currency');
  });

  it('honours a renamed audit field from systemFields', () => {
    const renamed = DomainMetadataSchema.parse({
      packageName: 'com.shop', entityName: 'Order',
      fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'insertedOn', type: 'java.time.Instant' }],
      systemFields: { createdAtField: 'insertedOn' },
    });
    const content = generateDetail(renamed, DEFAULT_CONFIG).content;
    expect(content).toContain("entity()?.insertedOn | date:'medium'");
    expect(content).not.toContain('entity()?.createdAt');
  });
});

describe('DetailGenerator — post-delete navigation', () => {
  const entity = (entityName: string) =>
    DomainMetadataSchema.parse({
      packageName: 'com.shop', entityName,
      fields: [{ name: 'id', type: 'java.util.UUID' }],
    });

  it('navigates to the plural the route table actually declares', () => {
    expect(generateDetail(entity('Order'), DEFAULT_CONFIG).content)
      .toContain("this.router.navigate(['/orders'])");
  });

  // The route table uses routePlural, which does NOT append a second 's'. detail-gen appended
  // one unconditionally, so a successful delete left the user on a URL with no matching route.
  it('does not double the s for an entity whose name already ends in one', () => {
    const content = generateDetail(entity('Address'), DEFAULT_CONFIG).content;
    expect(content).toContain("this.router.navigate(['/address'])");
    expect(content).not.toContain('addresss');
  });
});
