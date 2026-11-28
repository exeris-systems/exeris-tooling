---
title: "0.12.0 migration step: `exeris-codegen-ts`: a childless entity-bound LIST iterates the store"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-22
---

### `exeris-codegen-ts`: a childless entity-bound LIST iterates the store

A `LIST` block bound to an entity and declared without children used to render an empty element although the page component loaded the store. It now renders `@for (<entity> of <entity>Store.entities(); track <entity>.<primary key>)` with one `<li>` per row, showing the bound `path` when the binding names one. A childless entity-bound `GRID` still renders an empty element.

Regenerate; a page with such a block changes. Nothing else needs doing.
