/**
 * Contract coverage — every top-level field of `DomainMetadataSchema` is in exactly one state.
 *
 * `READ` means a TS generator reads the field while the orchestrator composes the emitted app;
 * `JAVA_ONLY`, `RESERVED` and `GAP` carry their reason in `src/models/contract-coverage.ts`. The read set
 * is measured, not inferred from source: every domain handed to `buildGeneratedFiles` — the
 * app's own and every peer's — is wrapped in a Proxy that records each top-level property the
 * pipeline touches. A text search cannot tell `domain.path` from `path.join`, and cannot see a
 * read made through a helper or a destructuring.
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
import { DomainMetadataSchema, type DomainMetadata } from '../../src/models/domain-model.js';
import { DEFAULT_CONFIG, type GeneratorConfig } from '../../src/config.js';
import type { PeerContract } from '../../src/peers/peer-contract.js';
import { CONTRACT_COVERAGE, type ContractCoverageEntry } from '../../src/models/contract-coverage.js';

const SCHEMA_KEYS = Object.keys(DomainMetadataSchema.shape);

/**
 * Property names a runtime or a test framework probes on any object it is handed, none of which a
 * generator means as a metadata read.
 */
const PROBES = new Set(['then', 'toJSON', 'constructor', 'asymmetricMatch', '$$typeof', 'nodeType', 'tagName']);

interface Recorder {
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
    (SCHEMA_KEYS.includes(key) ? rec.reads : rec.undeclared).add(key);
  }
}

function watch(domain: DomainMetadata, rec: Recorder): DomainMetadata {
  return new Proxy(domain, {
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
  ],
  actions: [
    { name: 'cancel', httpMethod: 'POST' },
    { name: 'setStatus', params: [{ name: 'status', type: 'com.shop.OrderStatus', required: true }] },
    { name: 'track', streaming: true, streamEventType: 'progress' },
  ],
  events: [
    { name: 'OrderPlaced', payloadFields: ['id', 'total'], trigger: 'CREATE' },
    { name: 'OrderCancelled', payloadFields: ['id'], sensitiveFields: ['total'], trigger: 'ACTION', actionName: 'cancel' },
  ],
  relationships: [{ name: 'productId', fieldName: 'productId', targetEntity: 'com.shop.Product', type: 'MANY_TO_ONE' }],
  projections: [{ name: 'OrderSummary', fields: ['id', 'total'] }],
  uiMetadata: { icon: 'cart', listColumns: ['id', 'total', 'status'], searchFields: ['note'], filterFields: ['status'] },
  graphMetadata: { label: 'Order', edges: [{ name: 'contains', targetLabel: 'Product', relationType: 'CONTAINS' }] },
  sagaMetadata: {
    name: 'OrderFulfilment',
    steps: [
      { name: 'reserveStock', action: 'reserve', compensatingAction: 'releaseStock', order: 0 },
      { name: 'notifyCustomer', action: 'notify', order: 1 },
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
  eventSourced: { aggregateType: 'Order', snapshotInterval: 50 },
  internalApi: { hidden: false, readOnly: true, internal: true, reason: 'ops', disabledActions: ['cancel'] },
} as const;

function fixture(rec: Recorder) {
  const domains = [
    d(MAXIMAL),
    // A plain target for the relationship, at the other end of every flag.
    d({
      entityName: 'Product',
      restApi: false,
      dataScope: 'UNIVERSE',
      fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }],
    }),
    // Tenant-scoped through the deprecated boolean only, with no systemFields block.
    d({ entityName: 'Address', tenantScoped: true, fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'city', type: 'String' }] }),
    // Hidden: generators skip it, and must still decide to.
    d({ entityName: 'AuditTrail', internalApi: { hidden: true }, fields: [{ name: 'id', type: 'java.util.UUID' }] }),
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
    domains: domains.map((x) => watch(x, rec)),
    enums,
    peers: peers.map((p) => ({ ...p, domains: p.domains.map((x) => watch(x, rec)) })),
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
  generateTests: true,
};

function run(): Recorder {
  const rec: Recorder = { reads: new Set(), undeclared: new Set(), enumerations: [] };
  const { domains, enums, peers } = fixture(rec);
  buildGeneratedFiles(domains, enums, ALL_ON, [], peers);
  return rec;
}

const STATES = CONTRACT_COVERAGE as Record<string, ContractCoverageEntry>;
const classifiedAs = (state: ContractCoverageEntry['state']): string[] =>
  Object.keys(STATES).filter((k) => STATES[k].state === state);

describe('DomainMetadata contract coverage', () => {
  const rec = run();

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
    const { raw } = fixture({ reads: new Set(), undeclared: new Set(), enumerations: [] });
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
