/**
 * The types a field or an action parameter names beyond the mapping table: another entity, an
 * enum, or a type the metadata does not describe.
 *
 * `DslMapper.mapType` maps the JDK types it has a rule for; every other type it reaches (the field
 * type itself, the element of a collection or an array, the value of a map, the content of an
 * optional) is resolved here, against the app's entities and the enums its enum module declares:
 *
 * - **an entity** is that entity's read type (`modelTypeName`), and its Zod schema a lazy reference
 *   to the entity's schema, typed by the read type so that two entities may reference each other,
 *   or one itself. The server sends the field as the nested object: the generated application
 *   serialises the entity record through the JSON codec, and a `List` column is that same JSON;
 * - **an enum** keeps the mapper's fallback, the enum's simple name and its `<Enum>Schema`, both
 *   exported by the enum module;
 * - **any other type** is `unknown` and `z.unknown()`. The metadata carries no shape for it, and
 *   what the JSON codec writes for it depends on the type's own serialisation (an object of its
 *   properties, or one value), which no type name tells. `Object` is the TypeScript `Object`, with
 *   a Zod schema that admits any present value.
 *
 * The emitters that print a field's type take both the type and the imports it needs from here, so
 * a module imports exactly the names its types reference.
 */

import type { EnumMetadata, GeneratorContext } from './generator-registry.js';
import type { DomainMetadata, FieldMetadata } from '../models/domain-model.js';
import { DslMapper, type TypeMapping, type UnmappedTypeResolver } from '../models/dsl-mapper.js';
import { modelTypeName } from '../models/model-naming.js';

/** An enum the app's enum module (`types/enums.ts`) declares, as far as type resolution needs it. */
export interface DeclaredEnum {
  readonly name: string;
  readonly qualifiedName: string;
}

/** What a type is resolved against. */
export interface TypeScope {
  /** The app's entities: a type naming one of them is that entity. */
  readonly domains: readonly DomainMetadata[];
  /**
   * The enums the app's enum module declares, all of them: a type none of them names is not an
   * enum. `undefined` when the caller does not hold that list; a type is then taken for an enum by
   * its name (`enumNameByConvention`).
   */
  readonly enums?: readonly DeclaredEnum[];
}

/** The app's entities and every enum its enum module declares, as a generator is handed them. */
export interface AppTypes {
  readonly domains: DomainMetadata[];
  readonly enums: EnumMetadata[];
}

/** The scope a generator's context resolves types in. */
export function typeScopeOf(context: Pick<GeneratorContext, 'allDomains' | 'enums' | 'enumsComplete'>): TypeScope {
  return { domains: context.allDomains, enums: context.enumsComplete ? context.enums : undefined };
}

/** A type outside the mapping table, resolved. */
export type TypeReference =
  | { readonly kind: 'entity'; readonly domain: DomainMetadata }
  | { readonly kind: 'enum'; readonly name: string }
  | { readonly kind: 'opaque' };

/**
 * The Java types that map to a built-in TypeScript type: never an enum, never an entity. Both
 * spellings of each, as the processor writes a type qualified and hand-built metadata often simple.
 */
export const KNOWN_JAVA_TYPES: ReadonlySet<string> = new Set([
  'String', 'java.lang.String',
  'Integer', 'java.lang.Integer', 'int',
  'Long', 'java.lang.Long', 'long',
  'Double', 'java.lang.Double', 'double',
  'Float', 'java.lang.Float', 'float',
  'Short', 'java.lang.Short', 'short',
  'Byte', 'java.lang.Byte', 'byte',
  'Boolean', 'java.lang.Boolean', 'boolean',
  'BigDecimal', 'java.math.BigDecimal',
  'BigInteger', 'java.math.BigInteger',
  'UUID', 'java.util.UUID',
  'Instant', 'java.time.Instant',
  'LocalDate', 'java.time.LocalDate',
  'LocalDateTime', 'java.time.LocalDateTime',
  'LocalTime', 'java.time.LocalTime',
  'ZonedDateTime', 'java.time.ZonedDateTime',
  'OffsetDateTime', 'java.time.OffsetDateTime',
  'Duration', 'java.time.Duration',
  'Period', 'java.time.Period',
  'Date', 'java.util.Date',
  'byte[]', 'Object', 'java.lang.Object',
]);

