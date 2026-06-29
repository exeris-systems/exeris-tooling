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
 * - tailwind.config.js
 *
 * @author Exeris Team
 * @since 0.2.0
 */

import type { DomainMetadata } from '../../models/domain-model.js';
import type { GeneratorConfig } from '../../config.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import { getStrategy } from '../../core/backend-strategy.js';

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
/** A TS single-quoted string literal (component `title`, route titles). */
function tsSingleQuoted(s: string): string {
  return s.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\r?\n/g, ' ');
}
/** HTML text that sits inside an emitted TS backtick template (both layers). */
function htmlInTemplate(s: string): string {
  return htmlText(s).replace(/\\/g, '\\\\').replace(/`/g, '\\`').replace(/\$\{/g, '\\${');
}

interface EnumMetadata {
  name: string;
  qualifiedName: string;
}

export function generateAppStructure(
  domains: DomainMetadata[],
  enums: EnumMetadata[],
  config: GeneratorConfig
): GeneratedFile[] {
  const files: GeneratedFile[] = [];
  // Output roots
  const outputRoot = '.';
  const srcRoot = 'src';
  const appRoot = 'src/app';
  const envRoot = 'src/environments';
  const { apiUrl, apiVersion } = resolveApiSettings(config);
  const appName = config.appName;

  // Config files at the project root
  files.push({ path: `${outputRoot}/package.json`, content: generatePackageJson(appName), overwritable: false });
  files.push({ path: `${outputRoot}/angular.json`, content: generateAngularJson(), overwritable: false });
  files.push({ path: `${outputRoot}/tsconfig.json`, content: generateTsConfig(), overwritable: false });
  files.push({ path: `${outputRoot}/tsconfig.app.json`, content: generateTsConfigApp(), overwritable: false });
  files.push({ path: `${outputRoot}/tailwind.config.js`, content: generateTailwindConfig(), overwritable: true });
  files.push({ path: `${outputRoot}/.postcssrc.json`, content: generatePostcssConfig(), overwritable: true });
  files.push({ path: `${outputRoot}/proxy.conf.json`, content: generateProxyConfig(), overwritable: true });

  // Static files under src/
  files.push({ path: `${srcRoot}/styles.css`, content: generateStylesCss(), overwritable: true });
  files.push({ path: `${srcRoot}/index.html`, content: generateIndexHtml(appName), overwritable: true });
  files.push({ path: `${srcRoot}/favicon.ico`, content: generateFavicon(), overwritable: true });
  // main.ts under src/
  files.push({ path: `${srcRoot}/main.ts`, content: generateMainTs(), overwritable: false });

  // Environment files under src/environments
  files.push({ path: `${envRoot}/environment.ts`, content: generateEnvironmentFile({ production: true, apiUrl, apiVersion }), overwritable: false });
  files.push({ path: `${envRoot}/environment.development.ts`, content: generateEnvironmentFile({ production: false, apiUrl, apiVersion }), overwritable: true });

  // Public-surface emitters (sidebar nav, route list, barrel re-exports) take the
  // visible list so they never reference an entity whose per-entity files the
  // orchestrator skips for hidden domains. Filtering once here keeps the
  // hidden-domain policy in one spot.
  const visibleDomains = domains.filter((d) => !d.internalApi?.hidden);

  // App shell under src/app
  files.push({ path: `${appRoot}/app.config.ts`, content: generateAppConfig(), overwritable: false });
  files.push({ path: `${appRoot}/app.component.ts`, content: generateAppComponent(visibleDomains, appName), overwritable: false });
  files.push({ path: `${appRoot}/app.routes.ts`, content: generateAppRoutes(visibleDomains, appName), overwritable: false });
  files.push({ path: `${appRoot}/index.ts`, content: generateBarrelExport(visibleDomains, enums), overwritable: true });

  // T20: per-entity components/services/types/schemas and enums are emitted by the
  // CLI orchestrator under src/app/ (the real generators), NOT here — this function
  // now emits the scaffold only, so there is exactly one src/app tree and no stub
  // shadowing the real enums/schemas. The app shell above (routes/component/barrel)
  // references those src/app files by their relative paths, which resolve.

  return files;
}

function generateMainTs(): string {
  return `/**
 * Angular Application Entry Point
 * Generated by @exeris/codegen-ts
 */

import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));
`;
}

function generateAppConfig(): string {
  return `/**
 * Angular Application Configuration
 * Generated by @exeris/codegen-ts
 * 
 * Uses Angular 22 Zoneless mode with Signals
 */

import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';

import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    // Zoneless mode - no zone.js needed for change detection
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding()),
    // v22: fetch is the default HttpClient transport (the old explicit opt-in is now redundant).
    provideHttpClient(),
    // Row enter/leave animations are native (animate.enter, Angular 22) — a
    // compiler feature, so no @angular/animations package or provider is needed.
  ],
};
`;
}

// Pluralisation helpers. Two seams need plurals — display labels
// (sidebar text, browser-tab title) and URL paths. Both must
// suppress the trailing 's' when the entity name already ends in 's'
// (e.g. `News`), otherwise we get `Newss` in the tab title or
// `/newss` in the URL. The display side keeps the original casing;
// the route side kebab-cases first.
function labelPlural(entityName: string): string {
  return entityName.endsWith('s') ? entityName : entityName + 's';
}

function routePlural(entityName: string): string {
  const kebab = DslMapper.toKebabCase(entityName);
  return entityName.endsWith('s') ? kebab : kebab + 's';
}

function generateAppComponent(domains: DomainMetadata[], appName: string): string {
  const navItems = domains.map((d) => {
    const icon = getEntityIcon(d.entityName);
    // Sidebar router-link target must match the path generated by
    // generateAppRoutes — both go through routePlural so a `News`
    // entity navigates to /news, not /newss.
    return { path: routePlural(d.entityName), label: labelPlural(d.entityName), icon };
  });

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

  return `/**
 * Root Application Component
 * Generated by @exeris/codegen-ts
 */

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
      <header class="bg-white dark:bg-gray-800 shadow">
        <div class="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
          <div class="flex items-center justify-between">
            <h1 class="text-3xl font-bold tracking-tight text-gray-900 dark:text-white">
              🚀 ${htmlInTemplate(appName)}
            </h1>
            <span class="text-sm text-gray-500 dark:text-gray-400">v0.1.0</span>
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

function generateAppRoutes(domains: DomainMetadata[], appName: string): string {
  const routes: string[] = [];
  for (const domain of domains) {
    const kebab = DslMapper.toKebabCase(domain.entityName);
    const plural = routePlural(domain.entityName);
    // List-page browser-tab title also needs the labelPlural guard
    // — without it, `News` would render as "Newss - <appName>"
    // in the tab + history. The /new and /:id titles use the bare
    // singular and are unaffected.
    const titlePlural = labelPlural(domain.entityName);
    routes.push(`\n  {\n    path: '${plural}',\n    loadComponent: () => import('./components/${kebab}-list.component')\n      .then(m => m.${domain.entityName}ListComponent),\n    title: '${titlePlural} - ${tsSingleQuoted(appName)}'\n  },\n  {\n    path: '${plural}/new',\n    loadComponent: () => import('./components/${kebab}-form.component')\n      .then(m => m.${domain.entityName}FormComponent),\n    title: 'New ${domain.entityName} - ${tsSingleQuoted(appName)}'\n  },\n  {\n    path: '${plural}/:id',\n    loadComponent: () => import('./components/${kebab}-form.component')\n      .then(m => m.${domain.entityName}FormComponent),\n    title: 'Edit ${domain.entityName} - ${tsSingleQuoted(appName)}'\n  },`);
  }

  const defaultRedirect = domains.length > 0
    ? routePlural(domains[0].entityName)
    : '';

  return `/**
 * Application Routes
 * Generated by @exeris/codegen-ts
 */

import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    redirectTo: '${defaultRedirect}',
    pathMatch: 'full'
  },${routes.join('')}
];
`;
}

function generateBarrelExport(visibleDomains: DomainMetadata[], enums: EnumMetadata[]): string {
  // Caller (generateAppStructure) is responsible for filtering out
  // hidden domains — see the `visibleDomains` comment at the call
  // site. This function trusts its input and iterates everything
  // it's given. Sole caller in tree; if a second caller ever shows
  // up, document the contract or reintroduce the filter here.

  const exports: string[] = [
    "// Generated barrel export",
    "// DO NOT EDIT - This file is auto-generated",
    "",
    "// Enums",
    "export * from './types/enums';",
    "",
    "// Types (main type definitions)",
  ];

  for (const domain of visibleDomains) {
    const kebab = DslMapper.toKebabCase(domain.entityName);
    exports.push(`export * from './types/${kebab}.types';`);
  }

  exports.push("", "// Schemas (Zod validation schemas only)");
  for (const domain of visibleDomains) {
    const kebab = DslMapper.toKebabCase(domain.entityName);
    exports.push(`export * from './schemas/${kebab}.schema';`);
  }

  exports.push("", "// Services (export service classes and pagination types)");

  // Export Page and PageRequest only once from first service
  let pageTypesExported = false;
  for (const domain of visibleDomains) {
    const kebab = DslMapper.toKebabCase(domain.entityName);
    if (!pageTypesExported) {
      exports.push(`export { ${domain.entityName}Service, ${domain.entityName}Filter, Page, PageRequest } from './services/${kebab}.service';`);
      pageTypesExported = true;
    } else {
      exports.push(`export { ${domain.entityName}Service, ${domain.entityName}Filter } from './services/${kebab}.service';`);
    }
  }

  exports.push("", "// Components");
  for (const domain of visibleDomains) {
    const kebab = DslMapper.toKebabCase(domain.entityName);
    exports.push(`export { ${domain.entityName}FormComponent } from './components/${kebab}-form.component';`);
    exports.push(`export { ${domain.entityName}ListComponent } from './components/${kebab}-list.component';`);
  }

  return exports.join('\n') + '\n';
}

function generatePackageJson(appName: string): string {
  const pkgName = `${DslMapper.toKebabCase(appName.replace(/\s+/g, '-'))}-frontend`;
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
    "start": "ng serve --proxy-config proxy.conf.json",
    "build": "ng build",
    "watch": "ng build --watch --configuration development",
    "test": "ng test",
    "lint": "ng lint"
  },
  "private": true,
  "dependencies": {
    "@angular/cdk": "^22.0.0",
    "@angular/common": "^22.0.0",
    "@angular/compiler": "^22.0.0",
    "@angular/core": "^22.0.0",
    "@angular/forms": "^22.0.0",
    "@angular/platform-browser": "^22.0.0",
    "@angular/router": "^22.0.0",
    "@exeris/ui-kit": "^0.1.0",
    "rxjs": "~7.8.1",
    "tslib": "^2.8.1",
    "zod": "^3.24.0"
  },
  "devDependencies": {
    "@angular/build": "^22.0.0",
    "@angular/cli": "^22.0.0",
    "@angular/compiler-cli": "^22.0.0",
    "@tailwindcss/postcss": "^4.0.0",
    "@types/node": "^22.0.0",
    "postcss": "^8.5.0",
    "tailwindcss": "^4.0.0",
    "typescript": "~6.0.0"
  }
}
`;
}

