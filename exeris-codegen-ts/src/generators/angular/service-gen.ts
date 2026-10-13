/**
 * Angular Service Generator
 * Generates Angular 22+ services from domain metadata.
 */

import { outPath } from '../../core/paths.js';
import { primaryKeyField } from '../../core/primary-key.js';
import type { DomainMetadata } from '../../models/index.js';
import { DslMapper, modelTypeName } from '../../models/index.js';
import type { GeneratorConfig } from '../../config.js';
 import type { CodeGenerator, GeneratedFile, GeneratorContext } from '../../core/generator-registry.js';
import type { BackendType } from '../../core/backend-strategy.js';
import { enumTypeOf } from './field-render.js';
import { filterProperties, sortableProperties, type ListQueryProperty } from './list-query.js';
import { fileHeaderLines } from '../file-header.js';
import {
  entityTypeImport,
  fieldEnumImports,
  mapScopedType,
  typeImports,
  typeScopeOf,
  type AppTypes,
  type TypeScope,
} from '../../core/type-references.js';



export { GeneratedFile };

/**
 * The API path an emitted service calls, and the single authority on it.
 *
 * Exported because the generated **service spec** must assert the URL the service actually builds.
 * ADR-058's rule for the Java half — the emitted double and its subject come from one source of
 * truth — has the same trap here: a spec that re-derives the path agrees with the service only
 * until one of them changes, and then it asserts a fiction.
 *
 * Mirrors the SDK's `DomainMetadata.effectivePath()`, which is what the emitted kernel router
 * registers, so client and server agree by construction.
 */
export function serviceApiPath(metadata: DomainMetadata): string {
  return metadata.apiPath ?? metadata.path ?? `/${DslMapper.routePlural(metadata.entityName)}`;
}

export class ServiceGenerator implements CodeGenerator {
  readonly name = 'ServiceGenerator';
  readonly artifactType = 'SERVICE' as const;
  readonly supportedBackends: BackendType[] = [];
  readonly priority = 5;

  generate(domain: DomainMetadata, context: GeneratorContext): GeneratedFile | null {
    const content = this.generateServiceContent(domain, context);
    const fileName = `${DslMapper.toKebabCase(domain.entityName)}.service.ts`;
    const filePath = outPath('services', fileName);

    return {
      path: filePath,
      content,
      artifactType: 'SERVICE',
      overwritable: true,
    };
  }

  generateAggregate(domains: DomainMetadata[], _context: GeneratorContext): GeneratedFile[] {
    return [
      {
        path: 'services/index.ts',
        content: this.generateServicesBarrel(domains),
        artifactType: 'SERVICE',
        overwritable: true,
      },
    ];
  }

  private generateServicesBarrel(domains: DomainMetadata[]): string {
    const lines: string[] = [
      ...fileHeaderLines({ title: 'Services - Barrel Export', doNotEdit: 'omit' }),
      ``,
    ];

    for (const domain of domains) {
      const kebab = DslMapper.toKebabCase(domain.entityName);
      lines.push(`export * from './${kebab}.service';`);
    }

    return lines.join('\n');
  }

