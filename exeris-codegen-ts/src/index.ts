#!/usr/bin/env node

/**
 * Exeris TypeScript Code Generator CLI
 *
 * Generates Angular 22+ frontend code from Exeris domain metadata.
 *
 * Usage:
 *   exeris-gen generate --input <path> --output <path>
 *   exeris-gen init
 *   exeris-gen --help
 *
 * @author Exeris Team
 * @since 0.3.0
 */

import { Command } from 'commander';
import pc from 'picocolors';
import { writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, basename, resolve } from 'node:path';
import { pruneOrphansAndWriteManifest, MANIFEST_NAME } from './output/manifest.js';
import { loadConfig, cliOverrides, type GeneratorConfig, DEFAULT_CONFIG } from './config.js';
import { findMetadataFiles, loadMetadataFamilies } from './models/metadata-files.js';
import { loadPeerContracts, type PeerContract } from './peers/peer-contract.js';
import { buildGeneratedFiles } from './orchestrator.js';

// Import new generators (v0.3.0)
import { getStrategy } from './core/backend-strategy.js';

const VERSION = '0.3.0';

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
  .option('--overwrite', 'Overwrite existing files')
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
  .action((options: Record<string, unknown>) => {
    const configPath = 'exeris-codegen.json';

    if (existsSync(configPath) && !options.force) {
      console.error(pc.yellow('Config file already exists. Use --force to overwrite.'));
      process.exit(1);
    }

    writeFileSync(configPath, JSON.stringify(DEFAULT_CONFIG, null, 2));
    console.log(pc.green('✓'), `Created ${configPath}`);
  });

// ============================================================================
// Generate Logic
// ============================================================================

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
    for (const file of generatedFiles) {
      console.log(pc.dim('  Would write:'), file.path);
    }
  } else {
    let written = 0;
    let skipped = 0;

    for (const file of generatedFiles) {
      // file.path is relative, combine with outputPath
      const fullPath = join(outputPath, file.path);

      if (existsSync(fullPath) && !config.overwrite) {
        if (config.verbose) {
          console.log(pc.yellow('  Skipped (exists):'), basename(fullPath));
        }
        skipped++;
        continue;
      }

      // Ensure directory exists
      const dir = dirname(fullPath);
      if (!existsSync(dir)) {
        mkdirSync(dir, { recursive: true });
      }

      writeFileSync(fullPath, file.content);
      console.log(pc.green('  ✓'), basename(fullPath));
      written++;
    }

    // T13: generation owns its output tree — delete files a previous run
    // emitted that this run no longer produces (e.g. a removed/re-homed entity),
    // then persist the manifest of this run's intended output set.
    const producedPaths = generatedFiles.map((f) => f.path.replace(/\\/g, '/'));
    const pruned = pruneOrphansAndWriteManifest(outputPath, producedPaths);

    console.log(pc.dim('─'.repeat(50)));
    console.log(pc.green('Generated:'), written, 'file(s)');
    if (skipped > 0) {
      console.log(pc.yellow('Skipped:'), skipped, 'file(s) (use --overwrite to replace)');
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
