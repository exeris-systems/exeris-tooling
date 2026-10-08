/**
 * TypeScript Type/Interface Generator
 * Generates TypeScript interfaces and Zod schemas from domain metadata.
 */

import { outPath } from '../../core/paths.js';
import { primaryKeyField } from '../../core/primary-key.js';
import { ownerFieldName, type DomainMetadata, type FieldMetadata } from '../../models/domain-model.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import { modelTypeName } from '../../models/model-naming.js';
import type { GeneratorConfig } from '../../config.js';
import type { CodeGenerator, GeneratedFile, GeneratorContext } from '../../core/generator-registry.js';
import type { BackendType } from '../../core/backend-strategy.js';
import { filterProperties } from '../angular/list-query.js';
import { fileHeaderLines } from '../file-header.js';

export { GeneratedFile };

export class TypeGenerator implements CodeGenerator {
  readonly name = 'TypeGenerator';
  readonly artifactType = 'TYPE' as const;
  readonly supportedBackends: BackendType[] = [];
  readonly priority = 2; // After enums

  generate(domain: DomainMetadata, context: GeneratorContext): GeneratedFile | null {
    const content = this.generateInterface(domain, context);
    const kebabName = DslMapper.toKebabCase(domain.entityName);

    return {
      path: outPath('types', `${kebabName}.types.ts`),
      content,
      artifactType: 'TYPE',
      overwritable: true,
    };
  }

  generateAggregate(domains: DomainMetadata[], context: GeneratorContext): GeneratedFile[] {
    const files: GeneratedFile[] = [];

    // Generate Zod schemas if enabled
    if (context.config.generateZod) {
      for (const domain of domains) {
        files.push(this.generateZodSchema(domain, context));
      }
    }

    // Generate barrel export for types
    files.push({
      path: 'types/index.ts',
      content: this.generateTypesBarrel(domains),
      artifactType: 'TYPE',
      overwritable: true,
    });

    // Generate barrel export for schemas. Enum schemas live in the enum module, so with no entity
    // the barrel would re-export nothing.
    if (context.config.generateZod && domains.length > 0) {
      files.push({
        path: 'schemas/index.ts',
        content: this.generateSchemasBarrel(domains),
        artifactType: 'SCHEMA',
        overwritable: true,
      });
    }

    return files;
  }

  private generateInterface(metadata: DomainMetadata, context: GeneratorContext): string {
    const interfaceName = modelTypeName(metadata.entityName);

    // Collect enum types used in fields
    const enumTypes = collectEnumTypes(metadata.fields);

    const lines: string[] = [
      ...fileHeaderLines({ title: `${metadata.displayName ?? metadata.entityName} Interface` }),
      ``,
    ];

    // Add enum imports if needed
    if (enumTypes.length > 0) {
      lines.push(`import { ${enumTypes.join(', ')} } from './enums';`);
      lines.push(``);
    }

    // Main interface
    lines.push(`export interface ${interfaceName} {`);
    for (const field of metadata.fields) {
      const mapping = DslMapper.mapType(field.type);
      const optional = !field.required ? '?' : '';
      const comment = field.description ? ` // ${field.description}` : '';
      lines.push(`  ${field.name}${optional}: ${mapping.tsType};${comment}`);
    }
    lines.push(`}`);
    lines.push(``);

    const createFields = createDtoFields(metadata);

    lines.push(`export interface ${interfaceName}Create {`);
    for (const field of createFields) {
      const mapping = DslMapper.mapType(field.type);
      const optional = !field.required ? '?' : '';
      lines.push(`  ${field.name}${optional}: ${mapping.tsType};`);
    }
    lines.push(`}`);
    lines.push(``);

    // Update DTO: the whole record, which the server's update writes back in full
    lines.push(...updateDtoDeclaration(interfaceName, metadata));
    lines.push(``);

    // Filter type: the list route's equality filters (ADR-096), the keys the service's filter has.
    const enums = context.enums ?? [];
    const filters = filterProperties(
      metadata,
      (field) => field.enumType !== undefined || enums.some((e) => e.qualifiedName === field.type || e.name === field.type),
    );
    if (filters.length > 0) {
      lines.push(`export interface ${interfaceName}Filter {`);
      for (const filter of filters) {
        const tsType = filter.field ? DslMapper.mapType(filter.field.type).tsType : 'string';
        lines.push(`  ${filter.name}?: ${tsType};`);
      }
      lines.push(`}`);
      lines.push(``);
    }

    // List response type
    lines.push(`export interface ${interfaceName}ListResponse {`);
    lines.push(`  content: ${interfaceName}[];`);
    lines.push(`  totalElements: number;`);
    lines.push(`  totalPages: number;`);
    lines.push(`  size: number;`);
    lines.push(`  number: number;`);
    lines.push(`  first: boolean;`);
    lines.push(`  last: boolean;`);
    lines.push(`}`);

    return lines.join('\n');
  }

