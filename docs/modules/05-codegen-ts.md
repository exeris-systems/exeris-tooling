---
title: Codegen TS
type: module
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Codegen TS

**Package:** `@exeris/codegen-ts` (npm), directory `exeris-codegen-ts`

`@exeris/codegen-ts` reads the same `exeris-metadata/` directory the Java pipeline reads and writes an Angular 22 application: types and Zod schemas per entity, services, signal-based stores, form, list and detail components, saga and event views, stream clients, `@View` pages, route guards and the bearer interceptor where a route demands identity, and the project scaffold around them. It is a command line, `exeris-gen`.

It is not a module of the Maven reactor. It has its own build (`npm`), its own tests (Vitest) and its own ledger of accepted output changes, and it shares no code with the Java side: the two agree through the metadata and through the contract files in `exeris-e2e-tests` ([e2e tests](10-e2e-tests.md)).

## Coordinates and dependencies

| Item | Value |
|---|---|
| Package | `@exeris/codegen-ts` on npmjs; the binary is `exeris-gen` (`dist/index.js`) |
| Version | The Maven reactor's version, released on the same `vX.Y.Z` tag ([ADR-092](../adr/ADR-092-emitted-typescript-output-stability.md) obligation 1); `npm run check:version` fails when `package.json`, `package-lock.json` and the root `pom.xml` disagree |
| Exports | `./package.json` only. The package has no programmatic API |
| Runtime dependencies | `commander`, `picocolors`, `zod` |
| Node | 18 or newer to run the generator; the emitted application declares `node >=22.0.0` |

## Contract

### Command line

| Command | What it does |
|---|---|
| `exeris-gen generate` (alias `gen`) | Reads `--input` (default `target/classes/exeris-metadata`) and writes under `--output` (default `src/app/generated`) |
| `exeris-gen init` | Writes `exeris-codegen.json`; `--views-only` presets a configuration for `@View` pages in an application you own |

