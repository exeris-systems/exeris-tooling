/**
 * Route policy parity — the TypeScript half of a cross-emitter check.
 *
 * The generated kernel application's `GeneratedRoutePolicy` declares a requirement per route, and
 * the generated frontend decides from the same rows whether it sends a bearer token. The
 * application is built by Maven and this package by npm, so the table lives in one committed file
 * both sides test against: `RoutePolicyContractE2ETest` (exeris-e2e-tests) proves the emitted policy
 * declares exactly `contract/route-policy.json`, and this spec proves the rows this package derives
 * from the same metadata are exactly its `routes`.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { deriveRouteRequirements, type RouteRequirementRow } from '../../../src/core/route-requirements.js';
import { DomainMetadataSchema } from '../../../src/models/domain-model.js';

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
