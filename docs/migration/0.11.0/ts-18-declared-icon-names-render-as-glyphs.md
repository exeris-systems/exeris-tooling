---
title: "0.11.0 migration step: exeris-codegen-ts: a declared Material icon name renders as a glyph"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### `exeris-codegen-ts`: a declared Material icon name renders as a glyph

The SDK documents `@NavMenu(icon, groupIcon)` and `@Tab(icon)` as Material icon names (`badge`,
`shopping_cart`). The generated sidebar, form tabs and detail tabs render a declared value that is
such a name, lowercase letters, digits and underscores only, through the Material Symbols font:

```html
<span class="material-symbols-outlined mr-3 text-xl" aria-hidden="true">badge</span>
```

The name is a ligature, so it renders as a glyph only with the font loaded, and `aria-hidden`
keeps a screen reader from reading it beside the label. Any other declared text, an emoji for
example, renders as itself, as before.

When an emitted template names that class, a newly generated app's `package.json` lists
`"material-symbols": "^0.48.0"` and its `src/styles.css` imports the font after the ui-kit entries:

```css
@import "@exeris/ui-kit/styles";
@import "material-symbols/outlined.css" layer(components);
```

The import goes into the `components` layer so that a utility on the same element, such as
`text-xl`, sizes the icon. The font file, `material-symbols-outlined.woff2` (4.0 MB), is fetched
when an icon first renders and is not part of the initial bundle.

**What to change.** `package.json`, `src/styles.css` and `src/app/app.component.ts` are seeds, so an
existing app keeps them on regeneration. Its sidebar keeps rendering the declared name as a word.
Its form and detail components are regenerated and name the class, and their tab icons render as a
word until the app loads the font. To render glyphs in both, add the dependency and the import above,
run `npm install`, and in `app.component.ts` give each declared icon's `<span>` the
`material-symbols-outlined` class and `aria-hidden="true"`, or delete the file and regenerate it.

**Where it applies.** Only to apps where some entity declares a Material icon name on `@NavMenu` or
`@Tab`. An app that declares none, or declares only emoji, regenerates byte-identically.