`generate` reads `exeris-codegen.json` when present; only the flags typed on the command line override it. The switches are `--api-base`, `--app-name`, `--no-zod`, `--no-services`, `--no-forms`, `--no-lists`, `--no-details`, `--no-stores`, `--no-sagas`, `--no-events`, `--tests`, `--no-scaffold`, `--render csr|ssg`, `--peer <name>=<path>` (repeatable), `--overwrite`, `--dry-run` and `--verbose`. `--framework`, `--styling` and `--backend` are accepted and printed; `--backend` has one value, `KERNEL`, and no generator branches on the other two. What each generator writes and under which flag is the TypeScript section of the [generator catalogue](../generators.md#typescript-generators).

### Reading the metadata

`models/metadata-files.ts` finds every `*.json` under the input path, sorted by path, and parses three families: entity files through the Zod `DomainMetadataSchema`, `enum_*.json`, and `view_*.json`. `DomainMetadataSchema` is the TypeScript mirror of the SDK's `DomainMetadata`. `models/contract-coverage.ts` puts every top-level field of it in exactly one state, `READ`, `JAVA_ONLY`, `RESERVED` or `GAP`, and `contract-coverage.spec.ts` measures the `READ` set by running the orchestrator over proxied metadata, so a field added to the schema fails the build until it is classified. The TS line's 1.0 criterion is that no field is a `GAP`.

### The emitted application

| Surface | Shape |
|---|---|
| Angular | Major 22 throughout the emitted `package.json` (`^22.0.0`), `provideZonelessChangeDetection`, standalone components |
| Forms | Signal Forms from `@angular/forms/signals` ([ADR-093](../adr/ADR-093-emitted-forms-are-signal-forms.md)) |
| Stores | An injectable class per entity holding its state in `signal` and `computed` |
| Guards | `core/route-guards.ts` with `requireAuthenticated` and `requireAnyPermission(...permissions)`, emitted only when some route demands identity; `app.routes.ts` attaches them per page from the route-requirement row of the backend route the page calls first ([ADR-105](../adr/ADR-105-generated-route-policy-emission.md) obligations 20 to 22). No permission name is invented |
| Styling | Tailwind v4 through `@exeris/ui-kit` |
| Relationships | A `MANY_TO_ONE` key links to the target's detail page and is picked from its records in the form. A `ONE_TO_MANY` is a related-records panel of the detail view, read through the target's service filtered by the back-reference's key. A `List` field that a `ONE_TO_MANY` with `mappedBy` declares is held by the target's rows, so the record type, its Zod schema and both request types leave it out, as they leave out a `@Blob` field (`src/core/relationships.ts`). A `MANY_TO_MANY` has no panel |

### Output tree ownership

`output/writer.ts` decides per file whether the tool may replace what is on disk. A path the previous run's `.exeris-codegen-manifest` records is the tool's and is rewritten when its content differs. A seed file (`overwritable: false`, such as `package.json` or `app.routes.ts`) is written when absent and kept after that. An existing file the manifest does not record is never replaced and never becomes owned. `--overwrite` replaces every differing file. CRLF on disk is compared as LF and kept when the file is rewritten. The manifest is written sorted.

### Output stability

ADR-092 classifies every change to the emitted output as `none`, `additive` or `breaking (ADR-092)` against the surface it defines: emitted paths, exports, routes, service methods, selectors, `data-testid` values, what a view renders for unchanged metadata, the dependency majors in the emitted `package.json`, and the package's flags and configuration keys. The emitted header carries no per-release value; `src/generators/file-header.ts` is the one place it is written, and `header-version-free.spec.ts` fails on a version in any emitted `.ts` or `.html` file. A breaking change takes an entry in `exeris-codegen-ts/api/accepted-api-changes.json` and a migration fragment ([stability matrix](../stability-matrix.md)).

## Hot path

None: build-time. The generator runs once per `exeris-gen generate`. The Angular application it writes runs in a browser, on Angular's code and the emitted code; this package is a dev-time dependency of that application and is not loaded by it.

## Failure modes

The command line prints its errors without an identifier: the `TS` area of [ADR-095](../adr/ADR-095-stable-diagnostic-identifiers.md) is reserved and has none allocated.

| Situation | What the user sees |
|---|---|
| `--input` does not exist | `Input path does not exist`, exit status 1; nothing is generated or deleted |
| `--input` exists and holds no metadata, and no peer is declared | A warning, exit status 0; nothing is generated or deleted |
| A metadata file does not match `DomainMetadataSchema` | `Error:` with the Zod message, exit status 1 |
| A declared peer has no manifest or a `schemaVersion` below the floor | The run fails; a peer contract that cannot be verified is not emitted from |
| A generated path would escape the output directory | `Target path escapes output directory`, and the run fails |
| An existing file is not the tool's | It is left in place and not recorded; rerun with `--overwrite` to replace it |
| `init` finds `exeris-codegen.json` already present | A warning, exit status 1, unless `--force` |

Trade-off: no TypeScript generator shares a line of code with its Java counterpart, so every rule both sides apply (routes, route requirements, server-owned fields, the list query) is written twice and held together by a contract file and a spec on each side. A rule that lands on one side without its contract row drifts.

## Owning ADRs

| ADR | Decision |
|---|---|
| [ADR-092](../adr/ADR-092-emitted-typescript-output-stability.md) | Lockstep version; what a change to the emitted TypeScript owes its consumers |
| [ADR-093](../adr/ADR-093-emitted-forms-are-signal-forms.md) | Emitted forms are Signal Forms |
| [ADR-105](../adr/ADR-105-generated-route-policy-emission.md) | Guards and the interceptor read the declared route requirements |
| [ADR-044](../adr/ADR-044-tooling-sse-stream-emitter-shape.md) | The stream clients' routes and frames |
| [ADR-076](../adr/ADR-076-write-rejection-status.md) | The write-rejection statuses the emitted error display maps |
| [ADR-048](../adr/ADR-048-cross-app-contract-mesh.md) | Peer contracts and the types generated from them |
| [ADR-047](../adr/ADR-047-view-leaf-field-facet-and-ui-subsumption.md) | The `@View` facet the page generator reads |

## Where this does not apply

- **React, Vue or another styling system.** The flags exist and change nothing; the only emitted framework is Angular.
- **The Java backend.** Routes the frontend calls are the ones [codegen-java](04-codegen-java.md) emits, held equal by contract files, not generated from this package.
- **The ui-kit.** `@exeris/ui-kit` is published by `exeris-sdk` on its own version line.
- **A machine-readable catalogue.** ADR-097 reserves `outputRoot: ts`; no TypeScript row is published yet.
