---
title: "0.10.0 migration step: `exeris-codegen-ts`: a `@View` CUSTOM block imports its component through `customBlocks`, and a LIST renders `<li>`"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### `exeris-codegen-ts`: a `@View` CUSTOM block imports its component through `customBlocks`, and a LIST renders `<li>`

`Compatibility impact: breaking (ADR-092)`, for an app with a `@View` that uses a CUSTOM or a LIST
block. TS only: the Java side emits no `@View` page.

A CUSTOM block emits the element its `customType` names, and the page component it sits in did not
import the component behind that element, so `ng build` failed with NG8001. The new config key
`customBlocks` maps each `customType`, exactly as the IR writes it, to the module the page imports
from and the exported class it imports:

```json
{
  "customBlocks": {
    "StarRating": { "import": "../blocks/star-rating.component", "symbol": "StarRatingComponent" }
  }
}
```

The page imports each mapped class once and lists it in its component `imports`, ordered by class
name. `import` is written verbatim into `src/app/pages/<view>.component.ts`, so a relative specifier
resolves from there. The component's selector is the kebab-cased `customType`, as before.

A block's `@Block(props)` is parsed as JSON and becomes a field of the page, `blockProps1`,
`blockProps2`, … in template order, bound as `[props]="blockProps<N>"`. The component declares an
input named `props`. A block without props gets no binding.

Generation now fails when a view has a CUSTOM block whose `customType` has no entry, that declares
no `customType`, or whose props are not valid JSON; the message names the view and the block's place
in it. It also fails when `customBlocks` maps one class name (`symbol`) from two different modules;
that message names the view, the class and both modules.

A LIST block's `<ul>` now holds `<li>` items: each child is wrapped in one, and a LIST bound to an
entity collection emits one `<li>` per row inside its `@for`. Authored text on a LIST is an item too.

**What to do.** Add a `customBlocks` entry for every `customType` your views use, give each
component an input named `props` if its block declares props, and regenerate (L1). A stylesheet or
test that selected a LIST child as a direct child of the `<ul>` now selects it inside the `<li>`.
**If you ran `exeris:detach` (L2),** the pages are yours and keep their markup.
