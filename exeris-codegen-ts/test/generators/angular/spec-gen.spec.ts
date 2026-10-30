/**
 * Coverage for src/generators/angular/spec-gen.ts — the FE half of T2 (ADR-058).
 *
 * The proof that these specs are correct is that the emitted runner executes them: the CI FE job
 * generates the sample with `generateTests` on and runs `ng test`. What is asserted here is the
 * shape that gate depends on, and the two rules the ADR carries over — doubles rather than mocks,
 * and assertions derived from metadata rather than assumed.
 */

import { describe, expect, it } from 'vitest';
import { generateFormSpec, generateSchemaSpec, generateServiceSpec, generateStoreSpec } from '../../../src/generators/angular/spec-gen.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { parseOffered } from '../../support/offered.js';

const entity = (fields: Array<Record<string, unknown>>, extra: Record<string, unknown> = {}) =>
  parseOffered({ packageName: 'com.shop', entityName: 'Order', fields, ...extra });

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
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'address', type: 'com.shop.Address' }]),
      DEFAULT_CONFIG,
    ).content;
    expect(content).not.toContain('address:');
  });

  it('gives a collection typed as the processor writes it an empty array', () => {
    const content = generateSchemaSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'tags', type: 'java.util.List<java.lang.String>' }]),
      DEFAULT_CONFIG,
    ).content;
    expect(content).toContain('tags: []');
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

  // A required field the fixture cannot produce is NOT the same as an optional one. Omitting an
  // optional field is safe — the schema tolerates absence. Omitting a required one makes the
  // fixture incomplete by construction, and the round-trip assertion then ships red to the
  // consumer. Both cases below are content assertions on purpose: the `ng test` gate cannot see
  // this class, because a silently-dropped assertion still passes.
  const ORDER_STATUS = [{
    name: 'OrderStatus', qualifiedName: 'com.shop.OrderStatus', packageName: 'com.shop',
    values: [{ name: 'NEW', displayName: 'New', ordinal: 0 }, { name: 'PAID', displayName: 'Paid', ordinal: 1 }],
  }];

  it('gives a required enum field the first member of its own enum module', () => {
    const content = generateSchemaSpec(
      entity([
        { name: 'id', type: 'java.util.UUID' },
        { name: 'status', type: 'com.shop.OrderStatus', enumType: 'com.shop.OrderStatus', required: true },
      ]),
      DEFAULT_CONFIG,
      ORDER_STATUS,
    ).content;
    expect(content).toContain("status: 'NEW',");
    expect(content).toContain("it('accepts a well-formed Order'");
    expect(content).toContain('rejects a Order missing %s');
  });

  it('drops the round-trip assertion when a required field has no derivable literal', () => {
    const content = generateSchemaSpec(
      entity([
        { name: 'id', type: 'java.util.UUID' },
        { name: 'code', type: 'String', pattern: '^[A-Z]{3}$', required: true },
      ]),
      DEFAULT_CONFIG,
    ).content;
    expect(content).not.toContain("it('accepts a well-formed Order'");
    expect(content).toContain('No round-trip assertion: code is required');
  });

  // Removing a key the fixture never carried asserts nothing and would pass whatever the schema
  // said — so a required field missing from the fixture must not appear in the removal list.
  it('never asserts removal of a field the fixture does not carry', () => {
    const content = generateSchemaSpec(
      entity([
        { name: 'id', type: 'java.util.UUID' },
        { name: 'code', type: 'String', pattern: '^[A-Z]{3}$', required: true },
      ]),
      DEFAULT_CONFIG,
    ).content;
    expect(content).not.toContain('rejects a Order missing');
  });

  it('falls back when the enum module for a required enum is absent', () => {
    const content = generateSchemaSpec(
      entity([
        { name: 'id', type: 'java.util.UUID' },
        { name: 'status', type: 'com.shop.OrderStatus', enumType: 'com.shop.OrderStatus', required: true },
      ]),
      DEFAULT_CONFIG,
      [],
    ).content;
    expect(content).not.toContain("it('accepts a well-formed Order'");
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

  it('asserts PUT for update — the verb the generated router serves, not PATCH', () => {
    const content = generateServiceSpec(idOnly, DEFAULT_CONFIG).content;
    expect(content).toContain("it('updates with PUT on the instance'");
    expect(content).toContain("expect(request.request.method).toBe('PUT');");
    expect(content).not.toContain('PATCH');
  });

  it('asserts softDelete as DELETE on the instance, only for a soft-delete entity', () => {
    const soft = generateServiceSpec(entity([{ name: 'id', type: 'java.util.UUID' }], { softDelete: true }), DEFAULT_CONFIG).content;
    expect(soft).toContain("it('archives with DELETE on the instance'");
    expect(soft).toContain("service.softDelete('42').subscribe();");
    expect(generateServiceSpec(idOnly, DEFAULT_CONFIG).content).not.toContain('softDelete');
  });

  it('verifies no request was left outstanding', () => {
    expect(generateServiceSpec(idOnly, DEFAULT_CONFIG).content).toContain('afterEach(() => http.verify());');
  });
});

