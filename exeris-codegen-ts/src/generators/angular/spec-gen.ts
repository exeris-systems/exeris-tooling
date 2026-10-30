/**
 * Generated specs for the generated surface — the FE half of T2 (ADR-058).
 *
 * **Two rules carry over from the Java half and shape everything here.**
 *
 * 1. **Doubles, never mocks.** The service spec drives the real service through Angular's own
 * `provideHttpClientTesting()`, which lives in `@angular/common/http/testing` — part of a package
 * the emitted app already depends on. No mocking library is required, so emitting these specs adds
 * no dependency beyond the runner itself. On the Java side the same rule produced an emitted
 * `HttpExchange` recorder rather than a Mockito stub.
 *
 * 2. **The gate runs them.** A spec that is only type-checked proves nothing; `ng test` on the
 * generated sample is the gate, exactly as `GeneratedTestsE2ETest` executes the Java half.
 *
 * **Assertions are derived from metadata, never assumed.** The first hand-written probe for
 * this slice asserted that an empty object fails the entity schema — and it passed the schema,
 * because that entity declares no required field. A spec emitter that guesses at shape emits
 * failing tests into a consumer's project, so every assertion below is conditioned on what the
 * metadata actually says.
 */

import { outPath } from '../../core/paths.js';
import { primaryKeyField } from '../../core/primary-key.js';
import type { DomainMetadata, FieldMetadata } from '../../models/domain-model.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import { modelTypeName } from '../../models/model-naming.js';
import type { GeneratorConfig } from '../../config.js';
import type { EnumMetadataForGen } from '../api/enum-module-gen.js';
import { serviceApiPath } from './service-gen.js';
import { fileHeaderLines } from '../file-header.js';
import { tsSingleQuoted } from './ts-literal.js';
import { fieldRenderContext, resolveFieldRenders } from './field-render.js';
import { entityViews, hasFormPage } from './entity-views.js';

/** An emitted file, in the (path, content) shape the orchestrator composes. */
export interface SpecOutputFile {
  path: string;
  content: string;
}

const HEADER = (what: string): string[] => [...fileHeaderLines({ title: what }), ''];

/**
 * A literal the field's **zod** type accepts.
 *
 * Keyed off `zodType`, not `tsType`: they disagree, and the schema is the thing being asserted
 * against. A `BigDecimal` is `string` in TypeScript but `z.string().regex(/^-?\d+(\.\d+)?$/)` in
 * the schema, so a plain `'x'` type-checks and then fails to parse — which is exactly how the first
 * run of this emitter produced a failing spec in the sample app.
 *
 * Returns `null` when no literal can be produced generically (enums, blobs, patterned strings the
 * mapper does not own). Those fields are left out of the fixture rather than guessed at; every
 * field the schema marks optional tolerates absence, and inventing a value that fails is worse
 * than omitting one.
 */
function sampleLiteral(field: FieldMetadata, enums: EnumMetadataForGen[]): string | null {
  if (field.enumType) {
    // The enum module emits a const object keyed by member name, so the member name IS the value.
    const simple = field.enumType.includes('.')
      ? field.enumType.slice(field.enumType.lastIndexOf('.') + 1)
      : field.enumType;
    const first = enums.find((e) => e.name === simple)?.values[0]?.name;
    return first ? `'${first}'` : null;
  }
  if (field.pattern) return null;
  if (field.format === 'email') return "'sample@example.com'";
  if (field.format === 'url') return "'https://example.com'";

  const zod = DslMapper.mapType(field.type).zodType;
  if (zod.startsWith('z.boolean')) return 'true';
  if (zod.startsWith('z.number')) return '1';
  if (zod.startsWith('z.array')) return '[]';
  if (zod.startsWith('z.record')) return '{}';
  if (zod.startsWith('z.string().uuid')) return "'00000000-0000-0000-0000-000000000000'";
  if (zod.startsWith('z.string().datetime')) return "'2026-01-01T00:00:00Z'";
  if (zod.startsWith('z.string().date')) return "'2026-01-01'";
  if (zod.startsWith('z.string().time')) return "'00:00:00'";
  // The two numeric-string mappings (BigInteger / BigDecimal) — a digit satisfies both regexes.
  if (zod.startsWith('z.string().regex')) return "'1'";
  if (zod.startsWith('z.string')) {
    const min = field.minLength ?? 1;
    const max = field.maxLength ?? Math.max(min, 1);
    return `'${'x'.repeat(Math.min(Math.max(min, 1), Math.max(max, 1)))}'`;
  }
  return null;
}

