/**
 * FE build gate (T20) — generate a sample app and type-check its data layer.
 *
 * The codegen e2e otherwise asserts only emitted *text*, which is exactly how the
 * T20 break (an empty enum stub shadowing the real module) stayed latent. This gate
 * generates a fixture app via the real orchestrator and runs `tsc --noEmit` over the
 * data layer (`src/app/types/**` + `src/app/schemas/**`) — the files that import only
 * `zod` and each other, so they type-check without Angular installed. It catches the
 * enum/schema cross-reference breakage (TS2304/2305) that "doesn't compile" means.
 *
 * The Angular component/service layer needs `@angular/*`; the full `ng build` over a
 * generated app is the CI job (build.yml). This script is the fast, dependency-light
 * half that also runs locally.
 *
 * Exit code 0 = data layer type-checks; non-zero = regression.
 */

import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { execSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const pkgRoot = join(here, '..');
const dist = join(pkgRoot, 'dist');

// Dynamic import() needs a file:// URL, not a bare OS path — a bare `d:\…` path
// throws ERR_UNSUPPORTED_ESM_URL_SCHEME on Windows. pathToFileURL is a no-op-equivalent on POSIX.
const { buildGeneratedFiles } = await import(pathToFileURL(join(dist, 'orchestrator.js')).href);
const { DomainMetadataSchema } = await import(pathToFileURL(join(dist, 'models/domain-model.js')).href);
const { DEFAULT_CONFIG } = await import(pathToFileURL(join(dist, 'config.js')).href);

// Fixture: an entity with an ENUM-typed field, so the generated entity type and Zod
// schema import the enum module — if that module were the empty stub, this fails. Its bounded
// fields put `min` / `max` on a boxed number (a nullable schema), a primitive and a string: a
// bound that lands on Zod's nullable wrapper does not type-check.
const domain = DomainMetadataSchema.parse({
  packageName: 'com.shop',
  entityName: 'Battle',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'status', type: 'com.shop.BattleStatus', enumType: 'com.shop.BattleStatus' },
    { name: 'rounds', type: 'java.lang.Integer', required: true, min: 1, max: 12 },
    { name: 'odds', type: 'java.lang.Double', min: 0 },
    { name: 'turn', type: 'int', max: 99 },
    { name: 'arena', type: 'String', minLength: 2, maxLength: 40 },
    // Collection and zoned types exactly as the processor writes them (TypeMirror.toString()):
    // qualified containers and element types, and an offset date-time.
    { name: 'tags', type: 'java.util.List<java.lang.String>' },
    { name: 'phases', type: 'java.util.List<com.shop.BattleStatus>' },
    { name: 'scores', type: 'java.util.Map<java.lang.String,java.lang.Integer>' },
    { name: 'judges', type: 'java.util.Set<java.util.UUID>' },
    { name: 'startsAt', type: 'java.time.OffsetDateTime' },
    { name: 'endsAt', type: 'java.time.ZonedDateTime' },
  ],
});
const enums = [{
  name: 'BattleStatus',
  qualifiedName: 'com.shop.BattleStatus',
  packageName: 'com.shop',
  values: [
    { name: 'ACTIVE', displayName: 'Active', ordinal: 0 },
    { name: 'RESOLVED', displayName: 'Resolved', ordinal: 1 },
  ],
}];

const tscBin = join(pkgRoot, 'node_modules', 'typescript', 'bin', 'tsc');

/** Generate a fixture app, write its data layer to a temp dir, and `tsc --noEmit`.
 *  The data layer (types + schemas + the enum module) imports only `zod` + itself,
 *  so it type-checks without an Angular install. */
