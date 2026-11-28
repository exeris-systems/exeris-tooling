/**
 * The presentation attributes of `@Tab` and `@NavMenu`, and the label of an action control, each at a
 * non-default value.
 *
 * Pinned:
 *   - `@NavMenu(parent, expanded)` opens a submenu per parent name, open when a child is expanded;
 *   - `@NavMenu(permissions)` wraps an entry in `auth.hasAnyPermission([...])`, the call
 *     `requireAnyPermission` makes, and the shell injects `AuthService` only then;
 *   - `@NavMenu(badge, badgeColor, tooltip, openInNewTab, routePath, queryParams, divider)` shape the link;
 *   - `@Tab(disabled, lazy, badgeField, badgeColor, cssClass)` shape the strip and the panel of a
 *     detail view and of a form;
 *   - `@Action(label)` names the action's button, in the detail view and in the list row.
 */

import { describe, expect, it } from 'vitest';
import { generateAppStructure } from '../../../src/generators/angular/app-structure-gen.js';
import { DetailGenerator } from '../../../src/generators/angular/detail-gen.js';
import { FormGenerator } from '../../../src/generators/angular/form-gen.js';
import { ListGenerator } from '../../../src/generators/angular/list-gen.js';
import { createGeneratorContext } from '../../../src/core/generator-registry.js';
import { buildGeneratedFiles } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../../src/models/domain-model.js';

const CTX = createGeneratorContext({});

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({
    packageName: 'com.shop',
    fields: [{ name: 'id', type: 'java.util.UUID' }],
    ...overrides,
  });
}

function shell(domains: DomainMetadata[]): string {
  const files = generateAppStructure(domains, [], DEFAULT_CONFIG);
  return files.find((f) => f.path === 'src/app/shell/app-shell.component.ts')!.content;
}

