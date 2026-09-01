/**
 * Coverage for src/config.ts — Zod-validated GeneratorConfig + the
 * three-layer loader (DEFAULT_CONFIG → optional file → CLI overrides)
 * and the path-resolver triplet.
 *
 * Branch points pinned:
 *   - Schema defaults: empty input parses into DEFAULT_CONFIG-equivalent
 *     shape (catches a future drift between the schema's `.default(...)`
 *     chain and the hand-written DEFAULT_CONFIG export).
 *   - Schema enum guards reject unknown framework / styling / backend.
 *   - findConfigFile walks up the directory tree, finds the FIRST name
 *     in CONFIG_FILE_NAMES at the FIRST matching ancestor, and returns
 *     null when the filesystem root is reached without a hit.
 *     We pin behaviour with an explicit startDir (the default uses
 *     process.cwd(), which is what loadConfig leans on — see below).
 *   - loadConfigFile is a thin JSON.parse wrapper; cover the happy path.
 *   - loadConfig: defaults-only when no config file is found AND no
 *     overrides; file values override defaults; overrides override file.
 *     We control which file findConfigFile sees by chdir-ing into a temp
 *     dir whose chain has (or doesn't have) one of the recognised names.
 *   - resolveInputPath / resolveOutputPath: resolve against process.cwd().
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import {
  GeneratorConfigSchema,
  DEFAULT_CONFIG,
  findConfigFile,
  loadConfigFile,
  loadConfig,
  cliOverrides,
  resolveInputPath,
  resolveOutputPath,
  type GeneratorConfig,
} from '../src/config.js';

let tempRoot: string;
const originalCwd = process.cwd();

beforeEach(() => {
  tempRoot = mkdtempSync(join(tmpdir(), 'exeris-config-spec-'));
});

afterEach(() => {
  process.chdir(originalCwd);
  rmSync(tempRoot, { recursive: true, force: true });
});

// ---------- GeneratorConfigSchema ----------

describe('GeneratorConfigSchema', () => {
  it('parses an empty object into the same value DEFAULT_CONFIG declares', () => {
    const parsed = GeneratorConfigSchema.parse({});
    expect(parsed).toEqual(DEFAULT_CONFIG);
  });

  it('rejects an unknown framework value', () => {
    expect(() => GeneratorConfigSchema.parse({ framework: 'svelte' })).toThrow();
  });

  it('rejects an unknown styling value', () => {
    expect(() => GeneratorConfigSchema.parse({ styling: 'chakra' })).toThrow();
  });

  it('rejects an unknown backend value', () => {
    expect(() => GeneratorConfigSchema.parse({ backend: 'RAILS' })).toThrow();
  });

  it('rejects the removed non-kernel backends (kernel-target-only)', () => {
    // Spring/Quarkus/Micronaut/Vanilla strategies were removed; the schema
    // must now reject them so config cannot reintroduce a second target.
    expect(() => GeneratorConfigSchema.parse({ backend: 'SPRING' })).toThrow();
    expect(() => GeneratorConfigSchema.parse({ backend: 'VANILLA' })).toThrow();
  });

  it('round-trips overridden values', () => {
    const parsed = GeneratorConfigSchema.parse({
      backend: 'KERNEL',
      framework: 'angular',
      apiBasePath: '/v2/api',
    });
    expect(parsed.backend).toBe('KERNEL');
    expect(parsed.apiBasePath).toBe('/v2/api');
  });
});

// ---------- DEFAULT_CONFIG ----------

describe('DEFAULT_CONFIG', () => {
  it('declares a KERNEL+angular default with an unprefixed basePath', () => {
    expect(DEFAULT_CONFIG.backend).toBe('KERNEL');
    expect(DEFAULT_CONFIG.framework).toBe('angular');
    // '' rather than '/api': the emitted client must request what the emitted server serves, and
    // the router registers the entity path with no prefix. The knob remains for gateway deployments.
    expect(DEFAULT_CONFIG.apiBasePath).toBe('');
  });
});

// ---------- findConfigFile ----------

describe('findConfigFile', () => {
  it('finds a config file in the start directory', () => {
    const cfgPath = join(tempRoot, 'exeris-codegen.json');
    writeFileSync(cfgPath, '{}', 'utf-8');
    expect(findConfigFile(tempRoot)).toBe(cfgPath);
  });

  it('walks UP one level when the start dir has no config', () => {
    const child = join(tempRoot, 'nested', 'deeper');
    mkdirSync(child, { recursive: true });
    const cfgPath = join(tempRoot, 'exeris-codegen.config.json');
    writeFileSync(cfgPath, '{}', 'utf-8');
    expect(findConfigFile(child)).toBe(cfgPath);
  });

  it('honours CONFIG_FILE_NAMES priority order: exeris-codegen.json wins over .exerisrc.json in the same dir', () => {
    writeFileSync(join(tempRoot, '.exerisrc.json'), '{}', 'utf-8');
    writeFileSync(join(tempRoot, 'exeris-codegen.json'), '{}', 'utf-8');
    expect(findConfigFile(tempRoot)).toBe(join(tempRoot, 'exeris-codegen.json'));
  });

  it('returns null when no config file is found anywhere in the chain', () => {
    // tempRoot has no config file and its ancestors (under /tmp) won't
    // either in a clean CI environment. We can't fully guarantee the
    // walk reaches `/` without finding *some* file in a developer's
    // home, so we narrow the assertion: passing a directory whose
    // ancestor chain we own — and which is empty — should return null
    // unless the system tmpdir itself carries one (it does not in CI).
    expect(findConfigFile(tempRoot)).toBeNull();
  });

  it('defaults startDir to process.cwd() — verified by chdir into a dir with a config', () => {
    const cfgPath = join(tempRoot, '.exerisrc.json');
    writeFileSync(cfgPath, '{}', 'utf-8');
    process.chdir(tempRoot);
    expect(findConfigFile()).toBe(cfgPath);
  });
});

// ---------- loadConfigFile ----------

describe('loadConfigFile', () => {
  it('reads + JSON.parses a config file', () => {
    const cfgPath = join(tempRoot, 'exeris-codegen.json');
    writeFileSync(cfgPath, JSON.stringify({ backend: 'KERNEL', apiBasePath: '/svc' }), 'utf-8');
    const loaded = loadConfigFile(cfgPath);
    expect(loaded).toEqual({ backend: 'KERNEL', apiBasePath: '/svc' });
  });

  it('throws on malformed JSON (caller is responsible for surfacing)', () => {
    const cfgPath = join(tempRoot, 'exeris-codegen.json');
    writeFileSync(cfgPath, '{ this is not json', 'utf-8');
    expect(() => loadConfigFile(cfgPath)).toThrow();
  });
});

// ---------- loadConfig ----------

describe('loadConfig', () => {
  it('returns defaults when no file is found and no overrides are passed', () => {
    process.chdir(tempRoot);
    expect(loadConfig()).toEqual(DEFAULT_CONFIG);
  });

  it('layers file values OVER defaults', () => {
    writeFileSync(
      join(tempRoot, 'exeris-codegen.json'),
      JSON.stringify({ framework: 'react', apiBasePath: '/q' }),
      'utf-8',
    );
    process.chdir(tempRoot);
    const cfg = loadConfig();
    expect(cfg.framework).toBe('react');
    expect(cfg.apiBasePath).toBe('/q');
    expect(cfg.backend).toBe('KERNEL'); // default preserved
  });

  it('layers CLI overrides OVER file values (last-write-wins)', () => {
    writeFileSync(
      join(tempRoot, 'exeris-codegen.json'),
      JSON.stringify({ framework: 'react', apiBasePath: '/from-file' }),
      'utf-8',
    );
    process.chdir(tempRoot);
    const cfg = loadConfig({ apiBasePath: '/from-override' });
    expect(cfg.framework).toBe('react'); // from file
    expect(cfg.apiBasePath).toBe('/from-override'); // override wins
  });

  it('still validates after merging (bad override still throws)', () => {
    process.chdir(tempRoot);
    expect(() => loadConfig({ backend: 'COBOL' as unknown as GeneratorConfig['backend'] })).toThrow();
  });
});

// ---------- resolveInputPath / resolveOutputPath ----------

describe('resolveInputPath / resolveOutputPath', () => {
  it('resolveInputPath joins config.inputPath against process.cwd()', () => {
    process.chdir(tempRoot);
    const cfg: GeneratorConfig = { ...DEFAULT_CONFIG, inputPath: 'metadata/in' };
    expect(resolveInputPath(cfg)).toBe(resolve(tempRoot, 'metadata/in'));
  });

  it('resolveOutputPath joins config.outputPath against process.cwd()', () => {
    process.chdir(tempRoot);
    const cfg: GeneratorConfig = { ...DEFAULT_CONFIG, outputPath: 'gen/out' };
    expect(resolveOutputPath(cfg)).toBe(resolve(tempRoot, 'gen/out'));
  });

  it('treats an absolute inputPath as-is (resolve is idempotent on absolute)', () => {
    const abs = join(tempRoot, 'abs', 'in');
    const cfg: GeneratorConfig = { ...DEFAULT_CONFIG, inputPath: abs };
    expect(resolveInputPath(cfg)).toBe(abs);
  });
});


// ---------- cliOverrides + the config-file path ----------
//
// `loadConfig` merges overrides with a spread, so an override key that is always PRESENT always
// wins — regardless of its value. Commander fills every option that declares a default, so
// building the override object unconditionally made exeris-codegen.json inert for those fields,
// and took the deliberate apiBasePath='' fix down with it on the CLI path. These tests pin the
// rule rather than the symptom: the symptom only appears in a full CLI run, and src/index.ts is
// excluded from coverage, which is why it shipped.

/** Commander's getOptionValueSource(k) === 'cli', as an injectable predicate. */
const passed = (...keys: string[]) => (key: string) => keys.includes(key);
const nothingPassed = () => false;

