/**
 * Coverage for src/generators/angular/form-gen.ts — FormGenerator emits
 * an Angular 22 standalone form component with FormBuilder + Validators +
 * Signals. Exercises:
 *   - isLifecycleField / isSystemField skip filter
 *   - inCreate=false / hidden=true / readOnly=true / computed exclusion
 *   - Computed field detection + separate rendering with dependsOn note +
 *     compute method stub + effect() per dependency set
 *   - mapInputType java.* → HTML input type mapping
 *   - isEnumField (explicit enumType OR FQCN with dots that isn't java.*
 *     / Entity / DTO)
 *   - Field rendering: enum select, checkbox, text/number/date input
 *   - Validator builder: required/minLength/maxLength/pattern/min/max
 *   - Default value: explicit defaultValue / Boolean → 'false' / fallback "''"
 *   - Order-based sort + label fallback (displayName ?? toTitleCase(name))
 *   - Mode-driven submit dispatch (create vs update)
 *   - Enum imports + enumValues + enumDisplayNames properties
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

  it('imports Angular core + FormBuilder + Validators + service + entity types', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("import { Component, ChangeDetectionStrategy, input, output, signal, computed, effect, inject } from '@angular/core';");
    expect(content).toContain("import { rxResource } from '@angular/core/rxjs-interop';");
    expect(content).toContain("import { ActivatedRoute, Router } from '@angular/router';");
    expect(content).toContain("import { FormsModule, ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';");
    expect(content).toContain("import { Order, OrderCreate, OrderUpdate, OrderService } from '../services/order.service';");
  });

  it('@Component decorator includes app-<kebab>-form selector + standalone + OnPush', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;

    expect(content).toContain("selector: 'app-order-line-form'");
    expect(content).toContain('standalone: true');
    expect(content).toContain('ChangeDetectionStrategy.OnPush');
  });

  it('FormComponent class declares mode/entity inputs + saved/cancelled outputs + saving/error signals + form FormGroup', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('export class OrderFormComponent {');
    expect(content).toContain("readonly mode = input<'create' | 'edit'>('create');");
    expect(content).toContain('readonly entity = input<Order | null>(null);');
    expect(content).toContain('readonly saved = output<Order>();');
    expect(content).toContain('readonly cancelled = output<void>();');
    expect(content).toContain('readonly saving = signal(false);');
    expect(content).toContain("readonly error = signal<string | null>(null);");
    expect(content).toContain('readonly form = this.fb.group({');
  });

  it('onSubmit dispatches to service.update in edit mode and service.create otherwise', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('const request$ = this.editMode() && current ? this.service.update(String(current.id), data as OrderUpdate) : this.service.create(data as OrderCreate);');
    expect(content).not.toContain("this.mode() === 'create'");
  });

  it('dispatches the update on id even when primaryKeyField names something else', () => {
    // A declared override must NOT move the emitted identity: nothing in the pipeline honours
    // `primaryKeyField` — Flyway emits `id UUID PRIMARY KEY`, the repository's clause is the
    // constant " WHERE id = ?", every by-id handler binds `{id}` — so an emitted app that
    // requested the override would talk to the wrong REST identifier.
    const content = gen.generate(domain({
      entityName: 'Order',
      systemFields: { primaryKeyField: 'uuid' },
    }), CTX)!.content;

    expect(content).toContain('String(current.id)');
    expect(content).not.toContain('current.uuid');
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

    expect(content).toContain('type="checkbox" formControlName="onVacation"');
    expect(content).toContain('type="checkbox" formControlName="flagged"');
    expect(content).toContain('onVacation: [false, []],');
    expect(content).toContain('flagged: [false, []],');

    // The seed is what fixes the cast: a boolean-seeded control is typed
    // `boolean | null`, which overlaps the DTO's `boolean` and casts cleanly.
    // A '' seed types it `string | null`, which does not overlap at all.
    expect(content).not.toContain("onVacation: ['', []],");
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

    expect(content).toContain('enabled: [true, []],');
    expect(content).toContain('archived: [false, []],');
    expect(content).not.toContain("enabled: ['true'");
  });

  it('a boolean default is read case-insensitively', () => {
    // defaultValue is free text from the annotation, so 'True' is the same intent as
    // 'true'. Anything else still falls back to false rather than being validated —
    // permissive, and deliberately so, matching the rest of this generator.
    const content = gen.generate(domain({
      entityName: 'Empire',
      fields: [field({ name: 'enabled', type: 'boolean', defaultValue: 'True' })],
    }), CTX)!.content;

    expect(content).toContain('enabled: [true, []],');
  });

  it('booleans are not run through the numeric coercion', () => {
    // Number(true) is 1. The coercion exists because a numeric control must seed ''
    // to keep "blank" distinct from 0; a checkbox has no blank state, so a boolean
    // needs no coercion and must not acquire one.
    const content = gen.generate(domain({
      entityName: 'Empire',
      fields: [field({ name: 'onVacation', type: 'boolean' })],
    }), CTX)!.content;

    expect(content).not.toContain('Number(raw.onVacation)');
    expect(content).toContain('const data = this.form.getRawValue();');
  });
});

// ---------- T20c: numeric coercion on submit ----------

describe('FormGenerator onSubmit numeric coercion (T20c)', () => {
  const gen = new FormGenerator();

  it('numeric (number-typed DTO) create field is coerced via Number() before the DTO cast', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'orderNumber', type: 'String' }),
        field({ name: 'quantity', type: 'java.lang.Integer' }),
      ],
    }), CTX)!.content;

    // raw read + spread + per-field numeric coercion
    expect(content).toContain('const raw = this.form.getRawValue();');
    expect(content).toContain('...raw,');
    expect(content).toContain("quantity: raw.quantity === null || raw.quantity === '' ? null : Number(raw.quantity),");
    // String field is NOT coerced
    expect(content).not.toContain('Number(raw.orderNumber)');
    // still dispatches with the coerced `data`
    expect(content).toContain('this.service.create(data as OrderCreate)');
  });

  it('every DslMapper number-typed variant is coerced', () => {
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
      expect(content).toContain(`${name}: raw.${name} === null || raw.${name} === '' ? null : Number(raw.${name}),`);
    }
  });

  // Regression: BigDecimal/BigInteger map to a `string` DTO (DslMapper precision
  // preservation, text input). Coercing them to Number() would lose precision AND
  // reintroduce a TS2352 (number value → string DTO field) — the exact crash the
  // first cut of this PR caused on the Stellar `ng build`.
  it('BigDecimal / BigInteger (string-typed DTO) are NOT coerced', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'total', type: 'java.math.BigDecimal' }),
        field({ name: 'ledger', type: 'java.math.BigInteger' }),
      ],
    }), CTX)!.content;

    expect(content).not.toContain('Number(raw.total)');
    expect(content).not.toContain('Number(raw.ledger)');
    // no numeric fields at all → plain getRawValue path
    expect(content).toContain('const data = this.form.getRawValue();');
    expect(content).not.toContain('const raw = this.form.getRawValue();');
  });

  it('no numeric create fields → plain getRawValue(), no coercion block', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('const data = this.form.getRawValue();');
    expect(content).not.toContain('Number(raw.');
    expect(content).not.toContain('const raw = this.form.getRawValue();');
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

  it('excludes inCreate=false fields', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'derived', type: 'String', inCreate: false }),
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
      // (no data-testid, no label, no FormBuilder entry).
      expect(content).not.toContain(`data-testid="field-${overlap}"`);
      // The FormBuilder block doesn't even reference the field name as
      // a key — guards against a silent partial filter that leaves
      // some occurrences behind.
      expect(content).not.toMatch(new RegExp(`^\\s*${overlap}: \\[`, 'm'));
    }
    // Sibling visible field survives.
    expect(content).toContain('data-testid="field-name"');
    expect(content).toMatch(/^\s*name: \[/m);
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
    }), CTX)!.content;

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
    }), CTX)!.content;

    expect(content).toContain("import { OrderStatus, OrderStatusDisplayNames } from '../types/enums';");
    expect(content).toContain('readonly OrderStatusValues = Object.values(OrderStatus);');
    expect(content).toContain('@for (value of OrderStatusValues; track value)');
    expect(content).not.toContain('com.shop.OrderStatus');
  });

  it('FQCN field type (containing dot, not java.*) is auto-detected as enum (simple name extracted)', () => {
    const content = gen.generate(domain({
      entityName: 'Tenant',
      fields: [field({ name: 'plan', type: 'eu.exeris.foundation.domain.TenantPlan' })],
    }), CTX)!.content;

    expect(content).toContain('<select id="plan"');
    expect(content).toContain('@for (value of TenantPlanValues; track value)');
    expect(content).toContain("import { TenantPlan, TenantPlanDisplayNames } from '../types/enums';");
  });

  it('FQCN types containing "Entity" or "DTO" are NOT auto-detected as enums (entity/DTO escape)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'ref', type: 'com.shop.CustomerEntity' }),
        field({ name: 'audit', type: 'com.shop.AuditDTO' }),
      ],
    }), CTX)!.content;

    // No select rendered for either field.
    expect(content).not.toContain('@for (value of CustomerEntityValues');
    expect(content).not.toContain('@for (value of AuditDTOValues');
    // No import line either.
    expect(content).not.toContain("from '../types/enums'");
  });

  it('java.lang.Boolean field renders a checkbox', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'flag', type: 'java.lang.Boolean' })],
    }), CTX)!.content;

    expect(content).toContain('type="checkbox" formControlName="flag"');
  });

  it.each([
    ['java.lang.Integer', 'number'],
    ['java.lang.Long', 'number'],
    ['java.lang.Double', 'number'],
    ['java.lang.Float', 'number'],
    ['java.time.Instant', 'datetime-local'],
    ['java.time.LocalDateTime', 'datetime-local'],
    ['java.time.LocalDate', 'date'],
    ['String', 'text'],
  ])('java type %s → input type=%s', (javaType, expectedInputType) => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'attr', type: javaType })],
    }), CTX)!.content;

    expect(content).toContain(`type="${expectedInputType}" formControlName="attr"`);
  });

  it('number input gets inputmode="decimal" extra attribute', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'amount', type: 'java.lang.Long' })],
    }), CTX)!.content;

    expect(content).toContain('inputmode="decimal"');
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

// ---------- validator builder ----------

describe('FormGenerator FormBuilder validators array', () => {
  const gen = new FormGenerator();

  function formGroupSliceFor(f: FieldMetadata): string {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [f],
    }), CTX)!.content;
    const groupStart = content.indexOf('readonly form = this.fb.group({');
    const groupEnd = content.indexOf('});', groupStart);
    return content.slice(groupStart, groupEnd);
  }

  it('required field → Validators.required in the FormBuilder array', () => {
    expect(formGroupSliceFor(field({ name: 'x', type: 'String', required: true })))
      .toContain('Validators.required');
  });

  it.each([
    ['minLength', 3, 'Validators.minLength(3)'],
    ['maxLength', 50, 'Validators.maxLength(50)'],
  ])('string %s=%s → %s', (attr, value, validator) => {
    expect(formGroupSliceFor(field({ name: 'x', type: 'String', [attr]: value } as Partial<FieldMetadata> & { name: string; type: string })))
      .toContain(validator);
  });

  it('numeric min / max → Validators.min / Validators.max', () => {
    expect(formGroupSliceFor(field({ name: 'x', type: 'java.lang.Long', min: 0 })))
      .toContain('Validators.min(0)');
    expect(formGroupSliceFor(field({ name: 'x', type: 'java.lang.Long', max: 100 })))
      .toContain('Validators.max(100)');
  });

  it('pattern → Validators.pattern(/<pattern>/)', () => {
    expect(formGroupSliceFor(field({ name: 'x', type: 'String', pattern: '^[A-Z]+$' })))
      .toContain('Validators.pattern(/^[A-Z]+$/)');
  });

  it('no validators set → empty [] array', () => {
    expect(formGroupSliceFor(field({ name: 'x', type: 'String' })))
      .toMatch(/x: \[[^,]+, \[\]\]/);
  });
});

// ---------- default values for FormBuilder ----------

describe('FormGenerator FormBuilder default values', () => {
  const gen = new FormGenerator();

  function defaultFor(f: FieldMetadata): string {
    const content = gen.generate(domain({ entityName: 'Thing', fields: [f] }), CTX)!.content;
    const m = content.match(new RegExp(`${f.name}: \\[(.+?), `));
    expect(m, `should match a ${f.name}: [<default>, ...] in the FormGroup`).not.toBeNull();
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
    expect(content).toContain('readonly class="mt-1 block w-full');
    // The "Computed from: ..." note.
    expect(content).toContain('Computed from: first, last');
  });

  it('each computed field generates an effect() block + a compute<Name> method stub', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'a', type: 'String' }),
        field({ name: 'b', type: 'String' }),
        field({ name: 'sum', type: 'String', computed: true, computedFrom: ['a', 'b'] }),
      ],
    }), CTX)!.content;

    // Effect block.
    expect(content).toContain("// Auto-sync sum based on a, b");
    expect(content).toContain('effect(() => {');
    expect(content).toContain("a: this.form.get('a')?.value");
    expect(content).toContain("b: this.form.get('b')?.value");
    expect(content).toContain('const computedSum = this.computeSum(values);');
    expect(content).toContain("this.form.get('sum')?.setValue(computedSum, { emitEvent: false });");

    // Compute stub at the bottom.
    expect(content).toContain('private computeSum(values: { a: any, b: any }): any {');
    expect(content).toContain('// TODO: Implement computation logic');
  });

  it('computed field without computedFrom → no effect block emitted', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'static', type: 'String', computed: true }),
      ],
    }), CTX)!.content;

    // No "Auto-sync" effect block.
    expect(content).not.toContain('Auto-sync static');
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
    expect(content).toContain('readonly current = computed<Address | null>(() => this.entity() ?? this.entityResource.value() ?? null);');
    expect(content).toContain("readonly editMode = computed(() => this.id() !== undefined || this.mode() === 'edit');");
    expect(content).toContain("{{ editMode() ? 'Update' : 'Create' }} Address");
  });

  it('patches the form from the effective entity', () => {
    expect(content).toContain('const entity = this.current();');
    expect(content).toContain('if (entity && this.editMode()) {');
  });

  it('never submits an edit before the entity is loaded', () => {
    expect(content).toContain('if (this.editMode() && !current) {');
    expect(content).toContain('[disabled]="form.invalid || saving() || (editMode() && !current())"');
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
    expect(versioned).not.toContain('formControlName="version"');
    expect(versioned).not.toContain('    version: [');
  });

  it('sends the loaded version on update, and leaves the create payload alone', () => {
    expect(versioned).toContain(
      'this.editMode() && current ? '
      + 'this.service.update(String(current.id), { ...data, version: this.loadedVersion() } as OrderUpdate) : '
      + 'this.service.create(data as OrderCreate);',
    );
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
    expect(versioned).toContain('this.form.reset(fresh as any);');
    expect(versioned).toContain('this.loadedVersion.set(fresh.version ?? null);');
  });

  it('keys the version on systemFields.versionField', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      versioned: true,
      fields: [field({ name: 'id', type: 'java.util.UUID' }), field({ name: 'rev', type: 'long' })],
      systemFields: { versionField: 'rev' } as DomainMetadata['systemFields'],
    }), CTX)!.content;
    expect(content).not.toContain('formControlName="rev"');
    expect(content).toContain('private readonly loadedVersion = signal<number | null>(null);');
    expect(content).toContain('{ ...data, rev: this.loadedVersion() } as OrderUpdate');
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
    expect(unversioned).toContain('this.service.update(String(current.id), data as OrderUpdate)');
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