  private generateZodSchema(metadata: DomainMetadata, _context: GeneratorContext): GeneratedFile {
    const interfaceName = modelTypeName(metadata.entityName);
    const kebabName = DslMapper.toKebabCase(metadata.entityName);

    // Collect enum types used in fields
    const enumTypes = collectEnumTypes(metadata.fields);
    const enumSchemaImports = enumTypes.map(e => `${e}Schema`);

    const lines: string[] = [
      ...fileHeaderLines({ title: `${metadata.displayName ?? metadata.entityName} Zod Schemas` }),
      ``,
      `import { z } from 'zod';`,
    ];

    // Add enum schema imports if needed
    if (enumSchemaImports.length > 0) {
      lines.push(`import { ${enumSchemaImports.join(', ')} } from '../types/enums';`);
    }
    lines.push(``);

    // Main schema
    lines.push(`export const ${interfaceName}Schema = z.object({`);
    for (const field of metadata.fields) {
      const zodType = buildZodType(field);
      lines.push(`  ${field.name}: ${zodType},`);
    }
    lines.push(`});`);
    lines.push(``);

    // Create schema (without system fields). Only omit system fields that are
    // actually keys of the schema above — `z.omit()` rejects absent keys at the
    // type level (TS2322: `true` not assignable to `never`), so omitting a system
    // field the entity doesn't declare breaks the generated schema's compile (T20).
    const presentFields = new Set(metadata.fields.map((f) => f.name));
    const omitKeys = systemFieldNames(metadata).filter((sf) => presentFields.has(sf));

    lines.push(`export const ${interfaceName}CreateSchema = ${interfaceName}Schema.omit({`);
    for (const sf of omitKeys) {
      lines.push(`  ${sf}: true,`);
    }
    lines.push(`});`);
    lines.push(``);

    // Update schema
    lines.push(updateSchemaDeclaration(interfaceName, metadata));

    return {
      path: outPath('schemas', `${kebabName}.schema.ts`),
      content: lines.join('\n'),
      artifactType: 'SCHEMA',
      overwritable: true,
    };
  }

  private generateTypesBarrel(domains: DomainMetadata[]): string {
    const lines: string[] = [
      ...fileHeaderLines({ title: 'Type Definitions - Barrel Export', doNotEdit: 'omit' }),
      ``,
      `export * from './enums';`,
    ];

    for (const domain of domains) {
      const kebab = DslMapper.toKebabCase(domain.entityName);
      lines.push(`export * from './${kebab}.types';`);
    }

    return lines.join('\n');
  }

  private generateSchemasBarrel(domains: DomainMetadata[]): string {
    const lines: string[] = [
      ...fileHeaderLines({ title: 'Zod Schemas - Barrel Export', doNotEdit: 'omit' }),
      ``,
    ];

    for (const domain of domains) {
      const kebab = DslMapper.toKebabCase(domain.entityName);
      lines.push(`export * from './${kebab}.schema';`);
    }

    return lines.join('\n');
  }

}

/**
 * The Zod expression for one field: the base mapping plus whatever validations the
 * metadata declares. Shared with the peer emitter (T42) so a peer's schema and this app's
 * own are produced by one implementation — a second copy is a parity bug waiting to happen.
 */
