---
title: "0.10.0 migration step: An `@ExerisDomain` type without an `id` field is a compile error (`EXT-PROC-1015`)"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### An `@ExerisDomain` type without an `id` field is a compile error (`EXT-PROC-1015`)

From 0.10.0 the annotation processor refuses an `@ExerisDomain` type that declares no field named
`id`, at the annotation:

```
[Exeris] EXT-PROC-1015: @ExerisDomain type 'Workspace' declares no field 'id'. The generated schema,
repository, routes and Angular model all identify a row by id, and primaryKeyField does not rename
it. Declare 'private UUID id;' with its getter and setter.
```

Every generated artefact identifies a row by the literal `id`: the migration's `id UUID PRIMARY KEY`,
the repository's `WHERE id = ?`, the `{id}` path variable of the by-id routes, the `getId()` and
`setId(...)` calls in the generated handlers and services, and the list, detail, form and store the
TypeScript generator emits. Through 0.9.0 an entity without the field passed the processor, and the
failure surfaced downstream: in the generated repository and handler, which call accessors the
entity does not have, and at `ng build` of the generated Angular app, with
`TS2339: Property 'id' does not exist on type '<Entity>'`.

**What to do:** declare the key on the entity, with its accessors:

```java
private UUID id;

public UUID getId() { return id; }
public void setId(UUID id) { this.id = id; }
```

A field inherited from a superclass satisfies the check. `@ExerisDomain(primaryKeyField = …)` does
not: no generator uses it as the key (the list route only keeps that field out of sort and filter),
so an entity whose key is named otherwise is refused as well, and the field has to be called `id`.
A build that compiled on 0.9.0 and declares `id` is unaffected; its emitted output is
byte-identical.

**An `id` inherited from a superclass** passes the check, but the processor records only the
fields the entity type declares itself, so the generated Angular model of such an entity still has
no `id` and `ng build` still reports TS2339 until the TypeScript generator always emits it (release
plan wave S2). Declaring `id` on the entity itself avoids both.

The check runs in the annotation processor. Metadata JSON that reaches `exeris:generate` without
passing through the processor is not checked.