/**
 * The zod-schema spec. Only emitted under `generateZod`, since the schemas it exercises are.
 *
 * Every assertion is metadata-conditioned: the required-field case is emitted only for a field the
 * entity actually marks required, and the create-schema case only when a system field is present
 * to be omitted. An entity that supports neither gets the round-trip assertion alone rather than
 * an invented one.
 */
export function generateSchemaSpec(
  metadata: DomainMetadata,
  _config: GeneratorConfig,
  enums: EnumMetadataForGen[] = [],
): SpecOutputFile {
  const name = modelTypeName(metadata.entityName);
  const kebab = DslMapper.toKebabCase(metadata.entityName);
  const required = metadata.fields.filter((f) => f.required);

  const lines = HEADER(`${metadata.entityName} schema spec`);
  lines.push("import { describe, expect, it } from 'vitest';");
  lines.push(`import { ${name}Schema, ${name}CreateSchema } from './${kebab}.schema';`);
  lines.push('');
  lines.push(`describe('${name}Schema', () => {`);

  const fixture = metadata.fields
    .map((f) => ({ f, literal: sampleLiteral(f, enums) }))
    .filter((e): e is { f: FieldMetadata; literal: string } => e.literal !== null);
  const covered = new Set(fixture.map((e) => e.f.name));

  // Omitting a field the fixture cannot produce is only safe while that field is OPTIONAL. A
  // REQUIRED field with no derivable literal (a pattern the entity author owns, an enum whose
  // module this app does not emit) makes the fixture incomplete by construction, and asserting it
  // parses would ship a red `ng test` to the consumer. The assertion is dropped instead, because
  // the same rule that forbids inventing a value forbids asserting one was found.
  const unsatisfiable = required.filter((f) => !covered.has(f.name));

  lines.push(`  const valid = {`);
  for (const { f, literal } of fixture) lines.push(`    ${f.name}: ${literal},`);
  lines.push(`  };`);
  lines.push('');
  if (unsatisfiable.length === 0) {
    lines.push(`  it('accepts a well-formed ${metadata.entityName}', () => {`);
    lines.push(`    expect(${name}Schema.safeParse(valid).success).toBe(true);`);
    lines.push(`  });`);
  } else {
    lines.push(`  // No round-trip assertion: ${unsatisfiable.map((f) => f.name).join(', ')} ${unsatisfiable.length === 1 ? 'is' : 'are'} required`);
    lines.push(`  // and no literal can be derived for ${unsatisfiable.length === 1 ? 'it' : 'them'}, so any fixture here would be a guess.`);
  }

  // Only fields the fixture actually carries: removing a key that was never present asserts
  // nothing, and would pass whatever the schema said.
  const removable = required.filter((f) => covered.has(f.name));
  if (removable.length > 0) {
    lines.push('');
    lines.push(`  it.each(${JSON.stringify(removable.map((f) => f.name))})('rejects a ${metadata.entityName} missing %s', (field) => {`);
    lines.push(`    const { [field]: _omitted, ...rest } = valid as Record<string, unknown>;`);
    lines.push(`    expect(${name}Schema.safeParse(rest).success).toBe(false);`);
    lines.push(`  });`);
  }

  lines.push('');
  lines.push(`  it('drops server-owned fields from the create schema', () => {`);
  lines.push(`    expect(Object.keys(${name}CreateSchema.shape)).not.toContain('${primaryKeyField(metadata)}');`);
  lines.push(`  });`);
  lines.push(`});`);
  lines.push('');

  return { path: outPath('schemas', `${kebab}.schema.spec.ts`), content: lines.join('\n') };
}

