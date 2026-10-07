/**
 * `render: 'ssg'` — the static prerender setup the scaffold carries on request.
 *
 * Pinned: the default (`csr`) emits no server file and leaves every file as an explicit `csr`
 * run writes it; `ssg` adds three seed files (the server entry, the server configuration and the
 * server routes) and changes exactly four seeds (package.json, angular.json, tsconfig.app.json,
 * app.config.ts); the server routes prerender the param-less @View pages that read no entity data
 * and the `''` redirect, and leave every other route to the browser; without the scaffold `ssg`
 * emits what `csr` does. The output is order-independent and repeatable.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles, SEED_PATHS, type OutputFile } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG, GeneratorConfigSchema, type GeneratorConfig } from '../../../src/config.js';
import {
  DomainMetadataSchema,
  ViewMetadataSchema,
  type DomainMetadata,
  type ViewMetadata,
} from '../../../src/models/domain-model.js';

const SERVER_FILES = ['src/main.server.ts', 'src/app/app.config.server.ts', 'src/app/app.routes.server.ts'];
const SSG_CHANGED = ['package.json', 'angular.json', 'tsconfig.app.json', 'src/app/app.config.ts'];

const cfg = (overrides: Partial<GeneratorConfig> = {}): GeneratorConfig => ({ ...DEFAULT_CONFIG, ...overrides });

function view(overrides: Record<string, unknown> & { name: string }): ViewMetadata {
  return ViewMetadataSchema.parse(overrides);
}

function domain(overrides: Record<string, unknown> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({
    packageName: 'com.shop',
    fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }],
    ...overrides,
  });
}

const authored = (props: string) => [{ components: [{ type: 'HERO', binding: { source: 'STATIC' }, props }] }];

const views: ViewMetadata[] = [
  view({ name: 'Home', title: 'Welcome', regions: authored('Hello') }),
  view({ name: 'About', route: '/about-us', regions: authored('About') }),
  view({ name: 'Footer', kind: 'SECTION', regions: authored('Links') }),
  view({ name: 'Post', route: 'posts/:slug', regions: authored('A post') }),
  view({ name: 'Docs', route: 'docs/**', regions: authored('Docs') }),
  view({
    name: 'Catalogue',
    regions: [{ components: [{ type: 'LIST', binding: { source: 'ENTITY', ref: 'Product' } }] }],
  }),
];

function byPath(files: OutputFile[]): Map<string, OutputFile> {
  return new Map(files.map((f) => [f.path, f]));
}

function content(files: OutputFile[], path: string): string {
  const file = files.find((f) => f.path === path);
  if (!file) throw new Error(`not emitted: ${path}`);
  return file.content;
}

describe("render: 'csr' (the default)", () => {
  it('is the schema default and the DEFAULT_CONFIG value', () => {
    expect(GeneratorConfigSchema.parse({}).render).toBe('csr');
    expect(DEFAULT_CONFIG.render).toBe('csr');
  });

  it('rejects a mode it does not know', () => {
    expect(() => GeneratorConfigSchema.parse({ render: 'ssr' })).toThrow();
  });

  it('emits no server file and no server wiring', () => {
    const files = buildGeneratedFiles([domain({ entityName: 'Product' })], [], cfg(), views);
    const paths = files.map((f) => f.path);
    for (const path of SERVER_FILES) expect(paths).not.toContain(path);
    expect(content(files, 'package.json')).not.toContain('@angular/ssr');
    expect(content(files, 'package.json')).not.toContain('@angular/platform-server');
    expect(content(files, 'angular.json')).not.toContain('outputMode');
    expect(content(files, 'angular.json')).not.toContain('"server"');
    expect(content(files, 'tsconfig.app.json')).toContain('"files": ["src/main.ts"],');
    expect(content(files, 'src/app/app.config.ts')).not.toContain('provideClientHydration');
  });
});

describe("render: 'ssg' — what changes", () => {
  const domains = [domain({ entityName: 'Product' })];
  const csr = byPath(buildGeneratedFiles(domains, [], cfg(), views));
  const ssg = byPath(buildGeneratedFiles(domains, [], cfg({ render: 'ssg' }), views));

  it('adds the three server files, and changes only the four seeds that carry the setup', () => {
    expect([...ssg.keys()].filter((p) => !csr.has(p)).sort()).toEqual([...SERVER_FILES].sort());
    expect([...csr.keys()].filter((p) => !ssg.has(p))).toEqual([]);
    const changed = [...csr.keys()].filter((p) => csr.get(p)?.content !== ssg.get(p)?.content);
    expect(changed.sort()).toEqual([...SSG_CHANGED].sort());
  });

  it('emits each server file as a seed the writer keeps and releases by path', () => {
    for (const path of SERVER_FILES) {
      expect(ssg.get(path)?.overwritable, path).toBe(false);
      expect(SEED_PATHS.has(path), path).toBe(true);
    }
  });

  it('main.server.ts bootstraps the app shell with the server config and the build context', () => {
    const main = ssg.get('src/main.server.ts')?.content ?? '';
    expect(main).toContain("import { BootstrapContext, bootstrapApplication } from '@angular/platform-browser';");
    expect(main).toContain("import { AppComponent } from './app/app.component';");
    expect(main).toContain("import { config } from './app/app.config.server';");
    expect(main).toContain('const bootstrap = (context: BootstrapContext) => bootstrapApplication(AppComponent, config, context);');
    expect(main).toContain('export default bootstrap;');
  });

  it('app.config.server.ts merges server rendering, bound to the server routes, into the browser config', () => {
    const server = ssg.get('src/app/app.config.server.ts')?.content ?? '';
    expect(server).toContain("import { provideServerRendering, withRoutes } from '@angular/ssr';");
    expect(server).toContain("import { serverRoutes } from './app.routes.server';");
    expect(server).toContain('providers: [provideServerRendering(withRoutes(serverRoutes))],');
    expect(server).toContain('export const config = mergeApplicationConfig(appConfig, serverConfig);');
    expect(server).not.toContain('provideServerRoutesConfig');
  });

  it('app.config.ts hydrates the prerendered pages, zoneless as before', () => {
    const app = ssg.get('src/app/app.config.ts')?.content ?? '';
    expect(app).toContain("import { provideClientHydration } from '@angular/platform-browser';");
    expect(app).toContain('    provideClientHydration(),');
    expect(app).toContain('provideZonelessChangeDetection(),');
  });

  it('angular.json builds static output from the server entry, with no server bundle to deploy', () => {
    const angular = JSON.parse(ssg.get('angular.json')?.content ?? '{}');
    const options = angular.projects['exeris-foundation-frontend'].architect.build.options;
    expect(options.browser).toBe('src/main.ts');
    expect(options.server).toBe('src/main.server.ts');
    expect(options.outputMode).toBe('static');
    expect(options.ssr).toBeUndefined();
    expect(options.prerender).toBeUndefined();
  });

  it('package.json adds @angular/ssr and @angular/platform-server on the v22 line, and no server framework', () => {
    const pkg = JSON.parse(ssg.get('package.json')?.content ?? '{}');
    expect(pkg.dependencies['@angular/ssr']).toBe('^22.0.0');
    expect(pkg.dependencies['@angular/platform-server']).toBe('^22.0.0');
    expect(pkg.dependencies.express).toBeUndefined();
    expect(Object.keys(pkg.dependencies)).toEqual([...Object.keys(pkg.dependencies)].sort());
  });

  it('tsconfig.app.json compiles the server entry', () => {
    expect(JSON.parse(ssg.get('tsconfig.app.json')?.content ?? '{}').files).toEqual(['src/main.ts', 'src/main.server.ts']);
  });
});

describe("render: 'ssg' — the server routes", () => {
  const routesOf = (domains: DomainMetadata[], vs: ViewMetadata[]): string =>
    content(buildGeneratedFiles(domains, [], cfg({ render: 'ssg' }), vs), 'src/app/app.routes.server.ts');

  it('prerenders the redirect and every param-less page that reads no entity data, then leaves the rest to the browser', () => {
    const routes = routesOf([domain({ entityName: 'Product' })], views);
    expect(routes).toContain("import { RenderMode, ServerRoute } from '@angular/ssr';");
    expect(routes).toContain(`export const serverRoutes: ServerRoute[] = [
  { path: '', renderMode: RenderMode.Prerender },
  { path: 'about-us', renderMode: RenderMode.Prerender },
  { path: 'footer', renderMode: RenderMode.Prerender },
  { path: 'home', renderMode: RenderMode.Prerender },
  { path: '**', renderMode: RenderMode.Client },
];
`);
  });

  it('names no parameterised, wildcard or entity-bound page, and no entity CRUD route', () => {
    const routes = routesOf([domain({ entityName: 'Product' })], views);
    for (const path of ['posts/:slug', 'docs/**', 'catalogue', 'products', 'products/new', 'products/:id']) {
      expect(routes).not.toContain(`path: '${path}'`);
    }
  });

  it('prerenders the redirect to an entity list as a redirect page, the list itself left to the browser', () => {
    expect(routesOf([domain({ entityName: 'Product' })], [])).toContain(`[
  { path: '', renderMode: RenderMode.Prerender },
  { path: '**', renderMode: RenderMode.Client },
];`);
  });

  it('names no redirect when the app has no destination to redirect to', () => {
    const routes = routesOf([], [view({ name: 'Footer', kind: 'SECTION', regions: authored('Links') })]);
    expect(routes).toContain(`[
  { path: 'footer', renderMode: RenderMode.Prerender },
  { path: '**', renderMode: RenderMode.Client },
];`);
  });

  it("lists a page routed at '' once", () => {
    const routes = routesOf([], [view({ name: 'Root', route: '/', regions: authored('Root') })]);
    expect(routes.match(/path: '',/g)).toHaveLength(1);
  });

  it('escapes a quote in a route path', () => {
    expect(routesOf([], [view({ name: 'Odd', route: "it's", regions: authored('x') })])).toContain(
      "{ path: 'it\\'s', renderMode: RenderMode.Prerender },",
    );
  });
});

describe("render: 'ssg' — determinism and scope", () => {
  it('is repeatable and independent of the order the views arrive in', () => {
    const domains = [domain({ entityName: 'Product' })];
    const first = buildGeneratedFiles(domains, [], cfg({ render: 'ssg' }), views);
    const again = buildGeneratedFiles(domains, [], cfg({ render: 'ssg' }), views);
    const reversed = buildGeneratedFiles(domains, [], cfg({ render: 'ssg' }), [...views].reverse());
    expect(again).toEqual(first);
    const scaffold = (files: OutputFile[]) => files.filter((f) => !f.path.startsWith('src/app/pages/'));
    expect(scaffold(reversed)).toEqual(scaffold(first));
  });

  it('changes nothing without the scaffold: the consumer owns its server setup', () => {
    const domains = [domain({ entityName: 'Product' })];
    expect(buildGeneratedFiles(domains, [], cfg({ scaffold: false, render: 'ssg' }), views)).toEqual(
      buildGeneratedFiles(domains, [], cfg({ scaffold: false }), views),
    );
  });
});
