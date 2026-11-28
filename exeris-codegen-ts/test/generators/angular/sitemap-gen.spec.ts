/**
 * `sitemap.xml` and `robots.txt` under `render: 'ssg'`.
 *
 * Pinned: nothing is emitted unless `sitemap` is on under `ssg` with the scaffold; the sitemap lists
 * the prerendered PAGE views, sorted, under `siteUrl`, with escaped and encoded URLs and no
 * timestamp; a run without a usable `siteUrl` throws a named error; `angular.json` copies both
 * files to the build output; both files are generator-owned.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles, SEED_PATHS, type OutputFile } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG, type GeneratorConfig } from '../../../src/config.js';
import { SitemapConfigError } from '../../../src/generators/angular/sitemap-gen.js';
import { DomainMetadataSchema, ViewMetadataSchema, type ViewMetadata } from '../../../src/models/domain-model.js';

const cfg = (overrides: Partial<GeneratorConfig> = {}): GeneratorConfig => ({
  ...DEFAULT_CONFIG,
  render: 'ssg',
  sitemap: true,
  siteUrl: 'https://example.com',
  ...overrides,
});

const authored = [{ components: [{ type: 'HERO', binding: { source: 'STATIC' }, props: 'x' }] }];
const view = (o: Record<string, unknown> & { name: string }): ViewMetadata =>
  ViewMetadataSchema.parse({ regions: authored, ...o });

const views: ViewMetadata[] = [
  view({ name: 'Home' }),
  view({ name: 'Pricing', route: 'pricing/plans' }),
  view({ name: 'About', route: '/about-us' }),
  view({ name: 'Footer', kind: 'SECTION' }),
  view({ name: 'Post', route: 'posts/:slug' }),
  view({ name: 'Docs', route: 'docs/**' }),
  view({ name: 'Catalogue', regions: [{ components: [{ type: 'LIST', binding: { source: 'ENTITY', ref: 'Product' } }] }] }),
];

const product = DomainMetadataSchema.parse({ packageName: 'com.shop', entityName: 'Product' });

function find(files: OutputFile[], path: string): OutputFile | undefined {
  return files.find((f) => f.path === path);
}

function text(files: OutputFile[], path: string): string {
  const file = find(files, path);
  if (!file) throw new Error(`not emitted: ${path}`);
  return file.content;
}

const build = (config: GeneratorConfig, vs: ViewMetadata[] = views) => buildGeneratedFiles([product], [], config, vs);

describe('sitemap.xml and robots.txt — when they are emitted', () => {
  it.each([
    ['sitemap unset', { sitemap: undefined }],
    ['sitemap off', { sitemap: false }],
    ['render csr', { render: 'csr' as const }],
    ['scaffold off', { scaffold: false }],
  ])('emits neither with %s', (_, overrides) => {
    const files = build(cfg(overrides));
    expect(find(files, 'src/sitemap.xml')).toBeUndefined();
    expect(find(files, 'src/robots.txt')).toBeUndefined();
    expect(find(files, 'angular.json')?.content ?? '').not.toContain('sitemap.xml');
  });

  it('emits both as generator-owned files, not seeds', () => {
    const files = build(cfg());
    for (const path of ['src/sitemap.xml', 'src/robots.txt']) {
      expect(find(files, path)?.overwritable, path).toBe(true);
      expect(SEED_PATHS.has(path), path).toBe(false);
    }
  });

  it('adds the two asset entries to angular.json', () => {
    const angular = text(build(cfg()), 'angular.json');
    expect(angular).toContain('{ "glob": "robots.txt", "input": "src", "output": "/" }');
    expect(angular).toContain('{ "glob": "sitemap.xml", "input": "src", "output": "/" }');
  });
});

describe('sitemap.xml — content', () => {
  const files = build(cfg());

  it('lists the prerendered PAGE views, sorted, and nothing else', () => {
    expect(text(files, 'src/sitemap.xml')).toBe(`<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>https://example.com/about-us</loc>
  </url>
  <url>
    <loc>https://example.com/home</loc>
  </url>
  <url>
    <loc>https://example.com/pricing/plans</loc>
  </url>
</urlset>
`);
  });

  it('carries no lastmod and is independent of the view order', () => {
    expect(text(files, 'src/sitemap.xml')).not.toContain('lastmod');
    expect(text(build(cfg(), [...views].reverse()), 'src/sitemap.xml')).toBe(text(files, 'src/sitemap.xml'));
  });

  it('keeps a path prefix of siteUrl and drops its trailing slash', () => {
    const out = text(build(cfg({ siteUrl: 'https://example.com/docs/' })), 'src/sitemap.xml');
    expect(out).toContain('<loc>https://example.com/docs/home</loc>');
    expect(text(build(cfg({ siteUrl: 'https://example.com/docs/' })), 'src/robots.txt')).toContain(
      'Sitemap: https://example.com/docs/sitemap.xml',
    );
  });

  it('percent-encodes a path segment and escapes XML characters', () => {
    const out = text(build(cfg(), [view({ name: 'Q', route: 'a&b/c d' })]), 'src/sitemap.xml');
    expect(out).toContain('<loc>https://example.com/a%26b/c%20d</loc>');
    const escaped = text(build(cfg({ siteUrl: "https://example.com/a'b" }), [view({ name: 'Q', route: 'x' })]), 'src/sitemap.xml');
    expect(escaped).toContain('<loc>https://example.com/a&apos;b/x</loc>');
  });

  it('omits the sitemap and its robots line when no page is prerendered', () => {
    const none = build(cfg(), [view({ name: 'Post', route: 'posts/:slug' })]);
    expect(find(none, 'src/sitemap.xml')).toBeUndefined();
    expect(text(none, 'src/robots.txt')).toBe('User-agent: *\nAllow: /\n');
  });
});

describe('robots.txt — content', () => {
  it('allows every crawler and names the sitemap', () => {
    expect(text(build(cfg()), 'src/robots.txt')).toBe(
      'User-agent: *\nAllow: /\nSitemap: https://example.com/sitemap.xml\n',
    );
  });
});

describe('sitemap — the base URL', () => {
  it('throws a named error naming siteUrl when it is absent', () => {
    expect(() => build(cfg({ siteUrl: undefined }))).toThrow(SitemapConfigError);
    expect(() => build(cfg({ siteUrl: undefined }))).toThrow(/needs "siteUrl"|need "siteUrl"/);
  });

  it.each(['/relative', 'example.com', 'ftp://example.com', 'https://example.com/?q=1', 'https://example.com/#top'])(
    'throws for %s',
    (siteUrl) => {
      expect(() => build(cfg({ siteUrl }))).toThrow(SitemapConfigError);
    },
  );
});
