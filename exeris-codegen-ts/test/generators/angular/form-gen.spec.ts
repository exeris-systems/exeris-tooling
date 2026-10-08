/**
 * Coverage for src/generators/angular/form-gen.ts — FormGenerator emits
 * an Angular 22 standalone form component on Signal Forms (ADR-093). Exercises:
 *   - isLifecycleField / isSystemField skip filter
 *   - inCreate=false / hidden=true / readOnly=true / computed exclusion
 *   - Computed field detection + separate rendering with dependsOn note +
 *     a computed signal per computed field reading the loaded entity
 *   - the input type by the shared type rules (number, date, date-time, zoned date-time as text)
 *   - enum select for an enum the app's enum module declares; any other type a text input
 *   - inUpdate = false: disabled in edit mode, its loaded value sent back on update
 *   - Field rendering: enum select, checkbox, text/number/date input
 *   - Schema validators: required/minLength/maxLength/pattern/min/max
 *   - Model seed: explicit defaultValue / Boolean → 'false' / number → null / fallback "''"
 *   - Order-based sort + label fallback (displayName ?? toTitleCase(name))
 *   - Mode-driven submit dispatch (create vs update)
 *   - Enum imports + enumValues + enumDisplayNames properties
 *   - MANY_TO_ONE foreign key: a select of the target's records labelled by displayField
 */

import { describe, expect, it } from 'vitest';
import { FormGenerator, generateForm } from '../../../src/generators/angular/form-gen.js';
import { DslMapper } from '../../../src/models/dsl-mapper.js';
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

/** The enums the processor emitted for the specs below, as `types/enums` declares them. */
function enumMeta(qualifiedName: string): GeneratorContext['enums'][number] {
  const name = qualifiedName.split('.').pop()!;
  return { name, qualifiedName, packageName: qualifiedName.slice(0, qualifiedName.lastIndexOf('.')), values: [] };
}

const ENUM_CTX: GeneratorContext = createGeneratorContext({}, [], [
  enumMeta('com.shop.OrderStatus'),
  enumMeta('com.shop.Status'),
  enumMeta('eu.exeris.foundation.domain.TenantPlan'),
]);

/** The form model's seed: the `signal<…FormModel>({ … })` the form is built on. */
function seedBlock(content: string): string {
  const start = content.indexOf('private readonly formModel = signal<');
  expect(start, 'the form model seed').toBeGreaterThan(-1);
  return content.slice(start, content.indexOf('});', start));
}

/** The form model interface: one property per control, typed as the control holds it. */
function modelInterface(content: string): string {
  const start = content.indexOf('interface ');
  expect(start, 'the form model interface').toBeGreaterThan(-1);
  return content.slice(start, content.indexOf('}', start));
}

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

function field(overrides: Partial<FieldMetadata> & { name: string; type: string }): FieldMetadata {
  return FieldMetadataSchema.parse(overrides);
}

// ---------- CodeGenerator contract ----------

describe('FormGenerator — CodeGenerator metadata', () => {
  const gen = new FormGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('FormGenerator');
    expect(gen.artifactType).toBe('FORM');
    expect(gen.priority).toBe(20);
    expect(gen.supportedBackends).toEqual([]);
  });
});

// ---------- generate — path + hidden-skip ----------

describe('FormGenerator.generate — emit path + hidden-skip', () => {
  const gen = new FormGenerator();

  it('emits components/<kebab>-form.component.ts for a visible domain', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine' }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-line-form.component.ts');
    expect(file!.artifactType).toBe('FORM');
    expect(file!.overwritable).toBe(true);
  });

});

// ---------- emitted structure ----------

describe('FormGenerator emitted content — top-level structure', () => {
  const gen = new FormGenerator();

  it('imports Angular core + Signal Forms + service + entity types, and nothing from Reactive Forms', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("import { Component, ChangeDetectionStrategy, input, output, signal, computed, effect, inject } from '@angular/core';");
    expect(content).toContain("import { rxResource } from '@angular/core/rxjs-interop';");
    expect(content).toContain("import { ActivatedRoute, Router } from '@angular/router';");
    expect(content).toContain("import { form, FormField, submit } from '@angular/forms/signals';");
    expect(content).toContain("import { Order, OrderCreate, OrderUpdate, OrderService } from '../services/order.service';");
    expect(content).toContain('imports: [FormField],');
    expect(content).not.toContain("from '@angular/forms';");
    for (const reactive of ['FormBuilder', 'ReactiveFormsModule', 'FormsModule', 'Validators', 'formControlName', 'formGroup']) {
      expect(content, reactive).not.toContain(reactive);
    }
  });

  it('@Component decorator includes app-<kebab>-form selector + standalone + OnPush', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;

    expect(content).toContain("selector: 'app-order-line-form'");
    expect(content).toContain('standalone: true');
    expect(content).toContain('ChangeDetectionStrategy.OnPush');
  });

  it('FormComponent class declares mode/entity inputs + saved/cancelled outputs + saving/error signals + form FieldTree', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('export class OrderFormComponent {');
    expect(content).toContain("readonly mode = input<'create' | 'edit'>('create');");
    expect(content).toContain('readonly entity = input<Order | null>(null);');
    expect(content).toContain('readonly saved = output<Order>();');
    expect(content).toContain('readonly cancelled = output<void>();');
    expect(content).toContain('readonly saving = signal(false);');
    expect(content).toContain("readonly error = signal<string | null>(null);");
    expect(content).toContain('interface OrderFormModel {');
    expect(content).toContain('private readonly formModel = signal<OrderFormModel>({');
    expect(content).toContain('readonly form = form(this.formModel);');
  });

  it('submits through submit(), which marks every field touched and runs the action only when valid', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String', required: true })],
    }), CTX)!.content;

    expect(content).toContain('<form (submit)="onSubmit($event)" novalidate class="space-y-6">');
    expect(content).toContain('onSubmit(event: Event): void {');
    expect(content).toContain('event.preventDefault();');
    expect(content).toContain('void submit(this.form, () => this.save());');
    expect(content).toContain('private save(): Promise<undefined> {');
    expect(content).toContain('const data = this.formModel();');
  });

  it('binds every control with [formField] and keeps every data-testid', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'orderNumber', type: 'String' }),
        field({ name: 'status', type: 'String', enumType: 'OrderStatus' }),
        field({ name: 'paid', type: 'java.lang.Boolean' }),
      ],
    }), ENUM_CTX)!.content;

    for (const name of ['orderNumber', 'status', 'paid']) {
      expect(content).toContain(`data-testid="field-${name}"`);
      expect(content).toContain(`[formField]="form.${name}"`);
      expect(content).toContain(`data-testid="error-${name}"`);
    }
    for (const testid of ['submit-error', 'cancel-button', 'submit-button']) {
      expect(content).toContain(`data-testid="${testid}"`);
    }
  });

  it('renders each validation error with the message it had, read by kind from the field state', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'code', type: 'String', displayName: 'Code' })],
    }), CTX)!.content;

    expect(content).toContain('@if (form.code().invalid() && form.code().touched()) {');
    expect(content).toContain("@if (form.code().getError('required')) { <span>Code is required.</span> }");
    expect(content).toContain("@if (form.code().getError('pattern') || form.code().getError('parse')) { <span>Invalid format.</span> }");
    expect(content).toContain("@if (form.code().getError('minLength')) { <span>Too short.</span> }");
    expect(content).toContain("@if (form.code().getError('maxLength')) { <span>Too long.</span> }");
    expect(content).toContain("@if (form.code().getError('min')) { <span>Too low.</span> }");
    expect(content).toContain("@if (form.code().getError('max')) { <span>Too high.</span> }");
  });

  it('an edit sends the loaded record with the form values over it, so a field the form does not offer keeps its value', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'id', type: 'java.util.UUID' }),
        field({ name: 'note', type: 'String' }),
        field({ name: 'internalCode', type: 'String', hidden: true }),
        field({ name: 'createdBy', type: 'String', inCreate: false }),
      ],
    }), CTX)!.content;
    expect(content).not.toContain('internalCode:');
    expect(content).toContain('this.service.update(String(current.id), this.updateBody(current, data))');
    expect(content).toContain('this.service.create(data as OrderCreate)');
  });

  it('onSubmit dispatches to service.update in edit mode and service.create otherwise', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('const request$ = this.editMode() && current ? this.service.update(String(current.id), this.updateBody(current, data)) : this.service.create(data as OrderCreate);');
    expect(content).not.toContain("this.mode() === 'create'");
  });

  it('dispatches the update on the key primaryKeyField names', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNo', type: 'java.util.UUID' }), field({ name: 'name', type: 'String' })],
      systemFields: { primaryKeyField: 'orderNo' },
    }), CTX)!.content;

    expect(content).toContain('String(current.orderNo)');
    expect(content).not.toMatch(/\bcurrent\.id\b/);
    expect(content).not.toContain('orderNo: string;');
  });
});

