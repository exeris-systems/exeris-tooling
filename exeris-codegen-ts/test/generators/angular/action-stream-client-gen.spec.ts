/**
 * Coverage for src/generators/angular/action-stream-client-gen.ts —
 * ActionStreamClientGenerator emits an RxJS-over-fetch client per
 * @Action(streaming) action, opening the stream over POST at the SAME
 * {base}/{id}/actions/{kebab} route the kernel KernelActionStreamHandlerGenerator
 * registers via streamRoute(POST, ...) (ADR-044 Slice 2 Java/TS parity, Axis 4b).
 */

import { describe, expect, it } from 'vitest';
import {
  ActionStreamClientGenerator,
  generateActionStreamClient,
} from '../../../src/generators/angular/action-stream-client-gen.js';
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

const streamingAction = {
  name: 'trackShipment',
  streaming: true,
  streamEventType: 'ShipmentMoved',
};
const plainAction = { name: 'cancel' };

// ---------- CodeGenerator contract ----------

describe('ActionStreamClientGenerator — CodeGenerator metadata', () => {
  const gen = new ActionStreamClientGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('ActionStreamClientGenerator');
    expect(gen.artifactType).toBe('STREAM');
    expect(gen.priority).toBe(6);
    expect(gen.supportedBackends).toEqual([]);
  });
});

// ---------- generate — driver gating ----------

describe('ActionStreamClientGenerator.generate — streaming-action gating', () => {
  const gen = new ActionStreamClientGenerator();

  it('returns null when no action is streaming', () => {
    expect(gen.generate(domain({ entityName: 'Order', actions: [plainAction] }), CTX)).toBeNull();
  });

  it('returns null for a hidden internal API even with a streaming action', () => {
    const d = domain({
      entityName: 'Order',
      actions: [streamingAction],
      internalApi: { hidden: true, readOnly: false, internal: false },
    });
    expect(gen.generate(d, CTX)).toBeNull();
  });

  it('emits services/<kebab>.action-streams.ts for an entity with a streaming action', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine', actions: [streamingAction] }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('services/order-line.action-streams.ts');
    expect(file!.artifactType).toBe('STREAM');
    expect(file!.overwritable).toBe(true);
  });
});

// ---------- route parity (byte-for-byte with the kernel streamRoute) ----------

describe('ActionStreamClientGenerator.generate — route + transport parity', () => {
  const gen = new ActionStreamClientGenerator();

  it('opens over POST at {apiBasePath}{path}/{id}/actions/{kebab} — byte-for-byte with the Java streamRoute', () => {
    const d = domain({ entityName: 'Order', actions: [streamingAction], path: '/orders' });
    const content = gen.generate(d, CTX)!.content;

    // CTX apiBasePath default is '/api'; path '/orders', kebab(trackShipment) = track-shipment.
    expect(content).toContain('const url = `/api/orders/${id}/actions/track-shipment`;');
    expect(content).toContain("method: 'POST'");
    expect(content).toContain("credentials: 'include'");
  });

  it('is RxJS over fetch + ReadableStream (NOT a native EventSource — POST-open, Axis 4b)', () => {
    const content = gen.generate(domain({ entityName: 'Order', actions: [streamingAction] }), CTX)!.content;

    expect(content).toContain('stream(id: string): Observable<StreamFrame>');
    expect(content).toContain('response.body.getReader()');
    expect(content).not.toContain('new EventSource');
  });

  it('aborts the fetch on unsubscribe (no leaked connection)', () => {
    const content = gen.generate(domain({ entityName: 'Order', actions: [streamingAction] }), CTX)!.content;

    expect(content).toContain('new AbortController()');
    expect(content).toContain('return () => controller.abort();');
  });

  it('does not leak any text/event-stream literal (Core owns the wire)', () => {
    const content = gen.generate(domain({ entityName: 'Order', actions: [streamingAction] }), CTX)!.content;
    expect(content).not.toContain('text/event-stream');
  });
});

// ---------- named-event handling (ADR-044 obligation 2) ----------

describe('ActionStreamClientGenerator — named SSE event handling', () => {
  const gen = new ActionStreamClientGenerator();

  it('parses the event: line and exposes the named streamEventType (no onmessage named-drop)', () => {
    const content = gen.generate(domain({ entityName: 'Order', actions: [streamingAction] }), CTX)!.content;

    // The hand-rolled parser reads event:, so named frames are delivered.
    expect(content).toContain("if (line.startsWith('event:'))");
    expect(content).toContain("STREAM_EVENT_TYPE = 'ShipmentMoved'");
    // Honesty note: named events are delivered, not silently dropped.
    expect(content).toContain('NAMED frames are delivered');
    expect(content).not.toContain('source.onmessage');
  });

  it('defaults the event name to the action name when streamEventType is unset', () => {
    const d = domain({ entityName: 'Order', actions: [{ name: 'watchPrice', streaming: true }] });
    const content = gen.generate(d, CTX)!.content;
    expect(content).toContain("STREAM_EVENT_TYPE = 'watchPrice'");
  });

  it('emits one client class per streaming action, skipping non-streaming ones', () => {
    const d = domain({
      entityName: 'Order',
      actions: [plainAction, streamingAction, { name: 'watchPrice', streaming: true }],
    });
    const content = gen.generate(d, CTX)!.content;

    expect(content).toContain('export class OrderTrackShipmentStreamClient');
    expect(content).toContain('export class OrderWatchPriceStreamClient');
    expect(content).not.toContain('OrderCancelStreamClient');
  });
});

// ---------- determinism ----------

describe('ActionStreamClientGenerator — determinism', () => {
  const gen = new ActionStreamClientGenerator();

  it('same metadata → byte-identical output (no timestamps/UUIDs/random)', () => {
    const d = domain({ entityName: 'Order', actions: [streamingAction], path: '/orders' });
    const a = gen.generate(d, CTX)!.content;
    const b = gen.generate(d, CTX)!.content;
    expect(a).toBe(b);
    expect(a).not.toMatch(/\d{4}-\d{2}-\d{2}T/); // ISO timestamp
  });
});

// ---------- generateAggregate — barrel ----------

describe('ActionStreamClientGenerator.generateAggregate — barrel', () => {
  const gen = new ActionStreamClientGenerator();

  it('barrels only entities with streaming actions', () => {
    const files = gen.generateAggregate([
      domain({ entityName: 'Order', actions: [streamingAction] }),
      domain({ entityName: 'Invoice', actions: [plainAction] }),
    ], CTX);

    expect(files).toHaveLength(1);
    expect(files[0].path).toBe('services/action-streams.index.ts');
    expect(files[0].content).toContain("export * from './order.action-streams';");
    expect(files[0].content).not.toContain('invoice.action-streams');
  });

  it('emits nothing when no entity has a streaming action', () => {
    const files = gen.generateAggregate([domain({ entityName: 'Order', actions: [plainAction] })], CTX);
    expect(files).toHaveLength(0);
  });
});

// ---------- convenience function ----------

describe('generateActionStreamClient — top-level convenience function', () => {
  it('routes through ActionStreamClientGenerator and returns the per-domain file', () => {
    const file = generateActionStreamClient(
      domain({ entityName: 'Order', actions: [streamingAction] }),
      CTX.config,
    );

    expect(file).not.toBeNull();
    expect(file!.path).toBe('services/order.action-streams.ts');
    expect(file!.content).toContain('export class OrderTrackShipmentStreamClient');
  });

  it('returns null for an entity with no streaming action', () => {
    expect(
      generateActionStreamClient(domain({ entityName: 'Order', actions: [plainAction] }), CTX.config),
    ).toBeNull();
  });
});
