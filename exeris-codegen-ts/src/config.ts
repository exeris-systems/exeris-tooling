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
import { fileURLToPath } from 'node:url';
import { z } from 'zod';

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

  /** API base path for generated services */
  apiBasePath: z.string().default('/api'),

  /** Backend strategy — kernel-target-only (single supported target) */
  backend: z.enum(['KERNEL']).default('KERNEL'),

  /** Whether to overwrite existing files */
  overwrite: z.boolean().default(false),

  /** Dry run - show what would be generated without writing files */
  dryRun: z.boolean().default(false),

  /** Verbose output */
  verbose: z.boolean().default(false),

  /** Custom templates directory */
  templatesDir: z.string().optional(),
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
  apiBasePath: '/api',
  backend: 'KERNEL',
  overwrite: false,
  dryRun: false,
  verbose: false,
  templatesDir: undefined,
};

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

export function resolveTemplatesPath(config: GeneratorConfig): string {
  if (config.templatesDir) {
    return resolve(process.cwd(), config.templatesDir);
  }
  // Use built-in templates
  const __dirname = dirname(fileURLToPath(import.meta.url));
  return resolve(__dirname, '..', 'templates');
}

