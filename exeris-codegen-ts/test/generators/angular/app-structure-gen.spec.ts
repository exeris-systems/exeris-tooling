/**
 * Coverage for src/generators/angular/app-structure-gen.ts —
 * generateAppStructure emits the full Angular app skeleton:
 *   * static configs (package.json, angular.json, tsconfig{.app}.json,
 *     tailwind.config.js, .postcssrc.json, proxy.conf.json)
 *   * src/{styles.css, index.html, favicon.ico, main.ts}
 *   * src/environments/{environment.ts, environment.development.ts}
 *   * src/app/{app.config.ts, app.component.ts, app.routes.ts, index.ts}
 *   * per-domain: form / list / service / types / schemas (delegated)
 *   * src/app/types/enums.ts (always emitted, even with empty enums —
 *     the local placeholder generateEnums returns a truthy
 *     {content: ''} which the orchestrator does NOT filter out)
 *
 * Branch points pinned:
 *   - Domain-loop body: hidden-domain skip for form/list/service/types
 *     (sub-generators return null on internalApi.hidden); schema still
 *     emits (local placeholder always returns content).
 *   - Pluraliser (two seams): nav label / browser-tab title go
 *     through labelPlural(entityName) (camelcase + `endsWith('s')`
 *     guard); URL paths + sidebar router-link target go through
 *     routePlural(entityName) (kebab-cased, same guard). Both
 *     seams must suppress the trailing 's' for entities already
 *     ending in 's' (e.g. `News`) so we don't get `Newss`
 *     in the tab title or `/newss` in the URL.
 *   - getEntityIcon: each known entity name → its emoji; unknown →
 *     default '📁'.
 *   - generateAppRoutes: empty domains → redirectTo: ''; non-empty →
 *     first domain's kebab + 's'.
 *   - generateBarrelExport: Page/PageRequest exported ONLY ONCE
 *     (from the first domain's service), other domains export bare
 *     {Service, Filter}.
 *   - resolveApiSettings: apiBasePath wins when truthy; falls back to
 *     strategy.getClientConfig().baseUrl when empty/missing; apiVersion
 *     always comes from the strategy.
 *
 * Conventions:
 *   - Domains/fields are built through Zod parse so we lean on the
 *     same defaults the production loader applies (matches the
 *     pattern used by saga-gen.spec / form-gen.spec).
 *   - We do not pin the exact emitted-source contents of the
 *     downstream sub-generators — those have dedicated specs. We
 *     pin only that this orchestrator wires their output into the
 *     right paths with the right overwritable flags.
 */

import { describe, expect, it } from 'vitest';
import { generateAppStructure } from '../../../src/generators/angular/app-structure-gen.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import {
  DomainMetadataSchema,
  type DomainMetadata,
} from '../../../src/models/domain-model.js';
import type { GeneratorConfig } from '../../../src/config.js';

interface EnumLike {
  name: string;
  qualifiedName: string;
}

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

function fileAt(files: Array<{ path: string; content: string }>, path: string) {
  return files.find((f) => f.path === path);
}

const cfg = (overrides: Partial<GeneratorConfig> = {}): GeneratorConfig => ({
  ...DEFAULT_CONFIG,
  ...overrides,
});

// ---------- static-file emission (independent of domains) ----------

