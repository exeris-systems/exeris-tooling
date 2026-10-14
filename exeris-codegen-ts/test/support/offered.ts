import { DomainMetadataSchema, type DomainMetadata } from '../../src/models/domain-model.js';

/**
 * Parses hand-written domain metadata whose fields the create and update bodies offer: the schema
 * reads an absent `inCreate` / `inUpdate` as `false`, so a fixture that names neither key would
 * leave every field out of both bodies. A key the fixture names is kept.
 */
export function parseOffered(domain: Record<string, unknown>): DomainMetadata {
  const fields = Array.isArray(domain.fields)
    ? domain.fields.map((f) => ({ inCreate: true, inUpdate: true, ...(f as Record<string, unknown>) }))
    : domain.fields;
  return DomainMetadataSchema.parse({ ...domain, fields });
}
