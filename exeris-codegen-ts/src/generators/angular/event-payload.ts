import type { DomainEventMetadata, DomainMetadata, FieldMetadata } from '../../models/domain-model.js';

/**
 * The entity fields the Java publisher puts in an event's payload record: the event's
 * `payloadFields` in listed order, without `sensitiveFields`, and without a name no entity field
 * carries. This is the set `KernelEventGenerator.payloadFields` resolves.
 */
export function publishedPayloadFields(event: DomainEventMetadata, domain: DomainMetadata): FieldMetadata[] {
  const sensitive = new Set(event.sensitiveFields ?? []);
  const fieldByName = new Map(domain.fields.map((f) => [f.name, f] as const));
  const out: FieldMetadata[] = [];
  for (const name of event.payloadFields ?? []) {
    const field = fieldByName.get(name);
    if (field !== undefined && !sensitive.has(name)) {
      out.push(field);
    }
  }
  return out;
}