describe('generateAppStructure — static skeleton', () => {
  const files = generateAppStructure([], [], cfg());

  it.each([
    ['./package.json', false],
    ['./angular.json', false],
    ['./tsconfig.json', false],
    ['./tsconfig.app.json', false],
    ['./tailwind.config.js', true],
    ['./.postcssrc.json', true],
    ['./proxy.conf.json', true],
    ['src/styles.css', true],
    ['src/index.html', true],
    ['src/favicon.ico', true],
    ['src/main.ts', false],
    ['src/environments/environment.ts', false],
    ['src/environments/environment.development.ts', true],
    ['src/app/app.config.ts', false],
    ['src/app/app.component.ts', false],
    ['src/app/app.routes.ts', false],
    ['src/app/index.ts', true],
  ] as const)('emits %s with overwritable=%s', (path, overwritable) => {
    const f = files.find((x) => x.path === path);
    expect(f, `missing ${path}`).toBeDefined();
    expect(f!.overwritable).toBe(overwritable);
  });

  it('production environment.ts has production:true; development has production:false', () => {
    const prod = fileAt(files, 'src/environments/environment.ts')!;
    const dev = fileAt(files, 'src/environments/environment.development.ts')!;
    expect(prod.content).toContain('production: true');
    expect(dev.content).toContain('production: false');
  });

  it('main.ts bootstraps AppComponent with appConfig', () => {
    const main = fileAt(files, 'src/main.ts')!;
    expect(main.content).toContain('bootstrapApplication(AppComponent, appConfig)');
  });

  it('app.config.ts uses Zoneless + provideRouter; v22 drops withFetch() (fetch is default)', () => {
    const c = fileAt(files, 'src/app/app.config.ts')!;
    expect(c.content).toContain('provideZonelessChangeDetection()');
    expect(c.content).toContain('provideRouter(routes, withComponentInputBinding())');
    // Phase A: fetch is the default HttpClient transport in v22 — withFetch() is gone.
    expect(c.content).toContain('provideHttpClient()');
    expect(c.content).not.toContain('withFetch');
    // B4: native animate.enter needs no animations provider — @angular/animations is dropped.
    expect(c.content).not.toContain('provideAnimationsAsync');
    expect(c.content).not.toContain("from '@angular/platform-browser/animations/async'");
  });

  it('package.json pins the Angular v22 toolchain (Phase A compat bump)', () => {
    const pkg = fileAt(files, './package.json')!;
    // @angular/* and devkit/cli/compiler-cli all on ^22; no lingering ^21 pin.
    expect(pkg.content).toContain('"@angular/core": "^22.0.0"');
    expect(pkg.content).toContain('"@angular/cli": "^22.0.0"');
    expect(pkg.content).toContain('"@angular/compiler-cli": "^22.0.0"');
    expect(pkg.content).not.toContain('^21.0.0');
    // v22 requires TypeScript 6 (>=6.0 <6.1) and drops 5.9.
    expect(pkg.content).toContain('"typescript": "~6.0.0"');
    expect(pkg.content).not.toContain('~5.9');
    // B4: @angular/animations is deprecated in v22 — the dep is dropped (native animate.enter).
    expect(pkg.content).not.toContain('@angular/animations');
  });

  it('Phase B scaffold cleanup: no platform-browser-dynamic, Node floor 22, @angular/build builder', () => {
    const pkg = fileAt(files, './package.json')!;
    const ng = fileAt(files, './angular.json')!;
    // bootstrapApplication() scaffold — platformBrowserDynamic is never used.
    expect(pkg.content).not.toContain('@angular/platform-browser-dynamic');
    // v22 floor is Node 22 (Active LTS), not 24.
    expect(pkg.content).toContain('"node": ">=22.0.0"');
    expect(pkg.content).not.toContain('>=24.0.0');
    // @types/node tracks the Node floor — must not regress to ^24.
    expect(pkg.content).toContain('"@types/node": "^22.0.0"');
    // esbuild @angular/build builder (ng-new default since v19), not the devkit wrapper.
    expect(pkg.content).toContain('"@angular/build": "^22.0.0"');
    expect(pkg.content).not.toContain('@angular-devkit/build-angular');
    expect(ng.content).toContain('"@angular/build:application"');
    expect(ng.content).toContain('"@angular/build:dev-server"');
    expect(ng.content).not.toContain('@angular-devkit/build-angular');
  });

  it('emits NO enum module (T20: the orchestrator owns src/app/types/enums.ts, not the scaffold)', () => {
    expect(fileAt(files, 'src/app/types/enums.ts')).toBeUndefined();
  });
});

// ---------- T25: ui-kit token system wiring ----------