// ---------- T20d: a boolean control holds a boolean ----------

describe('FormGenerator boolean controls (T20d)', () => {
  const gen = new FormGenerator();

  // The three sites that shaped a boolean control all tested the literal
  // 'java.lang.Boolean', so a *primitive* `boolean` — whose DTO type is `boolean`
  // just the same — fell through every one of them: a text input, a '' seed, and
  // therefore a `string | null` value cast to a `boolean` DTO field (TS2352, the
  // two errors left on the generated app after the T20c numeric fix).
  it('a primitive boolean is a checkbox seeded with false, exactly like the wrapper', () => {
    const content = gen.generate(domain({
      entityName: 'Empire',
      fields: [
        field({ name: 'onVacation', type: 'boolean' }),
        field({ name: 'flagged', type: 'java.lang.Boolean' }),
      ],
    }), CTX)!.content;

    expect(content).toContain('type="checkbox" [formField]="form.onVacation"');
    expect(content).toContain('type="checkbox" [formField]="form.flagged"');
    expect(seedBlock(content)).toContain('onVacation: false,');
    expect(seedBlock(content)).toContain('flagged: false,');

    // The model type is what fixes the cast: a boolean control is held as `boolean`,
    // which matches the DTO's `boolean` and casts cleanly. A string-typed control
    // would not overlap it at all.
    expect(modelInterface(content)).toContain('onVacation: boolean;');
    expect(modelInterface(content)).toContain('flagged: boolean;');
    expect(seedBlock(content)).not.toContain("onVacation: '',");
  });

  it('a declared boolean default is emitted unquoted', () => {
    // `'true'` is a string and would reintroduce the same no-overlap cast through
    // the one path that bypasses the seed default.
    const content = gen.generate(domain({
      entityName: 'Empire',
      fields: [
        field({ name: 'enabled', type: 'boolean', defaultValue: 'true' }),
        field({ name: 'archived', type: 'boolean', defaultValue: 'false' }),
      ],
    }), CTX)!.content;

    expect(seedBlock(content)).toContain('enabled: true,');
    expect(seedBlock(content)).toContain('archived: false,');
    expect(content).not.toContain("enabled: 'true'");
  });

  it('a boolean default is read case-insensitively', () => {
    // defaultValue is free text from the annotation, so 'True' is the same intent as
    // 'true'. Anything else still falls back to false rather than being validated —
    // permissive, and deliberately so, matching the rest of this generator.
    const content = gen.generate(domain({
      entityName: 'Empire',
      fields: [field({ name: 'enabled', type: 'boolean', defaultValue: 'True' })],
    }), CTX)!.content;

    expect(seedBlock(content)).toContain('enabled: true,');
  });

  it('booleans are not held as numbers', () => {
    // Number(true) is 1. A number control holds `number | null` so that blank stays
    // distinct from 0; a checkbox has no blank state, so a boolean is held as a boolean.
    const content = gen.generate(domain({
      entityName: 'Empire',
      fields: [field({ name: 'onVacation', type: 'boolean' })],
    }), CTX)!.content;

    expect(modelInterface(content)).not.toContain('onVacation: number | null;');
    expect(content).not.toMatch(/[^A-Za-z]Number\(/);
    expect(content).toContain('const data = this.formModel();');
  });

  it('a required boolean is not forced to true', () => {
    // Signal Forms' required() counts false as empty, so a required boolean is not emitted as
    // required(): it only has to hold a boolean, which a checkbox always does.
    const content = gen.generate(domain({
      entityName: 'Empire',
      fields: [field({ name: 'onVacation', type: 'boolean', required: true })],
    }), CTX)!.content;

    expect(content).not.toContain('required(path.onVacation)');
    expect(content).toContain('<span class="text-red-500" aria-hidden="true">*</span>');
  });
});

// ---------- T20c: numeric coercion on submit ----------

describe('FormGenerator numeric controls hold numbers (T20c)', () => {
  const gen = new FormGenerator();

  // A number control holds `number | null`, the DTO's own type: blank is null, and the
  // payload needs no coercion before the DTO cast.
  it('numeric (number-typed DTO) create field is held as number | null and sent as is', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'orderNumber', type: 'String' }),
        field({ name: 'quantity', type: 'java.lang.Integer' }),
      ],
    }), CTX)!.content;

    expect(modelInterface(content)).toContain('quantity: number | null;');
    expect(seedBlock(content)).toContain('quantity: null,');
    // String field stays a string
    expect(modelInterface(content)).toContain('orderNumber: string;');
    // dispatches with the model as the payload
    expect(content).toContain('const data = this.formModel();');
    expect(content).toContain('this.service.create(data as OrderCreate)');
    expect(content).not.toMatch(/[^A-Za-z]Number\(/);
  });

  it('a numeric default is seeded as a number; one that is not a number seeds blank', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'quantity', type: 'java.lang.Integer', defaultValue: '5' }),
        field({ name: 'ratio', type: 'java.lang.Double', defaultValue: 'lots' }),
      ],
    }), CTX)!.content;

    expect(seedBlock(content)).toContain('quantity: 5,');
    expect(seedBlock(content)).toContain('ratio: null,');
  });

  it('every DslMapper number-typed variant is held as a number', () => {
    // Only java types whose DslMapper tsType is `number`/`number | null`. The
    // predicate keys off the mapped DTO type, so this list IS the contract.
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'i', type: 'java.lang.Integer' }),
        field({ name: 'l', type: 'java.lang.Long' }),
        field({ name: 'd', type: 'java.lang.Double' }),
        field({ name: 'f', type: 'java.lang.Float' }),
        field({ name: 'p', type: 'int' }),
      ],
    }), CTX)!.content;

    for (const name of ['i', 'l', 'd', 'f', 'p']) {
      expect(modelInterface(content)).toContain(`${name}: number | null;`);
    }
  });

  // Regression: BigDecimal/BigInteger map to a `string` DTO (DslMapper precision
  // preservation, text input). Coercing them to Number() would lose precision AND
  // reintroduce a TS2352 (number value → string DTO field) — the exact crash the
  // first cut of this PR caused on the Stellar `ng build`.
  it('BigDecimal / BigInteger (string-typed DTO) are NOT held as numbers', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'total', type: 'java.math.BigDecimal' }),
        field({ name: 'ledger', type: 'java.math.BigInteger' }),
      ],
    }), CTX)!.content;

    expect(modelInterface(content)).toContain('total: string;');
    expect(modelInterface(content)).toContain('ledger: string;');
    expect(seedBlock(content)).toContain("total: '',");
    expect(content).not.toMatch(/[^A-Za-z]Number\(/);
    expect(content).toContain('const data = this.formModel();');
  });

  it('no numeric create fields → no number-typed model property', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('const data = this.formModel();');
    expect(modelInterface(content)).not.toContain('number | null');
  });
});

// ---------- field filtering: lifecycle / system / inCreate / hidden / readOnly / computed ----------

