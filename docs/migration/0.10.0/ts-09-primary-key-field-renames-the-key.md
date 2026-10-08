---
title: "0.10.0 migration step: `exeris-codegen-ts`: the emitted model, views and store identify a row by the key `primaryKeyField` names, and always carry it"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `exeris-codegen-ts`: the emitted model, views and store identify a row by the key `primaryKeyField` names, and always carry it

`Compatibility impact: breaking (ADR-092)`, for an application whose metadata sets
`systemFields.primaryKeyField` to a name other than `id`, and for code that reads the key of such an
entity's rows.

From 0.10.0 the emitted TypeScript identifies a row by the field `@ExerisDomain(primaryKeyField = …)`
names, and by `id` when the attribute is absent or blank, the same rule as the generated Java
([ADR-104](../../adr/ADR-104-declared-primary-key-uuid-only.md)). For `primaryKeyField = "invoiceNo"`:

- the entity type carries the key as `invoiceNo?: string`, and `InvoiceCreate`,
  `InvoiceCreateSchema`, `InvoiceUpdate` (`Omit<Invoice, 'invoiceNo'>`) and `InvoiceUpdateSchema`
  leave it out;
- the list tracks, links and deletes by `item.invoiceNo`, the detail view's system panel and title
  read `entity()?.invoiceNo`, the form dispatches its update on `current.invoiceNo`, and the store
  matches `e.invoiceNo === id` in its lookups, updates and deletes;
- a `@View` collection bound to the entity is tracked by `invoiceNo`;
- a foreign-key `<select>` into the entity is valued by `invoiceNo`. A form whose selects point at
  targets with different keys passes each target's key to its `pickerOptions` helper.

The route variable stays `:id` (`/invoices/:id`, `/invoices/:id/edit`), and so do the service's and
the store's `findById(id)`, `update(id, …)`, `delete(id)` and `loadById(id)`: they name the URL
segment, which carries the key's value. An entity that does not set `primaryKeyField` regenerates
byte-identically.

The entity type and schema now always carry the key. The processor records an entity's own fields
only, so an entity whose key is inherited from a superclass got a model without it, and the emitted
list, detail, form and store failed `ng build` with `TS2339: Property 'id' does not exist`. The key
is now the model's first property, an optional UUID string like a declared one, whether or not the
metadata lists it.

**What to do.** Regenerate. Code of your own that reads the key of a renamed-key entity's rows as
`row.id` reads the named property instead; a URL you build from such a row takes its value, as in
`['/invoices', invoice.invoiceNo]`. The Java side of the same change, with the two new processor
refusals, is in the Maven step for `primaryKeyField`.
