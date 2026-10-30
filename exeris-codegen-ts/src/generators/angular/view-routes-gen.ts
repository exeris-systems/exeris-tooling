/**
 * The view-routes aggregate: one `view.routes.ts` exporting every `@View` route as a single
 * `Routes` array, and under `render: 'ssg'`, `view.routes.server.ts` exporting the prerenderable
 * view routes as a `ServerRoute[]` array.
 *
 * Emitted when the scaffold is off. The scaffold's `app.routes.ts` and `app.routes.server.ts` spread
 * each per-view route const itself; without it the routes files belong to the consumer, which then
 * imports one array instead of one const per view and keeps its own list in step with the views.
 *
 * The files sit at the root of the generated tree, where `app.routes.ts` and `app.routes.server.ts`
 * sit with the scaffold on, so they name each per-view route by the same specifier the app shell
 * uses. Views are in the app shell's order (route path, then name), so the arrays are independent
 * of the order the metadata files were read in. No redirect is emitted: which page `''` lands on
 * is the consumer's routing decision.
 */

import type { ViewMetadata } from '../../models/domain-model.js';
import type { OutputFile } from '../../orchestrator.js';
import { fileHeaderLines } from '../file-header.js';
import { prerenderedViewPaths, sortViews } from './app-structure-gen.js';
import { tsSingleQuoted } from './ts-literal.js';
import { viewRouteConstName, viewRouteImportPath } from './view-gen.js';

/** Path of the aggregate, relative to the generated tree's root. */
export const VIEW_ROUTES_PATH = 'view.routes.ts';

/** The exported name of the aggregate array. */
export const VIEW_ROUTES_CONST = 'viewRoutes';

/** Path of the prerender aggregate, relative to the generated tree's root. */
export const VIEW_SERVER_ROUTES_PATH = 'view.routes.server.ts';

/** The exported name of the prerender aggregate array. */
export const VIEW_SERVER_ROUTES_CONST = 'viewServerRoutes';

/** The aggregate for `views`, or null when there is no view to route to. */
export function generateViewRoutesAggregate(views: ViewMetadata[]): OutputFile | null {
  if (views.length === 0) return null;
  const sorted = sortViews(views);

  const lines: string[] = [
    ...fileHeaderLines({
      title: 'Routes of every generated @View page, as one array.',
      notes: [`Spread into the application's routes: \`...${VIEW_ROUTES_CONST}\`.`],
      doNotEdit: 'on-provenance',
    }),
    '',
    "import { Routes } from '@angular/router';",
  ];
  for (const view of sorted) {
    lines.push(`import { ${viewRouteConstName(view)} } from '${viewRouteImportPath(view)}';`);
  }
  lines.push('', `export const ${VIEW_ROUTES_CONST}: Routes = [`);
  for (const view of sorted) {
    lines.push(`  ...${viewRouteConstName(view)},`);
  }
  lines.push('];', '');

  return { path: VIEW_ROUTES_PATH, content: lines.join('\n') };
}

/**
 * The server-routes aggregate: one `view.routes.server.ts` exporting every prerenderable `@View`
 * route as a `ServerRoute[]` array.
 *
 * Emitted under `render: 'ssg'` when the scaffold is off. The owning application's
 * `app.routes.server.ts` spreads this array (`...viewServerRoutes`) before its own fallback route.
 * It lists the views the scaffold's server routes would prerender, in the app shell's order, and no
 * `''` redirect and no `**` entry, which are the consumer's routing decisions.
 */
export function generateViewServerRoutesAggregate(views: ViewMetadata[]): OutputFile | null {
  if (views.length === 0) return null;
  const paths = prerenderedViewPaths(sortViews(views));

  const ssrImports = paths.length > 0 ? 'RenderMode, ServerRoute' : 'ServerRoute';
  const lines: string[] = [
    ...fileHeaderLines({
      title: 'Server routes of every prerenderable @View page, as one array.',
      notes: [`Spread into the application's server routes: \`...${VIEW_SERVER_ROUTES_CONST}\`.`],
      doNotEdit: 'on-provenance',
    }),
    '',
    `import { ${ssrImports} } from '@angular/ssr';`,
    '',
    `export const ${VIEW_SERVER_ROUTES_CONST}: ServerRoute[] = [`,
  ];
  for (const path of paths) {
    lines.push(`  { path: '${tsSingleQuoted(path)}', renderMode: RenderMode.Prerender },`);
  }
  lines.push('];', '');

  return { path: VIEW_SERVER_ROUTES_PATH, content: lines.join('\n') };
}
