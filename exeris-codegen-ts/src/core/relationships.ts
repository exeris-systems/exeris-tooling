/**
 * Relationships as both emitters read them: a `MANY_TO_ONE`'s key parameter, the inverse
 * collections an entity declares, and the back-reference an inverse's `mappedBy` names.
 *
 * An inverse collection is a `List` field that a `ONE_TO_MANY` with a `mappedBy` declares. Its rows
 * are held by the target entity's table, through the `MANY_TO_ONE` back to the owner that `mappedBy`
 * names, never by the owner's row: the server stores no column for it, takes it from neither request
 * body and answers it as `null`. The record type, its schema and both request types therefore leave
 * it out, as they leave out a `@Blob` field (`storedMember`), and the detail view lists the rows
 * through the target's list route filtered by the back-reference's key.
 *
 * The Java side's twins are `InverseCollections`, `StoredFields` and `ForeignKeys#backReference` in
 * `exeris-codegen-java`; the rules here read the same metadata the same way.
 */

import type { DomainMetadata, FieldMetadata, RelationshipMetadata } from '../models/domain-model.js';

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

/** The processor's fallback may record a qualified name; entity names are simple names. */
export function simpleEntityName(name: string): string {
  return name.includes('.') ? name.substring(name.lastIndexOf('.') + 1) : name;
}

const LIST_PREFIXES = ['List<', 'java.util.List<'];

/** The element type of a `List` type, either spelling, or `undefined` for any other type. */
export function listElementType(javaType: string): string | undefined {
  for (const prefix of LIST_PREFIXES) {
    if (javaType.startsWith(prefix) && javaType.endsWith('>')) {
      return javaType.slice(prefix.length, -1);
    }
  }
  return undefined;
}

/** Whether a relationship is the inverse side of a `MANY_TO_ONE`: a `ONE_TO_MANY` that names its `mappedBy`. */
export function isInverseRelationship(relationship: RelationshipMetadata): boolean {
  return relationship.type === 'ONE_TO_MANY'
    && relationship.mappedBy !== undefined && relationship.mappedBy.trim() !== '';
}

/** The `ONE_TO_MANY` that makes `field` an inverse collection of `domain`, if it is one. */
export function inverseRelationship(field: FieldMetadata, domain: DomainMetadata): RelationshipMetadata | undefined {
  if (listElementType(field.type) === undefined) return undefined;
  return (domain.relationships ?? []).find(
    (r) => (r.fieldName ?? r.name) === field.name && isInverseRelationship(r),
  );
}

/** Whether `field` is an inverse collection of `domain`. */
export function isInverseCollection(field: FieldMetadata, domain: DomainMetadata): boolean {
  return inverseRelationship(field, domain) !== undefined;
}

/**
 * Whether the record the server answers carries a value for `field`: every field but a `@Blob`
 * field and an inverse collection, both of which the server leaves `null`. The record type, its
 * schema and the request types list exactly these fields.
 */
export function storedMember(field: FieldMetadata, domain: DomainMetadata): boolean {
  return field.blob === undefined && !isInverseCollection(field, domain);
}

/**
 * The `MANY_TO_ONE` of `target` back to `owner` that `mappedBy` names: the first, in declaration
 * order, whose relationship name, field name, key base (`customer` for `customerId`) or key parameter
 * (`customerId`) it is. `undefined` when there is none.
 */
export function backReference(
  mappedBy: string,
  owner: DomainMetadata,
  target: DomainMetadata,
): RelationshipMetadata | undefined {
  return (target.relationships ?? []).find(
    (r) => r.type === 'MANY_TO_ONE'
      && simpleEntityName(r.targetEntity) === owner.entityName
      && [r.name, r.fieldName ?? r.name, foreignKeyBase(r.name), foreignKeyParam(r.name)].includes(mappedBy),
  );
}
