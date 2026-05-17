/**
 * Coverage for src/models/dsl-mapper.ts — the DslMapper static methods
 * and the JAVA_TO_TS_MAP lookup table.
 *
 * mapType is exercised through:
 *   - direct-lookup hits (one fixture per category: primitive, boxed,
 *     bignum, date/time, UUID, byte[], collection, JSON)
 *   - simple-name fallback (FQN with package prefix → simple-name hit)
 *   - generic parsing (List<X> / Set<X> / Collection<X> / Map<K,V> /
 *     Optional<X>)
 *   - array notation (X[])
 *   - custom-entity fallback for an unknown type
 *
 * mapField is exercised through every validation builder branch + each
 * format-driven inputType override + the textarea promotion for long
 * strings.
 *
 * The four formatting helpers (humanize / toKebabCase / toCamelCase /
 * toInterfaceName) get focused one-liner tests.
 */

import { describe, expect, it } from 'vitest';
import { DslMapper } from '../../src/models/dsl-mapper.js';
import { FieldMetadataSchema, type FieldMetadata } from '../../src/models/domain-model.js';

// ---------- mapType: direct lookup hits ----------

describe('DslMapper.mapType — direct lookup hits', () => {
  it('String → string / z.string()', () => {
    const m = DslMapper.mapType('String');
    expect(m.tsType).toBe('string');
    expect(m.formControl).toBe('input');
    expect(m.inputType).toBe('text');
    expect(m.zodType).toBe('z.string()');
    expect(m.defaultValue).toBe("''");
  });

  it.each([
    ['int', 'number', 'z.number().int()', '0'],
    ['Integer', 'number | null', 'z.number().int().nullable()', 'null'],
    ['long', 'number', 'z.number()', '0'],
    ['Long', 'number | null', 'z.number().nullable()', 'null'],
    ['double', 'number', 'z.number()', '0'],
    ['Double', 'number | null', 'z.number().nullable()', 'null'],
    ['float', 'number', 'z.number()', '0'],
    ['Float', 'number | null', 'z.number().nullable()', 'null'],
  ])('numeric type %s → %s', (javaType, tsType, zodType, defaultValue) => {
    const m = DslMapper.mapType(javaType);
    expect(m.tsType).toBe(tsType);
    expect(m.zodType).toBe(zodType);
    expect(m.defaultValue).toBe(defaultValue);
    expect(m.formControl).toBe('number');
    expect(m.inputType).toBe('number');
  });

  it.each([
    ['boolean', 'boolean', 'z.boolean()', 'false'],
    ['Boolean', 'boolean | null', 'z.boolean().nullable()', 'null'],
  ])('boolean type %s → %s', (javaType, tsType, zodType, defaultValue) => {
    const m = DslMapper.mapType(javaType);
    expect(m.tsType).toBe(tsType);
    expect(m.zodType).toBe(zodType);
    expect(m.defaultValue).toBe(defaultValue);
    expect(m.formControl).toBe('checkbox');
  });

  it.each([
    ['BigDecimal', 'string'],
    ['BigInteger', 'string'],
  ])('big-number %s → string with regex validation', (javaType) => {
    const m = DslMapper.mapType(javaType);
    expect(m.tsType).toBe('string');
    expect(m.zodType).toContain('regex');
  });

  it.each([
    ['Instant', 'datepicker', 'datetime-local'],
    ['LocalDate', 'datepicker', 'date'],
    ['LocalDateTime', 'datepicker', 'datetime-local'],
    ['LocalTime', 'input', 'time'],
    ['ZonedDateTime', 'datepicker', 'datetime-local'],
    ['Date', 'datepicker', 'datetime-local'],
  ])('date/time type %s → %s / %s', (javaType, formControl, inputType) => {
    const m = DslMapper.mapType(javaType);
    expect(m.formControl).toBe(formControl);
    expect(m.inputType).toBe(inputType);
  });

  it.each([
    ['Duration', "'PT0S'"],
    ['Period', "'P0D'"],
  ])('temporal-amount %s defaults to ISO-8601 zero %s', (javaType, defaultValue) => {
    expect(DslMapper.mapType(javaType).defaultValue).toBe(defaultValue);
  });

  it('UUID → z.string().uuid()', () => {
    expect(DslMapper.mapType('UUID').zodType).toBe('z.string().uuid()');
  });

  it('byte[] → file input', () => {
    const m = DslMapper.mapType('byte[]');
    expect(m.formControl).toBe('file');
    expect(m.inputType).toBe('file');
  });

  it('Blob → Blob type + file form control', () => {
    const m = DslMapper.mapType('Blob');
    expect(m.tsType).toBe('Blob');
    expect(m.formControl).toBe('file');
    expect(m.zodType).toBe('z.instanceof(Blob)');
  });

  it.each([
    ['List', 'unknown[]', '[]'],
    ['Set', 'unknown[]', '[]'],
  ])('bare collection %s → unknown[]', (javaType, tsType, defaultValue) => {
    const m = DslMapper.mapType(javaType);
    expect(m.tsType).toBe(tsType);
    expect(m.defaultValue).toBe(defaultValue);
  });

  it('bare Map → Record<string, unknown>', () => {
    const m = DslMapper.mapType('Map');
    expect(m.tsType).toBe('Record<string, unknown>');
    expect(m.defaultValue).toBe('{}');
  });

  it.each(['JsonNode', 'com.fasterxml.jackson.databind.JsonNode'])(
    'JsonNode form %s → unknown / textarea',
    (javaType) => {
      const m = DslMapper.mapType(javaType);
      expect(m.tsType).toBe('unknown');
      expect(m.formControl).toBe('textarea');
    },
  );
});

