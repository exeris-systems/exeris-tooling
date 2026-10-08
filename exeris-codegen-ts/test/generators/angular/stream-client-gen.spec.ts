/**
 * Coverage for src/generators/angular/stream-client-gen.ts — StreamClientGenerator
 * emits a native EventSource client per @ExerisDomain(realTimeApi) entity, hitting
 * the SAME {base}/stream route the kernel KernelStreamHandlerGenerator registers
 * via streamRoute(...), and listening for the SAME named events its frames carry
 * (ADR-043 Slice 1, ADR-044 obligations 2 and 5).
 */

import { describe, expect, it } from 'vitest';
import ts from 'typescript';
import {
  StreamClientGenerator,
  generateStreamClient,
} from '../../../src/generators/angular/stream-client-gen.js';
import {
  createGeneratorContext,
  type GeneratorContext,
} from '../../../src/core/generator-registry.js';
import {
  DomainMetadataSchema,
  type DomainMetadata,
} from '../../../src/models/domain-model.js';

const CTX: GeneratorContext = createGeneratorContext({});

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

// ---------- CodeGenerator contract ----------

describe('StreamClientGenerator — CodeGenerator metadata', () => {
  const gen = new StreamClientGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('StreamClientGenerator');
    expect(gen.artifactType).toBe('STREAM');
    expect(gen.priority).toBe(6);
    expect(gen.supportedBackends).toEqual([]);
  });
});

// ---------- generate — driver gating ----------

describe('StreamClientGenerator.generate — realTimeApi gating', () => {
  const gen = new StreamClientGenerator();

  it('returns null when realTimeApi is false (default)', () => {
    expect(gen.generate(domain({ entityName: 'Order' }), CTX)).toBeNull();
  });

  it('returns null for a tenant-partitioned domain (stream routes carry no tenant guard)', () => {
    for (const scope of [{ dataScope: 'TENANT' }, { dataScope: 'UNIVERSE' }, { tenantScoped: true }] as const) {
      expect(gen.generate(domain({ entityName: 'Order', realTimeApi: true, ...scope }), CTX)).toBeNull();
    }
  });

  it('emits services/<kebab>.stream.ts for a realTimeApi domain', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine', realTimeApi: true }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('services/order-line.stream.ts');
    expect(file!.artifactType).toBe('STREAM');
    expect(file!.overwritable).toBe(true);
  });
});

// ---------- route parity ----------

describe('StreamClientGenerator.generate — route parity with the kernel handler', () => {
  const gen = new StreamClientGenerator();

  it('targets {apiBasePath}{path}/stream — byte-for-byte with the kernel streamRoute path', () => {
    const d = domain({ entityName: 'Order', realTimeApi: true, path: '/orders' });
    const content = gen.generate(d, CTX)!.content;

    // CTX apiBasePath default is '' (the shipped default); path '/orders' → '/orders/stream',
    // which is what KernelStreamHandlerGenerator registers: effectivePath() + "/stream".
    expect(content).toContain(`private readonly streamUrl = '/orders/stream';`);
  });

  it('apiVersion is NOT folded into the stream URL — the router serves no version segment', () => {
    // The fixtures above leave apiVersion unset, which is why this emitter kept folding
    // it in long after the service emitter stopped: no test ever rendered the broken path.
    // KernelApplicationGenerator registers streamRoute at effectivePath() + "/stream".
    const d = domain({ entityName: 'Order', realTimeApi: true, path: '/orders', apiVersion: 'v1' });
    const content = gen.generate(d, CTX)!.content;

    expect(content).toContain(`private readonly streamUrl = '/orders/stream';`);
    expect(content).not.toContain('/v1/');
  });

  it('without a path, derives the segment the way effectivePath() does', () => {
    const d = domain({ entityName: 'Colony', realTimeApi: true });
    expect(gen.generate(d, CTX)!.content).toContain(`private readonly streamUrl = '/colonies/stream';`);
  });

  it('explicit apiPath still wins over the derived path', () => {
    const d = domain({
      entityName: 'Order',
      realTimeApi: true,
      path: '/anything',
      apiVersion: 'v2',
      apiPath: '/custom/orders',
    });
    expect(gen.generate(d, CTX)!.content).toContain(`private readonly streamUrl = '/custom/orders/stream';`);
  });

  it('uses a native EventSource with withCredentials (GET-only, cookie auth)', () => {
    const content = gen.generate(domain({ entityName: 'Order', realTimeApi: true }), CTX)!.content;

    expect(content).toContain('new EventSource(this.streamUrl, { withCredentials: true })');
    expect(content).toContain('stream(): Observable<MessageEvent<string>>');
    expect(content).toContain('return () => source.close();');
    expect(content).toContain('export class OrderStreamClient');
  });

  it('does not leak any text/event-stream literal or chunk framing (Core owns the wire)', () => {
    const content = gen.generate(domain({ entityName: 'Order', realTimeApi: true }), CTX)!.content;
    expect(content).not.toContain('text/event-stream');
  });

  it('lists one SSE event name per @DomainEvent, as the kernel handler names its frames', () => {
    // KernelStreamHandlerGenerator.eventBindings: the raw @DomainEvent name, or the entity name
    // + "Event" when the name is blank. Declaration order.
    const d = domain({
      entityName: 'Order',
      realTimeApi: true,
      events: [{ name: 'OrderPlaced' }, { name: 'OrderCancelled' }, { name: ' ' }],
    });
    const content = gen.generate(d, CTX)!.content;
    expect(content).toContain(
      "static readonly STREAM_EVENT_TYPES: readonly string[] = ['OrderPlaced', 'OrderCancelled', 'OrderEvent'];",
    );
    // Every frame is named, so onmessage would receive none of them.
    expect(content).not.toContain('source.onmessage');
  });

  it('an entity with no @DomainEvent lists no event name and says the stream carries only the heartbeat', () => {
    const content = gen.generate(domain({ entityName: 'Order', realTimeApi: true }), CTX)!.content;
    expect(content).toContain('static readonly STREAM_EVENT_TYPES: readonly string[] = [];');
    expect(content).toContain("'keep-alive' heartbeat and this stream delivers no message");
  });
});