describe('FormGenerator field filtering for createFields', () => {
  const gen = new FormGenerator();

  it('excludes id / createdAt / updatedAt / createdBy / updatedBy / version / deleted / deletedAt / tenantId (system fields)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'id', type: 'java.util.UUID' }),
        field({ name: 'createdAt', type: 'java.time.Instant' }),
        field({ name: 'updatedAt', type: 'java.time.Instant' }),
        field({ name: 'createdBy', type: 'String' }),
        field({ name: 'updatedBy', type: 'String' }),
        field({ name: 'version', type: 'java.lang.Long' }),
        field({ name: 'tenantId', type: 'java.util.UUID' }),
        field({ name: 'orderNumber', type: 'String' }), // VISIBLE
      ],
    }), CTX)!.content;

    // Each excluded field should NOT appear as a form control id.
    for (const excluded of ['id', 'createdAt', 'updatedAt', 'createdBy', 'updatedBy', 'version', 'tenantId']) {
      expect(content).not.toContain(`data-testid="field-${excluded}"`);
      expect(content).not.toContain(`for="${excluded}"`);
    }
    // orderNumber IS visible.
    expect(content).toContain('data-testid="field-orderNumber"');
  });

  it('excludes a UNIVERSE entity\'s sharedScopeField — server-owned, stamped by the repository (T29 slice B)', () => {
    const universe = gen.generate(domain({
      entityName: 'Species',
      dataScope: 'UNIVERSE',
      systemFields: { primaryKeyField: 'id', tenantIdField: 'tenantId', sharedScopeField: 'worldId' },
      fields: [
        field({ name: 'tenantId', type: 'java.util.UUID' }),
        field({ name: 'worldId', type: 'java.util.UUID' }),
        field({ name: 'name', type: 'String' }), // visible
      ],
    }), CTX)!.content;
    // The same field on an entity that declares no shared tier is an ordinary field.
    const plain = gen.generate(domain({
      entityName: 'Species',
      fields: [
        field({ name: 'worldId', type: 'java.util.UUID' }),
        field({ name: 'name', type: 'String' }),
      ],
    }), CTX)!.content;

    expect(universe).not.toContain('data-testid="field-worldId"');
    expect(universe).toContain('data-testid="field-name"');
    expect(plain).toContain('data-testid="field-worldId"');
  });

  it('excludes lifecycle fields (active, onboardingStatus, parentTenantId, deleted, ...)', () => {
    const content = gen.generate(domain({
      entityName: 'Tenant',
      fields: [
        field({ name: 'active', type: 'java.lang.Boolean' }),
        field({ name: 'onboardingStatus', type: 'String' }),
        field({ name: 'parentTenantId', type: 'java.util.UUID' }),
        field({ name: 'deleted', type: 'java.lang.Boolean' }),
        field({ name: 'name', type: 'String' }), // visible
      ],
    }), CTX)!.content;

    for (const lc of ['active', 'onboardingStatus', 'parentTenantId', 'deleted']) {
      expect(content).not.toContain(`data-testid="field-${lc}"`);
    }
    expect(content).toContain('data-testid="field-name"');
  });

  it('excludes fields with inCreate = false and inUpdate = false', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'derived', type: 'String', inCreate: false, inUpdate: false }),
        field({ name: 'name', type: 'String' }),
      ],
    }), CTX)!.content;

    expect(content).not.toContain('data-testid="field-derived"');
    expect(content).toContain('data-testid="field-name"');
  });

  it('lifecycle ∩ system overlap fields (createdAt / updatedAt / version / deleted) are excluded EXACTLY ONCE — no double-filter side effects', () => {
    // These four field names appear in BOTH isLifecycleField and
    // isSystemField sets in form-gen.ts:61-67. A future refactor that
    // changed the filter chain semantics (e.g., switched from .filter
    // to .filter().filter and forgot to chain correctly) could
    // accidentally drop them twice — or, worse, only filter them once
    // and leak the duplicate-filter source-of-truth. This test pins
    // the current behaviour: overlap fields are excluded ONCE,
    // sibling non-overlapping fields are preserved.
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'createdAt', type: 'java.time.Instant' }), // overlap
        field({ name: 'updatedAt', type: 'java.time.Instant' }), // overlap
        field({ name: 'version', type: 'java.lang.Long' }),       // overlap
        field({ name: 'deleted', type: 'java.lang.Boolean' }),    // overlap
        field({ name: 'name', type: 'String' }),                  // visible
      ],
    }), CTX)!.content;

    for (const overlap of ['createdAt', 'updatedAt', 'version', 'deleted']) {
      // Each overlap field appears exactly zero times in the form
      // (no data-testid, no label, no form model entry).
      expect(content).not.toContain(`data-testid="field-${overlap}"`);
      // The form model doesn't even reference the field name as a key —
      // guards against a silent partial filter that leaves some
      // occurrences behind.
      expect(content).not.toMatch(new RegExp(`^\\s*${overlap}: `, 'm'));
    }
    // Sibling visible field survives.
    expect(content).toContain('data-testid="field-name"');
    expect(modelInterface(content)).toMatch(/^\s*name: string;/m);
    expect(seedBlock(content)).toMatch(/^\s*name: '',/m);
  });

  it('excludes hidden=true and readOnly=true fields', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'secret', type: 'String', hidden: true }),
        field({ name: 'audit', type: 'String', readOnly: true }),
        field({ name: 'name', type: 'String' }),
      ],
    }), CTX)!.content;

    expect(content).not.toContain('data-testid="field-secret"');
    expect(content).not.toContain('data-testid="field-audit"');
    expect(content).toContain('data-testid="field-name"');
  });
});

// ---------- field rendering: select / checkbox / text/number/date inputs ----------

describe('FormGenerator field rendering', () => {
  const gen = new FormGenerator();

  it('explicit enumType field renders a <select> with enumValues + DisplayNames lookup', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'String', enumType: 'OrderStatus' })],
    }), ENUM_CTX)!.content;

    expect(content).toContain('<select id="status"');
    expect(content).toContain('@for (value of OrderStatusValues; track value)');
    expect(content).toContain('{{ OrderStatusDisplayNames[value] }}');
    // Enum import line emitted.
    expect(content).toContain("import { OrderStatus, OrderStatusDisplayNames } from '../types/enums';");
    // Per-enum class-level properties.
    expect(content).toContain('readonly OrderStatusValues = Object.values(OrderStatus);');
    expect(content).toContain('readonly OrderStatusDisplayNames = OrderStatusDisplayNames;');
  });

  it('FQN enumType (the real processor shape) is simplified — never emits a dotted identifier (T20)', () => {
    // The processor records enumType as a fully-qualified name; an FQN can't be a TS
    // identifier or import binding. Regression for the form-gen bug that emitted
    // `import { com.shop.OrderStatus }` + `readonly com.shop.OrderStatusValues`.
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'com.shop.OrderStatus', enumType: 'com.shop.OrderStatus' })],
    }), ENUM_CTX)!.content;

    expect(content).toContain("import { OrderStatus, OrderStatusDisplayNames } from '../types/enums';");
    expect(content).toContain('readonly OrderStatusValues = Object.values(OrderStatus);');
    expect(content).toContain('@for (value of OrderStatusValues; track value)');
    expect(content).not.toContain('com.shop.OrderStatus');
  });

  it('a field whose type names a processor-emitted enum renders a select over that enum', () => {
    const content = gen.generate(domain({
      entityName: 'Tenant',
      fields: [field({ name: 'plan', type: 'eu.exeris.foundation.domain.TenantPlan' })],
    }), ENUM_CTX)!.content;

    expect(content).toContain('<select id="plan"');
    expect(content).toContain('@for (value of TenantPlanValues; track value)');
    expect(content).toContain("import { TenantPlan, TenantPlanDisplayNames } from '../types/enums';");
  });

  it('a qualified type the enum module does not declare is a text input with no enum import', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'address', type: 'com.shop.Address' }),
        field({ name: 'ref', type: 'com.shop.CustomerEntity' }),
        field({ name: 'payment', type: 'com.shop.PaymentStatus' }),
      ],
    }), ENUM_CTX)!.content;

    for (const name of ['address', 'ref', 'payment']) {
      expect(content).toContain(`type="text" [formField]="form.${name}"`);
    }
    expect(content).not.toContain('<select');
    expect(content).not.toContain("from '../types/enums'");
  });

  it('an explicit enumType the enum module does not declare renders no select', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'priority', type: 'String', enumType: 'com.shop.Priority' })],
    }), ENUM_CTX)!.content;

    expect(content).toContain('type="text" [formField]="form.priority"');
    expect(content).not.toContain('PriorityValues');
    expect(content).not.toContain("from '../types/enums'");
  });

  it('java.lang.Boolean field renders a checkbox', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'flag', type: 'java.lang.Boolean' })],
    }), CTX)!.content;

    expect(content).toContain('type="checkbox" [formField]="form.flag"');
  });

  it.each([
    ['java.lang.Integer', 'number'],
    ['java.lang.Long', 'number'],
    ['java.lang.Double', 'number'],
    ['java.lang.Float', 'number'],
    ['long', 'number'],
    ['int', 'number'],
    ['Long', 'number'],
    ['double', 'number'],
    ['java.time.LocalDateTime', 'datetime-local'],
    ['LocalDateTime', 'datetime-local'],
    ['java.time.LocalDate', 'date'],
    ['LocalDate', 'date'],
    // A value naming a zone or an offset: neither native date input can hold it.
    ['java.time.Instant', 'text'],
    ['java.time.OffsetDateTime', 'text'],
    ['java.time.ZonedDateTime', 'text'],
    ['java.math.BigDecimal', 'text'],
    ['String', 'text'],
  ])('java type %s → input type=%s', (javaType, expectedInputType) => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: javaType })],
    }), CTX)!.content;

    expect(content).toContain(`type="${expectedInputType}" [formField]="form.attr"`);
  });

  it('number input gets inputmode="decimal" extra attribute', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'amount', type: 'java.lang.Long' })],
    }), CTX)!.content;

    expect(content).toContain('inputmode="decimal"');
  });

  it('a BigDecimal is a text input with a decimal keyboard, a BigInteger with a numeric one', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'price', type: 'java.math.BigDecimal' }),
        field({ name: 'units', type: 'java.math.BigInteger' }),
      ],
    }), CTX)!.content;

    expect(content).toMatch(/type="text" \[formField\]="form\.price"[^>]*inputmode="decimal"/);
    expect(content).toMatch(/type="text" \[formField\]="form\.units"[^>]*inputmode="numeric"/);
    expect(modelInterface(content)).toContain('price: string;');
    expect(modelInterface(content)).toContain('units: string;');
  });

  it('a primitive long is a number input holding number | null', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'count', type: 'long' })],
    }), CTX)!.content;

    expect(content).toContain('type="number" [formField]="form.count"');
    expect(modelInterface(content)).toContain('count: number | null;');
  });

  it('label falls back to toTitleCase(name) when displayName is absent; explicit displayName wins', () => {
    const auto = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;
    expect(auto).toContain('Order Number ');

    const explicit = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'orderNumber', type: 'String', displayName: 'Numer zamówienia' })],
    }), CTX)!.content;
    expect(explicit).toContain('Numer zamówienia ');
  });

  it('required field gets the visual red-asterisk marker', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'name', type: 'String', required: true })],
    }), CTX)!.content;

    expect(content).toContain('<span class="text-red-500" aria-hidden="true">*</span>');
  });
});