  private generateServiceContent(domain: DomainMetadata, context: GeneratorContext): string {
    const metadata = domain;
    const entityName = metadata.entityName;

    // The emitted service must request what the emitted server serves. The router registers the
    // entity's path with no version segment and the OpenAPI document publishes the same, so
    // apiVersion is deliberately NOT folded into the URL here. Without a declared path the
    // fallback is the kebab-cased plural, the segment effectivePath() derives.
    const apiPath = serviceApiPath(metadata);

    // Collect enum types for imports (fields + action params — T20a)
    const scope = typeScopeOf(context);
    const enumTypes = this.collectEnumTypes(metadata, scope);

    // The list route's equality filters and sort keys (ADR-096): exactly what the server reads.
    const filterableFields = filterProperties(metadata, (field) => enumTypeOf(field, context.enums ?? []) !== undefined)
      .map((property) => ({ name: property.name, filterType: this.filterType(property, enumTypes, context) }));
    const sortFields = sortableProperties(metadata, (field) => enumTypeOf(field, context.enums ?? []) !== undefined).map((property) => property.name);

    // T1: actions are served at POST {base}/{id}/actions/{kebab(name)} (matches the
    // OpenAPI path + the kernel route), and the server responds with the updated
    // aggregate — so the client method takes the entity id and returns the entity.
    // A streaming action has no respond-once route: the kernel serves that path as a
    // stream only, and its client is the action stream client.
    const servedActions = (metadata.actions ?? []).filter((action) => !action.streaming);
    // The entities an action parameter is typed by, which the method signatures name; the entity's
    // own type is imported already.
    const entityImports = typeImports(servedActions.flatMap((action) => (action.params ?? []).map((p) => p.type)), scope)
      .entities.filter((entity) => entity.entityName !== entityName)
      .map((entity) => entityTypeImport(entity, '../types'));
    const actions = servedActions.map((action) => ({
      name: action.name,
      description: action.description ?? `Execute ${action.name} action`,
      // methodName: valid camelCase JS identifier (kebab/snake action names would
      // otherwise emit invalid syntax); kebabName: the URL segment, matching the kernel route.
      methodName: DslMapper.toMethodName(action.name),
      kebabName: DslMapper.toKebabCase(action.name),
      hasParams: action.params && action.params.length > 0,
      params: (action.params ?? []).map((p) => ({
        name: p.name,
        tsType: mapScopedType(p.type, scope).tsType,
      })),
    }));

    const systemFields = this.getSystemFields(metadata);

    // Generate service content inline (without template file for reliability)
    return this.renderService({
      entityName,
      apiPath,
      apiBasePath: context.config.apiBasePath,
      generateZod: context.config.generateZod,
      softDelete: metadata.softDelete,
      filterableFields,
      sortFields,
      actions,
      systemFields,
      enumTypes,
      entityImports,
    });
  }

