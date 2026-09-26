/**
 * PATCH/PUT parity — the TypeScript half of a cross-emitter check.
 *
 * The generated service sent PATCH for update while the generated kernel router served PUT, and the
 * kernel router matches methods exactly, so no generated front end could update anything. Its
 * `archive`/`restore` PATCHed paths nothing serves at all. Each emitter had tests; each test
 * asserted what its own emitter wrote — the emitted service spec pinned PATCH — and nothing held
 * one artefact's verb against another's.
 *
 * The router, the OpenAPI document and the Java client are built by Maven, this package by npm,
 * and the two never run together. So the route table lives in one committed file that both sides
 * test against: `CrudRouteParityE2ETest` (exeris-e2e-tests) proves the generated router serves
 * exactly `contract/crud-routes.json`, and this spec proves the generated service calls nothing
 * outside it — and that the emitted service spec asserts the same verbs.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { ServiceGenerator } from '../../../src/generators/angular/service-gen.js';
import { generateServiceSpec } from '../../../src/generators/angular/spec-gen.js';
import { createGeneratorContext } from '../../../src/core/generator-registry.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { DomainMetadataSchema } from '../../../src/models/domain-model.js';

interface Route {
  operation: string;
  method: string;
  path: string;
}

// Repo root is four levels up from this file (test/generators/angular/). A missing file must fail
// the suite, not skip it — a parity check that cannot find its contract proves nothing.
const CONTRACT_URL = new URL(
  '../../../../exeris-e2e-tests/src/test/resources/contract/crud-routes.json',
  import.meta.url,
);
const contract = JSON.parse(readFileSync(fileURLToPath(CONTRACT_URL), 'utf8')) as { routes: Route[] };

const endpointsFor = (operation: string): string[] =>
  contract.routes.filter(r => r.operation === operation).map(r => `${r.method} ${r.path}`);

// softDelete + actions, so every call the service can emit is on the table.
const domain = DomainMetadataSchema.parse({
  packageName: 'com.shop',
  entityName: 'Order',
  path: '/orders',
  softDelete: true,
  actions: [
    { name: 'cancel', params: [] },
    { name: 'markUrgent', params: [{ name: 'reason', type: 'String', required: true }] },
  ],
});

const SERVICE_OPERATION: Record<string, string> = {
  findAll: 'list',
  findById: 'get',
  create: 'create',
  update: 'update',
  delete: 'delete',
  // On a @SoftDelete entity the server's DELETE is the archive; there is no other route for it.
  softDelete: 'delete',
  cancel: 'action',
  markUrgent: 'action',
};

/** `this.baseUrl` / `${this.baseUrl}/${id}` / `.../actions/<kebab>` → the contract's templates. */
function template(urlExpression: string): string {
  if (urlExpression === 'this.baseUrl') {
    return '{base}';
  }
  return urlExpression
    .replace(/^`\$\{this\.baseUrl\}/, '{base}')
    .replace(/\$\{id\}/, '{id}')
    .replace(/\/actions\/[a-z0-9-]+`$/, '/actions/{action}')
    .replace(/`$/, '');
}

/** Every `name(...): Observable<…> {` method of the emitted service and the HTTP call it makes. */
function serviceCalls(content: string): Array<{ method: string; endpoint: string }> {
  const calls: Array<{ method: string; endpoint: string }> = [];
  const methodRe = /^ {2}(\w+)\([^)]*\): Observable<.*> \{\n([\s\S]*?)\n {2}\}$/gm;
  for (const m of content.matchAll(methodRe)) {
    const call = /this\.http\.(\w+)<.*?>\((this\.baseUrl|`[^`]*`)/.exec(m[2]);
    expect(call, `HTTP call in ${m[1]}`).not.toBeNull();
    calls.push({ method: m[1], endpoint: `${call![1].toUpperCase()} ${template(call![2])}` });
  }
  return calls;
}

describe('CRUD route parity — generated TypeScript service vs the generated router', () => {
  const service = new ServiceGenerator().generate(domain, createGeneratorContext({}))!.content;
  const calls = serviceCalls(service);

  it('finds every service method that issues a request', () => {
    expect(calls.map(c => c.method)).toEqual(Object.keys(SERVICE_OPERATION));
  });

  it.each(Object.entries(SERVICE_OPERATION))('%s calls a route the router serves for %s', (method, operation) => {
    const call = calls.find(c => c.method === method);
    expect(call, method).toBeDefined();
    expect(endpointsFor(operation)).toContain(call!.endpoint);
  });

  it('update is PUT on the instance — the verb the router serves, not PATCH', () => {
    expect(calls.find(c => c.method === 'update')!.endpoint).toBe('PUT {base}/{id}');
    expect(service).not.toContain('this.http.patch');
  });

  it('emits no restore(): nothing on the server un-sets the soft-delete flag', () => {
    expect(service).not.toMatch(/\brestore\(/);
  });
});

describe('CRUD route parity — the emitted service spec asserts the router\'s verbs', () => {
  const spec = generateServiceSpec(domain, DEFAULT_CONFIG).content;

  it.each([
    ['create', 'create'],
    ['update', 'update'],
    ['delete', 'delete'],
    ['softDelete', 'delete'],
  ])('the %s case expects the contract verb for %s', (method, operation) => {
    const re = new RegExp(
      `service\\.${method}\\([^\\n]*\\n[^\\n]*\\n\\s*expect\\(request\\.request\\.method\\)\\.toBe\\('(\\w+)'\\)`,
    );
    const asserted = re.exec(spec);
    expect(asserted, `${method} case in the emitted spec`).not.toBeNull();
    const verbs = contract.routes.filter(r => r.operation === operation).map(r => r.method);
    expect(verbs).toContain(asserted![1]);
  });
});
