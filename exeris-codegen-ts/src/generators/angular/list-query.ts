/**
 * The list route's query contract (ADR-096) as the front sends it: the parameter names, the page
 * size bounds, the page envelope, and which properties of an entity the route sorts and filters on.
 *
 * The server half is `ListQuerySupport` in `exeris-codegen-java`; both are pinned to
 * `exeris-e2e-tests/src/test/resources/contract/list-query.json`. The rules here read the same
 * metadata the same way, so the service, the store, the list, the relationship picker and the
 * related-records panels send only parameters the route reads: a parameter it does not read is
 * refused with `400`.
 *
 * Every list returned is sorted by property name in UTF-16 code-unit order, the order Java's
 * `String.compareTo` gives, so the output is a pure function of the metadata.
 */

import type { DomainMetadata, FieldMetadata, RelationshipMetadata } from '../../models/domain-model.js';
import { effectiveDataScope, isTenantPartitioned } from '../../models/domain-model.js';

/** Zero-based page index. */
export const PAGE_PARAM = 'page';
/** Page size. */
export const SIZE_PARAM = 'size';
/** `<property>,<asc|desc>`. */
export const SORT_PARAM = 'sort';

/** The page size the route serves when the request names none, and the size the list starts at. */
export const DEFAULT_PAGE_SIZE = 20;
/** The largest page size the route serves; a larger one is refused, not clamped. */
export const MAX_PAGE_SIZE = 100;

/** The parameter names that are never a filter. */
export const RESERVED_PARAMS: readonly string[] = [PAGE_PARAM, SIZE_PARAM, SORT_PARAM];

/** The page envelope's members, in declaration order. */
export const PAGE_ENVELOPE: readonly string[] = [
  'content', 'totalElements', 'totalPages', 'size', 'number', 'first', 'last',
];

/** The metadata type strings a filter parses, both spellings. An enum is filterable under its own type. */
export const FILTERABLE_SCALAR_TYPES: readonly string[] = [
  'BigDecimal', 'Boolean', 'Byte', 'Double', 'Float', 'Integer', 'LocalDate', 'Long', 'Short',
  'String', 'UUID', 'boolean', 'byte', 'double', 'float', 'int', 'java.lang.Boolean',
  'java.lang.Byte', 'java.lang.Double', 'java.lang.Float', 'java.lang.Integer', 'java.lang.Long',
  'java.lang.Short', 'java.lang.String', 'java.math.BigDecimal', 'java.time.LocalDate',
  'java.util.UUID', 'long', 'short',
];

/** The metadata type strings `sort` orders by, both spellings. An enum is sortable under its own type. */
export const SORTABLE_SCALAR_TYPES: readonly string[] = [
  'BigDecimal', 'Boolean', 'Byte', 'Double', 'Float', 'Instant', 'Integer', 'LocalDate',
  'LocalDateTime', 'Long', 'OffsetDateTime', 'Short', 'String', 'UUID', 'ZonedDateTime',
  'boolean', 'byte', 'double', 'float', 'int', 'java.lang.Boolean', 'java.lang.Byte',
  'java.lang.Double', 'java.lang.Float', 'java.lang.Integer', 'java.lang.Long',
  'java.lang.Short', 'java.lang.String', 'java.math.BigDecimal', 'java.time.Instant',
  'java.time.LocalDate', 'java.time.LocalDateTime', 'java.time.OffsetDateTime',
  'java.time.ZonedDateTime', 'java.util.UUID', 'long', 'short',
];

/**
 * How the list route treats a metadata type string — the classification of `DomainTypeKind` in
 * `exeris-codegen-java`, rule for rule: a `List<…>` in either spelling; any other parameterised type
 * or an array is `unstorable`; the recognised scalars in either spelling; `char` and the JDK types
 * with no column encoding are `unstorable`; then anything containing `Instant`, `LocalDateTime` or
 * `LocalDate`; and `other` for everything else.
 */
export type ListQueryKind =
  | 'list' | 'uuid' | 'string' | 'long' | 'int' | 'short' | 'byte' | 'bool' | 'float' | 'double'
  | 'bigDecimal' | 'offsetDateTime' | 'zonedDateTime' | 'instant' | 'localDateTime' | 'localDate'
  | 'unstorable' | 'other';

