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
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, basename, resolve } from 'node:path';
import { pruneOrphansAndWriteManifest, MANIFEST_NAME } from './output/manifest.js';
import { loadConfig, type GeneratorConfig, DEFAULT_CONFIG } from './config.js';
import { parseDomainMetadata, parseExerisMetadata, type DomainMetadata } from './models/domain-model.js';
import { generateService } from './generators/angular/service-gen.js';
import { generateForm } from './generators/angular/form-gen.js';
import { generateList } from './generators/angular/list-gen.js';
import { generateTypes } from './generators/api/type-gen.js';
import { generateAppStructure } from './generators/angular/app-structure-gen.js';

// Import new generators (v0.3.0)
import { getStrategy } from './core/backend-strategy.js';

const VERSION = '0.3.0';

interface GeneratedFile {
  path: string;
  content: string;
}

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
  .option('--api-base <path>', 'API base path', '/api')
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
  .option('--overwrite', 'Overwrite existing files')
  .option('--dry-run', 'Show what would be generated without writing files')
  .option('-v, --verbose', 'Verbose output')
  .action(async (options: Record<string, unknown>) => {
    try {
      const config = loadConfig({
        inputPath: options.input as string | undefined,
        outputPath: options.output as string | undefined,
        apiBasePath: options.apiBase as string | undefined,
        framework: options.framework as 'angular' | 'react' | 'vue' | undefined,
        styling: options.styling as 'tailwind' | 'material' | 'bootstrap' | 'none' | undefined,
        backend: options.backend as 'KERNEL' | undefined,
        generateZod: options.zod !== false,
        generateServices: options.services !== false,
        generateForms: options.forms !== false,
        generateLists: options.lists !== false,
        generateStores: options.stores !== false,
        generateSagas: options.sagas !== false,
        generateEvents: options.events !== false,
        overwrite: (options.overwrite as boolean) ?? false,
        dryRun: (options.dryRun as boolean) ?? false,
        verbose: (options.verbose as boolean) ?? false,
      });

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

  // Find metadata files
  const metadataFiles = findMetadataFiles(inputPath);

  if (metadataFiles.length === 0) {
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

  // Separate enum files from domain files
  const enumFiles = metadataFiles.filter(f => basename(f).startsWith('enum_'));
  const domainFiles = metadataFiles.filter(f => !basename(f).startsWith('enum_'));

  // Load and parse enum metadata
  interface EnumMetadata {
    name: string;
    qualifiedName: string;
    packageName: string;
    description?: string;
    values: Array<{ name: string; displayName: string; ordinal: number; description?: string }>;
  }

  const enums: EnumMetadata[] = [];
  for (const file of enumFiles) {
    if (config.verbose) {
      console.log(pc.dim('  Loading enum:'), basename(file));
    }
    const content = readFileSync(file, 'utf-8');
    const json = JSON.parse(content) as EnumMetadata;
    enums.push(json);
  }

  // Load and parse domain metadata
  const domains: DomainMetadata[] = [];
  for (const file of domainFiles) {
    if (config.verbose) {
      console.log(pc.dim('  Loading:'), basename(file));
    }

    const content = readFileSync(file, 'utf-8');
    const json = JSON.parse(content) as unknown;

    // Handle both single domain and multi-domain files
    if (typeof json === 'object' && json !== null) {
      const obj = json as Record<string, unknown>;
      if (Array.isArray(obj.domains)) {
        const metadata = parseExerisMetadata(json);
        domains.push(...metadata.domains);
      } else if (typeof obj.entityName === 'string') {
        domains.push(parseDomainMetadata(json));
      }
    }
  }

  console.log(pc.green('Loaded'), domains.length, 'domain(s)', '+', enums.length, 'enum(s)');

  // Generate files
  const generatedFiles: GeneratedFile[] = [];

  // Generate enum types first
  if (enums.length > 0) {
    const enumTypesContent = generateEnumTypes(enums);
    generatedFiles.push({
      path: 'types/enums.ts',
      content: enumTypesContent
    });
  }

  for (const domain of domains) {
    if (config.verbose) {
      console.log(pc.dim('  Generating:'), domain.entityName);
    }

    // Skip internal/hidden entities
    if (domain.internalApi?.hidden) {
      if (config.verbose) {
        console.log(pc.dim('    Skipping (internal)'));
      }
      continue;
    }

    // Generate TypeScript types
    const types = generateTypes(domain, config);
    generatedFiles.push(...types);

    // Generate service
    if (config.generateServices) {
      const service = generateService(domain, config);
      if (service) generatedFiles.push(service);
    }

    // Generate form
    if (config.generateForms) {
      const form = generateForm(domain, config);
      if (form) generatedFiles.push(form);
    }

    // Generate list
    if (config.generateLists) {
      const list = generateList(domain, config);
      if (list) generatedFiles.push(list);
    }
  }

  // Generate application structure (routes, app.component, configs)
  const appStructureFiles = generateAppStructure(domains, enums, config);
  generatedFiles.push(...appStructureFiles);

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
// Enum Types Generation
// ============================================================================

interface EnumMetadataForGen {
  name: string;
  qualifiedName: string;
  packageName: string;
  description?: string;
  values: Array<{ name: string; displayName: string; ordinal: number; description?: string }>;
}

function generateEnumTypes(enums: EnumMetadataForGen[]): string {
  const lines: string[] = [
    '/**',
    ' * Generated Enum Types',
    ' * Generated by @exeris/codegen-ts',
    ' * DO NOT EDIT - This file is auto-generated',
    ' */',
    '',
  ];

  for (const enumDef of enums) {
    // Generate TypeScript const enum
    if (enumDef.description) {
      lines.push(`/** ${enumDef.description} */`);
    }
    lines.push(`export const ${enumDef.name} = {`);
    for (const value of enumDef.values) {
      if (value.description) {
        lines.push(`  /** ${value.description} */`);
      }
      lines.push(`  ${value.name}: '${value.name}',`);
    }
    lines.push('} as const;');
    lines.push('');
    lines.push(`export type ${enumDef.name} = typeof ${enumDef.name}[keyof typeof ${enumDef.name}];`);
    lines.push('');

    // Generate display name lookup
    lines.push(`export const ${enumDef.name}DisplayNames: Record<${enumDef.name}, string> = {`);
    for (const value of enumDef.values) {
      lines.push(`  [${enumDef.name}.${value.name}]: '${value.displayName}',`);
    }
    lines.push('};');
    lines.push('');

    // Generate Zod schema
    lines.push(`export const ${enumDef.name}Schema = z.enum([`);
    for (const value of enumDef.values) {
      lines.push(`  '${value.name}',`);
    }
    lines.push(']);');
    lines.push('');
  }

  // Add zod import at the top
  lines.splice(6, 0, "import { z } from 'zod';", '');

  return lines.join('\n');
}

function findMetadataFiles(inputPath: string): string[] {
  if (!existsSync(inputPath)) {
    return [];
  }

  const files: string[] = [];

  // If it's a file, check if it's JSON
  const stat = statSync(inputPath);
  if (stat.isFile()) {
    if (inputPath.endsWith('.json')) {
      return [inputPath];
    }
    return [];
  }

  // It's a directory, scan for JSON files
  const entries = readdirSync(inputPath, { withFileTypes: true });

  for (const entry of entries) {
    const fullPath = join(inputPath, entry.name);

    if (entry.isFile() && entry.name.endsWith('.json')) {
      files.push(fullPath);
    } else if (entry.isDirectory()) {
      // Recursively scan subdirectories
      files.push(...findMetadataFiles(fullPath));
    }
  }

  return files;
}

// ============================================================================
// Run CLI
// ============================================================================

program.parse();