/** `java.lang.Object` in both spellings. */
const JAVA_OBJECT: ReadonlySet<string> = new Set(['Object', 'java.lang.Object']);

/** The simple name of a type: what follows its last dot. */
export function simpleTypeName(javaType: string): string {
  return javaType.includes('.') ? javaType.substring(javaType.lastIndexOf('.') + 1) : javaType;
}

/** The suffixes `enumNameByConvention` reads as an enum's. */
const ENUM_NAME_SUFFIXES = ['Role', 'Status', 'Type', 'Plan', 'State', 'Level', 'Kind', 'Mode', 'Category'];

/**
 * The enum a type is taken for when the caller holds no enum list: the simple name of a PascalCase
 * type that is not a known JDK type, is neither parameterised nor an array, and either ends in one
 * of `ENUM_NAME_SUFFIXES` or lives in a `.domain.` package. `undefined` for any other type.
 */
export function enumNameByConvention(javaType: string): string | undefined {
  if (KNOWN_JAVA_TYPES.has(javaType) || javaType.includes('<') || javaType.endsWith('[]')) {
    return undefined;
  }
  const simpleName = simpleTypeName(javaType);
  if (KNOWN_JAVA_TYPES.has(simpleName) || !/^[A-Z][a-zA-Z0-9]*$/.test(simpleName)) {
    return undefined;
  }
  return ENUM_NAME_SUFFIXES.some((suffix) => simpleName.endsWith(suffix)) || javaType.includes('.domain.')
    ? simpleName
    : undefined;
}

/**
 * The entity a type names: a qualified type the entity's package and name spell, or a simple type
 * the entity's name is. A qualified type in another package is another class of that name.
 */
export function entityNamed(javaType: string, domains: readonly DomainMetadata[]): DomainMetadata | undefined {
  if (!javaType.includes('.')) {
    return domains.find((d) => d.entityName === javaType);
  }
  return domains.find((d) => (d.packageName ? `${d.packageName}.${d.entityName}` : d.entityName) === javaType);
}

/** The enum a type names among the declared ones: a qualified type by its qualified name, a simple one by its name. */
function declaredEnumNamed(javaType: string, enums: readonly DeclaredEnum[]): string | undefined {
  const known = javaType.includes('.')
    ? enums.find((e) => e.qualifiedName === javaType)
    : enums.find((e) => e.name === javaType);
  return known?.name;
}

/**
 * What a type the mapping table does not name is. A parameterised type the mapper has no rule for
 * (`Page<Order>`) is opaque, whatever its arguments are.
 */
export function resolveTypeReference(javaType: string, scope: TypeScope): TypeReference {
  if (javaType.includes('<') || javaType.includes('[')) {
    return { kind: 'opaque' };
  }
  const domain = entityNamed(javaType, scope.domains);
  if (domain) {
    return { kind: 'entity', domain };
  }
  const enumName = scope.enums ? declaredEnumNamed(javaType, scope.enums) : enumNameByConvention(javaType);
  return enumName ? { kind: 'enum', name: enumName } : { kind: 'opaque' };
}

/**
 * The mapper's resolver for `scope`. `onReference` is told of every type it resolves, in the
 * order `DslMapper.mapType` reaches them.
 */
export function typeResolver(scope: TypeScope, onReference?: (reference: TypeReference) => void): UnmappedTypeResolver {
  return (javaType) => {
    const reference = resolveTypeReference(javaType, scope);
    onReference?.(reference);
    switch (reference.kind) {
      case 'entity': {
        const typeName = modelTypeName(reference.domain.entityName);
        return { tsType: typeName, zodType: `z.lazy((): z.ZodType<${typeName}> => ${typeName}Schema)` };
      }
      case 'opaque':
        return JAVA_OBJECT.has(javaType)
          ? { tsType: 'Object', zodType: 'z.custom<Object>((value) => value !== null && value !== undefined)' }
          : { tsType: 'unknown', zodType: 'z.unknown()' };
      default:
        return undefined;
    }
  };
}