// ---------- field ordering ----------

describe('FormGenerator field ordering', () => {
  const gen = new FormGenerator();

  it('fields are rendered in declaration order', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'third', type: 'String' }),
        field({ name: 'first', type: 'String' }),
        field({ name: 'second', type: 'String' }),
      ],
    }), CTX)!.content;

    const firstIdx = content.indexOf('data-testid="field-first"');
    const secondIdx = content.indexOf('data-testid="field-second"');
    const thirdIdx = content.indexOf('data-testid="field-third"');

    expect(thirdIdx).toBeGreaterThan(-1);
    expect(thirdIdx).toBeLessThan(firstIdx);
    expect(firstIdx).toBeLessThan(secondIdx);
  });
});

// ---------- schema validators ----------

describe('FormGenerator schema validators', () => {
  const gen = new FormGenerator();

  function contentFor(f: FieldMetadata): string {
    return gen.generate(domain({ entityName: 'Thing', fields: [f] }), CTX)!.content;
  }

  function schemaSliceFor(f: FieldMetadata): string {
    const content = contentFor(f);
    const schemaStart = content.indexOf('readonly form = form(this.formModel');
    const schemaEnd = content.indexOf('\n  });', schemaStart);
    return content.slice(schemaStart, schemaEnd);
  }

  it('required field → required() in the schema', () => {
    expect(schemaSliceFor(field({ name: 'x', type: 'String', required: true })))
      .toContain('required(path.x);');
  });

  it.each([
    ['minLength', 3, 'minLength(path.x, 3);'],
    ['maxLength', 50, 'maxLength(path.x, 50);'],
  ])('string %s=%s → %s', (attr, value, validator) => {
    expect(schemaSliceFor(field({ name: 'x', type: 'String', [attr]: value } as Partial<FieldMetadata> & { name: string; type: string })))
      .toContain(validator);
  });

  it('numeric min / max → min() / max()', () => {
    expect(schemaSliceFor(field({ name: 'x', type: 'java.lang.Long', min: 0 })))
      .toContain('min(path.x, 0);');
    expect(schemaSliceFor(field({ name: 'x', type: 'java.lang.Long', max: 100 })))
      .toContain('max(path.x, 100);');
  });

  it('min / max on a decimal string bound its parsed value', () => {
    // min() and max() take number paths only; a BigDecimal control holds a string.
    const slice = schemaSliceFor(field({ name: 'x', type: 'java.math.BigDecimal', min: 0, max: 10 }));
    expect(slice).toContain('validate(path.x, ({ value }) => (parseFloat(value()) < 0 ? minError(0) : undefined));');
    expect(slice).toContain('validate(path.x, ({ value }) => (parseFloat(value()) > 10 ? maxError(10) : undefined));');
    expect(slice).not.toContain('min(path.x');
  });

  it('pattern → pattern(path, /<pattern>/)', () => {
    expect(schemaSliceFor(field({ name: 'x', type: 'String', pattern: '^[A-Z]+$' })))
      .toContain('pattern(path.x, /^[A-Z]+$/);');
  });

  it('length and pattern apply to text controls, never to a number', () => {
    const slice = schemaSliceFor(field({ name: 'x', type: 'java.lang.Integer', minLength: 2, pattern: '^[0-9]+$' }));
    expect(slice).not.toContain('minLength(');
    expect(slice).not.toContain('pattern(');
  });

  it('no validators set → a form without a schema', () => {
    expect(contentFor(field({ name: 'x', type: 'String' })))
      .toContain('readonly form = form(this.formModel);');
  });

  it('imports exactly the validators the schema calls, in a fixed order', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'max', type: 'java.lang.Long', max: 9 }),
        field({ name: 'code', type: 'String', required: true, pattern: '^[A-Z]+$' }),
      ],
    }), CTX)!.content;
    expect(content).toContain("import { form, FormField, submit, required, pattern, max } from '@angular/forms/signals';");
  });
});

// ---------- ADR-093 obligation 5: stable Signal Forms symbols only ----------

describe('FormGenerator imports only stable @angular/forms/signals symbols', () => {
  const gen = new FormGenerator();
  // Each is `@publicApi 22.0` in the Angular 22 type declarations. Experimental symbols
  // (provideExperimentalWebMcpForms, the experimentalWebMcpTool option) are absent on purpose.
  const STABLE = new Set([
    'form', 'FormField', 'submit',
    'required', 'minLength', 'maxLength', 'pattern', 'min', 'max', 'email',
    'validate', 'minError', 'maxError', 'disabled',
  ]);

  it('every imported symbol is on the stable allow-list, for every validator combination', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'code', type: 'String', required: true, minLength: 1, maxLength: 9, pattern: '^[A-Z]+$' }),
        field({ name: 'count', type: 'java.lang.Integer', min: 0, max: 9 }),
        field({ name: 'price', type: 'java.math.BigDecimal', min: 0, max: 9 }),
        field({ name: 'armed', type: 'boolean', required: true }),
        field({ name: 'status', type: 'String', enumType: 'Status' }),
        field({ name: 'sku', type: 'String', inUpdate: false }),
      ],
    }), ENUM_CTX)!.content;

    const line = content.match(/import \{ ([^}]+) \} from '@angular\/forms\/signals';/);
    expect(line).not.toBeNull();
    const symbols = line![1].split(',').map((name) => name.trim());
    expect(symbols.filter((name) => !STABLE.has(name))).toEqual([]);
    expect(content).not.toMatch(/experimental/i);
  });
});

// ---------- form model seed values ----------

describe('FormGenerator form model seed values', () => {
  const gen = new FormGenerator();

  function defaultFor(f: FieldMetadata): string {
    const content = gen.generate(domain({ entityName: 'Thing', fields: [f] }), CTX)!.content;
    const m = seedBlock(content).match(new RegExp(`${f.name}: (.+?),\n`));
    expect(m, `should match a ${f.name}: <seed>, in the form model seed`).not.toBeNull();
    return m![1];
  }

  it("explicit field.defaultValue → wrapped in single quotes", () => {
    expect(defaultFor(field({ name: 'x', type: 'String', defaultValue: 'foo' }))).toBe("'foo'");
  });

  it('java.lang.Boolean field with no defaultValue → "false" literal (not a string)', () => {
    expect(defaultFor(field({ name: 'x', type: 'java.lang.Boolean' }))).toBe('false');
  });

  it("string field with no defaultValue → empty string literal \"''\"", () => {
    expect(defaultFor(field({ name: 'x', type: 'String' }))).toBe("''");
  });

  it('number field with no defaultValue → null', () => {
    expect(defaultFor(field({ name: 'x', type: 'java.lang.Long' }))).toBe('null');
  });
});

// ---------- computed fields ----------

