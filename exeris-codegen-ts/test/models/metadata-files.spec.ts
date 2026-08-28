/**
 * Coverage for src/models/metadata-files.ts — the metadata scan + family split.
 *
 * This code ran only inside the CLI until the peer-types slice (T42) needed it, so it had
 * never been under test: the recursive scan, the `enum_` / `view_` / domain split, and the
 * single-entity vs. multi-domain wrapper branch were all covered only by whether a full
 * generation run happened to work.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { findMetadataFiles, loadMetadataFamilies } from '../../src/models/metadata-files.js';

let root: string;
const write = (rel: string, body: unknown) => {
  const full = join(root, rel);
  mkdirSync(join(full, '..'), { recursive: true });
  writeFileSync(full, typeof body === 'string' ? body : JSON.stringify(body));
  return full;
};
const entity = (entityName: string) => ({
  packageName: 'com.shop', entityName, fields: [{ name: 'id', type: 'java.util.UUID' }],
});

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'metadata-files-'));
});
afterEach(() => {
  rmSync(root, { recursive: true, force: true });
});

describe('findMetadataFiles', () => {
  it('finds JSON recursively and ignores everything else', () => {
    write('Order.json', entity('Order'));
    write('nested/Product.json', entity('Product'));
    write('notes.txt', 'ignored');
    expect(findMetadataFiles(root).sort().map((f) => f.slice(root.length + 1)))
      .toEqual(['Order.json', 'nested/Product.json']);
  });

  it('returns a JSON file path given directly, and nothing for a non-JSON one', () => {
    const json = write('Order.json', entity('Order'));
    const txt = write('notes.txt', 'x');
    expect(findMetadataFiles(json)).toEqual([json]);
    expect(findMetadataFiles(txt)).toEqual([]);
  });

  it('returns nothing for a path that does not exist', () => {
    expect(findMetadataFiles(join(root, 'absent'))).toEqual([]);
  });
});

describe('loadMetadataFamilies', () => {
  it('splits the three families by basename prefix', () => {
    const files = [
      write('Order.json', entity('Order')),
      write('enum_OrderStatus.json', { name: 'OrderStatus', qualifiedName: 'com.shop.OrderStatus', packageName: 'com.shop', values: [] }),
      write('view_Dashboard.json', {
        name: 'Dashboard', packageName: 'com.shop', qualifiedName: 'com.shop.Dashboard',
        view: { name: 'Dashboard', route: '/dash', regions: [] },
      }),
    ];
    const { domains, enums, views } = loadMetadataFamilies(files);
    expect(domains.map((d) => d.entityName)).toEqual(['Order']);
    expect(enums.map((e) => e.name)).toEqual(['OrderStatus']);
    expect(views).toHaveLength(1);
  });

  it('unwraps a multi-domain file as well as a single entity', () => {
    const files = [write('all.json', { domains: [entity('Order'), entity('Product')] })];
    expect(loadMetadataFamilies(files).domains.map((d) => d.entityName)).toEqual(['Order', 'Product']);
  });

  // This is what lets a peer's cap-manifest.json sit in the same directory as its entities.
  it('ignores a JSON file that is neither an entity nor a domain wrapper', () => {
    const files = [write('cap-manifest.json', { schemaVersion: 2, modules: [] })];
    expect(loadMetadataFamilies(files).domains).toEqual([]);
  });

  it('reports each file to the callback under its family', () => {
    const files = [
      write('Order.json', entity('Order')),
      write('enum_S.json', { name: 'S', qualifiedName: 'com.shop.S', packageName: 'com.shop', values: [] }),
    ];
    const seen: string[] = [];
    loadMetadataFamilies(files, (family) => seen.push(family));
    expect(seen).toEqual(['domain', 'enum']);
  });
});
