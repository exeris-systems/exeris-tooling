/**
 * Contract coverage — every top-level field of `DomainMetadataSchema` is in exactly one state.
 *
 * `READ` means a TS generator reads the field while the orchestrator composes the emitted app;
 * `JAVA_ONLY`, `RESERVED` and `GAP` carry their reason in `src/models/contract-coverage.ts`. The read set
 * is measured, not inferred from source: every domain handed to `buildGeneratedFiles` — the
 * app's own and every peer's — is wrapped in a Proxy that records each top-level property the
 * pipeline touches. A text search cannot tell `domain.path` from `path.join`, and cannot see a
 * read made through a helper or a destructuring. Each object a domain nests — its fields, actions,
 * events, relationships and saga steps, `uiMetadata`, `eventSourced` and `internalApi` — is proxied
 * the same way, for the nested tables in the same file.
 *
 * The proxies wrap the parsed objects, after the last `DomainMetadataSchema.parse`: nothing in the
 * orchestrator re-parses, so the objects the generators receive are the proxies themselves.
 *
 * A read counts only when the pipeline actually runs it, so the fixture switches on every
 * generator flag and sets every schema field on at least one domain to a value that is not the
 * schema default. A field the fixture leaves unset fails here as well: an unset field cannot
 * show whether a generator would read it.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles, type EnumMetadataForGen } from '../../src/orchestrator.js';
import {
  ActionMetadataSchema,
  DomainEventMetadataSchema,
  DomainMetadataSchema,
  EventSourcedMetadataSchema,
  FieldMetadataSchema,
  InternalApiMetadataSchema,
  RelationshipMetadataSchema,
  SagaStepMetadataSchema,
  UIMetadataSchema,
  type DomainMetadata,
} from '../../src/models/domain-model.js';
import { DEFAULT_CONFIG, type GeneratorConfig } from '../../src/config.js';
import type { PeerContract } from '../../src/peers/peer-contract.js';
import {
  ACTION_CONTRACT_COVERAGE,
  CONTRACT_COVERAGE,
  EVENT_CONTRACT_COVERAGE,
  EVENT_SOURCED_CONTRACT_COVERAGE,
  FIELD_CONTRACT_COVERAGE,
  INTERNAL_API_CONTRACT_COVERAGE,
  RELATIONSHIP_CONTRACT_COVERAGE,
  SAGA_STEP_CONTRACT_COVERAGE,
  UI_CONTRACT_COVERAGE,
  type ContractCoverageEntry,
  type NestedContractCoverageEntry,
} from '../../src/models/contract-coverage.js';

const SCHEMA_KEYS = Object.keys(DomainMetadataSchema.shape);

/**
 * Property names a runtime or a test framework probes on any object it is handed, none of which a
 * generator means as a metadata read.
 */
const PROBES = new Set(['then', 'toJSON', 'constructor', 'asymmetricMatch', '$$typeof', 'nodeType', 'tagName']);

interface Recorder {
  /** The keys of the schema the watched objects were parsed with. */
  keys: readonly string[];
  reads: Set<string>;
  /** Property names read that the schema does not declare: Zod strips them, so they are always undefined. */
  undeclared: Set<string>;
  /** Whole-object enumerations (spread, Object.keys, JSON.stringify, for…in), with the call site. */
  enumerations: string[];
}

function callSite(): string {
  const frames = (new Error().stack ?? '').split('\n').slice(3);
  return frames.find((f) => f.includes('/src/'))?.trim() ?? frames[0]?.trim() ?? '<unknown>';
}

function note(rec: Recorder, key: string | symbol): void {
  if (typeof key === 'string' && !PROBES.has(key)) {
    (rec.keys.includes(key) ? rec.reads : rec.undeclared).add(key);
  }
}

function watch<T extends object>(target: T, rec: Recorder): T {
  return new Proxy(target, {
    get(target, key, receiver) {
      note(rec, key);
      return Reflect.get(target, key, receiver);
    },
    has(target, key) {
      note(rec, key);
      return Reflect.has(target, key);
    },
    getOwnPropertyDescriptor(target, key) {
      note(rec, key);
      return Reflect.getOwnPropertyDescriptor(target, key);
    },
    ownKeys(target) {
      rec.enumerations.push(callSite());
      return Reflect.ownKeys(target);
    },
  });
}

