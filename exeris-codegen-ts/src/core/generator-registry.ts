/**
 * Generator Registry
 *
 * Manages registration and execution of code generators.
 * Similar to Java's GeneratorRegistry pattern for consistency.
 *
 * @author Exeris Team
 * @since 0.3.0
 */

import type { BackendType } from './backend-strategy.js';

// Re-export types from domain-model for convenience
export type {
  DomainMetadata,
  FieldMetadata,
  ActionMetadata,
  RelationshipMetadata,
  DomainEventMetadata,
  SagaMetadata,
  SagaStepMetadata,
  ProjectionMetadata,
  UIMetadata,
  SystemFieldsMetadata,
} from '../models/domain-model.js';

import type {
  DomainMetadata,
} from '../models/domain-model.js';

// ============================================================================
// Types
// ============================================================================

export type ArtifactType =
  | 'TYPE'         // TypeScript types/interfaces
  | 'SCHEMA'       // Zod validation schema
  | 'SERVICE'      // API service
  | 'CLIENT'       // HTTP client
  | 'STREAM'       // SSE live-view EventSource client (parity with kernel HttpStreamHandler, ADR-043 Slice 1)
  | 'STORE'        // Signal store
  | 'FORM'         // Form component
  | 'LIST'         // List component
  | 'DETAIL'       // Detail view component
  | 'SAGA'         // Saga state machine
  | 'EVENT'        // Event handler
  | 'ROUTE'        // Route configuration
  | 'GUARD'        // Route guard
  | 'PIPE'         // Angular pipe
  | 'DIRECTIVE'    // Angular directive
  | 'ENUM'         // Enum types
  | 'QUERY_BUILDER'; // Query builder

export interface GeneratedFile {
  /** Relative path from output root */
  path: string;
  /** Generated code content */
  content: string;
  /** Artifact type for categorization */
  artifactType: ArtifactType;
  /** Whether file can be overwritten */
  overwritable: boolean;
}

export interface EnumMetadata {
  name: string;
  qualifiedName: string;
  packageName: string;
  description?: string;
  values: Array<{
    name: string;
    displayName: string;
    ordinal: number;
    description?: string;
  }>;
}

export interface GeneratorContext {
  /** Generator configuration */
  config: GeneratorConfig;
  /** Target backend type */
  backend: BackendType;
  /** All domains being processed (for cross-references) */
  allDomains: DomainMetadata[];
  /** Enum metadata */
  enums: EnumMetadata[];
}

export interface GeneratorConfig {
  inputPath: string;
  outputPath: string;
  framework: 'angular' | 'react' | 'vue';
  styling: 'tailwind' | 'material' | 'bootstrap' | 'none';
  standalone: boolean;
  signals: boolean;
  lazyRoutes: boolean;
  generateZod: boolean;
  generateServices: boolean;
  generateForms: boolean;
  generateLists: boolean;
  generateDetails: boolean;
  generateStores: boolean;
  generateSagas: boolean;
  generateEvents: boolean;
  apiBasePath: string;
  overwrite: boolean;
  dryRun: boolean;
  verbose: boolean;
  backend: BackendType;
}

// ============================================================================
// Generator Interface
// ============================================================================

export interface CodeGenerator {
  /** Generator name for logging/debugging */
  readonly name: string;

  /** Artifact type produced by this generator */
  readonly artifactType: ArtifactType;

  /** Supported backend types (empty = all backends) */
  readonly supportedBackends: BackendType[];

  /**
   * Execution priority (lower = earlier).
   * Default: 10. Types/Enums should be 1, Services 5, Components 10+
   */
  readonly priority?: number;

  /**
   * Generate code for a single domain entity.
   * Returns null if generation should be skipped.
   */
  generate(domain: DomainMetadata, context: GeneratorContext): GeneratedFile | null;

  /**
   * Generate aggregated code across all domains.
   * Used for barrel exports, route configs, etc.
   * Optional - return empty array if not applicable.
   */
  generateAggregate?(domains: DomainMetadata[], context: GeneratorContext): GeneratedFile[];
}