describe('FormGenerator computed fields', () => {
  const gen = new FormGenerator();

  it('computed fields are rendered as a readonly input below the create fields, with the (Auto) marker', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'first', type: 'String' }),
        field({ name: 'last', type: 'String' }),
        field({ name: 'full', type: 'String', computed: true, computedFrom: ['first', 'last'] }),
      ],
    }), CTX)!.content;

    // The (Auto) marker tags the computed field's label.
    expect(content).toContain('(Auto)</span></label>');
    // The readonly attribute on the rendered input.
    expect(content).toContain('readonly class="exeris-input mt-1 bg-[rgb(var(--exeris-bg-tertiary))] cursor-not-allowed opacity-75">');
    expect(content).toContain('<p class="exeris-help-text">Computed from: first, last</p>');
    // The "Computed from: ..." note.
    expect(content).toContain('Computed from: first, last');
  });

  it('each computed field reads the loaded entity into a computed signal', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'a', type: 'String' }),
        field({ name: 'b', type: 'String' }),
        field({ name: 'sum', type: 'String', computed: true, computedFrom: ['a', 'b'] }),
      ],
    }), CTX)!.content;

    expect(content).toContain('readonly computedSum = computed(() => this.current()?.sum ?? null);');
    expect(content).toContain(`[value]="computedSum() ?? ''" readonly`);
    // Kept out of the form model, and so out of the submitted DTO.
    expect(modelInterface(content)).not.toMatch(/^\s*sum: /m);
    expect(seedBlock(content)).not.toMatch(/^\s*sum: /m);
  });

  it('a computed field emits no compute method and no TODO, since the metadata carries no formula', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'a', type: 'String' }),
        field({ name: 'sum', type: 'String', computed: true, computedFrom: ['a'] }),
        field({ name: 'static', type: 'String', computed: true }),
      ],
    }), CTX)!.content;

    expect(content).not.toContain('TODO');
    expect(content).not.toContain('computeSum');
    expect(content).not.toContain('computeStatic');
    expect(content).not.toContain('Auto-sync');
    expect(content).toContain('readonly computedStatic = computed(() => this.current()?.static ?? null);');
  });

  it('a camelCase computed field gets valid member names', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'id', type: 'java.util.UUID' }),
        field({ name: 'firstName', type: 'String' }),
        field({ name: 'fullName', type: 'String', computed: true, computedFrom: ['firstName', 'id', 'unknown'] }),
      ],
    }), CTX)!.content;

    expect(content).toContain('readonly computedFullName = computed(() => this.current()?.fullName ?? null);');
    expect(content).toContain(`[value]="computedFullName() ?? ''" readonly`);
  });

});

// ---------- generateForm convenience ----------

describe('generateForm — top-level convenience function', () => {
  it('returns the per-domain file for a visible domain', () => {
    const file = generateForm(domain({ entityName: 'Order' }), CTX.config);
    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-form.component.ts');
  });

  it('falls back to KERNEL backend when config.backend is undefined', () => {
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    const file = generateForm(domain({ entityName: 'Order' }), partialConfig);
    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-form.component.ts');
  });
});

// ---------- @Field.dataType → input type mapping (Wave 1A) ----------

