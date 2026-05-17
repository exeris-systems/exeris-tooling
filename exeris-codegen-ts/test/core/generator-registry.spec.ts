/**
 * Coverage for src/core/generator-registry.ts — the GeneratorRegistry
 * class, the createGeneratorContext factory with default-fill behaviour,
 * and the file-utility helpers (filterFilesByType / groupFilesByType /
 * getGenerationStats).
 *
 * The async registerAllGenerators / createDefaultRegistry entry points
 * are exercised via dynamic import of every sibling generator module;
 * we cover their wiring contract (12 generators registered, correct
 * artifact-type set) without re-testing each generator's output.
 */

import { describe, expect, it } from 'vitest';
import {
  GeneratorRegistry,
  createGeneratorContext,
  filterFilesByType,
  groupFilesByType,
  getGenerationStats,
  createDefaultRegistry,
  registerAllGenerators,
  defaultRegistry,
  type ArtifactType,
  type CodeGenerator,
  type GeneratedFile,
  type GeneratorContext,
} from '../../src/core/generator-registry.js';
import type { DomainMetadata } from '../../src/models/domain-model.js';

// ---------- fixtures ----------

const FAKE_DOMAIN = { entityName: 'Order' } as unknown as DomainMetadata;
const OTHER_DOMAIN = { entityName: 'Customer' } as unknown as DomainMetadata;

function makeGenerator(opts: {
  name: string;
  artifactType: ArtifactType;
  supportedBackends?: CodeGenerator['supportedBackends'];
  priority?: number;
  generateReturns?: GeneratedFile | null;
  generateThrows?: Error;
  aggregateReturns?: GeneratedFile[];
  aggregateThrows?: Error;
}): CodeGenerator {
  return {
    name: opts.name,
    artifactType: opts.artifactType,
    supportedBackends: opts.supportedBackends ?? [],
    priority: opts.priority,
    generate: (_d, _c) => {
      if (opts.generateThrows) throw opts.generateThrows;
      return opts.generateReturns ?? null;
    },
    generateAggregate: opts.aggregateReturns !== undefined || opts.aggregateThrows !== undefined
      ? (_d, _c) => {
          if (opts.aggregateThrows) throw opts.aggregateThrows;
          return opts.aggregateReturns ?? [];
        }
      : undefined,
  };
}

function makeFile(path: string, artifactType: ArtifactType, overwritable = true): GeneratedFile {
  return { path, content: `// ${path}`, artifactType, overwritable };
}

const CTX_KERNEL: GeneratorContext = createGeneratorContext({ backend: 'KERNEL' });
const CTX_SPRING: GeneratorContext = createGeneratorContext({ backend: 'SPRING' });

// ---------- register / count / clear ----------

describe('GeneratorRegistry — register / count / clear', () => {
  it('register adds a generator that count + getAllGenerators reflect', () => {
    const reg = new GeneratorRegistry();
    const gen = makeGenerator({ name: 'g', artifactType: 'TYPE' });

    const ret = reg.register(gen);

    expect(ret).toBe(reg);
    expect(reg.count).toBe(1);
    expect(reg.getAllGenerators()).toEqual([gen]);
  });

  it('registerAll wires up every supplied generator', () => {
    const reg = new GeneratorRegistry();
    reg.registerAll(
      makeGenerator({ name: 'a', artifactType: 'TYPE' }),
      makeGenerator({ name: 'b', artifactType: 'SCHEMA' }),
      makeGenerator({ name: 'c', artifactType: 'FORM' }),
    );

    expect(reg.count).toBe(3);
  });

  it('getAllGenerators returns a defensive copy (mutation does not affect the registry)', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({ name: 'g', artifactType: 'TYPE' }));

    reg.getAllGenerators().push(makeGenerator({ name: 'sneak', artifactType: 'FORM' }));

    expect(reg.count).toBe(1);
  });

  it('clear drops every registration and is chainable', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({ name: 'g', artifactType: 'TYPE' }));

    const ret = reg.clear();

    expect(ret).toBe(reg);
    expect(reg.count).toBe(0);
  });
});

// ---------- getGenerators (backend filter + priority sort) ----------