describe('generateAppStructure — @exeris/ui-kit token wiring (T25)', () => {
  const files = generateAppStructure(
    [domain({ entityName: 'Order' }), domain({ entityName: 'Product' })],
    [],
    cfg(),
  );

  it('styles.css imports Tailwind v4 then the ui-kit v4 @theme token entry, and drops the boilerplate theme', () => {
    const css = fileAt(files, 'src/styles.css')!;
    // v4 token wiring: tailwindcss first, then the ui-kit "theme" (v4 @theme) entry.
    expect(css.content).toContain('@import "tailwindcss";');
    expect(css.content).toContain('@import "@exeris/ui-kit/theme";');
    // Boilerplate component classes a product immediately deletes are gone.
    expect(css.content).not.toContain('.btn-primary');
    expect(css.content).not.toContain('.btn-secondary');
    expect(css.content).not.toContain('.input-field');
    // Hardcoded gray body theme is gone; body uses the exeris font token instead.
    expect(css.content).not.toContain('bg-gray-100 text-gray-900');
    expect(css.content).toContain('@apply font-exeris;');
    // No hardcoded indigo accent left in the global stylesheet.
    expect(css.content).not.toContain('indigo');
  });

  it('package.json declares the @exeris/ui-kit dependency (^0.1.0, the current ui-kit version)', () => {
    const pkg = fileAt(files, './package.json')!;
    expect(pkg.content).toContain('"@exeris/ui-kit": "^0.1.0"');
  });

  it('tailwind.config.js wires the ui-kit v3 preset so a v3 toolchain also gets the tokens', () => {
    const tw = fileAt(files, './tailwind.config.js')!;
    expect(tw.content).toContain("import exerisPreset from '@exeris/ui-kit/tailwind.preset.js';");
    expect(tw.content).toContain('presets: [exerisPreset]');
  });

  it('no emitted template (scaffold or per-shape) ships the hardcoded bg-indigo-600 accent', () => {
    // The scaffold's own emitted files must be token-driven, not indigo-hardcoded.
    for (const f of files) {
      expect(f.content, `bg-indigo-600 leaked into ${f.path}`).not.toContain('bg-indigo-600');
    }
    // The brand button uses the exeris primary token + its hover token.
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    expect(comp.content).toContain('text-exeris-primary-hover');
  });
});

// ---------- T7/U5: configurable appName ----------

describe('generateAppStructure — configurable appName (T7/U5)', () => {
  it('defaults to "Exeris Foundation" (logo, index title, route titles, package name)', () => {
    const files = generateAppStructure([domain({ entityName: 'Order' })], [], cfg());
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    const html = fileAt(files, 'src/index.html')!;
    const routes = fileAt(files, 'src/app/app.routes.ts')!;
    const pkg = fileAt(files, './package.json')!;
    expect(comp.content).toContain('🚀 Exeris Foundation');
    expect(comp.content).toContain("title = 'Exeris Foundation';");
    expect(html.content).toContain('<title>Exeris Foundation</title>');
    expect(routes.content).toContain("title: 'Orders - Exeris Foundation'");
    expect(pkg.content).toContain('"name": "exeris-foundation-frontend"');
  });

  it('flows a custom appName into the logo, index title, route titles, package name + description', () => {
    const files = generateAppStructure(
      [domain({ entityName: 'Order' })],
      [],
      cfg({ appName: 'Acme Portal' }),
    );
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    const html = fileAt(files, 'src/index.html')!;
    const routes = fileAt(files, 'src/app/app.routes.ts')!;
    const pkg = fileAt(files, './package.json')!;
    // Logo + Angular component title field.
    expect(comp.content).toContain('🚀 Acme Portal');
    expect(comp.content).toContain("title = 'Acme Portal';");
    expect(comp.content).not.toContain('Exeris Foundation');
    // Browser tab + per-route titles.
    expect(html.content).toContain('<title>Acme Portal</title>');
    expect(routes.content).toContain("title: 'Orders - Acme Portal'");
    expect(routes.content).toContain("title: 'New Order - Acme Portal'");
    expect(routes.content).toContain("title: 'Edit Order - Acme Portal'");
    expect(routes.content).not.toContain('Exeris Foundation');
    // package.json name (kebab-cased) + description.
    expect(pkg.content).toContain('"name": "acme-portal-frontend"');
    expect(pkg.content).toContain('"description": "Acme Portal - Generated Angular Frontend"');
  });
});

// ---------- routes / barrel: empty domain set ----------