export function buildZodType(field: FieldMetadata): string {
  const baseMapping = DslMapper.mapType(field.type);
  // A boxed type maps to `….nullable()`. Zod's nullable wrapper has no `min` / `max`, so the
  // constraints go on the inner schema and the wrapper is put back after them.
  const NULLABLE = '.nullable()';
  const nullable = baseMapping.zodType.endsWith(NULLABLE);
  let zodType = nullable ? baseMapping.zodType.slice(0, -NULLABLE.length) : baseMapping.zodType;

  // Apply validations
  if (field.minLength && zodType.includes('z.string')) {
    zodType = zodType.replace('z.string()', `z.string().min(${field.minLength})`);
  }
  if (field.maxLength && zodType.includes('z.string')) {
    zodType += `.max(${field.maxLength})`;
  }
  if (field.min !== undefined && zodType.includes('z.number')) {
    zodType += `.min(${field.min})`;
  }
  if (field.max !== undefined && zodType.includes('z.number')) {
    zodType += `.max(${field.max})`;
  }
  if (nullable) {
    zodType += NULLABLE;
  }
  if (field.format === 'email') {
    zodType = 'z.string().email()';
  }
  if (field.format === 'url') {
    zodType = 'z.string().url()';
  }
  if (field.pattern) {
    zodType = `z.string().regex(/${field.pattern}/)`;
  }

  // Optional handling
  if (!field.required) {
    zodType += '.optional()';
  }

  return zodType;
}

/**
 * The fields the server owns: the id, plus whatever the entity's `systemFields` block
 * declares (or the `version`/`createdAt`/`updatedAt` default when it declares none), plus the
 * owner of a tenant-partitioned entity.
 *
 * The owner is `ownerFieldName`: the generated repository stamps the bound tenant, answers 400 to
 * another one and never updates it, and the emitted OpenAPI marks it read-only and leaves it out of
 * both DTOs (ADR-090). A GLOBAL entity has no owner, so a field it declares as `tenantId` is
 * writable even when its block names `tenantIdField`, as it is in the OpenAPI.
 *
 * A UNIVERSE entity's `sharedScopeField` is server-owned exactly like its `tenantIdField`: the
 * generated repository stamps it from the bound storage context, and the emitted OpenAPI marks it
 * read-only, so the create/update DTOs never carry it.
 */
export function systemFieldNames(metadata: DomainMetadata): string[] {
  const fields = [primaryKeyField(metadata)];
  const sf = metadata.systemFields;
  const owner = ownerFieldName(metadata);

  if (sf) {
    if (sf.versionField) fields.push(sf.versionField);
    if (sf.createdAtField) fields.push(sf.createdAtField);
    if (sf.updatedAtField) fields.push(sf.updatedAtField);
    if (sf.createdByField) fields.push(sf.createdByField);
    if (sf.updatedByField) fields.push(sf.updatedByField);
    if (owner) fields.push(owner);
    if (sf.softDeleteField) fields.push(sf.softDeleteField);
    if (sf.softDeleteTimestampField) fields.push(sf.softDeleteTimestampField);
    if (sf.softDeletedByField) fields.push(sf.softDeletedByField);
    if (sf.sharedScopeField) fields.push(sf.sharedScopeField);
  } else {
    // Default system fields
    fields.push('version', 'createdAt', 'updatedAt');
    if (owner) fields.push(owner);
  }

  return [...new Set(fields)];
}

/**
 * The audit stamp names: `systemFields.createdAtField` / `updatedAtField`, or `createdAt` /
 * `updatedAt` when the entity names none (a blank name falls back too). These are the properties
 * the generated repository stamps on every row of an `audited` entity, whether or not the entity
 * declares them as fields.
 */
export function auditFieldNames(metadata: DomainMetadata): { createdAt: string; updatedAt: string } {
  const sf = metadata.systemFields;
  return {
    createdAt: sf?.createdAtField || 'createdAt',
    updatedAt: sf?.updatedAtField || 'updatedAt',
  };
}

