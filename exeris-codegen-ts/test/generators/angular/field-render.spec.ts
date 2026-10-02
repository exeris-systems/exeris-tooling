/**
 * Coverage for the field-render model (ADR-047 Amendment 1).
 *
 * Exercises:
 *   - per Java type: list cell, detail display type and enum, form control, input type and value kind
 *   - the dataType facets, the foreign-key link and the enum detected by each surface's own rule
 *   - system fields (audited, versioned, tenantId), readOnly, inCreate = false, hidden and computed fields
 *   - the form's initial value, and the facet slot leaving the result unchanged
 *   - list-gen, detail-gen and form-gen keeping no control or format mapping of their own
 */

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  fieldRenderContext,
  resolveFieldRender,
  resolveFieldRenders,
  type FieldRenderModel,
} from '../../../src/generators/angular/field-render.js';
import { DslMapper } from '../../../src/models/dsl-mapper.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../../src/models/domain-model.js';

function domain(overrides: Record<string, unknown>): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', entityName: 'Order', ...overrides });
}

/** Resolves the one field `name` of an entity carrying `field` beside an id. */
function render(field: Record<string, unknown>, overrides: Record<string, unknown> = {}, all: DomainMetadata[] = []): FieldRenderModel {
  const d = domain({ fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'value', ...field }], ...overrides });
  return resolveFieldRenders(d, fieldRenderContext(d, [d, ...all], true)).find((r) => r.name === (field.name ?? 'value'))!;
}

describe('resolveFieldRender — per type', () => {
  type Row = {
    type: string;
    cell: FieldRenderModel['list']['cell'];
    display: FieldRenderModel['detail']['display'];
    inputType: string;
    control: FieldRenderModel['form']['control'];
    value: FieldRenderModel['form']['value'];
  };
  const rows: Row[] = [
    { type: 'String', cell: 'text', display: 'text', inputType: 'text', control: 'input', value: 'text' },
    { type: 'java.lang.String', cell: 'text', display: 'text', inputType: 'text', control: 'input', value: 'text' },
    // The form maps only the qualified wrapper to a number input; the primitive is still coerced.
    { type: 'long', cell: 'text', display: 'text', inputType: 'text', control: 'input', value: 'number' },
    { type: 'java.lang.Long', cell: 'text', display: 'number', inputType: 'number', control: 'input', value: 'number' },
    { type: 'int', cell: 'text', display: 'text', inputType: 'text', control: 'input', value: 'number' },
    { type: 'java.lang.Integer', cell: 'text', display: 'number', inputType: 'number', control: 'input', value: 'number' },
    // BigDecimal is a string DTO for precision: a text input, never coerced.
    { type: 'java.math.BigDecimal', cell: 'text', display: 'text', inputType: 'text', control: 'input', value: 'text' },
    // The list badges only the Boolean wrapper's simple name.
    { type: 'boolean', cell: 'text', display: 'boolean', inputType: 'checkbox', control: 'checkbox', value: 'boolean' },
    { type: 'Boolean', cell: 'boolean', display: 'boolean', inputType: 'checkbox', control: 'checkbox', value: 'boolean' },
    { type: 'java.lang.Boolean', cell: 'text', display: 'text', inputType: 'checkbox', control: 'checkbox', value: 'boolean' },
    { type: 'java.util.UUID', cell: 'text', display: 'text', inputType: 'text', control: 'input', value: 'text' },
    { type: 'java.time.LocalDate', cell: 'date', display: 'date', inputType: 'date', control: 'input', value: 'text' },
    { type: 'java.time.Instant', cell: 'datetime', display: 'datetime', inputType: 'datetime-local', control: 'input', value: 'text' },
    // The list's date test matches the substring `Date` before the date-time test is reached.
    { type: 'java.time.LocalDateTime', cell: 'date', display: 'datetime', inputType: 'datetime-local', control: 'input', value: 'text' },
  ];

  for (const row of rows) {
    it(`${row.type}`, () => {
      const r = render({ type: row.type });
      expect(r.list.cell).toBe(row.cell);
      expect(r.detail.display).toBe(row.display);
      expect(r.form.inputType).toBe(row.inputType);
      expect(r.form.control).toBe(row.control);
      expect(r.form.value).toBe(row.value);
      expect(r.form.inputMode).toBe(row.inputType === 'number' ? 'decimal' : undefined);
      expect(r.list.align).toBe('left');
      expect(r.placeholder).toBeUndefined();
      expect(r.help).toBeUndefined();
    });
  }
});

