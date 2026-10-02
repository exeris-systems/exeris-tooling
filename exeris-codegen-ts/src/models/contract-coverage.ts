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

import type { DomainMetadata, FieldMetadata, UIMetadata } from './domain-model.js';

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

/**
 * Contract coverage one level down: the keys of a field object and of `uiMetadata`.
 *
 * The states mean what they mean for `CONTRACT_COVERAGE`, with one difference: for `GAP` it is
 * enough that the processor writes the key and the front owes an emission for it — a presentation
 * key can be owed by the front alone. Each entry also says whether `ExerisDomainProcessor` ever
 * sets the key from the source (`written`). The schemas mirror the SDK records, which declare
 * more than the processor extracts, so a key can be declared, even read, and still never arrive
 * from a real build: a generator branch on it runs only for hand-built metadata. A key that is
 * never written can be acted on by no one, so it is only ever `READ` or `RESERVED`.
 *
 * `READ` is measured by the same orchestrator run as `CONTRACT_COVERAGE`, with every field object
 * and every `uiMetadata` object proxied; `written` is read off the processor's extraction and is
 * not measured.
 */
export type NestedContractCoverageEntry = ContractCoverageEntry & { readonly written: boolean };

const NOT_WRITTEN = 'Declared by the SDK record; the processor never sets it.';

export const FIELD_CONTRACT_COVERAGE = {
  name: { state: 'READ', written: true },
  type: { state: 'READ', written: true },
  columnName: { state: 'RESERVED', written: false, reason: `${NOT_WRITTEN} Columns are the snake-cased field name.` },
  displayName: { state: 'READ', written: true },
  description: { state: 'READ', written: true },
  required: { state: 'READ', written: true },
  unique: { state: 'JAVA_ONLY', written: true, reason: 'Indexes the column in the Flyway migration.' },
  indexed: {
    state: 'RESERVED',
    written: true,
    reason: 'No emitter reads it: the migration indexes searchable, unique and filterable fields, not indexed ones.',
  },
  searchable: { state: 'READ', written: true },
  sortable: { state: 'READ', written: true },
  filterable: { state: 'READ', written: true },
  audited: {
    state: 'RESERVED',
    written: false,
    reason: `${NOT_WRITTEN} Auditing is the entity-level audited flag; nothing is audited per field.`,
  },
  readOnly: { state: 'READ', written: true },
  hidden: { state: 'READ', written: false },
  defaultValue: { state: 'READ', written: false },
  minLength: { state: 'READ', written: true },
  maxLength: { state: 'READ', written: true },
  min: { state: 'READ', written: true },
  max: { state: 'READ', written: true },
  pattern: { state: 'READ', written: true },
  format: { state: 'READ', written: false },
  dataType: { state: 'READ', written: true },
  enumType: { state: 'READ', written: false },
  inCreate: { state: 'READ', written: true },
  inUpdate: {
    state: 'GAP',
    written: true,
    reason: 'The edit form is built from inCreate alone, so @Field(inUpdate = false) still offers the field for editing.',
  },
  computed: { state: 'READ', written: true },
  computedFrom: { state: 'READ', written: true },
} as const satisfies { readonly [K in keyof FieldMetadata]-?: NestedContractCoverageEntry };

export const UI_CONTRACT_COVERAGE = {
  icon: { state: 'RESERVED', written: false, reason: `${NOT_WRITTEN} @UI declares it and the processor drops it.` },
  color: { state: 'RESERVED', written: false, reason: `${NOT_WRITTEN} @UI declares it and the processor drops it.` },
  listView: { state: 'READ', written: true },
  detailView: { state: 'READ', written: true },
  createForm: { state: 'READ', written: true },
  editForm: { state: 'READ', written: true },
  searchable: { state: 'READ', written: true },
  filterable: { state: 'READ', written: true },
  exportable: {
    state: 'RESERVED',
    written: true,
    reason: 'Written from @UI; no export action is emitted on either side, and the switch defaults to off.',
  },
} as const satisfies { readonly [K in keyof UIMetadata]-?: NestedContractCoverageEntry };