/**
 * The fields the emitted views treat as system fields: no form control, no row in the detail
 * view's field table, no default list column. The detail view's system panel shows the id, the
 * audit stamps and the version instead.
 *
 * It is `systemFieldNames` plus what the flags make server-owned (the audit stamps on `audited`,
 * the version on `versioned`, the soft-delete flag on `softDelete`), plus `tenantId`, which the
 * views never render whatever the block says. An entity without a `systemFields` block also keeps
 * the conventional names (`createdBy`, `updatedBy`, `deleted`, `deletedAt`), since without a block
 * nothing names its system fields otherwise. The order is stable: `systemFieldNames` first, then
 * the additions in the order listed.
 */
export function viewSystemFieldNames(metadata: DomainMetadata): string[] {
  const names = systemFieldNames(metadata);
  if (metadata.audited) {
    const audit = auditFieldNames(metadata);
    names.push(audit.createdAt, audit.updatedAt);
  }
  const version = updateVersionField(metadata);
  if (version) names.push(version.name);
  if (metadata.softDelete) names.push(metadata.systemFields?.softDeleteField || 'deleted');
  names.push('tenantId');
  if (!metadata.systemFields) names.push('createdBy', 'updatedBy', 'deleted', 'deletedAt');
  return [...new Set(names)];
}

/** The optimistic-lock field a versioned entity's update carries. */
export interface UpdateVersionField {
  /** The property name: `systemFields.versionField`, or `version` when the entity names none. */
  name: string;
  /** The TS type, mirroring the entity interface's type for the field (`number` when undeclared). */
  tsType: string;
  /** The Zod expression, mirroring the entity schema's base type for the field (no `.optional()`). */
  zodType: string;
  /** Whether the entity declares the field, i.e. whether the entity interface carries it. */
  declared: boolean;
}

/**
 * The version an update of a `versioned` entity must send, or `undefined` for an unversioned one.
 *
 * The generated repository reads the version in the PUT body as the version the edit was loaded
 * at, increments it, and matches `WHERE version = ?`; zero rows is a 409. The server owns the
 * initial version, so the create DTO never carries the field, but the update DTO must: without it
 * the server takes the expected version as 0 and every update after the first is refused.
 */
export function updateVersionField(metadata: DomainMetadata): UpdateVersionField | undefined {
  if (!metadata.versioned) return undefined;
  const name = metadata.systemFields?.versionField || 'version';
  const field = metadata.fields.find((f) => f.name === name);
  if (!field) return { name, tsType: 'number', zodType: 'z.number()', declared: false };
  const mapping = DslMapper.mapType(field.type);
  return { name, tsType: mapping.tsType, zodType: mapping.zodType, declared: true };
}

/**
 * The declared fields an update body leaves out: the key, which the route's path carries and which
 * the generated handler sets over whatever the body says, and the owner of a tenant-partitioned
 * entity, which the generated repository never writes on update. Only declared fields are listed,
 * because `z.omit()` rejects a key the object does not declare (TS2322).
 */
export function updateOmittedFields(metadata: DomainMetadata): string[] {
  const declared = new Set(metadata.fields.map((f) => f.name));
  const owner = ownerFieldName(metadata);
  const key = primaryKeyField(metadata);
  return [...new Set(owner ? [key, owner] : [key])].filter((name) => declared.has(name));
}

/**
 * The `…Update` type declaration: the entity record without the key and the owner, plus the
 * required version on a versioned entity. Shared by the local and the peer emitter so the two
 * cannot drift.
 *
 * The generated server's update is a full replacement: the handler decodes the body into the whole
 * entity and the repository's `UPDATE` writes every column but the key and the owner, read-only
 * fields, the audit `createdAt` and a UNIVERSE entity's shared scope included. A property the body
 * leaves out is written as null, so the update type is the record as read, not a subset of the
 * create DTO.
 */
export function updateDtoDeclaration(typeName: string, metadata: DomainMetadata): string[] {
  const omitted = updateOmittedFields(metadata);
  const record = omitted.length > 0
    ? `Omit<${typeName}, ${omitted.map((name) => `'${name}'`).join(' | ')}>`
    : typeName;
  const version = updateVersionField(metadata);
  if (!version) {
    return [
      `/** The whole record: the server's update replaces the row, and a property left out is stored as null. */`,
      `export type ${typeName}Update = ${record};`,
    ];
  }
  return [
    `/** The whole record and the version this edit was loaded at: the server's update replaces the row, and refuses it with 409 when the row has moved on. */`,
    `export type ${typeName}Update = ${record} & { ${version.name}: ${version.tsType} };`,
  ];
}