describe('generateAppStructure — empty domain set', () => {
  const files = generateAppStructure([], [], cfg());

  it('app.routes.ts emits a redirectTo:"" route (no first-domain default)', () => {
    const routes = fileAt(files, 'src/app/app.routes.ts')!;
    expect(routes.content).toContain("redirectTo: ''");
    expect(routes.content).toContain("pathMatch: 'full'");
  });

  it('app.component.ts header still renders without any nav links', () => {
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    expect(comp.content).toContain('🚀 Exeris Foundation');
    expect(comp.content).not.toContain('routerLink="/');
  });

  it('index.ts barrel still emits enums + section headers, no service exports', () => {
    const barrel = fileAt(files, 'src/app/index.ts')!;
    expect(barrel.content).toContain("export * from './types/enums';");
    expect(barrel.content).toContain('// Services (export service classes and pagination types)');
    expect(barrel.content).not.toContain('PageRequest');
  });

  it('emits NO per-domain component/service/schema/types files', () => {
    const componentFiles = files.filter((f) => f.path.startsWith('src/app/components/'));
    const serviceFiles = files.filter((f) => f.path.startsWith('src/app/services/'));
    const schemaFiles = files.filter((f) => f.path.startsWith('src/app/schemas/'));
    // src/app/types/ has enums.ts; per-domain *.types.ts files would
    // also live there — filter them out.
    const typeFiles = files.filter(
      (f) => f.path.startsWith('src/app/types/') && f.path !== 'src/app/types/enums.ts',
    );
    expect(componentFiles).toHaveLength(0);
    expect(serviceFiles).toHaveLength(0);
    expect(schemaFiles).toHaveLength(0);
    expect(typeFiles).toHaveLength(0);
  });
});

// ---------- routes / barrel: multi-domain ----------

describe('generateAppStructure — multi-domain wiring', () => {
  const domains = [
    domain({ entityName: 'Order' }),
    domain({ entityName: 'Product' }),
  ];
  const enums: EnumLike[] = [{ name: 'Status', qualifiedName: 'com.shop.Status' }];
  const files = generateAppStructure(domains, enums, cfg());

  it('app.routes.ts redirects to the FIRST domain (kebab + plural "s")', () => {
    const routes = fileAt(files, 'src/app/app.routes.ts')!;
    expect(routes.content).toContain("redirectTo: 'orders'");
  });

  it('app.routes.ts emits list / new / :id routes for each domain', () => {
    const routes = fileAt(files, 'src/app/app.routes.ts')!;
    expect(routes.content).toContain("path: 'orders'");
    expect(routes.content).toContain("path: 'orders/new'");
    expect(routes.content).toContain("path: 'orders/:id'");
    expect(routes.content).toContain('OrderListComponent');
    expect(routes.content).toContain('OrderFormComponent');
    expect(routes.content).toContain("path: 'products'");
    expect(routes.content).toContain('ProductListComponent');
  });

  it('app.component.ts sidebar emits a router link per domain', () => {
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    expect(comp.content).toContain('routerLink="/orders"');
    expect(comp.content).toContain('routerLink="/products"');
  });

  it('T20: scaffold-only — emits NO per-domain components/services/types/schemas or enum module, even with domains + enums (the orchestrator owns the src/app tree)', () => {
    expect(files.filter((f) => f.path.startsWith('src/app/components/'))).toHaveLength(0);
    expect(files.filter((f) => f.path.startsWith('src/app/services/'))).toHaveLength(0);
    expect(files.filter((f) => f.path.startsWith('src/app/schemas/'))).toHaveLength(0);
    expect(files.filter((f) => f.path.startsWith('src/app/types/'))).toHaveLength(0);
  });

  it('barrel exports Page+PageRequest ONLY from the first domain service', () => {
    const barrel = fileAt(files, 'src/app/index.ts')!;
    expect(barrel.content).toContain(
      "export { OrderService, OrderFilter, Page, PageRequest } from './services/order.service';",
    );
    expect(barrel.content).toContain(
      "export { ProductService, ProductFilter } from './services/product.service';",
    );
    // Belt-and-braces: PageRequest must appear exactly once in the
    // barrel; if a future change starts re-exporting it from every
    // service, we'd hit a TS2308 (duplicate export) in consumers.
    const matches = barrel.content.match(/PageRequest/g) ?? [];
    expect(matches).toHaveLength(1);
  });
});

// ---------- nav label pluralisation + entity-icon table ----------

