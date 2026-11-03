/**
 * Route policy parity — the TypeScript half of a cross-emitter check.
 *
 * The generated kernel application's `GeneratedRoutePolicy` declares a requirement per route, and
 * the generated frontend decides from the same rows whether it sends a bearer token. The
 * application is built by Maven and this package by npm, so the table lives in one committed file
 * both sides test against: `RoutePolicyContractE2ETest` (exeris-e2e-tests) proves the emitted policy
 * declares exactly `contract/route-policy.json`, and this spec proves the rows this package derives
 * from the same metadata are exactly its `routes`, and that each generated page is guarded exactly
 * as the contract row of the backend route it calls first requires.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { deriveRouteRequirements, type RouteRequirementRow } from '../../../src/core/route-requirements.js';
import { DomainMetadataSchema } from '../../../src/models/domain-model.js';
import { DslMapper } from '../../../src/models/dsl-mapper.js';
import { buildGeneratedFiles } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';

// A missing file must fail the suite, not skip it.
const CONTRACT_URL = new URL(
  '../../../../exeris-e2e-tests/src/test/resources/contract/route-policy.json',
  import.meta.url,
);
const contract = JSON.parse(readFileSync(fileURLToPath(CONTRACT_URL), 'utf8')) as { routes: RouteRequirementRow[] };

// The domain model RoutePolicyFixture (exeris-e2e-tests) generates its application from.
const pkg = 'eu.exeris.e2e.parity.domain';
const domains = [
  DomainMetadataSchema.parse({
    packageName: pkg,
    entityName: 'Order',
    path: '/orders',
    realTimeApi: true,
    permissions: ['order:read', 'order:write'],
    fields: [
      {
        name: 'receipt',
        type: 'eu.exeris.kernel.spi.storage.blob.BlobRef',
        blob: { contentTypes: ['application/pdf'] },
      },
    ],
    actions: [
      { name: 'cancel', methodName: 'cancel' },
      { name: 'approve', methodName: 'approve', permissions: ['order:approve'] },
      { name: 'trackShipment', methodName: 'trackShipment', streaming: true },
    ],
  }),
  DomainMetadataSchema.parse({
    packageName: pkg,
    entityName: 'Invoice',
    path: '/invoices',
    realTimeApi: true,
    routeAccess: 'AUTHENTICATED',
    actions: [{ name: 'export', methodName: 'export', routeAccess: 'PUBLIC' }],
  }),
  DomainMetadataSchema.parse({
    packageName: pkg,
    entityName: 'Catalog',
    path: '/orders/catalog',
    realTimeApi: true,
    routeAccess: 'PUBLIC',
    fields: [
      {
        name: 'image',
        type: 'eu.exeris.kernel.spi.storage.blob.BlobRef',
        blob: { contentTypes: [] },
      },
    ],
  }),
  DomainMetadataSchema.parse({
    packageName: pkg,
    entityName: 'Tag',
    path: '/tags',
    actions: [{ name: 'merge', methodName: 'merge' }],
  }),
];

describe('route policy parity with the generated kernel application', () => {
  it('the rows derived from the fixture metadata are exactly the contract rows, in order', () => {
    expect(deriveRouteRequirements(domains)).toEqual(contract.routes);
  });
});

describe('page guard parity with the generated kernel application', () => {
  const routes = buildGeneratedFiles(domains, [], DEFAULT_CONFIG).find((f) => f.path === 'src/app/app.routes.ts')!.content;
  /** Each page, the route path it is served at, and the backend route it calls first. */
  const PAGES = [
    ['list', 'GET', '{base}', ''],
    ['create', 'POST', '{base}', '/new'],
    ['detail', 'GET', '{base}/{id}', '/:id'],
    ['edit', 'PUT', '{base}/{id}', '/:id/edit'],
  ] as const;

  /** The `canActivate` the contract row demands, or undefined for a public or absent row. */
  const expectedGuard = (row: RouteRequirementRow | undefined): string | undefined => {
    if (row?.requirement.kind === 'authenticated') return 'requireAuthenticated';
    if (row?.requirement.kind === 'anyScope') {
      return `requireAnyPermission(${row.requirement.scopes!.map((scope) => `'${scope}'`).join(', ')})`;
    }
    return undefined;
  };

  /** The `canActivate` entry of the page at `path`, or undefined when it has none. */
  const actualGuard = (path: string): string | undefined => {
    const start = routes.indexOf(`path: '${path}',`);
    expect(start, `page ${path} is routed`).toBeGreaterThan(0);
    const page = routes.slice(start, routes.indexOf('\n  },', start));
    return /canActivate: \[(.*)\]/.exec(page)?.[1];
  };

  it.each(domains.flatMap((d) => PAGES.map((p) => [d.entityName, ...p] as const)))(
    '%s %s page carries the guard of the contract row of %s %s',
    (entity, _page, method, path, suffix) => {
      const row = contract.routes.find(
        (r) => r.entity === entity && r.method === method && r.path === path && !r.action && !r.field,
      );
      expect(actualGuard(`${DslMapper.routePlural(entity)}${suffix}`)).toBe(expectedGuard(row));
    },
  );

  it('covers a page of every requirement kind', () => {
    expect(routes).toContain('canActivate: [requireAuthenticated]');
    expect(routes).toContain('canActivate: [requireAnyPermission(');
    expect(actualGuard('catalogs')).toBeUndefined();
    expect(actualGuard('tags')).toBeUndefined();
  });
});