describe('resolveFieldRender — facets, enums and links', () => {
  it('the dataType facets drive the list cell, the detail facet and a numeric or url input', () => {
    const currency = render({ type: 'java.math.BigDecimal', dataType: 'currency' });
    expect(currency.list.cell).toBe('currency');
    expect(currency.detail.dataType).toBe('currency');
    expect(currency.form.inputType).toBe('number');
    expect(render({ type: 'java.math.BigDecimal', dataType: 'percent' }).list.cell).toBe('percent');
    const url = render({ type: 'String', dataType: 'url' });
    expect([url.list.cell, url.detail.dataType, url.form.inputType]).toEqual(['url', 'url', 'url']);
  });

  it('a dataType outside the three facets renders as the default', () => {
    const r = render({ type: 'String', dataType: 'email' });
    expect(r.list.cell).toBe('text');
    expect(r.detail.dataType).toBeUndefined();
    expect(r.form.inputType).toBe('text');
  });

  it('an explicit enumType is an enum on every surface, by its simple name', () => {
    const r = render({ type: 'com.shop.OrderStatus', enumType: 'com.shop.OrderStatus' });
    expect(r.detail).toMatchObject({ display: 'enum', enumType: 'OrderStatus' });
    expect(r.form).toMatchObject({ control: 'select', enumType: 'OrderStatus' });
  });

  it('without enumType the detail view and the form detect an enum by different rules', () => {
    // Detail: a capitalised simple name ending in Status/Type/Role/State. Form: any qualified
    // non-JDK type that names no entity or DTO.
    const bare = render({ type: 'OrderStatus' });
    expect(bare.detail.enumType).toBe('OrderStatus');
    expect(bare.form.enumType).toBeUndefined();
    const address = render({ type: 'com.shop.Address' });
    expect(address.detail.enumType).toBeUndefined();
    expect(address.form).toMatchObject({ control: 'select', enumType: 'Address' });
    expect(render({ type: 'com.shop.CustomerEntity' }).form.enumType).toBeUndefined();
  });

  it('a MANY_TO_ONE UUID foreign key to a routed entity links, and the list cell is the link', () => {
    const customer = domain({ entityName: 'Customer', fields: [{ name: 'id', type: 'java.util.UUID' }] });
    const r = render(
      { name: 'customerId', type: 'java.util.UUID' },
      { relationships: [{ name: 'customerId', targetEntity: 'com.shop.Customer', type: 'MANY_TO_ONE' }] },
      [customer],
    );
    expect(r.link).toBe(`/${DslMapper.routePlural('Customer')}`);
    expect(r.list.cell).toBe('link');
    expect(r.detail.display).toBe('text');
  });

  it('a filterable Boolean gets the yes/no filter; any other filterable type gets none', () => {
    expect(render({ type: 'Boolean', filterable: true }).list.filter).toBe('boolean-select');
    expect(render({ type: 'boolean', filterable: true }).list.filter).toBeUndefined();
    expect(render({ type: 'Boolean' }).list.filter).toBeUndefined();
  });
});

describe('resolveFieldRender — placement', () => {
  it('audit stamps and the version of an audited, versioned entity are system fields', () => {
    const d = domain({
      audited: true,
      versioned: true,
      fields: [
        { name: 'id', type: 'java.util.UUID' },
        { name: 'createdAt', type: 'java.time.Instant' },
        { name: 'rev', type: 'java.lang.Long' },
        { name: 'tenantId', type: 'java.util.UUID' },
        { name: 'name', type: 'String' },
      ],
      systemFields: { versionField: 'rev' },
    });
    const byName = new Map(resolveFieldRenders(d, fieldRenderContext(d, [d], true)).map((r) => [r.name, r]));
    for (const name of ['createdAt', 'rev', 'tenantId']) {
      expect(byName.get(name)).toMatchObject({ system: true, displayed: false, form: { placement: 'none' } });
    }
    expect(byName.get('name')).toMatchObject({ system: false, displayed: true, form: { placement: 'control' } });
  });

  it('a readOnly field is displayed but has no control', () => {
    expect(render({ type: 'String', readOnly: true })).toMatchObject({ displayed: true, form: { placement: 'none', readOnly: true } });
  });

  it('inCreate = false removes the control, the computed input included', () => {
    expect(render({ type: 'String', inCreate: false }).form.placement).toBe('none');
    expect(render({ type: 'String', inCreate: false, computed: true }).form.placement).toBe('none');
  });

  it('a hidden field is not displayed and has no control', () => {
    expect(render({ type: 'String', hidden: true })).toMatchObject({ displayed: false, form: { placement: 'none' } });
  });

  it('a computed field is a read-only input naming its sources, even when readOnly', () => {
    const r = render({ type: 'java.lang.Long', computed: true, computedFrom: ['a', 'b'], readOnly: true });
    expect(r.form).toMatchObject({ placement: 'computed', computedFrom: ['a', 'b'], inputType: 'number' });
  });

  it('a lifecycle field has no control on an entity without a system-field block', () => {
    expect(render({ name: 'active', type: 'Boolean' }).form.placement).toBe('none');
  });
});