function generateAngularJson(): string {
  return `{
  "$schema": "./node_modules/@angular/cli/lib/config/schema.json",
  "version": 1,
  "newProjectRoot": "projects",
  "projects": {
    "exeris-foundation-frontend": {
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
            "outputPath": "dist/exeris-foundation-frontend",
            "index": "src/index.html",
            "browser": "src/main.ts",
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
            "production": { "buildTarget": "exeris-foundation-frontend:build:production" },
            "development": { "buildTarget": "exeris-foundation-frontend:build:development" }
          },
          "defaultConfiguration": "development"
        }
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

function generateTsConfigApp(): string {
  return `{
  "extends": "./tsconfig.json",
  "compilerOptions": {
    "outDir": "./out-tsc/app",
    "types": []
  },
  "files": ["src/main.ts"],
  "include": ["src/**/*.ts", "src/**/*.d.ts"]
}
`;
}

function generateTailwindConfig(): string {
  // Tailwind CSS v4 is CSS-first, so this file is largely vestigial for a v4
  // build (the tokens come from `@import "@exeris/ui-kit/theme"` in styles.css).
  // It is kept valid and wires the ui-kit v3 JS preset so a v3-toolchain consumer
  // ALSO gets the same `exeris-*` token namespace. The preset is the documented
  // v3 entry point (v4 ignores `presets`); both entries declare identical tokens.
  return `/** @type {import('tailwindcss').Config} */
import exerisPreset from '@exeris/ui-kit/tailwind.preset.js';

export default {
  presets: [exerisPreset],
  content: [
    "./src/**/*.{html,ts}",
  ],
  theme: {
    extend: {},
  },
  plugins: [],
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

function generateStylesCss(): string {
  // Tailwind CSS v4 uses @import instead of @tailwind directives.
  //
  // The @exeris/ui-kit "theme" entry is the v4 (@theme, CSS-first) token entry:
  // it declares the `exeris-*` design-token namespace (bg-exeris-primary,
  // text-exeris-primary-hover, font-exeris, …) so generated components style
  // against the shared SDK tokens instead of hardcoded boilerplate. (A v3
  // toolchain consumes the same tokens via the tailwind.preset.js wired in
  // tailwind.config.js.)
  //
  // The v4 @theme entry defines brand/semantic colours + typography tokens only
  // (no neutral surface/text tokens). The body therefore takes the exeris font
  // token and relies on Tailwind's preflight neutrals rather than re-introducing
  // a hardcoded gray theme a product immediately deletes (T25). Components opt
  // into the exeris colour tokens (bg-exeris-primary, …) directly.
  return `/* Generated Angular Frontend - Global Styles */
/* Tailwind CSS v4 */
@import "tailwindcss";
@import "@exeris/ui-kit/theme";

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

function generateProxyConfig(): string {
  return `{
  "/api": {
    "target": "http://localhost:8443",
    "secure": false,
    "changeOrigin": true,
    "logLevel": "debug"
  }
}
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

function generateEnvironmentFile(params: { production: boolean; apiUrl: string; apiVersion: string }): string {
  const { production, apiUrl, apiVersion } = params;
  return `/**
 * Angular Environment Configuration
 * Generated by @exeris/codegen-ts
 */

export const environment = {
  production: ${production},
  apiUrl: '${apiUrl}',
  apiVersion: '${apiVersion}',
} as const;
`;
}

function resolveApiSettings(config: GeneratorConfig): { apiUrl: string; apiVersion: string } {
  const strategy = getStrategy(config.backend);
  const clientConfig = strategy.getClientConfig();
  return {
    apiUrl: config.apiBasePath || clientConfig.baseUrl || '/api',
    apiVersion: clientConfig.apiVersion ?? 'v1',
  };
}
