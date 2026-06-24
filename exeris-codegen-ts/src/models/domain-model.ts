/**
 * TypeScript models mapping Java DomainMetadata structure from exeris-processor.
 * These interfaces define the contract between Java annotation processor output
 * and TypeScript code generators.
 *
 * @author Exeris Team
 * @since 0.2.0
 */

import { z } from 'zod';

// ============================================================================
// Validation Metadata
// ============================================================================

export const ValidationMetadataSchema = z.object({
  required: z.boolean().optional(),
  minLength: z.number().optional(),
  maxLength: z.number().optional(),
  min: z.number().optional(),
  max: z.number().optional(),
  pattern: z.string().optional(),
  email: z.boolean().optional(),
  url: z.boolean().optional(),
});

export type ValidationMetadata = z.infer<typeof ValidationMetadataSchema>;

// ============================================================================
// Field Metadata
// ============================================================================

export const FieldMetadataSchema = z.object({
  name: z.string(),
  type: z.string(),
  columnName: z.string().optional(),
  displayName: z.string().optional(),
  description: z.string().optional(),
  required: z.boolean().default(false),
  unique: z.boolean().default(false),
  indexed: z.boolean().default(false),
  searchable: z.boolean().default(false),
  sortable: z.boolean().default(false),
  filterable: z.boolean().default(false),
  audited: z.boolean().default(false),
  readOnly: z.boolean().default(false),
  hidden: z.boolean().default(false),
  defaultValue: z.string().optional(),
  minLength: z.number().optional(),
  maxLength: z.number().optional(),
  min: z.number().optional(),
  max: z.number().optional(),
  pattern: z.string().optional(),
  format: z.string().optional(),
  enumType: z.string().optional(),
  // Formularz / widoki
  inList: z.boolean().default(true),
  inDetail: z.boolean().default(true),
  inCreate: z.boolean().default(true),
  inUpdate: z.boolean().default(true),
  order: z.number().optional(),
  ui: z.record(z.any()).optional(),
  // Computed fields
  computed: z.boolean().default(false),
  computedFrom: z.array(z.string()).optional(), // List of field names this depends on
  dependencies: z.array(z.string()).optional(), // Alias for computedFrom
});

export type FieldMetadata = z.infer<typeof FieldMetadataSchema>;

// ============================================================================
// Action Parameter Metadata
// ============================================================================

export const ActionParamMetadataSchema = z.object({
  name: z.string(),
  type: z.string(),
  required: z.boolean().default(false),
  description: z.string().optional(),
  defaultValue: z.string().optional(),
});

export type ActionParamMetadata = z.infer<typeof ActionParamMetadataSchema>;

// ============================================================================
// Action Metadata
// ============================================================================

export const ActionMetadataSchema = z.object({
  name: z.string(),
  displayName: z.string().optional(),
  description: z.string().optional(),
  httpMethod: z.string().optional(),
  path: z.string().optional(),
  params: z.array(ActionParamMetadataSchema).default([]),
  returnType: z.string().optional(),
  async: z.boolean().default(false),
  requiresAuth: z.boolean().default(true),
  permissions: z.array(z.string()).default([]),
  // ADR-044 Slice 2: per-action SSE streaming. The AST twin of
  // @Action(streaming=true) / @Action(streamEventType=…). When streaming is
  // true, the Java side emits an HttpStreamHandler bound via streamRoute(POST,
  // {base}/{id}/actions/{kebab}, …) and the TS side emits the RxJS
  // streaming-action client (parity, strong-default #4). streamEventType is the
  // named SSE event: carried on each frame (obligation 2).
  streaming: z.boolean().default(false),
  streamEventType: z.string().optional(),
});

export type ActionMetadata = z.infer<typeof ActionMetadataSchema>;

// ============================================================================
// Domain Event Metadata
// ============================================================================

export const DomainEventMetadataSchema = z.object({
  name: z.string(),
  displayName: z.string().optional(),
  description: z.string().optional(),
  payloadType: z.string().optional(),
  fields: z.array(FieldMetadataSchema).default([]),
});

