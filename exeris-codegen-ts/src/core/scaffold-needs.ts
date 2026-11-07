/**
 * What the app scaffold has to provide, derived from the metadata and the files emitted beside it.
 *
 * The scaffold (`app.config.ts`, `package.json`, `proxy.conf.js`, the environment files) wires
 * pieces that only some apps use. Which ones an app uses is a property of its emitted source, not
 * of any single config flag: a service, a store, a stream client, a spec or a `@View` page bound to
 * an entity each reach the network through a different emitter, and a new emitter must not need a
 * new flag here to be accounted for. So the scaffold reads the import specifiers of the composed
 * `src/app` tree, once, after every per-entity, per-view and peer emitter has run.
 *
 * The one fact the emitted files cannot show is an API the consumer's own code calls: an
 * entity is served by the kernel application whether or not its TS service is emitted, so its app
 * keeps the HTTP wiring with every client emitter turned off.
 *
 * Tailwind plugins are accounted for the same way: the typography plugin is installed when an
 * emitted template names one of its `prose` classes, not when some block type or flag is set, so
 * any emitter that adopts `prose` brings the plugin with it and an app that never renders rich text
 * keeps the scaffold without it. The Material Symbols font is accounted for alike: it is installed
 * when an emitted template renders a declared icon name through the font's class.
 *
 * An app with no backend is a first-class shape (a `@View`-only front with authored content), not a
 * second backend target: the kernel stays the only server the emitted code can address. Without a
 * backend the scaffold simply omits the HTTP wiring nothing uses.
 */

import type { DomainMetadata } from '../models/domain-model.js';
import type { RouteRequirementRow } from './route-requirements.js';

/**
 * The class the `material-symbols` package's `outlined.css` maps to the Material Symbols font. An
 * emitted template names it on an element whose text is a Material icon name.
 */
export const MATERIAL_SYMBOLS_CLASS = 'material-symbols-outlined';

/** The HTTP client entry point. Every emitter that reaches the kernel application imports it. */
const HTTP_CLIENT_MODULE = '@angular/common/http';

export interface ScaffoldNeeds {
  /**
   * The app has an API to call: an entity the kernel application serves, or an emitted file
   * that imports the HTTP client. When true the scaffold carries `provideHttpClient()`, the
   * dev-server proxy, the `apiUrl` environment entries and its full, fixed dependency set; when
   * false it carries none of the HTTP wiring.
   */
  readonly backend: boolean;
  /**
   * The npm packages the emitted files import (`zod`, `@angular/cdk`, …), as package names: a
   * subpath import counts for its package. Membership only; never iterated into output.
   */
  readonly packages: ReadonlySet<string>;
  /**
   * An emitted template uses the `@tailwindcss/typography` plugin: a `class` attribute names
   * `prose` or a `prose-*` modifier, under any variant (`dark:prose-invert`). When true the scaffold
   * installs the plugin and loads it in `styles.css`; when false it does neither.
   */
  readonly typography: boolean;
  /**
   * An emitted template renders a declared Material icon name: a `class` attribute names the
   * Material Symbols class. When true the scaffold installs the `material-symbols` font package and
   * loads its stylesheet in `styles.css`; absent or false, it does neither.
   */
  readonly materialSymbols?: boolean;
  /**
   * The tree carries the bearer interceptor (`core/auth.interceptor.ts`), emitted when some route
   * demands identity. When true `app.config.ts` registers it through
   * `provideHttpClient(withInterceptors([authInterceptor]))`; absent or false, it registers none.
   */
  readonly authInterceptor?: boolean;
  /**
   * The access each generated route demands (ADR-105). `app.routes.ts` guards an entity page by the
   * row of the backend route the page calls first; absent or empty, no page has a guard.
   */
  readonly routeRequirements?: readonly RouteRequirementRow[];
}

/**
 * The needs of an app with a backend. `generateAppStructure` defaults to it, so a caller that
 * composes the scaffold on its own gets the backend scaffold.
 */
export const BACKEND_SCAFFOLD_NEEDS: ScaffoldNeeds = { backend: true, packages: new Set<string>(), typography: false };

/**
 * Static `import … from '…'`, `export … from '…'` and side-effect `import '…'` declarations that
 * start a line. Emitted modules put their imports at column 0; an indented occurrence is template or
 * string content, and a dynamic `import('…')` is always a relative lazy route.
 */
const IMPORT_SPECIFIER = /^(?:import|export)\b(?:[^'"`;]*?\bfrom\s*)?\s*['"]([^'"]+)['"]/gm;

/** A static `class="…"` attribute's value. A `[class]` binding is an expression, not a class list. */
const CLASS_ATTRIBUTE = /(?<![\w-])class="([^"]*)"/g;

/** A typography plugin class, its variant prefixes removed: `prose`, `prose-invert`, `prose-lg`, … */
const PROSE_CLASS = /^prose(?:-[a-z0-9-]+)?$/;

/** Whether a file's static class attributes name a class `matches` accepts, variant prefixes removed. */
function usesClass(content: string, matches: (className: string) => boolean): boolean {
  for (const match of content.matchAll(CLASS_ATTRIBUTE)) {
    for (const token of match[1].split(/\s+/)) {
      if (matches(token.slice(token.lastIndexOf(':') + 1))) return true;
    }
  }
  return false;
}

/** Whether a file's static class attributes name a typography plugin class. */
function usesTypography(content: string): boolean {
  return usesClass(content, (className) => PROSE_CLASS.test(className));
}

/** Whether a file's static class attributes name the Material Symbols class. */
export function usesMaterialSymbols(content: string): boolean {
  return usesClass(content, (className) => className === MATERIAL_SYMBOLS_CLASS);
}

/** `@scope/name/sub` → `@scope/name`; `name/sub` → `name`; a relative specifier → null. */
function packageOf(specifier: string): string | null {
  if (specifier.startsWith('.') || specifier.startsWith('/')) return null;
  const parts = specifier.split('/');
  return specifier.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0];
}

/** Derive the scaffold's needs from the domain set and the emitted files' import declarations. */
export function deriveScaffoldNeeds(
  domains: readonly DomainMetadata[],
  files: ReadonlyArray<{ readonly content: string }>,
): ScaffoldNeeds {
  const packages = new Set<string>();
  let backend = domains.length > 0;
  let typography = false;
  let materialSymbols = false;
  for (const file of files) {
    typography ||= usesTypography(file.content);
    materialSymbols ||= usesMaterialSymbols(file.content);
    for (const match of file.content.matchAll(IMPORT_SPECIFIER)) {
      const specifier = match[1];
      if (specifier === HTTP_CLIENT_MODULE || specifier.startsWith(`${HTTP_CLIENT_MODULE}/`)) {
        backend = true;
      }
      const pkg = packageOf(specifier);
      if (pkg) packages.add(pkg);
    }
  }
  return { backend, packages, typography, materialSymbols };
}
