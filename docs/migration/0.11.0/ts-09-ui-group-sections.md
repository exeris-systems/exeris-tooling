---
title: "0.11.0 migration step: generated create, edit and detail views lay fields out in @UIGroup sections"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

### `exeris-codegen-ts`: generated create, edit and detail views lay fields out in their `@UIGroup` sections

The generated create/edit form component and detail view component lay fields out into their declared
`@UIGroup` sections when an entity's `uiMetadata.groups` is non-empty.

Groups render in `order` (ties broken by declaration order) with the group's label (or name when label
is omitted) as its heading, and description underneath when present. A group declared with
`collapsible: true` renders as a native `<details>` element with `<summary>` (open unless `collapsed: true`),
while non-collapsible groups render as `<section>`. Each section carries `data-testid="group-<name>"`.

Fields listed in a group render inside that group in the group's declared field order. Fields listed in a
group but not rendered by that view (such as fields with `inCreate = false` in a create form, or hidden
fields in a detail view) are skipped. Fields not assigned to any group continue to render after the groups
exactly as before. An entity without `@UIGroup` declarations regenerates byte-identically.
