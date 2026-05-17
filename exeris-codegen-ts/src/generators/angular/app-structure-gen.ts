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
import { generateForm } from './form-gen.js';
import { generateList } from './list-gen.js';
import { generateService } from './service-gen.js';
import { generateTypes } from '../api/type-gen.js';

export interface GeneratedFile {
  path: string;
  content: string;
  overwritable?: boolean;
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
  // Główne katalogi
  const outputRoot = '.';
  const srcRoot = 'src';
  const appRoot = 'src/app';
  const envRoot = 'src/environments';
  const schemasRoot = `${appRoot}/schemas`;
  const componentsRoot = `${appRoot}/components`;
  const servicesRoot = `${appRoot}/services`;
  const typesRoot = `${appRoot}/types`;
  const { apiUrl, apiVersion } = resolveApiSettings(config);

  // Pliki konfiguracyjne do głównego katalogu
  files.push({ path: `${outputRoot}/package.json`, content: generatePackageJson(), overwritable: false });
  files.push({ path: `${outputRoot}/angular.json`, content: generateAngularJson(), overwritable: false });
  files.push({ path: `${outputRoot}/tsconfig.json`, content: generateTsConfig(), overwritable: false });
  files.push({ path: `${outputRoot}/tsconfig.app.json`, content: generateTsConfigApp(), overwritable: false });
  files.push({ path: `${outputRoot}/tailwind.config.js`, content: generateTailwindConfig(), overwritable: true });
  files.push({ path: `${outputRoot}/.postcssrc.json`, content: generatePostcssConfig(), overwritable: true });
  files.push({ path: `${outputRoot}/proxy.conf.json`, content: generateProxyConfig(), overwritable: true });

  // Pliki statyczne do src
  files.push({ path: `${srcRoot}/styles.css`, content: generateStylesCss(), overwritable: true });
  files.push({ path: `${srcRoot}/index.html`, content: generateIndexHtml(), overwritable: true });
  files.push({ path: `${srcRoot}/favicon.ico`, content: generateFavicon(), overwritable: true });
  // main.ts do src/
  files.push({ path: `${srcRoot}/main.ts`, content: generateMainTs(), overwritable: false });

  // Pliki środowiskowe do src/environments
  files.push({ path: `${envRoot}/environment.ts`, content: generateEnvironmentFile({ production: true, apiUrl, apiVersion }), overwritable: false });
  files.push({ path: `${envRoot}/environment.development.ts`, content: generateEnvironmentFile({ production: false, apiUrl, apiVersion }), overwritable: true });

  // Pliki główne aplikacji do src/app
  files.push({ path: `${appRoot}/app.config.ts`, content: generateAppConfig(), overwritable: false });
  files.push({ path: `${appRoot}/app.component.ts`, content: generateAppComponent(domains), overwritable: false });
  files.push({ path: `${appRoot}/app.routes.ts`, content: generateAppRoutes(domains), overwritable: false });
  files.push({ path: `${appRoot}/index.ts`, content: generateBarrelExport(domains, enums), overwritable: true });