describe('FormGenerator @Field.dataType input-type mapping', () => {
  const gen = new FormGenerator();

  it("dataType 'url' maps the control to <input type=\"url\">", () => {
    const content = gen.generate(domain({
      entityName: 'Site',
      fields: [field({ name: 'homepage', type: 'String', dataType: 'url' })],
    }), CTX)!.content;

    expect(content).toContain('id="homepage" data-testid="field-homepage" type="url"');
  });

  it("dataType 'currency' on a String field maps the control to <input type=\"number\">", () => {
    const content = gen.generate(domain({
      entityName: 'Invoice',
      fields: [field({ name: 'amount', type: 'String', dataType: 'currency' })],
    }), CTX)!.content;

    expect(content).toContain('id="amount" data-testid="field-amount" type="number"');
  });

  it("dataType 'percent' on a String field maps the control to <input type=\"number\">", () => {
    const content = gen.generate(domain({
      entityName: 'Stat',
      fields: [field({ name: 'rate', type: 'String', dataType: 'percent' })],
    }), CTX)!.content;

    expect(content).toContain('id="rate" data-testid="field-rate" type="number"');
  });

  it('absent dataType keeps the type-derived input (String → text)', () => {
    const content = gen.generate(domain({
      entityName: 'Plain',
      fields: [field({ name: 'note', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('id="note" data-testid="field-note" type="text"');
  });
});

// ---------- routed edit: the form loads its entity from the :id route parameter ----------

describe('FormGenerator — routed by id', () => {
  const gen = new FormGenerator();
  const content = gen.generate(domain({
    entityName: 'Address',
    fields: [field({ name: 'street', type: 'String' })],
  }), CTX)!.content;

  it('declares an optional id input for withComponentInputBinding to bind from :id', () => {
    expect(content).toContain('readonly id = input<string | undefined>();');
  });

  it('loads the entity by id through rxResource with the params/stream keys', () => {
    expect(content).toContain('private readonly entityResource = rxResource({');
    expect(content).toContain('params: () => this.id(),');
    expect(content).toContain('stream: ({ params }) => this.service.findById(params),');
  });

  it('prefers the entity input over the loaded entity, and derives edit mode from the id', () => {
    expect(content).toContain('readonly current = computed<Address | null>(() => this.entity() ?? (this.entityResource.hasValue() ? this.entityResource.value() : null) ?? null);');
    expect(content).toContain("readonly editMode = computed(() => this.id() !== undefined || this.mode() === 'edit');");
    expect(content).toContain("{{ editMode() ? 'Update' : 'Create' }} Address");
  });

  it('patches the form from the effective entity', () => {
    expect(content).toContain('const entity = this.current();');
    expect(content).toContain('if (entity && this.editMode()) {');
    expect(content).toContain('this.formModel.set(this.toFormModel(entity));');
    // A loaded null takes the control's empty value rather than reaching a text input.
    expect(content).toContain('private toFormModel(entity: Address): AddressFormModel {');
    expect(content).toContain("street: entity.street ?? '',");
  });

  it('never submits an edit before the entity is loaded', () => {
    expect(content).toContain('if (this.editMode() && !current) {');
    expect(content).toContain('[disabled]="form().invalid() || saving() || (editMode() && !current())"');
  });

  it('shows the by-id load state', () => {
    expect(content).toContain('@if (isLoading()) {');
    expect(content).toContain('} @else if (loadError()) {');
    expect(content).toContain('readonly isLoading = computed(() => this.entityResource.isLoading());');
    expect(content).toContain('reload(): void { this.entityResource.reload(); }');
  });
});

describe('FormGenerator — navigation when the form is the routed page', () => {
  const gen = new FormGenerator();
  const content = gen.generate(domain({ entityName: 'Address' }), CTX)!.content;

  it('detects routing by its activated route naming this component', () => {
    expect(content).toContain('private readonly routed = inject(ActivatedRoute, { optional: true })?.component === AddressFormComponent;');
  });

  // The segment is whatever the route table uses, so the form cannot drift from app.routes.ts.
  const plural = DslMapper.routePlural('Address');

  it('navigates to the detail route after save, on the route table plural', () => {
    expect(content).toContain('this.saved.emit(result);');
    expect(content).toContain(`void this.router.navigate(['/${plural}', String(result.id)]);`);
  });

  it('cancel emits, then returns to the detail when editing and to the list otherwise', () => {
    expect(content).toContain('(click)="onCancel()"');
    expect(content).not.toContain('(click)="cancelled.emit()"');
    expect(content).toContain('this.cancelled.emit();');
    expect(content).toContain(`void this.router.navigate(id !== undefined ? ['/${plural}', id] : ['/${plural}']);`);
  });

  it('keeps navigation behind the routed check so an embedding host drives the next step', () => {
    expect(content.match(/if \(this\.routed\) \{/g)).toHaveLength(2);
  });

  it('uses a regular plural for an entity not ending in s', () => {
    const order = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;
    expect(order).toContain("void this.router.navigate(['/order-lines', String(result.id)]);");
  });
});

// ---------- versioned: the edit sends the loaded version; a 409 is a conflict ----------

describe('FormGenerator — collection fields', () => {
  const content = new FormGenerator().generate(domain({
    entityName: 'Order',
    fields: [
      field({ name: 'id', type: 'java.util.UUID' }),
      field({ name: 'note', type: 'String' }),
      field({ name: 'labels', type: 'java.util.List<java.lang.String>' }),
      field({ name: 'attributes', type: 'java.util.Map<java.lang.String,java.lang.String>' }),
    ],
  }), CTX)!.content;

  it('a list or map field has no control and no place in the form model', () => {
    expect(content).toContain('data-testid="field-note"');
    expect(content).not.toContain('data-testid="field-labels"');
    expect(content).not.toContain('data-testid="field-attributes"');
    expect(modelInterface(content)).not.toContain('labels');
    expect(modelInterface(content)).not.toContain('attributes');
  });

  it('an edit sends the loaded record, so the stored collection is kept', () => {
    expect(content).toContain('this.service.update(String(current.id), this.updateBody(current, data))');
  });
});

describe('FormGenerator — @Field(inUpdate = false)', () => {
  const gen = new FormGenerator();
  const fields = [
    field({ name: 'id', type: 'java.util.UUID' }),
    field({ name: 'sku', type: 'String', required: true, inUpdate: false }),
    field({ name: 'name', type: 'String' }),
    field({ name: 'price', type: 'java.lang.Long' }),
  ];
  const content = gen.generate(domain({ entityName: 'Product', fields }), CTX)!.content;

  it('keeps the control, so the create form still offers the field', () => {
    expect(content).toContain('data-testid="field-sku"');
    expect(content).toContain('[formField]="form.sku"');
    expect(modelInterface(content)).toContain('sku: string;');
    expect(content).toContain('this.service.create(data as ProductCreate)');
  });

  it('disables the control while the form edits, and only then', () => {
    expect(content).toContain('disabled(path.sku, { when: () => this.editMode() });');
    expect(content).not.toContain('disabled(path.name');
    expect(content).not.toContain('disabled(path.price');
    expect(content).toMatch(/import \{ [^}]*\bdisabled\b[^}]* \} from '@angular\/forms\/signals';/);
  });

  it('the required rule stays, and the disabled rule follows it', () => {
    expect(content.indexOf('required(path.sku);')).toBeGreaterThan(-1);
    expect(content.indexOf('required(path.sku);')).toBeLessThan(content.indexOf('disabled(path.sku'));
  });

  it('sends the stored value back on update, read from the loaded record rather than the model', () => {
    // The model seeds a stored null as the control's empty value; the loaded record holds the null.
    expect(content).toContain('      sku: entity.sku ?? \'\',');
    expect(content).toContain(
      'this.editMode() && current ? '
      + 'this.service.update(String(current.id), this.updateBody(current, data)) : '
      + 'this.service.create(data as ProductCreate);',
    );
    expect(content).toContain('return { ...stored, ...data, sku: current.sku } as ProductUpdate;');
  });

  it('a form without such a field disables nothing and imports no disabled', () => {
    const plain = gen.generate(domain({
      entityName: 'Product',
      fields: [field({ name: 'name', type: 'String' })],
    }), CTX)!.content;
    expect(plain).not.toContain('disabled(');
    expect(plain).not.toMatch(/import \{ [^}]*\bdisabled\b/);
  });
});

describe('FormGenerator — @Field(inCreate = false, inUpdate = true)', () => {
  const gen = new FormGenerator();
  const fields = [
    field({ name: 'id', type: 'java.util.UUID' }),
    field({ name: 'name', type: 'String' }),
    field({ name: 'trackingCode', type: 'String', required: true, inCreate: false }),
    field({ name: 'price', type: 'java.lang.Long' }),
  ];
  const content = gen.generate(domain({ entityName: 'Shipment', fields }), CTX)!.content;

  it('renders the control in edit mode only', () => {
    const start = content.indexOf('      @if (editMode()) {\n        <div class="form-group">\n          <label for="trackingCode"');
    expect(start, 'the edit-only block').toBeGreaterThan(-1);
    const block = content.slice(start, content.indexOf('\n      }\n', start));
    expect(block).toContain('<input id="trackingCode" data-testid="field-trackingCode" type="text" [formField]="form.trackingCode"');
    expect(block).toContain('data-testid="error-trackingCode"');
    // The neighbouring controls render in both modes.
    expect(content).toContain('    <form (submit)="onSubmit($event)" novalidate class="space-y-6">\n      <div class="form-group">\n        <label for="name"');
    expect(content).toContain('      }\n      <div class="form-group">\n        <label for="price"');
  });

  it('holds the field in the form model, seeded from the loaded record', () => {
    expect(modelInterface(content)).toContain('trackingCode: string;');
    expect(content).toContain("      trackingCode: entity.trackingCode ?? '',");
  });

  it('disables the field while the form creates, so a required one never blocks a create', () => {
    expect(content).toContain('required(path.trackingCode);');
    expect(content).toContain('disabled(path.trackingCode, { when: () => !this.editMode() });');
    expect(content).not.toContain('disabled(path.trackingCode, { when: () => this.editMode() });');
    expect(content).not.toContain('disabled(path.name');
  });

  it('leaves the field out of the create payload and sends it with an edit', () => {
    expect(content).toContain(
      'this.editMode() && current ? '
      + 'this.service.update(String(current.id), this.updateBody(current, data)) : '
      + 'this.service.create({ name: data.name, price: data.price } as ShipmentCreate);',
    );
  });

  it('a form whose every control is edit-only creates with an empty payload', () => {
    const editOnly = gen.generate(domain({
      entityName: 'Shipment',
      fields: [field({ name: 'trackingCode', type: 'String', inCreate: false })],
    }), CTX)!.content;
    expect(editOnly).toContain('this.service.create({} as ShipmentCreate);');
  });

  it('a form without such a field keeps every control in both modes and creates with the model', () => {
    const plain = gen.generate(domain({
      entityName: 'Shipment',
      fields: [field({ name: 'name', type: 'String' })],
    }), CTX)!.content;
    expect(plain).not.toContain('@if (editMode())');
    expect(plain).toContain('this.service.create(data as ShipmentCreate);');
  });
});

describe('FormGenerator — @Field(readOnly = true)', () => {
  const gen = new FormGenerator();
  const content = gen.generate(domain({
    entityName: 'Order',
    fields: [
      field({ name: 'id', type: 'java.util.UUID' }),
      field({ name: 'name', type: 'String' }),
      field({ name: 'status', type: 'String', readOnly: true }),
    ],
  }), CTX)!.content;

  it('drops the read-only field from the PUT body, which the server keeps as stored', () => {
    expect(content).toContain('const { id: _id, status: _status, ...stored } = current;');
    expect(content).toContain('return { ...stored, ...data } as OrderUpdate;');
  });

  it('offers no control for it', () => {
    expect(content).not.toContain('[formField]="form.status"');
  });
});

describe('FormGenerator — versioned entity', () => {
  const gen = new FormGenerator();
  const fields = [
    field({ name: 'id', type: 'java.util.UUID' }),
    field({ name: 'name', type: 'String' }),
    field({ name: 'version', type: 'java.lang.Long' }),
  ];
  const versioned = gen.generate(domain({ entityName: 'Order', versioned: true, fields }), CTX)!.content;
  const unversioned = gen.generate(domain({ entityName: 'Order', fields }), CTX)!.content;

  it('holds the loaded entity version aside, never as a control', () => {
    expect(versioned).toContain('private readonly loadedVersion = signal<number | null>(null);');
    expect(versioned).toContain('this.loadedVersion.set(entity.version ?? null);');
    expect(versioned).not.toContain('[formField]="form.version"');
    expect(versioned).not.toMatch(/^\s*version: /m);
  });

  it('sends the loaded version on update, and leaves the create payload alone', () => {
    expect(versioned).toContain(
      'this.editMode() && current ? '
      + 'this.service.update(String(current.id), this.updateBody(current, data)) : '
      + 'this.service.create(data as OrderCreate);',
    );
    expect(versioned).toContain('const { id: _id, ...stored } = current;');
    expect(versioned).toContain('return { ...stored, ...data, version: this.loadedVersion() } as OrderUpdate;');
  });

  it('turns a 409 on update into a conflict with a reload of the current row', () => {
    expect(versioned).toContain('readonly conflict = signal(false);');
    expect(versioned).toContain('if (this.editMode() && err?.status === 409) {');
    expect(versioned).toContain('This record was changed by someone else. Reload to see the latest version.');
    expect(versioned).toContain('(click)="reload()"');
    // Loaded by id: the resource reloads and the patch effect takes the fresh version.
    expect(versioned).toContain('this.entityResource.reload();\n      return;');
    // Host-supplied entity: fetched directly.
    expect(versioned).toContain('this.service.findById(String(current.id)).subscribe({');
    expect(versioned).toContain('this.form().reset(this.toFormModel(fresh));');
    expect(versioned).toContain('this.loadedVersion.set(fresh.version ?? null);');
  });

  it('a 409 is a form-level conflict: the save resolves with no field error', () => {
    const start = versioned.indexOf('error: (err) => {');
    const errorBranch = versioned.slice(start, versioned.indexOf('},', start));
    // The submit action settles before the conflict is shown, and returns no field error.
    expect(errorBranch.indexOf('resolve(undefined);')).toBeGreaterThan(-1);
    expect(errorBranch.indexOf('resolve(undefined);')).toBeLessThan(errorBranch.indexOf('err?.status === 409'));
    expect(versioned).not.toContain("kind: 'server'");
    expect(versioned).toContain('data-testid="conflict-message"');
    expect(versioned).toContain('data-testid="reload-button"');
  });

  it('keys the version on systemFields.versionField', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      versioned: true,
      fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'rev', type: 'long' })],
      systemFields: { versionField: 'rev' } as DomainMetadata['systemFields'],
    }), CTX)!.content;
    expect(content).not.toContain('[formField]="form.rev"');
    expect(content).toContain('private readonly loadedVersion = signal<number | null>(null);');
    expect(content).toContain('return { ...stored, ...data, rev: this.loadedVersion() } as OrderUpdate;');
  });

  it('reads an undeclared version field through a narrowing cast', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      versioned: true,
      fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'name', type: 'String' })],
    }), CTX)!.content;
    expect(content).toContain('this.loadedVersion.set((entity as unknown as { version?: number }).version ?? null);');
  });

  it('an unversioned entity emits no version payload or conflict state', () => {
    expect(unversioned).toContain('this.service.update(String(current.id), this.updateBody(current, data))');
    expect(unversioned).not.toContain('loadedVersion');
    expect(unversioned).not.toContain('conflict');
    expect(unversioned).not.toContain('409');
    // reload() still exists: it retries a failed by-id load, and only re-fetches the resource.
    expect(unversioned).toContain('reload(): void { this.entityResource.reload(); }');
  });
});

