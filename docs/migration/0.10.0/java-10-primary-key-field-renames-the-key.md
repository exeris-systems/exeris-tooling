---
title: "0.10.0 migration step: `@ExerisDomain(primaryKeyField)` renames the primary key, and the key must be a `UUID` (`EXT-PROC-1018`, `EXT-PROC-1019`)"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `@ExerisDomain(primaryKeyField)` renames the primary key, and the key must be a `UUID` (`EXT-PROC-1018`, `EXT-PROC-1019`)

From 0.10.0 the generated code identifies a row by the field `@ExerisDomain(primaryKeyField = …)`
names, and by `id` when the attribute is absent ([ADR-104](../../adr/ADR-104-declared-primary-key-uuid-only.md)).
For `primaryKeyField = "invoiceNo"`:

- the migration declares `invoice_no UUID PRIMARY KEY DEFAULT gen_random_uuid()`;
- the repository selects, updates and deletes `WHERE invoice_no = ?`, closes every list
  `ORDER BY` with `invoice_no`, and fills a new row's key through `setInvoiceNo(UUID.randomUUID())`;
- the handler, which passes the key to the event publisher as the stream id, the graph sync and the
  generated tests call `getInvoiceNo()` and `setInvoiceNo(...)`;
- a `MANY_TO_ONE` into the entity references `invoices(invoice_no)`; the referencing column keeps its
  name, such as `invoice_id`;
- the OpenAPI entity schema carries the key as `invoiceNo`, and the create and update schemas omit it.

The route variable stays `{id}` (`/invoices/{id}`), and so do the generated `findById`,
`deleteById` and `UUID id` parameter names: they name the URL segment and the operation, not the
field. An entity that does not set `primaryKeyField` regenerates byte-identically.

**An application that already sets `primaryKeyField`** to something other than `id` gets a changed
`CREATE TABLE` migration for that entity: the key column moves from `id` to the named field's
column. Flyway refuses a changed migration that a database has already applied (checksum
mismatch). Before regenerating, decide per entity:

- if no database has applied the entity's migration, regenerate and keep the new migration;
- if one has, either remove `primaryKeyField` to keep `id` as the key, or write the
  `ALTER TABLE … RENAME COLUMN` migration yourself and keep the applied `CREATE TABLE` file
  unchanged (for example with `exeris:detach`). The generator writes only `CREATE TABLE`.

Two new refusals come with the switch, both at the `@ExerisDomain` type:

- `EXT-PROC-1018`: the key field, `id` included, is not a `java.util.UUID`. The kernel identifies an
  entity's event stream and graph node by a UUID. An entity that declared `Long id` or `String id`
  compiled through the processor before and failed in the generated repository at
  `setId(UUID.randomUUID())`; it now fails at the declaration. Declare the key as `UUID` and keep any
  other identifier, such as an order number, as an ordinary unique field.
- `EXT-PROC-1019`: the entity sets `primaryKeyField` to a name other than `id` and also declares a
  field `id`, its own or inherited. Honouring the rename would move an existing key column without a
  diagnostic. Drop `primaryKeyField` to keep `id` as the key; if no database has applied the
  migration, rename or remove the field `id` instead.

`EXT-PROC-1015` now names the effective key: an entity with `primaryKeyField = "invoiceNo"` that
declares no field `invoiceNo` is refused with that name. `-Aexeris.strict` no longer reports
`@ExerisDomain.primaryKeyField` as having no effect. The field-level `@PrimaryKey` marker is still
not read and is still reported by `-Aexeris.strict`; name the key with `primaryKeyField`.

See [the diagnostic reference](../../diagnostics.md) for every identifier.
