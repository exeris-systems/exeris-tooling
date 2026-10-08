/**
 * Coverage for src/generators/angular/detail-gen.ts — DetailGenerator
 * emits an Angular 21 standalone component with Signal-based state +
 * the `rxResource()` API for data fetching. Exercises:
 *   - system-field filtering (type-gen's viewSystemFieldNames) and the
 *     system panel (audit stamps on `audited`, version on `versioned`)
 *   - display-type matrix (enum / boolean / date / datetime / number /
 *     text fallback), dates through DatePipe
 *   - the enum rule (explicit enumType, else a type naming a declared enum)
 *   - sections, the related-records panels (children read through the list route's `<base>Id`
 *     filter, ADR-096) and links, and the action buttons
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

/** A context whose app declares the enums `names` name, in package com.shop. */
function enumCtx(...names: string[]): GeneratorContext {
  return createGeneratorContext({}, [], names.map((name) => ({
    name,
    qualifiedName: `com.shop.${name}`,
    packageName: 'com.shop',
    values: [{ name: 'A', displayName: 'A', ordinal: 0 }],
  })));
}

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

function field(overrides: Partial<FieldMetadata> & { name: string; type: string }): FieldMetadata {
  return FieldMetadataSchema.parse(overrides);
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

  it('the key primaryKeyField names leaves the field table for the system panel', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      systemFields: { primaryKeyField: 'orderNo' },
      fields: [
        field({ name: 'orderNo', type: 'UUID' }),
        field({ name: 'orderNumber', type: 'String' }),
      ],
    }), CTX)!.content;

    expect(content).not.toContain("name: 'orderNo' as keyof Order");
    expect(content).toContain("name: 'orderNumber' as keyof Order");
    expect(content).toContain('{{ entity()?.orderNo }}');
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

  function displayTypeFor(fieldType: string, extras: Partial<FieldMetadata> = {}, ctx = CTX): string {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: fieldType, ...extras })],
    }), ctx)!.content;
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
    ['long', 'number'],
    ['java.lang.Boolean', 'boolean'],
    ['java.time.OffsetDateTime', 'datetime'],
    ['String', 'text'],
    ['BigDecimal', 'text'], // not in the special-case list → text fallback
  ])('field type %s → display type %s', (fieldType, expected) => {
    expect(displayTypeFor(fieldType)).toBe(expected);
  });

  it('explicit enumType promotes the display type to "enum" regardless of base type', () => {
    expect(displayTypeFor('String', { enumType: 'OrderStatus' })).toBe('enum');
  });

  it('a type naming a declared enum is an enum, qualified or simple', () => {
    const ctx = enumCtx('OrderStatus');
    expect(displayTypeFor('com.shop.OrderStatus', {}, ctx)).toBe('enum');
    expect(displayTypeFor('OrderStatus', {}, ctx)).toBe('enum');
  });

  it('a type named like an enum that no declared enum matches is text: nothing could be imported for it', () => {
    expect(displayTypeFor('OrderStatus')).toBe('text');
    expect(displayTypeFor('PaymentType')).toBe('text');
    expect(displayTypeFor('com.other.OrderStatus', {}, enumCtx('OrderStatus'))).toBe('text');
    expect(displayTypeFor('Customer')).toBe('text');
  });

  it('JDK, generic and array types are text', () => {
    expect(displayTypeFor('java.util.UUID')).toBe('text');
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
      fields: [field({ name: 'status', type: 'com.shop.OrderStatus' })],
    }), enumCtx('OrderStatus'))!.content;

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
    }), enumCtx('OrderStatus', 'UserRole'))!.content;

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
    }), enumCtx('OrderStatus'))!.content;
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
    }), enumCtx('OrderStatus'))!.content;

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

  it('falls back to the key primaryKeyField names for the title', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'thingNo', type: 'UUID' })],
      systemFields: { primaryKeyField: 'thingNo' },
    }), CTX)!.content;

    expect(content).toContain('return String(entity.thingNo);');
    expect(content).not.toMatch(/\bentity\.id\b/);
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

  // The route table uses routePlural, the SDK plural; navigating anywhere else after a delete
  // leaves the user on a URL with no matching route.
  it('navigates to the SDK plural for an entity whose name ends in s', () => {
    const content = generateDetail(entity('Address'), DEFAULT_CONFIG).content;
    expect(content).toContain("this.router.navigate(['/addresses'])");
    expect(content).not.toContain('addresss');
  });

  it('navigates to the ies plural for a consonant + y entity', () => {
    expect(generateDetail(entity('Colony'), DEFAULT_CONFIG).content)
      .toContain("this.router.navigate(['/colonies'])");
  });
});