// ============================================================================
// Generator Registry
// ============================================================================

export class GeneratorRegistry {
  private readonly generators: CodeGenerator[] = [];

  /**
   * Register a code generator.
   */
  register(generator: CodeGenerator): this {
    this.generators.push(generator);
    return this;
  }

  /**
   * Register multiple generators.
   */
  registerAll(...generators: CodeGenerator[]): this {
    generators.forEach(g => this.register(g));
    return this;
  }

  /**
   * Get generators for a specific backend, sorted by priority.
   */
  getGenerators(backend: BackendType): CodeGenerator[] {
    return this.generators
      .filter(g =>
        g.supportedBackends.length === 0 ||
        g.supportedBackends.includes(backend)
      )
      .sort((a, b) => (a.priority ?? 10) - (b.priority ?? 10));
  }

  /**
   * Get generators by artifact type.
   */
  getByArtifactType(type: ArtifactType): CodeGenerator[] {
    return this.generators.filter(g => g.artifactType === type);
  }

  /**
   * Generate all artifacts for a domain.
   */
  generateForDomain(
    domain: DomainMetadata,
    context: GeneratorContext
  ): GeneratedFile[] {
    const files: GeneratedFile[] = [];
    const applicableGenerators = this.getGenerators(context.backend);

    for (const generator of applicableGenerators) {
      try {
        const file = generator.generate(domain, context);
        if (file) {
          files.push(file);
        }
      } catch (error) {
        console.error(`Generator ${generator.name} failed for ${domain.entityName}:`, error);
        throw error;
      }
    }

    return files;
  }

  /**
   * Generate all artifacts for all domains.
   */
  generateAll(
    domains: DomainMetadata[],
    context: GeneratorContext
  ): GeneratedFile[] {
    const files: GeneratedFile[] = [];
    const applicableGenerators = this.getGenerators(context.backend);

    // Per-domain generation
    for (const domain of domains) {
      files.push(...this.generateForDomain(domain, context));
    }

    // Aggregate generation (routes, barrel exports, etc.)
    for (const generator of applicableGenerators) {
      if (generator.generateAggregate) {
        try {
          const aggregateFiles = generator.generateAggregate(domains, context);
          files.push(...aggregateFiles);
        } catch (error) {
          console.error(`Generator ${generator.name} aggregate generation failed:`, error);
          throw error;
        }
      }
    }

    return files;
  }

  /**
   * Get all registered generators.
   */
  getAllGenerators(): CodeGenerator[] {
    return [...this.generators];
  }

  /**
   * Get count of registered generators.
   */
  get count(): number {
    return this.generators.length;
  }

  /**
   * Clear all registered generators.
   */
  clear(): this {
    this.generators.length = 0;
    return this;
  }

  /**
   * Check if a generator of a specific type exists.
   */
  hasGenerator(artifactType: ArtifactType): boolean {
    return this.generators.some(g => g.artifactType === artifactType);
  }
}

// ============================================================================
// Default Registry Instance
// ============================================================================

export const defaultRegistry = new GeneratorRegistry();

// ============================================================================
// Helper Functions
// ============================================================================

/**
 * Create a generator context with defaults.
 */
export function createGeneratorContext(
  config: Partial<GeneratorConfig>,
  domains: DomainMetadata[] = [],
  enums: EnumMetadata[] = []
): GeneratorContext {
  const fullConfig: GeneratorConfig = {
    inputPath: config.inputPath ?? 'target/classes/exeris-metadata',
    outputPath: config.outputPath ?? 'src/app/generated',
    framework: config.framework ?? 'angular',
    styling: config.styling ?? 'tailwind',
    standalone: config.standalone ?? true,
    signals: config.signals ?? true,
    lazyRoutes: config.lazyRoutes ?? true,
    generateZod: config.generateZod ?? true,
    generateServices: config.generateServices ?? true,
    generateForms: config.generateForms ?? true,
    generateLists: config.generateLists ?? true,
    generateDetails: config.generateDetails ?? true,
    generateStores: config.generateStores ?? true,
    generateSagas: config.generateSagas ?? true,
    generateEvents: config.generateEvents ?? true,
    apiBasePath: config.apiBasePath ?? '/api',
    overwrite: config.overwrite ?? false,
    dryRun: config.dryRun ?? false,
    verbose: config.verbose ?? false,
    backend: config.backend ?? 'KERNEL',
  };

  return {
    config: fullConfig,
    backend: fullConfig.backend,
    allDomains: domains,
    enums,
  };
}

