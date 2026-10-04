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

import type {
  ActionMetadata,
  DomainEventMetadata,
  DomainMetadata,
  EventSourcedMetadata,
  FieldMetadata,
  InternalApiMetadata,
  RelationshipMetadata,
  SagaStepMetadata,
  UIMetadata,
} from './domain-model.js';

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
  restApi: { state: 'RESERVED', reason: 'Neither emitter gates on it: REST routes and clients are emitted for every entity.' },
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
  projections: {
    state: 'RESERVED',
    reason: 'The processor writes it empty, since @Projection is not extracted; no projection read model or endpoint is emitted on either side.',
  },
  uiMetadata: { state: 'READ' },
  graphMetadata: { state: 'JAVA_ONLY', reason: 'Server-side graph synchronisation; the front has no graph surface.' },
  sagaMetadata: { state: 'READ' },
  systemFields: { state: 'READ' },
  eventSourced: { state: 'RESERVED', reason: 'Event-sourced aggregates need a kernel SPI that no emitter targets yet.' },
  internalApi: {
    state: 'RESERVED',
    reason: 'Written as internal = true for an @InternalApi entity; no emitter on either side gates on it.',
  },
} as const satisfies { readonly [K in keyof DomainMetadata]-?: ContractCoverageEntry };

export type ContractField = keyof typeof CONTRACT_COVERAGE;

