/**
 * No emitted file carries a `TODO`. The output is regenerated, so a marker a consumer cannot act on
 * in place is noise in their tree. The `@View` pages are the one exception: their `TODO(@View G#)`
 * markers name a binding the view IR does not model yet, and they are covered by the view specs.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../src/orchestrator.js';
import { DomainMetadataSchema } from '../src/models/domain-model.js';
import { DEFAULT_CONFIG } from '../src/config.js';

const order = DomainMetadataSchema.parse({
  entityName: 'Order',
  packageName: 'com.shop',
  versioned: true,
  realTimeApi: true,
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'note', type: 'String', filterable: true, required: true },
    { name: 'total', type: 'java.lang.Integer', filterable: true },
    { name: 'status', type: 'com.shop.OrderStatus', filterable: true },
    { name: 'summary', type: 'String', computed: true, computedFrom: ['note', 'total'] },
    { name: 'label', type: 'String', computed: true },
    { name: 'version', type: 'java.lang.Long' },
  ],
  actions: [{ name: 'markPaid', methodName: 'markPaid' }],
});

const customer = DomainMetadataSchema.parse({
  entityName: 'Customer',
  packageName: 'com.shop',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'name', type: 'String', required: true },
  ],
});

const orderStatus = {
  name: 'OrderStatus',
  qualifiedName: 'com.shop.OrderStatus',
  packageName: 'com.shop',
  values: [
    { name: 'NEW', displayName: 'New', ordinal: 0 },
    { name: 'PAID', displayName: 'Paid', ordinal: 1 },
  ],
};

describe('emitted output', () => {
  it('no generated file contains TODO', () => {
    const files = buildGeneratedFiles([order, customer], [orderStatus], { ...DEFAULT_CONFIG, generateTests: true });

    expect(files.length).toBeGreaterThan(0);
    expect(files.filter((f) => f.content.includes('TODO')).map((f) => f.path)).toEqual([]);
  });
});
