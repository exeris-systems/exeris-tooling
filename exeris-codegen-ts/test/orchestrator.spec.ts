/**
 * Coverage for src/orchestrator.ts — buildGeneratedFiles + generateEnumTypes.
 *
 * This is the T20 regression guard: the emitted Angular app must be ONE real tree
 * under the sourceRoot `src/app/`. Before the collapse, two paths ran — a correct
 * top-level tree AND a stub-tainted `src/app` duplicate (empty enum module) — and the
 * build resolved enums to the stub. These tests pin: real enum module, per-entity
 * artefacts under src/app, and NO duplicate top-level tree. The full type-check that
 * the generated app compiles is the FE build gate (ng build / tsc) in CI.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles, generateEnumTypes, type EnumMetadataForGen } from '../src/orchestrator.js';
import {
  DomainMetadataSchema,
  ViewMetadataSchema,
  type DomainMetadata,
} from '../src/models/domain-model.js';
import { DEFAULT_CONFIG } from '../src/config.js';

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

const BATTLE_STATUS: EnumMetadataForGen = {
  name: 'BattleStatus',
  qualifiedName: 'com.shop.BattleStatus',
  packageName: 'com.shop',
  values: [
    { name: 'ACTIVE', displayName: 'Active', ordinal: 0 },
    { name: 'RESOLVED', displayName: 'Resolved', ordinal: 1 },
  ],
};

describe('generateEnumTypes', () => {
  it('emits real members — const value + literal-union type + DisplayNames + Zod (never the empty-enum stub)', () => {
    const c = generateEnumTypes([BATTLE_STATUS], true);
    expect(c).toContain("import { z } from 'zod';");
    expect(c).toContain('export const BattleStatus = {');
    expect(c).toContain("ACTIVE: 'ACTIVE',");
    expect(c).toContain("RESOLVED: 'RESOLVED',");
    expect(c).toContain('export type BattleStatus = typeof BattleStatus[keyof typeof BattleStatus];');
    expect(c).toContain('export const BattleStatusDisplayNames');
    expect(c).toContain('export const BattleStatusSchema = z.enum([');
    // The T20 stub forms must never reappear.
    expect(c).not.toContain('// TODO');
    expect(c).not.toContain('export enum BattleStatus');
  });

  it('zero enums → a valid empty module (export {};) with NO dangling zod import — barrels re-exporting ./enums must still resolve', () => {
    const c = generateEnumTypes([], true);
    expect(c).toContain('export {};');
    expect(c).not.toContain("import { z } from 'zod';");
    expect(c).not.toContain('export const');
  });

  it('includeZod=false suppresses the Zod schema + import (a --no-zod build)', () => {
    const c = generateEnumTypes([BATTLE_STATUS], false);
    expect(c).toContain('export const BattleStatus = {');
    expect(c).not.toContain("import { z } from 'zod';");
    expect(c).not.toContain('BattleStatusSchema');
  });
});

describe('buildGeneratedFiles — T20: one real tree under src/app', () => {
  const files = buildGeneratedFiles(
    [domain({ entityName: 'Order' }), domain({ entityName: 'Battle' })],
    [BATTLE_STATUS],
    DEFAULT_CONFIG,
  );
  const at = (p: string) => files.find((f) => f.path === p);

  it('emits the REAL enum module under the Angular sourceRoot', () => {
    const e = at('src/app/types/enums.ts');
    expect(e, 'src/app/types/enums.ts missing').toBeDefined();
    expect(e!.content).toContain('export const BattleStatus = {');
    expect(e!.content).toContain('export const BattleStatusSchema = z.enum([');
    expect(e!.content).not.toContain('// TODO');
  });

  it('emits per-entity artefacts under src/app (types, service, form, list, schema)', () => {
    expect(at('src/app/types/order.types.ts')).toBeDefined();
    expect(at('src/app/services/order.service.ts')).toBeDefined();
    expect(at('src/app/components/order-form.component.ts')).toBeDefined();
    expect(at('src/app/components/order-list.component.ts')).toBeDefined();
    expect(at('src/app/schemas/order.schema.ts')).toBeDefined();
    expect(at('src/app/types/battle.types.ts')).toBeDefined();
  });

  it('emits NO duplicate top-level tree — every per-entity/enum/schema file is under src/app', () => {
    const stray = files
      .map((f) => f.path)
      .filter((p) => /^(types|services|components|schemas)\//.test(p));
    expect(stray).toEqual([]);
  });

  it('includes the scaffold (package.json, main.ts, app.config) alongside the one tree', () => {
    expect(at('./package.json')).toBeDefined();
    expect(at('src/main.ts')).toBeDefined();
    expect(at('src/app/app.config.ts')).toBeDefined();
  });

  it('skips internalApi.hidden domains in the per-entity tree', () => {
    const withHidden = buildGeneratedFiles(
      [domain({ entityName: 'Secret', internalApi: { hidden: true, readOnly: false, internal: false } })],
      [],
      DEFAULT_CONFIG,
    );
    expect(withHidden.find((f) => f.path === 'src/app/services/secret.service.ts')).toBeUndefined();
    expect(withHidden.find((f) => f.path === 'src/app/types/secret.types.ts')).toBeUndefined();
  });
});

describe('buildGeneratedFiles — presentation IR (@View) flows to src/app/pages', () => {
  const view = ViewMetadataSchema.parse({
    name: 'Dashboard',
    kind: 'PAGE',
    route: '/dashboard',
    title: 'Dashboard',
    regions: [{ slot: 'main', components: [{ type: 'HERO', binding: { source: 'STATIC' }, props: 'Hi' }] }],
  });

  const files = buildGeneratedFiles([domain({ entityName: 'Order' })], [], DEFAULT_CONFIG, [view]);
  const at = (p: string) => files.find((f) => f.path === p);

  it('emits the view page component under src/app/pages', () => {
    const c = at('src/app/pages/dashboard.component.ts');
    expect(c, 'src/app/pages/dashboard.component.ts missing').toBeDefined();
    expect(c!.content).toContain('export class DashboardPageComponent {');
    expect(c!.content).toContain('<section data-region="main">');
  });

  it('emits the paired route under src/app/pages', () => {
    const r = at('src/app/pages/dashboard.route.ts');
    expect(r, 'src/app/pages/dashboard.route.ts missing').toBeDefined();
    expect(r!.content).toContain('export const dashboardRoutes: Routes = [');
  });

  it('omits the pages tree entirely when no views are supplied (default arg)', () => {
    const noViews = buildGeneratedFiles([domain({ entityName: 'Order' })], [], DEFAULT_CONFIG);
    expect(noViews.some((f) => f.path.startsWith('src/app/pages/'))).toBe(false);
  });

  it('wires the view route into the app shell — app.routes.ts imports + spreads the page route (RFC §5 route assembly)', () => {
    const routes = at('src/app/app.routes.ts')!;
    expect(routes, 'src/app/app.routes.ts missing').toBeDefined();
    // The per-view route const is imported from ./pages/<kebab>.route and spread
    // into the routes array — the standalone front can now navigate to the @View page.
    expect(routes.content).toContain("import { dashboardRoutes } from './pages/dashboard.route';");
    expect(routes.content).toContain('...dashboardRoutes,');
    // A PAGE view also wins the default redirect over the first entity.
    expect(routes.content).toContain("redirectTo: 'dashboard'");
  });

  it('leaves app.routes.ts free of any pages import when no views are supplied (additive)', () => {
    const noViews = buildGeneratedFiles([domain({ entityName: 'Order' })], [], DEFAULT_CONFIG);
    const routes = noViews.find((f) => f.path === 'src/app/app.routes.ts')!;
    expect(routes.content).not.toContain('./pages/');
    expect(routes.content).toContain("redirectTo: 'orders'");
  });
});