describe('generateAppStructure — nav label pluralisation', () => {
  it('does NOT append a second "s" to entity names already ending in "s" (label AND route AND sidebar link AND list-page tab title)', () => {
    const files = generateAppStructure([domain({ entityName: 'News' })], [], cfg());
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    const routes = fileAt(files, 'src/app/app.routes.ts')!;
    // Label stays bare.
    expect(comp.content).toContain('News\n            </a>');
    expect(comp.content).not.toContain('Newss');
    // Route path uses routePlural → no double-s.
    expect(routes.content).toContain("path: 'news'");
    expect(routes.content).toContain("path: 'news/new'");
    expect(routes.content).toContain("path: 'news/:id'");
    expect(routes.content).not.toContain('newss');
    // Sidebar router-link target stays in sync with the route path.
    expect(comp.content).toContain('routerLink="/news"');
    expect(comp.content).not.toContain('routerLink="/newss"');
    // And the default redirect for the first domain follows the
    // same plural rule.
    expect(routes.content).toContain("redirectTo: 'news'");
    // The list-page browser-tab title also uses labelPlural — the
    // /new and /:id titles use the bare singular "News" and stay
    // unaffected, but the list page would otherwise render
    // "Newss - Exeris Foundation" in the tab + history.
    expect(routes.content).toContain("title: 'News - Exeris Foundation'");
    expect(routes.content).toContain("title: 'New News - Exeris Foundation'");
    expect(routes.content).toContain("title: 'Edit News - Exeris Foundation'");
    expect(routes.content).not.toContain('Newss');
  });

  it('appends "s" to entity names not already plural', () => {
    const files = generateAppStructure([domain({ entityName: 'Order' })], [], cfg());
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    expect(comp.content).toContain('Orders\n            </a>');
  });
});

describe('generateAppStructure — getEntityIcon lookup', () => {
  // The icon table is private to the module; we exercise it through
  // generateAppStructure rather than exporting it. One assertion per
  // known key + one for the default fallback.
  it.each([
    ['Tenant', '🏢'],
    ['User', '👥'],
    ['Product', '📦'],
    ['Order', '📋'],
    ['Customer', '👤'],
    ['Invoice', '📄'],
    ['Payment', '💳'],
  ] as const)('uses %s → %s', (entityName, icon) => {
    const files = generateAppStructure([domain({ entityName })], [], cfg());
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    expect(comp.content).toContain(icon);
  });

  it('falls back to 📁 for entity names not in the lookup table', () => {
    const files = generateAppStructure([domain({ entityName: 'Widget' })], [], cfg());
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    expect(comp.content).toContain('📁');
  });
});

// ---------- resolveApiSettings branches ----------

describe('generateAppStructure — resolveApiSettings', () => {
  it('honours config.apiBasePath when set (truthy branch)', () => {
    const files = generateAppStructure([], [], cfg({ apiBasePath: '/custom-base' }));
    const env = fileAt(files, 'src/environments/environment.ts')!;
    expect(env.content).toContain("apiUrl: '/custom-base'");
  });

  it('falls back to strategy.getClientConfig().baseUrl when apiBasePath is empty', () => {
    // KERNEL strategy baseUrl = '/api'.
    const files = generateAppStructure([], [], cfg({ apiBasePath: '' }));
    const env = fileAt(files, 'src/environments/environment.ts')!;
    expect(env.content).toContain("apiUrl: '/api'");
  });

  it('always pulls apiVersion from the strategy (KERNEL → "v1")', () => {
    const files = generateAppStructure([], [], cfg());
    const env = fileAt(files, 'src/environments/environment.ts')!;
    expect(env.content).toContain("apiVersion: 'v1'");
  });
});

// ---------- hidden-domain skip-skip ----------

