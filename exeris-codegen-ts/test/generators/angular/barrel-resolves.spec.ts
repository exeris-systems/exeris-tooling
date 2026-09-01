/**
 * The app barrel must never name a file no generator emitted.
 *
 * `src/app/index.ts` re-exports the whole generated surface, while the orchestrator gates each
 * shape on its own config flag. The barrel used to export unconditionally, so turning any flag
 * off produced `export ... from './x'` against a file that was never written — `ng build`
 * `TS2307`. Measured on a one-entity project before the fix: `--no-forms`, `--no-lists`,
 * `--no-services`, `--no-zod` and `--no-details` each left one dangling export; `--no-events`
 * left three.
 *
 * This asserts the INVARIANT rather than the six known shapes, so a section added later is
 * covered without anyone remembering to extend a list.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG, type GeneratorConfig } from '../../../src/config.js';
import { DomainMetadataSchema } from '../../../src/models/domain-model.js';

const order = DomainMetadataSchema.parse({
  packageName: 'com.shop',
  entityName: 'Order',
  fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'total', type: 'java.math.BigDecimal' }],
  events: [{ name: 'OrderPlaced', payloadFields: ['id', 'total'] }],
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

const FLAGS = [
  'generateZod',
  'generateServices',
  'generateForms',
  'generateLists',
  'generateDetails',
  'generateStores',
  'generateEvents',
] as const;

describe('app barrel resolves', () => {
  it('names only emitted files with every generator on', () => {
    expect(danglingSpecifiers(DEFAULT_CONFIG)).toEqual([]);
  });

  it.each(FLAGS)('names only emitted files with %s off', (flag) => {
    expect(danglingSpecifiers({ ...DEFAULT_CONFIG, [flag]: false })).toEqual([]);
  });

  it('names only emitted files with every optional generator off', () => {
    const allOff = FLAGS.reduce<GeneratorConfig>(
      (config, flag) => ({ ...config, [flag]: false }),
      DEFAULT_CONFIG,
    );
    expect(danglingSpecifiers(allOff)).toEqual([]);
  });

  // The barrel is the consumer's entry point, so a flag being ON must actually put the surface
  // there — the opposite failure from a dangling export, and the one the events slice fixed.
  it('exports the event surface when the flag is on, and drops it when off', () => {
    const on = buildGeneratedFiles([order], [], DEFAULT_CONFIG)
      .find((f) => f.path === 'src/app/index.ts')?.content ?? '';
    const off = buildGeneratedFiles([order], [], { ...DEFAULT_CONFIG, generateEvents: false })
      .find((f) => f.path === 'src/app/index.ts')?.content ?? '';

    expect(on).toContain("export { EventBusService } from './events/event-bus.service';");
    expect(off).not.toContain('events/');
  });
});
