/**
 * Contract coverage of `DomainMetadata` — where each top-level field is acted on.
 *
 * Every field of `DomainMetadataSchema` is in exactly one state:
 *
 * - `READ` — a TS generator reads it while the orchestrator composes the emitted app;
 * - `JAVA_ONLY` — only the Java emitters act on it; the reason says what they emit from it;
 * - `RESERVED` — no emitter on either side acts on it yet; the reason says why it is carried;
 * - `GAP` — the Java emitters act on it and the front owes a counterpart it does not emit yet; the
 *   reason names what is missing. The TS line's 1.0 criterion is that no field is in this state.
 *
 * `test/contract/contract-coverage.spec.ts` measures the `READ` set by running the orchestrator
 * on proxied metadata and requires it to equal the entries marked `READ` here, and requires the
 * keys here to equal the schema's keys, in schema order. A field added to the schema therefore
 * fails the build until it is classified, and a field a generator starts or stops reading fails
 * it until its state is moved.
 *
 * `JAVA_ONLY` is for a field the front has nothing to do with; a field the front should act on and
 * does not is a `GAP`, never `JAVA_ONLY`.
 */

import type { DomainMetadata } from './domain-model.js';

export type ContractState = 'READ' | 'JAVA_ONLY' | 'RESERVED' | 'GAP';

export type ContractCoverageEntry =
  | { readonly state: 'READ' }
  | { readonly state: 'JAVA_ONLY' | 'RESERVED' | 'GAP'; readonly reason: string };

export const CONTRACT_COVERAGE = {
  entityName: { state: 'READ' },
  packageName: { state: 'JAVA_ONLY', reason: 'Java package of the emitted classes; TS paths derive from entityName.' },
  tableName: { state: 'JAVA_ONLY', reason: 'SQL table name for the DDL and repository statements.' },
  displayName: { state: 'READ' },
  pluralName: { state: 'READ' },
  description: { state: 'JAVA_ONLY', reason: 'OpenAPI info, tag and schema descriptions.' },
  path: { state: 'READ' },
  apiVersion: { state: 'RESERVED', reason: 'No emitted route or client carries a version segment on either side.' },
  apiPath: { state: 'READ' },
  module: { state: 'RESERVED', reason: 'Carried from @ExerisDomain; no emitter reads it.' },
  restApi: { state: 'RESERVED', reason: 'Neither emitter gates on it: REST routes and clients are emitted for every visible entity.' },
  graphqlApi: { state: 'RESERVED', reason: 'No GraphQL surface is emitted on either side.' },
  realTimeApi: { state: 'READ' },
  internalClient: { state: 'RESERVED', reason: 'Carried from @ExerisDomain; no emitter reads it.' },
  dataScope: { state: 'READ' },
  tenantScoped: { state: 'READ' },
  versioned: { state: 'READ' },
  fullTextSearch: { state: 'RESERVED', reason: 'No search index or search endpoint is emitted on either side.' },
  searchConfig: { state: 'RESERVED', reason: 'Configuration for fullTextSearch, which nothing emits yet.' },
  audited: { state: 'READ' },
  softDelete: { state: 'READ' },
  multiTenant: { state: 'RESERVED', reason: 'Superseded by dataScope; the Java DSL output derives its own multiTenant from the scope tier.' },
  cacheable: { state: 'RESERVED', reason: 'No cache layer is emitted on either side.' },
  cacheSeconds: { state: 'RESERVED', reason: 'Cache configuration, inert while cacheable is.' },
  cacheTtl: { state: 'RESERVED', reason: 'Cache configuration, inert while cacheable is.' },
  cacheRegion: { state: 'RESERVED', reason: 'Cache configuration, inert while cacheable is.' },
  fields: { state: 'READ' },
  actions: { state: 'READ' },
  events: { state: 'READ' },
  relationships: { state: 'READ' },
  projections: { state: 'RESERVED', reason: 'No projection read model or endpoint is emitted on either side.' },
  uiMetadata: { state: 'READ' },
  graphMetadata: { state: 'JAVA_ONLY', reason: 'Server-side graph synchronisation; the front has no graph surface.' },
  sagaMetadata: { state: 'READ' },
  systemFields: { state: 'READ' },
  eventSourced: { state: 'RESERVED', reason: 'Event-sourced aggregates need a kernel SPI that no emitter targets yet.' },
  internalApi: { state: 'READ' },
} as const satisfies { readonly [K in keyof DomainMetadata]-?: ContractCoverageEntry };

export type ContractField = keyof typeof CONTRACT_COVERAGE;
