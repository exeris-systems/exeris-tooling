/**
 * `scaffold: false` — the generated tree for an Angular app the consumer already owns.
 *
 * Pinned: no project-root or app-shell file is emitted; the tree is written at the output root;
 * the barrel and the view routes (as one `viewRoutes` array) take the shell's place; and the
 * default, `scaffold: true`, emits neither the aggregate nor a root-level tree.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../src/orchestrator.js';
import { DEFAULT_CONFIG, initConfig, type GeneratorConfig } from '../src/config.js';
import { DomainMetadataSchema, ViewMetadataSchema, type ViewMetadata } from '../src/models/domain-model.js';

const order = DomainMetadataSchema.parse({
  packageName: 'com.shop',
  entityName: 'Order',
  fields: [{ name: 'id', type: 'java.util.UUID' }],
});

function view(name: string, route?: string): ViewMetadata {
  return ViewMetadataSchema.parse({
    name,
    ...(route === undefined ? {} : { route }),
    regions: [{ slot: 'main', components: [{ type: 'HERO', binding: { source: 'STATIC' }, props: 'Hi' }] }],
  });
}

const scaffoldOff: GeneratorConfig = { ...DEFAULT_CONFIG, scaffold: false };

/** Every file the scaffold emits, by its path in a scaffolded tree. */
const SCAFFOLD_PATHS = [
  'package.json',
  'angular.json',
  'tsconfig.json',
  'tsconfig.app.json',
  'tsconfig.spec.json',
  '.postcssrc.json',
  'proxy.conf.js',
  'src/styles.css',
  'src/index.html',
  'src/favicon.ico',
  'src/main.ts',
  'src/environments/environment.ts',
  'src/environments/environment.development.ts',
  'src/app/app.config.ts',
  'src/app/app.component.ts',
  'src/app/app.routes.ts',
];

/** The scaffold's files under the paths they would take at the root of an unscaffolded tree. */
const SHELL_AT_ROOT = ['app.config.ts', 'app.component.ts', 'app.routes.ts', 'main.ts', 'styles.css', 'index.html'];

describe('scaffold off', () => {
  const files = buildGeneratedFiles([order], [], { ...scaffoldOff, generateTests: true }, [view('About', 'about'), view('Home', '')]);
  const paths = files.map((f) => f.path);

  it('emits no project-root or app-shell file', () => {
    expect(paths.filter((p) => SCAFFOLD_PATHS.includes(p) || SHELL_AT_ROOT.includes(p))).toEqual([]);
  });

  it('writes the tree at the output root', () => {
    expect(paths.filter((p) => p.startsWith('src/'))).toEqual([]);
    expect(paths).toContain('pages/about.component.ts');
    expect(paths).toContain('pages/about.route.ts');
    expect(paths).toContain('types/order.types.ts');
    expect(paths).toContain('services/order.service.ts');
    expect(paths).toContain('index.ts');
  });

  it('exports every view route as one array, ordered by route path', () => {
    const aggregate = files.find((f) => f.path === 'view.routes.ts')?.content;
    expect(aggregate).toBeDefined();
    expect(aggregate).toContain("import { Routes } from '@angular/router';");
    expect(aggregate).toContain("import { aboutRoutes } from './pages/about.route';");
    expect(aggregate).toContain("import { homeRoutes } from './pages/home.route';");
    expect(aggregate).toContain('export const viewRoutes: Routes = [\n  ...homeRoutes,\n  ...aboutRoutes,\n];\n');
    expect(aggregate).not.toContain('redirectTo');
  });

  it('emits the same aggregate whatever order the views arrive in', () => {
    const reversed = buildGeneratedFiles([order], [], scaffoldOff, [view('Home', ''), view('About', 'about')]);
    expect(reversed.find((f) => f.path === 'view.routes.ts')?.content)
      .toBe(files.find((f) => f.path === 'view.routes.ts')?.content);
  });

  it('emits no aggregate when there is no view', () => {
    const noViews = buildGeneratedFiles([order], [], scaffoldOff, []);
    expect(noViews.some((f) => f.path === 'view.routes.ts')).toBe(false);
  });

  it('emits only the pages and the aggregate for a views-only run', () => {
    const viewsOnly = buildGeneratedFiles([], [], scaffoldOff, [view('About', 'about')]);
    expect(viewsOnly.map((f) => f.path).sort()).toEqual([
      'pages/about.component.ts',
      'pages/about.route.ts',
      'view.routes.ts',
    ]);
  });
});

describe('the views-only init preset', () => {
  it('emits only the pages, their routes and the aggregate', () => {
    const files = buildGeneratedFiles([], [], initConfig({ viewsOnly: true }), [view('About', 'about'), view('Home', '')]);
    expect(files.map((f) => f.path).sort()).toEqual([
      'pages/about.component.ts',
      'pages/about.route.ts',
      'pages/home.component.ts',
      'pages/home.route.ts',
      'view.routes.ts',
    ]);
  });
});

describe('scaffold on (the default)', () => {
  it('defaults to true', () => {
    expect(DEFAULT_CONFIG.scaffold).toBe(true);
  });

  it('emits the app shell, the tree under src/app, and no aggregate', () => {
    const files = buildGeneratedFiles([order], [], DEFAULT_CONFIG, [view('About', 'about')]);
    const paths = files.map((f) => f.path);
    expect(paths).toContain('package.json');
    expect(paths).toContain('src/app/app.routes.ts');
    expect(paths).toContain('src/app/pages/about.route.ts');
    expect(paths).toContain('src/app/index.ts');
    expect(paths.some((p) => p.endsWith('view.routes.ts'))).toBe(false);
  });
});