// ---------- mapType: simple-name fallback ----------

describe('DslMapper.mapType — simple-name fallback (FQN with package)', () => {
  it('java.lang.String resolves via the direct java.lang.String entry', () => {
    expect(DslMapper.mapType('java.lang.String').tsType).toBe('string');
  });

  it('com.example.UUID resolves via the SIMPLE-name UUID entry (fallback path)', () => {
    // UUID is in the map under "UUID" and "java.util.UUID" but NOT
    // under "com.example.UUID" — the simple-name fallback fires.
    expect(DslMapper.mapType('com.example.UUID').zodType).toBe('z.string().uuid()');
  });

  it('java.math.BigDecimal hits the dedicated FQN entry', () => {
    expect(DslMapper.mapType('java.math.BigDecimal').zodType).toContain('regex');
  });
});

// ---------- mapType: generics ----------

describe('DslMapper.mapType — generic parsing', () => {
  it('List<String> → string[]', () => {
    const m = DslMapper.mapType('List<String>');
    expect(m.tsType).toBe('string[]');
    expect(m.zodType).toBe('z.array(z.string())');
    expect(m.formControl).toBe('textarea');
    expect(m.defaultValue).toBe('[]');
  });

  it('Set<UUID> → string[] (UUID element resolves to string)', () => {
    const m = DslMapper.mapType('Set<UUID>');
    expect(m.tsType).toBe('string[]');
    expect(m.zodType).toBe('z.array(z.string().uuid())');
  });

  it('Collection<Integer> → (number | null)[] — union arms wrapped in parens before []', () => {
    // Without paren-wrapping, the emitted string `number | null[]`
    // would parse as `number | (null[])` (a number OR an array of
    // nulls), not `(number | null)[]` (an array of nullable numbers).
    // The arrayOf helper in dsl-mapper now wraps unions explicitly.
    const m = DslMapper.mapType('Collection<Integer>');
    expect(m.tsType).toBe('(number | null)[]');
  });

  it('Map<String, Integer> → Record<string, number | null>', () => {
    const m = DslMapper.mapType('Map<String, Integer>');
    expect(m.tsType).toBe('Record<string, number | null>');
    expect(m.zodType).toBe('z.record(z.number().int().nullable())');
  });

  it('Map<String> with missing value type → Record<string, unknown> fallback', () => {
    // Only one type arg supplied — the value-type lookup defaults to
    // { tsType: "unknown", zodType: "z.unknown()" }.
    const m = DslMapper.mapType('Map<String>');
    expect(m.tsType).toBe('Record<string, unknown>');
  });

  it('Optional<LocalDate> → string | null + datepicker form control preserved from inner type', () => {
    const m = DslMapper.mapType('Optional<LocalDate>');
    expect(m.tsType).toBe('string | null');
    expect(m.formControl).toBe('datepicker');
    expect(m.defaultValue).toBe('null');
    expect(m.zodType).toBe('z.string().date().nullable()');
  });

  it('unknown generic container (UnknownContainer<String>) falls through to the custom-entity fallback', () => {
    // The generic match succeeds (containerType captured) but none of
    // the List/Set/Collection/Map/Optional branches match → falls
    // through to the final "custom entity" return at the bottom of
    // mapType.
    const m = DslMapper.mapType('UnknownContainer<String>');
    expect(m.tsType).toBe('UnknownContainer<String>');
    expect(m.zodType).toContain('UnknownContainer<String>Schema');
  });
});

