/**
 * Backend-less emission: an app whose metadata has no visible entity — only `@View` pages with
 * authored content — gets a scaffold without the HTTP wiring nothing in it uses. An app with a
 * visible entity gets the scaffold `generateAppStructure` emits on its own, unchanged.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles, type EnumMetadataForGen } from '../src/orchestrator.js';
import { generateAppStructure } from '../src/generators/angular/app-structure-gen.js';
import {
  DomainMetadataSchema,
  ViewMetadataSchema,
  type DomainMetadata,
  type ViewMetadata,
} from '../src/models/domain-model.js';
import { DEFAULT_CONFIG } from '../src/config.js';

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

function view(overrides: Partial<ViewMetadata> & { name: string }): ViewMetadata {
  return ViewMetadataSchema.parse(overrides);
}

const STATIC_HOME = view({
  name: 'Home',
  title: 'Home',
  regions: [{ slot: 'main', components: [{ type: 'HERO', binding: { source: 'STATIC' }, props: 'Welcome' }] }],
});

const TIER: EnumMetadataForGen = {
  name: 'Tier',
  qualifiedName: 'com.shop.Tier',
  packageName: 'com.shop',
  values: [{ name: 'GOLD', displayName: 'Gold', ordinal: 0 }],
};

const content = (files: Array<{ path: string; content: string }>, path: string): string => {
  const f = files.find((x) => x.path === path);
  expect(f, `missing ${path}`).toBeDefined();
  return f!.content;
};

const paths = (files: Array<{ path: string }>) => files.map((f) => f.path);

describe('backend-less emission — a view-only app', () => {
  const files = buildGeneratedFiles([], [], DEFAULT_CONFIG, [STATIC_HOME]);

  it('emits exactly the shell, the view page and its route', () => {
    expect([...paths(files)].sort()).toEqual([
      '.postcssrc.json',
      'angular.json',
      'package.json',
      'src/app/app.component.ts',
      'src/app/app.config.ts',
      'src/app/app.routes.ts',
      'src/app/pages/home.component.ts',
      'src/app/pages/home.route.ts',
      'src/environments/environment.development.ts',
      'src/environments/environment.ts',
      'src/favicon.ico',
      'src/index.html',
      'src/main.ts',
      'src/styles.css',
      'tsconfig.app.json',
      'tsconfig.json',
    ]);
  });

  it('app.config.ts keeps the router and zoneless change detection, and provides no HttpClient', () => {
    const config = content(files, 'src/app/app.config.ts');
    expect(config).toContain('provideZonelessChangeDetection()');
    expect(config).toContain('provideRouter(routes, withComponentInputBinding())');
    expect(config).not.toContain('provideHttpClient');
    expect(config).not.toContain('@angular/common/http');
  });

  it('emits no dev-server proxy and starts without one', () => {
    expect(paths(files)).not.toContain('proxy.conf.js');
    const pkg = JSON.parse(content(files, 'package.json'));
    expect(pkg.scripts.start).toBe('ng serve');
  });

  it('the environments carry no API entries', () => {
    for (const env of ['src/environments/environment.ts', 'src/environments/environment.development.ts']) {
      const text = content(files, env);
      expect(text).toContain('production:');
      expect(text).not.toContain('apiUrl');
      expect(text).not.toContain('apiVersion');
    }
  });

  it('package.json drops zod, @angular/cdk and @angular/forms, and keeps what Angular itself needs', () => {
    const deps = Object.keys(JSON.parse(content(files, 'package.json')).dependencies);
    expect(deps).toEqual([
      '@angular/common',
      '@angular/compiler',
      '@angular/core',
      '@angular/platform-browser',
      '@angular/router',
      '@exeris/ui-kit',
      'rxjs',
      'tslib',
    ]);
  });

  it('emits no empty type, schema or app barrel', () => {
    expect(paths(files).some((p) => p.startsWith('src/app/types/'))).toBe(false);
    expect(paths(files).some((p) => p.startsWith('src/app/schemas/'))).toBe(false);
    expect(paths(files)).not.toContain('src/app/index.ts');
  });

  it('routes the view and redirects to it', () => {
    const routes = content(files, 'src/app/app.routes.ts');
    expect(routes).toContain("import { homeRoutes } from './pages/home.route';");
    expect(routes).toContain('...homeRoutes,');
    expect(routes).toContain("redirectTo: 'home'");
  });

  it('is deterministic: two runs are byte-identical', () => {
    expect(buildGeneratedFiles([], [], DEFAULT_CONFIG, [STATIC_HOME])).toEqual(files);
  });
});

describe('backend-less emission — what the emitted files use is still provided', () => {
  it('an enum emitted with its Zod schema keeps zod, the enum module and a barrel that re-exports it', () => {
    const files = buildGeneratedFiles([], [TIER], DEFAULT_CONFIG, [STATIC_HOME]);
    expect(JSON.parse(content(files, 'package.json')).dependencies.zod).toBe('^3.24.0');
    expect(content(files, 'src/app/types/enums.ts')).toContain("import { z } from 'zod';");
    expect(content(files, 'src/app/index.ts')).toContain("export * from './types/enums';");
    expect(paths(files)).not.toContain('src/app/schemas/index.ts');
    // Still no backend.
    expect(content(files, 'src/app/app.config.ts')).not.toContain('provideHttpClient');
  });

  it('the same enum under --no-zod drops zod again', () => {
    const files = buildGeneratedFiles([], [TIER], { ...DEFAULT_CONFIG, generateZod: false }, [STATIC_HOME]);
    expect(JSON.parse(content(files, 'package.json')).dependencies.zod).toBeUndefined();
  });

  it('a peer contract keeps zod for its schemas', () => {
    const peer = { name: 'billing', domains: [domain({ entityName: 'Invoice', fields: [{ name: 'id', type: 'java.util.UUID' }] })], enums: [] };
    const files = buildGeneratedFiles([], [], DEFAULT_CONFIG, [STATIC_HOME], [peer]);
    expect(JSON.parse(content(files, 'package.json')).dependencies.zod).toBe('^3.24.0');
  });
});

describe('backend-less emission — an app with neither entity nor view', () => {
  const files = buildGeneratedFiles([], [], DEFAULT_CONFIG);

  it('emits the bare shell: no HTTP wiring, no proxy, no barrels', () => {
    expect(paths(files)).not.toContain('proxy.conf.js');
    expect(paths(files)).not.toContain('src/app/index.ts');
    expect(paths(files).some((p) => p.startsWith('src/app/types/'))).toBe(false);
    expect(content(files, 'src/app/app.config.ts')).not.toContain('provideHttpClient');
    expect(content(files, 'src/app/app.config.ts')).toContain('provideZonelessChangeDetection()');
  });

  it('emits an empty route table with no self-redirect', () => {
    const routes = content(files, 'src/app/app.routes.ts');
    expect(routes).not.toContain('redirectTo');
    expect(routes).toContain('export const routes: Routes = [\n];');
  });
});

describe('backend-less emission — an app with a visible entity is unchanged', () => {
  const order = domain({ entityName: 'Order', fields: [{ name: 'id', type: 'java.util.UUID' }] });
  const scaffoldPaths = new Set(paths(generateAppStructure([order], [], DEFAULT_CONFIG)));

  it.each([
    ['default config', DEFAULT_CONFIG, [] as ViewMetadata[]],
    ['default config with a view', DEFAULT_CONFIG, [STATIC_HOME]],
    ['--no-zod', { ...DEFAULT_CONFIG, generateZod: false }, [] as ViewMetadata[]],
    [
      'every client emitter off (the consumer calls the API itself)',
      {
        ...DEFAULT_CONFIG,
        generateServices: false,
        generateStores: false,
        generateForms: false,
        generateLists: false,
        generateDetails: false,
        generateSagas: false,
        generateEvents: false,
      },
      [] as ViewMetadata[],
    ],
  ] as const)('%s: the scaffold is the backend scaffold, byte for byte', (_label, config, views) => {
    const composed = buildGeneratedFiles([order], [], config, [...views]).filter((f) => scaffoldPaths.has(f.path));
    const standalone = generateAppStructure([order], [], config, [...views]);
    expect(composed).toEqual(standalone);
    expect(content(composed, 'src/app/app.config.ts')).toContain('provideHttpClient()');
    expect(paths(composed)).toContain('proxy.conf.js');
  });
});