function check(label, domains, fixtureEnums, peers = [], consumer = null, withServices = false) {
  const files = buildGeneratedFiles(domains, fixtureEnums, DEFAULT_CONFIG, [], peers);
  const dataLayer = files.filter(
    (f) =>
      f.path.startsWith('src/app/types/') ||
      f.path.startsWith('src/app/schemas/') ||
      f.path.startsWith('src/app/peers/') ||
      // A stream client imports only @angular/core and rxjs, which the ambient stubs below stand in for.
      (withServices && (/^src\/app\/services\/[a-z0-9-]+\.stream\.ts$/.test(f.path)
        || /^src\/app\/services\/(stream-types|[a-z0-9-]+\.action-streams)\.ts$/.test(f.path))),
  );
  if (dataLayer.length === 0) {
    console.error(`verify:generated [${label}] — no data-layer files emitted; orchestrator changed?`);
    process.exit(1);
  }

  const tmp = join(pkgRoot, '.verify-tmp', label);
  rmSync(tmp, { recursive: true, force: true });
  for (const f of dataLayer) {
    const full = join(tmp, f.path);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, f.content);
  }
  // Not generator output — a hand-written consumer module, so the peer types are
  // type-checked as something an app IMPORTS rather than merely as files that parse.
  if (consumer) {
    const full = join(tmp, 'src/app/consumer.ts');
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, consumer);
  }
  if (withServices) {
    const stubs = join(tmp, 'src/app/stubs/ambient.d.ts');
    mkdirSync(dirname(stubs), { recursive: true });
    writeFileSync(stubs, [
      "declare module '@angular/core' {",
      '  export function Injectable(options?: { providedIn?: string }): (value: unknown, context?: unknown) => void;',
      '}',
      "declare module 'rxjs' {",
      '  export interface Subscription { unsubscribe(): void }',
      '  export interface Observer<T> { next: (value: T) => void; error: (err: unknown) => void; complete: () => void }',
      '  export interface Subscriber<T> extends Observer<T> {}',
      '  export class Observable<T> {',
      '    constructor(subscribe: (subscriber: Observer<T>) => (() => void) | void);',
      '    subscribe(observer: Partial<Observer<T>>): Subscription;',
      '  }',
      '}',
      '',
    ].join('\n'));
  }
  writeFileSync(join(tmp, 'tsconfig.json'), JSON.stringify({
    compilerOptions: {
      target: 'ES2022',
      module: 'ESNext',
      moduleResolution: 'bundler',
      strict: true,
      noEmit: true,
      skipLibCheck: true,
      lib: ['ES2022', 'DOM'],
      types: [],
    },
    include: [
      'src/app/types/**/*.ts',
      'src/app/schemas/**/*.ts',
      'src/app/peers/**/*.ts',
      'src/app/services/**/*.ts',
      'src/app/stubs/**/*.d.ts',
      'src/app/consumer.ts',
    ],
  }, null, 2));

  console.log(`verify:generated [${label}] — type-checking ${dataLayer.length} data-layer file(s)…`);
  try {
    execSync(`node "${tscBin}" -p "${join(tmp, 'tsconfig.json')}"`, {
      cwd: pkgRoot,
      stdio: 'inherit',
      timeout: 60_000,
    });
  } catch {
    console.error(`\n✗ [${label}] generated frontend data layer does NOT type-check (T20 regression).`);
    rmSync(join(pkgRoot, '.verify-tmp'), { recursive: true, force: true });
    process.exit(1);
  }
  rmSync(tmp, { recursive: true, force: true });
}

// (1) entity with an enum-typed field; (2) zero-enum project — the empty enum module
// + barrels re-exporting ./enums must still resolve (dangling re-export = TS2307).
check('with-enums', [domain], enums);
check('zero-enums', [DomainMetadataSchema.parse({ packageName: 'com.shop', entityName: 'Plain', fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }] })], []);

