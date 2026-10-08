/**
 * The dev-server proxy, `proxy.conf.js`.
 *
 * Pinned: one rule per path prefix the emitted clients request (`apiBasePath` followed by the
 * entity's served path), deduplicated and sorted; every rule carries the `bypass` that answers a
 * request accepting `text/html` with `/index.html` and lets every other request through to the
 * kernel application; `npm start` passes `proxy.conf.js`; the file is a seed, emitted only when the
 * app has a backend; `proxy.conf.json` is not emitted and stays a seed path, so an existing app's
 * copy is released rather than deleted.
 *
 * The bypass is exercised by importing the emitted module, not by matching its text.
 */

import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { generateAppStructure, proxyPrefixes } from '../../../src/generators/angular/app-structure-gen.js';
import { buildGeneratedFiles, SEED_PATHS } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG, type GeneratorConfig } from '../../../src/config.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../../src/models/domain-model.js';

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({
    packageName: 'com.shop',
    fields: [{ name: 'id', type: 'java.util.UUID' }],
    ...overrides,
  });
}

const cfg = (overrides: Partial<GeneratorConfig> = {}): GeneratorConfig => ({ ...DEFAULT_CONFIG, ...overrides });

const order = domain({ entityName: 'Order' });
const product = domain({ entityName: 'Product' });
const ticket = domain({ entityName: 'Ticket', path: '/support/tickets' });

function proxyOf(domains: DomainMetadata[], config: GeneratorConfig = cfg()): string {
  const file = generateAppStructure(domains, [], config).find((f) => f.path === 'proxy.conf.js');
  expect(file, 'proxy.conf.js').toBeDefined();
  return file!.content;
}

type Bypass = (req: { headers: { accept?: string } }) => string | undefined;
type ProxyRule = { target: string; secure: boolean; changeOrigin: boolean; bypass: Bypass };

describe('proxy.conf.js: the rules', () => {
  it('has one rule per entity path, sorted, whatever order the metadata was loaded in', () => {
    expect(proxyPrefixes([ticket, product, order], cfg())).toEqual(['/orders', '/products', '/support/tickets']);
    expect(proxyOf([ticket, product, order])).toBe(proxyOf([order, ticket, product]));
  });

  it('prefixes each rule with apiBasePath', () => {
    expect(proxyPrefixes([order, ticket], cfg({ apiBasePath: '/api' }))).toEqual(['/api/orders', '/api/support/tickets']);
  });

  it('writes one rule for two entities served at the same path', () => {
    const alias = domain({ entityName: 'PurchaseOrder', path: '/orders' });
    expect(proxyPrefixes([order, alias], cfg())).toEqual(['/orders']);
  });

  it('forwards the base URL every emitted service requests', () => {
    const domains = [order, product, ticket];
    const services = buildGeneratedFiles(domains, [], cfg()).filter((f) => f.path.endsWith('.service.ts'));
    const baseUrls = services
      .map((f) => /private readonly baseUrl = '([^']*)';/.exec(f.content)?.[1])
      .filter((url): url is string => url !== undefined)
      .sort();
    expect(baseUrls).toHaveLength(domains.length);
    expect(proxyPrefixes(domains, cfg())).toEqual(baseUrls);
  });

  it('declares no /api rule and no JSON proxy', () => {
    const files = generateAppStructure([order], [], cfg());
    expect(files.map((f) => f.path)).not.toContain('proxy.conf.json');
    expect(proxyOf([order])).not.toContain("'/api'");
  });
});

describe('proxy.conf.js: the bypass, as ng serve loads it', () => {
  let dir: string;
  let config: Record<string, ProxyRule>;

  beforeAll(async () => {
    dir = mkdtempSync(join(tmpdir(), 'exeris-proxy-'));
    // The scaffold's package.json declares "type": "module"; .mjs gives the import the same reading.
    const file = join(dir, 'proxy.conf.mjs');
    writeFileSync(file, proxyOf([order, ticket]));
    config = (await import(pathToFileURL(file).href)).default;
  });

  afterAll(() => {
    rmSync(dir, { recursive: true, force: true });
  });

  it('exports one rule per prefix, each targeting the kernel application', () => {
    expect(Object.keys(config)).toEqual(['/orders', '/support/tickets']);
    for (const rule of Object.values(config)) {
      expect(rule).toMatchObject({ target: 'http://localhost:8443', secure: false, changeOrigin: true });
    }
  });

  it('answers a browser navigation with index.html', () => {
    const navigation = 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8';
    for (const rule of Object.values(config)) {
      expect(rule.bypass({ headers: { accept: navigation } })).toBe('/index.html');
    }
  });

  it.each([
    ['an HttpClient call', 'application/json, text/plain, */*'],
    ['an EventSource', 'text/event-stream'],
    ['a request without Accept', undefined],
  ])('forwards %s', (_label, accept) => {
    expect(config['/orders'].bypass({ headers: accept === undefined ? {} : { accept } })).toBeUndefined();
  });
});

describe('proxy.conf.js: the scaffold around it', () => {
  it('is a seed, and npm start passes it to ng serve', () => {
    const files = generateAppStructure([order], [], cfg());
    expect(files.find((f) => f.path === 'proxy.conf.js')?.overwritable).toBe(false);
    const pkg = JSON.parse(files.find((f) => f.path === 'package.json')!.content);
    expect(pkg.type).toBe('module');
    expect(pkg.scripts.start).toBe('ng serve --proxy-config proxy.conf.js');
  });

  it('keeps proxy.conf.json a seed path, so an existing copy is released and not deleted', () => {
    expect(SEED_PATHS.has('proxy.conf.js')).toBe(true);
    expect(SEED_PATHS.has('proxy.conf.json')).toBe(true);
  });
});
