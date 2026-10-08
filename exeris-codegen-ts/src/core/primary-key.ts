/**
 * The primary key of an entity, as the emitted TypeScript names it.
 *
 * The key is the field `systemFields.primaryKeyField` names, or `id` when the entity names none
 * (ADR-104), the rule the Java side's `PrimaryKeys.field` applies. Every emitter that writes the
 * key of a row (a model property, a schema field, a store lookup, a route link, a picker value)
 * takes the name from here, so the rule that decides it has one home. The route path variable
 * `:id` and the by-id method names name a URL segment and a role, not the field, and do not come
 * from here.
 */

import { FieldMetadataSchema, type DomainMetadata } from '../models/domain-model.js';

/** The key name of an entity that names none. */
export const DEFAULT_PRIMARY_KEY_FIELD = 'id';

/** The type the processor admits for a key (EXT-PROC-1018), as it writes a field's type. */
const PRIMARY_KEY_TYPE = 'java.util.UUID';

/**
 * The name of the field that is the entity's primary key: `systemFields.primaryKeyField` when it
 * is present and non-blank, else `id`. A caller that holds no metadata for the entity passes
 * `undefined` and gets `id`.
 */
export function primaryKeyField(metadata: Pick<DomainMetadata, 'systemFields'> | undefined): string {
  const named = metadata?.systemFields?.primaryKeyField;
  return named === undefined || named.trim() === '' ? DEFAULT_PRIMARY_KEY_FIELD : named;
}

/** Whether the entity's `fields` list a field with the name of its primary key. */
export function declaresPrimaryKey(metadata: DomainMetadata): boolean {
  const key = primaryKeyField(metadata);
  return metadata.fields.some((f) => f.name === key);
}

/**
 * The entity with its key among its fields. The processor records an entity's own fields only, so
 * a key inherited from a superclass, which it accepts, is absent from `fields`; every row the
 * server returns carries it all the same. Such an entity gets the key as its first field, an
 * optional UUID like a declared one; an entity that lists its key is returned as it is.
 */
export function withPrimaryKey(metadata: DomainMetadata): DomainMetadata {
  if (declaresPrimaryKey(metadata)) return metadata;
  const key = FieldMetadataSchema.parse({ name: primaryKeyField(metadata), type: PRIMARY_KEY_TYPE });
  return { ...metadata, fields: [key, ...metadata.fields] };
}