const KIND_BY_TYPE: ReadonlyMap<string, ListQueryKind> = new Map<string, ListQueryKind>([
  ['UUID', 'uuid'], ['java.util.UUID', 'uuid'],
  ['String', 'string'], ['java.lang.String', 'string'],
  ['Long', 'long'], ['long', 'long'], ['java.lang.Long', 'long'],
  ['Integer', 'int'], ['int', 'int'], ['java.lang.Integer', 'int'],
  ['Short', 'short'], ['short', 'short'], ['java.lang.Short', 'short'],
  ['Byte', 'byte'], ['byte', 'byte'], ['java.lang.Byte', 'byte'],
  ['Boolean', 'bool'], ['boolean', 'bool'], ['java.lang.Boolean', 'bool'],
  ['Float', 'float'], ['float', 'float'], ['java.lang.Float', 'float'],
  ['Double', 'double'], ['double', 'double'], ['java.lang.Double', 'double'],
  ['BigDecimal', 'bigDecimal'], ['java.math.BigDecimal', 'bigDecimal'],
  ['OffsetDateTime', 'offsetDateTime'], ['java.time.OffsetDateTime', 'offsetDateTime'],
  ['ZonedDateTime', 'zonedDateTime'], ['java.time.ZonedDateTime', 'zonedDateTime'],
]);

/** JDK types the generated repository has no column encoding for, in both spellings. */
const UNSTORABLE_TYPES: ReadonlySet<string> = new Set(
  [
    'java.math.BigInteger', 'java.lang.Character', 'java.lang.Object', 'java.time.LocalTime',
    'java.time.OffsetTime', 'java.time.Duration', 'java.time.Period', 'java.time.Year',
    'java.time.YearMonth', 'java.time.MonthDay', 'java.time.ZoneId', 'java.time.ZoneOffset',
    'java.util.Date', 'java.util.Currency', 'java.util.Locale', 'java.net.URI', 'java.net.URL',
  ].flatMap((t) => [t, t.slice(t.lastIndexOf('.') + 1)]),
);

export function listQueryKind(type: string): ListQueryKind {
  for (const prefix of ['List<', 'java.util.List<']) {
    if (type.startsWith(prefix) && type.endsWith('>')) return 'list';
  }
  // Any other parameterised type or an array: its arguments or component may name a recognised type
  // (`Map<String, LocalDate>`, `Instant[]`), which the containment checks below would match.
  if (type.includes('<') || type.endsWith('[]')) return 'unstorable';
  const scalar = KIND_BY_TYPE.get(type);
  if (scalar) return scalar;
  if (type === 'char' || UNSTORABLE_TYPES.has(type)) return 'unstorable';
  if (type.includes('Instant')) return 'instant';
  // LocalDateTime before LocalDate: "LocalDateTime" contains "LocalDate".
  if (type.includes('LocalDateTime')) return 'localDateTime';
  if (type.includes('LocalDate')) return 'localDate';
  return 'other';
}

/** The kinds a filter value parses into. `other` is filterable only when it is an enum. */
const SCALAR_FILTER_KINDS: ReadonlySet<ListQueryKind> = new Set<ListQueryKind>([
  'uuid', 'string', 'long', 'int', 'short', 'byte', 'bool', 'float', 'double', 'bigDecimal', 'localDate',
]);

/** The kinds `sort` orders by. `other` is sortable only when it is an enum. */
const SCALAR_SORT_KINDS: ReadonlySet<ListQueryKind> = new Set<ListQueryKind>([
  'uuid', 'string', 'long', 'int', 'short', 'byte', 'bool', 'float', 'double', 'bigDecimal',
  'instant', 'localDateTime', 'offsetDateTime', 'zonedDateTime', 'localDate',
]);

/**
 * Whether a field of this type may be an enum: an unrecognised type, or a simple JDK name an
 * application's own enum can shadow — never a parameterised type or an array.
 */
function enumCandidate(type: string, kind: ListQueryKind): boolean {
  return kind === 'other' || (kind === 'unstorable' && !type.includes('<') && !type.endsWith('[]'));
}

/** One property the list route sorts or filters on. */
export interface ListQueryProperty {
  /** The query-parameter name of a filter, or the property a sort names. */
  readonly name: string;
  /** The metadata type string; `java.util.UUID` for a foreign key. */
  readonly type: string;
  readonly kind: ListQueryKind;
  /** The entity field the property is, absent for a foreign key no field carries. */
  readonly field?: FieldMetadata;
  /** The `MANY_TO_ONE` relationship whose foreign key the property is, when a field does not carry it. */
  readonly relationship?: RelationshipMetadata;
  /** For a field of kind `other`: whether it is an enum, which is what makes it filterable. */
  readonly enumeration?: boolean;
}

/** `customer` and `customerId` both → `customer`: the relationship name with a trailing `Id` removed. */
export function foreignKeyBase(relationshipName: string): string {
  return relationshipName.length > 2 && relationshipName.endsWith('Id')
    ? relationshipName.slice(0, -2)
    : relationshipName;
}

/** The filter parameter of a `MANY_TO_ONE` relationship: `<base>Id`. */
export function foreignKeyParam(relationshipName: string): string {
  return `${foreignKeyBase(relationshipName)}Id`;
}

function byName(a: { name: string }, b: { name: string }): number {
  return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
}