// ---------- mapType: array notation ----------

describe('DslMapper.mapType — array notation (X[])', () => {
  it('String[] → string[]', () => {
    const m = DslMapper.mapType('String[]');
    expect(m.tsType).toBe('string[]');
    expect(m.zodType).toBe('z.array(z.string())');
    expect(m.formControl).toBe('textarea');
  });

  it('Integer[] → (number | null)[] — union arms wrapped in parens (same fix as the generic-array path)', () => {
    expect(DslMapper.mapType('Integer[]').tsType).toBe('(number | null)[]');
  });
});

// ---------- mapType: custom-entity fallback ----------

describe('DslMapper.mapType — custom-entity fallback', () => {
  it('unknown type (no map hit, no generic, no array) → simple name + lazy schema reference', () => {
    const m = DslMapper.mapType('OrderLine');
    expect(m.tsType).toBe('OrderLine');
    expect(m.zodType).toBe('z.lazy(() => OrderLineSchema)');
    expect(m.formControl).toBe('input');
    expect(m.defaultValue).toBe('null as unknown as OrderLine');
  });

  it('FQN custom entity uses just the simple name in the schema reference', () => {
    const m = DslMapper.mapType('com.shop.Order');
    expect(m.tsType).toBe('Order');
    expect(m.zodType).toBe('z.lazy(() => OrderSchema)');
  });
});

// ---------- mapField: validation builder + format / length overrides ----------

/**
 * Build a FieldMetadata fixture by routing through FieldMetadataSchema.parse
 * — that way every documented default comes from the same Zod source of
 * truth as production code. A future change to a schema default
 * automatically flows into these mapField tests instead of silently
 * drifting from the source.
 */
function field(overrides: Partial<FieldMetadata>): FieldMetadata {
  return FieldMetadataSchema.parse({ name: 'someField', type: 'String', ...overrides });
}

describe('DslMapper.mapField — validation builder', () => {
  it('required → emits ".min(1)" validation', () => {
    expect(DslMapper.mapField(field({ required: true })).validations).toContain('.min(1)');
  });

  it('minLength + maxLength on a string → emits both ".min" and ".max" validations', () => {
    const m = DslMapper.mapField(field({ minLength: 3, maxLength: 50 }));
    expect(m.validations).toContain('.min(3)');
    expect(m.validations).toContain('.max(50)');
  });

  it('numeric min + max → emits ".min" and ".max" validations on the number type', () => {
    const m = DslMapper.mapField(field({ type: 'int', min: 0, max: 100 }));
    expect(m.validations).toContain('.min(0)');
    expect(m.validations).toContain('.max(100)');
  });

  it('pattern → emits a ".regex(/.../)" validation', () => {
    const m = DslMapper.mapField(field({ pattern: '^[A-Z]+$' }));
    expect(m.validations.some(v => v.includes('regex'))).toBe(true);
    expect(m.validations.find(v => v.includes('regex'))).toContain('^[A-Z]+$');
  });

  it('format=email → adds ".email()" validation', () => {
    expect(DslMapper.mapField(field({ format: 'email' })).validations).toContain('.email()');
  });

  it('format=url → adds ".url()" validation', () => {
    expect(DslMapper.mapField(field({ format: 'url' })).validations).toContain('.url()');
  });

  it('no validation hints → emits an empty validations array', () => {
    expect(DslMapper.mapField(field({})).validations).toEqual([]);
  });

  it('minLength=0 / maxLength=0 are TREATED AS OMITTED (truthy guard skips zero)', () => {
    // Pins the deliberate-asymmetry-with-comment in dsl-mapper.ts:
    // minLength=0 / maxLength=0 are semantically the same as omitting
    // the constraint, so the truthy check drops them. A regression that
    // flipped these to `!== undefined` (like the numeric min/max guards)
    // would start emitting `.min(0)` / `.max(0)` noise on every form
    // schema with zero-bounded text fields, and would fail this test.
    expect(DslMapper.mapField(field({ minLength: 0 })).validations).toEqual([]);
    expect(DslMapper.mapField(field({ maxLength: 0 })).validations).toEqual([]);
  });

  it('min=0 / max=0 ARE preserved (`!== undefined` guard, not truthy) — numeric bounds are real constraints at zero', () => {
    // Sibling pin for the OTHER half of the asymmetry: min=0 means
    // "values must be ≥ 0" and is a meaningful Zod validation. If a
    // future change flipped these to a truthy check (matching
    // minLength/maxLength), zero-bounded numeric constraints would
    // silently disappear.
    const minOnly = DslMapper.mapField(field({ type: 'int', min: 0 }));
    expect(minOnly.validations).toContain('.min(0)');
    const maxOnly = DslMapper.mapField(field({ type: 'int', max: 0 }));
    expect(maxOnly.validations).toContain('.max(0)');
  });
});

