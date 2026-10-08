---
title: "ADR-104 — The generated code identifies a row by the primary key the entity names, and that key is a UUID"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-104
---

# ADR-104 — The generated code identifies a row by the primary key the entity names, and that key is a UUID

- **Status:** ACCEPTED (2026-10-08) · accepted-on-merge per the per-repo pattern (ADR-047 / ADR-058)
- **Deciders:** the founder; `exeris-tooling` (processor refusals, emitter order)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen — per-repo; the SDK Javadoc that describes today's behaviour is
  corrected by an `exeris-sdk` issue, not by a stub
- **Visibility:** public
- **Milestone:** 0.10.0 (`docs/0.10.0-release-plan.md`, wave J5)
- **Relates to:** ADR-042 (the processor and the SDK `-io` reader agree on metadata), ADR-095
  (diagnostic identifiers), ADR-096 (sort keys and filters), ADR-090 (server-owned fields), ADR-015
  (output stability), ADR-092 (emitted TypeScript output stability)
- **Supersedes / superseded by:** —

## Context and Problem Statement

`@ExerisDomain(primaryKeyField = "...")` names an entity's primary key. The processor extracts it
into `SystemFieldsMetadata.primaryKeyField`
(`exeris-processor/.../ExerisDomainProcessor.java:2154`), and the SDK `-io` reader reads it the same
way (`exeris-sdk-source-model-io/.../SourceModelReader.java:450`). The generators do not use it to
identify a row. Each one writes the literal `id`:

- the migration declares `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
  (`KernelFlywayGenerator.java:195`);
- the repository selects through the constant `" WHERE id = ?"` (`KernelRepositoryGenerator.java:161`),
  closes every list `ORDER BY` with `id` (`:672`), and assigns `entity.setId(UUID.randomUUID())` on
  save when the key is null (`:1280`);
- the foreign-key migration writes `REFERENCES <target_table>(id)`
  (`KernelApplicationGenerator.java:353`);
- the OpenAPI schema declares a property `id` (`OpenApiComponentsBuilder.java:61`);
- the Angular list, detail, form and store read the literal `'id'`, each with a comment saying why
  (`list-gen.ts:128`, `detail-gen.ts:57`, `form-gen.ts:63`, `store-gen.ts:58`).

The one reader of the attribute is the ADR-096 list query, which keeps the named field out of the
sort keys and filters (`ListQuerySupport.java:191`). Everywhere else, setting `primaryKeyField`
changes nothing, so the processor reports it as inert under `-Aexeris.strict`
(`ExerisDomainProcessor.java:449`) and refuses an entity with no field `id` whatever the attribute
says (`EXT-PROC-1015`, `ExerisDomainProcessor.java:1850`). The field-level `@PrimaryKey` marker is
read by neither the processor (`UNREAD_NOTES`, `ExerisDomainProcessor.java:715`) nor the SDK reader
(`SourceModelReader.java:392`).

The key's type is fixed as well. The kernel identifies an aggregate's event stream by a UUID split
into two `long`s (`EventDescriptor.streamIdHigh` / `streamIdLow`,
`exeris-kernel-spi/.../events/EventDescriptor.java:60` at kernel v0.12.0) and a graph node by a
`UUID` (`GraphSession.upsertNode(String, UUID, LoanedBuffer)`, `.../graph/GraphSession.java:153`).
The generated handler passes the saved entity's key to the event publisher as the stream id
(`KernelHandlerGenerator.java:280`, `KernelEventGenerator.java:292`), the graph sync passes it as
the node id (`KernelGraphSyncGenerator.java:171`), and every foreign-key column is emitted as `UUID`
(`KernelFlywayGenerator.java:229`). An entity that declares `Long id` or `String id` passes the
processor today and produces Java that does not compile, at `setId(UUID.randomUUID())`.

**The question this ADR answers:** which field is an entity's primary key in the generated code,
what type may it have, and what does the processor refuse?

## 🏁 The Decision

**The generated code identifies a row by the effective key: the field
`SystemFieldsMetadata.primaryKeyField` names, else `id`. The effective key is a `java.util.UUID`, and
the processor refuses any other type. The route path variable stays `{id}`.**

**Concrete obligations — the effective key:**

1. **Resolution.** The effective key is `systemFields.primaryKeyField` when `systemFields` is present
   and the value is non-blank, else `id`. Java and TypeScript resolve it with one rule each, in one
   place each, and every generator that writes the key calls that resolver.
2. **Source.** The resolver reads `DomainMetadata` only. The processor and the SDK `-io` reader
   already carry `primaryKeyField`, so honouring it changes no metadata shape and needs no SDK
   release (ADR-042: a released pair must not disagree).
3. **What follows the key.** Each of these uses the effective key where it uses `id` today:
   - the key column of the `CREATE TABLE` migration, in snake case like every other column, with the
     same `UUID PRIMARY KEY DEFAULT gen_random_uuid()` definition;
   - the repository's identity clause, its column list, and the key it assigns on save;
   - the entity accessors the generated Java calls (`get<Key>()`, `set<Key>(...)`), in the
     handler, service, repository, event publisher, graph sync, client and generated tests;
   - the target column of every foreign key that references the entity:
     `REFERENCES <target_table>(<target key column>)`. The referencing column's own name does not
     change;
   - the JSON property, the OpenAPI schema property, and the property of the emitted TypeScript
     model that the list, detail, form, store and service read;
   - the `ORDER BY` tiebreak of the ADR-096 list query.
4. **What stays `id`.** The route path variable (`{base}/{id}`, `{base}/{id}/actions/...`), its
   OpenAPI path parameter, and the Angular route parameter. The variable names a URL segment, not a
   field, and does not appear in any request or response body. Generated method names and parameter
   names (`findById`, `deleteById`, `UUID id`) also stay: they name the role, not the field.
5. **The key is server-owned.** The effective key is treated wherever `id` is treated today: it is
   not a sort key or a filter (ADR-096), not in the create or update request schema, and it gets no
   derived finder, because `findById` already covers it.
6. **Unchanged output.** For an entity that does not rename its key, every emitted file, Java and
   TypeScript, is byte-identical to the output before this decision.

**Concrete obligations — the processor:**

7. **The key is a `java.util.UUID`.** The processor refuses an `@ExerisDomain` type whose effective
   key field, its own or inherited, has any other type, including a key named `id`. The refusal
   carries a new `EXT-PROC` identifier, allocated in the implementation pull request under ADR-095's
   numbering in `docs/diagnostics.md`.
8. **`EXT-PROC-1015` names the effective key.** An entity that declares no field with the effective
   key's name, its own or inherited, is refused as today. The identifier keeps its number (ADR-095),
   and its text names the effective key in place of the literal `id`.
9. **A renamed key and a field `id` do not coexist.** The processor refuses an entity whose
   effective key is not `id` and which also declares a field `id`, its own or inherited, with a new
   `EXT-PROC` identifier. Without the refusal, adding `primaryKeyField` to an entity that already has
   an `id` column would move its key, change its existing `CREATE TABLE` migration, and fail Flyway's
   checksum validation on every database the migration has run against.
10. **The strict audit follows.** `ExerisDomain#primaryKeyField` leaves `INERT_ATTRIBUTES`. The
    `UNREAD_NOTES` reason for `@PrimaryKey` is rewritten: the marker stays unread until obligation
    11 lands, and its reason stops saying that no generator honours a renamed key.

