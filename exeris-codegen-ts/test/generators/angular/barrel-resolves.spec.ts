/**
 * The app barrel must never name a file no generator emitted.
 *
 * `src/app/index.ts` re-exports the whole generated surface, while the orchestrator gates each
 * shape on its own config flag. Turning any flag off must not produce `export ... from './x'`
 * against a file that was never written — that would cause `ng build` TS2307.
 *
 * This asserts the INVARIANT rather than the six known shapes, so a section added later is
 * covered without anyone remembering to extend a list.
 *
 * The Stores section is emitted and gated like every other optional generator; if the flag is
 * off, the barrel must not reference it.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG, type GeneratorConfig } from '../../../src/config.js';
import { DomainMetadataSchema, ViewMetadataSchema, type ViewMetadata } from '../../../src/models/domain-model.js';

const order = DomainMetadataSchema.parse({
  packageName: 'com.shop',
  entityName: 'Order',
  fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'total', type: 'java.math.BigDecimal' }],
  events: [{ name: 'OrderPlaced', payloadFields: ['id', 'total'] }],
  // Declared so the `generateSagas` case below is not vacuous the way `generateStores` is: the
  // saga file is emitted per-entity only for an entity that has one.
  sagaMetadata: { name: 'OrderFulfilment', steps: [{ name: 'reserveStock' }] },
  // Both stream routes, so the stream-client sections are emitted and resolved too.
  realTimeApi: true,
  actions: [{ name: 'track', streaming: true }],
});

/** Every module specifier the barrel names, resolved against what was actually emitted. */
function danglingSpecifiers(config: GeneratorConfig): string[] {
  const files = buildGeneratedFiles([order], [], config);
  const emitted = new Set(files.map((f) => f.path));
  const barrel = files.find((f) => f.path === 'src/app/index.ts')?.content ?? '';
  expect(barrel).not.toBe('');

  return [...barrel.matchAll(/from '(\.\/[^']+)'/g)]
    .map((m) => m[1].replace(/^\.\//, ''))
    .filter((spec) => !emitted.has(`src/app/${spec}.ts`));
}

// The event surface needs a bus endpoint, so every case below runs with one.
const BASE_CONFIG: GeneratorConfig = { ...DEFAULT_CONFIG, eventBusEndpoint: '/orders/events' };

const FLAGS = [
  'generateZod',
  'generateServices',
  'generateForms',
  'generateLists',
  'generateDetails',
  'generateStores',
  'generateEvents',
  'generateSagas',
] as const;

describe('app barrel resolves', () => {
  it('names only emitted files with every generator on', () => {
    expect(danglingSpecifiers(BASE_CONFIG)).toEqual([]);
  });

  it.each(FLAGS)('names only emitted files with %s off', (flag) => {
    expect(danglingSpecifiers({ ...BASE_CONFIG, [flag]: false })).toEqual([]);
  });

  it('names only emitted files with every optional generator off', () => {
    const allOff = FLAGS.reduce<GeneratorConfig>(
      (config, flag) => ({ ...config, [flag]: false }),
      BASE_CONFIG,
    );
    expect(danglingSpecifiers(allOff)).toEqual([]);
  });

  // The barrel is the consumer's entry point, so a flag being ON must actually put the surface
  // there — the opposite failure from a dangling export, and the one the events slice fixed.
  it('exports the event surface when the flag is on, and drops it when off', () => {
    const on = buildGeneratedFiles([order], [], BASE_CONFIG)
      .find((f) => f.path === 'src/app/index.ts')?.content ?? '';
    const off = buildGeneratedFiles([order], [], { ...BASE_CONFIG, generateEvents: false })
      .find((f) => f.path === 'src/app/index.ts')?.content ?? '';

    expect(on).toContain("export { EventBusService } from './events/event-bus.service';");
    expect(off).not.toContain('events/');
  });

  it('exports the saga surface when the flag is on, and drops it when off', () => {
    const on = buildGeneratedFiles([order], [], BASE_CONFIG)
      .find((f) => f.path === 'src/app/index.ts')?.content ?? '';
    const off = buildGeneratedFiles([order], [], { ...BASE_CONFIG, generateSagas: false })
      .find((f) => f.path === 'src/app/index.ts')?.content ?? '';

    expect(on).toContain("export { OrderFulfilmentStateMachine } from './sagas/order.saga';");
    expect(off).not.toContain('sagas/');
  });

  // Two saga files each declare their own SagaState/SagaStep/SagaStatusSnapshot. `export *` from
  // both would make every shared name ambiguous — and TypeScript drops an ambiguous star export
  // silently, so nothing would fail; the names would just stop being reachable.
  it('names the shared saga types once, from one saga file, however many sagas there are', () => {
    const second = DomainMetadataSchema.parse({
      packageName: 'com.shop',
      entityName: 'Product',
      fields: [{ name: 'id', type: 'java.util.UUID' }],
      sagaMetadata: { name: 'ProductRestock', steps: [{ name: 'requestQuote' }] },
    });

    const barrel = buildGeneratedFiles([order, second], [], BASE_CONFIG)
      .find((f) => f.path === 'src/app/index.ts')?.content ?? '';

    expect(barrel).not.toContain("export * from './sagas/");
    expect(barrel).toContain("export { OrderFulfilmentStateMachine } from './sagas/order.saga';");
    expect(barrel).toContain("export { ProductRestockStateMachine } from './sagas/product.saga';");
    expect((barrel.match(/export type \{ SagaState,/g) ?? []).length).toBe(1);
  });
});

// With the scaffold off the tree is written at the output root and the shell that imported it is
// gone, so the invariant widens from the barrel to every emitted module: no relative specifier, in
// a static import, an export or a lazy `import()`, may name a file the run did not emit.
describe('scaffold-off tree resolves', () => {
  const unbound: ViewMetadata = ViewMetadataSchema.parse({
    name: 'Landing',
    route: '/',
    regions: [{ slot: 'main', components: [{ type: 'HERO', binding: { source: 'STATIC' }, props: 'Hi' }] }],
  });
  const bound: ViewMetadata = ViewMetadataSchema.parse({
    name: 'OrderBoard',
    regions: [{ slot: 'main', components: [{ type: 'LIST', binding: { source: 'ENTITY', ref: 'Order' } }] }],
  });

  /** Every relative specifier in every emitted .ts file that resolves to no emitted file. */
  function danglingRelativeImports(files: { path: string; content: string }[]): string[] {
    const emitted = new Set(files.map((f) => f.path));
    const dangling: string[] = [];
    for (const file of files.filter((f) => f.path.endsWith('.ts'))) {
      const dir = file.path.includes('/') ? file.path.slice(0, file.path.lastIndexOf('/')) : '';
      const specifiers = [
        ...file.content.matchAll(/from '(\.\.?\/[^']+)'/g),
        ...file.content.matchAll(/import\('(\.\.?\/[^']+)'\)/g),
      ].map((m) => m[1]);
      for (const spec of specifiers) {
        const segments = dir === '' ? [] : dir.split('/');
        for (const part of spec.split('/')) {
          if (part === '.') continue;
          if (part === '..') segments.pop();
          else segments.push(part);
        }
        const target = segments.join('/');
        if (!emitted.has(`${target}.ts`) && !emitted.has(`${target}/index.ts`)) {
          dangling.push(`${file.path} -> ${spec}`);
        }
      }
    }
    return dangling;
  }

  const scaffoldOff: GeneratorConfig = { ...BASE_CONFIG, scaffold: false };

  it('names only emitted files with every generator on', () => {
    expect(danglingRelativeImports(buildGeneratedFiles([order], [], scaffoldOff, [unbound, bound]))).toEqual([]);
  });

  // The form, list and detail components and the store inject the entity service, so
  // generateServices off on its own leaves their imports unresolved, scaffold on or off; it is
  // covered below together with the generators that depend on it.
  const independentFlags = FLAGS.filter((flag) => flag !== 'generateServices');

  it.each(independentFlags)('names only emitted files with %s off', (flag) => {
    const config = { ...scaffoldOff, [flag]: false };
    expect(danglingRelativeImports(buildGeneratedFiles([order], [], config, [unbound]))).toEqual([]);
  });

  it('names only emitted files for a views-only tree with every entity generator off', () => {
    const allOff = FLAGS.reduce<GeneratorConfig>((config, flag) => ({ ...config, [flag]: false }), scaffoldOff);
    expect(danglingRelativeImports(buildGeneratedFiles([], [], allOff, [unbound]))).toEqual([]);
    expect(danglingRelativeImports(buildGeneratedFiles([order], [], allOff, [unbound]))).toEqual([]);
  });

  it('keeps the barrel, at the tree root', () => {
    const files = buildGeneratedFiles([order], [], scaffoldOff, [unbound]);
    const barrel = files.find((f) => f.path === 'index.ts')?.content ?? '';
    expect(barrel).toContain("export * from './types/order.types';");
  });
});