describe('resolveFieldRender — labels and initial values', () => {
  it('the list/detail label humanizes; the form label title-cases; displayName wins on both', () => {
    const r = render({ name: 'unitPrice', type: 'String' });
    expect(r.label).toBe('Unit Price');
    expect(r.form.label).toBe('Unit Price');
    const named = render({ type: 'String', displayName: 'Price' });
    expect([named.label, named.form.label]).toEqual(['Price', 'Price']);
  });

  it('a boolean seeds a boolean, from its declared default; a number seeds a number or null; any other control seeds a string', () => {
    expect(render({ type: 'boolean' }).form.initialValue).toBe('false');
    expect(render({ type: 'Boolean', defaultValue: ' TRUE ' }).form.initialValue).toBe('true');
    expect(render({ type: 'java.lang.Integer' }).form.initialValue).toBe('null');
    expect(render({ type: 'java.lang.Integer', defaultValue: '7' }).form.initialValue).toBe('7');
    expect(render({ type: 'java.lang.Integer', defaultValue: 'seven' }).form.initialValue).toBe('null');
    expect(render({ type: 'String' }).form.initialValue).toBe("''");
    expect(render({ type: 'String', defaultValue: 'x' }).form.initialValue).toBe("'x'");
  });

  it('the form model holds each value kind as its DTO type, with an empty value of that type', () => {
    expect(render({ type: 'boolean' }).form).toMatchObject({ modelType: 'boolean', emptyValue: 'false' });
    expect(render({ type: 'long' }).form).toMatchObject({ modelType: 'number | null', emptyValue: 'null' });
    expect(render({ type: 'java.math.BigDecimal' }).form).toMatchObject({ modelType: 'string', emptyValue: "''" });
    expect(render({ type: 'com.shop.Status', enumType: 'com.shop.Status' }).form).toMatchObject({ modelType: 'string', emptyValue: "''" });
  });
});

describe('resolveFieldRender — determinism and the facet slot', () => {
  it('resolves the same model twice, and an absent facet changes nothing', () => {
    const d = domain({ fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'status', type: 'com.shop.S', enumType: 'com.shop.S' }] });
    const ctx = fieldRenderContext(d, [d], true);
    const field = d.fields[1];
    expect(resolveFieldRender(field, ctx)).toEqual(resolveFieldRender(field, ctx));
    expect(resolveFieldRender(field, ctx, undefined)).toEqual(resolveFieldRender(field, ctx));
  });
});

describe('list-gen, detail-gen and form-gen render fields only through the model', () => {
  const dir = join(dirname(fileURLToPath(import.meta.url)), '../../../src/generators/angular');
  // Each is a per-field render decision the model owns; finding one in a generator is a second mapping.
  const OWNED_BY_MODEL = [
    'DslMapper.mapField(',
    'DslMapper.mapType(',
    'foreignKeyLinks(',
    "includes('Date')",
    "=== 'Boolean'",
    "'java.time.",
    "'java.lang.",
    '.dataType ===',
  ];

  for (const file of ['list-gen.ts', 'detail-gen.ts', 'form-gen.ts']) {
    it(file, () => {
      const source = readFileSync(join(dir, file), 'utf8');
      expect(source).toContain("from './field-render.js'");
      for (const needle of OWNED_BY_MODEL) {
        expect(source, needle).not.toContain(needle);
      }
    });
  }
});
