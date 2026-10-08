/**
 * Foreign keys: the links of the list and detail emitters, and the pickers of the form.
 *
 * A MANY_TO_ONE relationship is the only kind the Java side stores as a column on the owning
 * table, so it is the only kind whose value on this entity is the target's key. When that local
 * field is a UUID, its value is exactly the `:id` of the target's detail route, and the list
 * cell and detail row can link to it without fetching the target; and it is exactly the key of
 * one of the records the target's service lists, so the form can offer those records to pick from.
 *
 * Entity-typed relationship fields, and ONE_TO_ONE / ONE_TO_MANY / MANY_TO_MANY relationships,
 * produce neither a link nor a picker: their serialised value is not a bare id.
 */

import type { DomainMetadata, RelationshipMetadata } from '../../models/domain-model.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import { entityViews } from './entity-views.js';
import { declaresPrimaryKey, primaryKeyField } from '../../core/primary-key.js';

const UUID_TYPES = new Set(['UUID', 'java.util.UUID']);

/** A local field holding the id of a MANY_TO_ONE relationship's target. */
export interface ManyToOneForeignKey {
  /** The local field carrying the target's id. */
  readonly field: string;
  /** The target's simple entity name. */
  readonly target: string;
  readonly relationship: RelationshipMetadata;
}

/**
 * The entity's foreign-key fields: each local UUID field a MANY_TO_ONE relationship names, with the
 * simple name of its target. Relationships are read in declaration order; the first relationship
 * naming a field wins.
 */
export function manyToOneForeignKeys(domain: DomainMetadata): ManyToOneForeignKey[] {
  const keys: ManyToOneForeignKey[] = [];
  for (const rel of domain.relationships ?? []) {
    if (rel.type !== 'MANY_TO_ONE') continue;
    // The processor names a relationship after the field that carries it; `fieldName`, when
    // present, is that same field.
    const localName = rel.fieldName ?? rel.name;
    if (keys.some((k) => k.field === localName)) continue;
    const field = domain.fields.find((f) => f.name === localName);
    if (!field || !UUID_TYPES.has(field.type)) continue;
    // The processor's fallback may record a qualified name; entity names are simple names.
    const target = rel.targetEntity.includes('.') ? rel.targetEntity.split('.').pop()! : rel.targetEntity;
    keys.push({ field: localName, target, relationship: rel });
  }
  return keys;
}

/**
 * Maps each linkable local field name to the absolute route prefix of its target's detail page
 * (`/<routePlural(target)>`). The route segment comes from `DslMapper.routePlural`, the function
 * the route table is built with, so the link always names a declared route.
 *
 * The target must be a loaded domain with a detail page: an absent entity, or one whose `@UI`
 * switches its detail view off, has no detail route in the emitted table, and its field
 * renders as plain text — a reference never opens another page in place of the detail. The detail
 * route exists only when detail views are generated, so with `detailRouted` false no field links.
 */
export function foreignKeyLinks(
  domain: DomainMetadata,
  allDomains: readonly DomainMetadata[],
  detailRouted = true,
): Map<string, string> {
  if (!detailRouted) return new Map();
  const routable = new Set(allDomains.filter((d) => entityViews(d).detail).map((d) => d.entityName));
  const links = new Map<string, string>();
  for (const key of manyToOneForeignKeys(domain)) {
    if (!routable.has(key.target)) continue;
    links.set(key.field, `/${DslMapper.routePlural(key.target)}`);
  }
  return links;
}

/** The records a foreign-key control offers: the target's, read through its generated service. */
export interface ForeignKeyPicker {
  /** The target's simple entity name; its service class is `<target>Service`. */
  readonly target: string;
  /** The target's service module, relative to `services/` (`<kebab(target)>.service`). */
  readonly serviceModule: string;
  /** The target's primary key, which an option's value is read from. */
  readonly keyField: string;
  /**
   * The target field an option is labelled with: `@Relationship.displayField` when the target
   * declares a field of that name. Absent otherwise, and an option is then labelled with its key.
   */
  readonly labelField?: string;
}

/**
 * Maps each pickable local field name to the records its control offers.
 *
 * The target must be a loaded domain whose generated service lists its records, and whose fields
 * list the key an option's value is read from. Without generated services (`servicesGenerated`
 * false), or for a target outside the domain set or one listing no key, the field has no picker.
 */
export function foreignKeyPickers(
  domain: DomainMetadata,
  allDomains: readonly DomainMetadata[],
  servicesGenerated: boolean,
): Map<string, ForeignKeyPicker> {
  if (!servicesGenerated) return new Map();
  const pickers = new Map<string, ForeignKeyPicker>();
  for (const key of manyToOneForeignKeys(domain)) {
    const targetDomain = allDomains.find((d) => d.entityName === key.target);
    if (!targetDomain || !declaresPrimaryKey(targetDomain)) continue;
    const displayField = key.relationship.displayField;
    const labelField = displayField && targetDomain.fields.some((f) => f.name === displayField) ? displayField : undefined;
    pickers.set(key.field, {
      target: key.target,
      serviceModule: `${DslMapper.toKebabCase(key.target)}.service`,
      keyField: primaryKeyField(targetDomain),
      ...(labelField ? { labelField } : {}),
    });
  }
  return pickers;
}