describe('generateAppStructure — hidden-domain handling', () => {
  it('scaffold-only: emits no per-domain components/services/types/schemas (the orchestrator owns those + skips hidden domains)', () => {
    const files = generateAppStructure(
      [domain({ entityName: 'Order', internalApi: { hidden: true, readOnly: false, internal: false } })],
      [],
      cfg(),
    );
    // T20: generateAppStructure no longer emits any per-entity artefact — neither
    // for visible nor hidden domains. The per-entity tree (and the hidden-domain
    // skip) lives in the orchestrator's buildGeneratedFiles, covered in orchestrator.spec.
    expect(fileAt(files, 'src/app/components/order-form.component.ts')).toBeUndefined();
    expect(fileAt(files, 'src/app/components/order-list.component.ts')).toBeUndefined();
    expect(fileAt(files, 'src/app/services/order.service.ts')).toBeUndefined();
    expect(fileAt(files, 'src/app/types/order.types.ts')).toBeUndefined();
    expect(fileAt(files, 'src/app/schemas/order.schema.ts')).toBeUndefined();
  });

  it('barrel + nav sidebar + routes ALL skip hidden domains (would otherwise be dead import paths / broken router links)', () => {
    const files = generateAppStructure(
      [
        domain({ entityName: 'Order' }),
        domain({ entityName: 'InternalLedger', internalApi: { hidden: true, readOnly: false, internal: false } }),
      ],
      [],
      cfg(),
    );
    const barrel = fileAt(files, 'src/app/index.ts')!;
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    const routes = fileAt(files, 'src/app/app.routes.ts')!;

    // Barrel — non-hidden Order is fully exported (types / schema /
    // service / components); hidden InternalLedger is fully absent.
    // The barrel re-exports the src/app tree the orchestrator emits;
    // it honours `hidden` so the public API surface never points at
    // an entity whose files were never written.
    expect(barrel.content).toContain("from './types/order.types';");
    expect(barrel.content).toContain("from './schemas/order.schema';");
    expect(barrel.content).toContain("from './services/order.service';");
    expect(barrel.content).toContain("from './components/order-form.component';");
    expect(barrel.content).toContain("from './components/order-list.component';");
    expect(barrel.content).not.toContain('internal-ledger.types');
    expect(barrel.content).not.toContain('internal-ledger.schema');
    expect(barrel.content).not.toContain('internal-ledger.service');
    expect(barrel.content).not.toContain('internal-ledger-form');
    expect(barrel.content).not.toContain('internal-ledger-list');
    expect(barrel.content).not.toContain('InternalLedger');
    // Belt-and-braces: with one visible domain remaining,
    // Page+PageRequest still re-exports exactly once (from Order)
    // — the de-duplication counter walks the FILTERED list now.
    const matches = barrel.content.match(/PageRequest/g) ?? [];
    expect(matches).toHaveLength(1);

    // Sidebar nav — Order link is present; InternalLedger has NO
    // router-link emitted (would otherwise point at a route whose
    // loadComponent target was never written to disk → runtime
    // crash on first click).
    expect(comp.content).toContain('routerLink="/orders"');
    expect(comp.content).not.toContain('routerLink="/internal-ledgers"');
    expect(comp.content).not.toContain('InternalLedger');

    // Routes — Order's list/new/:id triple is present;
    // InternalLedger has NO route entry (path or loadComponent).
    expect(routes.content).toContain("path: 'orders'");
    expect(routes.content).toContain('OrderListComponent');
    expect(routes.content).not.toContain("path: 'internal-ledgers'");
    expect(routes.content).not.toContain('InternalLedgerListComponent');
    expect(routes.content).not.toContain('InternalLedgerFormComponent');
    expect(routes.content).not.toContain('internal-ledger-list.component');
    expect(routes.content).not.toContain('internal-ledger-form.component');
  });

  it('ALL-hidden domain set: barrel + nav + routes degrade to the empty-domain shape (no per-entity emit, no broken redirect)', () => {
    const files = generateAppStructure(
      [domain({ entityName: 'InternalLedger', internalApi: { hidden: true, readOnly: false, internal: false } })],
      [],
      cfg(),
    );
    const barrel = fileAt(files, 'src/app/index.ts')!;
    const comp = fileAt(files, 'src/app/app.component.ts')!;
    const routes = fileAt(files, 'src/app/app.routes.ts')!;

    // Barrel: same shape as the empty-domains case — enums + section
    // headers, no per-entity exports.
    expect(barrel.content).toContain("export * from './types/enums';");
    expect(barrel.content).toContain('// Services (export service classes and pagination types)');
    expect(barrel.content).not.toContain('PageRequest');
    expect(barrel.content).not.toContain('InternalLedger');

    // Nav: no sidebar links at all.
    expect(comp.content).not.toContain('routerLink="/');
    expect(comp.content).not.toContain('InternalLedger');

    // Routes: redirectTo degrades to '' (no first-visible-domain
    // default), and no list/new/:id triple is emitted. Equivalent
    // to passing [] to generateAppStructure.
    expect(routes.content).toContain("redirectTo: ''");
    expect(routes.content).not.toContain('internal-ledger');
    expect(routes.content).not.toContain('InternalLedger');
  });
});
