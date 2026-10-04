/**
 * Codegen orchestrator — the pure "metadata → OutputFile[]" step.
 *
 * The composition is separate from filesystem I/O so it is unit-testable (the TS analog
 * of the Java `CodegenPipeline` seam). The CLI loads metadata + writes files; this module
 * decides *what* gets emitted *where*.
 *
 * T20 invariant enforced here: the per-entity artefacts and the enum module are the
 * canonical app source and are emitted by the REAL generators under the Angular
 * sourceRoot `src/app/` — exactly one tree. `generateAppStructure` contributes the
 * scaffold only; it must not re-emit per-entity files or a stub enum module.
 */

import type { DomainMetadata, ViewMetadata } from './models/domain-model.js';
import type { GeneratorConfig } from './config.js';
import { createGeneratorContext } from './core/generator-registry.js';
import { generateTypes, TypeGenerator } from './generators/api/type-gen.js';
import { generateEnumTypes, type EnumMetadataForGen } from './generators/api/enum-module-gen.js';
import { generateService } from './generators/angular/service-gen.js';
import { StreamClientGenerator } from './generators/angular/stream-client-gen.js';
import { ActionStreamClientGenerator } from './generators/angular/action-stream-client-gen.js';
import { generateForm } from './generators/angular/form-gen.js';
import { generateList } from './generators/angular/list-gen.js';
import { generateDetail } from './generators/angular/detail-gen.js';
import { EventHandlerGenerator } from './generators/angular/event-gen.js';
import { generateSchemaSpec, generateServiceSpec } from './generators/angular/spec-gen.js';
import { generateSaga } from './generators/angular/saga-gen.js';
import { generateStore } from './generators/angular/store-gen.js';
import { generateAppStructure } from './generators/angular/app-structure-gen.js';
import { generateView, generateViewRoute } from './generators/angular/view-gen.js';
import { generateHttpErrorHelper, needsHttpErrorHelper } from './generators/angular/http-error-gen.js';
import { generatePeerTypes } from './generators/api/peer-type-gen.js';
import type { PeerContract } from './peers/peer-contract.js';
import { deriveScaffoldNeeds } from './core/scaffold-needs.js';

/** Minimal output-file shape the writer consumes (path + content). The per-shape
 *  generators return richer objects (artifactType/overwritable); those are structurally
 *  assignable here, and nothing downstream of composition needs the extra fields. */
export interface OutputFile {
  path: string;
  content: string;
}

// The enum module is emitted by `generators/api/enum-module-gen.ts` — the peer-types
// slice (T42) needed the same emitter, and a generator importing the orchestrator that
// composes it is a cycle. Re-exported here so existing importers keep their path.
export { generateEnumTypes, type EnumMetadataForGen } from './generators/api/enum-module-gen.js';


/**
 * Compose the full set of files to write from parsed metadata. Per-entity output
 * (types + Zod schemas + services and SSE stream clients + form/list components), the enum module, and
 * the per-view page components / routes are re-rooted under `src/app/` (the
 * Angular sourceRoot); the scaffold is appended as-is.
 *
 * `views` is the presentation-IR family (RFC-2026-06-28): each parsed
 * `view_*.json` ViewMetadata emits one standalone, signal-first page component
 * (`pages/<kebab>.component.ts`) + its paired lazy route (`pages/<kebab>.route.ts`).
 * It is optional (defaults to none) so existing 3-arg callers stay valid.
 *
 * `peers` is the mesh's peer-contract set (T42, ADR-048): each loaded peer contributes its
 * own DTO tree under `peers/<name>/`, with its own enum module and its own barrel. It is
 * deliberately NOT threaded into `generateAppStructure` — the app barrel and the app's own
 * `types/index.ts` must not re-export a peer's types, or two peers' `Order` would meet in
 * one namespace, which is the T40 break at mesh scale.
 */
