/**
 * Coverage for src/generators/angular/stream-client-gen.ts — StreamClientGenerator
 * emits a native EventSource client per @ExerisDomain(realTimeApi) entity, hitting
 * the SAME {base}/stream route the kernel KernelStreamHandlerGenerator registers
 * via streamRoute(...) (ADR-043 Slice 1 Java/TS parity).
 */

import { describe, expect, it } from 'vitest';
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

  it('returns null for a hidden internal API even when realTimeApi is true', () => {
    const d = domain({
      entityName: 'Order',
      realTimeApi: true,
      internalApi: { hidden: true, readOnly: false, internal: false },
    });
    expect(gen.generate(d, CTX)).toBeNull();
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

    // CTX apiBasePath default is '/api'; path '/orders' → '/api/orders/stream'.
    expect(content).toContain(`private readonly streamUrl = '/api/orders/stream';`);
  });

  it('uses a native EventSource with withCredentials (GET-only, cookie auth)', () => {
    const content = gen.generate(domain({ entityName: 'Order', realTimeApi: true }), CTX)!.content;

    expect(content).toContain('new EventSource(this.streamUrl, { withCredentials: true })');
    expect(content).toContain('stream(): Observable<MessageEvent>');
    expect(content).toContain('return () => source.close();');
    expect(content).toContain('export class OrderStreamClient');
  });

  it('does not leak any text/event-stream literal or chunk framing (Core owns the wire)', () => {
    const content = gen.generate(domain({ entityName: 'Order', realTimeApi: true }), CTX)!.content;
    expect(content).not.toContain('text/event-stream');
  });

  it('documents the native-EventSource named-event limitation (onmessage = unnamed only)', () => {
    // Honesty pin: onmessage drops named SSE frames (the scaffold's keep-alive,
    // and future EV1 domain events). The emitted code must say so, so readers
    // know named events need addEventListener(type, ...) — tracked for Slice 2/EV1.
    const content = gen.generate(domain({ entityName: 'Order', realTimeApi: true }), CTX)!.content;
    expect(content).toContain('onmessage fires only for unnamed SSE events');
    expect(content).toContain('addEventListener');
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
