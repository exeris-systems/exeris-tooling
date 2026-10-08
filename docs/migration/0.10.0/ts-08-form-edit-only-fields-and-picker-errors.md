---
title: "0.10.0 migration step: `exeris-codegen-ts`: an `inCreate = false` field is editable in the edit form, and the relationship picker leaves out the edited record and reports a failed load"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `exeris-codegen-ts`: an `inCreate = false` field is editable in the edit form, and the relationship picker leaves out the edited record and reports a failed load

`Compatibility impact: breaking (ADR-092)`, for an entity with a `@Field(inCreate = false)` field
whose `inUpdate` is left `true`, and additive for a form with a relationship picker. TS only: the
Java side already reads `inCreate` and `inUpdate`.

The emitted `<Entity>FormComponent` changes in three ways:

- A field with `inCreate = false` and `inUpdate = true` (the `@Field` default, and what
  `validateOn = UPDATE` resolves to) has a control in edit mode only, inside
  `@if (editMode())`. Its form-model member is seeded from the loaded record, a
  `disabled(path.<name>, { when: () => !this.editMode() })` rule keeps a required one from
  blocking a create, the create payload lists the other members and leaves it out, and an edit
  sends it. Such a field used to have no control in either mode, so an edit always sent back the
  stored value. A field with `inCreate = false` and `inUpdate = false` still has no control.
- A picker for a `MANY_TO_ONE` to the entity itself (`Tag.parentId`) leaves out the option whose
  value is the edited record's id while the form edits.
- Each picker has a `<field>OptionsError` signal, and a failed options request shows its message
  after the select (`role="alert"`, `data-testid="options-error-<field>"`), worded by
  `httpErrorMessage` with the target's plural noun and the `load` action.

**What to do.** Regenerate (L1). A field that is meant to be set only by the server, and that
declares `@Field(inCreate = false)` alone, now gets an edit control and an edit sends the user's
value; declare `@Field(inCreate = false, inUpdate = false)`, or `readOnly = true`, to keep it out of
both forms. An end-to-end test that fills the create form is unaffected, since the new control is
not rendered there; one that fills the edit form sees one more field. **If you ran
`exeris:detach` (L2),** the component is yours and keeps its earlier shape.
