# @exeris/codegen-ts

> Exeris front-end code generator: emits an Angular 22 application

Generates TypeScript interfaces, Angular services, form components, and list components from Exeris domain metadata.

## Features

- 🎯 **Angular 22** - Standalone components, Signals, Control Flow, Resource API
- 📝 **TypeScript Types** - Interfaces and Zod schemas from Java domain models
- 🔧 **Services** - HttpClient-based services with full CRUD support
- 📋 **Form Components** - Angular Signal Forms with validation (ADR-093)
- 📊 **List Components** - Data tables with pagination, sorting, filtering
- 🎨 **Tailwind CSS** - Modern utility-first styling out of the box
- ✅ **Zod Validation** - Runtime validation schemas for type safety
- 🔒 **Security First** - Minimal dependencies (picocolors instead of chalk) to reduce supply chain risk

## Installation

Published to npmjs under the `@exeris` scope, at the same version as the `eu.exeris` Maven
artefacts it releases with. Pin it to the tooling version your build uses.

```bash
npm install --save-dev @exeris/codegen-ts@<tooling-version>
npx exeris-gen --help
```

The package is the `exeris-gen` command line only; it exports no library API.

## Quick Start

### 1. Generate domain metadata (Java)

Run Maven compile to generate metadata from `@ExerisDomain` annotated classes:

```bash
mvn clean compile
```

This creates JSON metadata files in `target/classes/exeris-metadata/`.

### 2. Run code generator

```bash
exeris-gen generate --input target/classes/exeris-metadata --output src/app/generated
```

### 3. Use generated code

```typescript
import { ProductService } from './generated/services/product.service';
import { ProductFormComponent } from './generated/components/product-form.component';
import { ProductListComponent } from './generated/components/product-list.component';
```

## CLI Reference

### `exeris-gen generate`

Generate frontend code from domain metadata.

```bash
exeris-gen generate [options]

Options:
  -i, --input <path>     Input path for metadata JSON files (default: "target/classes/exeris-metadata")
  -o, --output <path>    Output directory for generated code (default: "src/app/generated")
  --api-base <path>      Prefix in front of every generated service URL (default: "" —
                         the emitted client requests exactly what the emitted
                         kernel router serves)
  --framework <name>     Target framework: angular, react, vue (default: "angular")
  --styling <name>       Style system: tailwind, material, bootstrap, none (default: "tailwind")
  --no-zod               Skip Zod schema generation
  --no-services          Skip service generation
  --no-forms             Skip form component generation
  --no-lists             Skip list component generation
  --no-details           Skip detail component generation
  --no-stores            Skip Signal store generation
  --no-sagas             Skip saga state-machine generation
  --no-events            Skip domain-event handler generation
  --no-scaffold          Emit no Angular project or app-shell file; write the generated
                         tree at the output root, for an app you already own (see below)
  --render <mode>        How the scaffolded app renders: csr (default, browser only) or
                         ssg (static prerender of the @View pages; see below)
  --tests                Emit specs for the generated surface plus the Vitest runner that
                         executes them (adds a test target, tsconfig.spec.json and the
                         vitest + jsdom devDependencies). Opt-in; off by default.
  --peer <name=path>     Import a peer's DTOs. <name> is the name YOU give the peer — it
                         becomes the directory and import path its types are reached by.
                         <path> is the peer's contract artifact. Repeatable.
  --overwrite            Also replace the files the generator would keep: an existing file
                         no previous run generated, and a file written once for you to edit
                         (see "Regenerating" below)
  --dry-run              Show what would be generated without writing files
  -v, --verbose          Verbose output
```

### `exeris-gen init`

Create a configuration file.

```bash
exeris-gen init [options]

Options:
  -f, --force          Overwrite existing config file
  --views-only         Preset for @View pages generated into an app you own: every entity
                       generator off and "scaffold": false
  --app-name <name>    Application name to write into the config (either preset;
                       default: "Exeris Foundation")
```

