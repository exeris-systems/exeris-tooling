---
title: "0.12.0 migration step: `exeris-codegen-ts`: `@Tab` lazy, badge, disabled and cssClass shape the tab strip"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-28
---

### `exeris-codegen-ts`: `@Tab` lazy, badge, disabled and cssClass shape the tab strip

The tab strip of the detail view and of the form reads the attributes below. A tab that sets none of them is emitted as before.

- `disabled` makes the button inert (`disabled aria-disabled="true"`, dimmed) and the strip opens on the first tab that is not disabled.
- `cssClass` adds the declared classes to the tab's panel; a token that could break the attribute is dropped.
- `badgeField` and `badgeColor` (`primary`, `accent` or `warn`, `primary` when unset) show a count beside the label, read from the loaded record: an array's length, any other value as text, nothing when it is empty. A `badgeField` that names no field of the entity shows nothing. A form shows the badge once the record to edit has loaded.
- `lazy` renders the panel's content once its tab has been selected and keeps it afterwards, through a `loadedTabs` signal:

```html
<div role="tabpanel" id="panel-details" aria-labelledby="tab-details" [hidden]="activeTab() !== 'details'" data-testid="tabpanel-details">
  @if (loadedTabs().has('details')) {
    ...
  }
</div>
```

```html
<span class="mr-2">📋</span>Details@if (tabBadge(entity()?.parcels); as badge) {<span data-testid="tab-details-badge" class="ml-2 inline-flex ... bg-red-100 ...">{{ badge }}</span>}
```

The component gains `loadedTabs` and `tabBadge(value)` only when a tab uses them. `disabledWhen` and `visibleWhen` are still not read.
