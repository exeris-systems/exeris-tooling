/**
 * Stream route parity — the TypeScript half of a cross-emitter check.
 *
 * The generated kernel application registers its SSE routes through `streamRoute(...)`; a
 * stream client that opens any other method or path reaches nothing. The application is built by
 * Maven and this package by npm, so the route table lives in one committed file both sides test
 * against: `StreamRouteParityE2ETest` (exeris-e2e-tests) proves the application registers exactly
 * `contract/stream-routes.json`, and this spec proves the generated stream clients open exactly
 * those routes.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { DomainMetadataSchema } from '../../../src/models/domain-model.js';

interface Route {
  operation: string;
  method: string;
  path: string;
}

// A missing file must fail the suite, not skip it — a parity check that cannot find its contract
// proves nothing.
const CONTRACT_URL = new URL(
  '../../../../exeris-e2e-tests/src/test/resources/contract/stream-routes.json',
  import.meta.url,
);
const contract = JSON.parse(readFileSync(fileURLToPath(CONTRACT_URL), 'utf8')) as { routes: Route[] };

// The entity StreamRouteParityE2ETest generates its application from. GLOBAL, as the default:
// a tenant-partitioned entity gets no stream client, because its stream routes carry no tenant guard.
const order = DomainMetadataSchema.parse({
  packageName: 'eu.exeris.e2e.parity.domain',
  entityName: 'Order',
  path: '/orders',
  realTimeApi: true,
  actions: [{ name: 'cancel' }, { name: 'trackShipment', streaming: true }],
});

const files = buildGeneratedFiles([order], [], DEFAULT_CONFIG);
const at = (p: string): string => {
  const file = files.find((f) => f.path === p);
  expect(file, `${p} is emitted`).toBeDefined();
  return file!.content;
};

function template(path: string): string {
  expect(path.startsWith('/orders')).toBe(true);
  return `{base}${path.slice('/orders'.length)}`
    .replace('/${id}/', '/{id}/')
    .replace(/\/actions\/[a-z0-9-]+$/, '/actions/{action}');
}

/** Every route the emitted stream clients open, as `METHOD template`. */
function opened(): string[] {
  const routes: string[] = [];

  // The live-view client is a native EventSource, which only ever sends GET.
  const live = at('src/app/services/order.stream.ts');
  expect(live).toContain('new EventSource(this.streamUrl');
  for (const m of live.matchAll(/private readonly streamUrl = '([^']+)';/g)) {
    routes.push(`GET ${template(m[1])}`);
  }

  // Each action stream client opens its route with fetch; the method is the one fetch states.
  const actions = at('src/app/services/order.action-streams.ts');
  for (const m of actions.matchAll(/const url = `([^`]+)`;\n[\s\S]*?method: '(\w+)'/g)) {
    routes.push(`${m[2]} ${template(m[1])}`);
  }
  return routes;
}

describe('stream route parity with the generated kernel application', () => {
  it('the generated stream clients open exactly the contract routes', () => {
    expect(opened().sort()).toEqual(contract.routes.map((r) => `${r.method} ${r.path}`).sort());
  });
});