  private renderService(data: Record<string, unknown>): string {
    const { entityName, apiBasePath, apiPath, softDelete, filterableFields, sortFields, actions, enumTypes, entityImports } = data as {
      entityName: string;
      apiBasePath: string;
      apiPath: string;
      generateZod: boolean;
      softDelete: boolean;
      filterableFields: Array<{ name: string; filterType: string }>;
      sortFields: string[];
      actions: Array<{ name: string; description: string; methodName: string; kebabName: string; hasParams: boolean; params: Array<{ name: string; tsType: string }> }>;
      systemFields: string[];
      enumTypes: string[];
      entityImports: string[];
    };

    const modelName = modelTypeName(entityName);
    const kebabName = entityName.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase();
    const lines: string[] = [];

    // Header
    lines.push(...fileHeaderLines({ title: `${entityName} API Service` }));
    lines.push(``);
    lines.push(`import { Injectable, inject } from '@angular/core';`);
    lines.push(`import { HttpClient, HttpParams } from '@angular/common/http';`);
    lines.push(`import { Observable } from 'rxjs';`);
    lines.push(``);

    // Import types from types file
    lines.push(`import type { ${modelName}, ${modelName}Create, ${modelName}Update } from '../types/${kebabName}.types';`);

    // Add enum imports if needed
    if (enumTypes && enumTypes.length > 0) {
      lines.push(`import type { ${enumTypes.join(', ')} } from '../types/enums';`);
    }
    lines.push(...entityImports);
    lines.push(``);

    // Re-export types for convenience
    lines.push(`// Re-export types for convenience`);
    lines.push(`export type { ${modelName}, ${modelName}Create, ${modelName}Update } from '../types/${kebabName}.types';`);
    lines.push(``);

    // Page and PageRequest interfaces (only define once, not duplicated)
    lines.push(`// ============================================================================`);
    lines.push(`// Pagination Types`);
    lines.push(`// ============================================================================`);
    lines.push(``);

    // Page interface
    lines.push(`export interface Page<T> {`);
    lines.push(`  content: T[];`);
    lines.push(`  totalElements: number;`);
    lines.push(`  totalPages: number;`);
    lines.push(`  size: number;`);
    lines.push(`  number: number;`);
    lines.push(`  first: boolean;`);
    lines.push(`  last: boolean;`);
    lines.push(`}`);
    lines.push(``);

    lines.push(`/**`);
    lines.push(` * A page of the list route: \`page\` is zero-based, \`size\` lies within 1..100 (the route serves`);
    lines.push(` * 20 when it is absent), and \`sort\` names one property the route sorts on, ascending unless`);
    lines.push(` * \`direction\` says otherwise. Without \`sort\` the rows come in id order.`);
    lines.push(` */`);
    lines.push(`export interface PageRequest {`);
    lines.push(`  page?: number;`);
    lines.push(`  size?: number;`);
    lines.push(`  sort?: string;`);
    lines.push(`  direction?: 'asc' | 'desc';`);
    lines.push(`}`);
    lines.push(``);

    lines.push(`/** The properties the list route sorts ${entityName} rows on. */`);
    lines.push(`export type ${modelName}SortField = ${sortFields.length > 0 ? sortFields.map((f) => `'${f}'`).join(' | ') : 'never'};`);
    lines.push(``);

    // Filter interface
    lines.push(`/** The list route's equality filters: a row matches when each given property equals its value. */`);
    lines.push(`export interface ${modelName}Filter {`);
    for (const ff of filterableFields) {
      lines.push(`  ${ff.name}?: ${ff.filterType};`);
    }
    lines.push(`}`);
    lines.push(``);

    // Service
    lines.push(`// ============================================================================`);
    lines.push(`// Service`);
    lines.push(`// ============================================================================`);
    lines.push(``);
    lines.push(`@Injectable({ providedIn: 'root' })`);
    lines.push(`export class ${entityName}Service {`);
    lines.push(`  private readonly http = inject(HttpClient);`);
    lines.push(`  private readonly baseUrl = '${apiBasePath}${apiPath}';`);
    lines.push(``);
    lines.push(`  /** One page of rows: the route pages, sorts and filters them, and answers the page envelope. */`);
    lines.push(`  findAll(pageRequest: PageRequest & { sort?: ${modelName}SortField } = {}, filter: ${modelName}Filter = {}): Observable<Page<${modelName}>> {`);
    lines.push(`    let params = new HttpParams();`);
    lines.push(`    if (pageRequest.page !== undefined) params = params.set('page', String(pageRequest.page));`);
    lines.push(`    if (pageRequest.size !== undefined) params = params.set('size', String(pageRequest.size));`);
    lines.push(`    if (pageRequest.sort) params = params.set('sort', \`\${pageRequest.sort},\${pageRequest.direction ?? 'asc'}\`);`);
    lines.push(`    for (const [key, value] of Object.entries(filter)) {`);
    lines.push(`      if (value !== undefined && value !== null && value !== '') {`);
    lines.push(`        params = params.set(key, String(value));`);
    lines.push(`      }`);
    lines.push(`    }`);
    lines.push(`    return this.http.get<Page<${modelName}>>(this.baseUrl, { params });`);
    lines.push(`  }`);
    lines.push(``);
    lines.push(`  findById(id: string): Observable<${modelName}> {`);
    lines.push(`    return this.http.get<${modelName}>(\`\${this.baseUrl}/\${id}\`);`);
    lines.push(`  }`);
    lines.push(``);
    lines.push(`  create(data: ${modelName}Create): Observable<${modelName}> {`);
    lines.push(`    return this.http.post<${modelName}>(this.baseUrl, data);`);
    lines.push(`  }`);
    lines.push(``);
    // PUT, because that is the verb the generated kernel router serves update on and the one
    // the OpenAPI document publishes; the kernel router matches methods exactly, so any other
    // verb would never reach a generated server. Pinned against the router by
    // crud-route-parity.spec.ts (shared contract: exeris-e2e-tests contract/crud-routes.json).
    lines.push(`  update(id: string, data: ${modelName}Update): Observable<${modelName}> {`);
    lines.push(`    return this.http.put<${modelName}>(\`\${this.baseUrl}/\${id}\`, data);`);
    lines.push(`  }`);
    lines.push(``);
    lines.push(`  delete(id: string): Observable<void> {`);
    lines.push(`    return this.http.delete<void>(\`\${this.baseUrl}/\${id}\`);`);
    lines.push(`  }`);

    // The generated server has no archive and no restore route. On a @SoftDelete entity its
    // DELETE is the archive — the emitted repository sets the flag instead of removing the row —
    // so softDelete calls that. No route, handler or repository method un-sets the flag, so the
    // service emits no restore.
    if (softDelete) {
      lines.push(``);
      lines.push(`  /** Archives the row: on this entity the server's DELETE sets the soft-delete flag. */`);
      lines.push(`  softDelete(id: string): Observable<void> {`);
      lines.push(`    return this.http.delete<void>(\`\${this.baseUrl}/\${id}\`);`);
      lines.push(`  }`);
    }

    // Custom actions (T1) — POST {baseUrl}/{id}/actions/{kebab(name)}, returns the
    // updated aggregate (the server responds with the persisted entity).
    for (const action of actions) {
      lines.push(``);
      lines.push(`  /** ${action.description} */`);
      const paramsStr = action.hasParams
        ? ', ' + action.params.map(p => `${p.name}: ${p.tsType}`).join(', ')
        : '';
      const bodyParams = action.hasParams
        ? `{ ${action.params.map(p => p.name).join(', ')} }`
        : '{}';
      lines.push(`  ${action.methodName}(id: string${paramsStr}): Observable<${modelName}> {`);
      lines.push(`    return this.http.post<${modelName}>(\`\${this.baseUrl}/\${id}/actions/${action.kebabName}\`, ${bodyParams});`);
      lines.push(`  }`);
    }

    lines.push(`}`);

    return lines.join('\n');
  }

