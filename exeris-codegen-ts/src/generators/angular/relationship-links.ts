/**
 * Foreign-key links shared by the list and detail emitters.
 *
 * A MANY_TO_ONE relationship is the only kind the Java side stores as a column on the owning
 * table, so it is the only kind whose value on this entity is the target's id. When that local
 * field is a UUID, its value is exactly the `:id` of the target's detail route, and the list
 * cell and detail row can link to it without fetching the target.
 *
 * Entity-typed relationship fields, and ONE_TO_ONE / ONE_TO_MANY / MANY_TO_MANY relationships,
 * produce no link: their serialised value is not a bare id.
 */

import type { DomainMetadata } from '../../models/domain-model.js';
import { DslMapper } from '../../models/dsl-mapper.js';

const UUID_TYPES = new Set(['UUID', 'java.util.UUID']);

/**
 * Maps each linkable local field name to the absolute route prefix of its target's detail page
 * (`/<routePlural(target)>`). The route segment comes from `DslMapper.routePlural`, the function
 * the route table is built with, so the link always names a declared route.
 *
 * The target must be a loaded domain: an absent entity has no route in the emitted table, and its field renders as plain text. The detail route exists only when detail
 * views are generated, so with `detailRouted` false no field links. Relationships are read in declaration
 * order; the first relationship naming a field wins.
 */
export function foreignKeyLinks(
  domain: DomainMetadata,
  allDomains: readonly DomainMetadata[],
  detailRouted = true,
): Map<string, string> {
  if (!detailRouted) return new Map();
  const routable = new Set(allDomains.map((d) => d.entityName));
  const links = new Map<string, string>();
  for (const rel of domain.relationships ?? []) {
    if (rel.type !== 'MANY_TO_ONE') continue;
    // The processor names a relationship after the field that carries it; `fieldName`, when
    // present, is that same field.
    const localName = rel.fieldName ?? rel.name;
    if (links.has(localName)) continue;
    const field = domain.fields.find((f) => f.name === localName);
    if (!field || !UUID_TYPES.has(field.type)) continue;
    // The processor's fallback may record a qualified name; entity names are simple names.
    const target = rel.targetEntity.includes('.') ? rel.targetEntity.split('.').pop()! : rel.targetEntity;
    if (!routable.has(target)) continue;
    links.set(localName, `/${DslMapper.routePlural(target)}`);
  }
  return links;
}
