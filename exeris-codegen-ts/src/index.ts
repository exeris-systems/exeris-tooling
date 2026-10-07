#!/usr/bin/env node

/**
 * Exeris TypeScript Code Generator CLI
 *
 * Generates Angular 22+ frontend code from Exeris domain metadata.
 *
 * Usage:
 *   exeris-gen generate --input <path> --output <path>
 *   exeris-gen init [--views-only] [--app-name <name>]
 *   exeris-gen --help
 */

import { Command } from 'commander';
import pc from 'picocolors';
import { writeFileSync, existsSync, readFileSync } from 'node:fs';
import { join, basename, resolve } from 'node:path';
import { pruneOrphansAndWriteManifest, MANIFEST_NAME } from './output/manifest.js';
import { planWrites, writeGeneratedFiles, type WriteAction } from './output/writer.js';
import { loadConfig, cliOverrides, initConfig, type GeneratorConfig } from './config.js';
import { findMetadataFiles, loadMetadataFamilies } from './models/metadata-files.js';
import { loadPeerContracts, type PeerContract } from './peers/peer-contract.js';
import { buildGeneratedFiles } from './orchestrator.js';

import { getStrategy } from './core/backend-strategy.js';

// The package's own manifest is the single source of the CLI version. It sits one directory above
// both src/ (tsx) and dist/ (built), and npm always ships it, so the relative URL resolves in each.
const VERSION: string = (
  JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as { version: string }
).version;

// ============================================================================
// CLI Setup
// ============================================================================

const program = new Command();

program
  .name('exeris-gen')
  .description('Exeris Frontend Code Generator for Angular 22+')
  .version(VERSION);

// ============================================================================
// Generate Command
// ============================================================================

program
  .command('generate')
  .alias('gen')
  .description('Generate frontend code from domain metadata')
  .option('-i, --input <path>', 'Input path for metadata JSON files', 'target/classes/exeris-metadata')
  .option('-o, --output <path>', 'Output directory for generated code', 'src/app/generated')
  .option(
    '--api-base <path>',
    'Prefix in front of every generated service URL. Default empty: the emitted client '
      + 'requests exactly what the emitted kernel router serves.',
  )
  .option('--app-name <name>', 'Application name (app title, route titles, package name)', 'Exeris Foundation')
  .option('--framework <name>', 'Target framework (angular, react, vue)', 'angular')
  .option('--styling <name>', 'Style system (tailwind, material, bootstrap, none)', 'tailwind')
  .option('--backend <name>', 'Backend target (KERNEL — the only supported target)', 'KERNEL')
  .option('--no-zod', 'Skip Zod schema generation')
  .option('--no-services', 'Skip service generation')
  .option('--no-forms', 'Skip form component generation')
  .option('--no-lists', 'Skip list component generation')
  .option('--no-details', 'Skip detail component generation')
  .option(
    '--tests',
    'Emit specs for the generated surface plus the Vitest runner that executes them '
      + '(adds a test target, tsconfig.spec.json and the vitest + jsdom devDependencies)',
  )
  .option(
    '--no-scaffold',
    'Emit no Angular project or app-shell file: the generated tree is written at the output root, '
      + 'for an app that owns its own package.json, angular.json, app.config.ts and app.routes.ts',
  )
  .option('--no-stores', 'Skip Signal store generation')
  .option('--no-sagas', 'Skip Saga UI generation')
  .option('--no-events', 'Skip Event handler generation')
  .option(
    '--peer <name=path>',
    'Import a peer\'s DTOs: <name> is the name YOU give the peer (it becomes the import path), '
      + '<path> its contract artifact directory. Repeatable.',
    (value: string, previous: string[] = []) => [...previous, value],
    [] as string[],
  )
  .option(
    '--overwrite',
    'Also replace existing files no previous run generated, and the files written once for you to edit',
  )
  .option('--dry-run', 'Show what would be generated without writing files')
  .option('-v, --verbose', 'Verbose output')
  .action(async (options: Record<string, unknown>, command: Command) => {
    try {
      // Only the flags actually typed become overrides — see cliOverrides. A CLI default that
      // always wins silently disables exeris-codegen.json, and once disabled it took the
      // deliberate apiBasePath='' fix with it.
      const config = loadConfig(
        cliOverrides(options, (key) => command.getOptionValueSource(key) === 'cli'),
      );

      await runGenerate(config);
    } catch (error) {
      console.error(pc.red('Error:'), error instanceof Error ? error.message : error);
      process.exit(1);
    }
  });

// ============================================================================
// Init Command
// ============================================================================

program
  .command('init')
  .description('Initialize configuration file')
  .option('-f, --force', 'Overwrite existing config file')
  .option(
    '--views-only',
    'Preset for @View pages generated into an app you own: entity generators off, scaffold off',
  )
  .option('--app-name <name>', 'Application name to write into the config (either preset)')
  .action((options: Record<string, unknown>) => {
    const configPath = 'exeris-codegen.json';

    if (existsSync(configPath) && !options.force) {
      console.error(pc.yellow('Config file already exists. Use --force to overwrite.'));
      process.exit(1);
    }

    const config = initConfig({
      viewsOnly: options.viewsOnly === true,
      appName: typeof options.appName === 'string' ? options.appName : undefined,
    });
    writeFileSync(configPath, JSON.stringify(config, null, 2));
    console.log(pc.green('✓'), `Created ${configPath}`);
  });

// ============================================================================
// Generate Logic
// ============================================================================

const DRY_RUN_LABEL: Record<WriteAction, string> = {
  create: 'Would create:',
  rewrite: 'Would rewrite:',
  unchanged: 'Unchanged:',
  'keep-seed': 'Would keep (yours to edit):',
  'skip-unowned': 'Would skip (not generated here):',
};

