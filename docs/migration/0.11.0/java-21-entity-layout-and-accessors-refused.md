---
title: "0.11.0 migration step: an entity outside a .domain package, or without an accessor the generated code calls, is refused at build time"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

### An entity outside a `.domain` package, or without an accessor the generated code calls, is refused at build time

The generated application places an entity's repository, service, handler and event types in
sibling packages it derives by replacing the trailing `.domain` of the entity's package, and it
calls JavaBean accessors on the entity to bind, read, reset and validate its fields. Both were
assumed and never checked: an entity outside a `.domain` package failed generation with an
unidentified exception after the per-entity files were written, and a missing accessor surfaced as
a compile error in the generated sources. The processor now refuses both on the declaration:

- `EXT-PROC-1033`: an `@ExerisDomain` entity whose package does not end in `.domain`. To fix, move
  the entity into a package ending in `.domain`, such as `com.shop.order.domain`.
- `EXT-PROC-1034`: an `@ExerisDomain` entity without a public instance accessor the generated
  application calls. Every column needs a getter and a setter: the primary key, every field that
  is a column, and the owning tenant (`TENANT` and `UNIVERSE`), audit times, soft-delete flag and
  version the entity enables, whether or not it declares them as fields. A primitive `boolean`
  field and the soft-delete flag read as `is<Name>()`. A `@Blob` field needs a setter only, and a
  static or transient field needs nothing. The error sits on the field, or on the type when the
  entity declares no field for the property, and names the methods to add.

The code-generation pipeline refuses metadata whose package does not end in `.domain` with the same
`EXT-PROC-1033` before it writes anything, so `exeris:generate` and the `exeris-codegen-cli` jar
leave the generated tree as it was.

A build whose metadata feeds no generated kernel application, such as a conformance kit, passes
`-Aexeris.metadataOnly=true` to `javac`, which skips both refusals; the metadata written is the
same either way.

**Where it applies.** To every `@ExerisDomain` entity. **What breaks.** A build whose entity sits
outside a `.domain` package, which could not generate before, now fails at compile time with
`EXT-PROC-1033`. A build whose entity lacks an accessor, whose generated sources did not compile
before, now fails with `EXT-PROC-1034` on the entity instead. Accessors Lombok generates count
only when Lombok runs before the Exeris processor: list `lombok` ahead of `exeris-processor` in
`annotationProcessorPaths`, or write the accessors in the source. See
[`docs/diagnostics.md`](../../diagnostics.md).
