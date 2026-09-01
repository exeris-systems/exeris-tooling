/**
 * Coverage for src/generators/api/peer-type-gen.ts — peer DTOs (T42, ADR-048).
 *
 * The compile-level guarantees live in `scripts/verify-generated-frontend.mjs`
 * (`two-peers-same-entity`) and in the CI `ng build`; a unit test asserting on emitted
 * strings cannot prove that an emitted app compiles — T40 is this repository's record of
 * exactly that. What is asserted here is the shape those gates depend on: one tree per
 * peer, its own enum module, its own barrel, and no edge to the app's own types.
 */

import { describe, expect, it } from 'vitest';
import { generatePeerTypes, peerRoot } from '../../../src/generators/api/peer-type-gen.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { DomainMetadataSchema } from '../../../src/models/domain-model.js';
import type { PeerContract } from '../../../src/peers/peer-contract.js';

const order = DomainMetadataSchema.parse({
  packageName: 'com.billing',
  entityName: 'Order',
  displayName: 'Billing Order',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'invoiceNo', type: 'String', required: true, description: 'Human-readable number' },
    { name: 'status', type: 'com.billing.OrderStatus', enumType: 'com.billing.OrderStatus' },
    { name: 'version', type: 'java.lang.Long' },
  ],
});

const billing: PeerContract = {
  name: 'billing',
  domains: [order],
  enums: [{
    name: 'OrderStatus',
    qualifiedName: 'com.billing.OrderStatus',
    packageName: 'com.billing',
    values: [{ name: 'DRAFT', displayName: 'Draft', ordinal: 0 }],
  }],
};

const byPath = (files: { path: string; content: string }[], suffix: string) =>
  files.find((f) => f.path.endsWith(suffix))?.content ?? '';

describe('generatePeerTypes', () => {
  it('roots every file under the peer name', () => {
    const files = generatePeerTypes(billing, DEFAULT_CONFIG);
    expect(files.length).toBeGreaterThan(0);
    for (const f of files) expect(f.path.startsWith('peers/billing/')).toBe(true);
  });

  it('emits the entity interface, its Create shape and its Update alias', () => {
    const types = byPath(generatePeerTypes(billing, DEFAULT_CONFIG), 'types/order.types.ts');
    expect(types).toContain('export interface Order {');
    expect(types).toContain('export interface OrderCreate {');
    expect(types).toContain('export type OrderUpdate = Partial<OrderCreate>;');
  });

  // Filter and ListResponse describe THIS app's list/query surface. For a peer whose client
  // is not emitted until the 0.9.0 slice they would describe a query nobody can make — the
  // inert-emitted-surface failure mode D10, D11 and the unwired templates each record.
  it('emits no Filter or ListResponse shape', () => {
    const types = byPath(generatePeerTypes(billing, DEFAULT_CONFIG), 'types/order.types.ts');
    expect(types).not.toContain('OrderFilter');
    expect(types).not.toContain('OrderListResponse');
  });

  it('drops server-owned fields from the Create shape, keeping them on the entity', () => {
    const types = byPath(generatePeerTypes(billing, DEFAULT_CONFIG), 'types/order.types.ts');
    const create = types.slice(types.indexOf('export interface OrderCreate'));
    expect(types).toContain('  id?: string;');
    expect(create).not.toContain('id?:');
    expect(create).not.toContain('version');
    expect(create).toContain('invoiceNo: string;');
  });

  it('carries the peer name and the field description into the emitted text', () => {
    const types = byPath(generatePeerTypes(billing, DEFAULT_CONFIG), 'types/order.types.ts');
    expect(types).toContain("Peer contract: 'billing'");
    expect(types).toContain('// Human-readable number');
    expect(types).toContain('Billing Order — peer DTO');
  });

  it('gives the peer its own enum module and imports the enum from it', () => {
    const files = generatePeerTypes(billing, DEFAULT_CONFIG);
    expect(byPath(files, 'peers/billing/types/enums.ts')).toContain('export const OrderStatus = {');
    expect(byPath(files, 'types/order.types.ts')).toContain("import { OrderStatus } from './enums';");
  });

  it('emits a valid empty enum module for a peer with no enums, so its barrel resolves', () => {
    const files = generatePeerTypes({ name: 'shipping', domains: [order], enums: [] }, DEFAULT_CONFIG);
    expect(byPath(files, 'types/enums.ts')).toContain('export {};');
    expect(byPath(files, 'peers/shipping/index.ts')).toContain("export * from './types/enums';");
  });

  it('emits Zod schemas that read the peer\'s own enum module', () => {
    const schema = byPath(generatePeerTypes(billing, DEFAULT_CONFIG), 'schemas/order.schema.ts');
    expect(schema).toContain("import { OrderStatusSchema } from '../types/enums';");
    expect(schema).toContain('export const OrderSchema = z.object({');
    expect(schema).toContain('export const OrderCreateSchema = OrderSchema.omit({');
    expect(schema).toContain('  id: true,');
    expect(schema).toContain('export const OrderUpdateSchema = OrderCreateSchema.partial();');
  });

  it('omits schemas and their barrel lines under --no-zod', () => {
    const files = generatePeerTypes(billing, { ...DEFAULT_CONFIG, generateZod: false });
    expect(files.some((f) => f.path.includes('/schemas/'))).toBe(false);
    expect(byPath(files, 'peers/billing/index.ts')).not.toContain('schema');
  });

  it('barrels only its own files — every specifier is peer-relative', () => {
    const barrel = byPath(generatePeerTypes(billing, DEFAULT_CONFIG), 'peers/billing/index.ts');
    const specifiers = [...barrel.matchAll(/from '([^']+)'/g)].map((m) => m[1]);
    expect(specifiers.length).toBeGreaterThan(0);
    for (const spec of specifiers) expect(spec.startsWith('./')).toBe(true);
  });

  it('renames an entity that collides with what an emitted module already binds (T40)', () => {
    const peer: PeerContract = {
      name: 'shipping',
      domains: [DomainMetadataSchema.parse({ packageName: 'com.shipping', entityName: 'Component', fields: [{ name: 'id', type: 'java.util.UUID' }] })],
      enums: [],
    };
    const types = byPath(generatePeerTypes(peer, DEFAULT_CONFIG), 'types/component.types.ts');
    expect(types).toContain('export interface ComponentModel {');
    expect(types).not.toContain('export interface Component {');
  });

  it('skips a hidden entity the peer does not expose', () => {
    const hidden = DomainMetadataSchema.parse({
      packageName: 'com.billing', entityName: 'Ledger',
      fields: [{ name: 'id', type: 'java.util.UUID' }],
      internalApi: { hidden: true },
    });
    const files = generatePeerTypes({ ...billing, domains: [order, hidden] }, DEFAULT_CONFIG);
    expect(files.some((f) => f.path.includes('ledger'))).toBe(false);
    expect(byPath(files, 'peers/billing/index.ts')).not.toContain('ledger');
  });

  it('is deterministic — same contract in, byte-identical output', () => {
    expect(generatePeerTypes(billing, DEFAULT_CONFIG)).toEqual(generatePeerTypes(billing, DEFAULT_CONFIG));
  });
});

describe('peerRoot', () => {
  it('is POSIX-separated so emitted paths do not depend on the generating OS', () => {
    expect(peerRoot('billing')).toBe('peers/billing');
  });
});