Without `--views-only` the file holds every configuration key at its default. With it, the entity
generators (services, forms, lists, details, stores, sagas, events, Zod schemas and tests) are off,
so for metadata that declares no entity or enum a run emits the `@View` pages, their routes and
`view.routes.ts`, and nothing else. An entity or enum still gets its types, the enum module and the
barrel `index.ts`:

```bash
exeris-gen init --views-only --app-name "Exeris Web"
exeris-gen generate
```

## Configuration File

Create `exeris-codegen.json` in your project root. Every field below is also a CLI flag;
a flag **only** overrides the file when you actually type it, so the file is the place to
put settings you want to keep.


```json
{
  "inputPath": "target/classes/exeris-metadata",
  "outputPath": "src/app/generated",
  "framework": "angular",
  "styling": "tailwind",
  "standalone": true,
  "signals": true,
  "lazyRoutes": true,
  "generateZod": true,
  "generateServices": true,
  "generateForms": true,
  "generateLists": true,
  "apiBasePath": "",
  "peers": [
    { "name": "billing", "path": "../billing-service/target/contract" }
  ],
  "customBlocks": {
    "StarRating": { "import": "../blocks/star-rating.component", "symbol": "StarRatingComponent" }
  },
  "viewHeading": "title"
}
```

### `customBlocks`: the components behind `@View` CUSTOM blocks

A CUSTOM block renders the element its `customType` names, kebab-cased (`StarRating` →
`<star-rating>`). The emitted page imports the component behind it from the `customBlocks` entry
keyed by that `customType`, exactly as the IR writes it, and lists the class in its `imports`.
`import` is written verbatim into the page, which is emitted at `src/app/pages/<view>.component.ts`,
so a relative specifier resolves from there. The component's selector must be the kebab-cased
`customType`.

A block's `@Block(props)` is JSON. It becomes a field of the page (`blockProps1`, `blockProps2`, …
in template order) bound as `[props]="blockProps<N>"`, so the component declares an input named
`props`. A block without props gets no binding.

Generation fails, naming the view, the block and `customBlocks`, when a view uses a CUSTOM block
that declares no `customType`, whose `customType` has no entry, or whose props are not valid JSON,
and when `customBlocks` maps one class name (`symbol`) from two different modules. This option is file-only; it
has no CLI flag.

### `viewHeading`: the heading of a `@View` page

`"title"`, the default, heads every emitted `@View` page with an `<h1>` holding the view's `title`,
or its name when it declares none. `"none"` emits no `<h1>`, for pages whose own blocks carry the
headline, such as a HERO with one; the rest of the page is unchanged. Either way the page's route
keeps the view's `title`, which sets the document title, and the navigation label stays the same.
The option applies to every `@View` page of the run. It is file-only; it has no CLI flag.

## Regenerating

The generator records every file it owns in `.exeris-codegen-manifest` at the output root. On the
next run, the previous manifest decides what it may replace and delete.

**Seed files** are written once for you to edit: `package.json`, `angular.json`, `tsconfig.json`,
`tsconfig.app.json`, `tsconfig.spec.json`, `.postcssrc.json`, `proxy.conf.json`, `src/main.ts`,
`src/index.html`, `src/styles.css`, `src/environments/environment.ts`,
`src/environments/environment.development.ts`, `src/app/app.config.ts`, `src/app/app.component.ts`,
`src/app/app.routes.ts`, under `render: 'ssg'` also `src/main.server.ts`,
`src/app/app.config.server.ts` and `src/app/app.routes.server.ts`, and the auth service template
`core/auth.service.ts`.

| On disk | Without `--overwrite` | With `--overwrite` |
|---|---|---|
| absent | written, then owned | written, then owned |
| owned, content differs | rewritten | rewritten |
| owned seed file | kept | rewritten |
| present, not in the manifest (hand-written, or a first run into a populated directory) | kept, and not owned | rewritten, then owned |
| a symbolic link, or reached through one below the output root | kept, and not owned | a link at the path is replaced, never written through; nothing is written through a linked directory |
| owned, no longer generated | deleted | deleted |
| owned seed file or link, no longer generated | kept, and no longer owned | kept, and no longer owned |

