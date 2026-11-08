/**
 * The markup of an icon a consumer declares on `@NavMenu(icon, groupIcon)` or `@Tab(icon)`.
 *
 * The SDK documents those attributes as Material icon names (`shopping_cart`, `badge`). A name is a
 * ligature: it renders as a glyph only inside an element of the Material Symbols class, with the
 * font loaded. Any other declared text, an emoji for example, is rendered as itself. The scaffold
 * loads the font when an emitted template names the class (core/scaffold-needs), so an app that
 * declares no icon name has neither the font nor the class.
 */

import { MATERIAL_SYMBOLS_CLASS } from '../../core/scaffold-needs.js';
import { tsTemplateText } from './ts-literal.js';

/** A Material icon name: lowercase letters, digits and underscores, as the font's ligatures are named. */
const MATERIAL_ICON_NAME = /^[a-z0-9_]+$/;

/** Whether declared icon text is a Material icon name, so it renders through the font. */
export function isMaterialIconName(icon: string): boolean {
  return MATERIAL_ICON_NAME.test(icon);
}

/**
 * A `<span>` rendering a declared icon, with `classes` for its spacing and size. A Material icon
 * name, surrounding whitespace aside, is a ligature in the Material Symbols class, hidden from
 * assistive technology, which would otherwise read the name aloud beside the label. Other text is a
 * plain span, its template metacharacters dropped.
 */
export function declaredIconSpan(icon: string, classes: string): string {
  const name = icon.trim();
  if (isMaterialIconName(name)) {
    return `<span class="${MATERIAL_SYMBOLS_CLASS} ${classes}" aria-hidden="true">${name}</span>`;
  }
  return `<span class="${classes}">${tsTemplateText(icon)}</span>`;
}