describe('@NavMenu presentation attributes', () => {
  it('opens a submenu per parent, open when a child is expanded, ordered with its siblings', () => {
    const html = shell([
      domain({ entityName: 'Role', navMenu: { group: 'Settings', parent: 'Security', order: 2, expanded: true } }),
      domain({ entityName: 'Policy', navMenu: { group: 'Settings', parent: 'Security', order: 3 } }),
      domain({ entityName: 'Theme', navMenu: { group: 'Settings', parent: 'Look', order: 1 } }),
      domain({ entityName: 'General', navMenu: { group: 'Settings', order: 0 } }),
    ]);
    expect(html).toContain('<details data-testid="nav-submenu-Security" open>');
    expect(html).toContain('<details data-testid="nav-submenu-Look">');
    expect(html.indexOf('routerLink="/generals"')).toBeLessThan(html.indexOf('nav-submenu-Look'));
    expect(html.indexOf('nav-submenu-Look')).toBeLessThan(html.indexOf('nav-submenu-Security'));
    const security = html.slice(html.indexOf('nav-submenu-Security'));
    expect(security.indexOf('routerLink="/roles"')).toBeLessThan(security.indexOf('routerLink="/policies"'));
  });

  it('shows a link with a badge in its colour, a tooltip, a new-tab target and dividers', () => {
    const html = shell([
      domain({
        entityName: 'Order',
        navMenu: { badge: 'NEW', badgeColor: 'warn', tooltip: 'All "open" orders', openInNewTab: true, divider: 'BEFORE' },
      }),
      domain({ entityName: 'Quote', navMenu: { divider: 'AFTER', order: 2 } }),
    ]);
    expect(html).toContain('<span data-testid="nav-badge-Orders" class="ml-auto inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium bg-red-100');
    expect(html).toContain('>NEW</span>');
    expect(html).toContain('title="All &quot;open&quot; orders"');
    expect(html).toContain('target="_blank"');
    expect(html).toContain('rel="noopener noreferrer"');
    expect(html.match(/role="separator"/g)).toHaveLength(2);
    expect(html.indexOf('role="separator"')).toBeLessThan(html.indexOf('routerLink="/orders"'));
  });

  it('defaults a badge to the accent colour and renders no badge for an expression', () => {
    const html = shell([
      domain({ entityName: 'Order', navMenu: { badge: '5' } }),
      domain({ entityName: 'Quote', navMenu: { badge: '@orderService.pendingCount()', order: 2 } }),
    ]);
    expect(html).toContain('bg-teal-100');
    expect(html).not.toContain('pendingCount');
    expect(html).not.toContain('nav-badge-Quotes');
  });

  it('targets routePath and carries queryParams as a router binding', () => {
    const html = shell([
      domain({ entityName: 'Order', navMenu: { routePath: '/orders/open', queryParams: 'status=OPEN&page=1' } }),
    ]);
    expect(html).toContain('routerLink="/orders/open"');
    expect(html).toContain("[queryParams]=\"{ 'status': 'OPEN', 'page': '1' }\"");
    expect(html).not.toContain('routerLink="/orders"');
  });

  it('links an absolute routePath with href and leaves the router out', () => {
    const html = shell([
      domain({ entityName: 'Order', navMenu: { routePath: 'https://example.com/orders', queryParams: 'a=1', openInNewTab: true } }),
    ]);
    expect(html).toContain('href="https://example.com/orders?a=1"');
    expect(html).not.toContain('routerLink="/orders"');
  });

  it('drops template metacharacters from declared query parameters', () => {
    const html = shell([domain({ entityName: 'Order', navMenu: { queryParams: "k=a'b${x}&`z=1" } })]);
    expect(html).toContain("[queryParams]=\"{ 'k': 'abx', 'z': '1' }\"");
    expect(html).not.toContain('${x}');
  });

  it('hides an entry from the names AuthService.hasAnyPermission reads, repeats dropped', () => {
    const html = shell([
      domain({ entityName: 'Order', navMenu: { permissions: ['order:read', 'order:admin', 'order:read'] } }),
      domain({ entityName: 'Quote', navMenu: { order: 2 } }),
    ]);
    expect(html).toContain("@if (auth.hasAnyPermission(['order:read', 'order:admin'])) {");
    expect(html.match(/hasAnyPermission/g)).toHaveLength(1);
    expect(html).toContain("import { AuthService } from '../core/auth.service';");
    expect(html).toContain('protected readonly auth = inject(AuthService);');
  });

  it('hides a group, and a submenu, only when every entry in it is gated', () => {
    const gatedGroup = shell([
      domain({ entityName: 'Order', navMenu: { group: 'Sales', permissions: ['a'], order: 1 } }),
      domain({ entityName: 'Quote', navMenu: { group: 'Sales', permissions: ['b'], order: 2 } }),
    ]);
    expect(gatedGroup).toContain("@if (auth.hasAnyPermission(['a', 'b'])) {\n");
    expect(gatedGroup.indexOf("hasAnyPermission(['a', 'b'])")).toBeLessThan(gatedGroup.indexOf('nav-group-Sales'));

    const openGroup = shell([
      domain({ entityName: 'Order', navMenu: { group: 'Sales', permissions: ['a'] } }),
      domain({ entityName: 'Quote', navMenu: { group: 'Sales', order: 2 } }),
    ]);
    expect(openGroup).not.toContain("hasAnyPermission(['a', 'b'])");
    expect(openGroup).toContain("hasAnyPermission(['a'])");
  });

  it('injects no AuthService when no entry declares permissions', () => {
    const html = shell([domain({ entityName: 'Order', navMenu: { label: 'Orders' } })]);
    expect(html).not.toContain('AuthService');
    expect(html).not.toContain('hasAnyPermission');
  });

  it('emits the auth service seed when only the menu reads permissions, and no interceptor', () => {
    const files = buildGeneratedFiles(
      [domain({ entityName: 'Order', navMenu: { permissions: ['order:read'] } })],
      [],
      DEFAULT_CONFIG,
    );
    const paths = files.map((f) => f.path);
    expect(paths).toContain('src/app/core/auth.service.ts');
    expect(paths).not.toContain('src/app/core/auth.interceptor.ts');
    expect(paths).not.toContain('src/app/core/route-guards.ts');
  });
});

