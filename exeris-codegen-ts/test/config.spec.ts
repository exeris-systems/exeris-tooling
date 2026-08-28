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
import { join, resolve, sep, isAbsolute } from 'node:path';
import {
  GeneratorConfigSchema,
  DEFAULT_CONFIG,
  findConfigFile,
  loadConfigFile,
  loadConfig,
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

