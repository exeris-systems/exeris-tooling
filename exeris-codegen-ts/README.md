# @exeris/codegen-ts

> Exeris Frontend Code Generator for Angular 21+

Generates TypeScript interfaces, Angular services, form components, and list components from Exeris domain metadata.

## Features

- 🎯 **Angular 21+ Support** - Standalone components, Signals, Control Flow, Resource API
- 📝 **TypeScript Types** - Interfaces and Zod schemas from Java domain models
- 🔧 **Services** - HttpClient-based services with full CRUD support
- 📋 **Form Components** - Reactive forms with validation, integrated with Signals
- 📊 **List Components** - Data tables with pagination, sorting, filtering
- 🎨 **Tailwind CSS** - Modern utility-first styling out of the box
- ✅ **Zod Validation** - Runtime validation schemas for type safety
- 🔒 **Security First** - Minimal dependencies (picocolors instead of chalk) to reduce supply chain risk

## Installation

```bash
npm install -g @exeris/codegen-ts
# or
npm install --save-dev @exeris/codegen-ts
```

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
  --tests                Emit specs for the generated surface plus the Vitest runner that
                         executes them (adds a test target, tsconfig.spec.json and the
                         vitest + jsdom devDependencies). Opt-in; off by default.
  --peer <name=path>     Import a peer's DTOs. <name> is the name YOU give the peer — it
                         becomes the directory and import path its types are reached by.
                         <path> is the peer's contract artifact. Repeatable.
  --overwrite            Overwrite existing files
  --dry-run              Show what would be generated without writing files
  -v, --verbose          Verbose output
```

### `exeris-gen init`

Create a configuration file.

```bash
exeris-gen init [options]

Options:
  -f, --force    Overwrite existing config file
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
  ]
}
```

## Peer contracts (mesh)

An app that talks to a peer service can generate that peer's DTOs instead of retyping them
([ADR-048](../docs/adr/ADR-048-cross-app-contract-mesh.md)). A peer's **contract artifact** is
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
| `Integer`, `Long` | `number` | `<input type="number">` |
| `Boolean` | `boolean` | `<input type="checkbox">` |
| `BigDecimal` | `string` | `<input type="text">` |
| `LocalDate` | `string` | `<input type="date">` |
| `LocalDateTime`, `Instant` | `string` | `<input type="datetime-local">` |
| `UUID` | `string` | `<input type="text">` |
| `List<T>` | `T[]` | `<textarea>` |
| `enum` | `string` | `<select>` |

## Integration with Exeris

This generator is part of the Exeris tooling ecosystem:

1. **exeris-processor** - Annotation processor that generates JSON metadata at compile time
2. **exeris-codegen-java** - Java/Spring code generator for backend
3. **exeris-codegen-ts** - TypeScript/Angular code generator for frontend

### Full Stack Generation Script

```powershell
# Windows
.\scripts\generate-all.ps1

# Unix/Linux/macOS
./scripts/generate-all.sh
```

## Development

```bash
# Install dependencies
npm install

# Build
npm run build

# Run in development mode
npm run dev generate --input path/to/metadata

# Run tests
npm test
```

## License

Apache-2.0