A regenerated page whose metadata changed is therefore rewritten without any flag, and a removed
`@View` or entity takes its files with it. A file you write beside the generated ones is never
touched, because it is not in the manifest. To take a generated file over, move it out of the output
directory and stop generating it (remove the view or turn its generator off); a file the generator
still produces is created again at its old path.

**A manifest written by 0.9.x or earlier** carries no `# ownership: written` line. Those releases
recorded every file they produced, including files they skipped because they already existed, so
on the first run with such a manifest an entry is owned only when the file already holds what this
run generates, starts with the header the generator writes (its provenance line or its do-not-edit
notice, within the first 10 lines), or is a seed file. A generated file whose content changed is
therefore rewritten as usual, and deleted when no longer generated; a hand-written file at a
generated path, which has no such header, is kept and dropped from the manifest, generated or not.
A file copied from generated output keeps the header and is treated as generated. The run writes
the manifest in the current format.

**A missing or empty metadata directory** generates and deletes nothing: a wrong `--input`, or a
`mvn clean` without a compile after it, must not empty the output tree. A missing directory fails
the run. This differs from the Java `OutputWriter`, for which a run with no entities is a valid
state that prunes the tree. To remove everything after deleting the last entity and view, delete
the generated files yourself.

`--dry-run` lists what each file would get (create, rewrite, unchanged, keep, skip) and every file
the run would prune or release, and changes nothing.

Commit the output directory, manifest included: the manifest is what tells the next run, on any
machine, which files are the generator's.

## Generating into an existing Angular app (`scaffold: false`)

By default the output is a complete Angular application: `package.json`, `angular.json`, the
`tsconfig` files, `src/main.ts`, `src/index.html`, the styles and environments, and the app shell
(`app.config.ts`, `app.component.ts`, `app.routes.ts`), with the generated tree under `src/app/`.

With `"scaffold": false` (or `--no-scaffold`) the output directory is a folder inside an app you
already own, such as `src/app/generated`. No project or app-shell file is emitted, and the
generated tree is written at the output root:

```
src/app/generated/
├── pages/
│   ├── about.component.ts
│   └── about.route.ts
├── view.routes.ts            # every @View route, as one array
└── index.ts                  # the barrel, when the metadata declares an entity or an enum
```

Spread the view routes into your own routes file; which page `''` redirects to is yours to decide:

```typescript
import { Routes } from '@angular/router';
import { viewRoutes } from './generated/view.routes';

export const routes: Routes = [...viewRoutes];
```

What the scaffold would otherwise provide is then your app's to provide: `provideHttpClient()` when
an emitted service or store calls the API, the `@exeris/ui-kit` styles and Tailwind, and the npm
dependencies the emitted files import (`zod`, `@angular/cdk`, `@angular/forms`, as the enabled
generators require). A page bound to an entity (`binding.source = ENTITY`) injects that entity's
store, which needs the store, service and type generators on.

Switching an existing output directory from the scaffold to no scaffold deletes the generated files
under `src/app/` that the previous run wrote, because the tree moves to the output root. The seed
files (`package.json`, `angular.json`, `app.routes.ts`, …) are kept and are no longer the
generator's; delete them yourself if the directory is no longer an app.

## Static prerender (`render: 'ssg'`)

With `"render": "ssg"` (or `--render ssg`) the scaffold is a static site: `ng build` writes an HTML
file for each page it can render at build time, and a static file host serves them. The default,
`"csr"`, is a browser-only app.

`ssg` adds three files, each a seed:

- `src/main.server.ts`, the server entry the build bootstraps for each prerendered route;
- `src/app/app.config.server.ts`, the browser configuration plus
  `provideServerRendering(withRoutes(serverRoutes))`;
- `src/app/app.routes.server.ts`, the render mode of every route.

