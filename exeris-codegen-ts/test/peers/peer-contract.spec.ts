/**
 * Coverage for src/peers/peer-contract.ts — the mesh's input shape (ADR-048 §1).
 *
 * The load path is mostly refusals, and each refusal is the point: a peer contract that
 * cannot be verified is not a weaker contract, and generating types from one would say
 * otherwise. Every message is asserted to name the peer, because a consumer with several
 * peers otherwise has to guess which one the build is complaining about.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  CAP_MANIFEST_NAME,
  MIN_MANIFEST_SCHEMA_VERSION,
  PeerContractError,
  loadPeerContract,
  loadPeerContracts,
  parsePeerRef,
} from '../../src/peers/peer-contract.js';

let root: string;

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'peer-contract-'));
});
afterEach(() => {
  rmSync(root, { recursive: true, force: true });
});

/** Writes a peer artifact: a manifest at the root and metadata under exeris-metadata/. */
function artifact(
  name: string,
  opts: {
    schemaVersion?: number | string | null;
    entities?: string[];
    enums?: string[];
    manifest?: string;
  } = {},
): string {
  const dir = join(root, name);
  mkdirSync(join(dir, 'exeris-metadata'), { recursive: true });

  if (opts.manifest !== undefined) {
    writeFileSync(join(dir, CAP_MANIFEST_NAME), opts.manifest);
  } else if (opts.schemaVersion !== null) {
    const body: Record<string, unknown> = { modules: [] };
    if (opts.schemaVersion !== undefined) body.schemaVersion = opts.schemaVersion;
    else body.schemaVersion = MIN_MANIFEST_SCHEMA_VERSION;
    writeFileSync(join(dir, CAP_MANIFEST_NAME), JSON.stringify(body));
  }

  for (const entity of opts.entities ?? []) {
    writeFileSync(
      join(dir, 'exeris-metadata', `${entity}.json`),
      JSON.stringify({ packageName: 'com.peer', entityName: entity, fields: [{ name: 'id', type: 'java.util.UUID' }] }),
    );
  }
  for (const e of opts.enums ?? []) {
    writeFileSync(
      join(dir, 'exeris-metadata', `enum_${e}.json`),
      JSON.stringify({ name: e, qualifiedName: `com.peer.${e}`, packageName: 'com.peer', values: [] }),
    );
  }
  return dir;
}

describe('parsePeerRef', () => {
  it('splits <name>=<path> on the first = so a path may contain one', () => {
    expect(parsePeerRef('billing=/srv/a=b/contract')).toEqual({ name: 'billing', path: '/srv/a=b/contract' });
  });

  it('accepts a hyphenated name', () => {
    expect(parsePeerRef('order-history=./x').name).toBe('order-history');
  });

  it.each(['billing', '=./x', 'billing='])('rejects the malformed reference %s', (spec) => {
    expect(() => parsePeerRef(spec)).toThrow(PeerContractError);
  });

  // The name becomes a directory segment and part of every import that reaches the peer,
  // so it is constrained rather than free text — a consumer string must not reach join().
  it.each(['../escape', 'bill/ing', '1billing', 'bill ing', 'bill.ing', ''])(
    'rejects the unusable peer name %s',
    (name) => {
      expect(() => parsePeerRef(`${name}=./x`)).toThrow(/not usable|not of the form/);
    },
  );
});