const d = (o: Record<string, unknown>): DomainMetadata => DomainMetadataSchema.parse({ packageName: 'com.shop', ...o });

/** Every schema field set, none at its default. */
const MAXIMAL = {
  entityName: 'Order',
  packageName: 'com.shop',
  tableName: 'shop_orders',
  displayName: 'Purchase Order',
  pluralName: 'PurchaseOrders',
  description: 'A customer order',
  path: '/purchase-orders',
  apiVersion: 'v2',
  apiPath: '/purchase-orders',
  module: 'sales',
  restApi: true,
  graphqlApi: true,
  realTimeApi: true,
  internalClient: true,
  dataScope: 'TENANT',
  tenantScoped: true,
  versioned: true,
  fullTextSearch: true,
  searchConfig: 'english',
  audited: true,
  softDelete: true,
  multiTenant: true,
  cacheable: true,
  cacheSeconds: 300,
  cacheTtl: 'PT5M',
  cacheRegion: 'orders',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'total', type: 'java.math.BigDecimal', dataType: 'currency', required: true, min: 0 },
    { name: 'status', type: 'com.shop.OrderStatus', enumType: 'com.shop.OrderStatus', required: true },
    { name: 'productId', type: 'java.util.UUID' },
    { name: 'note', type: 'String', searchable: true, sortable: true, filterable: true, maxLength: 200 },
    { name: 'expedited', type: 'boolean' },
    { name: 'placedAt', type: 'java.time.Instant' },
    { name: 'rev', type: 'java.lang.Long' },
    { name: 'tenantId', type: 'java.util.UUID' },
    { name: 'removed', type: 'boolean' },
    // The FieldMetadata keys the fields above leave at their defaults.
    {
      name: 'reference',
      type: 'String',
      columnName: 'ref_code',
      displayName: 'Reference',
      description: 'Customer reference',
      unique: true,
      indexed: true,
      audited: true,
      defaultValue: 'N/A',
      minLength: 3,
      pattern: '^[A-Z]+$',
      format: 'text',
      inUpdate: false,
    },
    { name: 'quantity', type: 'java.lang.Integer', max: 99, readOnly: true, inCreate: false },
    { name: 'grandTotal', type: 'java.math.BigDecimal', computed: true, computedFrom: ['total'] },
    { name: 'internalNote', type: 'String', hidden: true },
  ],
  actions: [
    {
      name: 'cancel',
      methodName: 'cancelOrder',
      displayName: 'Cancel',
      description: 'Cancel the order',
      httpMethod: 'PUT',
      resultType: 'void',
      async: true,
      permissions: ['order:cancel'],
      producesEvents: ['OrderCancelled'],
      routeAccess: 'AUTHENTICATED',
    },
    { name: 'setStatus', params: [{ name: 'status', type: 'com.shop.OrderStatus', required: true }] },
    { name: 'track', streaming: true, streamEventType: 'progress' },
  ],
  events: [
    {
      name: 'OrderPlaced',
      topic: 'shop.orders',
      description: 'An order was placed',
      aggregateType: 'Order',
      payloadFields: ['id', 'total'],
      trigger: 'CREATE',
    },
    { name: 'OrderCancelled', payloadFields: ['id'], sensitiveFields: ['total'], trigger: 'ACTION', actionName: 'cancel' },
    { name: 'NoteChanged', payloadFields: ['note'], trigger: 'UPDATE', fieldName: 'note' },
  ],
  relationships: [
    {
      name: 'productId',
      fieldName: 'productId',
      targetEntity: 'com.shop.Product',
      type: 'MANY_TO_ONE',
      mappedBy: 'orders',
      fetch: 'EAGER',
      cascade: 'ALL',
      orphanRemoval: true,
      optional: false,
      lazy: false,
      displayField: 'name',
      valueField: 'sku',
      joinColumns: ['product_id'],
    },
    // Hand-built metadata may omit fieldName; the processor writes it equal to the name.
    { name: 'tenantId', targetEntity: 'com.shop.Tenant', type: 'MANY_TO_ONE' },
    { name: 'lines', targetEntity: 'com.shop.OrderLine', type: 'ONE_TO_MANY', mappedBy: 'order' },
  ],
  projections: [{ name: 'OrderSummary', fields: ['id', 'total'] }],
  // Every page on, explicitly: the field keys are read by the list, detail and form emitters, so
  // the maximal entity must keep them. Product turns each switch off.
  uiMetadata: {
    icon: 'cart',
    color: '#0a7',
    listView: true,
    detailView: true,
    createForm: true,
    editForm: true,
    searchable: true,
    filterable: true,
    exportable: true,
  },
  graphMetadata: { label: 'Order', edges: [{ name: 'contains', targetLabel: 'Product', relationType: 'CONTAINS' }] },
  sagaMetadata: {
    name: 'OrderFulfilment',
    steps: [
      {
        name: 'reserveStock',
        service: 'stock',
        command: 'reserve',
        compensation: 'releaseStock',
        timeout: 'PT1M',
        maxRetries: 5,
        order: 0,
        parallel: true,
        condition: 'total > 0',
        dependsOn: ['validate'],
      },
      { name: 'notifyCustomer', service: 'mail', command: 'notify', order: 1 },
    ],
    compensationStrategy: 'ALL_OR_NOTHING',
    compensationOrder: 'REVERSE',
  },
  systemFields: {
    primaryKeyField: 'id',
    createdAtField: 'placedAt',
    updatedAtField: 'touchedAt',
    tenantIdField: 'tenantId',
    versionField: 'rev',
    softDeleteField: 'removed',
  },
  eventSourced: { aggregateType: 'Order', snapshotEvery: 50, eventStore: 'orders' },
  internalApi: {
    readOnly: true,
    internal: true,
    reason: 'ops',
    since: '0.9',
    disabledActions: ['cancel'],
    allowedRoles: ['ops'],
  },
} as const;