// (3) T42/ADR-048 §3 — the app and two peers each declare an entity named `Order`, and one
// peer declares an enum with the same name as the app's own. Emitting them is not the claim;
// the claim is that a consumer can hold all three at once. So a hand-written module imports
// all three into ONE namespace under distinct local names and cross-assigns their fields.
// Nothing else in this repository proves that: unit tests assert on emitted text, and
// `ng build` type-checks the peer trees without any module importing them together.
const peerOrder = (pkg, extra) => DomainMetadataSchema.parse({
  packageName: pkg,
  entityName: 'Order',
  fields: [{ name: 'id', type: 'java.util.UUID' }, ...extra],
});
check(
  'two-peers-same-entity',
  [peerOrder('com.shop', [{ name: 'total', type: 'java.math.BigDecimal' }])],
  [{ name: 'OrderStatus', qualifiedName: 'com.shop.OrderStatus', packageName: 'com.shop',
     values: [{ name: 'OPEN', displayName: 'Open', ordinal: 0 }] }],
  [
    {
      name: 'billing',
      domains: [peerOrder('com.billing', [{ name: 'invoiceNo', type: 'String', required: true }, { name: 'lines', type: 'java.lang.Long', min: 1 }])],
      enums: [{ name: 'OrderStatus', qualifiedName: 'com.billing.OrderStatus', packageName: 'com.billing',
                values: [{ name: 'DRAFT', displayName: 'Draft', ordinal: 0 }] }],
    },
    {
      name: 'shipping',
      domains: [peerOrder('com.shipping', [{ name: 'trackingCode', type: 'String', required: true }])],
      enums: [],
    },
  ],
  [
    "import type { Order, OrderStatus } from './types';",
    "import type { Order as BillingOrder, OrderStatus as BillingOrderStatus } from './peers/billing';",
    "import type { Order as ShippingOrder } from './peers/shipping';",
    '',
    '// Each peer keeps its own field set — a merged namespace would have collapsed these.',
    'export function fields(a: Order, b: BillingOrder, c: ShippingOrder): string {',
    '  return `${a.total} ${b.invoiceNo} ${c.trackingCode}`;',
    '}',
    '',
    "// Two enums of the same name, from two modules, held side by side.",
    'export function statuses(a: OrderStatus, b: BillingOrderStatus): string {',
    '  return `${a} ${b}`;',
    '}',
    '',
  ].join('\n'),
);

// (4) A versioned entity: its Update type and Update schema both require the version. The
// consumer checks the two agree in both directions, so a type that drops the version while the
// schema keeps it (or the reverse) fails here rather than at the first 409.
check(
  'versioned-update',
  [DomainMetadataSchema.parse({
    packageName: 'com.shop',
    entityName: 'Ticket',
    versioned: true,
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'title', type: 'String' },
      { name: 'version', type: 'java.lang.Long' },
    ],
  })],
  [],
  [],
  [
    "import type { z } from 'zod';",
    "import type { TicketUpdate } from './types';",
    "import { TicketUpdateSchema } from './schemas';",
    '',
    'type Parsed = z.infer<typeof TicketUpdateSchema>;',
    'export const fromSchema = (p: Parsed): TicketUpdate => p;',
    'export const toSchema = (u: TicketUpdate): Parsed => u;',
    '',
    '// The version is required: an update without it must not type-check.',
    "// @ts-expect-error — `version` is missing",
    "export const missing: TicketUpdate = { title: 'x' };",
    '',
  ].join('\n'),
);

// (5) The generated server's update is a full replacement: the PUT body is decoded into the whole
// entity and every domain column but the server-owned and read-only ones is written from it. The
// Update type and schema are the record as read without those fields, they agree in both
// directions, and a body that leaves out a required field does not type-check.
check(
  'full-replacement-update',
  [DomainMetadataSchema.parse({
    packageName: 'com.shop',
    entityName: 'Ledger',
    dataScope: 'TENANT',
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'title', type: 'String', required: true },
      { name: 'code', type: 'String', readOnly: true, required: true },
      { name: 'note', type: 'String', required: true },
      { name: 'tenantId', type: 'java.util.UUID' },
    ],
  })],
  [],
  [],
  [
    "import type { z } from 'zod';",
    "import type { Ledger, LedgerUpdate } from './types';",
    "import { LedgerUpdateSchema } from './schemas';",
    '',
    'type Parsed = z.infer<typeof LedgerUpdateSchema>;',
    'export const fromSchema = (p: Parsed): LedgerUpdate => p;',
    'export const toSchema = (u: LedgerUpdate): Parsed => u;',
    'export const fromLoaded = (loaded: Ledger): LedgerUpdate => ({ ...loaded, title: "renamed" });',
    '',
    "// @ts-expect-error — `note` is missing",
    "export const missing: LedgerUpdate = { title: 'x' };",
    '',
  ].join('\n'),
);

