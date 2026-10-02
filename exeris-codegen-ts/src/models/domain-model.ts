/**
 * TypeScript models mapping Java DomainMetadata structure from exeris-processor.
 * These interfaces define the contract between Java annotation processor output
 * and TypeScript code generators.
 */

import { z } from 'zod';

// ============================================================================
// Field Metadata
// ============================================================================

// Mirrors eu.exeris.sdk.sourcemodel.ast.FieldMetadata: every key here is a component of that
// record. The record declares more than the processor writes, so several keys never arrive from a
// real build; `FIELD_CONTRACT_COVERAGE` in contract-coverage.ts records which, per key.
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
  // @Field.dataType — front-presentation type hint (currency / percent / url / …).
  // Additive (Wave 1A): drives the Angular formatter switch in the list/detail/form
  // generators; the default render path is unchanged when dataType is absent.
  dataType: z.string().optional(),
  enumType: z.string().optional(),
  inCreate: z.boolean().default(true),
  inUpdate: z.boolean().default(true),
  computed: z.boolean().default(false),
  // The names of the fields a computed field is derived from.
  computedFrom: z.array(z.string()).optional(),
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

// Every key is a component of eu.exeris.sdk.sourcemodel.ast.ActionMetadata; the record declares
// more than the processor writes, and `ACTION_CONTRACT_COVERAGE` in contract-coverage.ts records
// which keys arrive from a real build.
export const ActionMetadataSchema = z.object({
  name: z.string(),
  // The Java method behind the action; `name` is the action identity and may differ.
  methodName: z.string().optional(),
  displayName: z.string().optional(),
  description: z.string().optional(),
  httpMethod: z.string().optional(),
  params: z.array(ActionParamMetadataSchema).default([]),
  // The method's return type as written in source.
  resultType: z.string().optional(),
  async: z.boolean().default(false),
  permissions: z.array(z.string()).default([]),
  producesEvents: z.array(z.string()).optional(),
  // What the action's route demands of its caller; absent means the author declared nothing.
  routeAccess: z.enum(['PUBLIC', 'AUTHENTICATED']).optional(),
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

// Every key is a component of eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
// `EVENT_CONTRACT_COVERAGE` in contract-coverage.ts records where each is acted on.
export const DomainEventMetadataSchema = z.object({
  name: z.string(),
  // The topic the emitted publisher routes on.
  topic: z.string().optional(),
  description: z.string().optional(),
  // The simple name of the entity that declares the event.
  aggregateType: z.string().optional(),
  // EV1: the resolved payload field NAMES (entity-declaration order) the processor
  // and -io reader emit. The generator resolves each name's type against
  // domain.fields by name (NOT full FieldMetadata copies on the event). sensitiveFields
  // names are marked in the emitted payload interface. Both are .optional() because the
  // SDK record carries @JsonInclude(NON_NULL) and absent lists are missing on the wire.
  payloadFields: z.array(z.string()).optional(),
  sensitiveFields: z.array(z.string()).optional(),
  // EV2 (T48 / ADR-075): WHEN the event fires. The Java side reads these to place a
  // publish call in the generated handler; no TS emitter reads them, and mirroring them
  // here is the parity contract rather than a pending feature — the front-end consumes
  // events off the SSE stream and never decides where one is produced. `trigger` is
  // nullable by design upstream: absent means "this baseline predates EV2 extraction",
  // which is a different claim from CREATE.
  trigger: z.string().optional(),
  actionName: z.string().optional(),
  fieldName: z.string().optional(),
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

// Mirrors the entity-level components of eu.exeris.sdk.sourcemodel.ast.UIMetadata that carry
// what @UI declares: the seven view switches the processor copies from the annotation, plus the
// icon and colour the record declares and the processor never sets. `columns`, `defaultLayout`,
// `bulkActions`, `groups` and `fieldOverrides` are written too, but only ever as the builder's
// defaults, so leaving them undeclared loses nothing the source said. The switches are optional rather than defaulted: the processor writes all seven
// whenever @UI is present, so a default would only fill hand-built metadata, with a value no
// source stated.
export const UIMetadataSchema = z.object({
  icon: z.string().optional(),
  color: z.string().optional(),
  listView: z.boolean().optional(),
  detailView: z.boolean().optional(),
  createForm: z.boolean().optional(),
  editForm: z.boolean().optional(),
  searchable: z.boolean().optional(),
  filterable: z.boolean().optional(),
  exportable: z.boolean().optional(),
});

export type UIMetadata = z.infer<typeof UIMetadataSchema>;

// ============================================================================
// Graph Metadata
// ============================================================================

// Mirrors eu.exeris.sdk.sourcemodel.ast.GraphMetadata and its three element records
// (GraphPropertyMetadata / GraphEdgeMetadata / GraphQueryMetadata). Every reference-typed
// component is .optional() because those records carry @JsonInclude(NON_NULL), so a null
// component is absent from the wire rather than null: the processor passes
// GraphMetadata.properties as null today, and an edge's targetLabel / relationType are null
// whenever the annotation left them blank.
//
// @GraphEdge.direction has no component on GraphEdgeMetadata — one of the attributes with no
// carrier listed in ROADMAP's annotation-surface debt — so no metadata document carries it.
// Declaring it with a default would report OUTGOING for every edge, which is a direction the
// pipeline has never been told.

export const GraphPropertyMetadataSchema = z.object({
  name: z.string(),
  type: z.string().optional(),
  indexed: z.boolean().default(false),
});

export type GraphPropertyMetadata = z.infer<typeof GraphPropertyMetadataSchema>;

export const GraphEdgeMetadataSchema = z.object({
  name: z.string(),
  targetLabel: z.string().optional(),
  relationType: z.string().optional(),
});

export type GraphEdgeMetadata = z.infer<typeof GraphEdgeMetadataSchema>;

export const GraphQueryMetadataSchema = z.object({
  name: z.string(),
  cypher: z.string().optional(),
  description: z.string().optional(),
});

export type GraphQueryMetadata = z.infer<typeof GraphQueryMetadataSchema>;

export const GraphMetadataSchema = z.object({
  label: z.string().optional(),
  properties: z.array(GraphPropertyMetadataSchema).default([]),
  edges: z.array(GraphEdgeMetadataSchema).default([]),
  queries: z.array(GraphQueryMetadataSchema).default([]),
});

export type GraphMetadata = z.infer<typeof GraphMetadataSchema>;

// ============================================================================
// Saga Metadata
// ============================================================================

// Every key is a component of eu.exeris.sdk.sourcemodel.ast.SagaStepMetadata;
// `SAGA_STEP_CONTRACT_COVERAGE` in contract-coverage.ts records where each is acted on.
export const SagaStepMetadataSchema = z.object({
  name: z.string(),
  service: z.string().optional(),
  command: z.string().optional(),
  // The step's compensation; a step without one is not compensated.
  compensation: z.string().optional(),
  timeout: z.string().optional(), // ISO Duration (PT10M)
  maxRetries: z.number().optional(),
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

// Mirrors eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata, component for component.
// The record carries @JsonInclude(NON_NULL), and `SystemFieldsMetadata.defaults()` leaves the
// three soft-delete components null, so those are absent unless the entity overrides them —
// hence .optional() on everything the defaults do not fill.
//
// `sharedScopeField` is the `@SharedScope` field of a `dataScope = UNIVERSE` entity, absent
// on every other entity. Declared here so Zod's unknown-key stripping does not drop it;
// `systemFieldNames` classifies it server-owned.
export const SystemFieldsMetadataSchema = z.object({
  primaryKeyField: z.string().default('id'),
  createdAtField: z.string().optional(),
  createdByField: z.string().optional(),
  updatedAtField: z.string().optional(),
  updatedByField: z.string().optional(),
  tenantIdField: z.string().optional(),
  versionField: z.string().optional(),
  softDeleteField: z.string().optional(),
  softDeleteTimestampField: z.string().optional(),
  softDeletedByField: z.string().optional(),
  sharedScopeField: z.string().optional(),
});

export type SystemFieldsMetadata = z.infer<typeof SystemFieldsMetadataSchema>;

// ============================================================================
// Event Sourced Metadata
// ============================================================================

// Every key is a component of eu.exeris.sdk.sourcemodel.ast.EventSourcedMetadata.
export const EventSourcedMetadataSchema = z.object({
  aggregateType: z.string(),
  // Events between snapshots.
  snapshotEvery: z.number().optional(),
  eventStore: z.string().optional(),
});

export type EventSourcedMetadata = z.infer<typeof EventSourcedMetadataSchema>;

// ============================================================================
// Internal API Metadata
// ============================================================================

// Mirrors eu.exeris.sdk.sourcemodel.ast.InternalApiMetadata except `hidden`, which no annotation
// sets: @InternalApi declares no such attribute and the processor writes the component as false
// for every entity, so declaring it would only let hand-built metadata hide an entity a real build
// cannot. `INTERNAL_API_CONTRACT_COVERAGE` in contract-coverage.ts records which keys arrive.
export const InternalApiMetadataSchema = z.object({
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
  // ADR-059: `dataScope` is the canonical data-scope tier; `tenantScoped` is
  // its deprecated predecessor (removal at SDK 1.0.0). Absent means "no tier
  // declared" — the AST has no UNSPECIFIED constant, that exists only on the
  // annotation side — and resolves through the boolean's fallback
  // (`true → TENANT`, `false → GLOBAL`); `effectiveDataScope` below is the
  // canonical read, mirroring `DomainMetadata.effectiveDataScope()` on the Java
  // side. Mirrored here for Java/TS parity even though no TS emitter consumes
  // the tier yet; a field visible on one side is visible on both.
  dataScope: z.enum(['GLOBAL', 'TENANT', 'UNIVERSE']).optional(),
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
// Presentation IR — @View / @Region / @Block / @Bind (RFC-2026-06-28)
//
// The framework-neutral presentation IR the SDK owns (ViewMetadata /
// RegionMetadata / ComponentNodeMetadata / BindingMetadata + ViewKind /
// BlockType / BindSource). Mirrors the SDK records' field names + nullability:
// every string field there normalises blank → null in its compact constructor
// and the records carry @JsonInclude(NON_NULL), so absent fields are simply
// missing on the wire — modelled here as `.optional()`. The enums are
// null-tolerated on the wire (the SDK applies effective* defaults), so each is
// an optional string union. Recursion (ComponentNodeMetadata.children) goes
// through z.lazy. The wire shape is the processor's `view_*.json` =
// ViewJson { name, packageName, qualifiedName, view: ViewMetadata }.
// ============================================================================

/** ViewKind string union (SDK enum constants; PAGE default applied SDK-side). */
export const ViewKindSchema = z.enum(['PAGE', 'SECTION', 'COMPONENT', 'FRAGMENT']);
export type ViewKind = z.infer<typeof ViewKindSchema>;

/** BlockType string union (SDK enum constants; CONTAINER default applied SDK-side). */
export const BlockTypeSchema = z.enum([
  'HERO',
  'LIST',
  'GRID',
  'RICH_TEXT',
  'NAV',
  'SLOT',
  'CONTAINER',
  'CARD',
  'FORM',
  'IMAGE',
  'CUSTOM',
]);
export type BlockType = z.infer<typeof BlockTypeSchema>;

/** BindSource string union (SDK enum constants; NONE default applied SDK-side). */
export const BindSourceSchema = z.enum([
  'ENTITY',
  'PROJECTION',
  'ACTION',
  'STATIC',
  'SLOT',
  'NONE',
]);
export type BindSource = z.infer<typeof BindSourceSchema>;

/**
 * BindingMetadata — the opaque data binding of a presentation node. Discriminator
 * `source` plus opaque `ref` / `path` / `expression` / `language` strings. A null
 * `source` on the wire means NONE (the SDK's effectiveSource default).
 */
export const BindingMetadataSchema = z.object({
  source: BindSourceSchema.optional(),
  ref: z.string().optional(),
  path: z.string().optional(),
  expression: z.string().optional(),
  language: z.string().optional(),
});

export type BindingMetadata = z.infer<typeof BindingMetadataSchema>;

/**
 * ComponentNodeMetadata — one node in the composition tree: a typed BlockType
 * block with an optional binding, opaque `props` JSON, a recursive `children`
 * list, and an optional `field` render facet (the @UI successor; minimally
 * modelled in slice 1 as opaque). A null `type` on the wire means CONTAINER (the
 * SDK's effectiveType default). The recursion is expressed via z.lazy.
 */
export const ComponentNodeMetadataSchema: z.ZodType<
  ComponentNodeMetadata,
  z.ZodTypeDef,
  ComponentNodeMetadataInput
> = z.lazy(() =>
  z.object({
    type: BlockTypeSchema.optional(),
    customType: z.string().optional(),
    binding: BindingMetadataSchema.optional(),
    props: z.string().optional(),
    children: z.array(ComponentNodeMetadataSchema).default([]),
    // Leaf field-render facet (UIMetadata.UIFieldMetadata). Slice 1 keeps it
    // opaque — modelled, not yet emitted (RFC §1 / §5 leaf-field-form deferral).
    field: z.record(z.any()).optional(),
  }),
);

/** The parsed (output) shape: `children` is always present (`.default([])`). */
export interface ComponentNodeMetadata {
  type?: BlockType;
  customType?: string;
  binding?: BindingMetadata;
  props?: string;
  children: ComponentNodeMetadata[];
  field?: Record<string, unknown>;
}

/** The wire (input) shape: `children` may be absent (the SDK omits empty lists). */
export interface ComponentNodeMetadataInput {
  type?: BlockType;
  customType?: string;
  binding?: BindingMetadata;
  props?: string;
  children?: ComponentNodeMetadataInput[];
  field?: Record<string, unknown>;
}

/**
 * RegionMetadata — a named region / slot holding a list of component nodes. A
 * blank `slot` is normalised to null SDK-side (the processor derives it from the
 * member name before write-out, so on the wire it is typically present).
 */
export const RegionMetadataSchema = z.object({
  slot: z.string().optional(),
  components: z.array(ComponentNodeMetadataSchema).default([]),
});

export type RegionMetadata = z.infer<typeof RegionMetadataSchema>;

/**
 * ViewMetadata — the root of the presentation IR. `name` is required and
 * non-blank; a null `kind` means PAGE (the SDK's effectiveKind default).
 */
export const ViewMetadataSchema = z.object({
  name: z.string(),
  kind: ViewKindSchema.optional(),
  route: z.string().optional(),
  title: z.string().optional(),
  titleKey: z.string().optional(),
  layout: z.string().optional(),
  regions: z.array(RegionMetadataSchema).default([]),
});

export type ViewMetadata = z.infer<typeof ViewMetadataSchema>;

/**
 * ViewJson — the processor's `view_*.json` wire shape: the inner ViewMetadata
 * wrapped with the declaring class's identity (mirrors CapabilityModuleJson).
 * Parallel to DomainMetadata, never nested.
 */
export const ViewJsonSchema = z.object({
  name: z.string(),
  packageName: z.string(),
  qualifiedName: z.string(),
  view: ViewMetadataSchema,
});

export type ViewJson = z.infer<typeof ViewJsonSchema>;

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

/**
 * The entity's data-scope tier — the canonical read, and the TS twin of
 * `DomainMetadata.effectiveDataScope()` on the Java side (ADR-059).
 *
 * An explicit `dataScope` wins; otherwise the deprecated `tenantScoped` boolean
 * still decides (`true → TENANT`, `false → GLOBAL`), so metadata written before
 * SDK 0.10.0 reads back with exactly the meaning it always had. Never returns
 * undefined. Read this rather than either field: an entity can declare
 * `dataScope: 'TENANT'` without ever setting `tenantScoped`, and a consumer
 * reading the raw boolean would silently treat it as unpartitioned.
 */
export function effectiveDataScope(
  metadata: Pick<DomainMetadata, 'dataScope' | 'tenantScoped'>
): 'GLOBAL' | 'TENANT' | 'UNIVERSE' {
  return metadata.dataScope ?? (metadata.tenantScoped ? 'TENANT' : 'GLOBAL');
}

/**
 * Whether the entity's rows have an owning tenant — the TS twin of the Java side's
 * `DataScopeSupport.isTenantPartitioned`. True for `TENANT` and for `UNIVERSE`, whose rows are
 * owned too (a UNIVERSE row is readable across its owner's shared scope, never by every tenant).
 */
export function isTenantPartitioned(metadata: Pick<DomainMetadata, 'dataScope' | 'tenantScoped'>): boolean {
  return effectiveDataScope(metadata) !== 'GLOBAL';
}

/**
 * Parse a processor `view_*.json` (the ViewJson wrapper) and return the inner
 * ViewMetadata. The wrapper's `name` is the view's own name (identical to
 * `view.name` by construction), so the inner record carries the identity the
 * emitter needs; the package/qualified name are processor bookkeeping the
 * front-only emitter does not consume in slice 1.
 */
export function parseViewJson(json: unknown): ViewMetadata {
  return ViewJsonSchema.parse(json).view;
}

