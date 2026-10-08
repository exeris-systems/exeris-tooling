---
title: "0.10.0 migration step: An `@ExerisDomain` type without its primary key field is a compile error (`EXT-PROC-1015`)"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### An `@ExerisDomain` type without its primary key field is a compile error (`EXT-PROC-1015`)

From 0.10.0 the annotation processor refuses an `@ExerisDomain` type that declares no field named
by its primary key, at the annotation. The key is `id` unless `@ExerisDomain(primaryKeyField = …)`
names another field:

```
[Exeris] EXT-PROC-1015: @ExerisDomain type 'Workspace' declares no field 'id', its primary key. The
generated schema, repository and Angular model identify a row by the primary key, which is 'id'
unless primaryKeyField names another field. Declare 'private UUID id;' with its getter and setter.
```

Every generated artefact identifies a row by that field: the migration's `id UUID PRIMARY KEY`, the
repository's `WHERE id = ?`, the `getId()` and `setId(...)` calls in the generated handlers and
services, and the list, detail, form and store the TypeScript generator emits. Through 0.9.0 an entity without the field passed the processor, and the
failure surfaced downstream: in the generated repository and handler, which call accessors the
entity does not have, and at `ng build` of the generated Angular app, with
`TS2339: Property 'id' does not exist on type '<Entity>'`.

**What to do:** declare the key on the entity, with its accessors:

```java
private UUID id;

public UUID getId() { return id; }
public void setId(UUID id) { this.id = id; }
```

A field inherited from a superclass satisfies the check. An entity that names its key with
`primaryKeyField` declares that field instead, as a `UUID` (the step on `primaryKeyField` below). A
build that compiled on 0.9.0 and declares `id` is unaffected; its emitted output is
byte-identical.

**An `id` inherited from a superclass** passes the check, but the processor records only the
fields the entity type declares itself, so the generated Angular model of such an entity still has
no `id` and `ng build` still reports TS2339 until the TypeScript generator always emits it (release
plan wave S2). Declaring `id` on the entity itself avoids both.

The check runs in the annotation processor. Metadata JSON that reaches `exeris:generate` without
passing through the processor is not checked.