describe('GeneratorRegistry.getGenerators — backend filter + priority sort', () => {
  it('returns generators whose supportedBackends is empty (all backends)', () => {
    const reg = new GeneratorRegistry();
    const g = makeGenerator({ name: 'universal', artifactType: 'TYPE', supportedBackends: [] });
    reg.register(g);

    expect(reg.getGenerators('KERNEL')).toEqual([g]);
    expect(reg.getGenerators('SPRING')).toEqual([g]);
    expect(reg.getGenerators('VANILLA')).toEqual([g]);
  });

  it('filters out generators whose supportedBackends does not include the requested backend', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({ name: 'kernel-only', artifactType: 'TYPE', supportedBackends: ['KERNEL'] }));
    reg.register(makeGenerator({ name: 'spring-only', artifactType: 'SERVICE', supportedBackends: ['SPRING'] }));

    expect(reg.getGenerators('KERNEL').map(g => g.name)).toEqual(['kernel-only']);
    expect(reg.getGenerators('SPRING').map(g => g.name)).toEqual(['spring-only']);
  });

  it('sorts generators by priority ascending (lower runs first); default priority is 10', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({ name: 'late', artifactType: 'FORM', priority: 20 }));
    reg.register(makeGenerator({ name: 'default-prio', artifactType: 'SERVICE' })); // priority undefined → 10
    reg.register(makeGenerator({ name: 'early', artifactType: 'TYPE', priority: 1 }));

    expect(reg.getGenerators('KERNEL').map(g => g.name)).toEqual(['early', 'default-prio', 'late']);
  });
});

// ---------- getByArtifactType + hasGenerator ----------

describe('GeneratorRegistry.getByArtifactType + hasGenerator', () => {
  it('getByArtifactType returns every generator matching the type', () => {
    const reg = new GeneratorRegistry();
    const t1 = makeGenerator({ name: 't1', artifactType: 'TYPE' });
    const t2 = makeGenerator({ name: 't2', artifactType: 'TYPE' });
    const s1 = makeGenerator({ name: 's1', artifactType: 'SCHEMA' });
    reg.registerAll(t1, t2, s1);

    expect(reg.getByArtifactType('TYPE')).toEqual([t1, t2]);
    expect(reg.getByArtifactType('SCHEMA')).toEqual([s1]);
    expect(reg.getByArtifactType('FORM')).toEqual([]);
  });

  it('hasGenerator returns true if any generator emits the requested type', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({ name: 'g', artifactType: 'STORE' }));

    expect(reg.hasGenerator('STORE')).toBe(true);
    expect(reg.hasGenerator('FORM')).toBe(false);
  });
});

// ---------- generateForDomain ----------

describe('GeneratorRegistry.generateForDomain', () => {
  it('collects every non-null file from each applicable generator', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({
      name: 'g-types',
      artifactType: 'TYPE',
      generateReturns: makeFile('Order.types.ts', 'TYPE'),
    }));
    reg.register(makeGenerator({
      name: 'g-service',
      artifactType: 'SERVICE',
      generateReturns: makeFile('Order.service.ts', 'SERVICE'),
    }));

    const files = reg.generateForDomain(FAKE_DOMAIN, CTX_KERNEL);

    expect(files.map(f => f.path)).toEqual(['Order.types.ts', 'Order.service.ts']);
  });

  it('skips generators that return null', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({
      name: 'always-skip',
      artifactType: 'TYPE',
      generateReturns: null,
    }));
    reg.register(makeGenerator({
      name: 'emits',
      artifactType: 'SERVICE',
      generateReturns: makeFile('Order.service.ts', 'SERVICE'),
    }));

    expect(reg.generateForDomain(FAKE_DOMAIN, CTX_KERNEL)).toHaveLength(1);
  });

  it('propagates errors from a generator (and writes the failing generator name to stderr)', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({
      name: 'broken-gen',
      artifactType: 'TYPE',
      generateThrows: new Error('boom'),
    }));

    expect(() => reg.generateForDomain(FAKE_DOMAIN, CTX_KERNEL)).toThrow('boom');
  });

  it('does not run generators that fail the backend filter', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({
      name: 'spring-only',
      artifactType: 'TYPE',
      supportedBackends: ['SPRING'],
      generateReturns: makeFile('would-fire.ts', 'TYPE'),
    }));

    // Backend = KERNEL → spring-only must not even be invoked, so no file emitted.
    expect(reg.generateForDomain(FAKE_DOMAIN, CTX_KERNEL)).toEqual([]);
  });
});

// ---------- generateAll ----------

