/**
 * The route-requirement contract the frontend shares with the generated kernel application.
 *
 * `contract/route-policy.json` is the table both builds are held to; `route-policy.example.json`
 * holds representative rows of every kind, for the shape only. The predicate is read over both; the
 * derivation is held to the committed table in route-policy-parity.spec.ts.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  demandsIdentity,
  deriveRouteRequirements,
  type RouteRequirementRow,
} from '../../src/core/route-requirements.js';
import { DomainMetadataSchema } from '../../src/models/domain-model.js';

// A missing file fails the suite rather than skipping it.
function contractRows(file: string): RouteRequirementRow[] {
  const url = new URL(`../../../exeris-e2e-tests/src/test/resources/contract/${file}`, import.meta.url);
  return (JSON.parse(readFileSync(fileURLToPath(url), 'utf8')) as { routes: RouteRequirementRow[] }).routes;
}

const example = contractRows('route-policy.example.json');
const committed = contractRows('route-policy.json');

describe('demandsIdentity', () => {
  it('holds for the example rows, which carry authenticated and anyScope routes', () => {
    expect(demandsIdentity(example)).toBe(true);
  });

  it('holds for the committed table, which carries authenticated and anyScope rows', () => {
    expect(committed.length).toBeGreaterThan(0);
    expect(demandsIdentity(committed)).toBe(true);
  });

  it('does not hold when every row is permitAll', () => {
    const open = example.filter((row) => row.requirement.kind === 'permitAll');
    expect(open.length).toBeGreaterThan(0);
    expect(demandsIdentity(open)).toBe(false);
  });

  it('holds for a single authenticated row and for a single anyScope row', () => {
    for (const kind of ['authenticated', 'anyScope'] as const) {
      const row = example.find((r) => r.requirement.kind === kind);
      expect(row, `an example ${kind} row`).toBeDefined();
      expect(demandsIdentity([row!])).toBe(true);
    }
  });
});

describe('deriveRouteRequirements', () => {
  const base = { packageName: 'com.shop', path: '/orders' };

  it('declares no row for an entity and actions that declare nothing', () => {
    const order = DomainMetadataSchema.parse({ ...base, entityName: 'Order', actions: [{ name: 'cancel' }] });
    expect(deriveRouteRequirements([order])).toEqual([]);
    expect(deriveRouteRequirements([])).toEqual([]);
  });

  it('gives an undeclared entity no row but its declaring action one, sourced from the action', () => {
    const order = DomainMetadataSchema.parse({
      ...base,
      entityName: 'Order',
      actions: [{ name: 'approve', permissions: ['order:approve', 'order:approve'] }, { name: 'cancel' }],
    });
    expect(deriveRouteRequirements([order])).toEqual([
      {
        entity: 'Order',
        method: 'POST',
        path: '{base}/{id}/actions/{action}',
        action: 'approve',
        requirement: { kind: 'anyScope', scopes: ['order:approve'] },
        source: 'action',
      },
    ]);
  });

  it('lets PUBLIC win over the entity and AUTHENTICATED apply only without permissions', () => {
    const order = DomainMetadataSchema.parse({
      ...base,
      entityName: 'Order',
      routeAccess: 'AUTHENTICATED',
      permissions: ['order:read'],
      actions: [{ name: 'export', routeAccess: 'PUBLIC' }],
    });
    const rows = deriveRouteRequirements([order]);
    expect(rows.filter((r) => r.source === 'entity').map((r) => r.requirement)).toEqual(
      Array(5).fill({ kind: 'anyScope', scopes: ['order:read'] }),
    );
    expect(rows.find((r) => r.action === 'export')?.requirement).toEqual({ kind: 'permitAll' });
  });
});
