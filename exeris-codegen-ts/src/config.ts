/**
 * Configuration module for Exeris TypeScript Code Generator.
 *
 * Loads configuration from CLI arguments, environment variables, or config file.
 *
 * @author Exeris Team
 * @since 0.2.0
 */

import { existsSync, readFileSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { z } from 'zod';
import { parsePeerRef } from './peers/peer-contract.js';

// ============================================================================
// Configuration Schema
// ============================================================================

export const GeneratorConfigSchema = z.object({
  /** Input path for metadata JSON files (from exeris-processor) */
  inputPath: z.string().default('target/classes/exeris-metadata'),

  /** Output directory for generated code */
  outputPath: z.string().default('src/app/generated'),

  /** Target framework */
  framework: z.enum(['angular', 'react', 'vue']).default('angular'),

  /** Style system */
  styling: z.enum(['tailwind', 'material', 'bootstrap', 'none']).default('tailwind'),

  /** Generate standalone components (Angular 22+) */
  standalone: z.boolean().default(true),

  /** Use Angular Signals */
  signals: z.boolean().default(true),

  /** Generate lazy-loaded routes */
  lazyRoutes: z.boolean().default(true),

  /** Generate Zod schemas for runtime validation */
  generateZod: z.boolean().default(true),

  /** Generate service layer */
  generateServices: z.boolean().default(true),

  /** Generate form components */
  generateForms: z.boolean().default(true),

  /** Generate list components */
  generateLists: z.boolean().default(true),

  /** Generate detail/view components */
  generateDetails: z.boolean().default(true),

  /** Generate Signal stores */
  generateStores: z.boolean().default(true),

  /** Generate Saga UI state machines */
  generateSagas: z.boolean().default(true),

  /** Generate Event handlers */
  generateEvents: z.boolean().default(true),

  /** Prefix in front of every generated service URL.
   *
   *  Defaults to '' so the emitted client requests exactly what the emitted server serves:
   *  KernelApplicationGenerator registers routes at the entity's path and the OpenAPI document
   *  publishes the same, with no prefix. This defaulted to '/api' — combined with the apiVersion
   *  segment below, every generated Angular service requested /api/v1/<path> and 404'd against the
   *  router it was generated alongside. The knob stays for deployments that really do sit behind a
   *  gateway at /api; the default no longer assumes one. */
  apiBasePath: z.string().default(''),

  /** Human-readable application name — drives the emitted app title,
   *  route titles, sidebar logo text, and scaffold package.json name.
   *  (T7/U5: was the hardcoded 'Exeris Foundation' in app-structure-gen.) */
  appName: z.string().default('Exeris Foundation'),

  /** Backend strategy — kernel-target-only (single supported target) */
  backend: z.enum(['KERNEL']).default('KERNEL'),

  /** Whether to overwrite existing files */
  overwrite: z.boolean().default(false),

  /** Dry run - show what would be generated without writing files */
  dryRun: z.boolean().default(false),

  /** Verbose output */
  verbose: z.boolean().default(false),

  /** Emit specs for the generated surface, and the runner that executes them (T2, ADR-058).
   *
   *  **Opt-in, defaulting to false** — the TS counterpart of the Java half's `-Dexeris.tests=true`.
   *  It gates more than the spec files: turning it on also adds a `test` target, a
   *  `tsconfig.spec.json`, and the `vitest` + `jsdom` devDependencies the runner needs. Tooling
   *  emits no dependency the consumer did not ask for, so asking is what this flag is. With it off,
   *  emitted output is byte-identical to an app generated before the slice existed. */
  generateTests: z.boolean().default(false),

  /** Peer contracts this app imports types from (T42, ADR-048).
   *
   *  Each entry names a peer and points at its contract artifact — the peer's
   *  `cap-manifest.json` plus the metadata of the entities it provides. The NAME is
   *  declared here, by the consumer, not read from the artifact: nothing in the emitted
   *  artefacts carries an application identity, and the name lands in this app's own import
   *  paths, where it has to stay stable across whatever the producer later renames itself
   *  to. Peers in one build supply the same directory shape from a local path — the
   *  degenerate same-build case, not a second input model. */
  peers: z.array(z.object({ name: z.string(), path: z.string() })).default([]),

});

export type GeneratorConfig = z.infer<typeof GeneratorConfigSchema>;

// ============================================================================
// Default Configuration
// ============================================================================

export const DEFAULT_CONFIG: GeneratorConfig = {
  inputPath: 'target/classes/exeris-metadata',
  outputPath: 'src/app/generated',
  framework: 'angular',
  styling: 'tailwind',
  standalone: true,
  signals: true,
  lazyRoutes: true,
  generateZod: true,
  generateServices: true,
  generateForms: true,
  generateLists: true,
  generateDetails: true,
  generateStores: true,
  generateSagas: true,
  generateEvents: true,
  apiBasePath: '',
  appName: 'Exeris Foundation',
  backend: 'KERNEL',
  overwrite: false,
  dryRun: false,
  verbose: false,
  peers: [],
  generateTests: false,
};

/**
 * Turns parsed CLI options into config overrides, keeping only the flags the user actually
 * **passed**.
 *
 * `loadConfig` merges overrides with a spread, so a key that is always PRESENT always wins,
 * whatever its value. Commander fills every option that declares a default — and every option
 * here does, `--no-*` booleans included, where "absent" and "explicitly true" are the same value.
 * Building the override object unconditionally therefore made the whole of `exeris-codegen.json`
 * inert for these fields: whatever it declared was overwritten by a CLI default the user never
 * typed.
 *
 * That was not only a config-file bug. `apiBasePath` had drifted — `config.ts` deliberately
 * defaults it to `''` so the emitted client requests what the emitted server serves, while this
 * CLI still declared `'/api'` — and because the CLI default always won, every app generated
 * through `exeris-gen` shipped a frontend calling `/api/<path>` at a router serving `/<path>`.
 *
 * `wasPassed` is commander's `getOptionValueSource(key) === 'cli'`, injected rather than imported
 * so this stays unit-testable without building a `Command`. Anything not passed is omitted, not
 * set to `undefined`: the spread would still overwrite the file's value, and the schema default
 * would then fill it in.
 */
export function cliOverrides(
  options: Record<string, unknown>,
  wasPassed: (optionKey: string) => boolean,
): Partial<GeneratorConfig> {
  const overrides: Partial<GeneratorConfig> = {};

  /**
   * `read` is a thunk, not a value: a flag that was not passed must not have its value even
   * *computed*. Eagerly evaluating made `cliOverrides` throw on a malformed `--peer` reference
   * the user never typed — commander cannot produce that state today, which is precisely the kind
   * of "safe by accident" this helper should not rely on.
   *
   * Generic in the config key rather than `Record<string, unknown>` + a cast at the return, so a
   * mismatched optionKey/configKey pair is a compile error instead of something only the
   * "maps every option to its config key" test would catch.
   */
  const take = <K extends keyof GeneratorConfig>(
    optionKey: string,
    configKey: K,
    read: () => GeneratorConfig[K],
  ): void => {
    if (wasPassed(optionKey)) overrides[configKey] = read();
  };

  take('input', 'inputPath', () => options.input as string);
  take('output', 'outputPath', () => options.output as string);
  take('apiBase', 'apiBasePath', () => options.apiBase as string);
  take('appName', 'appName', () => options.appName as string);
  take('framework', 'framework', () => options.framework as GeneratorConfig['framework']);
  take('styling', 'styling', () => options.styling as GeneratorConfig['styling']);
  take('backend', 'backend', () => options.backend as GeneratorConfig['backend']);

  // `--no-x` reads back as `x === false`; the option key is the positive one.
  take('zod', 'generateZod', () => options.zod !== false);
  take('services', 'generateServices', () => options.services !== false);
  take('forms', 'generateForms', () => options.forms !== false);
  take('lists', 'generateLists', () => options.lists !== false);
  take('details', 'generateDetails', () => options.details !== false);
  take('tests', 'generateTests', () => options.tests === true);
  take('stores', 'generateStores', () => options.stores !== false);
  take('sagas', 'generateSagas', () => options.sagas !== false);
  take('events', 'generateEvents', () => options.events !== false);

  take('overwrite', 'overwrite', () => options.overwrite === true);
  take('dryRun', 'dryRun', () => options.dryRun === true);
  take('verbose', 'verbose', () => options.verbose === true);

  // Peers carry a parse step, and a malformed reference must fail the run rather than be dropped
  // — but only when the flag was actually passed, which is what the thunk above guarantees.
  take('peer', 'peers', () => ((options.peer as string[] | undefined) ?? []).map(parsePeerRef));

  return overrides;
}

// ============================================================================
// Configuration Loader
// ============================================================================

const CONFIG_FILE_NAMES = [
  'exeris-codegen.json',
  'exeris-codegen.config.json',
  '.exerisrc.json',
];

export function findConfigFile(startDir: string = process.cwd()): string | null {
  let currentDir = resolve(startDir);

  while (currentDir !== dirname(currentDir)) {
    for (const configName of CONFIG_FILE_NAMES) {
      const configPath = join(currentDir, configName);
      if (existsSync(configPath)) {
        return configPath;
      }
    }
    currentDir = dirname(currentDir);
  }

  return null;
}

export function loadConfigFile(configPath: string): Partial<GeneratorConfig> {
  const content = readFileSync(configPath, 'utf-8');
  return JSON.parse(content) as Partial<GeneratorConfig>;
}

export function loadConfig(overrides: Partial<GeneratorConfig> = {}): GeneratorConfig {
  // Start with defaults
  let config: Partial<GeneratorConfig> = { ...DEFAULT_CONFIG };

  // Try to load from config file
  const configFile = findConfigFile();
  if (configFile) {
    const fileConfig = loadConfigFile(configFile);
    config = { ...config, ...fileConfig };
  }

  // Apply CLI overrides
  config = { ...config, ...overrides };

  // Validate and return
  return GeneratorConfigSchema.parse(config);
}

// ============================================================================
// Path Resolution
// ============================================================================

export function resolveInputPath(config: GeneratorConfig): string {
  return resolve(process.cwd(), config.inputPath);
}

export function resolveOutputPath(config: GeneratorConfig): string {
  return resolve(process.cwd(), config.outputPath);
}