async function runGenerate(config: GeneratorConfig): Promise<void> {
  const inputPath = resolve(process.cwd(), config.inputPath);
  const outputPath = resolve(process.cwd(), config.outputPath);

  // Get backend strategy info
  const strategy = getStrategy(config.backend);
  const strategyConfig = strategy.getClientConfig();

  console.log(pc.blue('Exeris Code Generator v' + VERSION));
  console.log(pc.dim('─'.repeat(50)));
  console.log(pc.dim('Input:'), inputPath);
  console.log(pc.dim('Output:'), outputPath);
  console.log(pc.dim('Framework:'), config.framework);
  console.log(pc.dim('Backend:'), config.backend, strategyConfig.useHttp3 ? '(HTTP/3)' : '');
  console.log(pc.dim('Styling:'), config.styling);
  console.log(pc.dim('─'.repeat(50)));

  // Peer contracts (T42, ADR-048), loaded BEFORE the empty-input return: a declared peer
  // must never be silently dropped, and an app whose whole domain is a peer's is a real
  // shape. Loading fails the run on a missing manifest or a below-floor schemaVersion — a
  // peer contract that cannot be verified is not a weaker contract, and emitting types from
  // it would say otherwise.
  const peers: PeerContract[] = loadPeerContracts(config.peers);
  if (peers.length > 0) {
    console.log(pc.green('Peers:'), peers.map((p) => `${p.name} (${p.domains.length} entity/ies)`).join(', '));
  }

  // Find metadata files
  const metadataFiles = findMetadataFiles(inputPath);

  if (metadataFiles.length === 0 && peers.length === 0) {
    console.error(pc.yellow('No metadata files found in'), inputPath);
    console.log(pc.dim('Make sure to run Maven compile first to generate metadata.'));
    // T13 (parity with the Java pipeline): "no entities" is a valid output
    // state — every @ExerisDomain was removed. If a previous run owned this
    // tree (a manifest is present), this run must own it too and prune the
    // orphans rather than leaving a stale tree behind. A never-generated dir
    // (no manifest) is left untouched. Skipped under --dry-run (no mutation).
    if (!config.dryRun && existsSync(join(outputPath, MANIFEST_NAME))) {
      const pruned = pruneOrphansAndWriteManifest(outputPath, []);
      if (pruned > 0) console.log(pc.yellow('Pruned:'), pruned, 'orphaned file(s)');
    }
    return;
  }

  console.log(pc.green('Found'), metadataFiles.length, 'metadata file(s)');

  // Family split + parse live in models/metadata-files.ts — the peer path (T42) reads a
  // peer's contract through the same loader, which is what makes a peer contract the same
  // input model as the local one rather than a second one (ADR-048 §1).
  const { domains, enums, views } = loadMetadataFamilies(metadataFiles, (family, file) => {
    if (!config.verbose) return;
    const label = family === 'domain' ? '  Loading:' : `  Loading ${family}:`;
    console.log(pc.dim(label), basename(file));
  });

  console.log(pc.green('Loaded'), domains.length, 'domain(s)', '+', enums.length, 'enum(s)', '+', views.length, 'view(s)');

  // Compose what to write (pure step — see orchestrator.ts). T20: per-entity
  // artefacts + the enum module are emitted by the real generators under src/app
  // (one tree); generateAppStructure adds the scaffold only. The presentation-IR
  // views emit one page component + route each (RFC-2026-06-28).
  const generatedFiles = buildGeneratedFiles(domains, enums, config, views, peers);

  // Write files
  console.log(pc.dim('─'.repeat(50)));

  if (config.dryRun) {
    console.log(pc.yellow('Dry run - no files written'));
    for (const entry of planWrites(outputPath, generatedFiles, { overwrite: config.overwrite })) {
      console.log(pc.dim(`  ${DRY_RUN_LABEL[entry.action]}`), entry.path);
    }
  } else {
    // Ownership is the previous run's manifest (output/writer.ts): an owned file is rewritten when it
    // differs, an owned seed file is kept, and an existing file the manifest does not record is
    // replaced only under --overwrite. Orphans are pruned and this run's manifest recorded.
    const { plan, pruned } = writeGeneratedFiles(outputPath, generatedFiles, { overwrite: config.overwrite });
    const count = (action: WriteAction): number => plan.filter((entry) => entry.action === action).length;

    for (const entry of plan) {
      if (entry.action === 'create' || entry.action === 'rewrite') {
        console.log(pc.green('  ✓'), basename(entry.path));
      } else if (config.verbose && entry.action === 'skip-unowned') {
        console.log(pc.yellow('  Skipped (not generated here):'), entry.path);
      } else if (config.verbose && entry.action === 'keep-seed') {
        console.log(pc.dim('  Kept (yours to edit):'), entry.path);
      }
    }

    console.log(pc.dim('─'.repeat(50)));
    console.log(pc.green('Generated:'), count('create') + count('rewrite'), 'file(s)');
    if (count('unchanged') > 0) {
      console.log(pc.dim('Unchanged:'), count('unchanged'), 'file(s)');
    }
    if (count('keep-seed') > 0) {
      console.log(pc.dim('Kept:'), count('keep-seed'), 'file(s) written once for you to edit (use --overwrite to replace)');
    }
    if (count('skip-unowned') > 0) {
      console.log(
        pc.yellow('Skipped:'),
        count('skip-unowned'),
        'existing file(s) a previous run did not generate (use --overwrite to replace them)',
      );
    }
    if (pruned > 0) {
      console.log(pc.yellow('Pruned:'), pruned, 'orphaned file(s)');
    }
  }

  console.log(pc.green('✓'), 'Done');
}

// ============================================================================
// Run CLI
// ============================================================================

program.parse();
