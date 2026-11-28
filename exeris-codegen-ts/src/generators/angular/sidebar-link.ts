/**
 * The markup of one sidebar entry declared by `@NavMenu`: its link, the badge, tooltip, target and
 * query it carries, the divider around it, the submenu a `parent` opens, and the permission check
 * that hides it.
 *
 * Every declared value lands inside an attribute of a component template that is itself a TypeScript
 * template literal, so each one passes through `attributeText` or `paramText` first.
 */

import { ownRequirement } from '../../core/route-requirements.js';
import { attributeText, testIdSegment, tsTemplateText } from './ts-literal.js';

/** The class list of the sidebar's icon on a link, and the shape every link shares. */
const LINK_ACTIVE = 'bg-indigo-100 dark:bg-indigo-900 text-exeris-primary-hover dark:text-indigo-200';
const LINK_CLASS = 'group flex items-center px-2 py-2 text-base font-medium rounded-md text-gray-600 hover:bg-gray-50 dark:text-gray-300 dark:hover:bg-gray-700';

/** Badge tones by the colour name `@NavMenu(badgeColor)` takes. Full literals, so Tailwind's source scan finds each. */
const BADGE_TONES: Readonly<Record<string, string>> = {
  primary: 'bg-indigo-100 text-indigo-700 dark:bg-indigo-900/40 dark:text-indigo-200',
  accent: 'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-200',
  warn: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-200',
};
const BADGE_BASE = 'ml-auto inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium';
const SEPARATOR = '<div role="separator" class="my-2 border-t border-gray-200 dark:border-gray-700"></div>';

/** A query key or value as it sits inside a single-quoted literal of a template binding. */
function paramText(text: string): string {
  return text.replace(/[`$\\{}<>"']/g, '');
}

function decoded(text: string): string {
  try {
    return decodeURIComponent(text.replace(/\+/g, ' '));
  } catch {
    return text;
  }
}

/** `a=1&b=2` as ordered pairs; a pair with no key is dropped and a later repeat of a key replaces the earlier one. */
export function parseQuery(query: string): Array<readonly [string, string]> {
  const pairs = new Map<string, string>();
  for (const part of query.split('&')) {
    if (part === '') continue;
    const at = part.indexOf('=');
    const key = paramText(decoded(at < 0 ? part : part.slice(0, at))).trim();
    if (key === '') continue;
    pairs.set(key, paramText(decoded(at < 0 ? '' : part.slice(at + 1))));
  }
  return [...pairs];
}

/** A badge declared as an expression, which the generated shell has nothing to evaluate. */
export function isBadgeExpression(badge: string): boolean {
  return badge.startsWith('@') || badge.startsWith('#');
}

export interface SidebarLink {
  readonly path: string;
  readonly label: string;
  readonly iconHtml: string;
  /** `@NavMenu(routePath)`, trimmed; replaces `path` as the link's target. */
  readonly routePath?: string;
  /** `@NavMenu(queryParams)`, trimmed. */
  readonly queryParams?: string;
  readonly badge?: string;
  readonly badgeColor?: string;
  readonly tooltip?: string;
  readonly openInNewTab?: boolean;
  readonly divider?: 'NONE' | 'BEFORE' | 'AFTER';
}

/** The permission names a `@NavMenu(permissions)` declares, repeats dropped, in declaration order. */
export function navScopes(permissions: readonly string[] | undefined): readonly string[] {
  const requirement = ownRequirement({ permissions });
  return requirement?.kind === 'anyScope' ? (requirement.scopes ?? []) : [];
}

/** Wraps markup in the check that admits a signed-in user holding any one of `scopes`. */
export function gated(scopes: readonly string[], html: string): string {
  const names = scopes.map((scope) => `'${paramText(scope)}'`).join(', ');
  return `            @if (auth.hasAnyPermission([${names}])) {\n${html}\n            }`;
}

const EXTERNAL_URL = /^https?:\/\//i;

/** One sidebar link with the attributes the declaration sets; with none set it is the plain link. */
export function renderSidebarLink(item: SidebarLink): string {
  const declared = (item.routePath ?? '').trim();
  const external = EXTERNAL_URL.test(declared);
  const queryAt = declared.indexOf('?');
  const internalPath = external ? '' : (queryAt < 0 ? declared : declared.slice(0, queryAt)).replace(/^\/+/, '');
  const pathQuery = !external && queryAt >= 0 ? declared.slice(queryAt + 1) : '';
  const pairs = parseQuery([pathQuery, item.queryParams ?? ''].filter((q) => q !== '').join('&'));

  const attrs: string[] = [];
  if (external) {
    const separator = declared.includes('?') ? '&' : '?';
    const query = item.queryParams ? `${separator}${item.queryParams}` : '';
    attrs.push(`href="${attributeText(declared + query)}"`);
  } else {
    attrs.push(`routerLink="/${attributeText(internalPath || item.path)}"`);
    if (pairs.length > 0) {
      attrs.push(`[queryParams]="{ ${pairs.map(([key, value]) => `'${key}': '${value}'`).join(', ')} }"`);
    }
  }
  if (item.openInNewTab === true) attrs.push('target="_blank"', 'rel="noopener noreferrer"');
  if (item.tooltip) attrs.push(`title="${attributeText(item.tooltip)}"`);
  if (!external) attrs.push(`routerLinkActive="${LINK_ACTIVE}"`);
  attrs.push(`class="${LINK_CLASS}"`);

  const badge = item.badge?.trim();
  const badgeHtml = badge && !isBadgeExpression(badge)
    ? `\n              <span data-testid="nav-badge-${testIdSegment(item.label)}" class="${BADGE_BASE} ${BADGE_TONES[item.badgeColor ?? 'accent'] ?? BADGE_TONES.accent}">${attributeText(badge)}</span>`
    : '';

  const link = `
            <a ${attrs.join('\n               ')}>
              ${item.iconHtml}
              ${item.label}${badgeHtml}
            </a>`;
  const before = item.divider === 'BEFORE' ? `\n            ${SEPARATOR}` : '';
  const after = item.divider === 'AFTER' ? `\n            ${SEPARATOR}` : '';
  return `${before}${link}${after}`;
}

/** A submenu: the parent's name as its heading, the children inside, open when `open`. */
export function renderSubmenu(name: string, childrenHtml: string, open: boolean): string {
  return `
            <details data-testid="nav-submenu-${testIdSegment(name)}"${open ? ' open' : ''}>
              <summary class="px-2 py-2 text-base font-medium text-gray-600 dark:text-gray-300 cursor-pointer">${tsTemplateText(name)}</summary>
              <div class="ml-4 space-y-1">
${childrenHtml}
              </div>
            </details>`;
}