type AnySchema = { shape: Record<string, unknown>; parse(input: unknown): unknown };

/**
 * One nested table: the schema it mirrors, the objects of that schema a domain holds, and the
 * minimal object the schema accepts, whose parse gives each key's default.
 */
const NESTED_KINDS = {
  field: {
    label: 'FieldMetadata',
    table: 'FIELD_CONTRACT_COVERAGE',
    schema: FieldMetadataSchema as AnySchema,
    states: FIELD_CONTRACT_COVERAGE,
    minimal: { name: '_', type: '_' },
    of: (dm: DomainMetadata): object[] => dm.fields,
  },
  ui: {
    label: 'UIMetadata',
    table: 'UI_CONTRACT_COVERAGE',
    schema: UIMetadataSchema as AnySchema,
    states: UI_CONTRACT_COVERAGE,
    minimal: {},
    of: (dm: DomainMetadata): object[] => (dm.uiMetadata ? [dm.uiMetadata] : []),
  },
  action: {
    label: 'ActionMetadata',
    table: 'ACTION_CONTRACT_COVERAGE',
    schema: ActionMetadataSchema as AnySchema,
    states: ACTION_CONTRACT_COVERAGE,
    minimal: { name: '_' },
    of: (dm: DomainMetadata): object[] => dm.actions,
  },
  event: {
    label: 'DomainEventMetadata',
    table: 'EVENT_CONTRACT_COVERAGE',
    schema: DomainEventMetadataSchema as AnySchema,
    states: EVENT_CONTRACT_COVERAGE,
    minimal: { name: '_' },
    of: (dm: DomainMetadata): object[] => dm.events,
  },
  relationship: {
    label: 'RelationshipMetadata',
    table: 'RELATIONSHIP_CONTRACT_COVERAGE',
    schema: RelationshipMetadataSchema as AnySchema,
    states: RELATIONSHIP_CONTRACT_COVERAGE,
    minimal: { name: '_', targetEntity: '_', type: 'MANY_TO_ONE' },
    of: (dm: DomainMetadata): object[] => dm.relationships,
  },
  sagaStep: {
    label: 'SagaStepMetadata',
    table: 'SAGA_STEP_CONTRACT_COVERAGE',
    schema: SagaStepMetadataSchema as AnySchema,
    states: SAGA_STEP_CONTRACT_COVERAGE,
    minimal: { name: '_' },
    of: (dm: DomainMetadata): object[] => dm.sagaMetadata?.steps ?? [],
  },
  eventSourced: {
    label: 'EventSourcedMetadata',
    table: 'EVENT_SOURCED_CONTRACT_COVERAGE',
    schema: EventSourcedMetadataSchema as AnySchema,
    states: EVENT_SOURCED_CONTRACT_COVERAGE,
    minimal: { aggregateType: '_' },
    of: (dm: DomainMetadata): object[] => (dm.eventSourced ? [dm.eventSourced] : []),
  },
  internalApi: {
    label: 'InternalApiMetadata',
    table: 'INTERNAL_API_CONTRACT_COVERAGE',
    schema: InternalApiMetadataSchema as AnySchema,
    states: INTERNAL_API_CONTRACT_COVERAGE,
    minimal: {},
    of: (dm: DomainMetadata): object[] => (dm.internalApi ? [dm.internalApi] : []),
  },
} as const;

