/**
 * The primary key of an entity, as the emitted TypeScript names it.
 *
 * Every emitter that writes the key of a row (a model property, a schema field, a store lookup, a
 * route link, a picker value) takes the name from here, so the rule that decides it has one home.
 * The route path variable `:id` and the by-id method names name a URL segment and a role, not the
 * field, and do not come from here.
 */

import type { DomainMetadata } from '../models/domain-model.js';

/** The key name for a caller that holds no entity metadata: the name an entity gets by default. */
export const DEFAULT_PRIMARY_KEY_FIELD = 'id';

/** The name of the field that is the entity's primary key. */
export function primaryKeyField(_metadata: DomainMetadata): string {
  return DEFAULT_PRIMARY_KEY_FIELD;
}

/** Whether the entity declares a field with the name of its primary key. */
export function declaresPrimaryKey(metadata: DomainMetadata): boolean {
  const key = primaryKeyField(metadata);
  return metadata.fields.some((f) => f.name === key);
}