// (6) A key the entity inherits from a superclass: the processor accepts it and records only the
// entity's own fields, so `fields` lists no key. Workspace keeps the default key `id`, Statement
// renames it to `statementNo`. The entity type and schema carry the key all the same, the Update
// type and schema leave it out, and the two agree in both directions.
check(
  'inherited-key',
  [
    DomainMetadataSchema.parse({
      packageName: 'com.studio',
      entityName: 'Workspace',
      fields: [{ name: 'name', type: 'String', required: true }],
    }),
    DomainMetadataSchema.parse({
      packageName: 'com.studio',
      entityName: 'Statement',
      fields: [{ name: 'total', type: 'java.math.BigDecimal' }],
      systemFields: { primaryKeyField: 'statementNo' },
    }),
  ],
  [],
  [],
  [
    "import type { z } from 'zod';",
    "import type { Statement, StatementUpdate, Workspace, WorkspaceUpdate } from './types';",
    "import { StatementSchema, StatementUpdateSchema, WorkspaceSchema, WorkspaceUpdateSchema } from './schemas';",
    '',
    'export const workspaceKey = (w: Workspace): string | undefined => w.id;',
    'export const statementKey = (s: Statement): string | undefined => s.statementNo;',
    'export const parsedWorkspaceKey = (p: z.infer<typeof WorkspaceSchema>): string | undefined => p.id;',
    'export const parsedStatementKey = (p: z.infer<typeof StatementSchema>): string | undefined => p.statementNo;',
    'export const fromWorkspaceSchema = (p: z.infer<typeof WorkspaceUpdateSchema>): WorkspaceUpdate => p;',
    'export const fromStatementSchema = (p: z.infer<typeof StatementUpdateSchema>): StatementUpdate => p;',
    '',
    "// @ts-expect-error — the update body does not carry the key",
    "export const keyed: StatementUpdate = { total: '1', statementNo: 'x' };",
    '',
  ].join('\n'),
);

// (7) The generated server's update never writes a server-owned column from the body: the key, the
// owner, the audit fields, the soft-delete fields and any field a `systemFields` role names, nor a
// `readOnly` or `inUpdate = false` field (the client does not set it through a `PUT`). The
// Update type and schema agree in both directions, carry the version and leave those out, and a
// body that sets one of them does not type-check. The create body leaves out the key, the owner, a
// read-only field and an `inCreate = false` field, and carries an `inUpdate = false` one; the two
// agree in both directions as well.
check(
  'server-owned-update',
  [DomainMetadataSchema.parse({
    packageName: 'com.shop',
    entityName: 'Ticket',
    dataScope: 'TENANT',
    audited: true,
    softDelete: true,
    versioned: true,
    systemFields: { createdAtField: 'born', tenantIdField: 'orgId' },
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'title', type: 'String', required: true },
      { name: 'status', type: 'String', readOnly: true },
      { name: 'sku', type: 'String', inUpdate: false },
      { name: 'trackingCode', type: 'String', inCreate: false },
      { name: 'orgId', type: 'java.util.UUID' },
      { name: 'born', type: 'java.time.Instant' },
      { name: 'createdBy', type: 'String' },
      { name: 'updatedAt', type: 'java.time.Instant' },
      { name: 'updatedBy', type: 'String' },
      { name: 'deleted', type: 'boolean' },
      { name: 'deletedAt', type: 'java.time.Instant' },
      { name: 'deletedBy', type: 'String' },
      { name: 'version', type: 'java.lang.Long' },
    ],
  })],
  [],
  [],
  [
    "import type { z } from 'zod';",
    "import type { TicketCreate, TicketUpdate } from './types';",
    "import { TicketCreateSchema, TicketUpdateSchema } from './schemas';",
    '',
    'type Parsed = z.infer<typeof TicketUpdateSchema>;',
    'export const fromSchema = (p: Parsed): TicketUpdate => p;',
    'export const toSchema = (u: TicketUpdate): Parsed => u;',
    "export const body: TicketUpdate = { title: 'x', version: 3 };",
    "// @ts-expect-error — the update body does not carry an inUpdate = false field",
    "export const fixed: TicketUpdate = { title: 'x', version: 3, sku: 'S-1' };",
    "export const edited: TicketUpdate = { title: 'x', version: 3, trackingCode: 'T-1' };",
    '',
    'type ParsedCreate = z.infer<typeof TicketCreateSchema>;',
    'export const createFromSchema = (p: ParsedCreate): TicketCreate => p;',
    'export const createToSchema = (c: TicketCreate): ParsedCreate => c;',
    "export const created: TicketCreate = { title: 'x', sku: 'S-1' };",
    "// @ts-expect-error — the create body does not carry an inCreate = false field",
    "export const tracked: TicketCreate = { title: 'x', trackingCode: 'T-1' };",
    "// @ts-expect-error — the create body does not carry a read-only field",
    "export const statused: TicketCreate = { title: 'x', status: 'OPEN' };",
    "// @ts-expect-error — the create body does not carry the owner",
    "export const owned: TicketCreate = { title: 'x', orgId: 'o' };",
    "// @ts-expect-error — the create body does not carry the key",
    "export const keyed: TicketCreate = { title: 'x', id: 'k' };",
    '',
    "// @ts-expect-error — the update body does not carry the creation stamp",
    "export const stamped: TicketUpdate = { title: 'x', version: 3, born: '2026-01-01T00:00:00Z' };",
    "// @ts-expect-error — the update body does not carry the soft-delete flag",
    "export const restored: TicketUpdate = { title: 'x', version: 3, deleted: false };",
    "// @ts-expect-error — the update body does not carry the owner",
    "export const moved: TicketUpdate = { title: 'x', version: 3, orgId: 'o' };",
    "// @ts-expect-error — the update body does not carry a read-only field",
    "export const forged: TicketUpdate = { title: 'x', version: 3, status: 'CLOSED' };",
    "// @ts-expect-error — the version is required",
    "export const unversioned: TicketUpdate = { title: 'x' };",
    '',
  ].join('\n'),
);

