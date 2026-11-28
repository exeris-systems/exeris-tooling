/**
 * The tab strip a detail view and a form share: one button per `@Tab`, and the attributes of the
 * panel each one opens. Both emitters read the same declaration, so both go through here.
 *
 * `disabled` makes the button inert and keeps the strip from opening on it; `badgeField` and
 * `badgeColor` show a count beside the label; `cssClass` styles the panel; `lazy` renders the panel's
 * content once the tab has been selected, and keeps it afterwards.
 */

import type { DomainMetadata, UITabMetadata } from '../../models/domain-model.js';
import { declaredIconSpan } from './declared-icon.js';
import { attributeText, testIdSegment, tsSingleQuoted, tsTemplateText } from './ts-literal.js';

const TAB_BUTTON_CLASS = 'px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors';
const TAB_ACTIVE_CLASS = 'border-exeris-primary text-exeris-primary font-semibold';
const TAB_IDLE_CLASS = 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200 hover:border-gray-300';

/** Badge tones by the colour name `@Tab(badgeColor)` takes. Full literals, so Tailwind's source scan finds each. */
const BADGE_TONES: Readonly<Record<string, string>> = {
  primary: 'bg-indigo-100 text-indigo-700 dark:bg-indigo-900/40 dark:text-indigo-200',
  accent: 'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-200',
  warn: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-200',
};
const BADGE_BASE = 'ml-2 inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium';

/** The tab the strip opens on: the first that is not disabled, else the first. */
export function initialTab(tabs: readonly UITabMetadata[]): UITabMetadata {
  return tabs.find((tab) => tab.disabled !== true) ?? tabs[0];
}

/** Whether any tab renders its panel only after it has been selected. */
export function hasLazyTab(tabs: readonly UITabMetadata[]): boolean {
  return tabs.some((tab) => tab.lazy === true);
}

/** Whether any tab shows a badge: a field of the entity it can read. */
export function hasTabBadge(domain: DomainMetadata, tabs: readonly UITabMetadata[]): boolean {
  return tabs.some((tab) => badgeProperty(domain, tab) !== undefined);
}

/** The entity property a tab's badge reads, or `undefined` when the tab has none or names no field. */
function badgeProperty(domain: DomainMetadata, tab: UITabMetadata): string | undefined {
  const name = tab.badgeField?.trim();
  return name && (domain.fields ?? []).some((field) => field.name === name) ? name : undefined;
}

/** Class tokens of a declared `cssClass`: the ones that cannot close an attribute or a template. */
export function panelClassTokens(cssClass: string | undefined): string[] {
  return (cssClass ?? '').split(/\s+/).filter((token) => /^[\w:/.%#[\]-]+$/.test(token));
}

/** The class attribute value of a tab panel: `base` followed by the declared classes. */
export function panelClass(base: string, tab: UITabMetadata): string {
  return [...(base === '' ? [] : [base]), ...panelClassTokens(tab.cssClass)].join(' ');
}

/** The class member that turns a badge field's value into the text shown. */
export const TAB_BADGE_MEMBER = [
  '  /** The text of a tab badge: an array\'s length, any other value as text, nothing for no value. */',
  '  protected tabBadge(value: unknown): string | null {',
  '    if (Array.isArray(value)) return String(value.length);',
  "    return value === null || value === undefined || value === '' ? null : String(value);",
  '  }',
];

/** The signal that holds the tabs selected so far, which a lazy panel reads. */
export const LOADED_TABS_MEMBER = [
  '  /** The tabs selected so far; a lazy panel renders once its tab is in it. */',
  '  readonly loadedTabs = linkedSignal<string, ReadonlySet<string>>({',
  '    source: this.activeTab,',
  '    computation: (tab, previous) => new Set([...(previous?.value ?? []), tab]),',
  '  });',
];

/**
 * One tab button. `entityRead` is the expression that reads the shown entity, `entity()` in a detail
 * view and `current()` in a form, whose property the badge shows.
 */
export function tabButton(domain: DomainMetadata, tab: UITabMetadata, indent: string, entityRead: string): string {
  const name = tab.name ?? '';
  const q = tsSingleQuoted(name);
  const segment = testIdSegment(name);
  const label = tsTemplateText(tab.label || tab.name || '');
  const iconSpan = tab.icon ? declaredIconSpan(tab.icon, 'mr-2') : '';
  const disabled = tab.disabled === true;
  const property = badgeProperty(domain, tab);
  const tone = BADGE_TONES[tab.badgeColor ?? 'primary'] ?? BADGE_TONES.primary;
  const badge = property
    ? `@if (tabBadge(${entityRead}?.${property}); as badge) {<span data-testid="tab-${segment}-badge" class="${BADGE_BASE} ${tone}">{{ badge }}</span>}`
    : '';
  const cursor = disabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer';
  const disabledAttrs = disabled ? ' disabled aria-disabled="true"' : '';
  return `${indent}<button type="button" role="tab" id="tab-${segment}" aria-controls="panel-${segment}" [attr.aria-selected]="activeTab() === '${q}'" (click)="activeTab.set('${q}')"${disabledAttrs} data-testid="tab-${segment}" class="${TAB_BUTTON_CLASS} ${cursor}" [class]="activeTab() === '${q}' ? '${TAB_ACTIVE_CLASS}' : '${TAB_IDLE_CLASS}'">${iconSpan}${label}${badge}</button>`;
}

/** The opening of a tab's panel. `classBase` is the panel's own class, empty for none. */
export function tabPanelOpen(tab: UITabMetadata, indent: string, classBase: string): string {
  const name = tab.name ?? '';
  const q = tsSingleQuoted(name);
  const segment = testIdSegment(name);
  const cls = panelClass(classBase, tab);
  return `${indent}<div role="tabpanel" id="panel-${segment}" aria-labelledby="tab-${segment}" [hidden]="activeTab() !== '${q}'" data-testid="tabpanel-${segment}"${cls === '' ? '' : ` class="${attributeText(cls)}"`}>`;
}

/** The lines that open and close a lazy panel's content. */
export function lazyPanelGuard(tab: UITabMetadata, indent: string): { open: string; close: string } {
  return {
    open: `${indent}@if (loadedTabs().has('${tsSingleQuoted(tab.name ?? '')}')) {`,
    close: `${indent}}`,
  };
}