**Concrete obligations — the `@PrimaryKey` marker (conditional, wave J5b):**

11. **Extraction waits on the SDK reader.** The processor reads `@PrimaryKey` into
    `primaryKeyField` only in the release whose pinned SDK `-io` reader reads it the same way
    (ADR-042); the reader half is exeris-sdk#187 item 1. Until then the marker has no effect and
    `-Aexeris.strict` reports it. This obligation does not hold the 0.10.0 cut: without a final SDK
    release carrying the reader half, it is 0.11.0's wave J1.
12. **What the marker may say.** When it is read: `strategy = UUID` and `strategy = UUID_DATABASE`
    are accepted, and both produce the output of obligation 3, a key the column defaults and the
    repository assigns when null. `SEQUENCE`, `IDENTITY` and `NONE` are refused: the first two need a
    non-UUID key, and `NONE` needs a create path that never assigns one. `generated`, `sequenceName`
    and `allowClientId` are registered in `INERT_ATTRIBUTES`. A marker and a `primaryKeyField`
    override that name different fields are refused, as the other nine system-field markers are.

**Concrete obligations — parity:**

13. **One release.** The Java and the TypeScript halves ship in the same tooling release. Neither
    emitter honours a renamed key while the other writes `id`.

## Consequences

### ✅ Positive Outcomes

- **[+] `primaryKeyField` does what its name says.** An entity with `orderId` as its key gets an
  `order_id` primary-key column, a repository, a DTO and an Angular model that agree on it.
- **[+] A non-UUID key fails at the declaration.** The error that surfaced as a compile failure in
  generated Java is a processor diagnostic with an identifier.
- **[+] No SDK release is needed.** The metadata already carries the value on both sides of ADR-042.
- **[+] The default is unchanged.** Every entity that renames nothing regenerates byte-identically.

### ⚠️ Trade-offs

- **[-] No business keys.** An order number, a slug or a database sequence cannot be the primary
  key. The SDK's own `@PrimaryKey` example, `@PrimaryKey(generated = false) String orderNumber`, is
  refused. Such a value is an ordinary unique field.
- **[-] Two names for one thing.** The key is `orderId` in the body and `{id}` in the URL. Clients
  that build URLs from the model use the key's property, not a property named `id`.
