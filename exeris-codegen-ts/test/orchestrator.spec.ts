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

// ---------------------------------------------------------------------------
// Peer contracts (T42, ADR-048) — composition, and the invariant that keeps the
// three-Order sample compiling.
// ---------------------------------------------------------------------------

describe('buildGeneratedFiles — peer contracts', () => {
  const peerOrder = (pkg: string, extra: Record<string, string>) =>
    DomainMetadataSchema.parse({
      packageName: pkg,
      entityName: 'Order',
      fields: [{ name: 'id', type: 'java.util.UUID' }, extra],
    });

  const peers = [
    { name: 'billing', domains: [peerOrder('com.billing', { name: 'invoiceNo', type: 'String' })], enums: [] },
    { name: 'shipping', domains: [peerOrder('com.shipping', { name: 'trackingCode', type: 'String' })], enums: [] },
  ];

  const localOrder = DomainMetadataSchema.parse({
    packageName: 'com.shop',
    entityName: 'Order',
    fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'total', type: 'java.math.BigDecimal' }],
  });

  it('emits nothing under peers/ when no peer is declared', () => {
    const files = buildGeneratedFiles([localOrder], [], DEFAULT_CONFIG);
    expect(files.some((f) => f.path.includes('/peers/'))).toBe(false);
  });

  it('roots each peer tree under src/app/peers/<name>', () => {
    const files = buildGeneratedFiles([localOrder], [], DEFAULT_CONFIG, [], peers);
    expect(files.some((f) => f.path === 'src/app/peers/billing/index.ts')).toBe(true);
    expect(files.some((f) => f.path === 'src/app/peers/shipping/index.ts')).toBe(true);
  });

  // ADR-048 §3. Two peers and the app may all declare `Order`; that compiles only because
  // no barrel outside a peer's own directory re-exports it. `export *` of two modules that
  // both export `Order` does not merge them — it makes `Order` unexported (TS2308/TS2305),
  // which is the mesh-scale form of the T40 break.
  it('never re-exports a peer from the app barrel or the app\'s own types barrel', () => {
    const files = buildGeneratedFiles([localOrder], [], DEFAULT_CONFIG, [], peers);
    const appBarrel = files.find((f) => f.path === 'src/app/index.ts')?.content ?? '';
    const typesBarrel = files.find((f) => f.path === 'src/app/types/index.ts')?.content ?? '';

    expect(appBarrel).not.toContain('peers/');
    expect(typesBarrel).not.toContain('peers/');
    expect(appBarrel.length).toBeGreaterThan(0);
    expect(typesBarrel).toContain("export * from './order.types';");
  });

  // The reverse edge is the same invariant read the other way: a peer file must not reach
  // into the app's tree either, or the app's `Order` would leak into the peer's namespace.
  it('emits no peer file that imports outside its own peer directory', () => {
    const files = buildGeneratedFiles([localOrder], [], DEFAULT_CONFIG, [], peers);
    const peerFiles = files.filter((f) => f.path.includes('/peers/'));
    expect(peerFiles.length).toBeGreaterThan(0);
    for (const file of peerFiles) {
      for (const [, spec] of file.content.matchAll(/from '([^']+)'/g)) {
        expect(spec === 'zod' || spec.startsWith('./') || spec.startsWith('../types')).toBe(true);
      }
    }
  });

  it('keeps each peer\'s own field set — the trees are separate, not deduplicated', () => {
    const files = buildGeneratedFiles([localOrder], [], DEFAULT_CONFIG, [], peers);
    const at = (p: string) => files.find((f) => f.path === p)?.content ?? '';
    expect(at('src/app/peers/billing/types/order.types.ts')).toContain('invoiceNo');
    expect(at('src/app/peers/shipping/types/order.types.ts')).toContain('trackingCode');
    expect(at('src/app/types/order.types.ts')).toContain('total');
  });

  it('emits peers in the order given, which the loader has already sorted by name', () => {
    const paths = buildGeneratedFiles([localOrder], [], DEFAULT_CONFIG, [], peers)
      .filter((f) => f.path.includes('/peers/'))
      .map((f) => f.path);
    expect(paths.findIndex((p) => p.includes('/billing/'))).toBeLessThan(
      paths.findIndex((p) => p.includes('/shipping/')),
    );
  });
});

// ---------------------------------------------------------------------------
// generateDetails wiring (0.8.0). The flag defaulted to true and was read by nobody,
// so DetailGenerator emitted nothing — while the emitted LIST already linked to the
// routes a detail component owns.
// ---------------------------------------------------------------------------