type NestedKind = keyof typeof NESTED_KINDS;
type Recorders = { domain: Recorder } & Record<NestedKind, Recorder>;

const newRecorder = (keys: readonly string[]): Recorder => ({ keys, reads: new Set(), undeclared: new Set(), enumerations: [] });

const recorders = (): Recorders => {
  const recs = { domain: newRecorder(SCHEMA_KEYS) } as Recorders;
  for (const kind of Object.keys(NESTED_KINDS) as NestedKind[]) {
    recs[kind] = newRecorder(Object.keys(NESTED_KINDS[kind].schema.shape));
  }
  return recs;
};

/** A watched copy of a domain whose nested objects are watched as well. */
function watchDomain(domain: DomainMetadata, recs: Recorders): DomainMetadata {
  const copy: DomainMetadata = {
    ...domain,
    fields: domain.fields.map((f) => watch(f, recs.field)),
    actions: domain.actions.map((a) => watch(a, recs.action)),
    events: domain.events.map((e) => watch(e, recs.event)),
    relationships: domain.relationships.map((r) => watch(r, recs.relationship)),
  };
  if (domain.uiMetadata) copy.uiMetadata = watch(domain.uiMetadata, recs.ui);
  if (domain.sagaMetadata) {
    copy.sagaMetadata = { ...domain.sagaMetadata, steps: domain.sagaMetadata.steps.map((s) => watch(s, recs.sagaStep)) };
  }
  if (domain.eventSourced) copy.eventSourced = watch(domain.eventSourced, recs.eventSourced);
  if (domain.internalApi) copy.internalApi = watch(domain.internalApi, recs.internalApi);
  return watch(copy, recs.domain);
}

function fixture(recs: Recorders) {
  const domains = [
    d(MAXIMAL),
    // A plain target for the relationship, at the other end of every flag.
    d({
      entityName: 'Product',
      restApi: false,
      dataScope: 'UNIVERSE',
      fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }],
      uiMetadata: {
        listView: false,
        detailView: false,
        createForm: false,
        editForm: false,
        searchable: false,
        filterable: false,
        exportable: false,
      },
    }),
    // The child of Order's ONE_TO_MANY, with the MANY_TO_ONE back its detail panel filters by.
    d({
      entityName: 'OrderLine',
      fields: [{ name: 'id', type: 'java.util.UUID' }],
      relationships: [{ name: 'order', targetEntity: 'com.shop.Order', type: 'MANY_TO_ONE' }],
    }),
    // Tenant-scoped through the deprecated boolean only, with no systemFields block.
    d({ entityName: 'Address', tenantScoped: true, fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'city', type: 'String' }] }),
    // GLOBAL with both stream routes: MAXIMAL is TENANT, whose stream clients are withheld, so
    // only an unpartitioned entity reaches the stream-client emitters past their scope check.
    d({
      entityName: 'Shipment',
      realTimeApi: true,
      actions: [{ name: 'track', streaming: true }],
      fields: [{ name: 'id', type: 'java.util.UUID' }],
    }),
  ];
  const enums: EnumMetadataForGen[] = [
    {
      name: 'OrderStatus',
      qualifiedName: 'com.shop.OrderStatus',
      packageName: 'com.shop',
      values: [
        { name: 'NEW', displayName: 'New', ordinal: 0 },
        { name: 'PAID', displayName: 'Paid', ordinal: 1 },
      ],
    },
  ];
  const peers: PeerContract[] = [
    { name: 'billing', domains: [d({ ...MAXIMAL, packageName: 'com.billing' })], enums: [] },
  ];
  return {
    domains: domains.map((x) => watchDomain(x, recs)),
    enums,
    peers: peers.map((p) => ({ ...p, domains: p.domains.map((x) => watchDomain(x, recs)) })),
    raw: [...domains, ...peers.flatMap((p) => p.domains)],
  };
}