- **[-] A rename is a schema change.** Moving an existing entity from `id` to a named key changes
  its `CREATE TABLE` migration. Obligation 9 refuses the silent case; the deliberate case is a
  migration the application owner writes.

### 📋 What is NOT in scope

- **Composite keys.** One field is the key.
- **Changing the key of an existing table.** The generator writes `CREATE TABLE` migrations; it does
  not write `ALTER TABLE ... RENAME COLUMN`.
- **The SDK Javadoc.** `@PrimaryKey` says RESERVED and `ExerisDomain#primaryKeyField` says PARTIAL,
  each because no generator honours a renamed key. Both notes become false when wave J5-3 merges;
  correcting them is an `exeris-sdk` issue.

### 🚫 Non-Goals

- **Non-UUID keys.** Rejected: the kernel's event stream id and graph node id are UUIDs, and a key
  of another type would need a second key, or a mapping, on every event and graph path.
- **Renaming the route variable.** Rejected: `{id}` never appears in a body, so renaming it changes
  every route template, the OpenAPI path parameters and the Angular routes, and gains nothing a
  client can observe.
- **Preferring the renamed key silently over a declared `id`.** Rejected: an entity that already has
  an `id` column would move its key without a diagnostic, and the changed migration would fail
  Flyway's checksum on every existing database. Obligation 9 refuses it instead.

### ⚠️ Risks and Assumptions

- **Assumes:** the kernel keeps identifying event streams and graph nodes by UUID. A kernel SPI that
  accepted another key type would reopen obligation 7.
- **Assumes:** no consumer reads the literal `id` from a generated body where the entity has renamed
  its key. Today no such entity can be generated, so none exists.
- **Risk:** a generator that writes `id` directly instead of calling the resolver. Obligation 6 does
  not catch it, because the default key is `id`. The end-to-end fixtures carry an entity with a
  renamed key on both emitters, so the literal shows up as a compile or test failure.
- **Reversed by:** a kernel event or graph SPI with a non-UUID identity, or a founder decision to
  support business keys. Either supersedes obligations 7 and 12.

## Cross-references

- **ADR-042** — the processor and the SDK `-io` reader must agree; obligation 2 needs no reader
  change, and obligation 11 waits for one.
- **ADR-095** — `EXT-PROC-1015` keeps its number; the two new refusals take new identifiers.
- **ADR-096** — the key is not a sort key or a filter, and is the `ORDER BY` tiebreak.
- **ADR-090** — server-owned fields; the key is one.
- **ADR-015** and **ADR-092** — output stability for the Java and the TypeScript output; obligation 6
  keeps the default output unchanged.
- Kernel v0.12.0: `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/events/EventDescriptor.java`,
  `exeris-kernel-spi/src/main/java/eu/exeris/kernel/spi/graph/GraphSession.java`.
- SDK: `exeris-sdk-annotations/.../annotation/system/PrimaryKey.java`,
  `exeris-sdk-annotations/.../annotation/ExerisDomain.java` (`primaryKeyField`),
  `exeris-sdk-source-model-io/.../io/SourceModelReader.java`.

## Engineering Protocol

1. **J5-1, Java resolver.** One resolver in `exeris-codegen-java` returns the effective key, and
   every Java generator and the OpenAPI builder take the key from it. Output is byte-identical: the
   e2e determinism check and `KernelCodegenE2ETest` pass unchanged.
2. **J5-2, TypeScript resolver.** The same in `exeris-codegen-ts`, with byte-identical output. The
   four "The literal 'id', deliberately" comments in `list-gen.ts`, `detail-gen.ts`, `form-gen.ts`
   and `store-gen.ts` are deleted with the literals they explain.
3. **J5-3, switch-over.** The processor obligations 7 to 10, and the generators honour a renamed
   key, in one pull request. `ExerisDomain#primaryKeyField` leaves `INERT_ATTRIBUTES`.
   The `Invoice` fixture in `exeris-codegen-ts/scripts/gen-sample-app.mjs`, which declares both `id`
   and `primaryKeyField: 'invoiceNo'` to pin the old behaviour, is rewritten as an entity whose only
   key is a renamed `UUID`. `KernelCodegenE2ETest` and `KernelCodegenCompileTest` gain a renamed-key
   entity with a `MANY_TO_ONE` relationship pointing at it. A migration step under
   `docs/migration/0.10.0/` records the two new refusals.
4. **J5b, conditional.** Obligations 11 and 12, in the release whose pinned SDK reads `@PrimaryKey`
   (exeris-sdk#187 item 1).
5. **SDK issue.** An `exeris-sdk` issue asks for the two Javadoc status notes to be corrected once
   J5-3 is released, and names obligation 12's refusals so the reader half of exeris-sdk#187 item 1
   reads the same marker attributes the processor does.
6. Migration owner: `exeris-tooling`, target 0.10.0, release-plan wave J5.