describe('cliOverrides', () => {
  it('is empty when no flag was passed, whatever values commander filled in', () => {
    expect(cliOverrides(
      { input: 'target/classes/exeris-metadata', apiBase: '/api', appName: 'Exeris Foundation', zod: true, peer: [] },
      nothingPassed,
    )).toEqual({});
  });

  // The defect, in one assertion: a CLI default the user never typed must not reach the config.
  it('omits apiBasePath when --api-base was not typed, even though commander supplies a value', () => {
    const overrides = cliOverrides({ apiBase: '/api' }, nothingPassed);
    expect('apiBasePath' in overrides).toBe(false);
  });

  it('takes a flag that was typed', () => {
    expect(cliOverrides({ apiBase: '/gateway' }, passed('apiBase'))).toEqual({ apiBasePath: '/gateway' });
  });

  // `--no-x` reads back as x === false; absent reads back as true. Only the source tells them apart.
  it('distinguishes an absent --no-zod from a typed one', () => {
    expect(cliOverrides({ zod: true }, nothingPassed)).toEqual({});
    expect(cliOverrides({ zod: false }, passed('zod'))).toEqual({ generateZod: false });
  });

  it('maps every option to its config key', () => {
    const all = {
      input: 'i', output: 'o', apiBase: '/a', appName: 'n', framework: 'angular', styling: 'none',
      backend: 'KERNEL', zod: false, services: false, forms: false, lists: false, details: false,
      stores: false, sagas: false, events: false, overwrite: true, dryRun: true, verbose: true,
      peer: ['p=./p'],
    };
    expect(cliOverrides(all, () => true)).toEqual({
      inputPath: 'i', outputPath: 'o', apiBasePath: '/a', appName: 'n', framework: 'angular',
      styling: 'none', backend: 'KERNEL', generateZod: false, generateServices: false,
      generateForms: false, generateLists: false, generateDetails: false, generateStores: false,
      generateSagas: false, generateEvents: false, overwrite: true, dryRun: true, verbose: true,
      peers: [{ name: 'p', path: './p' }],
    });
  });

  // --no-details existed nowhere until the detail generator was wired: no .option(), no take().
  // Since commander does not allowUnknownOption, typing it was a hard error rather than a no-op.
  it('maps --no-details, like every other --no-* flag', () => {
    expect(cliOverrides({ details: false }, passed('details'))).toEqual({ generateDetails: false });
    expect(cliOverrides({ details: true }, nothingPassed)).toEqual({});
  });

  it('propagates a malformed peer reference rather than dropping it', () => {
    expect(() => cliOverrides({ peer: ['no-equals-sign'] }, passed('peer'))).toThrow(/<name>=<path>/);
  });

  // A flag that was not passed must not have its value even COMPUTED. This threw before the
  // value became a thunk — commander cannot hand back a malformed spec it was never given, so
  // the old code was safe only by accident, which is not a property worth relying on.
  it('does not evaluate the value of a flag that was not passed', () => {
    expect(() => cliOverrides({ peer: ['no-equals-sign'] }, nothingPassed)).not.toThrow();
    expect(cliOverrides({ peer: ['no-equals-sign'] }, nothingPassed)).toEqual({});
  });
});