describe('buildGeneratedFiles — detail components', () => {
  const order = domain({ entityName: 'Order', fields: [{ name: 'id', type: 'java.util.UUID' }] });
  const at = (files: { path: string; content: string }[], p: string) =>
    files.find((f) => f.path === p)?.content ?? '';

  it('emits a detail component per entity', () => {
    const files = buildGeneratedFiles([order], [], DEFAULT_CONFIG);
    expect(files.some((f) => f.path === 'src/app/components/order-detail.component.ts')).toBe(true);
  });

  // Named for what it actually does. The earlier title said "honours --no-details" while
  // testing only the config object — and the CLI flag did not exist at all, so the name was
  // the thing hiding the gap. The flag itself is covered in config.spec.ts (cliOverrides).
  it('emits no detail component when generateDetails is false', () => {
    const files = buildGeneratedFiles([order], [], { ...DEFAULT_CONFIG, generateDetails: false });
    expect(files.some((f) => f.path.includes('-detail.component'))).toBe(false);
  });

  it('exports the detail component from the app barrel, like form and list', () => {
    const barrel = at(buildGeneratedFiles([order], [], DEFAULT_CONFIG), 'src/app/index.ts');
    expect(barrel).toContain("export { OrderDetailComponent } from './components/order-detail.component';");
  });

  // The route shape is not a preference: list-gen emits `[routerLink]="[item.id]"` labelled
  // "View" and `[routerLink]="[item.id, 'edit']"` labelled "Edit". Before this wiring, `:id`
  // loaded the FORM — so "View" opened an editor — and `:id/edit` matched no route at all,
  // because the emitted table carries no wildcard.
  it('routes :id to the detail view and :id/edit to the form', () => {
    const routes = at(buildGeneratedFiles([order], [], DEFAULT_CONFIG), 'src/app/app.routes.ts');
    expect(routes).toContain("path: 'orders/:id'");
    expect(routes).toContain('m.OrderDetailComponent');
    expect(routes).toContain("path: 'orders/:id/edit'");
    expect(routes).toContain('m.OrderFormComponent');
  });

  it('gives every link the emitted list renders a matching route', () => {
    const files = buildGeneratedFiles([order], [], DEFAULT_CONFIG);
    const list = at(files, 'src/app/components/order-list.component.ts');
    const routes = at(files, 'src/app/app.routes.ts');

    expect(list).toContain('[routerLink]="[item.id]"');
    expect(list).toContain(`[routerLink]="[item.id, 'edit']"`);
    // ...and both targets now exist.
    expect(routes).toContain("path: 'orders/:id'");
    expect(routes).toContain("path: 'orders/:id/edit'");
  });
});

// ---------------------------------------------------------------------------
// generateEvents wiring (0.8.0). Unlike its siblings this generator has TWO call
// sites: a per-entity handler and one app-wide bus that every handler imports.
// Wiring only the first would emit handlers importing a file that does not exist.
// ---------------------------------------------------------------------------

describe('buildGeneratedFiles — domain events', () => {
  const withEvents = domain({
    entityName: 'Order',
    fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'total', type: 'java.math.BigDecimal' }],
    events: [{ name: 'OrderPlaced', payloadFields: ['id', 'total'] }],
  });
  const noEvents = domain({ entityName: 'Product', fields: [{ name: 'id', type: 'java.util.UUID' }] });
  const at = (files: { path: string; content: string }[], p: string) =>
    files.find((f) => f.path === p)?.content ?? '';

  it('emits both the per-entity handler and the shared bus', () => {
    const files = buildGeneratedFiles([withEvents], [], DEFAULT_CONFIG);
    expect(files.some((f) => f.path === 'src/app/events/order.events.ts')).toBe(true);
    expect(files.some((f) => f.path === 'src/app/events/event-bus.service.ts')).toBe(true);
  });

  // The handler imports './event-bus.service'. Emitting one without the other is a dangling
  // import — TS2307 — which is why both call sites had to be wired in the same change.
  it('emits the bus the handler imports', () => {
    const files = buildGeneratedFiles([withEvents], [], DEFAULT_CONFIG);
    expect(at(files, 'src/app/events/order.events.ts')).toContain("from './event-bus.service'");
    expect(files.some((f) => f.path === 'src/app/events/event-bus.service.ts')).toBe(true);
  });

  it('emits one bus for many entities, not one per entity', () => {
    const second = domain({
      entityName: 'Invoice',
      fields: [{ name: 'id', type: 'java.util.UUID' }],
      events: [{ name: 'InvoiceIssued', payloadFields: ['id'] }],
    });
    const buses = buildGeneratedFiles([withEvents, second], [], DEFAULT_CONFIG)
      .filter((f) => f.path.endsWith('event-bus.service.ts'));
    expect(buses).toHaveLength(1);
  });

  it('emits nothing under events/ when no entity declares one', () => {
    const files = buildGeneratedFiles([noEvents], [], DEFAULT_CONFIG);
    expect(files.some((f) => f.path.includes('/events/'))).toBe(false);
  });

  it('emits no event code when generateEvents is false', () => {
    const files = buildGeneratedFiles([withEvents], [], { ...DEFAULT_CONFIG, generateEvents: false });
    expect(files.some((f) => f.path.includes('/events/'))).toBe(false);
  });

  // Both emitted classes are providedIn:'root' and nothing in the emitted app injects them —
  // they exist for the consumer's own code, like the generated services. The barrel is how that
  // code reaches them, so an event surface missing from it is emitted-but-unreachable.
  it('exports the event surface from the app barrel', () => {
    const barrel = at(buildGeneratedFiles([withEvents], [], DEFAULT_CONFIG), 'src/app/index.ts');
    expect(barrel).toContain("export { EventBusService } from './events/event-bus.service';");
    expect(barrel).toContain("export * from './events/order.events';");
  });

  it('adds no event exports to the barrel for an app with no events', () => {
    const barrel = at(buildGeneratedFiles([noEvents], [], DEFAULT_CONFIG), 'src/app/index.ts');
    expect(barrel).not.toContain('events/');
  });
});

