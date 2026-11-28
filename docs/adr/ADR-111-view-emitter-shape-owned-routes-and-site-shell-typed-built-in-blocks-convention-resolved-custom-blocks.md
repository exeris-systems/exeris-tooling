---
title: "ADR-111 — The @View emitter shape: owned routes and site shell, typed built-in blocks, convention-resolved custom blocks"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-111
---

# ADR-111 — The `@View` emitter shape: owned routes and site shell, typed built-in blocks, convention-resolved custom blocks

- **Status:** ACCEPTED (2026-11-28) · accepted-on-merge per the per-repo pattern (ADR-047 / ADR-058)
- **Deciders:** Arkadiusz Przychocki
- **Repo:** `exeris-tooling` (`exeris-codegen-ts`)
- **Scope:** tooling / codegen — per-repo
- **Visibility:** public
- **Milestone:** 0.12.0 (`docs/0.12.0-release-plan.md`, waves W2 and W3, rows V2 to V5, V7 and B10)
- **Driven by:** [RFC-2026-06-28](../rfc/RFC-2026-06-28-presentation-view-emitter-tooling.md)
  (accepted with this ADR); `docs/0.12.0-release-plan.md` waves W2 and W3; issues #441, #417,
  #543–#547
- **Relates to:** [ADR-092](ADR-092-emitted-typescript-output-stability.md) (emitted TS surface);
  [ADR-047](ADR-047-view-leaf-field-facet-and-ui-subsumption.md) (the leaf facet and `FORM`,
  unchanged); [ADR-044](ADR-044-tooling-sse-stream-emitter-shape.md) (the sibling emitter-shape
  decision); hard constraint #3 (deterministic codegen); hard constraint #6 (committed generated
  code); `.agents/policies/scoped-bans.md`, which requires every codegen-ts emitter input to come
  through `DomainMetadata` and `MetadataLoader`: custom-block resolution reads the block directory
  in the CLI before composition, and `buildGeneratedFiles` receives the resolved map and reads no
  file
- **Supersedes / superseded by:** —
- **Decided in:** `docs/rfc/RFC-2026-06-28-presentation-view-emitter-tooling.md`

## Context and Problem Statement

The SDK's presentation IR (`ViewMetadata`, `RegionMetadata`, `ComponentNodeMetadata`) reaches
`@exeris/codegen-ts` as `view_*.json`. The first slice of the emitter, shipped under
RFC-2026-06-28, writes one standalone signal-first page component and one lazy route file per
view, and a CUSTOM block's component from a hand-written `customBlocks` map.

A static site built on it needs code the generator does not write. With the scaffold on,
`app.routes.ts`, `app.component.ts` and `app.routes.server.ts` are seeds: written once and kept.
Those files list the view routes, the navigation links and the prerendered paths, so a view added
after the first generation has no route, no link and no page. No generator reads `@View.layout`,
so the shell is the entity sidebar. A SECTION view gets a route and is prerendered as a page.
The shell's `<main>` wraps the page's own `<main>`. There is no `**` route and no `404.html`.
The built-in blocks print their `props` as text, so a site authors nearly every node as a CUSTOM
block, and each of those needs an entry in a map the generator cannot check against the app.

The IR already carries what a shell, a not-found page and typed blocks need: `@View.kind`,
`@View.layout`, `@View.route`, the region `slot` and the `@Block(props)` JSON object. This
decision is therefore confined to the TypeScript emitter and needs no SDK release.

**The question this ADR answers:** what does the `@View` emitter write, which of those files does
it own on every run, how is a site shell composed from views, what do the built-in blocks accept,
and how does a CUSTOM block find its component?

## 🏁 The Decision

**The `@View` emitter routes only PAGE views, composes each `@View.layout` into a shell from
that layout's SECTION views, writes every metadata-derived route, shell and navigation file on
every run behind write-once seeds that only compose them, renders HERO, CARD, IMAGE, RICH_TEXT and
NAV from typed props checked against a schema published with the package, and resolves a CUSTOM
block by the selector of a component in a configured directory, with `customBlocks` as an
override.**

The slice-1 shape, as shipped, is the base contract:

- the processor writes one `view_<Name>.json` per `@View`, parallel to `DomainMetadata`;
- regions and blocks are emitted in declaration order;
- ENTITY bindings read the generated signal store;
- ACTION bindings emit a handler stub;
- STATIC/NONE render authored content;
- PROJECTION, an expression on a data source, and SLOT fills are TODO-marked passthroughs.