describe('DetailGenerator — system panel', () => {
  const parse = (o: Record<string, unknown>) =>
    DomainMetadataSchema.parse({ packageName: 'com.shop', entityName: 'Ticket', ...o });
  const id = { name: 'id', type: 'java.util.UUID' };
  const title = { name: 'title', type: 'String' };

  it('renders the id only for an entity that is neither audited nor versioned and declares no stamp', () => {
    const content = generateDetail(parse({ fields: [id, title] }), DEFAULT_CONFIG).content;
    expect(content).toContain('System Information');
    expect(content).toContain('{{ entity()?.id }}');
    expect(content).not.toContain('<dt class="text-gray-400">Created</dt>');
    expect(content).not.toContain('<dt class="text-gray-400">Version</dt>');
    expect(content).not.toContain('systemInfo');
    expect(content).not.toContain('DatePipe');
  });

  it('renders the stamps of an audited entity that does not declare them, through a narrowing cast', () => {
    const content = generateDetail(parse({ audited: true, fields: [id, title] }), DEFAULT_CONFIG).content;
    expect(content).toContain(
      'readonly systemInfo = computed(() => this.entity() as unknown as { createdAt?: string; updatedAt?: string } | null);',
    );
    expect(content).toContain("@if (systemInfo()?.createdAt) { <div><dt class=\"text-gray-400\">Created</dt><dd>{{ systemInfo()?.createdAt | date:'medium' }}</dd></div> }");
    expect(content).toContain("@if (systemInfo()?.updatedAt) { <div><dt class=\"text-gray-400\">Updated</dt><dd>{{ systemInfo()?.updatedAt | date:'medium' }}</dd></div> }");
    expect(content).toContain('imports: [CommonModule, RouterModule, DatePipe],');
    expect(content).not.toContain('entity()?.createdAt');
  });

  it('names the stamps through systemFields on an audited entity', () => {
    const content = generateDetail(parse({
      audited: true,
      systemFields: { createdAtField: 'openedAt', updatedAtField: 'touchedAt' },
      fields: [id, title, { name: 'openedAt', type: 'java.time.Instant' }],
    }), DEFAULT_CONFIG).content;
    // Declared: read off the entity. Undeclared: read off the cast.
    expect(content).toContain("{{ entity()?.openedAt | date:'medium' }}");
    expect(content).toContain("{{ systemInfo()?.touchedAt | date:'medium' }}");
    expect(content).toContain('this.entity() as unknown as { touchedAt?: string } | null');
    expect(content).not.toContain('createdAt');
    expect(content).not.toContain('updatedAt');
    // A declared stamp leaves the field table for the panel.
    expect(content).not.toContain("name: 'openedAt' as keyof Ticket");
  });

  it('renders the version of a versioned entity, keyed on systemFields.versionField', () => {
    const content = generateDetail(parse({
      versioned: true,
      systemFields: { versionField: 'revision' },
      fields: [id, title, { name: 'revision', type: 'java.lang.Long' }],
    }), DEFAULT_CONFIG).content;
    expect(content).toContain('@if (entity()?.revision != null) { <div><dt class="text-gray-400">Version</dt><dd class="font-mono text-gray-600 dark:text-gray-300">{{ entity()?.revision }}</dd></div> }');
    expect(content).not.toContain("name: 'revision' as keyof Ticket");
    expect(content).not.toContain('systemInfo');
    expect(content).not.toContain('DatePipe');
  });

  it('reads an undeclared version through the cast', () => {
    const content = generateDetail(parse({ versioned: true, fields: [id, title] }), DEFAULT_CONFIG).content;
    expect(content).toContain('this.entity() as unknown as { version?: number } | null');
    expect(content).toContain('@if (systemInfo()?.version != null)');
  });

  it('orders the rows id, created, updated, version', () => {
    const content = generateDetail(parse({ audited: true, versioned: true, fields: [id, title] }), DEFAULT_CONFIG).content;
    const at = (s: string) => content.indexOf(s);
    expect(at('{{ entity()?.id }}')).toBeLessThan(at('>Created</dt>'));
    expect(at('>Created</dt>')).toBeLessThan(at('>Updated</dt>'));
    expect(at('>Updated</dt>')).toBeLessThan(at('>Version</dt>'));
    expect(content).toContain('{ createdAt?: string; updatedAt?: string; version?: number }');
  });

  it('shows a declared stamp an unaudited entity carries, as before', () => {
    const content = generateDetail(parse({
      fields: [id, title, { name: 'createdAt', type: 'java.time.Instant' }],
    }), DEFAULT_CONFIG).content;
    expect(content).toContain("@if (entity()?.createdAt) { <div><dt class=\"text-gray-400\">Created</dt><dd>{{ entity()?.createdAt | date:'medium' }}</dd></div> }");
    expect(content).not.toContain('Updated</dt>');
    expect(content).not.toContain('systemInfo');
  });
});

