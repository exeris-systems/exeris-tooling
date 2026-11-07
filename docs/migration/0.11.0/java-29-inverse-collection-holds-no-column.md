---
title: "0.11.0 migration step: a mappedBy collection holds no column, and any other List of entities is refused"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### A mappedBy collection holds no column, and any other List of entities is refused

A `List` field that `@Relationship(relationshipType = ONE_TO_MANY, mappedBy = "…")` declares is the
inverse side of the target's `MANY_TO_ONE`: its rows are the target's rows, held through the key
column `mappedBy` names. The generated code stored the field anyway, as a `VARCHAR(255)` JSON column
of the owner's table with an index on it, and bound and read it as JSON, so the collection was a
copy that no constraint, no tenant scope and no write of the target kept in step.

A regenerated tree now holds an inverse collection outside the owner's row:

- `V{tier}{nnnnnn}__create_{table}.sql` declares no column and no index for it;
- `{Entity}Repository` neither binds nor reads it. A repository whose only `List` field was the
  inverse no longer imports Jackson;
- `{Entity}Handler` resets it to `null` after decoding a create or an update, after the `@Blob`
  fields, so a value a client sends never reaches the service;
- the OpenAPI document declares it `readOnly`, an array or `null` with no `items` (the entity's own
  document defines no schema of the target), with the
  description `Always null: the rows are listed on GET /invoice-lines?invoiceId={id}`, and leaves it
  out of the create and update schemas. The rows are read from the target's list route with the
  back-reference's key filter, which every list route already serves;
- the processor requires its setter and no getter (`EXT-PROC-1034`), as for a `@Blob` field: the
  handlers reset it, and nothing generated reads it.

`mappedBy` is matched against the target's `MANY_TO_ONE` relationships back to the owner, by
relationship name, field name, or key base: `invoiceId` and `invoice` both name
`@Relationship(targetEntity = Invoice.class) UUID invoiceId`.

`cascadeDelete = true` on the collection side now sets `ON DELETE CASCADE` on the target's
foreign key, the constraint the back-reference holds, as `cascadeDelete` on the `MANY_TO_ONE`
side already did. For the SDK example `Invoice.lines`:

```sql
ALTER TABLE invoice_lines ADD CONSTRAINT fk_invoice_lines_invoice_id FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE;
```

A cascading constraint does not remove the children of a `softDelete` entity, since its delete
keeps the row.

Every other `List` whose element is an entity of the build is refused with `EXT-GEN-3003` before
anything is written: one without `@Relationship`, a `ONE_TO_MANY` without `mappedBy`, a
`MANY_TO_MANY`, and an inverse whose `mappedBy` names no `MANY_TO_ONE` of the target back to the
owner, whose target is no entity of the build, or whose element is not its `targetEntity`. The
message names the field and the remedy:

```
com.shop.domain.Invoice.lines : java.util.List<com.shop.domain.InvoiceLine> (a collection of the
entity InvoiceLine, which no column stores; read it from InvoiceLine's rows with
@Relationship(targetEntity = InvoiceLine.class, relationshipType = ONE_TO_MANY, mappedBy =
"invoiceId"), or hold their keys: List<UUID> invoiceLineIds)
```

A `List` of values that are not entities (`List<String>`, `List<UUID>`, a record) is unchanged: a
JSON column. A `MANY_TO_MANY` held as `List<UUID>` keys stays such a column. No join table is
emitted for it, and its `cascadeDelete` has no effect.

**What breaks.**

- The create-table migration of an entity with an inverse collection loses a column and an index,
  and the foreign-key migration `V3000000__foreign_keys.sql` changes when an inverse declares
  `cascadeDelete`. A database that has applied the earlier files fails Flyway's checksum
  validation. Either keep the earlier files and add a forward migration of your own, such as

  ```sql
  DROP INDEX IF EXISTS idx_invoices_lines;
  ALTER TABLE invoices DROP COLUMN lines;
  ALTER TABLE invoice_lines DROP CONSTRAINT fk_invoice_lines_invoice_id;
  ALTER TABLE invoice_lines ADD CONSTRAINT fk_invoice_lines_invoice_id FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE;
  ```

  or commit the regenerated files and run `flyway repair`, per your Flyway policy. A database that
  has not applied them needs neither.
- A client that sent the collection in a create or update body, or read it from a response, gets
  `null`: read the rows from the target's list route, `GET {target path}?{key}={id}`, and write
  them through the target's own routes.
- A build with a refused `List` of entities stops at code generation. Declare the target's key to
  the owner and make the field the inverse of it, or hold the targets' keys as `List<UUID>`.

**Where it applies.** Only to entities that declare a `List` of entities, and to the foreign-key
migration when an inverse declares `cascadeDelete`. Every other generated file is byte-identical to
the previous release.