The obligations below extend that contract.

**Concrete obligations — routes and ownership:**

1. **Only PAGE views are routed.** A PAGE view emits `pages/{kebab(V)}.component.ts` and
   `pages/{kebab(V)}.route.ts`. A SECTION, COMPONENT or FRAGMENT view emits the component only,
   with no route file, and is never prerendered.
2. **One owner of `<main>`.** A page component's root element is
   `<div class="exeris-page" data-view="…">`. The document's only `<main>` is always in a
   generator-owned component that holds the router outlet the page renders in:
   - the layout component, for a page that declares a layout, in both modes;
   - `AppShellComponent`, for a page without a layout, with the scaffold on;
   - `ViewMainComponent` (`view-main.component.ts`, template `<main><router-outlet /></main>`),
     for a page without a layout, with the scaffold off. It is the empty-path parent of those
     PAGE views in `viewRoutes`.

   A consuming app's own shell does not wrap its outlet in `<main>`.
3. **Owned on every run** (rewritten whenever the content differs, deleted when the run stops
   producing them):
   - with the scaffold on:
     - `src/app/app.routes.generated.ts`, exporting `generatedRoutes` (the layout parents of
       obligation 6, then `{ path: '', component: AppShellComponent, children: [entity routes,
       PAGE views without a layout] }`) and `notFoundRoutes`;
     - `src/app/app.routes.server.generated.ts`, exporting `generatedServerRoutes` under
       `render: 'ssg'`;
     - `src/app/shell/app-shell.component.ts`, the header, the navigation and `<main>`;
     - `src/app/layouts/{kebab(L)}.layout.ts` per layout key;
     - the default not-found page, `src/app/shell/not-found.component.ts`, when no view claims
       route `404`;
     - `exeris-postbuild.mjs` under `render: 'ssg'`;
   - with the scaffold off:
     - `view.routes.ts`, exporting `viewRoutes` (the layout parents, then
       `{ path: '', component: ViewMainComponent, children: [PAGE views without a layout] }`)
       and `notFoundRoutes`;
     - `view-main.component.ts`, exporting `ViewMainComponent`;
     - `view.routes.server.ts` under `render: 'ssg'`;
     - `layouts/{kebab(L)}.layout.ts`.
4. **Written once** (seeds): the files `SCAFFOLD_SEED_PATHS` lists keep their class. They are
   generated to compose the owned files, and nothing derived from metadata is written into them
   except the landing redirect:
   - `app.routes.ts` is `[redirect?, ...generatedRoutes, ...notFoundRoutes]`, with the
     consumer's own routes placed before `notFoundRoutes`;
   - `app.routes.server.ts` is
     `[{ path: '', Prerender }?, ...generatedServerRoutes, { path: '**', Client }]`;
   - `app.component.ts` is `<router-outlet />`.
5. **A kept seed is reported.** The CLI prints a warning naming the MIGRATION entry, and the run
   does not fail, when a run keeps:
   - an `app.routes.ts` that does not import `app.routes.generated`;
   - under `render: 'ssg'`, a `package.json` whose `build` script does not run
     `exeris-postbuild.mjs` (`package.json` is a seed).

**Concrete obligations — the site shell:**

6. **Layouts.** Every distinct non-null `@View.layout` value among PAGE and SECTION views is a
   layout. Its component is emitted at `layouts/{kebab(L)}.layout.ts`. In the routes it is an
   empty-path parent whose children are the PAGE views declaring that layout, ordered as the
   app's routes are ordered (route path, then name).
7. **SECTION views fill shell slots.** A SECTION view with `layout = L` has exactly one region,
   whose slot is one of `header`, `nav`, `aside` or `footer`. It is rendered in that slot of L's
   shell: `header` in `<header>`, `aside` in `<aside>`, `footer` in `<footer>`, and `nav` in a
   wrapper whose `<nav>` comes from the section's NAV block. The layout component's
   `<main><router-outlet /></main>` sits between them. A second SECTION for the same (layout,
   slot), a slot outside the four, or more than one region fails generation with an error naming
   the views. A SECTION with no layout is emitted as a component and placed nowhere.
8. **Navigation.** The scaffold's navigation lists the entity list pages and the PAGE views
   without a layout, except the not-found view. It lives in the owned `AppShellComponent`. A
   layout's navigation is its SECTION views' NAV blocks.