// ============================================================================
// Generator Factory Functions
// ============================================================================

/**
 * Create and register all default generators.
 * Call this once during application bootstrap.
 */
export async function registerAllGenerators(registry: GeneratorRegistry = defaultRegistry): Promise<GeneratorRegistry> {
  // Dynamic imports for tree-shaking support
  const [
    { TypeGenerator },
    { EnumGenerator },
    { ServiceGenerator },
    { StreamClientGenerator },
    { ActionStreamClientGenerator },
    { StoreGenerator },
    { FormGenerator },
    { ListGenerator },
    { DetailGenerator },
    { SagaGenerator },
    { EventHandlerGenerator },
    { GuardGenerator },
    { QueryBuilderGenerator },
    { LandingPageGenerator },
  ] = await Promise.all([
    import('../generators/api/type-gen.js'),
    import('../generators/api/enum-gen.js'),
    import('../generators/angular/service-gen.js'),
    import('../generators/angular/stream-client-gen.js'),
    import('../generators/angular/action-stream-client-gen.js'),
    import('../generators/angular/store-gen.js'),
    import('../generators/angular/form-gen.js'),
    import('../generators/angular/list-gen.js'),
    import('../generators/angular/detail-gen.js'),
    import('../generators/angular/saga-gen.js'),
    import('../generators/angular/event-gen.js'),
    import('../generators/angular/guard-gen.js'),
    import('../generators/api/query-builder-gen.js'),
    import('../generators/angular/landing-gen.js'),
  ]);

  registry
    .register(new EnumGenerator())
    .register(new TypeGenerator())
    .register(new ServiceGenerator())
    .register(new StreamClientGenerator())
    .register(new ActionStreamClientGenerator())
    .register(new StoreGenerator())
    .register(new FormGenerator())
    .register(new ListGenerator())
    .register(new DetailGenerator())
    .register(new SagaGenerator())
    .register(new EventHandlerGenerator())
    .register(new GuardGenerator())
    .register(new QueryBuilderGenerator())
    .register(new LandingPageGenerator());

  return registry;
}

/**
 * Create a new registry with all default generators already registered.
 */
export async function createDefaultRegistry(): Promise<GeneratorRegistry> {
  const registry = new GeneratorRegistry();
  await registerAllGenerators(registry);
  return registry;
}

// ============================================================================
// Utility Functions
// ============================================================================

/**
 * Filter files by artifact type.
 */
export function filterFilesByType(files: GeneratedFile[], type: ArtifactType): GeneratedFile[] {
  return files.filter(f => f.artifactType === type);
}

/**
 * Group files by artifact type.
 */
export function groupFilesByType(files: GeneratedFile[]): Map<ArtifactType, GeneratedFile[]> {
  const grouped = new Map<ArtifactType, GeneratedFile[]>();
  for (const file of files) {
    const existing = grouped.get(file.artifactType) ?? [];
    existing.push(file);
    grouped.set(file.artifactType, existing);
  }
  return grouped;
}

/**
 * Get summary statistics for generated files.
 */
export function getGenerationStats(files: GeneratedFile[]): {
  totalFiles: number;
  byType: Record<ArtifactType, number>;
  overwritable: number;
  protected: number;
} {
  const byType: Partial<Record<ArtifactType, number>> = {};
  let overwritable = 0;
  let protectedCount = 0;

  for (const file of files) {
    byType[file.artifactType] = (byType[file.artifactType] ?? 0) + 1;
    if (file.overwritable) {
      overwritable++;
    } else {
      protectedCount++;
    }
  }

  return {
    totalFiles: files.length,
    byType: byType as Record<ArtifactType, number>,
    overwritable,
    protected: protectedCount,
  };
}

