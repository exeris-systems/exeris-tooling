/**
 * What the app scaffold has to provide, derived from the metadata and the files emitted beside it.
 *
 * The scaffold (`app.config.ts`, `package.json`, `proxy.conf.json`, the environment files) wires
 * pieces that only some apps use. Which ones an app uses is a property of its emitted source, not
 * of any single config flag: a service, a store, a stream client, a spec or a `@View` page bound to
 * an entity each reach the network through a different emitter, and a new emitter must not need a
 * new flag here to be accounted for. So the scaffold reads the import specifiers of the composed
 * `src/app` tree, once, after every per-entity, per-view and peer emitter has run.
 *
 * The one fact the emitted files cannot show is an API the consumer's own code calls: a visible
 * entity is served by the kernel application whether or not its TS service is emitted, so its app
 * keeps the HTTP wiring with every client emitter turned off.
 *
 * An app with no backend is a first-class shape (a `@View`-only front with authored content), not a
 * second backend target: the kernel stays the only server the emitted code can address. Without a
 * backend the scaffold simply omits the HTTP wiring nothing uses.
 */

import type { DomainMetadata } from '../models/domain-model.js';

/** The HTTP client entry point. Every emitter that reaches the kernel application imports it. */
const HTTP_CLIENT_MODULE = '@angular/common/http';

export interface ScaffoldNeeds {
  /**
   * The app has an API to call: a visible entity the kernel application serves, or an emitted file
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
}

/**
 * The needs of an app with a backend. `generateAppStructure` defaults to it, so a caller that
 * composes the scaffold on its own gets the scaffold it always got.
 */
export const BACKEND_SCAFFOLD_NEEDS: ScaffoldNeeds = { backend: true, packages: new Set<string>() };

/**
 * Static `import … from '…'`, `export … from '…'` and side-effect `import '…'` declarations that
 * start a line. Emitted modules put their imports at column 0; an indented occurrence is template or
 * string content, and a dynamic `import('…')` is always a relative lazy route.
 */
const IMPORT_SPECIFIER = /^(?:import|export)\b(?:[^'"`;]*?\bfrom\s*)?\s*['"]([^'"]+)['"]/gm;

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
  let backend = domains.some((d) => !d.internalApi?.hidden);
  for (const file of files) {
    for (const match of file.content.matchAll(IMPORT_SPECIFIER)) {
      const specifier = match[1];
      if (specifier === HTTP_CLIENT_MODULE || specifier.startsWith(`${HTTP_CLIENT_MODULE}/`)) {
        backend = true;
      }
      const pkg = packageOf(specifier);
      if (pkg) packages.add(pkg);
    }
  }
  return { backend, packages };
}