// ---------- behaviour ----------

interface Observer {
  next(v: unknown): void;
  error(e: unknown): void;
  complete(): void;
}

/** The smallest Observable the emitted client needs: subscribe runs the producer once. */
class StubObservable {
  constructor(private readonly producer: (s: Observer) => () => void) {}

  subscribe(observer: Observer): { unsubscribe(): void } {
    return { unsubscribe: this.producer(observer) };
  }
}

/** Records what the client registers, and lets the test fire events and errors at it. */
class StubEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  static last: StubEventSource | undefined;

  readyState = StubEventSource.OPEN;
  closed = false;
  onerror: (() => void) | null = null;
  readonly listeners = new Map<string, (e: unknown) => void>();

  constructor(readonly url: string, readonly init: { withCredentials?: boolean }) {
    StubEventSource.last = this;
  }

  addEventListener(type: string, listener: (e: unknown) => void): void {
    this.listeners.set(type, listener);
  }

  close(): void {
    this.closed = true;
  }
}

function loadClient(content: string): new () => { stream(): StubObservable } {
  const source = content.replace(/^import .*;$/gm, '');
  const js = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports: Record<string, unknown> = {};
  new Function('exports', 'Injectable', 'Observable', 'EventSource', js)(
    exports, () => () => undefined, StubObservable, StubEventSource,
  );
  return exports.OrderStreamClient as new () => { stream(): StubObservable };
}

describe('StreamClientGenerator — emitted client behaviour', () => {
  const content = new StreamClientGenerator().generate(
    domain({ entityName: 'Order', realTimeApi: true, path: '/orders', events: [{ name: 'OrderPlaced' }, { name: 'OrderCancelled' }] }),
    CTX,
  )!.content;

  it('listens for each named event on the served route and forwards it', () => {
    const Client = loadClient(content);
    const seen: unknown[] = [];
    new Client().stream().subscribe({ next: (e) => seen.push(e), error: () => {}, complete: () => {} });
    const source = StubEventSource.last!;

    expect(source.url).toBe('/orders/stream');
    expect(source.init).toEqual({ withCredentials: true });
    expect([...source.listeners.keys()]).toEqual(['OrderPlaced', 'OrderCancelled']);

    const frame = { type: 'OrderPlaced', data: '{"id":"1"}' };
    source.listeners.get('OrderPlaced')!(frame);
    expect(seen).toEqual([frame]);
  });

  it('keeps the subscription across a reconnect and errors only once the source is closed', () => {
    const Client = loadClient(content);
    const errors: unknown[] = [];
    const sub = new Client().stream().subscribe({ next: () => {}, error: (e) => errors.push(e), complete: () => {} });
    const source = StubEventSource.last!;

    // The server closed the stream; the browser is reconnecting.
    source.readyState = StubEventSource.CONNECTING;
    source.onerror!();
    expect(errors).toHaveLength(0);

    source.readyState = StubEventSource.CLOSED;
    source.onerror!();
    expect(errors).toHaveLength(1);

    sub.unsubscribe();
    expect(source.closed).toBe(true);
  });
});