/** Every flag that switches an emitter on. */
const ALL_ON: GeneratorConfig = {
  ...DEFAULT_CONFIG,
  generateZod: true,
  generateServices: true,
  generateForms: true,
  generateLists: true,
  generateDetails: true,
  generateStores: true,
  generateSagas: true,
  generateEvents: true,
  eventBusEndpoint: '/orders/events',
  generateTests: true,
};

function run(): Recorders {
  const recs = recorders();
  const { domains, enums, peers } = fixture(recs);
  buildGeneratedFiles(domains, enums, ALL_ON, [], peers);
  return recs;
}

const RUN = run();

const STATES = CONTRACT_COVERAGE as Record<string, ContractCoverageEntry>;
const classifiedAs = (state: ContractCoverageEntry['state']): string[] =>
  Object.keys(STATES).filter((k) => STATES[k].state === state);

describe('DomainMetadata contract coverage', () => {
  const rec = RUN.domain;

  it('classifies every schema field, and only schema fields, in schema order', () => {
    const unclassified = SCHEMA_KEYS.filter((k) => !(k in STATES));
    const stale = Object.keys(STATES).filter((k) => !SCHEMA_KEYS.includes(k));
    expect(
      unclassified,
      `DomainMetadataSchema fields with no entry in CONTRACT_COVERAGE (src/models/contract-coverage.ts): ` +
        `${unclassified.join(', ')}. Classify each as READ (a TS generator reads it), JAVA_ONLY, RESERVED or GAP ` +
        `(both with a one-line reason).`,
    ).toEqual([]);
    expect(
      stale,
      `CONTRACT_COVERAGE entries for fields DomainMetadataSchema no longer declares: ${stale.join(', ')}. Remove them.`,
    ).toEqual([]);
    expect(Object.keys(STATES), 'CONTRACT_COVERAGE lists its fields in DomainMetadataSchema declaration order.').toEqual(
      SCHEMA_KEYS,
    );
  });

  it('gives every JAVA_ONLY, RESERVED and GAP field a reason', () => {
    const missing = Object.keys(STATES).filter((k) => STATES[k].state !== 'READ' && !('reason' in STATES[k] && STATES[k].reason.trim()));
    expect(missing, `CONTRACT_COVERAGE entries without a reason: ${missing.join(', ')}.`).toEqual([]);
  });

  it('runs on a fixture that sets every schema field away from its default', () => {
    const defaults = DomainMetadataSchema.parse({ entityName: '_', packageName: '_' }) as Record<string, unknown>;
    const { raw } = fixture(recorders());
    const unexercised = SCHEMA_KEYS.filter((k) =>
      raw.every((dm) => {
        const v = (dm as Record<string, unknown>)[k];
        return v === undefined || JSON.stringify(v) === JSON.stringify(defaults[k]);
      }),
    );
    expect(
      unexercised,
      `Fields no fixture domain sets to a non-default value: ${unexercised.join(', ')}. Set them in MAXIMAL ` +
        `(test/contract/contract-coverage.spec.ts) — a field left at its default cannot show whether a generator reads it.`,
    ).toEqual([]);
  });

  it('sees no whole-object enumeration of a domain', () => {
    // A spread, Object.keys, JSON.stringify or for…in touches every key, which would make every
    // field look read. None happens today; one that appears must be removed or justified here.
    expect(
      rec.enumerations,
      `A generator enumerates a whole DomainMetadata object, which reads every field indiscriminately. ` +
        `Call sites:\n  ${[...new Set(rec.enumerations)].join('\n  ')}\nRead the fields it needs by name instead.`,
    ).toEqual([]);
  });

  it('reads no property the schema does not declare', () => {
    expect(
      [...rec.undeclared].sort(),
      `Generators read DomainMetadata properties the schema does not declare (Zod strips them, so they are always ` +
        `undefined): ${[...rec.undeclared].join(', ')}. Declare them in DomainMetadataSchema or stop reading them.`,
    ).toEqual([]);
  });

  it('marks READ every field a generator reads', () => {
    const readButNotClassified = SCHEMA_KEYS.filter((k) => rec.reads.has(k) && STATES[k]?.state !== 'READ').map(
      (k) => `${k} (classified ${STATES[k]?.state ?? 'nowhere'})`,
    );
    expect(
      readButNotClassified,
      `TS generators read these fields, but CONTRACT_COVERAGE does not mark them READ: ${readButNotClassified.join(', ')}. ` +
        `Move each to { state: 'READ' } in src/models/contract-coverage.ts.`,
    ).toEqual([]);
  });

  it('marks READ only fields a generator reads', () => {
    const classifiedButNotRead = classifiedAs('READ').filter((k) => !rec.reads.has(k));
    expect(
      classifiedButNotRead,
      `CONTRACT_COVERAGE marks these fields READ, but no TS generator read them during generation: ` +
        `${classifiedButNotRead.join(', ')}. Move each to JAVA_ONLY, RESERVED or GAP with a reason — or, if a generator ` +
        `should read it, make sure the fixture reaches that code path.`,
    ).toEqual([]);
  });
});

