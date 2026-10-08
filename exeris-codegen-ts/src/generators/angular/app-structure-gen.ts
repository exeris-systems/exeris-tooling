/**
 * Angular App Structure Generator
 * Generates complete Angular application structure including:
 * - app.routes.ts
 * - app.component.ts
 * - app.config.ts
 * - main.ts
 * - package.json
 * - angular.json
 * - tsconfig.json
 * - .postcssrc.json and src/styles.css (Tailwind CSS v4, CSS-first: no tailwind.config.js)
 * - under `render: 'ssg'`: main.server.ts, app.config.server.ts and app.routes.server.ts
 */

import type { DomainMetadata, ViewMetadata } from '../../models/domain-model.js';
import type { GeneratorConfig } from '../../config.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import { modelTypeName } from '../../models/model-naming.js';
import {
  viewRoutePath,
  viewRouteConstName,
  viewRouteImportPath,
  isPageView,
  viewReadsEntityData,
} from './view-gen.js';
import { tsSingleQuoted } from './ts-literal.js';
import { sagaMachineName } from './saga-gen.js';
import { hasLiveViewClient } from './stream-client-gen.js';
import { hasActionStreamClients } from './action-stream-client-gen.js';
import { BACKEND_SCAFFOLD_NEEDS, type ScaffoldNeeds } from '../../core/scaffold-needs.js';
import { entityViews, hasFormPage } from './entity-views.js';
import { serviceApiPath } from './service-gen.js';
import { fileHeader, lineHeaderLines } from '../file-header.js';

export interface GeneratedFile {
  path: string;
  content: string;
  overwritable?: boolean;
}