const TABBED = domain({
  entityName: 'Employee',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'first', type: 'String', inCreate: true, inUpdate: true },
    { name: 'last', type: 'String', inCreate: true, inUpdate: true },
    { name: 'notes', type: 'String', inCreate: true, inUpdate: true },
  ],
  uiMetadata: {
    tabs: [
      { name: 'locked', label: 'Locked', order: 1, disabled: true, fields: ['first'] },
      { name: 'general', label: 'General', order: 2, cssClass: 'p-4 md:grid-cols-2 bad"class', fields: ['last'] },
      { name: 'history', label: 'History', order: 3, lazy: true, badgeField: 'notes', badgeColor: 'warn', fields: ['notes'] },
    ],
  },
});

describe.each([
  ['detail view', () => new DetailGenerator().generate(TABBED, CTX)!.content, 'entity()'],
  ['form', () => new FormGenerator().generate(TABBED, CTX)!.content, 'current()'],
])('@Tab presentation attributes — %s', (_name, emit, read) => {
  const content = emit();

  it('makes a disabled tab inert and opens on the first one that is not', () => {
    expect(content).toMatch(/<button[^>]*id="tab-locked"[^>]* disabled aria-disabled="true"[^>]*opacity-50 cursor-not-allowed/);
    expect(content).not.toMatch(/<button[^>]*id="tab-general"[^>]* disabled/);
    expect(content).toContain("readonly activeTab = signal<string>('general');");
  });

  it('adds the declared classes to the panel, keeping only tokens that cannot break the attribute', () => {
    expect(content).toMatch(/id="panel-general"[^>]*class="[^"]*p-4 md:grid-cols-2"/);
    expect(content).not.toContain('bad"class');
  });

  it('shows the badge field beside the label in the declared colour', () => {
    expect(content).toContain(`@if (tabBadge(${read}?.notes); as badge) {<span data-testid="tab-history-badge"`);
    expect(content).toContain('bg-red-100');
    expect(content).toContain('protected tabBadge(value: unknown): string | null');
  });

  it('renders a lazy panel once its tab has been selected', () => {
    expect(content).toContain('readonly loadedTabs = linkedSignal<string, ReadonlySet<string>>({');
    expect(content).toContain("@if (loadedTabs().has('history')) {");
    expect(content).not.toContain("loadedTabs().has('general')");
    expect(content).toMatch(/\blinkedSignal\b[^;]*from '@angular\/core'|linkedSignal,\s*\n?[^]*from '@angular\/core'/);
  });
});

describe('@Tab presentation attributes — unset', () => {
  it('declares no badge, lazy signal or disabled button for a tab that sets none', () => {
    const plain = domain({
      entityName: 'Employee',
      fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'first', type: 'String', inCreate: true }],
      uiMetadata: { tabs: [{ name: 'a', fields: ['first'] }] },
    });
    for (const content of [new DetailGenerator().generate(plain, CTX)!.content, new FormGenerator().generate(plain, CTX)!.content]) {
      expect(content).not.toContain('tabBadge');
      expect(content).not.toContain('loadedTabs');
      expect(content).not.toContain('aria-disabled');
    }
  });

  it('shows no badge for a badgeField that names no field', () => {
    const named = domain({
      entityName: 'Employee',
      fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'first', type: 'String', inCreate: true }],
      uiMetadata: { tabs: [{ name: 'a', badgeField: 'missing', fields: ['first'] }] },
    });
    expect(new DetailGenerator().generate(named, CTX)!.content).not.toContain('tabBadge');
  });
});

describe('@Action(label) names the action control', () => {
  const order = domain({
    entityName: 'Order',
    actions: [
      { name: 'markPaid', methodName: 'markPaid', displayName: 'Mark as paid' },
      { name: 'cancel', methodName: 'cancel' },
    ],
  });

  it('labels the detail button and the list row button by displayName, else the title-cased method', () => {
    const detail = new DetailGenerator().generate(order, CTX)!.content;
    const list = new ListGenerator().generate(order, CTX)!.content;
    for (const content of [detail, list]) {
      expect(content).toMatch(/data-testid="action-mark-paid[^>]*>Mark as paid<\/button>|action-mark-paid-' \+ item[^>]*>Mark as paid<\/button>/);
      expect(content).toMatch(/action-cancel[^>]*>Cancel<\/button>/);
    }
  });

  it('keeps the action addressed by its name, whatever the label says', () => {
    const detail = new DetailGenerator().generate(order, CTX)!.content;
    expect(detail).toContain("runAction('markPaid')");
  });
});