describe('DslMapper.mapField — format-driven inputType override', () => {
  it.each([
    ['email', 'email'],
    ['url', 'url'],
    ['password', 'password'],
    ['tel', 'tel'],
    ['color', 'color'],
  ])('format=%s → inputType=%s', (format, expectedInputType) => {
    expect(DslMapper.mapField(field({ format })).inputType).toBe(expectedInputType);
  });

  it('format=unknown → inputType falls back to the type-driven mapping', () => {
    // "weird" is not in the format switch → inputType stays at the
    // String mapping's default of "text".
    expect(DslMapper.mapField(field({ format: 'weird' })).inputType).toBe('text');
  });
});

describe('DslMapper.mapField — length-driven formControl promotion', () => {
  it('maxLength > 255 promotes input → textarea', () => {
    expect(DslMapper.mapField(field({ maxLength: 1000 })).formControl).toBe('textarea');
  });

  it('maxLength ≤ 255 keeps input', () => {
    expect(DslMapper.mapField(field({ maxLength: 100 })).formControl).toBe('input');
  });

  it('non-string types are NOT promoted to textarea even with maxLength > 255', () => {
    // The promotion guard checks formControl === "input"; an int field
    // is "number" so it should stay "number".
    expect(DslMapper.mapField(field({ type: 'int', maxLength: 1000 })).formControl).toBe('number');
  });
});

describe('DslMapper.mapField — label + placeholder fallback', () => {
  it('label falls back to humanize(name) when displayName is absent', () => {
    expect(DslMapper.mapField(field({ name: 'firstName' })).label).toBe('First Name');
  });

  it('displayName overrides the humanize fallback', () => {
    expect(DslMapper.mapField(field({ name: 'firstName', displayName: 'Imię' })).label).toBe('Imię');
  });

  it('placeholder uses description if present, empty string otherwise', () => {
    expect(DslMapper.mapField(field({ description: 'Customer first name' })).placeholder)
      .toBe('Customer first name');
    expect(DslMapper.mapField(field({})).placeholder).toBe('');
  });

  it('fieldName mirrors the input field.name', () => {
    expect(DslMapper.mapField(field({ name: 'orderId' })).fieldName).toBe('orderId');
  });
});

// ---------- humanize / toKebabCase / toCamelCase / toInterfaceName ----------

describe('DslMapper.humanize', () => {
  it('camelCase → space-separated Title Case', () => {
    expect(DslMapper.humanize('firstName')).toBe('First Name');
    expect(DslMapper.humanize('orderLineItem')).toBe('Order Line Item');
  });

  it('already-capitalized → leaves first char upper, splits internal uppercase', () => {
    expect(DslMapper.humanize('OrderId')).toBe('Order Id');
  });

  it('all-lowercase → first-char upper, no internal spaces', () => {
    expect(DslMapper.humanize('active')).toBe('Active');
  });
});

describe('DslMapper.toKebabCase', () => {
  it('camelCase → kebab-case', () => {
    expect(DslMapper.toKebabCase('OrderLineItem')).toBe('order-line-item');
    expect(DslMapper.toKebabCase('orderLine')).toBe('order-line');
  });

  it('all-lowercase passes through unchanged', () => {
    expect(DslMapper.toKebabCase('order')).toBe('order');
  });
});

describe('DslMapper.toCamelCase', () => {
  it('first letter lowercased, rest unchanged', () => {
    expect(DslMapper.toCamelCase('OrderLine')).toBe('orderLine');
  });

  it('already-camelCase passes through unchanged', () => {
    expect(DslMapper.toCamelCase('orderLine')).toBe('orderLine');
  });
});

describe('DslMapper.toInterfaceName', () => {
  it('strips trailing "Entity" suffix', () => {
    expect(DslMapper.toInterfaceName('OrderEntity')).toBe('Order');
  });

  it('leaves names without the "Entity" suffix unchanged', () => {
    expect(DslMapper.toInterfaceName('Order')).toBe('Order');
  });
});