// ---------- dates ----------

describe('DetailGenerator — dates render through DatePipe', () => {
  const gen = new DetailGenerator();

  it('a LocalDate renders with mediumDate and a date-time with medium, an empty one as the em-dash', () => {
    const content = gen.generate(domain({
      entityName: 'Booking',
      fields: [field({ name: 'day', type: 'java.time.LocalDate' }), field({ name: 'at', type: 'java.time.OffsetDateTime' })],
    }), CTX)!.content;
    expect(content).toContain("import { CommonModule, DatePipe } from '@angular/common';");
    expect(content).toContain("{{ (dateValue(field, entity()) | date:'mediumDate') ?? '—' }}");
    expect(content).toContain("{{ (dateValue(field, entity()) | date:'medium') ?? '—' }}");
    expect(content).toContain('dateValue(field: FieldDisplay, entity: Booking | null): string | number | null {');
    expect(content).not.toContain('toLocaleDateString');
    expect(content).not.toContain('toLocaleString');
  });

  it('an entity with no date field emits neither the date arms nor dateValue', () => {
    const content = gen.generate(domain({ entityName: 'Plain', fields: [field({ name: 'name', type: 'String' })] }), CTX)!.content;
    expect(content).not.toContain('dateValue(');
    expect(content).not.toContain('DatePipe');
  });
});

// ---------- sections ----------

describe('DetailGenerator — sections', () => {
  const gen = new DetailGenerator();

  it('labels the field table "Details" and the system panel "System Information", each a labelled section', () => {
    const content = gen.generate(domain({ entityName: 'Plain', fields: [field({ name: 'name', type: 'String' })] }), CTX)!.content;
    expect(content).toContain('<section aria-labelledby="details-title"');
    expect(content).toContain('<h2 id="details-title"');
    expect(content).toContain('<section aria-labelledby="system-title"');
    expect(content).toContain('<h2 id="system-title" class="exeris-card-header text-sm font-medium text-gray-500 dark:text-gray-400">System Information</h2>');
    expect(content).not.toContain('Related records');
  });
});

// ---------- related records ----------

