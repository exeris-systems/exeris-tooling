---
title: "0.12.0 migration step: `exeris-codegen-ts`: a list shows a search box where `@UI(searchable)` is set"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-28
---

### `exeris-codegen-ts`: a list shows a search box where `@UI(searchable)` is set

The generated list page offers a search box when the entity's `uiMetadata.searchable` is `true` and at least one `@Field(searchable)` String field exists. The box sends the list route's `search` parameter, applied when the text is committed (Enter or leaving the field), trimmed and cut to the 100 characters the route accepts:

```html
<input
  #searchBox
  type="search"
  (change)="setSearch(searchBox.value)"
  placeholder="Search consignments"
  aria-label="Search consignments"
  maxlength="100"
  data-testid="list-search"
  class="exeris-input sm:w-auto"
/>
```

The list component gains `searchText`, `setSearch(value)` and a `search` member in the page request; `filtersActive` also holds while a term is set.

`@UI` writes `searchable` as `true` when the attribute is left unset, so an entity that carries a type-level `@UI` and a searchable field now shows the box after regenerating. To keep the list as it was, set `@UI(searchable = false)`. An entity with no type-level `@UI`, or with no searchable String field, emits no box.