and changes four: `angular.json` (`"server": "src/main.server.ts"`, `"outputMode": "static"`),
`package.json` (`@angular/ssr`, `@angular/platform-server`), `tsconfig.app.json` (the server entry)
and `app.config.ts` (`provideClientHydration(withEventReplay())`, so the browser takes over the
prerendered DOM and replays clicks made before hydration finished).

The server routes decide what is prerendered:

| Route | Render mode | Why |
|---|---|---|
| a `@View` page whose path has no `:param` or wildcard and that binds no entity | `Prerender` | its content is authored, so the HTML the build writes is the page |
| `''`, when the app redirects it | `Prerender` | written as the root `index.html`, a page that sends the browser on to the redirect target |
| a page with a `:param` in its path | `Client` (through `**`) | the metadata does not list the parameter's values; add `getPrerenderParams` to its entry to prerender it |
| a page bound to an entity (`binding.source = ENTITY`), and every entity list, detail and form route | `Client` (through `**`) | it loads its data from the kernel API on init, which no build reaches |
| anything else, including routes you add | `Client` (`**`) | |

`ng build` writes `dist/<app>/browser/<route>/index.html` for each prerendered route and
`index.csr.html`, the browser shell, for the rest; configure the host to serve `index.csr.html` for a
path with no file of its own. No server bundle is produced.

The server routes are a seed, written from the metadata of the first `ssg` run: a page added later
is rendered in the browser until you add its entry. Switching an existing app from `csr` to `ssg`
writes the three new files, but keeps the four changed seeds as they are; merge their `ssg` form
(regenerate into an empty directory to see it) or rerun with `--overwrite`. Until `@angular/ssr`
is installed the build fails on the new files, and until `angular.json` names the server entry it
prerenders nothing. A run under `ssg` that keeps an existing `angular.json`, as a seed or as a file
no previous run generated, prints a warning naming the four files; `--dry-run` prints it too.

With `scaffold: false` there is no `angular.json` or app configuration to change, so `render` has no
effect: the server setup belongs to the app that owns them.

## Peer contracts (mesh)