export function buildGeneratedFiles(
  domains: DomainMetadata[],
  enums: EnumMetadataForGen[],
  config: GeneratorConfig,
  views: ViewMetadata[] = [],
  peers: PeerContract[] = []
): OutputFile[] {
  const generatedFiles: OutputFile[] = [];

  // Hoisted above the per-entity loop: the event generator needs a context for BOTH its
  // per-entity handler and its app-wide bus, and building one per entity would be wasteful
  // and would give the two halves different views of the domain set.
  const ctx = createGeneratorContext(config, domains);
  const eventGenerator = new EventHandlerGenerator();
  const streamClientGenerator = new StreamClientGenerator();
  const actionStreamClientGenerator = new ActionStreamClientGenerator();

  // The per-entity tree — emitted by the real generators, then re-rooted to src/app.
  const appTree: OutputFile[] = [];

  // The type surface — the enum module, and the type/schema barrels that re-export it — exists
  // when the app declares an entity or an enum. With either, the enum module is emitted even
  // empty, so the barrels' re-export of './enums' resolves; with neither, nothing would import it.
  const hasTypeSurface = enums.length > 0 || domains.length > 0;
  if (hasTypeSurface) {
    appTree.push({ path: 'types/enums.ts', content: generateEnumTypes(enums, config.generateZod) });
  }

  for (const domain of domains) {
    appTree.push(...generateTypes(domain, config));
    if (config.generateServices) {
      const service = generateService(domain, config);
      if (service) appTree.push(service);
      // SSE clients, one per stream route the kernel application serves: the live view at
      // GET {base}/stream for a realTimeApi entity, and POST {base}/{id}/actions/{kebab} for each
      // streaming action. Each generator returns null for an entity with no such route, and for
      // a tenant-partitioned entity, whose stream routes carry no tenant guard.
      const streamClient = streamClientGenerator.generate(domain, ctx);
      if (streamClient) appTree.push(streamClient);
      const actionStreamClient = actionStreamClientGenerator.generate(domain, ctx);
      if (actionStreamClient) appTree.push(actionStreamClient);
    }
    if (config.generateForms) {
      const form = generateForm(domain, config, enums);
      if (form) appTree.push(form);
    }
    if (config.generateLists) {
      const list = generateList(domain, config, domains, enums);
      if (list) appTree.push(list);
    }
    // Detail view component: read/edit for a single entity instance.
    if (config.generateDetails) {
      const detail = generateDetail(domain, config, domains, enums);
      if (detail) appTree.push(detail);
    }
    // Signal store: reactive entity state (signal-first).
    if (config.generateStores) {
      appTree.push(generateStore(domain, config));
    }

    // Saga UI state machine: multi-step transactional workflow. Emitted only when an entity
    // declares `@Saga`.
    if (config.generateSagas) {
      const saga = generateSaga(domain, config);
      if (saga) appTree.push(saga);
    }

    // Domain-event handler: listen to and react to domain events published by this entity.
    // (The shared event bus is emitted separately below for entities that declare events.)
    if (config.generateEvents) {
      const handler = eventGenerator.generate(domain, ctx);
      if (handler) appTree.push(handler);
    }

    // Generated specs (T2, ADR-058). Opt-in: `generateTests` defaults to false, because turning it
    // on also puts a runner and two devDependencies into the consumer's package.json. Each spec is
    // gated on the surface it exercises actually being emitted — a schema spec for an app built
    // with --no-zod would import a file that does not exist.
    if (config.generateTests) {
      if (config.generateZod) appTree.push(generateSchemaSpec(domain, config, enums));
      if (config.generateServices) appTree.push(generateServiceSpec(domain, config));
    }
  }

  // Real per-entity Zod schemas + type/schema barrels (gated by config.generateZod).
  // These were a stub before (T20); the schemas reference the real enum module above.
  if (hasTypeSurface) {
    appTree.push(...new TypeGenerator().generateAggregate(domains, ctx));
  }

  // The status-to-message helper is app-wide: one file the per-entity components, stores and
  // sagas import. An app with no entity has nothing that imports it and gets none.
  if (needsHttpErrorHelper(domains, config)) {
    appTree.push(generateHttpErrorHelper());
  }

  // The stream-client barrels, and the StreamFrame module the action stream clients share.
  // Both generators return nothing when no entity has a stream route.
  if (config.generateServices) {
    appTree.push(...streamClientGenerator.generateAggregate(domains, ctx));
    appTree.push(...actionStreamClientGenerator.generateAggregate(domains, ctx));
  }

  // The event bus is app-wide: one service every entity's handler imports, emitted only when
  // some entity actually declares an event.
  if (config.generateEvents) {
    appTree.push(...eventGenerator.generateAggregate(domains, ctx));
  }

  // Presentation IR (@View): one page component + paired route per view, in
  // declaration order (deterministic — the views arrive in directory-scan order
  // from index.ts; the per-view output itself is order-stable).
  for (const view of views) {
    appTree.push(generateView(view, config));
    appTree.push(generateViewRoute(view, config));
  }

  // Peer DTOs (T42). Peers arrive sorted by their consumer-declared name; each tree is
  // self-contained — its own enum module, its own barrel, no edge to the app's own types.
  for (const peer of peers) {
    appTree.push(...generatePeerTypes(peer, config));
  }

  // A run with peers and no local entity, enum or view emits contracts only (ADR-048, T42 types
  // slice): its consumer need not be an Angular app, so the tree is written at the output root
  // (`peers/<name>/…`) and no app scaffold is emitted. A local enum is part of the app's own type
  // surface (`types/`), which lives under `src/app/` beside the scaffold, so it keeps the app layout.
  const contractsOnly = peers.length > 0 && domains.length === 0 && enums.length === 0 && views.length === 0;
  const treeRoot = contractsOnly ? '' : 'src/app/';

  for (const file of appTree) {
    generatedFiles.push({ ...file, path: `${treeRoot}${file.path}` });
  }

  // Scaffold only — no per-entity files, no enum module (those live in appTree above).
  // `views` is threaded through so the app shell's app.routes.ts imports + spreads
  // each per-view route export (RFC-2026-06-28 §5 route-assembly). What the scaffold wires
  // (HTTP client, dev proxy, API environment, optional dependencies) is read off the composed
  // tree, so it never carries a backend piece no emitted file uses.
  if (!contractsOnly) {
    generatedFiles.push(...generateAppStructure(domains, enums, config, views, deriveScaffoldNeeds(domains, appTree)));
  }

  return generatedFiles;
}