export type DomainEventMetadata = z.infer<typeof DomainEventMetadataSchema>;

// ============================================================================
// Relationship Metadata
// ============================================================================

export const RelationshipMetadataSchema = z.object({
  name: z.string(),
  fieldName: z.string().optional(),
  targetEntity: z.string(),
  type: z.enum(['ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE', 'MANY_TO_MANY']),
  mappedBy: z.string().optional(),
  fetch: z.enum(['LAZY', 'EAGER']).default('LAZY'),
  cascade: z.union([z.string(), z.array(z.string())]).default('NONE'),
  orphanRemoval: z.boolean().default(false),
  optional: z.boolean().default(true),
  lazy: z.boolean().default(true),
  displayField: z.string().optional(),
  valueField: z.string().optional(),
  joinColumns: z.array(z.string()).optional(),
});

export type RelationshipMetadata = z.infer<typeof RelationshipMetadataSchema>;

// ============================================================================
// Projection Metadata
// ============================================================================

export const ProjectionMetadataSchema = z.object({
  name: z.string(),
  fields: z.array(z.string()).default([]),
  description: z.string().optional(),
});

export type ProjectionMetadata = z.infer<typeof ProjectionMetadataSchema>;

// ============================================================================
// UI Metadata
// ============================================================================

export const UIMetadataSchema = z.object({
  icon: z.string().optional(),
  color: z.string().optional(),
  listColumns: z.array(z.string()).default([]),
  searchFields: z.array(z.string()).default([]),
  filterFields: z.array(z.string()).default([]),
  formLayout: z.string().optional(),
});

export type UIMetadata = z.infer<typeof UIMetadataSchema>;

// ============================================================================
// Graph Metadata
// ============================================================================

export const GraphEdgeMetadataSchema = z.object({
  name: z.string(),
  targetEntity: z.string(),
  edgeType: z.string().optional(),
  direction: z.enum(['OUTGOING', 'INCOMING', 'BOTH']).default('OUTGOING'),
});

export type GraphEdgeMetadata = z.infer<typeof GraphEdgeMetadataSchema>;

export const GraphMetadataSchema = z.object({
  label: z.string().optional(),
  edges: z.array(GraphEdgeMetadataSchema).default([]),
});

export type GraphMetadata = z.infer<typeof GraphMetadataSchema>;

// ============================================================================
// Saga Metadata
// ============================================================================

export const SagaStepMetadataSchema = z.object({
  name: z.string(),
  action: z.string().optional(),
  compensatingAction: z.string().optional(),
  timeout: z.string().optional(), // ISO Duration (PT10M)
  retries: z.number().optional(),
  order: z.number().optional(),
  parallel: z.boolean().optional(),
  condition: z.string().optional(),
  dependsOn: z.array(z.string()).optional(),
});

export type SagaStepMetadata = z.infer<typeof SagaStepMetadataSchema>;

export const SagaMetadataSchema = z.object({
  name: z.string(),
  description: z.string().optional(),
  version: z.number().optional(),
  steps: z.array(SagaStepMetadataSchema).default([]),
  compensationStrategy: z.enum(['ALL_OR_NOTHING', 'BEST_EFFORT', 'CUSTOM']).optional(),
  compensationOrder: z.enum(['REVERSE', 'FORWARD', 'PARALLEL']).optional(),
  timeout: z.string().optional(), // ISO Duration (PT10M)
  compensationTimeout: z.string().optional(),
  maxRetries: z.number().optional(),
  retryBackoff: z.string().optional(),
  persistent: z.boolean().optional(),
  permissions: z.array(z.string()).optional(),
});

export type SagaMetadata = z.infer<typeof SagaMetadataSchema>;

// ============================================================================
// System Fields Metadata
// ============================================================================

export const SystemFieldsMetadataSchema = z.object({
  idField: z.string().default('id'),
  versionField: z.string().optional(),
  createdAtField: z.string().optional(),
  updatedAtField: z.string().optional(),
  createdByField: z.string().optional(),
  updatedByField: z.string().optional(),
  tenantIdField: z.string().optional(),
  deletedAtField: z.string().optional(),
});