/**
 * The service spec: the real service, Angular's testing HTTP backend, and assertions on the URL
 * and verb each method issues.
 *
 * This is the regression test for the defect class that shipped twice — an emitted client
 * calling a path the emitted server does not serve. It asserts the exact `baseUrl` the generator
 * computed, so a prefix reintroduced by a default nobody typed fails here rather than at runtime.
 */
export function generateServiceSpec(metadata: DomainMetadata, config: GeneratorConfig): SpecOutputFile {
  const entity = metadata.entityName;
  const kebab = DslMapper.toKebabCase(entity);
  // Same authority the service itself uses — a re-derived path would agree only until
  // one of the two changed, which is the drift ADR-058 forbids for emitted doubles.
  const baseUrl = `${config.apiBasePath}${serviceApiPath(metadata)}`;

  const lines = HEADER(`${entity} service spec`);
  lines.push("import { TestBed } from '@angular/core/testing';");
  lines.push("import { provideHttpClient } from '@angular/common/http';");
  lines.push("import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';");
  lines.push("import { afterEach, beforeEach, describe, expect, it } from 'vitest';");
  lines.push(`import { ${entity}Service } from './${kebab}.service';`);
  lines.push('');
  lines.push(`describe('${entity}Service', () => {`);
  lines.push(`  let service: ${entity}Service;`);
  lines.push(`  let http: HttpTestingController;`);
  lines.push('');
  lines.push(`  beforeEach(() => {`);
  lines.push(`    TestBed.configureTestingModule({`);
  lines.push(`      providers: [provideHttpClient(), provideHttpClientTesting()],`);
  lines.push(`    });`);
  lines.push(`    service = TestBed.inject(${entity}Service);`);
  lines.push(`    http = TestBed.inject(HttpTestingController);`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  afterEach(() => http.verify());`);
  lines.push('');

  // The URL the emitted server actually serves. A prefix the client invents fails here.
  lines.push(`  it('requests the path the generated server serves', () => {`);
  lines.push(`    service.findById('42').subscribe();`);
  lines.push(`    http.expectOne('${baseUrl}/42').flush({});`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  it('creates with POST on the collection', () => {`);
  lines.push(`    service.create({} as never).subscribe();`);
  lines.push(`    const request = http.expectOne('${baseUrl}');`);
  lines.push(`    expect(request.request.method).toBe('POST');`);
  lines.push(`    request.flush({});`);
  lines.push(`  });`);
  lines.push('');
  // PUT: the verb the generated router serves update on.
  lines.push(`  it('updates with PUT on the instance', () => {`);
  lines.push(`    service.update('42', {} as never).subscribe();`);
  lines.push(`    const request = http.expectOne('${baseUrl}/42');`);
  lines.push(`    expect(request.request.method).toBe('PUT');`);
  lines.push(`    request.flush({});`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  it('deletes with DELETE on the instance', () => {`);
  lines.push(`    service.delete('42').subscribe();`);
  lines.push(`    const request = http.expectOne('${baseUrl}/42');`);
  lines.push(`    expect(request.request.method).toBe('DELETE');`);
  lines.push(`    request.flush(null);`);
  lines.push(`  });`);
  if (metadata.softDelete) {
    lines.push('');
    lines.push(`  it('archives with DELETE on the instance', () => {`);
    lines.push(`    service.softDelete('42').subscribe();`);
    lines.push(`    const request = http.expectOne('${baseUrl}/42');`);
    lines.push(`    expect(request.request.method).toBe('DELETE');`);
    lines.push(`    request.flush(null);`);
    lines.push(`  });`);
  }
  lines.push(`});`);
  lines.push('');

  return { path: outPath('services', `${kebab}.service.spec.ts`), content: lines.join('\n') };
}

/**
 * The store spec: state transitions for load/create/update/delete success, and HTTP error mappings.
 *
 * Drives the real Signal store through Angular's HttpTestingController.
 */
export function generateStoreSpec(metadata: DomainMetadata, config: GeneratorConfig): SpecOutputFile {
  const entity = metadata.entityName;
  const kebab = DslMapper.toKebabCase(entity);
  const idField = primaryKeyField(metadata);
  const baseUrl = `${config.apiBasePath}${serviceApiPath(metadata)}`;
  const noun = (metadata.displayName ?? entity).toLowerCase();
  const subject = `the ${noun}`;
  const Subject = subject.charAt(0).toUpperCase() + subject.slice(1);

  const lines = HEADER(`${entity} store spec`);
  lines.push("import { TestBed } from '@angular/core/testing';");
  lines.push("import { provideHttpClient } from '@angular/common/http';");
  lines.push("import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';");
  lines.push("import { afterEach, beforeEach, describe, expect, it } from 'vitest';");
  lines.push(`import { ${entity}Store } from './${kebab}.store';`);
  lines.push('');
  lines.push(`describe('${entity}Store', () => {`);
  lines.push(`  let store: ${entity}Store;`);
  lines.push(`  let http: HttpTestingController;`);
  lines.push('');
  lines.push(`  beforeEach(() => {`);
  lines.push(`    TestBed.configureTestingModule({`);
  lines.push(`      providers: [provideHttpClient(), provideHttpClientTesting()],`);
  lines.push(`    });`);
  lines.push(`    store = TestBed.inject(${entity}Store);`);
  lines.push(`    http = TestBed.inject(HttpTestingController);`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  afterEach(() => http.verify());`);
  lines.push('');
  lines.push(`  it('loads all entities and updates loading state', async () => {`);
  lines.push(`    expect(store.loading()).toBe(false);`);
  lines.push(`    const loadPromise = store.loadAll();`);
  lines.push(`    expect(store.loading()).toBe(true);`);
  lines.push(`    const req = http.expectOne((r) => r.url === '${baseUrl}');`);
  lines.push(`    expect(req.request.method).toBe('GET');`);
  lines.push(`    req.flush({ content: [{ ${idField}: '42' }], totalElements: 1, totalPages: 1 });`);
  lines.push(`    await loadPromise;`);
  lines.push(`    expect(store.loading()).toBe(false);`);
  lines.push(`    expect(store.entities().length).toBe(1);`);
  lines.push(`    expect(store.totalElements()).toBe(1);`);
  lines.push(`    expect(store.error()).toBeNull();`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  it('loads a single entity by id and updates selection', async () => {`);
  lines.push(`    expect(store.loading()).toBe(false);`);
  lines.push(`    const loadPromise = store.loadById('42');`);
  lines.push(`    expect(store.loading()).toBe(true);`);
  lines.push(`    const req = http.expectOne('${baseUrl}/42');`);
  lines.push(`    expect(req.request.method).toBe('GET');`);
  lines.push(`    req.flush({ ${idField}: '42' });`);
  lines.push(`    const entity = await loadPromise;`);
  lines.push(`    expect(store.loading()).toBe(false);`);
  lines.push(`    expect(store.selected()).toEqual({ ${idField}: '42' });`);
  lines.push(`    expect(entity).toEqual({ ${idField}: '42' });`);
  lines.push(`    expect(store.error()).toBeNull();`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  it('creates an entity and prepends it to entities', async () => {`);
  lines.push(`    expect(store.saving()).toBe(false);`);
  lines.push(`    const createPromise = store.create({} as never);`);
  lines.push(`    expect(store.saving()).toBe(true);`);
  lines.push(`    const req = http.expectOne('${baseUrl}');`);
  lines.push(`    expect(req.request.method).toBe('POST');`);
  lines.push(`    req.flush({ ${idField}: '42' });`);
  lines.push(`    const created = await createPromise;`);
  lines.push(`    expect(store.saving()).toBe(false);`);
  lines.push(`    expect(store.entities()).toContainEqual({ ${idField}: '42' });`);
  lines.push(`    expect(created).toEqual({ ${idField}: '42' });`);
  lines.push(`    expect(store.error()).toBeNull();`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  it('updates a loaded entity and replaces it with the server response', async () => {`);
  lines.push(`    const loadPromise = store.loadAll();`);
  lines.push(`    http.expectOne((r) => r.url === '${baseUrl}').flush({ content: [{ ${idField}: '42', revision: 1 }], totalElements: 1, totalPages: 1 });`);
  lines.push(`    await loadPromise;`);
  lines.push(`    expect(store.saving()).toBe(false);`);
  lines.push(`    const updatePromise = store.update('42', {} as never);`);
  lines.push(`    expect(store.saving()).toBe(true);`);
  lines.push(`    const req = http.expectOne('${baseUrl}/42');`);
  lines.push(`    expect(req.request.method).toBe('PUT');`);
  lines.push(`    req.flush({ ${idField}: '42', revision: 2 });`);
  lines.push(`    const updated = await updatePromise;`);
  lines.push(`    expect(store.saving()).toBe(false);`);
  lines.push(`    expect(store.entities()).toEqual([{ ${idField}: '42', revision: 2 }]);`);
  lines.push(`    expect(updated).toEqual({ ${idField}: '42', revision: 2 });`);
  lines.push(`    expect(store.error()).toBeNull();`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  it('deletes an entity and removes it from entities', async () => {`);
  lines.push(`    expect(store.saving()).toBe(false);`);
  lines.push(`    const deletePromise = store.delete('42');`);
  lines.push(`    expect(store.saving()).toBe(true);`);
  lines.push(`    const req = http.expectOne('${baseUrl}/42');`);
  lines.push(`    expect(req.request.method).toBe('DELETE');`);
  lines.push(`    req.flush(null);`);
  lines.push(`    await deletePromise;`);
  lines.push(`    expect(store.saving()).toBe(false);`);
  lines.push(`    expect(store.entities().some((e) => e.${idField} === '42')).toBe(false);`);
  lines.push(`    expect(store.error()).toBeNull();`);
  lines.push(`  });`);
  if (metadata.softDelete) {
    lines.push('');
    lines.push(`  it('archives an entity with DELETE and removes it from entities', async () => {`);
    lines.push(`    expect(store.saving()).toBe(false);`);
    lines.push(`    const archivePromise = store.archive('42');`);
    lines.push(`    expect(store.saving()).toBe(true);`);
    lines.push(`    const req = http.expectOne('${baseUrl}/42');`);
    lines.push(`    expect(req.request.method).toBe('DELETE');`);
    lines.push(`    req.flush(null);`);
    lines.push(`    await archivePromise;`);
    lines.push(`    expect(store.saving()).toBe(false);`);
    lines.push(`    expect(store.entities().some((e) => e.${idField} === '42')).toBe(false);`);
    lines.push(`    expect(store.error()).toBeNull();`);
    lines.push(`  });`);
  }
  lines.push('');
  lines.push(`  it('maps a 409 conflict on update to the stale-version message', async () => {`);
  lines.push(`    let caught: unknown;`);
  lines.push(`    try {`);
  lines.push(`      const promise = store.update('42', {} as never);`);
  lines.push(`      const req = http.expectOne('${baseUrl}/42');`);
  lines.push(`      req.flush('conflict', { status: 409, statusText: 'Conflict' });`);
  lines.push(`      await promise;`);
  lines.push(`    } catch (err) {`);
  lines.push(`      caught = err;`);
  lines.push(`    }`);
  lines.push(`    expect(caught).toBeDefined();`);
  lines.push(`    expect(store.saving()).toBe(false);`);
  lines.push(`    expect(store.error()).toBe('${tsSingleQuoted(`${Subject} was changed by someone else. Reload to see the latest version.`)}');`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  it('maps a 409 conflict on delete to the foreign-key reference message', async () => {`);
  lines.push(`    let caught: unknown;`);
  lines.push(`    try {`);
  lines.push(`      const promise = store.delete('42');`);
  lines.push(`      const req = http.expectOne('${baseUrl}/42');`);
  lines.push(`      req.flush('conflict', { status: 409, statusText: 'Conflict' });`);
  lines.push(`      await promise;`);
  lines.push(`    } catch (err) {`);
  lines.push(`      caught = err;`);
  lines.push(`    }`);
  lines.push(`    expect(caught).toBeDefined();`);
  lines.push(`    expect(store.saving()).toBe(false);`);
  lines.push(`    expect(store.error()).toBe('${tsSingleQuoted(`${Subject} is still referenced by other records. Remove or change them first.`)}');`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  it('maps a 422 unprocessable entity on create to the related-record message', async () => {`);
  lines.push(`    let caught: unknown;`);
  lines.push(`    try {`);
  lines.push(`      const promise = store.create({} as never);`);
  lines.push(`      const req = http.expectOne('${baseUrl}');`);
  lines.push(`      req.flush('unprocessable', { status: 422, statusText: 'Unprocessable Entity' });`);
  lines.push(`      await promise;`);
  lines.push(`    } catch (err) {`);
  lines.push(`      caught = err;`);
  lines.push(`    }`);
  lines.push(`    expect(caught).toBeDefined();`);
  lines.push(`    expect(store.saving()).toBe(false);`);
  lines.push(`    expect(store.error()).toBe('${tsSingleQuoted(`${Subject} refers to a related record that does not exist.`)}');`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  it('maps a 422 unprocessable entity on update to the related-record message', async () => {`);
  lines.push(`    let caught: unknown;`);
  lines.push(`    try {`);
  lines.push(`      const promise = store.update('42', {} as never);`);
  lines.push(`      const req = http.expectOne('${baseUrl}/42');`);
  lines.push(`      req.flush('unprocessable', { status: 422, statusText: 'Unprocessable Entity' });`);
  lines.push(`      await promise;`);
  lines.push(`    } catch (err) {`);
  lines.push(`      caught = err;`);
  lines.push(`    }`);
  lines.push(`    expect(caught).toBeDefined();`);
  lines.push(`    expect(store.saving()).toBe(false);`);
  lines.push(`    expect(store.error()).toBe('${tsSingleQuoted(`${Subject} refers to a related record that does not exist.`)}');`);
  lines.push(`  });`);
  lines.push(`});`);
  lines.push('');

  return { path: outPath('stores', `${kebab}.store.spec.ts`), content: lines.join('\n') };
}

/**
 * The form spec: Signal Forms control inclusion on create vs edit (inCreate / inUpdate),
 * exclusion of server-owned and read-only fields, and required field validation blocking submit.
 */
export function generateFormSpec(
  metadata: DomainMetadata,
  config: GeneratorConfig,
  enums: EnumMetadataForGen[] = [],
  allDomains: DomainMetadata[] = [metadata],
): SpecOutputFile | null {
  if (!hasFormPage(entityViews(metadata))) {
    return null;
  }

  const entity = metadata.entityName;
  const kebab = DslMapper.toKebabCase(entity);
  const idField = primaryKeyField(metadata);
  const baseUrl = `${config.apiBasePath}${serviceApiPath(metadata)}`;

  const renders = resolveFieldRenders(
    metadata,
    fieldRenderContext(metadata, allDomains, false, enums, config.generateServices !== false),
  );
  const createFields = renders.filter((r) => r.form.placement === 'control');
  const createOmitted = createFields.filter((r) => !r.form.inCreate);
  const updateOmitted = createFields.filter((r) => !r.form.inUpdate);
  const bothModes = createFields.filter((r) => r.form.inCreate && r.form.inUpdate);
  const nonControlFields = metadata.fields.filter(
    (f) => f.readOnly === true || f.computed === true || f.name === idField || !createFields.some((c) => c.name === f.name),
  );
  const requiredCreateFields = createFields.filter(
    (r) => r.field.required && r.form.value !== 'boolean' && r.form.inCreate,
  );

  const lines = HEADER(`${entity} form spec`);
  lines.push("import { ComponentFixture, TestBed } from '@angular/core/testing';");
  lines.push("import { provideHttpClient } from '@angular/common/http';");
  lines.push("import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';");
  lines.push("import { ActivatedRoute, Router } from '@angular/router';");
  lines.push("import { afterEach, beforeEach, describe, expect, it } from 'vitest';");
  lines.push(`import { ${entity}FormComponent } from './${kebab}-form.component';`);
  lines.push('');
  lines.push(`describe('${entity}FormComponent', () => {`);
  lines.push(`  let fixture: ComponentFixture<${entity}FormComponent>;`);
  lines.push(`  let component: ${entity}FormComponent;`);
  lines.push(`  let http: HttpTestingController;`);
  lines.push('');
  lines.push(`  beforeEach(() => {`);
  lines.push(`    TestBed.configureTestingModule({`);
  lines.push(`      providers: [`);
  lines.push(`        provideHttpClient(),`);
  lines.push(`        provideHttpClientTesting(),`);
  lines.push(`        { provide: ActivatedRoute, useValue: {} },`);
  lines.push(`        { provide: Router, useValue: { navigate: () => {} } },`);
  lines.push(`      ],`);
  lines.push(`    });`);
  lines.push(`    fixture = TestBed.createComponent(${entity}FormComponent);`);
  lines.push(`    component = fixture.componentInstance;`);
  lines.push(`    http = TestBed.inject(HttpTestingController);`);
  lines.push(`  });`);
  lines.push('');
  lines.push(`  afterEach(() => http.verify());`);

  if (bothModes.length > 0) {
    lines.push('');
    lines.push(`  it('offers editable controls in create mode', () => {`);
    lines.push(`    fixture.componentRef.setInput('mode', 'create');`);
    for (const f of bothModes) {
      lines.push(`    expect(component.form).toHaveProperty('${f.name}');`);
      lines.push(`    expect(component.form.${f.name}().disabled()).toBe(false);`);
    }
    lines.push(`  });`);
  }

  for (const f of createOmitted) {
    lines.push('');
    lines.push(`  it('disables create-omitted field ${f.name} in create mode and enables it in edit mode', () => {`);
    lines.push(`    fixture.componentRef.setInput('mode', 'create');`);
    lines.push(`    expect(component.form.${f.name}().disabled()).toBe(true);`);
    lines.push(`    fixture.componentRef.setInput('mode', 'edit');`);
    lines.push(`    expect(component.form.${f.name}().disabled()).toBe(false);`);
    lines.push(`  });`);
  }

  for (const f of updateOmitted) {
    lines.push('');
    lines.push(`  it('enables update-omitted field ${f.name} in create mode and disables it in edit mode', () => {`);
    lines.push(`    fixture.componentRef.setInput('mode', 'create');`);
    lines.push(`    expect(component.form.${f.name}().disabled()).toBe(false);`);
    lines.push(`    fixture.componentRef.setInput('mode', 'edit');`);
    lines.push(`    expect(component.form.${f.name}().disabled()).toBe(true);`);
    lines.push(`  });`);
  }

  lines.push('');
  lines.push(`  it('does not offer server-owned or read-only fields as editable controls', () => {`);
  const seenNonControl = new Set<string>();
  seenNonControl.add(idField);
  lines.push(`    expect(component.form).not.toHaveProperty('${idField}');`);
  for (const f of nonControlFields) {
    if (!seenNonControl.has(f.name)) {
      seenNonControl.add(f.name);
      lines.push(`    expect(component.form).not.toHaveProperty('${f.name}');`);
    }
  }
  lines.push(`  });`);

  lines.push('');
  if (requiredCreateFields.length > 0) {
    lines.push(`  it('blocks submit when required fields are missing', () => {`);
    lines.push(`    expect(component.form().invalid()).toBe(true);`);
    for (const f of requiredCreateFields) {
      lines.push(`    expect(component.form.${f.name}().invalid()).toBe(true);`);
    }
    lines.push(`    component.onSubmit(new Event('submit'));`);
    lines.push(`    expect(component.saving()).toBe(false);`);
    lines.push(`    http.expectNone('${baseUrl}');`);
    lines.push(`  });`);
  } else {
    lines.push(`  it('initializes form with valid state when no required fields exist', () => {`);
    lines.push(`    expect(component.form().valid()).toBe(true);`);
    lines.push(`  });`);
  }

  lines.push(`});`);
  lines.push('');

  return { path: outPath('components', `${kebab}-form.component.spec.ts`), content: lines.join('\n') };
}