  /**
   * The TypeScript type of one filter value: the DTO type of a scalar, the enum's type when the
   * service imports it (else its constant's name as a string), and a string for a foreign key.
   */
  private filterType(property: ListQueryProperty, enumTypes: readonly string[], context: GeneratorContext): string {
    if (property.enumeration && property.field) {
      const name = enumTypeOf(property.field, context.enums ?? []);
      return name !== undefined && enumTypes.includes(name) ? name : 'string';
    }
    if (!property.field) return 'string';
    const ts = DslMapper.mapType(property.type).tsType.replace(/ \| null$/, '');
    return ts === 'boolean' || ts === 'number' ? ts : 'string';
  }

  private getSystemFields(metadata: DomainMetadata): string[] {
    const fields = [primaryKeyField(metadata)];
    const sf = metadata.systemFields;

    if (sf) {
      if (sf.versionField) fields.push(sf.versionField);
      if (sf.createdAtField) fields.push(sf.createdAtField);
      if (sf.updatedAtField) fields.push(sf.updatedAtField);
      if (sf.createdByField) fields.push(sf.createdByField);
      if (sf.updatedByField) fields.push(sf.updatedByField);
    } else {
      fields.push('createdAt', 'updatedAt', 'version');
    }

    return fields;
  }

  /**
   * The enums the service imports: those of the fields (`fieldEnumImports`), then those of the
   * served actions' parameters. A parameter carries no `enumType`, so its type alone decides.
   */
  private collectEnumTypes(metadata: DomainMetadata, scope: TypeScope): string[] {
    const enumTypes = new Set(fieldEnumImports(metadata.fields, scope));
    for (const action of metadata.actions ?? []) {
      if (action.streaming) {
        continue;
      }
      for (const name of typeImports((action.params ?? []).map((p) => p.type), scope).enums) {
        enumTypes.add(name);
      }
    }
    return [...enumTypes];
  }
}

/**
 * The entity's service. `scope` is what its types resolve against, as for `generateTypes`.
 */
export function generateService(
  metadata: DomainMetadata,
  config: GeneratorConfig,
  scope?: AppTypes,
): GeneratedFile | null {
  const generator = new ServiceGenerator();
  const context: GeneratorContext = scope
    ? { config, backend: config.backend ?? 'KERNEL', allDomains: scope.domains, enums: scope.enums, enumsComplete: true }
    : { config, backend: config.backend ?? 'KERNEL', allDomains: [metadata], enums: [] };
  return generator.generate(metadata, context);
}

