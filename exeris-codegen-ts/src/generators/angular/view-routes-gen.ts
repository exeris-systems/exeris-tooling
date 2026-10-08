/**
 * The view-routes aggregate: one `view.routes.ts` exporting every `@View` route as a single
 * `Routes` array.
 *
 * Emitted when the scaffold is off. The scaffold's `app.routes.ts` spreads each per-view route
 * const itself; without it the routes file belongs to the consumer, which then imports one array
 * instead of one const per view and keeps its own list in step with the views.
 *
 * The file sits at the root of the generated tree, where `app.routes.ts` sits with the scaffold
 * on, so it names each per-view route by the same specifier the app shell uses. Views are in
 * the app shell's order (route path, then name), so the array is independent of the order the
 * metadata files were read in. No redirect is emitted: which page `''` lands on is the
 * consumer's routing decision.
 */

import type { ViewMetadata } from '../../models/domain-model.js';
import type { OutputFile } from '../../orchestrator.js';
import { fileHeaderLines } from '../file-header.js';
import { sortViews } from './app-structure-gen.js';
import { viewRouteConstName, viewRouteImportPath } from './view-gen.js';

/** Path of the aggregate, relative to the generated tree's root. */
export const VIEW_ROUTES_PATH = 'view.routes.ts';

/** The exported name of the aggregate array. */
export const VIEW_ROUTES_CONST = 'viewRoutes';

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