/**
 * The same checks one level down, on the run above: every nested object the generators receive is
 * proxied, so a key counts as read only when a generator reads it off the object itself.
 */
const NESTED = (Object.keys(NESTED_KINDS) as NestedKind[]).map((kind) => ({
  kind,
  label: NESTED_KINDS[kind].label,
  table: NESTED_KINDS[kind].table,
  keys: RUN[kind].keys,
  rec: RUN[kind],
  states: NESTED_KINDS[kind].states as Record<string, NestedContractCoverageEntry>,
}));

describe.each(NESTED)('$label contract coverage', ({ kind, label, table, keys, rec, states }) => {

  it('classifies every schema key, and only schema keys, in schema order', () => {
    expect(Object.keys(states), `${table} lists exactly the ${label} schema keys, in declaration order.`).toEqual(keys);
  });

  it('gives every JAVA_ONLY, RESERVED and GAP key a reason', () => {
    const missing = keys.filter((k) => states[k].state !== 'READ' && !('reason' in states[k] && states[k].reason.trim()));
    expect(missing, `${table} entries without a reason: ${missing.join(', ')}.`).toEqual([]);
  });

  it('acts on no key the processor never writes', () => {
    const acted = keys.filter((k) => !states[k].written && states[k].state !== 'READ' && states[k].state !== 'RESERVED');
    expect(acted, `${table} marks keys the processor never writes as acted on: ${acted.join(', ')}.`).toEqual([]);
  });

  it('runs on a fixture that sets every key away from its default', () => {
    const { raw } = fixture(recorders());
    const { of, schema, minimal } = NESTED_KINDS[kind];
    const objects = raw.flatMap(of) as Record<string, unknown>[];
    const defaults = schema.parse(minimal) as Record<string, unknown>;
    const unexercised = keys.filter((k) =>
      objects.every((o) => o[k] === undefined || JSON.stringify(o[k]) === JSON.stringify(defaults[k])),
    );
    expect(unexercised, `${label} keys no fixture object sets to a non-default value: ${unexercised.join(', ')}.`).toEqual(
      [],
    );
  });

  it('sees no whole-object enumeration', () => {
    expect(
      [...new Set(rec.enumerations)],
      `A generator enumerates a whole ${label} object, which reads every key indiscriminately.`,
    ).toEqual([]);
  });

  it('reads no key the schema does not declare', () => {
    expect([...rec.undeclared].sort(), `Generators read ${label} keys the schema strips.`).toEqual([]);
  });

  it('marks READ exactly the keys a generator reads', () => {
    const read = keys.filter((k) => rec.reads.has(k));
    expect(
      read,
      `${table} must mark READ exactly the keys TS generators read during generation (measured below).`,
    ).toEqual(keys.filter((k) => states[k].state === 'READ'));
  });
});