**Concrete obligations — the not-found page:**

9. **Not-found view.** The PAGE view whose `route` is `404` is the not-found view. Two such views
   fail generation. `notFoundRoutes` routes `**` to its component, inside its layout when it
   declares one. With the scaffold on and no such view, the default not-found page is routed at
   `404` and as `**` inside `AppShellComponent`. With the scaffold off, the not-found view is
   routed inside its layout or inside `ViewMainComponent`, and with no such view `notFoundRoutes`
   is `[]`.
10. **`404.html`.** Under `render: 'ssg'`, `404` is prerendered like any param-less page that reads
    no entity data. With the scaffold on, the owned `exeris-postbuild.mjs` reads the output path
    from `angular.json` and copies `<browser>/404/index.html` to `<browser>/404.html`. The
    scaffold's `build` script is `ng build && node exeris-postbuild.mjs`. With the scaffold off
    the generator writes no `404.html`; the consuming app's build produces it.

**Concrete obligations — built-in blocks:**

11. **Typed props.** HERO, CARD, IMAGE, RICH_TEXT and NAV take `props` as a JSON object valid
    against their definition in `block-props.schema.json`. Unknown keys are refused. Props that
    are not a JSON object, or are invalid, fail generation with an error naming the view, the
    block path and the key. The definitions:
    - `Link` = `{ label: string, href: string, variant?: "primary" | "secondary" | "link" }`;
    - `Image` = `{ src: string, alt: string, width?: integer, height?: integer }`, where width
      and height come both or neither;
    - **HERO** `{ title: string, eyebrow?: string, lede?: string, image?: Image, links?: Link[] }`;
    - **CARD** `{ title?: string, eyebrow?: string, body?: string, href?: string, image?: Image }`;
    - **IMAGE** `Image & { caption?: string, loading?: "lazy" | "eager" }`;
    - **RICH_TEXT** `{ markdown: string }`;
    - **NAV** `{ label?: string, links: Link[] }`.
12. **Rendering.** Values are written into the template at generation time, so the prerendered
    HTML carries them:
    - IMAGE emits `<figure><img …></figure>`, with `<figcaption>` when there is a caption;
    - RICH_TEXT emits HTML from a CommonMark subset (paragraphs, headings h2–h4, emphasis,
      strong, inline code, links, lists), and raw HTML in the source is text. The generator
      renders it and no dependency is added to the emitted app;
    - NAV emits `<nav>` with a `<ul>` of links;
    - a link or image URL that starts with `/` is a `routerLink`; `http:`, `https:`, `mailto:`,
      `tel:` and `#` are `href`; any other scheme fails generation;
    - text is escaped for HTML and for Angular template syntax (`{`, `}`, `@`);
    - children render after the typed content.
13. **Published schema.** `block-props.schema.json` (JSON Schema 2020-12, no version and no date
    in its content) ships in the package and is exported as
    `@exeris/codegen-ts/schemas/block-props.schema.json`. It is part of the package's interface
    under ADR-092: removing or narrowing a key is breaking, and adding an optional key is
    additive.

**Concrete obligations — CUSTOM blocks:**

14. **Resolution.** For a CUSTOM block with customType `T`, the run takes:
    - the `customBlocks[T]` entry when there is one;
    - otherwise, the one `*.ts` file (not `*.spec.ts`) directly in `customBlocksDir` that
      contains a `selector` string literal equal to `kebab(T)`, the element the page emits. The
      `customBlocksDir` config key defaults to `src/app/blocks` under the app root: `outputPath`
      with the scaffold on, where the pages are emitted under `src/app/pages/`, and the working
      directory with the scaffold off. The symbol is the first `export class` that follows that
      literal. The import specifier is the posix relative path from the emitted `pages/`
      directory to the file, without `.ts`.

    No match, or more than one, fails generation with an error naming T, the expected selector,
    the directory and any files that matched. Resolution happens in the CLI before composition.
    `buildGeneratedFiles` receives the resolved map and reads no file.
15. **Determinism.** The same metadata, config and block directory give byte-identical output.
    The directory is read in code-unit order of file names.

## Consequences

### ✅ Positive Outcomes

- **[+] A view added later is routed, linked and prerendered** without `--overwrite`, and the
  consumer's edits to the seeds survive.
- **[+] A site's shell, not-found page and content blocks come from the IR**, so a site project
  keeps content, block components and brand styles, not routing or shell code.
