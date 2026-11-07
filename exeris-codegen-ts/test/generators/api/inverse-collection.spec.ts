/**
 * An inverse collection (a `List` field under a `ONE_TO_MANY` with `mappedBy`) through the emitters:
 * the server answers it as `null` and takes it from neither body, so the record type, its schema and
 * both request types leave it out, and the detail view lists the rows through the child's list route.
 */

import { describe, expect, it } from 'vitest';
import { TypeGenerator } from '../../../src/generators/api/type-gen.js';
import { generatePeerTypes } from '../../../src/generators/api/peer-type-gen.js';
import { resolveFieldRenders } from '../../../src/generators/angular/field-render.js';
import { omittedFromCreate, omittedFromUpdate } from '../../../src/core/server-owned-fields.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { createGeneratorContext } from '../../../src/core/generator-registry.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../../src/models/domain-model.js';

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop.domain', ...overrides });
}

const invoice = domain({
  entityName: 'Invoice',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'number', type: 'String', required: true, inCreate: true, inUpdate: true },
    { name: 'lines', type: 'java.util.List<com.shop.domain.InvoiceLine>', inCreate: true, inUpdate: true },
  ] as DomainMetadata['fields'],
  relationships: [{
    name: 'lines', targetEntity: 'InvoiceLine', type: 'ONE_TO_MANY', mappedBy: 'invoiceId', cascade: 'REMOVE',
  }] as DomainMetadata['relationships'],
});

const invoiceLine = domain({
  entityName: 'InvoiceLine',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'description', type: 'String', inCreate: true, inUpdate: true },
    { name: 'invoiceId', type: 'java.util.UUID', inCreate: true, inUpdate: true },
  ] as DomainMetadata['fields'],
  relationships: [{ name: 'invoiceId', targetEntity: 'Invoice', type: 'MANY_TO_ONE' }] as DomainMetadata['relationships'],
});

describe('inverse collection — record, schema and request types', () => {
  const context = createGeneratorContext({ generateZod: true }, [invoice, invoiceLine]);
  const generator = new TypeGenerator();
  const files = generator.generateAggregate([invoice, invoiceLine], context);
  const byPath = (path: string) => files.find((f) => f.path === path)?.content ?? '';
  const typesOf = (metadata: DomainMetadata) => generator.generate(metadata, context)!.content;

  it('leaves the member and the import of the child type out of the record and both request types', () => {
    const types = typesOf(invoice);
    expect(types).toContain('export interface Invoice {');
    expect(types).toContain('  number: string;');
    expect(types).not.toContain('lines');
    expect(types).not.toContain('InvoiceLine');
  });

  it('leaves the member and the child schema out of the Zod schemas', () => {
    const schema = byPath('schemas/invoice.schema.ts');
    expect(schema).toContain('export const InvoiceSchema = z.object({');
    expect(schema).not.toContain('lines');
    expect(schema).not.toContain('InvoiceLineSchema');
  });

  it('keeps the child unchanged: it holds the key the inverse reads through', () => {
    expect(typesOf(invoiceLine)).toContain('  invoiceId?: string;');
  });

  it('omits it from both bodies', () => {
    expect(omittedFromCreate(invoice)).toContain('lines');
    expect(omittedFromUpdate(invoice)).toContain('lines');
  });

  it('a peer DTO leaves it out too', () => {
    const peer = generatePeerTypes({ name: 'billing', domains: [invoice, invoiceLine], enums: [] }, DEFAULT_CONFIG);
    const types = peer.find((f) => f.path.endsWith('types/invoice.types.ts'))!.content;
    const schema = peer.find((f) => f.path.endsWith('schemas/invoice.schema.ts'))!.content;
    expect(types).not.toContain('lines');
    expect(schema).not.toContain('lines');
  });
});

describe('inverse collection — views', () => {
  it('is no displayed field of the list or the detail view', () => {
    const renders = resolveFieldRenders(invoice, { systemFieldNames: ['id'], links: new Map() });
    expect(renders.find((r) => r.name === 'lines')!.displayed).toBe(false);
    expect(renders.find((r) => r.name === 'number')!.displayed).toBe(true);
  });
});