// (8) A versioned, audited entity that declares neither the version nor the audit stamps: the entity
// type and schema carry them all the same (the server's entity schema lists them, read-only), the
// update sends the version back and leaves the stamps out, and the create carries none of them.
check(
  'undeclared-version',
  [DomainMetadataSchema.parse({
    packageName: 'com.shop',
    entityName: 'Note',
    versioned: true,
    audited: true,
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'body', type: 'String', required: true },
    ],
  })],
  [],
  [],
  [
    "import type { z } from 'zod';",
    "import type { Note, NoteCreate, NoteUpdate } from './types';",
    "import { NoteSchema, NoteUpdateSchema } from './schemas';",
    '',
    'export const stored = (n: Note): number | undefined => n.version;',
    'export const stamps = (n: Note): [string | undefined, string | undefined] => [n.createdAt, n.updatedAt];',
    'export const parsedStored = (p: z.infer<typeof NoteSchema>): number | undefined => p.version;',
    'export const resend = (n: Note): NoteUpdate => ({ body: n.body, version: n.version ?? 0 });',
    'export const fromSchema = (p: z.infer<typeof NoteUpdateSchema>): NoteUpdate => p;',
    "export const created: NoteCreate = { body: 'x' };",
    "// @ts-expect-error — the create body does not carry the version",
    "export const versioned: NoteCreate = { body: 'x', version: 1 };",
    "// @ts-expect-error — the create body does not carry the creation stamp",
    "export const stamped: NoteCreate = { body: 'x', createdAt: '2026-01-01T00:00:00Z' };",
    "// @ts-expect-error — the update body does not carry the update stamp",
    "export const touched: NoteUpdate = { body: 'x', version: 1, updatedAt: '2026-01-01T00:00:00Z' };",
    '',
  ].join('\n'),
);