describe('DetailGenerator — related records', () => {
  const gen = new DetailGenerator();
  const line = domain({ entityName: 'OrderLine', fields: [field({ name: 'id', type: 'java.util.UUID' })] });
  const note = domain({ entityName: 'Note', fields: [field({ name: 'id', type: 'java.util.UUID' })] });
  const order = (relationships: Array<Record<string, unknown>>) =>
    domain({ entityName: 'Order', fields: [field({ name: 'id', type: 'java.util.UUID' })], relationships: relationships as never });

  it('links each ONE_TO_MANY to its target list, in declaration order, qualified targets by simple name', () => {
    const d = order([
      { name: 'lines', targetEntity: 'com.shop.OrderLine', type: 'ONE_TO_MANY', mappedBy: 'orderId' },
      { name: 'notes', targetEntity: 'Note', type: 'ONE_TO_MANY' },
    ]);
    const content = gen.generate(d, createGeneratorContext({}, [d, line, note]))!.content;
    expect(content).toContain('<h2 id="related-title"');
    const lines = content.indexOf('<a routerLink="/order-lines" data-testid="related-lines"');
    const notes = content.indexOf('<a routerLink="/notes" data-testid="related-notes"');
    expect(lines).toBeGreaterThan(-1);
    expect(notes).toBeGreaterThan(lines);
    expect(content).toContain('>View all OrderLines</a>');
    expect(content).toContain('<span class="font-medium text-gray-900 dark:text-white">Lines</span>');
    // Neither target has a MANY_TO_ONE back to Order, so no child is fetched.
    expect(content).not.toContain('OrderLineService');
    expect(content).not.toContain('findAll');
  });

  describe('a child with a MANY_TO_ONE back to the record', () => {
    const child = (extra: Record<string, unknown> = {}) => domain({
      entityName: 'OrderLine',
      fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'sku', type: 'String' })],
      relationships: [{ name: 'order', targetEntity: 'com.shop.Order', type: 'MANY_TO_ONE' }] as never,
      ...extra,
    });
    const parent = (rel: Record<string, unknown> = {}) =>
      order([{ name: 'lines', targetEntity: 'OrderLine', type: 'ONE_TO_MANY', ...rel }]);

    it('reads the first rows through the child service, filtered by <base>Id, and links each to its detail', () => {
      const d = parent({ displayField: 'sku' });
      const content = gen.generate(d, createGeneratorContext({}, [d, child()]))!.content;
      expect(content).toContain("import { OrderLineService } from '../services/order-line.service';");
      expect(content).toContain('  private readonly orderLineService = inject(OrderLineService);');
      expect(content).toContain([
        '  protected readonly relatedLinesResource = rxResource({',
        '    params: () => this.id(),',
        '    stream: ({ params }) => this.orderLineService.findAll({ size: 10 }, { orderId: params }),',
        '  });',
        '  readonly relatedLines = computed(() => (this.relatedLinesResource.hasValue() ? this.relatedLinesResource.value().content : []));',
      ].join('\n'));
      expect(content).toContain('data-testid="related-lines-rows"');
      expect(content).toContain('@for (row of relatedLines(); track row.id) {');
      expect(content).toContain(`<a [routerLink]="['/order-lines', row.id]" [attr.data-testid]="'related-lines-' + row.id" class="text-exeris-primary hover:underline">{{ row.sku || row.id }}</a>`);
      // The link to the whole list stays, under its test id.
      expect(content).toContain('<a routerLink="/order-lines" data-testid="related-lines" class="text-exeris-primary hover:underline">View all OrderLines</a>');
      expect(content).toContain('@if (relatedLinesResource.error()) {');
    });

    it('labels a row by its id when the relationship names no field the child declares', () => {
      const d = parent({ displayField: 'missing' });
      const content = gen.generate(d, createGeneratorContext({}, [d, child()]))!.content;
      expect(content).toContain(`'related-lines-' + row.id" class="text-exeris-primary hover:underline">{{ row.id }}</a>`);
    });

    it('lists the children without a "View all" link when the child has no list page, and as text without a detail page', () => {
      const d = parent();
      const hidden = child({ uiMetadata: { listView: false, detailView: false } });
      const content = gen.generate(d, createGeneratorContext({}, [d, hidden]))!.content;
      expect(content).not.toContain('View all');
      expect(content).toContain(`<span [attr.data-testid]="'related-lines-' + row.id">{{ row.id }}</span>`);
    });

    it('takes the back-reference mappedBy names, by relationship, field or <base>Id name', () => {
      const two = child({
        relationships: [
          { name: 'order', targetEntity: 'Order', type: 'MANY_TO_ONE' },
          { name: 'replacedOrderId', targetEntity: 'Order', type: 'MANY_TO_ONE' },
        ],
      });
      for (const mappedBy of ['replacedOrderId', 'replacedOrder']) {
        const d = parent({ mappedBy });
        const content = gen.generate(d, createGeneratorContext({}, [d, two]))!.content;
        expect(content, mappedBy).toContain('findAll({ size: 10 }, { replacedOrderId: params })');
      }
      const ambiguous = parent();
      expect(gen.generate(ambiguous, createGeneratorContext({}, [ambiguous, two]))!.content).not.toContain('findAll');
    });

    it('fetches nothing without generated services, or when the child has no id', () => {
      const d = parent();
      expect(gen.generate(d, createGeneratorContext({ generateServices: false }, [d, child()]))!.content).not.toContain('findAll');
      const idless = child({ fields: [field({ name: 'sku', type: 'String' })] });
      expect(gen.generate(d, createGeneratorContext({}, [d, idless]))!.content).not.toContain('findAll');
    });

    it('reads a ONE_TO_MANY to the entity itself through its own service', () => {
      const node = domain({
        entityName: 'Node',
        fields: [field({ name: 'id', type: 'java.util.UUID' })],
        relationships: [
          { name: 'parent', targetEntity: 'Node', type: 'MANY_TO_ONE' },
          { name: 'children', targetEntity: 'Node', type: 'ONE_TO_MANY', mappedBy: 'parent' },
        ] as never,
      });
      const content = gen.generate(node, createGeneratorContext({}, [node]))!.content;
      expect(content).toContain('this.service.findAll({ size: 10 }, { parentId: params })');
      expect(content.match(/NodeService/g)).toHaveLength(2);
    });
  });

  it('shows nothing for a MANY_TO_MANY, an unloaded target, or a target it can neither list nor link', () => {
    const unlisted = domain({ entityName: 'Note', fields: [field({ name: 'id', type: 'java.util.UUID' })], uiMetadata: { listView: false } as never });
    const cases: Array<[DomainMetadata, GeneratorContext]> = [];
    const m2m = order([{ name: 'notes', targetEntity: 'Note', type: 'MANY_TO_MANY' }]);
    cases.push([m2m, createGeneratorContext({}, [m2m, note])]);
    const missing = order([{ name: 'notes', targetEntity: 'Note', type: 'ONE_TO_MANY' }]);
    cases.push([missing, createGeneratorContext({}, [missing])]);
    cases.push([missing, createGeneratorContext({}, [missing, unlisted])]);
    cases.push([missing, createGeneratorContext({ generateLists: false }, [missing, note])]);
    for (const [d, ctx] of cases) {
      expect(gen.generate(d, ctx)!.content).not.toContain('Related records');
    }
  });
});

