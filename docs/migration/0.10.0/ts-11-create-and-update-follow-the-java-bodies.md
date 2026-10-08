---
title: "0.10.0 migration step: `exeris-codegen-ts`: the create and update types follow the Java bodies"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `exeris-codegen-ts`: the create and update types follow the Java bodies

`Compatibility impact: breaking (ADR-090)`, for code that builds a `<Entity>Update` that names an
`inUpdate = false` property, or a `<Entity>Create` that names a property the create type used to
carry or to drop.

The generated server's `PUT` treats a field marked `@Field(inUpdate = false)` as it treats a
read-only one: the stored value stays and the response carries it (ADR-090 Amendment 3).
`<Entity>Update` and `<Entity>UpdateSchema` leave the field out, and the emitted edit form leaves it
out of the request: its control, disabled while the form edits, is taken out of the values the body
is built from. An action whose entity method changes the field still stores the change.

The create body is the fields the server does not set (ADR-090 Amendment 4). `<Entity>Create` and
`<Entity>CreateSchema` list the properties the Java `<Entity>CreateDto` lists: every declared field
except

- the key, the owner of a TENANT or UNIVERSE entity and a UNIVERSE entity's shared scope;
- the audit stamps of an audited entity, the version of a versioned one and the soft-delete fields
  of a soft-delete one, under the names the entity's `systemFields` block gives them;
- every name a `systemFields` block declares, whatever the entity's data scope;
- the read-only fields and the fields marked `inCreate = false`.

`<Entity>CreateSchema` omitted only the system-role names, so it listed a read-only or
`inCreate = false` field the `<Entity>Create` type already left out. A field named `active`,
`onboardingStatus`, `onboardingStartedAt`, `onboardingCompletedAt`, `hierarchyLevel` or
`parentTenantId` is in `<Entity>Create` like any other declared field that plays no role; the type
no longer drops it by name.

A versioned entity that declares no version field gets an optional `version` (or the name
`systemFields.versionField` gives it) on `<Entity>` and `<Entity>Schema`, and an audited one that
declares no stamps gets optional `createdAt` and `updatedAt` (under the names the block gives them),
the properties the Java entity schema lists. `<Entity>Update` still requires the version and leaves
the stamps out; `<Entity>Create` carries none of them.

**What to do:** remove an `inUpdate = false` property from an update body you build, and read it from
the response. Where you build a `<Entity>Create` with a field that is now a property of no create
body, remove it, and set a field that plays a system role through the server. A `<Entity>Create`
that named a field only because it is called `active` or `hierarchyLevel` still compiles. TypeScript
refuses an update literal that names an omitted property, but an object arriving through a spread
still compiles and the server ignores the property.