// ---------- spectate ----------

describe('StreamClientGenerator — spectate stream', () => {
  const gen = new StreamClientGenerator();
  const orders = domain({
    entityName: 'Order',
    realTimeApi: true,
    path: '/orders',
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'total', type: 'java.math.BigDecimal' },
    ],
    events: [
      { name: 'OrderPlaced', payloadFields: ['id', 'total'], sensitiveFields: ['total'] },
      { name: 'OrderCancelled', payloadFields: ['id'] },
    ],
  });
  const content = gen.generate(orders, CTX)!.content;

  it('opens GET {base}/{id}/stream on the same client, the id encoded', () => {
    expect(content).toContain('spectate(id: string): Observable<OrderSpectateFrame>');
    expect(content).toContain('return `/orders/${encodeURIComponent(id)}/stream`;');
    expect(content).toContain('new EventSource(this.spectateUrl(id), { withCredentials: true })');
  });

  it('follows the apiBasePath and an explicit apiPath like the live view', () => {
    const custom = domain({ entityName: 'Order', realTimeApi: true, apiPath: '/custom/orders', apiVersion: 'v2' });
    const out = gen.generate(custom, createGeneratorContext({ apiBasePath: '/api' }))!.content;
    expect(out).toContain('return `/api/custom/orders/${encodeURIComponent(id)}/stream`;');
  });

  it('types each frame by the payload of the @DomainEvent that names it', () => {
    expect(content).toContain('export interface OrderOrderPlacedSpectatePayload {');
    expect(content).toContain('// sensitive: redacted before publish');
    expect(content).toContain("| { readonly event: 'OrderPlaced'; readonly data: OrderOrderPlacedSpectatePayload }");
    expect(content).toContain("| { readonly event: 'OrderCancelled'; readonly data: OrderOrderCancelledSpectatePayload }");
  });

  it('types an entity with no @DomainEvent as a stream of no frame', () => {
    const out = gen.generate(domain({ entityName: 'Order', realTimeApi: true }), CTX)!.content;
    expect(out).toContain('export type OrderSpectateFrame = never;');
  });

  it('keeps a payload type per event whose names collapse to one identifier', () => {
    const out = gen.generate(domain({
      entityName: 'Order',
      realTimeApi: true,
      events: [{ name: 'order-placed' }, { name: 'orderPlaced' }, { name: 'order-placed' }],
    }), CTX)!.content;
    expect(out).toContain('export interface OrderOrderPlacedSpectatePayload {');
    expect(out).toContain('export interface OrderOrderPlacedSpectatePayload2 {');
    expect(out).not.toContain('SpectatePayload3');
  });

  it('is not emitted for a tenant-partitioned entity', () => {
    expect(gen.generate(domain({ entityName: 'Order', realTimeApi: true, dataScope: 'TENANT' }), CTX)).toBeNull();
  });
});