// ---------- actions ----------

describe('DetailGenerator — action buttons', () => {
  const gen = new DetailGenerator();
  const withActions = domain({
    entityName: 'Order',
    fields: [field({ name: 'id', type: 'java.util.UUID' })],
    actions: [
      { name: 'cancel', methodName: 'cancel' },
      { name: 'mark-urgent', methodName: 'markUrgent' },
      { name: 'setStatus', methodName: 'setStatus', params: [{ name: 'status', type: 'String' }] },
      { name: 'track', methodName: 'track', streaming: true },
    ] as never,
  });

  it('offers a button per parameterless, non-streaming action, calling the service method service-gen names', () => {
    const content = gen.generate(withActions, CTX)!.content;
    expect(content).toContain(`<button type="button" (click)="runAction('cancel')" [disabled]="actionPending()" data-testid="action-cancel"`);
    expect(content).toContain(`(click)="runAction('markUrgent')" [disabled]="actionPending()" data-testid="action-mark-urgent"`);
    expect(content).toContain('    cancel: (id: string) => this.service.cancel(id),');
    expect(content).toContain('    markUrgent: (id: string) => this.service.markUrgent(id),');
    expect(content).toContain('>Mark Urgent</button>');
    expect(content).not.toContain('setStatus');
    expect(content).not.toContain('this.service.track(');
  });

  it('reloads the entity after an action and shows a failure in its own alert', () => {
    const content = gen.generate(withActions, CTX)!.content;
    expect(content).toContain('runAction(name: keyof typeof this.actionCalls): void {');
    expect(content).toContain('        this.entityResource.reload();');
    expect(content).toContain("this.actionError.set(httpErrorMessage(err, { entity: 'order' }));");
    expect(content).toContain('data-testid="action-error"');
  });

  it('an entity with no such action emits no action state', () => {
    const content = gen.generate(domain({ entityName: 'Plain', fields: [field({ name: 'id', type: 'java.util.UUID' })] }), CTX)!.content;
    expect(content).not.toContain('actionPending');
    expect(content).not.toContain('runAction');
  });
});
// ---------- kit component classes ----------