describe('generateStoreSpec', () => {
  it('emits beside the store it exercises', () => {
    expect(generateStoreSpec(idOnly, DEFAULT_CONFIG).path).toBe('stores/order.store.spec.ts');
  });

  it('drives the real store through Angular\'s testing backend, requiring no mocking library', () => {
    const content = generateStoreSpec(idOnly, DEFAULT_CONFIG).content;
    expect(content).toContain("import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';");
    expect(content).toContain('providers: [provideHttpClient(), provideHttpClientTesting()]');
    for (const mocking of ['jest', 'sinon', 'vi.mock', 'ts-mockito']) {
      expect(content).not.toContain(mocking);
    }
  });

  it('asserts the exact path the service builds, with no invented prefix', () => {
    const content = generateStoreSpec(idOnly, DEFAULT_CONFIG).content;
    expect(content).toContain("r.url === '/orders'");
    expect(content).toContain("'/orders/42'");
    expect(content).not.toContain('/api/orders');
  });

  it('follows a configured apiBasePath rather than hard-coding one', () => {
    const content = generateStoreSpec(idOnly, { ...DEFAULT_CONFIG, apiBasePath: '/gateway' }).content;
    expect(content).toContain("r.url === '/gateway/orders'");
    expect(content).toContain("'/gateway/orders/42'");
  });

  it('honours an entity-declared path', () => {
    const content = generateStoreSpec(entity([{ name: 'id', type: 'java.util.UUID' }], { path: '/custom' }), DEFAULT_CONFIG).content;
    expect(content).toContain("r.url === '/custom'");
    expect(content).toContain("'/custom/42'");
  });

  it('asserts state transitions for loadAll, loadById, create, update and delete', () => {
    const content = generateStoreSpec(idOnly, DEFAULT_CONFIG).content;
    expect(content).toContain('store.loadAll()');
    expect(content).toContain("store.loadById('42')");
    expect(content).toContain('store.create({} as never)');
    expect(content).toContain("store.update('42', {} as never)");
    expect(content).toContain("store.delete('42')");
    expect(content).toContain('expect(store.loading()).toBe(false)');
    expect(content).toContain('expect(store.saving()).toBe(false)');
  });

  it('asserts softDelete as archive only for a soft-delete entity', () => {
    const soft = generateStoreSpec(entity([{ name: 'id', type: 'java.util.UUID' }], { softDelete: true }), DEFAULT_CONFIG).content;
    expect(soft).toContain("store.archive('42')");
    expect(generateStoreSpec(idOnly, DEFAULT_CONFIG).content).not.toContain('archive(');
  });

  it('asserts error mapping for 409 conflict on update vs delete', () => {
    const content = generateStoreSpec(idOnly, DEFAULT_CONFIG).content;
    expect(content).toContain("expect(store.error()).toBe('The order was changed by someone else. Reload to see the latest version.');");
    expect(content).toContain("expect(store.error()).toBe('The order is still referenced by other records. Remove or change them first.');");
  });

  it('asserts error mapping for 422 unprocessable entity on create and update', () => {
    const content = generateStoreSpec(idOnly, DEFAULT_CONFIG).content;
    expect(content).toContain("expect(store.error()).toBe('The order refers to a related record that does not exist.');");
  });

  it('honours a custom displayName in error messages', () => {
    const custom = generateStoreSpec(entity([{ name: 'id', type: 'java.util.UUID' }], { displayName: 'Purchase Order' }), DEFAULT_CONFIG).content;
    expect(custom).toContain('The purchase order was changed by someone else.');
  });

  it('escapes a display name holding a quote in the expected messages', () => {
    const content = generateStoreSpec(entity([{ name: 'id', type: 'java.util.UUID' }], { displayName: "Customer's Order" }), DEFAULT_CONFIG).content;
    expect(content).toContain("toBe('The customer\\'s order was changed by someone else. Reload to see the latest version.');");
    expect(content).not.toContain("toBe('The customer's order");
  });

  it('verifies no request was left outstanding', () => {
    expect(generateStoreSpec(idOnly, DEFAULT_CONFIG).content).toContain('afterEach(() => http.verify());');
  });
});