/**
 * Contract coverage one level down: the keys of the objects `DomainMetadata` nests — a field, an
 * action, an event, a relationship, a saga step, `uiMetadata`, `eventSourced` and `internalApi`.
 *
 * The states mean what they mean for `CONTRACT_COVERAGE`, with one difference: for `GAP` it is
 * enough that the processor writes the key and the front owes an emission for it — a presentation
 * key can be owed by the front alone. Each entry also says whether `ExerisDomainProcessor` ever
 * sets the key from the source (`written`). The schemas mirror the SDK records, which declare
 * more than the processor extracts, so a key can be declared, even read, and still never arrive
 * from a real build: a generator branch on it runs only for hand-built metadata. A key that is
 * never written can be acted on by no one, so it is only ever `READ` or `RESERVED`.
 *
 * `READ` is measured by the same orchestrator run as `CONTRACT_COVERAGE`, with every nested object
 * proxied; `written` is read off the processor's extraction and is not measured.
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
  inUpdate: { state: 'READ', written: true },
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

export const ACTION_CONTRACT_COVERAGE = {
  name: { state: 'READ', written: true },
  methodName: {
    state: 'JAVA_ONLY',
    written: true,
    reason: 'The emitted handler invokes this method on the aggregate; the client addresses the action by name.',
  },
  displayName: { state: 'RESERVED', written: false, reason: `${NOT_WRITTEN} @Action declares a label it does not copy here.` },
  description: { state: 'READ', written: true },
  httpMethod: {
    state: 'RESERVED',
    written: true,
    reason: 'Written from @Action; both emitters serve and call every action with POST.',
  },
  params: { state: 'READ', written: true },
  resultType: {
    state: 'RESERVED',
    written: false,
    reason: `${NOT_WRITTEN} The action route answers with the updated aggregate on both sides, whatever the method returns.`,
  },
  async: { state: 'RESERVED', written: true, reason: 'Written from @Action; no emitted route or client behaves differently for it.' },
  permissions: {
    state: 'RESERVED',
    written: false,
    reason: `${NOT_WRITTEN} @Action.permissions is only checked against @RouteAccess at compile time.`,
  },
  producesEvents: { state: 'RESERVED', written: false, reason: NOT_WRITTEN },
  routeAccess: {
    state: 'RESERVED',
    written: false,
    reason: `${NOT_WRITTEN} @RouteAccess is validated, not transcribed: no route policy is emitted on either side.`,
  },
  streaming: { state: 'READ', written: true },
  streamEventType: { state: 'READ', written: true },
} as const satisfies { readonly [K in keyof ActionMetadata]-?: NestedContractCoverageEntry };

const EVENT_PLACEMENT =
  'Places the publish call in the emitted handler; the front receives events off the stream and never decides where one is produced.';

export const EVENT_CONTRACT_COVERAGE = {
  name: { state: 'READ', written: true },
  topic: {
    state: 'JAVA_ONLY',
    written: true,
    reason: 'The emitted event type and publisher route on it; the front receives events off the entity stream by name.',
  },
  description: { state: 'RESERVED', written: true, reason: 'Written from @DomainEvent; neither emitter renders it.' },
  aggregateType: {
    state: 'RESERVED',
    written: true,
    reason: 'Always the declaring entity, which both emitters already have; neither reads it off the event.',
  },
  payloadFields: { state: 'READ', written: true },
  sensitiveFields: { state: 'READ', written: true },
  trigger: { state: 'JAVA_ONLY', written: true, reason: EVENT_PLACEMENT },
  actionName: { state: 'JAVA_ONLY', written: true, reason: EVENT_PLACEMENT },
  fieldName: { state: 'JAVA_ONLY', written: true, reason: EVENT_PLACEMENT },
} as const satisfies { readonly [K in keyof DomainEventMetadata]-?: NestedContractCoverageEntry };

const RECORD_DEFAULT = 'The processor writes only the record default, never a value from the source.';

export const RELATIONSHIP_CONTRACT_COVERAGE = {
  name: { state: 'READ', written: true },
  fieldName: { state: 'READ', written: true },
  targetEntity: { state: 'READ', written: true },
  type: { state: 'READ', written: true },
  mappedBy: { state: 'RESERVED', written: true, reason: 'Written from @Relationship; no emitter reads it.' },
  fetch: { state: 'RESERVED', written: false, reason: RECORD_DEFAULT },
  cascade: {
    state: 'JAVA_ONLY',
    written: true,
    reason: 'Sets ON DELETE CASCADE on the emitted foreign key constraint.',
  },
  orphanRemoval: { state: 'RESERVED', written: false, reason: RECORD_DEFAULT },
  optional: { state: 'RESERVED', written: false, reason: RECORD_DEFAULT },
  lazy: { state: 'RESERVED', written: false, reason: RECORD_DEFAULT },
  displayField: {
    state: 'GAP',
    written: true,
    reason:
      'Written from @Relationship, which requires it; the list and detail views label a foreign key link with ' +
      'the raw id and the form offers a plain input, so the declared display field is shown nowhere.',
  },
  valueField: { state: 'RESERVED', written: false, reason: RECORD_DEFAULT },
  joinColumns: { state: 'RESERVED', written: false, reason: RECORD_DEFAULT },
} as const satisfies { readonly [K in keyof RelationshipMetadata]-?: NestedContractCoverageEntry };

export const SAGA_STEP_CONTRACT_COVERAGE = {
  name: { state: 'READ', written: true },
  service: {
    state: 'RESERVED',
    written: true,
    reason: 'Written from @SagaStep; the emitted step is a skeleton for the author to override, and nothing dispatches to the service.',
  },
  command: {
    state: 'RESERVED',
    written: true,
    reason: 'Written from @SagaStep; the emitted step is a skeleton for the author to override, and no command is sent.',
  },
  compensation: { state: 'READ', written: true },
  timeout: {
    state: 'RESERVED',
    written: true,
    reason: 'Written from @SagaStep; the kernel flow model has no per-step deadline to emit.',
  },
  maxRetries: { state: 'RESERVED', written: false, reason: `${RECORD_DEFAULT} @SagaStep.retries is not extracted.` },
  order: {
    state: 'RESERVED',
    written: true,
    reason: 'The processor sorts the steps by it; both emitters walk the list in the order given.',
  },
  parallel: {
    state: 'RESERVED',
    written: true,
    reason: 'Written from @SagaStep; the kernel flow model has no concurrent steps, so both emitters chain them.',
  },
  condition: { state: 'RESERVED', written: false, reason: NOT_WRITTEN },
  dependsOn: { state: 'RESERVED', written: false, reason: RECORD_DEFAULT },
} as const satisfies { readonly [K in keyof SagaStepMetadata]-?: NestedContractCoverageEntry };

const NO_EVENT_SOURCING = 'No emitter on either side produces an event-sourced aggregate yet.';

export const EVENT_SOURCED_CONTRACT_COVERAGE = {
  aggregateType: { state: 'RESERVED', written: true, reason: NO_EVENT_SOURCING },
  snapshotEvery: { state: 'RESERVED', written: true, reason: NO_EVENT_SOURCING },
  eventStore: { state: 'RESERVED', written: false, reason: `${NOT_WRITTEN} ${NO_EVENT_SOURCING}` },
} as const satisfies { readonly [K in keyof EventSourcedMetadata]-?: NestedContractCoverageEntry };

const NO_INTERNAL_API_SOURCE = `${NOT_WRITTEN} @InternalApi declares no such attribute.`;

export const INTERNAL_API_CONTRACT_COVERAGE = {
  readOnly: {
    state: 'RESERVED',
    written: false,
    reason: `${NO_INTERNAL_API_SOURCE} The guard emitter reads it, and the composed app does not run that emitter.`,
  },
  internal: {
    state: 'RESERVED',
    written: true,
    reason: 'Written as the presence of @InternalApi; no emitter on either side gates on it.',
  },
  reason: { state: 'RESERVED', written: false, reason: NO_INTERNAL_API_SOURCE },
  since: { state: 'RESERVED', written: false, reason: NO_INTERNAL_API_SOURCE },
  disabledActions: { state: 'RESERVED', written: false, reason: NO_INTERNAL_API_SOURCE },
  allowedRoles: { state: 'RESERVED', written: false, reason: NO_INTERNAL_API_SOURCE },
} as const satisfies { readonly [K in keyof InternalApiMetadata]-?: NestedContractCoverageEntry };