// ---------- system fields: one classification, from systemFields and the flags ----------

describe('FormGenerator — system fields follow systemFields and the flags', () => {
  const gen = new FormGenerator();

  it('renders no control for the audit stamps an audited entity renames', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      audited: true,
      systemFields: { createdAtField: 'openedAt', updatedAtField: 'touchedAt' },
      fields: [
        field({ name: 'openedAt', type: 'java.time.Instant' }),
        field({ name: 'touchedAt', type: 'java.time.Instant' }),
        field({ name: 'orderNumber', type: 'String' }),
      ],
    }), CTX)!.content;
    expect(content).not.toContain('data-testid="field-openedAt"');
    expect(content).not.toContain('data-testid="field-touchedAt"');
    expect(content).toContain('data-testid="field-orderNumber"');
  });

  it('renders no control for the soft-delete fields a systemFields block names', () => {
    const content = gen.generate(domain({
      entityName: 'Invoice',
      softDelete: true,
      systemFields: { softDeleteField: 'archived', softDeleteTimestampField: 'archivedAt', softDeletedByField: 'archivedBy' },
      fields: [
        field({ name: 'archived', type: 'boolean' }),
        field({ name: 'archivedAt', type: 'java.time.Instant' }),
        field({ name: 'archivedBy', type: 'String' }),
        field({ name: 'amount', type: 'java.math.BigDecimal' }),
      ],
    }), CTX)!.content;
    for (const name of ['archived', 'archivedAt', 'archivedBy']) {
      expect(content).not.toContain(`data-testid="field-${name}"`);
    }
    expect(content).toContain('data-testid="field-amount"');
  });
});
// ---------- kit component classes ----------

describe('FormGenerator styles its controls, errors and buttons through the kit classes', () => {
  const gen = new FormGenerator();
  const content = gen.generate(domain({
    entityName: 'Order',
    versioned: true,
    fields: [
      field({ name: 'id', type: 'java.util.UUID' }),
      field({ name: 'name', type: 'String', required: true }),
      field({ name: 'paid', type: 'java.lang.Boolean' }),
      field({ name: 'status', type: 'com.shop.OrderStatus' }),
      field({ name: 'version', type: 'java.lang.Long' }),
    ],
  }), ENUM_CTX)!.content;

  it('a text input, a select and a checkbox carry the matching kit field class, labelled by exeris-label', () => {
    expect(content).toContain('<label for="name" class="exeris-label">');
    expect(content).toContain(
      'data-testid="field-name" type="text" [formField]="form.name" class="exeris-input mt-1" [class.exeris-input-error]="form.name().invalid() && form.name().touched()">',
    );
    expect(content).toContain(
      'data-testid="field-status" [formField]="form.status" class="exeris-select mt-1" [class.exeris-input-error]="form.status().invalid() && form.status().touched()">',
    );
    expect(content).toContain('data-testid="field-paid" type="checkbox" [formField]="form.paid" class="exeris-checkbox">');
    expect(content).toContain('<label for="paid" class="exeris-label">');
  });

  it('a field error is exeris-error-text', () => {
    expect(content).toContain('<p class="exeris-error-text" data-testid="error-name">');
  });

  it('the submit error is a danger alert and the conflict a warning one, with a small secondary Reload', () => {
    expect(content).toContain('data-testid="submit-error" class="exeris-alert exeris-alert-danger text-sm"');
    expect(content).toContain('data-testid="conflict-message" class="exeris-alert exeris-alert-warning text-sm"');
    expect(content).toContain('data-testid="reload-button" class="exeris-btn exeris-btn-secondary exeris-btn-sm mt-2">Reload</button>');
    expect(content).toContain('<div role="alert" class="exeris-alert exeris-alert-danger mb-6">');
  });

  it('Cancel is a secondary button and submit the primary one', () => {
    expect(content).toContain('data-testid="cancel-button" class="exeris-btn exeris-btn-secondary">Cancel</button>');
    expect(content).toContain('data-testid="submit-button" class="exeris-btn exeris-btn-primary">');
  });
});

// ---------- foreign-key picker ----------