An app that talks to a peer service can generate that peer's DTOs instead of retyping them
([ADR-048](https://github.com/exeris-systems/exeris-tooling/blob/main/docs/adr/ADR-048-cross-app-contract-mesh.md)).
A peer's **contract artifact** is
a directory holding its `cap-manifest.json` and the metadata of the entities it provides:

```
billing-contract/
├── cap-manifest.json          # required — schemaVersion >= 2
└── exeris-metadata/
    ├── Order.json
    └── enum_OrderStatus.json
```

```bash
exeris-gen generate --peer billing=../billing-contract --peer shipping=../shipping-contract
```

A run with peers and no local entity, enum or view emits only the contracts, at the output root
(`<output>/peers/<name>/`), with no Angular app scaffold — for a consumer that is not an Angular app.

Three things to know:

- **You name the peer.** Nothing in an Exeris artifact carries an application identity, and the
  name lands in *your* import paths, where it has to survive the producer renaming itself.
- **Each peer gets its own namespace**, its own enum module and its own barrel, and is never
  re-exported from your app's `types/` barrel. Two peers may both call an entity `Order`; that
  compiles only because neither is merged into anyone else's namespace.
- **The manifest is required.** A directory of metadata alone is not a contract — the build
  fails, naming the peer, rather than importing something it cannot check.

Peers in one build are the same shape supplied from a local path — the degenerate case, not a
second mode. What is emitted is DTOs only: the peer **client** and the capability registry are
the next slice.

## Generated Structure

```
src/app/generated/
├── types/                    # TypeScript interfaces
│   ├── product.types.ts
│   └── customer.types.ts
├── schemas/                  # Zod validation schemas
│   ├── product.schema.ts
│   └── customer.schema.ts
├── services/                 # Angular services
│   ├── product.service.ts
│   └── customer.service.ts
├── components/               # Angular components
│   ├── product-form.component.ts
│   ├── product-list.component.ts
│   ├── customer-form.component.ts
│   └── customer-list.component.ts
├── events/                   # domain-event handlers + the shared bus
│   ├── event-bus.service.ts
│   └── order.events.ts
├── sagas/                    # one state machine per entity declaring @Saga
│   └── order.saga.ts
├── peers/                    # one self-contained tree per --peer, never merged above
    └── billing/
        ├── types/
        │   ├── enums.ts
        │   └── order.types.ts
        ├── schemas/
        │   └── order.schema.ts
        └── index.ts          # the peer's own barrel
```

Under `--tests`, each entity also gets `schemas/<entity>.schema.spec.ts` and
`services/<entity>.service.spec.ts`, run by `npm test`.

## Generated tests (`--tests`)

Off by default, because turning it on adds to *your* `package.json`. It emits, in one piece:

- `*.schema.spec.ts` — a fixture built from your metadata, asserting the schema accepts it and
  rejects each declared required field's absence;
- `*.service.spec.ts` — the real service driven through Angular's own
  `provideHttpClientTesting()`, asserting the URL and verb of each call. **No mocking library** is
  needed: the double ships with `@angular/common`, which the app already depends on;
- a `test` target on `@angular/build:unit-test` (Vitest), a `tsconfig.spec.json`, and the two
  devDependencies the runner cannot start without — `vitest` (an *optional* peer of
  `@angular/build`) and `jsdom` (the builder refuses to run without a DOM implementation).

Specs are excluded from `tsconfig.app.json`, so a production `ng build` never requires the test
dependencies.

## Saga state machines

An entity declaring `@Saga` gets `sagas/<entity>.saga.ts`: a `providedIn: 'root'` signal machine
holding the declared steps in order, their status, progress, an estimated time remaining, and
screen-reader announcements — everything a progress UI needs, derived from the metadata you
already wrote.

**It tracks a run; it does not perform one.** No transport is emitted, because there is nothing
to emit it against: the generated backend registers no saga route, the generated OpenAPI
document describes none, and the kernel flow SPI exposes no per-execution handle to build one
from. Saga *orchestration* is generated on the Java side (`<Entity>SagaOrchestrator`, driven by
the flow engine); how a browser observes it is your application's decision.

So you drive it:

```typescript
const { executionId } = await this.myBackend.startFulfilment(orderId);
this.saga.begin(orderId, executionId);

// then on every update you receive — poll, SSE frame, websocket message, in-process call:
this.saga.applyStatus(snapshot);   // SagaStatusSnapshot
```

`begin` / `failToStart` / `cancelling` / `retrying` / `reset` are the remaining transitions. The
machine, and the `SagaStatusSnapshot` shape it folds, are exported from the app barrel.

## Type Mapping

| Java Type | TypeScript Type | Form Control |
|-----------|-----------------|--------------|
| `String` | `string` | `<input type="text">` |
| `int`, `long`, `double`, `Integer`, `Long`, `Double`, `Float` | `number` (boxed: `number \| null`) | `<input type="number">` |
| `boolean`, `Boolean` | `boolean` (boxed: `boolean \| null`) | `<input type="checkbox">` |
| `BigDecimal`, `BigInteger` | `string` (exact digits) | `<input type="text">` with `inputmode` `decimal` / `numeric` |
| `LocalDate` | `string` | `<input type="date">` |
| `LocalDateTime` | `string` | `<input type="datetime-local">` |
| `Instant`, `ZonedDateTime` | `string` | `<input type="text">` holding the ISO-8601 value, zone included |
| `UUID` | `string` | `<input type="text">` |
| `UUID` holding a `MANY_TO_ONE` foreign key, the target generated with its service | `string` | `<select>` of the target's records (`findAll()`), valued by `id` and labelled by `@Relationship.displayField` (the id when that is empty) |
| an `enum` the processor emitted | the enum's string union | `<select>` |

A field with `@Field(inUpdate = false)` is disabled while the form edits, and is sent back as loaded.

## License

Apache-2.0