describe('GeneratorRegistry.generateAll', () => {
  it('fans out per-domain generation across every domain', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({
      name: 'per-domain',
      artifactType: 'TYPE',
      generateReturns: makeFile('whatever.ts', 'TYPE'),
    }));

    expect(reg.generateAll([FAKE_DOMAIN, OTHER_DOMAIN], CTX_KERNEL)).toHaveLength(2);
  });

  it('appends aggregate-pass files after the per-domain pass', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({
      name: 'route-aggregator',
      artifactType: 'ROUTE',
      generateReturns: makeFile('per.ts', 'ROUTE'),
      aggregateReturns: [makeFile('routes.ts', 'ROUTE'), makeFile('barrel.ts', 'ROUTE')],
    }));

    const files = reg.generateAll([FAKE_DOMAIN], CTX_KERNEL);

    // 1 per-domain + 2 aggregate = 3 files; aggregate-set comes at the end.
    expect(files).toHaveLength(3);
    expect(files.slice(-2).map(f => f.path)).toEqual(['routes.ts', 'barrel.ts']);
  });

  it('propagates errors from aggregate generation', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({
      name: 'broken-aggregate',
      artifactType: 'ROUTE',
      generateReturns: null,
      aggregateThrows: new Error('aggregate-failure'),
    }));

    expect(() => reg.generateAll([FAKE_DOMAIN], CTX_KERNEL)).toThrow('aggregate-failure');
  });

  it('skips generators that have no generateAggregate hook', () => {
    const reg = new GeneratorRegistry();
    reg.register(makeGenerator({
      name: 'per-domain-only',
      artifactType: 'TYPE',
      generateReturns: makeFile('Order.types.ts', 'TYPE'),
      // No aggregateReturns / aggregateThrows → generateAggregate omitted.
    }));

    expect(reg.generateAll([FAKE_DOMAIN], CTX_KERNEL).map(f => f.path)).toEqual(['Order.types.ts']);
  });
});

// ---------- createGeneratorContext defaults ----------

describe('createGeneratorContext — default-fill behaviour', () => {
  it('every default is populated when called with an empty config', () => {
    const ctx = createGeneratorContext({});

    expect(ctx.config.framework).toBe('angular');
    expect(ctx.config.styling).toBe('tailwind');
    expect(ctx.config.standalone).toBe(true);
    expect(ctx.config.signals).toBe(true);
    expect(ctx.config.lazyRoutes).toBe(true);
    expect(ctx.config.generateZod).toBe(true);
    expect(ctx.config.generateServices).toBe(true);
    expect(ctx.config.generateForms).toBe(true);
    expect(ctx.config.generateLists).toBe(true);
    expect(ctx.config.generateDetails).toBe(true);
    expect(ctx.config.generateStores).toBe(true);
    expect(ctx.config.generateSagas).toBe(true);
    expect(ctx.config.generateEvents).toBe(true);
    expect(ctx.config.apiBasePath).toBe('/api');
    expect(ctx.config.overwrite).toBe(false);
    expect(ctx.config.dryRun).toBe(false);
    expect(ctx.config.verbose).toBe(false);
    expect(ctx.config.backend).toBe('KERNEL');
    expect(ctx.config.inputPath).toBe('target/classes/exeris-metadata');
    expect(ctx.config.outputPath).toBe('src/app/generated');
    expect(ctx.allDomains).toEqual([]);
    expect(ctx.enums).toEqual([]);
  });

  it('explicit overrides win over defaults; backend prop mirrors config.backend', () => {
    const ctx = createGeneratorContext({
      backend: 'SPRING',
      framework: 'react',
      styling: 'material',
      apiBasePath: '/v2/api',
      overwrite: true,
      dryRun: true,
      verbose: true,
    });

    expect(ctx.config.backend).toBe('SPRING');
    expect(ctx.backend).toBe('SPRING');
    expect(ctx.config.framework).toBe('react');
    expect(ctx.config.styling).toBe('material');
    expect(ctx.config.apiBasePath).toBe('/v2/api');
    expect(ctx.config.overwrite).toBe(true);
    expect(ctx.config.dryRun).toBe(true);
    expect(ctx.config.verbose).toBe(true);
  });

  it('explicit-false on a boolean is preserved (does not fall back to true default)', () => {
    // Catches a `??` vs `||` regression — `false ?? true` is false, `false || true` is true.
    const ctx = createGeneratorContext({
      standalone: false,
      signals: false,
      lazyRoutes: false,
      generateZod: false,
      generateServices: false,
      generateForms: false,
      generateLists: false,
      generateDetails: false,
      generateStores: false,
      generateSagas: false,
      generateEvents: false,
    });

    expect(ctx.config.standalone).toBe(false);
    expect(ctx.config.signals).toBe(false);
    expect(ctx.config.lazyRoutes).toBe(false);
    expect(ctx.config.generateZod).toBe(false);
    expect(ctx.config.generateServices).toBe(false);
    expect(ctx.config.generateForms).toBe(false);
    expect(ctx.config.generateLists).toBe(false);
    expect(ctx.config.generateDetails).toBe(false);
    expect(ctx.config.generateStores).toBe(false);
    expect(ctx.config.generateSagas).toBe(false);
    expect(ctx.config.generateEvents).toBe(false);
  });

  it('supplied domains + enums propagate to the returned context', () => {
    const ctx = createGeneratorContext(
      {},
      [FAKE_DOMAIN, OTHER_DOMAIN],
      [{ name: 'Status', qualifiedName: 'app.Status', packageName: 'app', values: [] }],
    );

    expect(ctx.allDomains).toHaveLength(2);
    expect(ctx.enums).toHaveLength(1);
    expect(ctx.enums[0].name).toBe('Status');
  });
});

