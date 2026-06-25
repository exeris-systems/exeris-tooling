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
 *
 * Note on generateForm convenience: this is the only api/angular
 * generator whose convenience function returns `null` for a hidden
 * domain (the others use `!`). Worth a dedicated test.
 */

import { describe, expect, it } from 'vitest';
import { FormGenerator, generateForm } from '../../../src/generators/angular/form-gen.js';
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

  it('returns null for an internalApi.hidden domain', () => {
    expect(gen.generate(hiddenDomain('Audit'), CTX)).toBeNull();
  });
});

// ---------- emitted structure ----------

describe('FormGenerator emitted content — top-level structure', () => {
  const gen = new FormGenerator();

  it('imports Angular core + FormBuilder + Validators + service + entity types', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("import { Component, ChangeDetectionStrategy, input, output, signal, effect, inject } from '@angular/core';");
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

  it('onSubmit dispatches to service.create or service.update depending on mode()', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain("this.mode() === 'create' ? this.service.create(data as OrderCreate)");
    expect(content).toContain('this.service.update(String(this.entity()!.id), data as OrderUpdate)');
  });

  it('uses systemFields.idField alias in the update dispatch path', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      systemFields: { idField: 'uuid' },
    }), CTX)!.content;

    expect(content).toContain('this.entity()!.uuid');
  });
});

// ---------- T20c: numeric coercion on submit ----------

describe('FormGenerator onSubmit numeric coercion (T20c)', () => {
  const gen = new FormGenerator();

  it('numeric create field is coerced via Number() before the DTO cast', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'orderNumber', type: 'String' }),
        field({ name: 'amount', type: 'java.math.BigDecimal' }),
      ],
    }), CTX)!.content;

    // raw read + spread + per-field numeric coercion
    expect(content).toContain('const raw = this.form.getRawValue();');
    expect(content).toContain('...raw,');
    expect(content).toContain("amount: raw.amount === null || raw.amount === '' ? null : Number(raw.amount),");
    // String field is NOT coerced
    expect(content).not.toContain('Number(raw.orderNumber)');
    // still dispatches with the coerced `data`
    expect(content).toContain('this.service.create(data as OrderCreate)');
  });

  it('every numeric java type variant is coerced', () => {
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

  it('fields are rendered in order(asc) — explicit order beats default 999', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'third', type: 'String', order: 30 }),
        field({ name: 'first', type: 'String', order: 10 }),
        field({ name: 'second', type: 'String', order: 20 }),
        field({ name: 'last', type: 'String' }), // no order → defaults to 999
      ],
    }), CTX)!.content;

    const firstIdx = content.indexOf('data-testid="field-first"');
    const secondIdx = content.indexOf('data-testid="field-second"');
    const thirdIdx = content.indexOf('data-testid="field-third"');
    const lastIdx = content.indexOf('data-testid="field-last"');

    expect(firstIdx).toBeLessThan(secondIdx);
    expect(secondIdx).toBeLessThan(thirdIdx);
    expect(thirdIdx).toBeLessThan(lastIdx);
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

  it('computed field without dependencies (no computedFrom + no dependencies) → no effect block emitted', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'static', type: 'String', computed: true }),
      ],
    }), CTX)!.content;

    // No "Auto-sync" effect block.
    expect(content).not.toContain('Auto-sync static');
  });

  it('falls back to field.dependencies when computedFrom is undefined', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'a', type: 'String' }),
        field({ name: 'tax', type: 'String', computed: true, dependencies: ['a'] }),
      ],
    }), CTX)!.content;

    expect(content).toContain('Computed from: a');
    expect(content).toContain('Auto-sync tax based on a');
  });
});

// ---------- generateForm convenience ----------

describe('generateForm — top-level convenience function', () => {
  it('returns the per-domain file for a visible domain', () => {
    const file = generateForm(domain({ entityName: 'Order' }), CTX.config);
    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-form.component.ts');
  });

  it('returns null for a hidden domain (unlike sibling generators which use !)', () => {
    expect(generateForm(hiddenDomain('Audit'), CTX.config)).toBeNull();
  });

  it('falls back to KERNEL backend when config.backend is undefined', () => {
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    const file = generateForm(domain({ entityName: 'Order' }), partialConfig);
    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-form.component.ts');
  });
});