describe('loadPeerContract', () => {
  it('loads entities and enums from a well-formed artifact', () => {
    const dir = artifact('billing', { entities: ['Order', 'Invoice'], enums: ['OrderStatus'] });
    const peer = loadPeerContract({ name: 'billing', path: dir });

    expect(peer.name).toBe('billing');
    expect(peer.domains.map((d) => d.entityName)).toEqual(['Invoice', 'Order']);
    expect(peer.enums.map((e) => e.name)).toEqual(['OrderStatus']);
  });

  it('sorts entities by name — a published artifact is unpacked onto a filesystem we do not own', () => {
    const dir = artifact('billing', { entities: ['Zebra', 'Apple', 'Mango'] });
    expect(loadPeerContract({ name: 'billing', path: dir }).domains.map((d) => d.entityName))
      .toEqual(['Apple', 'Mango', 'Zebra']);
  });

  it('sorts enums by name too — the peer\'s enum module is emitted in this order', () => {
    const dir = artifact('billing', { entities: ['Order'], enums: ['Zone', 'Apex', 'Mid'] });
    expect(loadPeerContract({ name: 'billing', path: dir }).enums.map((e) => e.name))
      .toEqual(['Apex', 'Mid', 'Zone']);
  });

  // Ordering leaks into emitted text: it decides file order and the lines of the peer's barrel.
  // `localeCompare` is backed by the Node build's ICU collation table, and it INVERTS these
  // pairs relative to code-unit order — so two consumers on different Node builds would emit
  // the same contract in different orders. These names are chosen to fail under localeCompare.
  it('orders by code unit, not by ICU collation', () => {
    const dir = artifact('billing', { entities: ['order', 'Order', 'Zeta', 'alpha'] });
    expect(loadPeerContract({ name: 'billing', path: dir }).domains.map((d) => d.entityName))
      .toEqual(['Order', 'Zeta', 'alpha', 'order']);
  });

  it('does not read the manifest as an entity', () => {
    const dir = artifact('billing', { entities: ['Order'] });
    expect(loadPeerContract({ name: 'billing', path: dir }).domains).toHaveLength(1);
  });

  it('refuses a directory that does not exist, naming the peer', () => {
    expect(() => loadPeerContract({ name: 'billing', path: join(root, 'nope') }))
      .toThrow(/peer 'billing'.*not a directory/s);
  });

  // The manifest is what makes a directory a contract rather than a pile of JSON. Accepting
  // one without it would ship the "second metadata directory" input model ADR-048 rejects.
  it('refuses a metadata directory with no manifest', () => {
    const dir = artifact('billing', { schemaVersion: null, entities: ['Order'] });
    expect(() => loadPeerContract({ name: 'billing', path: dir }))
      .toThrow(/peer 'billing'.*a directory of metadata alone is not a contract/s);
  });

  it('refuses a v1 manifest rather than degrading, naming the floor', () => {
    const dir = artifact('billing', { schemaVersion: 1, entities: ['Order'] });
    expect(() => loadPeerContract({ name: 'billing', path: dir }))
      .toThrow(/peer 'billing'.*schemaVersion 1, below the floor of 2/s);
  });

  it.each([
    ['absent', '{"modules":[]}'],
    ['a string', '{"schemaVersion":"2"}'],
    ['fractional', '{"schemaVersion":2.5}'],
    ['a JSON array', '[2]'],
  ])('refuses a manifest whose schemaVersion is %s', (label, manifest) => {
    const dir = artifact(`p-${label.replace(/ /g, '-')}`, { manifest, entities: ['Order'] });
    expect(() => loadPeerContract({ name: 'billing', path: dir }))
      .toThrow(/peer 'billing'.*no integer schemaVersion/s);
  });

  it('refuses a manifest that is not readable JSON', () => {
    const dir = artifact('billing', { manifest: '{ not json', entities: ['Order'] });
    expect(() => loadPeerContract({ name: 'billing', path: dir }))
      .toThrow(/peer 'billing'.*not readable JSON/s);
  });

  it('accepts an artifact whose manifest is above the floor', () => {
    const dir = artifact('billing', { schemaVersion: 7, entities: ['Order'] });
    expect(loadPeerContract({ name: 'billing', path: dir }).domains).toHaveLength(1);
  });

  it('accepts an artifact that provides no entities — a peer may provide only services', () => {
    const dir = artifact('billing', {});
    expect(loadPeerContract({ name: 'billing', path: dir }).domains).toEqual([]);
  });
});

describe('loadPeerContracts', () => {
  it('loads in declared-name order regardless of the order given', () => {
    const shipping = artifact('shipping', { entities: ['Order'] });
    const billing = artifact('billing', { entities: ['Order'] });
    const peers = loadPeerContracts([
      { name: 'shipping', path: shipping },
      { name: 'billing', path: billing },
    ]);
    expect(peers.map((p) => p.name)).toEqual(['billing', 'shipping']);
  });

  it('is empty for no peers', () => {
    expect(loadPeerContracts([])).toEqual([]);
  });
});
