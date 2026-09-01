/**
 * Codegen orchestrator — the pure "metadata → OutputFile[]" step.
 *
 * Extracted from the CLI (`index.ts`) so the composition is unit-testable without
 * filesystem I/O (the TS analog of the Java `CodegenPipeline` seam). The CLI loads
 * metadata + writes files; this module decides *what* gets emitted *where*.
 *
 * T20 invariant enforced here: the per-entity artefacts and the enum module are the
 * canonical app source and are emitted by the REAL generators under the Angular
 * sourceRoot `src/app/` — exactly one tree. `generateAppStructure` contributes the
 * scaffold only; it must not re-emit per-entity files or a stub enum module (that
 * second, stub-tainted tree was the T20 build break).
 *
 * @author Exeris Team
 * @since 0.6.0
 */

import type { DomainMetadata, ViewMetadata } from './models/domain-model.js';
import type { GeneratorConfig } from './config.js';
import { createGeneratorContext } from './core/generator-registry.js';
import { generateTypes, TypeGenerator } from './generators/api/type-gen.js';
import { generateEnumTypes, type EnumMetadataForGen } from './generators/api/enum-module-gen.js';
import { generateService } from './generators/angular/service-gen.js';
import { generateForm } from './generators/angular/form-gen.js';
import { generateList } from './generators/angular/list-gen.js';
import { generateDetail } from './generators/angular/detail-gen.js';
import { EventHandlerGenerator } from './generators/angular/event-gen.js';
import { generateSchemaSpec, generateServiceSpec } from './generators/angular/spec-gen.js';
import { generateStore } from './generators/angular/store-gen.js';
import { generateAppStructure } from './generators/angular/app-structure-gen.js';
import { generateView, generateViewRoute } from './generators/angular/view-gen.js';
import { generatePeerTypes } from './generators/api/peer-type-gen.js';
import type { PeerContract } from './peers/peer-contract.js';

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
 * (types + Zod schemas + services + form/list components), the enum module, and
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

  // The per-entity tree — emitted by the real generators, then re-rooted to src/app.
  const appTree: OutputFile[] = [];

  // Always emit the enum module (even empty) so the type/app barrels' re-export of
  // './enums' resolves whether or not the project declares any @ExerisEnum.
  appTree.push({ path: 'types/enums.ts', content: generateEnumTypes(enums, config.generateZod) });

  for (const domain of domains) {
    if (domain.internalApi?.hidden) {
      continue;
    }
    appTree.push(...generateTypes(domain, config));
    if (config.generateServices) {
      const service = generateService(domain, config);
      if (service) appTree.push(service);
    }
    if (config.generateForms) {
      const form = generateForm(domain, config);
      if (form) appTree.push(form);
    }
    if (config.generateLists) {
      const list = generateList(domain, config);
      if (list) appTree.push(list);
    }
    // Detail views. `generateDetails` has defaulted to true since the flag was added and nothing
    // read it, so `DetailGenerator` emitted nothing — while the emitted LIST already linked to the
    // routes a detail component owns: `[item.id]` labelled "View", and `[item.id, 'edit']` for
    // Edit. Neither worked. `{plural}/:id` loaded the edit form (so "View" opened an editor), and
    // `{plural}/:id/edit` matched no route at all, since the emitted table has no wildcard.
    if (config.generateDetails) {
      appTree.push(generateDetail(domain, config));
    }
    // Signal stores. `generateStores` has defaulted to true since the flag was added, and nothing
    // read it — `StoreGenerator` was exported from the Angular barrel and invoked by no one, so the
    // signal-first surface the config promises was never emitted. That is what led view-gen to bind
    // `<entity>Service.current()`, a method the RxJS service does not have: the author was reaching
    // for a store that the pipeline silently dropped.
    //
    // NOTE: `generateSagas` is the last flag still in that state — declared, defaulted true, read
    // by nothing. `generateDetails` and `generateEvents` have since been wired, each in its own
    // change with its own evidence; the ROADMAP entry carries what each one exposed.
    if (config.generateStores) {
      appTree.push(generateStore(domain, config));
    }

    // Domain-event handlers. `generateEvents` has defaulted to true since the flag was added and
    // nothing read it, so no generated app could observe its own domain events — the emitted
    // publisher's counterpart on the front end simply did not exist. Two call sites, not one:
    // the per-entity handler here, and the shared event bus below, which several entities share.
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
  appTree.push(...new TypeGenerator().generateAggregate(domains, ctx));

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

  for (const file of appTree) {
    generatedFiles.push({ ...file, path: `src/app/${file.path}` });
  }

  // Scaffold only — no per-entity files, no enum module (those live in appTree above).
  // `views` is threaded through so the app shell's app.routes.ts imports + spreads
  // each per-view route export (RFC-2026-06-28 §5 route-assembly).
  generatedFiles.push(...generateAppStructure(domains, enums, config, views));

  return generatedFiles;
}