// ---------------------------------------------------------------------------
// T2 FE spec slice (0.8.0, ADR-058). Opt-in: `generateTests` defaults to false,
// because turning it on also puts a runner and two devDependencies into the
// consumer's package.json.
// ---------------------------------------------------------------------------

describe('buildGeneratedFiles — generated specs', () => {
  const order = domain({
    entityName: 'Order',
    fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'total', type: 'java.math.BigDecimal' }],
  });
  const withTests = { ...DEFAULT_CONFIG, generateTests: true };
  const at = (files: { path: string; content: string }[], p: string) =>
    files.find((f) => f.path === p)?.content ?? '';

  it('emits no spec and no runner by default', () => {
    const files = buildGeneratedFiles([order], [], DEFAULT_CONFIG);
    expect(files.some((f) => f.path.endsWith('.spec.ts'))).toBe(false);
    expect(files.some((f) => f.path.endsWith('tsconfig.spec.json'))).toBe(false);
    expect(at(files, './package.json')).not.toContain('vitest');
    expect(at(files, './angular.json')).not.toContain('unit-test');
  });

  it('emits both specs and the whole runner when asked', () => {
    const files = buildGeneratedFiles([order], [], withTests);
    expect(files.some((f) => f.path === 'src/app/schemas/order.schema.spec.ts')).toBe(true);
    expect(files.some((f) => f.path === 'src/app/services/order.service.spec.ts')).toBe(true);
    expect(files.some((f) => f.path === './tsconfig.spec.json')).toBe(true);
    expect(at(files, './angular.json')).toContain('"builder": "@angular/build:unit-test"');
  });

  // Both are consumer-build requirements the runner cannot start without: vitest is an OPTIONAL
  // peer of @angular/build, and the builder refuses to run without a DOM implementation, naming
  // jsdom or happy-dom itself.
  it('declares the two dependencies the runner needs, and only under the flag', () => {
    const on = at(buildGeneratedFiles([order], [], withTests), './package.json');
    expect(on).toContain('"vitest"');
    expect(on).toContain('"jsdom"');
  });

  // Otherwise a consumer's production `ng build` type-checks the specs and therefore needs vitest
  // installed — a test-only dependency leaking into the build path.
  it('excludes specs from the app tsconfig so a production build never needs the runner', () => {
    expect(at(buildGeneratedFiles([order], [], withTests), './tsconfig.app.json'))
      .toContain('"exclude": ["src/**/*.spec.ts"]');
    expect(at(buildGeneratedFiles([order], [], DEFAULT_CONFIG), './tsconfig.app.json'))
      .not.toContain('exclude');
  });

  // A spec must never import a file the same run did not emit.
  it('emits a spec only for a surface that was itself emitted', () => {
    const noZod = buildGeneratedFiles([order], [], { ...withTests, generateZod: false });
    expect(noZod.some((f) => f.path.endsWith('.schema.spec.ts'))).toBe(false);
    expect(noZod.some((f) => f.path.endsWith('.service.spec.ts'))).toBe(true);

    const noServices = buildGeneratedFiles([order], [], { ...withTests, generateServices: false });
    expect(noServices.some((f) => f.path.endsWith('.service.spec.ts'))).toBe(false);
  });
});