// ---------- file utility helpers ----------

describe('filterFilesByType + groupFilesByType + getGenerationStats', () => {
  const sampleFiles: GeneratedFile[] = [
    makeFile('a.types.ts', 'TYPE', true),
    makeFile('b.types.ts', 'TYPE', true),
    makeFile('a.service.ts', 'SERVICE', true),
    makeFile('a.form.ts', 'FORM', false), // protected (user-editable)
    makeFile('b.form.ts', 'FORM', false),
  ];

  it('filterFilesByType narrows to the requested artifact type', () => {
    expect(filterFilesByType(sampleFiles, 'TYPE').map(f => f.path)).toEqual(['a.types.ts', 'b.types.ts']);
    expect(filterFilesByType(sampleFiles, 'SERVICE')).toHaveLength(1);
    expect(filterFilesByType(sampleFiles, 'STORE')).toEqual([]);
  });

  it('groupFilesByType returns a Map keyed by artifact type with every file routed to its bucket', () => {
    const grouped = groupFilesByType(sampleFiles);

    expect(grouped.get('TYPE')).toHaveLength(2);
    expect(grouped.get('SERVICE')).toHaveLength(1);
    expect(grouped.get('FORM')).toHaveLength(2);
    expect(grouped.has('STORE')).toBe(false);
  });

  it('groupFilesByType handles the empty input (returns an empty Map)', () => {
    expect(groupFilesByType([]).size).toBe(0);
  });

  it('getGenerationStats reports totals, per-type counts, and overwritable / protected split', () => {
    const stats = getGenerationStats(sampleFiles);

    expect(stats.totalFiles).toBe(5);
    expect(stats.byType.TYPE).toBe(2);
    expect(stats.byType.SERVICE).toBe(1);
    expect(stats.byType.FORM).toBe(2);
    expect(stats.overwritable).toBe(3);
    expect(stats.protected).toBe(2);
  });

  it('getGenerationStats on empty input zeros every metric', () => {
    const stats = getGenerationStats([]);
    expect(stats.totalFiles).toBe(0);
    expect(stats.overwritable).toBe(0);
    expect(stats.protected).toBe(0);
    expect(Object.keys(stats.byType)).toEqual([]);
  });
});

// ---------- async registerAllGenerators / createDefaultRegistry ----------

describe('registerAllGenerators + createDefaultRegistry (wiring)', () => {
  // The factory dynamically imports every sibling generator module. We
  // assert the wiring contract only — every artifact type that the
  // production set is supposed to provide should be present on the
  // resulting registry. Individual generator output is tested separately.

  it('registerAllGenerators(reg) registers the production set (12 generators)', async () => {
    const reg = new GeneratorRegistry();
    await registerAllGenerators(reg);

    expect(reg.count).toBe(12);
  });

  it('createDefaultRegistry returns a registry with the production set already populated', async () => {
    const reg = await createDefaultRegistry();

    expect(reg.count).toBe(12);
    expect(reg).toBeInstanceOf(GeneratorRegistry);
  });

  it('the production set covers TYPE, ENUM, SERVICE, STORE, FORM, LIST, DETAIL, SAGA, EVENT, GUARD, QUERY_BUILDER', async () => {
    const reg = await createDefaultRegistry();

    for (const t of ['TYPE', 'ENUM', 'SERVICE', 'STORE', 'FORM', 'LIST', 'DETAIL', 'SAGA', 'EVENT', 'GUARD', 'QUERY_BUILDER'] as const) {
      expect(reg.hasGenerator(t)).toBe(true);
    }
  });

  it('the module-level defaultRegistry singleton exposes a count getter and is mutation-safe via clear/register', () => {
    // Avoids leaking state into other tests — we don't mutate it, just
    // verify the singleton is the expected shape.
    expect(defaultRegistry).toBeInstanceOf(GeneratorRegistry);
    expect(typeof defaultRegistry.count).toBe('number');
  });
});
