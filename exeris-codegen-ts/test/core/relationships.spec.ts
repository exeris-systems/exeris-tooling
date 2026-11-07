/**
 * The relationship rules both emitters share: which `List` field is an inverse collection, which
 * members the record carries, and which `MANY_TO_ONE` a `mappedBy` names. The Java twins are
 * `InverseCollections`, `StoredFields` and `ForeignKeys#backReference`, tested over the same table.
 */

import { describe, expect, it } from 'vitest';
import {
  backReference,
  foreignKeyBase,
  foreignKeyParam,
  isInverseCollection,
  listElementType,
  storedMember,
} from '../../src/core/relationships.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../src/models/domain-model.js';

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop.domain', ...overrides });
}

const LINES = { name: 'lines', type: 'java.util.List<com.shop.domain.InvoiceLine>' };

function invoice(relationships: unknown[]): DomainMetadata {
  return domain({
    entityName: 'Invoice',
    fields: [
      { name: 'number', type: 'String' },
      LINES,
      { name: 'tags', type: 'java.util.List<String>' },
      { name: 'scan', type: 'byte[]', blob: { contentTypes: [] } },
    ] as DomainMetadata['fields'],
    relationships: relationships as DomainMetadata['relationships'],
  });
}

const inverse = (mappedBy?: string, type = 'ONE_TO_MANY') => ({ name: 'lines', targetEntity: 'InvoiceLine', type, mappedBy });

describe('relationships — key parameter', () => {
  it('strips one trailing Id for the base and adds it back for the parameter', () => {
    expect(foreignKeyBase('customerId')).toBe('customer');
    expect(foreignKeyBase('customer')).toBe('customer');
    expect(foreignKeyBase('Id')).toBe('Id');
    expect(foreignKeyParam('customer')).toBe('customerId');
    expect(foreignKeyParam('customerId')).toBe('customerId');
  });

  it('reads a List element type in either spelling', () => {
    expect(listElementType('java.util.List<com.shop.domain.InvoiceLine>')).toBe('com.shop.domain.InvoiceLine');
    expect(listElementType('List<String>')).toBe('String');
    expect(listElementType('java.util.Set<String>')).toBeUndefined();
  });
});

describe('relationships — inverse collections', () => {
  it('is an inverse only under a ONE_TO_MANY that names its mappedBy', () => {
    const lines = (d: DomainMetadata) => d.fields.find((f) => f.name === 'lines')!;
    const withInverse = invoice([inverse('invoiceId')]);
    expect(isInverseCollection(lines(withInverse), withInverse)).toBe(true);
    for (const other of [invoice([]), invoice([inverse(' ')]), invoice([inverse(undefined)]),
      invoice([inverse('invoiceId', 'MANY_TO_MANY')])]) {
      expect(isInverseCollection(lines(other), other)).toBe(false);
    }
  });

  it('the record carries every member but a @Blob field and an inverse collection', () => {
    const metadata = invoice([inverse('invoiceId')]);
    expect(metadata.fields.filter((f) => storedMember(f, metadata)).map((f) => f.name)).toEqual(['number', 'tags']);
  });
});

describe('relationships — backReference', () => {
  const owner = domain({ entityName: 'Invoice' });
  const child = (name: string, fieldName: string, target = 'Invoice') => domain({
    entityName: 'InvoiceLine',
    relationships: [{ name, fieldName, targetEntity: target, type: 'MANY_TO_ONE' }] as DomainMetadata['relationships'],
  });

  it.each([
    ['invoiceId', 'invoiceId', 'invoiceId'],
    ['invoiceId', 'invoiceId', 'invoice'],
    ['invoice', 'invoiceKey', 'invoiceKey'],
    ['invoice', 'invoiceKey', 'invoiceId'],
    ['invoice', 'invoiceKey', 'invoice'],
  ])('back-reference %s (field %s) answers to mappedBy "%s"', (name, fieldName, mappedBy) => {
    const target = child(name, fieldName);
    expect(backReference(mappedBy, owner, target)).toBe(target.relationships![0]);
  });

  it('answers nothing for a mappedBy no MANY_TO_ONE back to the owner is named by', () => {
    expect(backReference('order', owner, child('invoiceId', 'invoiceId'))).toBeUndefined();
    expect(backReference('invoiceId', owner, child('invoiceId', 'invoiceId', 'Customer'))).toBeUndefined();
    expect(backReference('invoiceId', owner, child('invoiceId', 'invoiceId', 'com.shop.domain.Invoice')))
      .toBeDefined();
  });
});
