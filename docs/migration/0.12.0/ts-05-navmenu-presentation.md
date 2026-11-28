---
title: "0.12.0 migration step: `exeris-codegen-ts`: `@NavMenu` parent, permissions, badge and the other presentation attributes shape the sidebar"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-28
---

### `exeris-codegen-ts`: `@NavMenu` parent, permissions, badge and the other presentation attributes shape the sidebar

When some entity declares `@NavMenu`, the sidebar of `app.component.ts` reads these attributes besides the ones it read before (`group`, `label`, `icon`, `order`, `groupOrder`, `groupIcon`, `hidden`):

- `parent` opens a `<details data-testid="nav-submenu-<parent>">` inside the entity's group, holding every entry of that parent name; it is `open` when any of its entries sets `expanded`, and it sorts among its siblings by the smallest `order` it holds.
- `badge` and `badgeColor` (`primary`, `accent` or `warn`, `accent` when unset) render a `nav-badge-<label>` pill after the label. A badge that is an expression (starts with `@` or `#`) renders nothing: the shell has nothing to evaluate it with.
- `tooltip` becomes `title`, `openInNewTab` adds `target="_blank" rel="noopener noreferrer"`, and `divider` (`BEFORE` or `AFTER`) adds a `role="separator"` rule around the link.
- `routePath` replaces the link's route; an `http://` or `https://` value is linked with `href` and no router. `queryParams` (`key=value&key=value`) become the link's `[queryParams]`, or are appended to an absolute `href`.
- `permissions` hides the entry unless `AuthService.hasAnyPermission(...)` admits the signed-in user, the call `requireAnyPermission` makes for a guarded route. Repeated names are dropped as the guards drop them. A group or submenu whose entries are all gated is hidden with them.

```html
@if (auth.hasAnyPermission(['consignment:read'])) {

<a routerLink="/consignments"
   [queryParams]="{ 'state': 'open', 'page': '1' }"
   target="_blank"
   rel="noopener noreferrer"
   title="Goods in transit"
   routerLinkActive="..."
   class="...">
  <span class="mr-3 text-xl">📁</span>
  Consignments
  <span data-testid="nav-badge-Consignments" class="... bg-indigo-100 ...">NEW</span>
</a>
<div role="separator" class="my-2 border-t border-gray-200 dark:border-gray-700"></div>
}
```

When an entry declares `permissions`, `AppComponent` imports `AuthService` from `./core/auth.service` and injects it as `auth`. That file is the seed the route guards use; it is now also emitted when no route demands identity but a menu entry declares permissions (without the interceptor or the guards). `@NavMenu(roles)` is still not read.

An entity with no `@NavMenu` keeps the sidebar it had. An entry that sets none of these attributes is emitted as before.