/** `DslMapper.mapType` of `javaType`, resolved against `scope`. */
export function mapScopedType(javaType: string, scope: TypeScope): TypeMapping {
  return DslMapper.mapType(javaType, typeResolver(scope));
}

/** The enums and the entities a set of types references, as a module imports them. */
export interface TypeImports {
  /** The enums' simple names, in the order the types reach them first. */
  readonly enums: readonly string[];
  /** The entities, by entity name in code-unit order, once each. */
  readonly entities: readonly DomainMetadata[];
}

/** The enums and entities `javaTypes` reference when mapped against `scope`. */
export function typeImports(javaTypes: readonly string[], scope: TypeScope): TypeImports {
  const enums = new Set<string>();
  const entities = new Map<string, DomainMetadata>();
  const record = (reference: TypeReference): void => {
    if (reference.kind === 'enum') enums.add(reference.name);
    if (reference.kind === 'entity') entities.set(reference.domain.entityName, reference.domain);
  };
  for (const javaType of javaTypes) {
    DslMapper.mapType(javaType, typeResolver(scope, record));
  }
  return {
    enums: [...enums],
    entities: [...entities.keys()].sort(byCodeUnit).map((name) => entities.get(name)!),
  };
}

/** The import of an entity's read type into a module of the directory `fromDir` names (`'.'`, `'../types'`). */
export function entityTypeImport(domain: DomainMetadata, fromDir: string): string {
  return `import type { ${modelTypeName(domain.entityName)} } from '${fromDir}/${DslMapper.toKebabCase(domain.entityName)}.types';`;
}

/**
 * The type-only imports a module outside `types/` needs for `javaTypes` mapped against `scope`:
 * the enums from the enum module, then each entity's read type, `typesDir` being the path to
 * `types/` from the module. Nothing when the types reference neither.
 */
export function typeOnlyImports(javaTypes: readonly string[], scope: TypeScope, typesDir: string): string[] {
  const imports = typeImports(javaTypes, scope);
  return [
    ...(imports.enums.length > 0 ? [`import type { ${imports.enums.join(', ')} } from '${typesDir}/enums';`] : []),
    ...imports.entities.map((domain) => entityTypeImport(domain, typesDir)),
  ];
}

/**
 * The enums a set of fields references: a field's `enumType` when it carries one, else every enum
 * its type reaches. Field order, first occurrence.
 */
export function fieldEnumImports(fields: readonly FieldMetadata[], scope: TypeScope): string[] {
  const enums = new Set<string>();
  for (const field of fields) {
    if (field.enumType) {
      enums.add(simpleTypeName(field.enumType));
      continue;
    }
    for (const name of typeImports([field.type], scope).enums) enums.add(name);
  }
  return [...enums];
}

/**
 * Whether a type reaches something other than a JDK type or an enum: an entity, or a type the
 * metadata does not describe. Such a value is an object, a list of objects or `unknown` in the
 * DTO, never a string, a number or a boolean.
 */
export function referencesNonScalar(javaType: string, scope: TypeScope): boolean {
  let found = false;
  DslMapper.mapType(javaType, typeResolver(scope, (reference) => {
    if (reference.kind !== 'enum') found = true;
  }));
  return found;
}

/**
 * Whether a type maps to `unknown` because it reaches a type the metadata does not describe
 * (`Money`, `Optional<Money>`). An entity type declares such a field optional even when it is
 * required: `unknown` includes `undefined`, so the type Zod infers for the schema has the key
 * optional, and the entity type must agree with it to be the type a referencing schema's lazy
 * reference is typed by.
 */
export function isUndescribedValue(javaType: string, scope: TypeScope): boolean {
  let opaque = false;
  const mapping = DslMapper.mapType(javaType, typeResolver(scope, (reference) => {
    if (reference.kind === 'opaque') opaque = true;
  }));
  return opaque && (mapping.tsType === 'unknown' || mapping.tsType.startsWith('unknown |'));
}

function byCodeUnit(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
