/**
 * Coverage for src/generators/angular/spec-gen.ts — the FE half of T2 (ADR-058).
 *
 * The proof that these specs are correct is that the emitted runner executes them: the CI FE job
 * generates the sample with `generateTests` on and runs `ng test`. What is asserted here is the
 * shape that gate depends on, and the two rules the ADR carries over — doubles rather than mocks,
 * and assertions derived from metadata rather than assumed.
 */

import { describe, expect, it } from 'vitest';
import { generateSchemaSpec, generateServiceSpec } from '../../../src/generators/angular/spec-gen.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { DomainMetadataSchema } from '../../../src/models/domain-model.js';

const entity = (fields: Array<Record<string, unknown>>, extra: Record<string, unknown> = {}) =>
  DomainMetadataSchema.parse({ packageName: 'com.shop', entityName: 'Order', fields, ...extra });

const idOnly = entity([{ name: 'id', type: 'java.util.UUID' }]);

describe('generateSchemaSpec', () => {
  it('emits beside the schema it exercises', () => {
    expect(generateSchemaSpec(idOnly, DEFAULT_CONFIG).path).toBe('schemas/order.schema.spec.ts');
  });

  // The fixture must satisfy the ZOD type, not the TS type: a BigDecimal is `string` in TypeScript
  // but `z.string().regex(/^-?\d+(\.\d+)?$/)` in the schema. Keying off tsType produced 'x', which
  // type-checks and then fails to parse — a failing spec emitted into a consumer's project.
  it('builds a numeric-string literal for a BigDecimal, not an arbitrary string', () => {
    const content = generateSchemaSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'total', type: 'java.math.BigDecimal' }]),
      DEFAULT_CONFIG,
    ).content;
    expect(content).toContain("total: '1',");
    expect(content).not.toContain("total: 'x',");
  });

  it.each([
    ['java.util.UUID', "'00000000-0000-0000-0000-000000000000'"],
    ['java.time.Instant', "'2026-01-01T00:00:00Z'"],
    ['java.lang.Integer', '1'],
    ['java.lang.Boolean', 'true'],
  ])('builds a %s literal its schema accepts', (type, literal) => {
    const content = generateSchemaSpec(entity([{ name: 'value', type }]), DEFAULT_CONFIG).content;
    expect(content).toContain(`value: ${literal},`);
  });

  it.each([
    ['java.time.LocalDate', "'2026-01-01'"],
    ['java.time.LocalTime', "'00:00:00'"],
    ['java.math.BigInteger', "'1'"],
    ['List<String>', '[]'],
  ])('builds a %s literal its schema accepts', (type, literal) => {
    const content = generateSchemaSpec(entity([{ name: 'value', type }]), DEFAULT_CONFIG).content;
    expect(content).toContain(`value: ${literal},`);
  });

  it.each([
    ['email', "'sample@example.com'"],
    ['url', "'https://example.com'"],
  ])('honours the %s format, which the schema overrides the base type with', (format, literal) => {
    const content = generateSchemaSpec(entity([{ name: 'value', type: 'String', format }]), DEFAULT_CONFIG).content;
    expect(content).toContain(`value: ${literal},`);
  });

  it('satisfies a declared minLength', () => {
    const content = generateSchemaSpec(
      entity([{ name: 'value', type: 'String', minLength: 4 }]), DEFAULT_CONFIG,
    ).content;
    expect(content).toContain("value: 'xxxx',");
  });

  // A pattern is the entity author's, not the mapper's, so no literal can be produced generically.
  it('omits a patterned string rather than inventing one that fails it', () => {
    const content = generateSchemaSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'code', type: 'String', pattern: '^[A-Z]{3}$' }]),
      DEFAULT_CONFIG,
    ).content;
    expect(content).not.toContain('code:');
  });

  // A fully-qualified generic maps to `z.lazy(() => List<String>Schema)` — a reference to a
  // schema that does not exist. No literal can satisfy it, so the field is omitted rather than
  // given one that fails.
  it('omits a field whose type the mapper has no literal for', () => {
    const content = generateSchemaSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'tags', type: 'java.util.List<String>' }]),
      DEFAULT_CONFIG,
    ).content;
    expect(content).not.toContain('tags:');
  });

  // Guessing a value that fails is worse than omitting an optional field.
  it('leaves out a field it cannot produce a valid literal for', () => {
    const content = generateSchemaSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'status', type: 'com.shop.Status', enumType: 'com.shop.Status' }]),
      DEFAULT_CONFIG,
    ).content;
    expect(content).not.toContain('status:');
  });

  // The probe that started this slice asserted an empty object fails the schema — and it passed,
  // because that entity declares no required field. An emitter must not invent that assertion.
  it('asserts a missing required field only when one is declared', () => {
    expect(generateSchemaSpec(idOnly, DEFAULT_CONFIG).content).not.toContain('rejects a Order missing');

    const withRequired = generateSchemaSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String', required: true }]),
      DEFAULT_CONFIG,
    ).content;
    expect(withRequired).toContain('rejects a Order missing %s');
    expect(withRequired).toContain('["name"]');
  });
});

describe('generateServiceSpec', () => {
  it('emits beside the service it exercises', () => {
    expect(generateServiceSpec(idOnly, DEFAULT_CONFIG).path).toBe('services/order.service.spec.ts');
  });

  // ADR-058: doubles, never mocks. Angular's own testing backend lives in @angular/common, which
  // the emitted app already depends on — so these specs add no dependency beyond the runner.
  it('drives the real service through Angular\'s testing backend, requiring no mocking library', () => {
    const content = generateServiceSpec(idOnly, DEFAULT_CONFIG).content;
    expect(content).toContain("import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';");
    expect(content).toContain('providers: [provideHttpClient(), provideHttpClientTesting()]');
    for (const mocking of ['jest', 'sinon', 'vi.mock', 'ts-mockito']) {
      expect(content).not.toContain(mocking);
    }
  });

  // The regression test for the defect that shipped twice: an emitted client calling a path the
  // emitted server does not serve. The asserted URL comes from the service's own authority.
  it('asserts the exact path the service builds, with no invented prefix', () => {
    const content = generateServiceSpec(idOnly, DEFAULT_CONFIG).content;
    expect(content).toContain("http.expectOne('/orders/42')");
    expect(content).not.toContain('/api/orders');
  });

  it('follows a configured apiBasePath rather than hard-coding one', () => {
    const content = generateServiceSpec(idOnly, { ...DEFAULT_CONFIG, apiBasePath: '/gateway' }).content;
    expect(content).toContain("http.expectOne('/gateway/orders/42')");
  });

  it('honours an entity-declared path', () => {
    const content = generateServiceSpec(entity([{ name: 'id', type: 'java.util.UUID' }], { path: '/custom' }), DEFAULT_CONFIG).content;
    expect(content).toContain("http.expectOne('/custom/42')");
  });

  it('verifies no request was left outstanding', () => {
    expect(generateServiceSpec(idOnly, DEFAULT_CONFIG).content).toContain('afterEach(() => http.verify());');
  });
});