describe('loadConfig — a config file survives untyped flags', () => {
  const writeConfig = (body: Record<string, unknown>) => {
    writeFileSync(join(tempRoot, 'exeris-codegen.json'), JSON.stringify(body));
    process.chdir(tempRoot);
  };

  it("keeps the file's apiBasePath when --api-base was not typed", () => {
    writeConfig({ apiBasePath: '/from-config' });
    expect(loadConfig(cliOverrides({ apiBase: '/api' }, nothingPassed)).apiBasePath).toBe('/from-config');
  });

  it("keeps the file's peers when --peer was not passed", () => {
    writeConfig({ peers: [{ name: 'billing', path: '../billing/contract' }] });
    expect(loadConfig(cliOverrides({ peer: [] }, nothingPassed)).peers)
      .toEqual([{ name: 'billing', path: '../billing/contract' }]);
  });

  it("keeps the file's generateZod:false when --no-zod was not typed", () => {
    writeConfig({ generateZod: false });
    expect(loadConfig(cliOverrides({ zod: true }, nothingPassed)).generateZod).toBe(false);
  });

  it('lets a typed flag replace the file value', () => {
    writeConfig({ apiBasePath: '/from-config', peers: [{ name: 'billing', path: './b' }] });
    const config = loadConfig(cliOverrides({ apiBase: '/typed', peer: ['shipping=./s'] }, passed('apiBase', 'peer')));
    expect(config.apiBasePath).toBe('/typed');
    expect(config.peers).toEqual([{ name: 'shipping', path: './s' }]);
  });

  // The default the CLI must NOT re-introduce: '' so the emitted client requests what the
  // emitted kernel router serves (#166). A CLI default of '/api' made every generated app 404.
  it('falls back to the schema default of an empty apiBasePath, never /api', () => {
    process.chdir(tempRoot);
    expect(loadConfig(cliOverrides({ apiBase: '/api' }, nothingPassed)).apiBasePath).toBe('');
  });
});