// (9) The spectate stream of a realTimeApi entity: a consumer subscribes to one row's events and
// narrows each frame by its event name to the payload that event declares. A frame named for an
// event the entity does not declare does not type-check, a stream-error arrives as the entity's
// SpectateError, and an entity with no event has a stream of no frame.
check(
  'spectate-stream',
  [
    DomainMetadataSchema.parse({
      packageName: 'com.shop',
      entityName: 'Order',
      path: '/orders',
      realTimeApi: true,
      fields: [
        { name: 'id', type: 'java.util.UUID' },
        { name: 'total', type: 'java.math.BigDecimal' },
        { name: 'note', type: 'String' },
      ],
      events: [
        { name: 'OrderPlaced', payloadFields: ['id', 'total'] },
        { name: 'OrderCancelled', payloadFields: ['id'] },
      ],
    }),
    DomainMetadataSchema.parse({
      packageName: 'com.shop',
      entityName: 'Quiet',
      path: '/quiet',
      realTimeApi: true,
      fields: [{ name: 'id', type: 'java.util.UUID' }],
    }),
  ],
  [],
  [],
  [
    "import { OrderStreamClient, OrderSpectateError, type OrderSpectateFrame } from './services/order.stream';",
    "import { QuietStreamClient, type QuietSpectateFrame } from './services/quiet.stream';",
    '',
    'export function watch(client: OrderStreamClient, id: string) {',
    '  return client.spectate(id).subscribe({',
    '    next: (frame: OrderSpectateFrame) => {',
    '      switch (frame.event) {',
    "        case 'OrderPlaced': return frame.data.total;",
    "        case 'OrderCancelled': return frame.data.id;",
    '      }',
    '    },',
    '    error: (e: unknown) => (e instanceof OrderSpectateError ? e.status : undefined),',
    '  });',
    '}',
    '',
    "// @ts-expect-error — the payload of an OrderCancelled frame carries no total",
    "export const total = (f: Extract<OrderSpectateFrame, { event: 'OrderCancelled' }>) => f.data.total;",
    "// @ts-expect-error — Order declares no such event",
    "export const unknown: OrderSpectateFrame = { event: 'OrderShipped', data: { id: 'x' } };",
    '',
    'export const quiet = (client: QuietStreamClient, id: string, frame: QuietSpectateFrame) => [client.spectate(id), frame];',
    "// @ts-expect-error — Quiet declares no event, so no frame exists",
    "export const none: QuietSpectateFrame = { event: 'any', data: {} };",
    '',
  ].join('\n'),
  true,
);

// (10) A streaming action on a tenant-partitioned entity gets the same per-action stream client as a
// GLOBAL one: a consumer opens it by row id and reads every frame as the shared StreamFrame, and
// an id that is not a string does not type-check.
check(
  'tenant-action-stream',
  [DomainMetadataSchema.parse({
    packageName: 'com.shop',
    entityName: 'Shipment',
    dataScope: 'TENANT',
    fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'leg', type: 'java.lang.Integer' }],
    actions: [{ name: 'trackShipment', streaming: true, streamEventType: 'ShipmentMoved' }],
  })],
  [],
  [],
  [
    "import type { Observable } from 'rxjs';",
    "import { ShipmentTrackShipmentStreamClient } from './services/shipment.action-streams';",
    "import type { StreamFrame } from './services/stream-types';",
    '',
    'export const open = (client: ShipmentTrackShipmentStreamClient, id: string): Observable<StreamFrame> => client.stream(id);',
    'export const eventName: string = ShipmentTrackShipmentStreamClient.STREAM_EVENT_TYPE;',
    'export const names = (frames: Observable<StreamFrame>): void => {',
    '  frames.subscribe({ next: (frame) => { const name: string = frame.event; const data: string = frame.data; void [name, data]; } });',
    '};',
    '',
    "// @ts-expect-error — the row id is a string",
    'export const numeric = (client: ShipmentTrackShipmentStreamClient): Observable<StreamFrame> => client.stream(1);',
    '',
  ].join('\n'),
  true,
);

rmSync(join(pkgRoot, '.verify-tmp'), { recursive: true, force: true });
console.log('✓ Generated frontend data layer type-checks (with-enums + zero-enums + two-peers-same-entity + versioned-update + full-replacement-update + inherited-key + server-owned-update + undeclared-version + spectate-stream + tenant-action-stream).');