/**
 * The fields that are never a sort key or a filter: the primary key, the owning tenant and the
 * shared-scope field, the audit, version and soft-delete fields of an entity whose flags switch them
 * on — under the declared name, else the canonical one — and every name `systemFields` declares for
 * a role. `ListQuerySupport.systemFieldNames`, rule for rule.
 */
export function listQuerySystemFieldNames(metadata: DomainMetadata): ReadonlySet<string> {
  const declared = metadata.systemFields;
  const role = (name: string | undefined, fallback: string): string =>
    name === undefined || name.trim() === '' ? fallback : name;
  const names = new Set<string>(['id']);
  if (isTenantPartitioned(metadata)) names.add(role(declared?.tenantIdField, 'tenantId'));
  if (effectiveDataScope(metadata) === 'UNIVERSE' && declared?.sharedScopeField?.trim()) {
    names.add(declared.sharedScopeField);
  }
  if (metadata.audited) {
    names.add(role(declared?.createdAtField, 'createdAt'));
    names.add(role(declared?.createdByField, 'createdBy'));
    names.add(role(declared?.updatedAtField, 'updatedAt'));
    names.add(role(declared?.updatedByField, 'updatedBy'));
  }
  if (metadata.versioned) names.add(role(declared?.versionField, 'version'));
  if (metadata.softDelete) {
    names.add(role(declared?.softDeleteField, 'deleted'));
    names.add(role(declared?.softDeleteTimestampField, 'deletedAt'));
    names.add(role(declared?.softDeletedByField, 'deletedBy'));
  }
  if (declared) {
    for (const name of [
      declared.primaryKeyField, declared.createdAtField, declared.createdByField, declared.updatedAtField,
      declared.updatedByField, declared.tenantIdField, declared.versionField, declared.softDeleteField,
      declared.softDeleteTimestampField, declared.softDeletedByField, declared.sharedScopeField,
    ]) {
      if (name !== undefined && name.trim() !== '') names.add(name);
    }
  }
  return names;
}

/**
 * The properties `sort` accepts: every field the metadata marks sortable whose type is of a sort kind
 * or an enum, except a system field. `ListQuerySupport.sortable`.
 */
export function sortableProperties(
  metadata: DomainMetadata,
  isEnum: (field: FieldMetadata) => boolean = (field) => field.enumType !== undefined,
): ListQueryProperty[] {
  const system = listQuerySystemFieldNames(metadata);
  const found = new Map<string, ListQueryProperty>();
  for (const field of metadata.fields ?? []) {
    const kind = listQueryKind(field.type);
    const enumeration = enumCandidate(field.type, kind) && isEnum(field);
    if (field.sortable && (SCALAR_SORT_KINDS.has(kind) || enumeration)
        && !system.has(field.name) && !found.has(field.name)) {
      found.set(field.name, { name: field.name, type: field.type, kind, field, ...(enumeration ? { enumeration } : {}) });
    }
  }
  return [...found.values()].sort(byName);
}

/**
 * The filter parameters: every field the metadata marks filterable whose type is a filterable
 * scalar or an enum, then `<base>Id` for every `MANY_TO_ONE` relationship. A reserved name and a
 * system field are never a filter; a foreign key whose name a field already took is that field.
 * `ListQuerySupport.filters`.
 *
 * `isEnum` answers whether an enum candidate (`enumCandidate`) is an enum. The Java side accepts every such
 * type and reads it back through its `valueOf(String)`, which only an enum has; here only a field
 * the front knows to be an enum is offered, so the front never sends a filter the server's parse
 * would not hold.
 */
export function filterProperties(
  metadata: DomainMetadata,
  isEnum: (field: FieldMetadata) => boolean = (field) => field.enumType !== undefined,
): ListQueryProperty[] {
  const system = listQuerySystemFieldNames(metadata);
  const found = new Map<string, ListQueryProperty>();
  for (const field of metadata.fields ?? []) {
    const kind = listQueryKind(field.type);
    const enumeration = enumCandidate(field.type, kind) && isEnum(field);
    if (field.filterable && (SCALAR_FILTER_KINDS.has(kind) || enumeration)
        && !RESERVED_PARAMS.includes(field.name) && !system.has(field.name) && !found.has(field.name)) {
      found.set(field.name, { name: field.name, type: field.type, kind, field, ...(enumeration ? { enumeration } : {}) });
    }
  }
  for (const relationship of metadata.relationships ?? []) {
    if (relationship.type !== 'MANY_TO_ONE') continue;
    const name = foreignKeyParam(relationship.name);
    if (!RESERVED_PARAMS.includes(name) && !system.has(name) && !found.has(name)) {
      found.set(name, { name, type: 'java.util.UUID', kind: 'uuid', relationship });
    }
  }
  return [...found.values()].sort(byName);
}