// User-supplied appName lands in three emitted contexts; each needs its own
// escaping so a name like `Foo "Bar"` or `A`B` can't break the generated artefact.
/** JSON string value (emits its own surrounding quotes). */
function jsonValue(s: string): string {
  return JSON.stringify(s);
}
/** HTML text content (index.html title). */
function htmlText(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
/** HTML text that sits inside an emitted TS backtick template (both layers). */
function htmlInTemplate(s: string): string {
  return htmlText(s).replace(/\\/g, '\\\\').replace(/`/g, '\\`').replace(/\$\{/g, '\\${');
}
/**
 * Project slug derived from the app name — the single source of truth for the
 * package.json `name` and the angular.json project key / dist path, so the two
 * never disagree (a CI step locating the artefact by `package.json.name` then
 * always finds `dist/<slug>`).
 */
function frontendSlug(appName: string): string {
  return `${DslMapper.toKebabCase(appName.replace(/\s+/g, '-'))}-frontend`;
}

interface EnumMetadata {
  name: string;
  qualifiedName: string;
}

/**
 * The scaffold's seed files: written when absent and kept once they exist, because the consumer
 * edits them (dependencies, builder options, providers, routes, styles, the page shell, the dev
 * proxy, environments). Each is emitted with `overwritable: false`, and an orphaned one is dropped
 * from the manifest rather than deleted (output/writer.ts).
 *
 * `.postcssrc.json` is a seed too: its content never depends on the metadata, so rewriting it could
 * only undo a PostCSS plugin the consumer added. `favicon.ico` is not: nothing in it is the
 * consumer's to edit. Paths are in the scaffold's layout, as the manifest records them.
 *
 * The three server files of `render: 'ssg'` are seeds for the same reason as `app.routes.ts`: the
 * server routes are where a consumer adds `getPrerenderParams` to prerender a parameterised route.
 */
export const SCAFFOLD_SEED_PATHS: readonly string[] = [
  'package.json',
  'angular.json',
  'tsconfig.json',
  'tsconfig.app.json',
  'tsconfig.spec.json',
  '.postcssrc.json',
  'proxy.conf.js',
  'src/styles.css',
  'src/index.html',
  'src/main.ts',
  'src/environments/environment.ts',
  'src/environments/environment.development.ts',
  'src/app/app.config.ts',
  'src/app/app.component.ts',
  'src/app/app.routes.ts',
  'src/main.server.ts',
  'src/app/app.config.server.ts',
  'src/app/app.routes.server.ts',
];

/**
 * Paths the scaffold does not emit that an existing app may hold as an owned seed, edited
 * or not. The writer releases a seed the run does not produce and deletes any other owned orphan, so a
 * path stays here for as long as a manifest can own it: dropping one deletes the consumer's file.
 */
export const RETIRED_SCAFFOLD_SEED_PATHS: readonly string[] = ['proxy.conf.json'];

export function generateAppStructure(
  domains: DomainMetadata[],
  enums: EnumMetadata[],
  config: GeneratorConfig,
  // Presentation-IR views (RFC-2026-06-28 §5 route-assembly). Optional + defaults
  // to none so existing 3-arg callers stay valid AND so a zero-view build emits
  // app.routes.ts / app.component.ts byte-identical to before this wiring landed.
  views: ViewMetadata[] = [],
  // What the emitted tree uses (core/scaffold-needs). Defaults to an app with a backend, the
  // scaffold this function emits on its own; the orchestrator passes what it composed.
  needs: ScaffoldNeeds = BACKEND_SCAFFOLD_NEEDS
): GeneratedFile[] {
  const files: GeneratedFile[] = [];
  // Output roots. Project-root files carry no directory prefix: every path is in the
  // canonical relative form the generated-output manifest records.
  const srcRoot = 'src';
  const appRoot = 'src/app';
  const envRoot = 'src/environments';
  // The API environment entries exist only for code that calls the API.
  const api = needs.backend ? resolveApiSettings(config) : null;
  const appName = config.appName;
  const ssg = config.render === 'ssg';

  // Config files at the project root
  files.push({ path: 'package.json', content: generatePackageJson(appName, config, needs), overwritable: false });
  files.push({ path: 'angular.json', content: generateAngularJson(appName, config, ssg), overwritable: false });
  files.push({ path: 'tsconfig.json', content: generateTsConfig(), overwritable: false });
  files.push({ path: 'tsconfig.app.json', content: generateTsConfigApp(config, ssg), overwritable: false });
  // T2 (ADR-058): the spec tsconfig only exists when specs do. It is the counterpart of the Java
  // half's second output root — specs compile under their own config, never the app's.
  if (config.generateTests) {
    files.push({ path: 'tsconfig.spec.json', content: generateTsConfigSpec(), overwritable: false });
  }
  files.push({ path: '.postcssrc.json', content: generatePostcssConfig(), overwritable: false });
  if (needs.backend) {
    files.push({ path: 'proxy.conf.js', content: generateProxyConfig(domains, config), overwritable: false });
  }

  // Static files under src/
  files.push({ path: `${srcRoot}/styles.css`, content: generateStylesCss(needs.typography), overwritable: false });
  files.push({ path: `${srcRoot}/index.html`, content: generateIndexHtml(appName), overwritable: false });
  files.push({ path: `${srcRoot}/favicon.ico`, content: generateFavicon(), overwritable: true });
  // main.ts under src/
  files.push({ path: `${srcRoot}/main.ts`, content: generateMainTs(), overwritable: false });
  if (ssg) {
    files.push({ path: `${srcRoot}/main.server.ts`, content: generateMainServerTs(), overwritable: false });
  }

  // Environment files under src/environments
  files.push({ path: `${envRoot}/environment.ts`, content: generateEnvironmentFile({ production: true, api }), overwritable: false });
  files.push({ path: `${envRoot}/environment.development.ts`, content: generateEnvironmentFile({ production: false, api }), overwritable: false });

  // Views are sorted deterministically (by effective route path, then name) so the
  // emitted route imports/spreads + nav links are order-stable regardless of the
  // directory-scan order the CLI hands them in. The sort is on a copy — the caller's
  // array is left untouched.
  const sortedViews = sortViews(views);

  // App shell under src/app
  files.push({ path: `${appRoot}/app.config.ts`, content: generateAppConfig(needs.backend, ssg), overwritable: false });
  if (ssg) {
    files.push({ path: `${appRoot}/app.config.server.ts`, content: generateAppConfigServer(), overwritable: false });
    files.push({ path: `${appRoot}/app.routes.server.ts`, content: generateServerRoutes(domains, sortedViews), overwritable: false });
  }
  files.push({ path: `${appRoot}/app.component.ts`, content: generateAppComponent(domains, appName, sortedViews), overwritable: false });
  files.push({ path: `${appRoot}/app.routes.ts`, content: generateAppRoutes(domains, appName, sortedViews), overwritable: false });
  const barrel = generateAppBarrel(domains, enums, config);
  if (barrel) {
    files.push({ ...barrel, path: `${appRoot}/${barrel.path}` });
  }

  // T20: per-entity components/services/types/schemas and enums are emitted by the
  // CLI orchestrator under src/app/ (the real generators), NOT here — this function
  // now emits the scaffold only, so there is exactly one src/app tree and no stub
  // shadowing the real enums/schemas. The app shell above (routes/component/barrel)
  // references those src/app files by their relative paths, which resolve.

  return files;
}

function generateMainTs(): string {
  return `${fileHeader({ title: 'Angular Application Entry Point', doNotEdit: 'omit' })}

import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));
`;
}

function generateAppConfig(backend: boolean, ssg: boolean): string {
  // provideHttpClient() is wired only when the app has an API (core/scaffold-needs); an app
  // without one has no HttpClient consumer to provide for.
  const httpImport = backend ? `\nimport { provideHttpClient } from '@angular/common/http';` : '';
  const httpProvider = backend
    ? `\n    // v22: fetch is the default HttpClient transport (the old explicit opt-in is now redundant).\n    provideHttpClient(),`
    : '';
  // A prerendered page is hydrated: the browser takes over the DOM the build wrote instead of
  // rendering it again, and replays the clicks made on it before hydration finished. A route the
  // server routes leave to the client has no such DOM and renders as in a browser-only app.
  const hydrationImport = ssg
    ? `\nimport { provideClientHydration, withEventReplay } from '@angular/platform-browser';`
    : '';
  const hydrationProvider = ssg ? `\n    provideClientHydration(withEventReplay()),` : '';
  return `${fileHeader({
    title: 'Angular Application Configuration',
    notes: ['', 'Uses Angular 22 Zoneless mode with Signals'],
    doNotEdit: 'omit',
    blank: 'spaced',
  })}

import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';${httpImport}${hydrationImport}

import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    // Zoneless mode - no zone.js needed for change detection
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding()),${httpProvider}${hydrationProvider}
    // Row enter/leave animations are native (animate.enter, Angular 22) — a
    // compiler feature, so no @angular/animations package or provider is needed.
  ],
};
`;
}

/**
 * The server entry `angular.json` names as `server`. The build calls its default export once per
 * prerendered route, with the context that route renders in.
 */
function generateMainServerTs(): string {
  return `${fileHeader({ title: 'Angular Server Entry Point (static prerender)', doNotEdit: 'omit' })}

import { BootstrapContext, bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { config } from './app/app.config.server';

const bootstrap = (context: BootstrapContext) => bootstrapApplication(AppComponent, config, context);

export default bootstrap;
`;
}

/** The browser configuration, plus server rendering bound to the server routes. */
function generateAppConfigServer(): string {
  return `${fileHeader({ title: 'Angular Server Configuration (static prerender)', doNotEdit: 'omit' })}

import { ApplicationConfig, mergeApplicationConfig } from '@angular/core';
import { provideServerRendering, withRoutes } from '@angular/ssr';

import { appConfig } from './app.config';
import { serverRoutes } from './app.routes.server';

const serverConfig: ApplicationConfig = {
  providers: [provideServerRendering(withRoutes(serverRoutes))],
};

export const config = mergeApplicationConfig(appConfig, serverConfig);
`;
}

/**
 * Whether a route path names one page: no `:param` segment and no wildcard. Only such a path can be
 * prerendered without `getPrerenderParams` listing the values of its parameters.
 */
function isStaticRoutePath(path: string): boolean {
  return path.split('/').every((segment) => !segment.startsWith(':') && !segment.includes('*'));
}

/**
 * The render mode of every route, for `outputMode: "static"`, where a route is either written to
 * HTML at build time or rendered in the browser.
 *
 * A `@View` page is prerendered when its path names one page and it reads no entity data: its
 * content is authored, so the HTML the build writes is the page. A page bound to an entity loads it
 * from the kernel API on init, which no build reaches, so it renders in the browser, as does a
 * parameterised page, whose parameter values the metadata does not list. The entity CRUD routes
 * read the API on every page and are left to the browser by the same rule, through the `**` entry,
 * which also covers every route the consumer adds; the browser routes are served from the build's
 * `index.csr.html`.
 *
 * The `''` redirect, when the app has one, is prerendered: the build writes it as the root
 * `index.html`, a page that sends the browser on to the redirect target, so the site root resolves
 * on a host that serves files only. Views arrive in the sorted order the app routes use.
 */
function generateServerRoutes(domains: DomainMetadata[], views: ViewMetadata[]): string {
  const paths = views
    .map(viewRoutePath)
    .filter((path, i) => isStaticRoutePath(path) && !viewReadsEntityData(views[i]))
    .filter((path, i, all) => all.indexOf(path) === i);
  if (defaultRedirectPath(domains, views) !== '' && !paths.includes('')) paths.unshift('');
  const prerendered = paths
    .map((path) => `\n  { path: '${tsSingleQuoted(path)}', renderMode: RenderMode.Prerender },`)
    .join('');
  return `${fileHeader({ title: 'Server Routes (static prerender)', doNotEdit: 'omit' })}

import { RenderMode, ServerRoute } from '@angular/ssr';

export const serverRoutes: ServerRoute[] = [${prerendered}
  { path: '**', renderMode: RenderMode.Client },
];
`;
}

// Two seams need plurals: display labels (sidebar text, browser-tab title) and URL paths.
// Both take DslMapper.pluralName, the SDK's rule; the display side keeps the original
// casing and the route side kebab-cases it.
function labelPlural(entityName: string): string {
  return DslMapper.pluralName(entityName);
}

/** Delegates to the single authority — see DslMapper.routePlural. */
function routePlural(entityName: string): string {
  return DslMapper.routePlural(entityName);
}

/**
 * Deterministic view ordering for the route-assembly seam: primary key is the
 * effective route path, tiebreak on the view name (locale-independent code-unit
 * comparison, never the OS locale — hard-constraint #3). Returns a new array;
 * the input is not mutated.
 */
export function sortViews(views: ViewMetadata[]): ViewMetadata[] {
  return [...views].sort((a, b) => {
    const pa = viewRoutePath(a);
    const pb = viewRoutePath(b);
    if (pa < pb) return -1;
    if (pa > pb) return 1;
    if (a.name < b.name) return -1;
    if (a.name > b.name) return 1;
    return 0;
  });
}

function generateAppComponent(domains: DomainMetadata[], appName: string, views: ViewMetadata[] = []): string {
  // An entity's sidebar link opens its list, so an entity whose @UI switches the list off has none.
  const navItems = domains.filter((d) => entityViews(d).list).map((d) => {
    const icon = getEntityIcon(d.entityName);
    // Sidebar router-link target must match the path generated by
    // generateAppRoutes — both go through routePlural so a `News`
    // entity navigates to /news, not /newss.
    return { path: routePlural(d.entityName), label: labelPlural(d.entityName), icon };
  });

  // A sidebar link per PAGE view (RFC §5), appended after the entity links so the
  // emitted HTML shape + join are unchanged when no PAGE view exists (byte-identical
  // zero-view output). The router-link target is the same effective route path the
  // view's route file registers; non-PAGE views (SECTION/COMPONENT/FRAGMENT) are
  // composed elsewhere and get no top-level nav link.
  for (const v of views) {
    if (isPageView(v)) {
      navItems.push({ path: viewRoutePath(v), label: v.title ?? v.name, icon: '🧩' });
    }
  }

  const navItemsHtml = navItems
    .map(
      // routerLinkActive uses the exeris-primary-hover token for the active
      // label colour (indigo-700 in the ui-kit v4 @theme). The indigo-100/900/200
      // tints have no exeris token in the v4 @theme entry, so they stay as
      // neutral Tailwind utilities.
      (item) => `
            <a routerLink="/${item.path}"
               routerLinkActive="bg-indigo-100 dark:bg-indigo-900 text-exeris-primary-hover dark:text-indigo-200"
               class="group flex items-center px-2 py-2 text-base font-medium rounded-md text-gray-600 hover:bg-gray-50 dark:text-gray-300 dark:hover:bg-gray-700">
              <span class="mr-3 text-xl">${item.icon}</span>
              ${item.label}
            </a>`
    )
    .join('\n');

  return `${fileHeader({ title: 'Root Application Component', doNotEdit: 'omit' })}

import { Component } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  template: \`
    <div class="min-h-screen bg-gray-100 dark:bg-gray-900">
      <!-- Header -->
      <header class="bg-white dark:bg-gray-800 shadow-sm">
        <div class="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
          <div class="flex items-center justify-between">
            <h1 class="text-3xl font-bold tracking-tight text-gray-900 dark:text-white">
              🚀 ${htmlInTemplate(appName)}
            </h1>
          </div>
        </div>
      </header>

      <div class="flex">
        <!-- Sidebar -->
        <aside class="w-64 bg-white dark:bg-gray-800 shadow-lg min-h-screen">
          <nav class="mt-5 px-2 space-y-1">
${navItemsHtml}
          </nav>
        </aside>

        <!-- Main Content -->
        <main class="flex-1 p-6">
          <router-outlet />
        </main>
      </div>
    </div>
  \`,
})
export class AppComponent {
  title = '${tsSingleQuoted(appName)}';
}
`;
}

/**
 * Where `''` redirects to. The FIRST PAGE view when any exists (a generated standalone front then
 * lands on a @View page out of the box); otherwise the first entity with a list page, and `''`
 * when neither is present, which means no redirect. Views arrive sorted.
 */
function defaultRedirectPath(domains: DomainMetadata[], views: ViewMetadata[]): string {
  const firstPageView = views.find(isPageView);
  if (firstPageView) return viewRoutePath(firstPageView);
  const firstListed = domains.find((d) => entityViews(d).list);
  return firstListed ? routePlural(firstListed.entityName) : '';
}

function generateAppRoutes(domains: DomainMetadata[], appName: string, views: ViewMetadata[] = []): string {
  const routes: string[] = [];
  for (const domain of domains) {
    const kebab = DslMapper.toKebabCase(domain.entityName);
    const plural = routePlural(domain.entityName);
    // List-page browser-tab title also needs the labelPlural guard
    // — without it, `News` would render as "Newss - <appName>"
    // in the tab + history. The /new and /:id titles use the bare
    // singular and are unaffected.
    const titlePlural = labelPlural(domain.entityName);
    // Route shape is dictated by what the emitted LIST already links to, not by preference:
    // `[item.id]` is labelled "View" and `[item.id, 'edit']` is labelled "Edit" (list-gen.ts).
    // `:id` loads the detail view, `:id/edit` loads the form; this table has no wildcard route.
    // Each route exists only when the entity's @UI keeps the page it loads.
    const pages = entityViews(domain);
    if (pages.list) {
      routes.push(`\n  {\n    path: '${plural}',\n    loadComponent: () => import('./components/${kebab}-list.component')\n      .then(m => m.${domain.entityName}ListComponent),\n    title: '${titlePlural} - ${tsSingleQuoted(appName)}'\n  },`);
    }
    if (pages.create) {
      routes.push(`\n  {\n    path: '${plural}/new',\n    loadComponent: () => import('./components/${kebab}-form.component')\n      .then(m => m.${domain.entityName}FormComponent),\n    title: 'New ${domain.entityName} - ${tsSingleQuoted(appName)}'\n  },`);
    }
    if (pages.detail) {
      routes.push(`\n  {\n    path: '${plural}/:id',\n    loadComponent: () => import('./components/${kebab}-detail.component')\n      .then(m => m.${domain.entityName}DetailComponent),\n    title: '${domain.entityName} - ${tsSingleQuoted(appName)}'\n  },`);
    }
    if (pages.edit) {
      routes.push(`\n  {\n    path: '${plural}/:id/edit',\n    loadComponent: () => import('./components/${kebab}-form.component')\n      .then(m => m.${domain.entityName}FormComponent),\n    title: 'Edit ${domain.entityName} - ${tsSingleQuoted(appName)}'\n  },`);
    }
  }

  // Presentation-IR views (RFC §5): each emits a `pages/<kebab>.route.ts` exporting
  // a Routes array const. The shell imports each const and spreads it into the
  // routes array (lazy-load is internal to each view route file, consistent with
  // the entity routes' loadComponent style). Views land AFTER the entity routes,
  // in the deterministic order the caller sorted them into.
  const viewImports = views
    .map((v) => `import { ${viewRouteConstName(v)} } from '${viewRouteImportPath(v)}';`)
    .join('\n');
  const viewSpreads = views.map((v) => `\n  ...${viewRouteConstName(v)},`).join('');

  const defaultRedirect = defaultRedirectPath(domains, views);

  // Additive guard (determinism #3): with zero views, NO import line and NO spread
  // are emitted, so app.routes.ts is byte-identical to the pre-route-assembly output.
  const importBlock = viewImports ? `\n${viewImports}` : '';

  // With no destination at all, a redirect from '' to '' would only point the router at itself.
  const redirect = defaultRedirect
    ? `\n  {\n    path: '',\n    redirectTo: '${defaultRedirect}',\n    pathMatch: 'full'\n  },`
    : '';

  return `${fileHeader({ title: 'Application Routes', doNotEdit: 'omit' })}

import { Routes } from '@angular/router';${importBlock}

export const routes: Routes = [${redirect}${routes.join('')}${viewSpreads}
];
`;
}

/**
 * The app barrel, at `index.ts` relative to the generated tree's root. It re-exports the
 * generated types, services, stores and components, so it is emitted with or without the
 * scaffold; with no entity and no enum there is nothing to re-export, and no barrel.
 */
export function generateAppBarrel(
  domains: DomainMetadata[],
  enums: EnumMetadata[],
  config: GeneratorConfig,
): GeneratedFile | null {
  if (domains.length === 0 && enums.length === 0) return null;
  return { path: 'index.ts', content: generateBarrelExport(domains, enums, config), overwritable: true };
}

/**
 * The app barrel — every generated symbol a consumer's own code can reach without knowing
 * internal paths.
 *
 * **Every section is gated on the flag that gates its emission.** When a flag is off,
 * no exports for that section are emitted — the barrel never references files that were not
 * generated. The `barrel-resolves.spec` asserts this invariant for every combination of flags.
 */
function generateBarrelExport(
  domains: DomainMetadata[],
  enums: EnumMetadata[],
  config: GeneratorConfig,
): string {
  const exports: string[] = [
    ...lineHeaderLines('Generated barrel export'),
    "",
    "// Enums",
    "export * from './types/enums';",
  ];

  // Every section below re-exports per-entity files; with no entity the enums are all
  // there is, and empty section headers would advertise surfaces the app does not have.
  if (domains.length === 0) {
    return exports.join('\n') + '\n';
  }

  exports.push("", "// Types (main type definitions)");

  for (const domain of domains) {
    const kebab = DslMapper.toKebabCase(domain.entityName);
    exports.push(`export * from './types/${kebab}.types';`);
  }

  if (config.generateZod) {
    exports.push("", "// Schemas (Zod validation schemas only)");
    for (const domain of domains) {
      const kebab = DslMapper.toKebabCase(domain.entityName);
      exports.push(`export * from './schemas/${kebab}.schema';`);
    }
  }

  if (config.generateServices) {
    exports.push("", "// Services (export service classes and pagination types)");

    // Export Page and PageRequest only once from first service
    let pageTypesExported = false;
    for (const domain of domains) {
      const kebab = DslMapper.toKebabCase(domain.entityName);
      const model = modelTypeName(domain.entityName);
      if (!pageTypesExported) {
        exports.push(`export { ${domain.entityName}Service, ${model}Filter, Page, PageRequest } from './services/${kebab}.service';`);
        pageTypesExported = true;
      } else {
        exports.push(`export { ${domain.entityName}Service, ${model}Filter } from './services/${kebab}.service';`);
      }
    }
  }

  // SSE stream clients, emitted beside the services for each stream route the kernel
  // application serves on a GLOBAL entity (the predicates say why no other). Each sub-barrel exists only when some entity has such a route, and
  // the per-file names (`<Entity>StreamClient`, `<Entity><Action>StreamClient`, the one
  // shared `StreamFrame`) are distinct, so starring them is unambiguous.
  if (config.generateServices) {
    const liveView = domains.some(hasLiveViewClient);
    const actionStreams = domains.some(hasActionStreamClients);
    if (liveView || actionStreams) {
      exports.push("", "// SSE stream clients");
      if (liveView) exports.push("export * from './services/streams.index';");
      if (actionStreams) exports.push("export * from './services/action-streams.index';");
    }
  }

  // Entity stores. `providedIn: 'root'` like the services, and layered ON them rather than
  // offered instead of them: the store injects `<Entity>Service` and adds the signal state
  // (entities, selected, loading, filter, pagination). Both are consumer surfaces, at different
  // levels — the emitted CRUD components inject the service, the emitted @View components inject
  // the store — so exporting both is one entry point per level, not two ways to do one thing.
  //
  // Named exports, not `export *`, for the reason the sagas section below gives: the store file
  // declares its own `<Model>Filter`, which the services section already exports, and starring
  // both would make that name ambiguous — an ambiguous star export is dropped silently rather
  // than reported. The filter type therefore keeps coming from the service alone.
  if (config.generateStores) {
    exports.push("", "// Stores (signal state over the services)");
    for (const domain of domains) {
      const kebab = DslMapper.toKebabCase(domain.entityName);
      exports.push(`export { ${domain.entityName}Store } from './stores/${kebab}.store';`);
      exports.push(`export type { ${domain.entityName}StoreState } from './stores/${kebab}.store';`);
    }
  }

  // A component is exported only when it is emitted: the config flag and the entity's @UI switch
  // both decide that.
  const componentExports: string[] = [];
  for (const domain of domains) {
    const kebab = DslMapper.toKebabCase(domain.entityName);
    const views = entityViews(domain);
    if (config.generateForms && hasFormPage(views)) {
      componentExports.push(`export { ${domain.entityName}FormComponent } from './components/${kebab}-form.component';`);
    }
    if (config.generateLists && views.list) {
      componentExports.push(`export { ${domain.entityName}ListComponent } from './components/${kebab}-list.component';`);
    }
    if (config.generateDetails && views.detail) {
      componentExports.push(`export { ${domain.entityName}DetailComponent } from './components/${kebab}-detail.component';`);
    }
  }
  if (componentExports.length > 0) {
    exports.push("", "// Components", ...componentExports);
  }

  // Domain events. Both emitted classes are `providedIn: 'root'`, so they need no provider —
  // but nothing in the emitted app injects them: they exist for the CONSUMER's code, exactly
  // like the generated services. The barrel is how that code reaches them without knowing
  // internal paths, so an event surface missing from it is emitted-but-unreachable.
  const eventDomains = config.generateEvents
    ? domains.filter((d) => d.events && d.events.length > 0)
    : [];
  if (eventDomains.length > 0) {
    exports.push('', '// Domain events (handlers, payload types, and the shared bus)');
    exports.push(`export { EventBusService } from './events/event-bus.service';`);
    exports.push(`export type { DomainEvent } from './events/event-bus.service';`);
    for (const domain of eventDomains) {
      exports.push(`export * from './events/${DslMapper.toKebabCase(domain.entityName)}.events';`);
    }
  }

  // Saga state machines. `providedIn: 'root'` like the event classes, and injected by nothing the
  // pipeline emits — they exist for the consumer's own progress UI, which is the only code that
  // can drive them (the machine carries no transport; see saga-gen's header).
  //
  // Named exports, not `export *`: every saga file declares its own `SagaState`, `StepStatus`,
  // `SagaStep`, `SagaExecution` and `SagaStatusSnapshot`, so starring two of them would make each
  // of those names ambiguous — and an ambiguous star export is dropped silently rather than
  // reported. The shared type names therefore come from the first saga only, exactly as `Page`
  // and `PageRequest` do in the services section above.
  const sagaDomains = config.generateSagas
    ? domains.filter((d) => sagaMachineName(d) !== null)
    : [];
  if (sagaDomains.length > 0) {
    exports.push('', '// Saga state machines');
    let sagaTypesExported = false;
    for (const domain of sagaDomains) {
      const kebab = DslMapper.toKebabCase(domain.entityName);
      const machine = sagaMachineName(domain);
      if (!sagaTypesExported) {
        exports.push(`export { ${machine} } from './sagas/${kebab}.saga';`);
        exports.push(`export type { SagaState, StepStatus, SagaStep, SagaExecution, SagaStatusSnapshot } from './sagas/${kebab}.saga';`);
        sagaTypesExported = true;
      } else {
        exports.push(`export { ${machine} } from './sagas/${kebab}.saga';`);
      }
    }
  }

  return exports.join('\n') + '\n';
}

/**
 * The runtime dependencies. The framework core — `@angular/common|compiler|core|platform-browser|
 * router`, `rxjs` (a peer dependency of `@angular/core`) and `tslib` (`importHelpers`) — and the
 * ui-kit the styles import is always present. `@angular/cdk`, `@angular/forms`
 * and `zod` are used only by some emitters: an app with a backend keeps its fixed set, and an app
 * without one lists each only when an emitted file imports it. `@angular/ssr` and
 * `@angular/platform-server` are the server rendering `render: 'ssg'` prerenders with.
 */
function runtimeDependencies(needs: ScaffoldNeeds, ssg: boolean): string {
  const used = (pkg: string): boolean => needs.backend || needs.packages.has(pkg);
  const entries: Array<[string, string, boolean]> = [
    ['@angular/cdk', '^22.0.0', used('@angular/cdk')],
    ['@angular/common', '^22.0.0', true],
    ['@angular/compiler', '^22.0.0', true],
    ['@angular/core', '^22.0.0', true],
    ['@angular/forms', '^22.0.0', used('@angular/forms')],
    ['@angular/platform-browser', '^22.0.0', true],
    ['@angular/platform-server', '^22.0.0', ssg],
    ['@angular/router', '^22.0.0', true],
    ['@angular/ssr', '^22.0.0', ssg],
    ['@exeris/ui-kit', '^0.2.1', true],
    ['rxjs', '~7.8.1', true],
    ['tslib', '^2.8.1', true],
    ['zod', '^3.24.0', used('zod')],
  ];
  return entries
    .filter(([, , kept]) => kept)
    .map(([name, range]) => `    "${name}": "${range}"`)
    .join(',\n');
}

/**
 * `@tailwindcss/typography`, beside `tailwindcss` in devDependencies: like Tailwind itself it is
 * consumed by the stylesheet build, never imported at runtime. Listed only when an emitted template
 * uses a `prose` class (core/scaffold-needs), so an app without rich text keeps its scaffold as is.
 */
function typographyDevDependency(needs: ScaffoldNeeds): string {
  return needs.typography ? '\n    "@tailwindcss/typography": "^0.5.20",' : '';
}

function generatePackageJson(appName: string, config: GeneratorConfig, needs: ScaffoldNeeds): string {
  const pkgName = frontendSlug(appName);
  // The dev-server proxy forwards API calls; without a backend there is no proxy config to pass.
  const start = needs.backend ? 'ng serve --proxy-config proxy.conf.js' : 'ng serve';
  return `{
  "name": ${jsonValue(pkgName)},
  "version": "0.1.0",
  "description": ${jsonValue(`${appName} - Generated Angular Frontend`)},
  "type": "module",
  "engines": {
    "node": ">=22.0.0"
  },
  "scripts": {
    "ng": "ng",
    "start": "${start}",
    "build": "ng build",
    "watch": "ng build --watch --configuration development",
    "test": "ng test",
    "lint": "ng lint"
  },
  "private": true,
  "dependencies": {
${runtimeDependencies(needs, config.render === 'ssg')}
  },
  "devDependencies": {
    "@angular/build": "^22.0.0",
    "@angular/cli": "^22.0.0",
    "@angular/compiler-cli": "^22.0.0",
    "@tailwindcss/postcss": "^4.0.0",${typographyDevDependency(needs)}
    "@types/node": "^22.0.0",
    "postcss": "^8.5.0",
    "tailwindcss": "^4.0.0",
    "typescript": "~6.0.0"${testDevDependencies(config)}
  }
}
`;
}

function generateAngularJson(appName: string, config: GeneratorConfig, ssg: boolean): string {
  const slug = frontendSlug(appName);
  // `outputMode: "static"` writes the prerendered routes as HTML and emits no server bundle to
  // deploy; `server` is the entry the prerender bootstraps.
  const server = ssg ? `\n            "server": "src/main.server.ts",\n            "outputMode": "static",` : '';
  return `{
  "$schema": "./node_modules/@angular/cli/lib/config/schema.json",
  "version": 1,
  "newProjectRoot": "projects",
  "projects": {
    "${slug}": {
      "projectType": "application",
      "schematics": {
        "@schematics/angular:component": {
          "style": "css",
          "standalone": true,
          "changeDetection": "OnPush"
        }
      },
      "root": "",
      "sourceRoot": "src",
      "prefix": "app",
      "architect": {
        "build": {
          "builder": "@angular/build:application",
          "options": {
            "outputPath": "dist/${slug}",
            "index": "src/index.html",
            "browser": "src/main.ts",${server}
            "polyfills": [],
            "tsConfig": "tsconfig.app.json",
            "inlineStyleLanguage": "css",
            "styles": ["src/styles.css"],
            "scripts": [],
            "assets": [
              { "glob": "favicon.ico", "input": "src", "output": "/" }
            ]
          },
          "configurations": {
            "production": {
              "budgets": [
                { "type": "initial", "maximumWarning": "500kB", "maximumError": "1MB" },
                { "type": "anyComponentStyle", "maximumWarning": "2kB", "maximumError": "4kB" }
              ],
              "outputHashing": "all"
            },
            "development": {
              "optimization": false,
              "extractLicenses": false,
              "sourceMap": true,
              "fileReplacements": [
                { "replace": "src/environments/environment.ts", "with": "src/environments/environment.development.ts" }
              ]
            }
          },
          "defaultConfiguration": "production"
        },
        "serve": {
          "builder": "@angular/build:dev-server",
          "configurations": {
            "production": { "buildTarget": "${slug}:build:production" },
            "development": { "buildTarget": "${slug}:build:development" }
          },
          "defaultConfiguration": "development"
        }${testTarget(config)}
      }
    }
  }
}
`;
}

function generateTsConfig(): string {
  return `{
  "compileOnSave": false,
  "compilerOptions": {
    "outDir": "./dist/out-tsc",
    "strict": true,
    "noImplicitOverride": true,
    "noPropertyAccessFromIndexSignature": true,
    "noImplicitReturns": true,
    "noFallthroughCasesInSwitch": true,
    "skipLibCheck": true,
    "esModuleInterop": true,
    "sourceMap": true,
    "declaration": false,
    "experimentalDecorators": true,
    "moduleResolution": "bundler",
    "importHelpers": true,
    "target": "ES2022",
    "module": "ES2022",
    "lib": ["ES2022", "dom"],
    "paths": {
      "@generated/*": ["./src/app/generated/*"]
    }
  },
  "angularCompilerOptions": {
    "enableI18nLegacyMessageIdFormat": false,
    "strictInjectionParameters": true,
    "strictInputAccessModifiers": true,
    "strictTemplates": true
  }
}
`;
}

function generateTsConfigApp(config: GeneratorConfig, ssg: boolean): string {
  // Specs are excluded from the APP config on purpose. `include` covers `src/**/*.ts`, so without
  // this a consumer's production `ng build` would type-check the emitted specs and therefore
  // require `vitest` to be installed — a test-only dependency leaking into the build path, which
  // is the requirement class ADR-058 exists to prevent.
  const exclude = config.generateTests ? `,\n  "exclude": ["src/**/*.spec.ts"]` : '';
  return `{
  "extends": "./tsconfig.json",
  "compilerOptions": {
    "outDir": "./out-tsc/app",
    "types": []
  },
  "files": ${ssg ? '["src/main.ts", "src/main.server.ts"]' : '["src/main.ts"]'},
  "include": ["src/**/*.ts", "src/**/*.d.ts"]${exclude}
}
`;
}

/**
 * The `test` architect target — `@angular/build:unit-test` on Vitest.
 * Emitted only under `generateTests`, so an app that did not ask for tests keeps the scaffold it
 * had before this slice existed.
 */
function testTarget(config: GeneratorConfig): string {
  if (!config.generateTests) return '';
  return `,
        "test": {
          "builder": "@angular/build:unit-test",
          "options": {
            "buildTarget": "::development",
            "tsConfig": "tsconfig.spec.json",
            "runner": "vitest"
          }
        }`;
}

/**
 * The two devDependencies the runner needs, and the reason each is named rather than assumed:
 * `vitest` is an **optional** peer of `@angular/build` (so the builder is inert without it), and a
 * DOM implementation is required for non-browser tests — the builder refuses to start otherwise,
 * naming `jsdom` or `happy-dom` itself. Both are added only under `generateTests`.
 */
function testDevDependencies(config: GeneratorConfig): string {
  if (!config.generateTests) return '';
  return `,
    "jsdom": "^26.0.0",
    "vitest": "^4.0.8"`;
}

/** Specs compile under their own config — the app's excludes them. */
function generateTsConfigSpec(): string {
  return `{
  "extends": "./tsconfig.json",
  "compilerOptions": {
    "outDir": "./out-tsc/spec",
    "types": []
  },
  "include": ["src/**/*.spec.ts", "src/**/*.d.ts"]
}
`;
}

function generatePostcssConfig(): string {
  // PostCSS configuration for Tailwind CSS v4 (JSON format per Angular docs)
  return `{
  "plugins": {
    "@tailwindcss/postcss": {}
  }
}
`;
}

function generateStylesCss(typography: boolean): string {
  // Tailwind CSS v4 is CSS-first: @import replaces the @tailwind directives, and no
  // tailwind.config.js is read. The ui-kit is imported here, in the global stylesheet that
  // Tailwind processes, and never listed in angular.json's `styles` array, where its CSS would
  // be compiled without Tailwind.
  //
  // Both kit entries are required, in the order the kit's README gives, after Tailwind:
  // - "theme" is the @theme token entry: it declares the `exeris-*` design-token namespace
  //   (bg-exeris-primary, text-exeris-primary-hover, font-exeris, …) and re-points the `dark`
  //   variant at the `.dark` class. It comes first so the component layer's `dark:` variants
  //   compile against that class instead of the operating system's setting.
  // - "styles" is the `.exeris-*` component layer (exeris-card, exeris-btn, …). It uses @apply
  //   and `dark:`, so it must be compiled by Tailwind in this same stylesheet. Its classes are
  //   plain CSS rules, not utilities, so they are emitted whether or not a template names them:
  //   no @source is needed for them.
  //
  // The v4 @theme entry defines brand/semantic colours + typography tokens only
  // (no neutral surface/text tokens). The body therefore takes the exeris font
  // token and relies on Tailwind's preflight neutrals rather than re-introducing
  // a hardcoded gray theme a product immediately deletes. Components opt
  // into the exeris colour tokens (bg-exeris-primary, …) directly.
  //
  // The typography plugin, when a template uses `prose`, is loaded with the v4 `@plugin`
  // directive after the imports, which CSS requires to come first. Its `prose-invert` is a plain
  // utility, so `dark:prose-invert` compiles against the kit's `.dark` class variant like every
  // other `dark:` utility.
  const plugins = typography ? '@plugin "@tailwindcss/typography";\n' : '';
  return `/* Generated Angular Frontend - Global Styles */
/* Tailwind CSS v4 */
@import "tailwindcss";
@import "@exeris/ui-kit/theme";
@import "@exeris/ui-kit/styles";
${plugins}
/* Custom base styles */
@layer base {
  html {
    @apply antialiased;
  }

  body {
    @apply font-exeris;
  }
}
`;
}

/**
 * The path prefixes the emitted clients request, one per entity: `apiBasePath` followed by the
 * entity's served path, the base `service-gen`, `stream-client-gen` and `action-stream-client-gen`
 * build every URL on. Every entity counts, whether or not its client is emitted, because the kernel
 * application serves it either way (core/scaffold-needs). Deduplicated and sorted, so the proxy
 * depends on the set of paths and not on the order the metadata was loaded in.
 */
export function proxyPrefixes(domains: readonly DomainMetadata[], config: GeneratorConfig): string[] {
  const prefixes = new Set(domains.map((d) => `${config.apiBasePath}${serviceApiPath(d)}`));
  return [...prefixes].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
}

/**
 * The dev-server proxy, in JavaScript because a JSON config cannot hold a `bypass` function.
 *
 * The app is served same-origin with the kernel application, and an entity's API path is also its
 * page route: `/orders` is the list endpoint and the list page. The path cannot tell the two
 * requests apart; the `Accept` header can. A browser navigation sends `text/html`, while an
 * `HttpClient` call and an `EventSource` do not. `bypass` answers the first with `/index.html`, so a
 * deep link or a refresh loads the app, and the proxy forwards the rest to the kernel application.
 *
 * `export default`, because the scaffold's `package.json` declares `"type": "module"`.
 */
function generateProxyConfig(domains: readonly DomainMetadata[], config: GeneratorConfig): string {
  const rules = proxyPrefixes(domains, config).map(
    (prefix) => `  '${tsSingleQuoted(prefix)}': { target, secure: false, changeOrigin: true, bypass },\n`,
  );
  return `${fileHeader({
    title: 'Angular Dev-Server Proxy (ng serve --proxy-config proxy.conf.js)',
    notes: [
      '',
      "An entity's API path is also its page route, so a request is told apart by its Accept",
      "header: a page navigation asks for text/html and is answered with the app's index.html;",
      'every other request under these paths is forwarded to the kernel application.',
    ],
    doNotEdit: 'omit',
  })}

const target = 'http://localhost:8443';

/** A page navigation stays with the dev server; an API request is forwarded. */
function bypass(req) {
  return req.headers.accept?.includes('text/html') ? '/index.html' : undefined;
}

export default {
${rules.join('')}};
`;
}

function generateIndexHtml(appName: string): string {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>${htmlText(appName)}</title>
  <base href="/">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="icon" type="image/svg+xml" href="favicon.ico">
</head>
<body>
  <app-root></app-root>
</body>
</html>
`;
}

function generateFavicon(): string {
  // Simple SVG favicon - rocket emoji style
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
  <rect width="100" height="100" rx="20" fill="#4f46e5"/>
  <text x="50" y="75" font-size="60" text-anchor="middle" fill="white">🚀</text>
</svg>`;
}

function getEntityIcon(entityName: string): string {
  const icons: Record<string, string> = {
    Tenant: '🏢',
    User: '👥',
    Product: '📦',
    Order: '📋',
    Customer: '👤',
    Invoice: '📄',
    Payment: '💳',
    default: '📁',
  };
  return icons[entityName] ?? icons.default;
}

function generateEnvironmentFile(params: {
  production: boolean;
  api: { apiUrl: string } | null;
}): string {
  const { production, api } = params;
  const apiEntries = api
    ? `
  apiUrl: '${api.apiUrl}',`
    : '';
  return `${fileHeader({ title: 'Angular Environment Configuration', doNotEdit: 'omit' })}

export const environment = {
  production: ${production},${apiEntries}
} as const;
`;
}

/**
 * `environment.apiUrl` states the prefix the emitted services actually use, which is
 * `config.apiBasePath` and nothing else — `service-gen` interpolates that value directly.
 */
function resolveApiSettings(config: GeneratorConfig): { apiUrl: string } {
  return { apiUrl: config.apiBasePath };
}