describe('FormGenerator — a MANY_TO_ONE foreign key is picked from its target records', () => {
  const gen = new FormGenerator();
  const product = domain({
    entityName: 'Product',
    fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'name', type: 'String' })],
  });
  function order(required: boolean, displayField = 'name'): DomainMetadata {
    return domain({
      entityName: 'Order',
      fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'productId', type: 'java.util.UUID', required })],
      relationships: [
        { name: 'productId', targetEntity: 'com.shop.Product', type: 'MANY_TO_ONE', displayField } as DomainMetadata['relationships'][number],
      ],
    });
  }
  function emit(d: DomainMetadata, all: DomainMetadata[], config: Record<string, unknown> = {}): string {
    return gen.generate(d, createGeneratorContext(config, all))!.content;
  }

  it('renders a kit select keeping the field testid and binding', () => {
    const content = emit(order(false), [order(false), product]);
    expect(content).toContain(
      '<select id="productId" data-testid="field-productId" [formField]="form.productId" class="exeris-select mt-1" [class.exeris-input-error]="form.productId().invalid() && form.productId().touched()" [attr.aria-describedby]="productIdOptionsTruncated() ? \'options-truncated-productId\' : null">',
    );
    expect(content).not.toContain('data-testid="field-productId" type="text"');
    expect(content).toContain('@for (option of productIdOptions(); track option.value) {');
    expect(content).toContain('<option [value]="option.value">{{ option.label }}</option>');
  });

  it('loads the options from the first page of the largest size the target\'s list route serves', () => {
    const content = emit(order(false), [order(false), product]);
    expect(content).toContain("import { ProductService } from '../services/product.service';");
    expect(content).toContain('private readonly productService = inject(ProductService);');
    expect(content).toContain('private readonly productIdOptionsResource = rxResource({ stream: () => this.productService.findAll({ size: 100 }) });');
    expect(content).toContain(
      'pickerOptions(this.productIdOptionsResource.hasValue() ? this.productIdOptionsResource.value() : undefined, (row) => row.name),',
    );
    expect(content).toContain('  page: { content: T[] } | undefined,');
    expect(content).toContain('  const rows = page?.content ?? [];');
    expect(content).not.toContain('Array.isArray');
  });

  it('labels an option by displayField, and by id when the value is empty or the target has no such field', () => {
    const content = emit(order(false), [order(false), product]);
    expect(content).toContain("return { value, label: text == null || String(text) === '' ? value : String(text) };");
    const unknownField = emit(order(false, 'title'), [order(false, 'title'), product]);
    expect(unknownField).toContain('pickerOptions(this.productIdOptionsResource.hasValue() ? this.productIdOptionsResource.value() : undefined),');
    expect(unknownField).toContain('label: (row: T) => unknown = (row) => row.id,');
  });

  it('offers an empty option for an optional key, and only a disabled placeholder for a required one', () => {
    expect(emit(order(false), [order(false), product])).toContain('<option value="">—</option>');
    const required = emit(order(true), [order(true), product]);
    expect(required).toContain('<option value="" disabled>Select...</option>');
    expect(required).not.toContain('<option value="">—</option>');
    expect(required).toContain('required(path.productId);');
  });

  it('keeps the held value an option while it is not among the loaded records', () => {
    const content = emit(order(false), [order(false), product]);
    expect(content).toContain('@if (productIdUnlisted()) {');
    expect(content).toContain('<option [value]="form.productId().value()">{{ form.productId().value() }}</option>');
    expect(content).toContain(
      "return value !== '' && !this.productIdOptions().some((option) => option.value === value);",
    );
    // The held value's option precedes the loaded ones, inside the select.
    const select = content.slice(content.indexOf('<select id="productId"'), content.indexOf('</select>', content.indexOf('<select id="productId"')));
    expect(select.indexOf('productIdUnlisted()')).toBeLessThan(select.indexOf('productIdOptions()'));
  });

  it('keeps a text input when the target service is not generated or the target is not loaded', () => {
    for (const content of [
      emit(order(false), [order(false), product], { generateServices: false }),
      emit(order(false), [order(false)]),
    ]) {
      expect(content).toContain('data-testid="field-productId" type="text" [formField]="form.productId" class="exeris-input mt-1"');
      expect(content).not.toContain('pickerOptions');
      expect(content).not.toContain('ProductService');
    }
  });

  it('serves a relationship to the entity itself through its own service', () => {
    const tag = domain({
      entityName: 'Tag',
      fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'parentId', type: 'java.util.UUID' })],
      relationships: [{ name: 'parentId', targetEntity: 'Tag', type: 'MANY_TO_ONE' } as DomainMetadata['relationships'][number]],
    });
    const content = emit(tag, [tag]);
    expect(content).toContain('rxResource({ stream: () => this.service.findAll({ size: 100 }) })');
    expect(content.match(/TagService/g)).toHaveLength(2);
  });

  it('leaves the record being edited out of a relationship to the entity itself', () => {
    const tag = domain({
      entityName: 'Tag',
      fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'parentId', type: 'java.util.UUID' })],
      relationships: [{ name: 'parentId', targetEntity: 'Tag', type: 'MANY_TO_ONE' } as DomainMetadata['relationships'][number]],
    });
    expect(emit(tag, [tag])).toContain(
      '  readonly parentIdOptions = computed(() =>\n'
      + '    pickerOptions(this.parentIdOptionsResource.hasValue() ? this.parentIdOptionsResource.value() : undefined)\n'
      + '      .filter((option) => !this.editMode() || option.value !== String(this.id() ?? this.current()?.id)),\n'
      + '  );',
    );
  });

  it('reads the key a renamed-key target names, in the helper and in the self-exclusion', () => {
    const invoice = domain({
      entityName: 'Invoice',
      fields: [field({ name: 'invoiceNo', type: 'java.util.UUID' }), field({ name: 'correctsId', type: 'java.util.UUID' })],
      relationships: [{ name: 'correctsId', targetEntity: 'Invoice', type: 'MANY_TO_ONE' } as DomainMetadata['relationships'][number]],
      systemFields: { primaryKeyField: 'invoiceNo' },
    });
    const content = emit(invoice, [invoice]);
    expect(content).toContain('function pickerOptions<T extends { invoiceNo?: unknown }>(');
    expect(content).toContain('  label: (row: T) => unknown = (row) => row.invoiceNo,');
    expect(content).toContain(
      '    pickerOptions(this.correctsIdOptionsResource.hasValue() ? this.correctsIdOptionsResource.value() : undefined)\n'
      + '      .filter((option) => !this.editMode() || option.value !== String(this.id() ?? this.current()?.invoiceNo)),',
    );
    expect(content).not.toMatch(/row\.id\b/);
  });

  it('passes each target\'s key to the one helper when the targets\' keys differ', () => {
    const invoice = domain({
      entityName: 'Invoice',
      fields: [
        field({ name: 'invoiceNo', type: 'java.util.UUID' }),
        field({ name: 'productId', type: 'java.util.UUID' }),
        field({ name: 'correctsId', type: 'java.util.UUID' }),
      ],
      relationships: [
        { name: 'productId', targetEntity: 'Product', type: 'MANY_TO_ONE', displayField: 'name' } as DomainMetadata['relationships'][number],
        { name: 'correctsId', targetEntity: 'Invoice', type: 'MANY_TO_ONE' } as DomainMetadata['relationships'][number],
      ],
      systemFields: { primaryKeyField: 'invoiceNo' },
    });
    const content = emit(invoice, [invoice, product]);
    expect(content.match(/^function pickerOptions/gm)).toHaveLength(1);
    expect(content).toContain(
      'function pickerOptions<K extends string, T extends { [P in K]?: unknown }>(\n'
      + '  page: { content: T[] } | undefined,\n'
      + '  key: K,\n'
      + '  label: (row: T) => unknown = (row) => row[key],\n'
      + '): { value: string; label: string }[] {\n'
      + '  const rows = page?.content ?? [];\n'
      + "  return rows.filter((row) => row[key] != null && row[key] !== '').map((row) => {\n"
      + '    const value = String(row[key]);',
    );
    expect(content).toContain(
      "pickerOptions(this.productIdOptionsResource.hasValue() ? this.productIdOptionsResource.value() : undefined, 'id', (row) => row.name),",
    );
    expect(content).toContain(
      "pickerOptions(this.correctsIdOptionsResource.hasValue() ? this.correctsIdOptionsResource.value() : undefined, 'invoiceNo')",
    );
  });

  it('shows a failed options request through httpErrorMessage, beside the select', () => {
    const content = emit(order(false), [order(false), product]);
    expect(content).toContain(
      '  readonly productIdOptionsError = computed(() => {\n'
      + '    const err = this.productIdOptionsResource.error();\n'
      + "    return err ? httpErrorMessage(err, { entity: 'products', action: 'load' }) : null;\n"
      + '  });',
    );
    expect(content).toContain(
      '        </select>\n'
      + '        @if (productIdOptionsError()) {\n'
      + '          <p role="alert" class="exeris-error-text" data-testid="options-error-productId">{{ productIdOptionsError() }}</p>\n'
      + '        }',
    );
  });

  it('names the target in the options error by its pluralName', () => {
    const people = domain({ ...product, entityName: 'Person', pluralName: 'People' });
    const visit = domain({
      entityName: 'Visit',
      fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'personId', type: 'java.util.UUID' })],
      relationships: [{ name: 'personId', targetEntity: 'Person', type: 'MANY_TO_ONE' } as DomainMetadata['relationships'][number]],
    });
    expect(emit(visit, [visit, people])).toContain("httpErrorMessage(err, { entity: 'people', action: 'load' })");
  });

  it('tells the user when the target has rows beyond the first page, and links the hint to the select', () => {
    const content = emit(order(false), [order(false), product]);
    expect(content).toContain(
      '  readonly productIdOptionsTruncated = computed(() => this.productIdOptionsResource.hasValue() && this.productIdOptionsResource.value().last === false);',
    );
    expect(content).toContain(
      '        @if (productIdOptionsTruncated()) {\n'
      + '          <p id="options-truncated-productId" class="exeris-help-text" data-testid="options-truncated-productId">Showing the first 100 options only.</p>\n'
      + '        }',
    );
  });

  it('a form without a picker has no options hint', () => {
    const content = emit(order(false), [order(false)]);
    expect(content).not.toContain('OptionsTruncated');
    expect(content).not.toContain('options-truncated');
  });

  it('a form without a picker has no options error', () => {
    expect(emit(order(false), [order(false)])).not.toContain('OptionsError');
  });

  it('offers every record of another target', () => {
    expect(emit(order(false), [order(false), product])).not.toContain('.filter((option) =>');
  });

  it('imports a target named like a framework symbol by its service class only', () => {
    const component = domain({
      entityName: 'Component',
      fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'name', type: 'String' })],
    });
    const address = domain({
      entityName: 'Address',
      fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'componentId', type: 'java.util.UUID' })],
      relationships: [
        { name: 'componentId', targetEntity: 'Component', type: 'MANY_TO_ONE', displayField: 'name' } as DomainMetadata['relationships'][number],
      ],
    });
    const content = emit(address, [address, component]);
    expect(content).toContain("import { ComponentService } from '../services/component.service';");
    expect(content).toContain('private readonly componentService = inject(ComponentService);');
  });

  it('generateForm resolves pickers against the domain set it is given', () => {
    expect(generateForm(order(false), CTX.config, [], [order(false), product])!.content).toContain('ProductService');
    expect(generateForm(order(false), CTX.config)!.content).not.toContain('ProductService');
  });
});