describe('StreamClientGenerator — spectate behaviour', () => {
  const content = new StreamClientGenerator().generate(
    domain({ entityName: 'Order', realTimeApi: true, path: '/orders', events: [{ name: 'OrderPlaced' }, { name: 'OrderCancelled' }] }),
    CTX,
  )!.content;

  interface SpectateClient {
    spectate(id: string): StubObservable;
  }
  const load = (): { Client: new () => SpectateClient; SpectateError: { fromFrame(d: string): unknown } } => {
    const source = content.replace(/^import .*;$/gm, '');
    const js = ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    }).outputText;
    const exports: Record<string, unknown> = {};
    new Function('exports', 'Injectable', 'Observable', 'EventSource', js)(
      exports, () => () => undefined, StubObservable, StubEventSource,
    );
    return {
      Client: exports.OrderStreamClient as new () => SpectateClient,
      SpectateError: exports.OrderSpectateError as { fromFrame(d: string): unknown },
    };
  };

  it('opens the row route and delivers each named event with its payload parsed', () => {
    const { Client } = load();
    const seen: unknown[] = [];
    new Client().spectate('a b').subscribe({ next: (e) => seen.push(e), error: () => {}, complete: () => {} });
    const source = StubEventSource.last!;

    expect(source.url).toBe('/orders/a%20b/stream');
    expect(source.init).toEqual({ withCredentials: true });
    expect([...source.listeners.keys()]).toEqual(['OrderPlaced', 'OrderCancelled', 'stream-error']);

    source.listeners.get('OrderPlaced')!({ type: 'OrderPlaced', data: '{"id":"1"}' });
    expect(seen).toEqual([{ event: 'OrderPlaced', data: { id: '1' } }]);
  });

  it('has no listener for the keep-alive heartbeat', () => {
    const { Client } = load();
    new Client().spectate('1').subscribe({ next: () => {}, error: () => {}, complete: () => {} });
    expect(StubEventSource.last!.listeners.has('keep-alive')).toBe(false);
  });

  it('errors and closes the source on a stream-error frame, with the problem members', () => {
    const { Client } = load();
    const errors: unknown[] = [];
    new Client().spectate('1').subscribe({
      next: () => {},
      error: (e) => errors.push(e),
      complete: () => {},
    });
    const source = StubEventSource.last!;

    source.listeners.get('stream-error')!({
      type: 'stream-error',
      data: '{"status":404,"title":"Not Found","detail":"no such order"}',
    });

    expect(source.closed).toBe(true);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toMatchObject({ status: 404, title: 'Not Found', detail: 'no such order', message: 'no such order' });
  });

  it('reads a stream-error frame whose data is not a problem object', () => {
    const { SpectateError } = load();
    expect(SpectateError.fromFrame('not json')).toMatchObject({ message: 'Order spectate stream failed' });
  });

  it('errors and closes the source on a payload that is not JSON', () => {
    const { Client } = load();
    const errors: unknown[] = [];
    const seen: unknown[] = [];
    new Client().spectate('1').subscribe({ next: (e) => seen.push(e), error: (e) => errors.push(e), complete: () => {} });
    const source = StubEventSource.last!;

    source.listeners.get('OrderPlaced')!({ type: 'OrderPlaced', data: '{' });

    expect(errors).toHaveLength(1);
    expect(seen).toHaveLength(0);
    expect(source.closed).toBe(true);
  });

  it('errors only once the source is closed, and closes on unsubscribe', () => {
    const { Client } = load();
    const errors: unknown[] = [];
    const sub = new Client().spectate('1').subscribe({ next: () => {}, error: (e) => errors.push(e), complete: () => {} });
    const source = StubEventSource.last!;

    source.readyState = StubEventSource.CONNECTING;
    source.onerror!();
    expect(errors).toHaveLength(0);

    source.readyState = StubEventSource.CLOSED;
    source.onerror!();
    expect(errors).toHaveLength(1);

    sub.unsubscribe();
    expect(source.closed).toBe(true);
  });
});

// ---------- determinism ----------

describe('StreamClientGenerator — determinism', () => {
  const gen = new StreamClientGenerator();

  it('same metadata → byte-identical output (no timestamps/UUIDs/random)', () => {
    const d = domain({ entityName: 'Order', realTimeApi: true, path: '/orders' });
    const a = gen.generate(d, CTX)!.content;
    const b = gen.generate(d, CTX)!.content;
    expect(a).toBe(b);
    expect(a).not.toMatch(/\d{4}-\d{2}-\d{2}T/); // ISO timestamp
  });
});

// ---------- generateAggregate — barrel ----------

describe('StreamClientGenerator.generateAggregate — barrel', () => {
  const gen = new StreamClientGenerator();

  it('emits services/streams.index.ts barreling only realTimeApi domains', () => {
    const files = gen.generateAggregate([
      domain({ entityName: 'Order', realTimeApi: true }),
      domain({ entityName: 'Invoice' }), // no realTimeApi
    ], CTX);

    expect(files).toHaveLength(1);
    const barrel = files[0];
    expect(barrel.path).toBe('services/streams.index.ts');
    expect(barrel.content).toContain("export * from './order.stream';");
    expect(barrel.content).not.toContain('invoice.stream');
  });

  it('emits nothing when no domain is realTimeApi', () => {
    const files = gen.generateAggregate([domain({ entityName: 'Order' })], CTX);
    expect(files).toHaveLength(0);
  });
});

// ---------- convenience function ----------

describe('generateStreamClient — top-level convenience function', () => {
  it('routes through StreamClientGenerator and returns the per-domain file', () => {
    const file = generateStreamClient(domain({ entityName: 'Order', realTimeApi: true }), CTX.config);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('services/order.stream.ts');
    expect(file!.content).toContain('export class OrderStreamClient');
  });

  it('returns null for a non-realTimeApi domain', () => {
    expect(generateStreamClient(domain({ entityName: 'Order' }), CTX.config)).toBeNull();
  });
});
