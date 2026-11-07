---
title: "0.11.0 migration step: the relationship picker searches a searchable target"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### `exeris-codegen-ts`: the relationship picker searches a searchable target, and the service sends `search`

The list route reads `search` over an entity's searchable `String` fields (ADR-096 Amendment 4),
the fields marked `@Field(searchable = true)` and those without `@Field`.

`services/<entity>.service.ts` of an entity with a searchable field accepts a `search` term in the
first argument of `findAll`:

```ts
this.customerService.findAll({ size: 20, sort: 'name', search: 'kow' });
```

The service trims the term and sends it only when it is not blank. An entity with nothing
searchable keeps `findAll` without `search`, because the route would refuse it. `search` is a
reserved name, so a field named `search` is no longer a key of `<Model>Filter`.

The create and edit form's select for a `MANY_TO_ONE` foreign key whose target has a searchable
field gains a search box in front of it (`data-testid="field-<field>-search"`, at most 100
characters). Typing narrows the options: 250 ms after the last keystroke the form asks the target's
list route for the first 100 rows matching the term. The select keeps the options it holds while
the next term loads, and the value it holds stays an option, under the label it was chosen by,
whether or not a later term matches it. Its truncation hint reads "Showing the first 100 matches; type to
narrow." A select whose target has nothing searchable is unchanged and lists the target's first 100
rows.

The form, service and types are generated files, so regenerating applies the step. An end-to-end
test that drives such a select by its `data-testid` still finds it; a test that counts the
controls of a form, or the arguments of `findAll`'s request type, sees the additions.