describe('generateFormSpec', () => {
  it('emits beside the form component it exercises', () => {
    expect(generateFormSpec(idOnly, DEFAULT_CONFIG)!.path).toBe('components/order-form.component.spec.ts');
  });

  it('returns null when the entity has no form page', () => {
    const disabled = generateFormSpec(entity([{ name: 'id', type: 'java.util.UUID' }], { uiMetadata: { createForm: false, editForm: false } }), DEFAULT_CONFIG);
    expect(disabled).toBeNull();
  });

  it('drives the real form component through Angular TestBed, requiring no mocking library', () => {
    const content = generateFormSpec(idOnly, DEFAULT_CONFIG)!.content;
    expect(content).toContain("import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';");
    expect(content).toContain('TestBed.createComponent(OrderFormComponent)');
    for (const mocking of ['jest', 'sinon', 'vi.mock', 'ts-mockito']) {
      expect(content).not.toContain(mocking);
    }
  });

  it('does not include read-only or server-owned fields as editable controls', () => {
    const content = generateFormSpec(
      entity([
        { name: 'id', type: 'java.util.UUID' },
        { name: 'sku', type: 'String', readOnly: true },
        { name: 'total', type: 'String', computed: true },
      ]),
      DEFAULT_CONFIG,
    )!.content;
    expect(content).toContain("expect(component.form).not.toHaveProperty('id');");
    expect(content).toContain("expect(component.form).not.toHaveProperty('sku');");
    expect(content).toContain("expect(component.form).not.toHaveProperty('total');");
  });

  it('asserts regular fields are offered in create mode', () => {
    const content = generateFormSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'title', type: 'String' }]),
      DEFAULT_CONFIG,
    )!.content;
    expect(content).toContain("expect(component.form).toHaveProperty('title');");
    expect(content).toContain('expect(component.form.title().disabled()).toBe(false);');
  });

  it('disables create-omitted field in create mode and enables it in edit mode', () => {
    const content = generateFormSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'tracking', type: 'String', inCreate: false, inUpdate: true }]),
      DEFAULT_CONFIG,
    )!.content;
    expect(content).toContain("fixture.componentRef.setInput('mode', 'create');");
    expect(content).toContain('expect(component.form.tracking().disabled()).toBe(true);');
    expect(content).toContain("fixture.componentRef.setInput('mode', 'edit');");
    expect(content).toContain('expect(component.form.tracking().disabled()).toBe(false);');
  });

  it('enables update-omitted field in create mode and disables it in edit mode', () => {
    const content = generateFormSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'orderNo', type: 'String', inCreate: true, inUpdate: false }]),
      DEFAULT_CONFIG,
    )!.content;
    expect(content).toContain("fixture.componentRef.setInput('mode', 'create');");
    expect(content).toContain('expect(component.form.orderNo().disabled()).toBe(false);');
    expect(content).toContain("fixture.componentRef.setInput('mode', 'edit');");
    expect(content).toContain('expect(component.form.orderNo().disabled()).toBe(true);');
  });

  it('asserts required fields block submit', () => {
    const content = generateFormSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'title', type: 'String', required: true }]),
      DEFAULT_CONFIG,
    )!.content;
    expect(content).toContain('expect(component.form().invalid()).toBe(true);');
    expect(content).toContain('expect(component.form.title().invalid()).toBe(true);');
    expect(content).toContain("component.onSubmit(new Event('submit'));");
    expect(content).toContain('expect(component.saving()).toBe(false);');
    expect(content).toContain("http.expectNone('/orders');");
  });

  it('leaves a required field with a default out of the blocks-submit case', () => {
    const content = generateFormSpec(
      entity([
        { name: 'id', type: 'java.util.UUID' },
        { name: 'title', type: 'String', required: true },
        { name: 'status', type: 'String', required: true, defaultValue: 'NEW' },
        { name: 'quantity', type: 'Integer', required: true, defaultValue: '1' },
      ]),
      DEFAULT_CONFIG,
    )!.content;
    expect(content).toContain('expect(component.form.title().invalid()).toBe(true);');
    expect(content).not.toContain('component.form.status().invalid()');
    expect(content).not.toContain('component.form.quantity().invalid()');
  });

  it('asserts a valid initial state when every required field has a default', () => {
    const content = generateFormSpec(
      entity([{ name: 'id', type: 'java.util.UUID' }, { name: 'status', type: 'String', required: true, defaultValue: 'NEW' }]),
      DEFAULT_CONFIG,
    )!.content;
    expect(content).not.toContain("it('blocks submit when required fields are missing'");
    expect(content).toContain('expect(component.form().valid()).toBe(true);');
  });

  it('asserts valid initial state when no required fields are declared', () => {
    const content = generateFormSpec(idOnly, DEFAULT_CONFIG)!.content;
    expect(content).toContain('expect(component.form().valid()).toBe(true);');
  });

  it('verifies no request was left outstanding', () => {
    expect(generateFormSpec(idOnly, DEFAULT_CONFIG)!.content).toContain('afterEach(() => http.verify());');
  });
});
