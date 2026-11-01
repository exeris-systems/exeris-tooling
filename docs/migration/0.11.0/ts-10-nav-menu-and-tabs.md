---
title: "0.11.0 migration step: sidebar groups navigation from @NavMenu and views render @Tab navigation"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

### `exeris-codegen-ts`: app shell sidebar renders `@NavMenu` and form/detail views render `@Tab` navigation

When at least one entity declares `navMenu`, the generated app shell sidebar organizes navigation items
by group (`navMenu.group`, ordered by `navMenu.groupOrder` then group name), with ungrouped entities
placed first in their existing layout. Within groups, items are sorted by `navMenu.order` then entity name.
Entity navigation labels fall back to `navMenu.label` when declared, and icons render via `navMenu.icon`
with `navMenu.groupIcon` displayed on group headers. Entities with `navMenu.hidden = true` are excluded from
sidebar navigation while preserving their underlying routes. When no entity declares `navMenu`, sidebar emission
remains byte-identical.

When an entity declares `uiMetadata.tabs`, the generated form and detail views render an accessible tab strip
(`role="tablist"`, `role="tab"` buttons with `aria-controls`/`aria-selected`, and `role="tabpanel"` with `[hidden]`).
Tabs are ordered by `order` then declaration order, displaying each tab's icon and label (falling back to its name).
Active tab state is held in a component `signal`, with the first tab active by default. Tab panels render the fields
listed in `fields`, with any `@UIGroup` sections restricted to member fields of that tab. Fields not belonging to any
tab render after the tab strip. Form controls remain within the single `<form>` element, preserving existing validation
and submission workflows. An entity without declared tabs regenerates byte-identically.
