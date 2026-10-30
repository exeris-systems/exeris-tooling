---
title: "0.11.0 migration step: metadata gains graph.properties and ui.groups"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### The metadata carries `graph.properties` and `ui.groups`

The processor reads `@GraphProperty` (on fields of a `@Graph` type) into `graphMetadata.properties`,
`[]` when the type declares none, and `@UIGroup` into `uiMetadata.groups`, in the shape the SDK 0.13
reader reads. `-Aexeris.strict` reports neither as unread; it reports `@UIGroup.columns`,
`cssClass` and `visibleWhen` as having no effect.

After regenerating, `target/classes/exeris-metadata/<Entity>.json` carries the two lists. No
generated Java or TypeScript file changes: the graph sync and the generated forms read them in
later 0.11.0 rows.