- **[+] The block props schema is written once** and is shared by the generator, the IR's authors
  and any editor that writes the IR.
- **[+] A CUSTOM block needs no map entry**; the selector the page already emits is the key.
- **[+] One `<main>` per document**, for entity pages and view pages alike, always in a
  generator-owned component.

### ⚠️ Trade-offs

- **[-] Apps generated earlier keep the old seeds.** Their `app.routes.ts` still imports per-view
  route files, and one that imports a SECTION view's route stops compiling once the file is
  deleted. The MIGRATION entry and the warning of obligation 5 cover this; the fix is to replace
  the three route and shell seeds and, under `render: 'ssg'`, to add the postbuild step to the
  `build` script of `package.json`.
- **[-] Breaking emitted output (ADR-092):** non-PAGE views lose their routes; the page root is
  `<div>` where it was `<main>`; text props on the five typed blocks fail generation.
- **[-] The generator reads consumer source** (the block directory), with a bounded textual match
  rather than a TypeScript parse.
- **[-] One SECTION per shell slot.** Two parts of a header are one SECTION with two blocks.
- **[-] `FRAGMENT` has no placement.** The SDK's `ViewKind` documents FRAGMENT as a non-route
  fragment such as a navigation tree and SECTION as a composable section of a page. This
  decision fills the shell slots, `nav` included, from SECTION views.

### 📋 What is NOT in scope

- A layout document in the IR and build-time data sources (SDK asks after this decision).
- SEO fields (V9, exeris-sdk#282).
- `sitemap.xml` and `robots.txt` (V6), which are written at generation time under their own row.
- Typed props for GRID, CONTAINER, LIST, SLOT and FORM, whose `props` keep their 0.11 rendering.
- `FORM` and the leaf field facet (ADR-047).
- `titleKey`.

### 🚫 Non-Goals

- **A component reference in the IR.** The IR stays framework-neutral; module paths are the
  app's.
- **Runtime block resolution** (a registry looked up by `NgComponentOutlet`). A missing component
  would surface when the page renders (prerender or browser), not at generation.
- **Overwriting seeds to deliver new routes.** Ownership is moved, not taken.

### ⚠️ Risks and Assumptions

- **Assumes:** Angular's static output writes `<route>/index.html` per prerendered route and has
  no option that names a file `404.html`.
- **Assumes:** a CUSTOM block's component declares its selector as a string literal in its own
  file, the form Angular's CLI writes.
- **Risk:** a run whose working directory has no block directory (a preview in a scratch
  directory) fails on every CUSTOM block without an override. The error names the directory.
- **Reversed by:** an SDK layout document in the IR (it supersedes obligations 6 and 7); an
  Angular build option that writes `404.html` (it supersedes the postbuild copy); a block
  catalogue in the SDK (it moves obligation 13's schema there).

## Cross-references

- RFC-2026-06-28 — the emitter's option comparison, accepted with this ADR.
- ADR-092 — classification of each change and the MIGRATION obligation.
- ADR-047 — the leaf facet and `FORM`, unchanged.
- ADR-044 — the sibling emitter-shape decision.
- `docs/generators.md` — the TS generator table, updated with the owned files.
- `docs/guides/05-generate-the-frontend.md` — the route-assembly paragraph.

## Engineering Protocol

1. Specs in `exeris-codegen-ts/test/` cover:
   - obligations 1–4 and 6–9 on emitted strings;
   - obligation 5 on the CLI plan;
   - obligation 11 on the schema and on generation failures;
   - obligation 14 on a temporary block directory;
   - generate-twice byte equality for 15.
2. The prerendered sample app (`scripts/gen-sample-app.mjs`, CI `ng build (generated sample
   app)`) is generated with the scaffold on. It carries a layout with header, nav and footer
   sections, a `404` view, each typed block with every text prop an object, and a CUSTOM block
   resolved by selector. The sample script calls `buildGeneratedFiles` directly, so it resolves
   the CUSTOM block through the resolver the CLI uses, exported for it. CI asserts that each
   prerendered HTML file contains one `<main` and that `404.html` exists.
3. A two-pass fixture generates through the writer, adds a view, regenerates and builds.
4. Every breaking change takes a fragment under `docs/migration/0.12.0/` and a `"since": "0.12"`
   entry in `exeris-codegen-ts/api/accepted-api-changes.json`.
5. Migration owner: `exeris-tooling`, 0.12.0, rows V2–V5 and V7 and B10.