export type SystemFieldsMetadata = z.infer<typeof SystemFieldsMetadataSchema>;

// ============================================================================
// Event Sourced Metadata
// ============================================================================

export const EventSourcedMetadataSchema = z.object({
  aggregateType: z.string(),
  snapshotInterval: z.number().optional(),
  eventStore: z.string().optional(),
});

export type EventSourcedMetadata = z.infer<typeof EventSourcedMetadataSchema>;

// ============================================================================
// Internal API Metadata
// ============================================================================

export const InternalApiMetadataSchema = z.object({
  hidden: z.boolean().default(false),
  readOnly: z.boolean().default(false),
  internal: z.boolean().default(false),
  reason: z.string().optional(),
  since: z.string().optional(),
  disabledActions: z.array(z.string()).optional(),
  allowedRoles: z.array(z.string()).optional(),
});

export type InternalApiMetadata = z.infer<typeof InternalApiMetadataSchema>;

// ============================================================================
// Domain Metadata (Root)
// ============================================================================

export const DomainMetadataSchema = z.object({
  entityName: z.string(),
  packageName: z.string(),
  tableName: z.string().optional(),
  displayName: z.string().optional(),
  pluralName: z.string().optional(),
  description: z.string().optional(),

  // API routing - from Java processor
  path: z.string().optional(),           // e.g., "/tenants"
  apiVersion: z.string().optional(),     // e.g., "v1"
  apiPath: z.string().optional(),        // Legacy/override: full path like "/api/v1/tenants"
  module: z.string().optional(),         // e.g., "foundation"

  // Feature flags
  restApi: z.boolean().default(true),
  graphqlApi: z.boolean().default(false),
  realTimeApi: z.boolean().default(false),
  internalClient: z.boolean().default(false),
  tenantScoped: z.boolean().default(false),
  versioned: z.boolean().default(false),
  fullTextSearch: z.boolean().default(false),
  searchConfig: z.string().optional(),

  audited: z.boolean().default(false),
  softDelete: z.boolean().default(false),
  multiTenant: z.boolean().default(false),
  cacheable: z.boolean().default(false),
  cacheSeconds: z.number().default(0),
  cacheTtl: z.string().optional(),       // e.g., "PT1H"
  cacheRegion: z.string().optional(),
  fields: z.array(FieldMetadataSchema).default([]),
  actions: z.array(ActionMetadataSchema).default([]),
  events: z.array(DomainEventMetadataSchema).default([]),
  relationships: z.array(RelationshipMetadataSchema).default([]),
  projections: z.array(ProjectionMetadataSchema).default([]),
  uiMetadata: UIMetadataSchema.optional(),
  graphMetadata: GraphMetadataSchema.optional(),
  sagaMetadata: SagaMetadataSchema.optional(),
  systemFields: SystemFieldsMetadataSchema.optional(),
  eventSourced: EventSourcedMetadataSchema.optional(),
  internalApi: InternalApiMetadataSchema.optional(),
});

export type DomainMetadata = z.infer<typeof DomainMetadataSchema>;

// ============================================================================
// Exeris Metadata File (Root JSON)
// ============================================================================

export const ExerisMetadataSchema = z.object({
  version: z.string().default('0.2.0'),
  generatedAt: z.string().optional(),
  domains: z.array(DomainMetadataSchema).default([]),
});

export type ExerisMetadata = z.infer<typeof ExerisMetadataSchema>;

// ============================================================================
// Helper Functions
// ============================================================================

/**
 * Parse and validate domain metadata from JSON.
 */
export function parseDomainMetadata(json: unknown): DomainMetadata {
  return DomainMetadataSchema.parse(json);
}

/**
 * Parse and validate exeris metadata file.
 */
export function parseExerisMetadata(json: unknown): ExerisMetadata {
  return ExerisMetadataSchema.parse(json);
}