describe('DetailGenerator styles its sections, actions and panels through the kit classes', () => {
  const gen = new DetailGenerator();
  const content = gen.generate(domain({
    entityName: 'Order',
    fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'name', type: 'String' })],
    actions: [{ name: 'cancel', methodName: 'cancel' }] as never,
  }), CTX)!.content;

  it('each section is an exeris-card whose heading is its header', () => {
    expect(content).toContain('<section aria-labelledby="details-title" class="exeris-card overflow-hidden">');
    expect(content).toContain('<h2 id="details-title" class="exeris-card-header');
    expect(content).toContain('<section aria-labelledby="system-title" class="exeris-card mt-8">');
    expect(content).toContain('<dl class="exeris-card-body grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">');
  });

  it('the header actions are kit buttons: actions secondary, Edit primary, Delete danger', () => {
    expect(content).toContain('data-testid="action-cancel" class="exeris-btn exeris-btn-secondary">Cancel</button>');
    expect(content).toContain(`<a [routerLink]="['edit']" class="exeris-btn exeris-btn-primary">Edit</a>`);
    expect(content).toContain('<button (click)="onDelete()" class="exeris-btn exeris-btn-danger">Delete</button>');
  });

  it('the load, delete and action errors are danger alerts', () => {
    expect(content).toContain('<div role="alert" class="exeris-alert exeris-alert-danger">');
    expect(content).toContain('(click)="reload()" class="exeris-btn exeris-btn-secondary exeris-btn-sm mt-4">Try again</button>');
    expect(content).toContain('data-testid="delete-error" class="exeris-alert exeris-alert-danger mb-6 text-sm"');
    expect(content).toContain('data-testid="action-error" class="exeris-alert exeris-alert-danger mb-6 text-sm"');
  });
});

describe('DetailGenerator — collection values', () => {
  it('formats an array comma-separated and an object as JSON', () => {
    const content = new DetailGenerator().generate(domain({
      entityName: 'Order',
      fields: [
        { name: 'id', type: 'java.util.UUID' },
        { name: 'labels', type: 'java.util.List<java.lang.String>' },
      ],
    }), CTX)!.content;
    expect(content).toContain(
      "default: return Array.isArray(value) ? value.join(', ') : typeof value === 'object' ? JSON.stringify(value) : String(value);",
    );
  });
});

describe('DetailGenerator — by-id resource', () => {
  it('reads the loaded entity only when the resource holds one, since value() throws after a failed load', () => {
    const content = new DetailGenerator().generate(domain({
      entityName: 'Order',
      fields: [{ name: 'id', type: 'java.util.UUID' }],
    }), CTX)!.content;
    expect(content).toContain(
      'readonly entity = computed(() => (this.entityResource.hasValue() ? this.entityResource.value() : null) ?? null);',
    );
  });
});