  // Komponenty, serwisy, typy, schemas do src/app
  for (const domain of domains) {
    const kebab = DslMapper.toKebabCase(domain.entityName);
    const formFile = generateForm(domain, config);
    if (formFile) files.push({ path: `${componentsRoot}/${kebab}-form.component.ts`, content: formFile.content, overwritable: true });
    const listFile = generateList(domain, config);
    if (listFile) files.push({ path: `${componentsRoot}/${kebab}-list.component.ts`, content: listFile.content, overwritable: true });
    const serviceFile = generateService(domain, config);
    if (serviceFile) files.push({ path: `${servicesRoot}/${kebab}.service.ts`, content: serviceFile.content, overwritable: true });
    const typesFiles = generateTypes(domain, config);
    if (Array.isArray(typesFiles)) {
      for (const tf of typesFiles) {
        files.push({ path: `${typesRoot}/${kebab}.types.ts`, content: tf.content, overwritable: true });
      }
    }
    // Dodaj schemas
    const schemaFile = generateSchema(domain, config); // zakładamy, że taka funkcja istnieje
    if (schemaFile) files.push({ path: `${schemasRoot}/${kebab}.schema.ts`, content: schemaFile.content, overwritable: true });
  }
  // enums.ts do src/app/types
  const enumsFile = generateEnums(enums, config); // zakładamy, że taka funkcja istnieje
  if (enumsFile) files.push({ path: `${typesRoot}/enums.ts`, content: enumsFile.content, overwritable: true });

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
 * Uses Angular 21 Zoneless mode with Signals
 */

import { ApplicationConfig, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';

import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    // Zoneless mode - no zone.js needed for change detection
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withFetch()),
    // Lazy-load animation code
    provideAnimationsAsync(),
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

function generateAppComponent(domains: DomainMetadata[]): string {
  const navItems = domains.map((d) => {
    const icon = getEntityIcon(d.entityName);
    // Sidebar router-link target must match the path generated by
    // generateAppRoutes — both go through routePlural so a `News`
    // entity navigates to /news, not /newss.
    return { path: routePlural(d.entityName), label: labelPlural(d.entityName), icon };
  });

  const navItemsHtml = navItems
    .map(
      (item) => `
            <a routerLink="/${item.path}" 
               routerLinkActive="bg-indigo-100 dark:bg-indigo-900 text-indigo-700 dark:text-indigo-200"
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
              🚀 Exeris Foundation
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
  title = 'Exeris Foundation';
}
`;
}

function generateAppRoutes(domains: DomainMetadata[]): string {
  const routes: string[] = [];
  for (const domain of domains) {
    const kebab = DslMapper.toKebabCase(domain.entityName);
    const plural = routePlural(domain.entityName);
    // List-page browser-tab title also needs the labelPlural guard
    // — without it, `News` would render as "Newss - Exeris Foundation"
    // in the tab + history. The /new and /:id titles use the bare
    // singular and are unaffected.
    const titlePlural = labelPlural(domain.entityName);
    routes.push(`\n  {\n    path: '${plural}',\n    loadComponent: () => import('./components/${kebab}-list.component')\n      .then(m => m.${domain.entityName}ListComponent),\n    title: '${titlePlural} - Exeris Foundation'\n  },\n  {\n    path: '${plural}/new',\n    loadComponent: () => import('./components/${kebab}-form.component')\n      .then(m => m.${domain.entityName}FormComponent),\n    title: 'New ${domain.entityName} - Exeris Foundation'\n  },\n  {\n    path: '${plural}/:id',\n    loadComponent: () => import('./components/${kebab}-form.component')\n      .then(m => m.${domain.entityName}FormComponent),\n    title: 'Edit ${domain.entityName} - Exeris Foundation'\n  },`);
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

function generateBarrelExport(domains: DomainMetadata[], enums: EnumMetadata[]): string {
  // Mirror the hidden-domain skip applied in generateAppStructure's
  // per-entity loop: form / list / service / types all early-return
  // null for internalApi.hidden=true, so emitting barrel entries for
  // them produces dead import paths and the consuming Angular app
  // fails tsc. Schema files DO emit for hidden domains today (the
  // local generateSchema placeholder doesn't check hidden) — but
  // schemas are an internal validation surface and re-exporting
  // them through the public barrel for a domain marked hidden
  // contradicts the intent of the hidden flag, so we filter them
  // out here as well.
  const visibleDomains = domains.filter((d) => !d.internalApi?.hidden);

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

function generatePackageJson(): string {
  return `{
  "name": "exeris-foundation-frontend",
  "version": "0.1.0",
  "description": "Exeris Foundation - Generated Angular Frontend",
  "type": "module",
  "engines": {
    "node": ">=24.0.0"
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
    "@angular/animations": "^21.0.0",
    "@angular/cdk": "^21.0.0",
    "@angular/common": "^21.0.0",
    "@angular/compiler": "^21.0.0",
    "@angular/core": "^21.0.0",
    "@angular/forms": "^21.0.0",
    "@angular/platform-browser": "^21.0.0",
    "@angular/platform-browser-dynamic": "^21.0.0",
    "@angular/router": "^21.0.0",
    "rxjs": "~7.8.1",
    "tslib": "^2.8.1",
    "zod": "^3.24.0"
  },
  "devDependencies": {
    "@angular-devkit/build-angular": "^21.0.0",
    "@angular/cli": "^21.0.0",
    "@angular/compiler-cli": "^21.0.0",
    "@tailwindcss/postcss": "^4.0.0",
    "@types/node": "^24.0.0",
    "postcss": "^8.5.0",
    "tailwindcss": "^4.0.0",
    "typescript": "~5.9.3"
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
          "builder": "@angular-devkit/build-angular:application",
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
          "builder": "@angular-devkit/build-angular:dev-server",
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
  // Tailwind CSS v4 uses CSS-first configuration
  // This file is optional but provides customization options
  return `/** @type {import('tailwindcss').Config} */
export default {
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
  // Tailwind CSS v4 uses @import instead of @tailwind directives
  return `/* Exeris Foundation - Global Styles */
/* Tailwind CSS v4 */
@import "tailwindcss";

/* Custom base styles */
@layer base {
  html {
    @apply antialiased;
  }
  
  body {
    @apply bg-gray-100 text-gray-900;
  }
  
  /* Dark mode support */
  .dark body {
    @apply bg-gray-900 text-gray-100;
  }
}

/* Custom component styles */
@layer components {
  .btn-primary {
    @apply rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700 disabled:opacity-50;
  }
  
  .btn-secondary {
    @apply rounded-md bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm border border-gray-300 hover:bg-gray-50;
  }
  
  .input-field {
    @apply mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 sm:text-sm;
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

function generateIndexHtml(): string {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Exeris Foundation</title>
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

// Minimalna implementacja generateSchema
function generateSchema(domain: DomainMetadata, config: GeneratorConfig): { content: string } {
  // Prosty Zod schema placeholder (do rozbudowy)
  const name = domain.entityName;
  return {
    content: `import { z } from 'zod';\n\nexport const ${name}Schema = z.object({\n  // TODO: wygeneruj pola\n});\n`
  };
}

// Minimalna implementacja generateEnums
function generateEnums(enums: EnumMetadata[], config: GeneratorConfig): { content: string } {
  // Prosty enum placeholder (do rozbudowy)
  let content = '';
  for (const en of enums) {
    content += `export enum ${en.name} {\n  // TODO: wartości\n}\n`;
  }
  return { content };
}