/**
 * The `…UpdateSchema` declaration, kept in step with `updateDtoDeclaration`: the entity schema
 * without the key and the owner, extended with the required version on a versioned entity.
 */
export function updateSchemaDeclaration(typeName: string, metadata: DomainMetadata): string {
  const omitted = updateOmittedFields(metadata);
  const record = omitted.length > 0
    ? `${typeName}Schema.omit({ ${omitted.map((name) => `${name}: true`).join(', ')} })`
    : `${typeName}Schema`;
  const version = updateVersionField(metadata);
  return version
    ? `export const ${typeName}UpdateSchema = ${record}.extend({ ${version.name}: ${version.zodType} });`
    : `export const ${typeName}UpdateSchema = ${record};`;
}

/**
 * Collects enum type names from fields that reference enums.
 * Detects enum types by checking if the field type is not a known Java type.
 */
export function collectEnumTypes(fields: FieldMetadata[]): string[] {
  const enumTypes = new Set<string>();

  // Known Java types that should NOT be treated as enums
  const knownJavaTypes = new Set([
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

  for (const field of fields) {
    const javaType = field.type;

    // Check if field has explicit enumType metadata
    if (field.enumType) {
      const simpleName = field.enumType.includes('.')
        ? field.enumType.substring(field.enumType.lastIndexOf('.') + 1)
        : field.enumType;
      enumTypes.add(simpleName);
      continue;
    }

    // Skip known Java types
    if (knownJavaTypes.has(javaType)) {
      continue;
    }

    // Skip generics (List, Map, Set, etc.)
    if (javaType.includes('<') || javaType.endsWith('[]')) {
      continue;
    }

    // Extract simple name from qualified name
    const simpleName = javaType.includes('.')
      ? javaType.substring(javaType.lastIndexOf('.') + 1)
      : javaType;

    // Skip if simple name is a known type
    if (knownJavaTypes.has(simpleName)) {
      continue;
    }

    // If it's a PascalCase name that's not a known type, it's likely an enum or entity
    // For now, we assume types ending with common enum suffixes or in "domain" package are enums
    if (/^[A-Z][a-zA-Z0-9]*$/.test(simpleName)) {
      // Check if it looks like an enum (typically short names or ends with Role, Status, Type, Plan, etc.)
      if (simpleName.endsWith('Role') || simpleName.endsWith('Status') ||
          simpleName.endsWith('Type') || simpleName.endsWith('Plan') ||
          simpleName.endsWith('State') || simpleName.endsWith('Level') ||
          simpleName.endsWith('Kind') || simpleName.endsWith('Mode') ||
          simpleName.endsWith('Category') || javaType.includes('.domain.')) {
        enumTypes.add(simpleName);
      }
    }
  }

  return [...enumTypes];
}

/**
 * The fields a create DTO carries: everything the server does not own and the entity does
 * not mark `inCreate: false`. The lifecycle list is the set an Exeris entity gets from the
 * platform rather than from its own declaration.
 */
export function createDtoFields(metadata: DomainMetadata): FieldMetadata[] {
  const system = systemFieldNames(metadata);
  const lifecycle = ['active', 'onboardingStatus', 'onboardingStartedAt', 'onboardingCompletedAt', 'hierarchyLevel', 'createdAt', 'updatedAt', 'deleted', 'version', 'parentTenantId'];
  return metadata.fields.filter(
    (f) => !system.includes(f.name) && !lifecycle.includes(f.name) && f.inCreate !== false,
  );
}

export function generateTypes(metadata: DomainMetadata, config: GeneratorConfig): GeneratedFile[] {
  const generator = new TypeGenerator();
  const context: GeneratorContext = { config, backend: config.backend ?? 'KERNEL', allDomains: [metadata], enums: [] };
  const file = generator.generate(metadata, context);
  return file ? [file] : [];
}

