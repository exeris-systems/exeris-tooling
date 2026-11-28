/**
 * Sitemap and robots emission for a statically prerendered site (`render: 'ssg'`).
 *
 * `src/sitemap.xml` lists the page of every prerendered `@View` route and `src/robots.txt` allows
 * every crawler and names the sitemap. Both are generator-owned (rewritten on every run, deleted
 * when no longer produced) and reach `dist/` through the asset entries `angular.json` carries
 * when the files are emitted.
 *
 * The files are asked for with `sitemap: true`. They need `siteUrl`, an absolute `http(s)` URL: a
 * sitemap lists absolute URLs, and the generator knows no host to invent one from, so a run that
 * asks for them without it throws {@link SitemapConfigError} and writes nothing.
 *
 * A page is listed when `@View` metadata marks it a page and the static build prerenders it
 * (`prerenderedViewPaths`): a parameterised page, a page that reads entity data, an entity CRUD
 * route and the `''` redirect page are not. URLs are sorted by code unit and carry no `lastmod`,
 * so the bytes depend on the metadata and the configuration only.
 */

import type { GeneratorConfig } from '../../config.js';
import type { GeneratedFile } from './app-structure-gen.js';

export const SITEMAP_PATH = 'src/sitemap.xml';
export const ROBOTS_PATH = 'src/robots.txt';

/** The configuration asks for the files and does not say where the site is served. */
export class SitemapConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SitemapConfigError';
  }
}

/** Whether the configuration asks for sitemap.xml and robots.txt: `sitemap` on under `render: 'ssg'`. */
export function emitsSitemap(config: GeneratorConfig): boolean {
  return config.render === 'ssg' && config.sitemap === true;
}

/** The origin and path prefix of the site, without a trailing slash. */
function resolveSiteUrl(config: GeneratorConfig): string {
  const raw = config.siteUrl;
  if (raw === undefined || raw === '') {
    throw new SitemapConfigError(
      'sitemap.xml and robots.txt need "siteUrl" in exeris-codegen.json (an absolute http(s) URL such as "https://example.com"): "sitemap" is on and no siteUrl is set.',
    );
  }
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new SitemapConfigError(`"siteUrl" must be an absolute http(s) URL, got "${raw}".`);
  }
  if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.search !== '' || url.hash !== '') {
    throw new SitemapConfigError(`"siteUrl" must be an absolute http(s) URL without a query or fragment, got "${raw}".`);
  }
  return `${url.origin}${url.pathname}`.replace(/\/+$/, '');
}

function xmlEscape(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

/** The absolute URLs of the prerendered pages, sorted and without duplicates. */
function pageUrls(paths: string[], base: string): string[] {
  const urls = paths
    .filter((path) => path !== '')
    .map((path) => `${base}/${path.split('/').map(encodeURIComponent).join('/')}`);
  return [...new Set(urls)].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
}

/**
 * The `sitemap.xml` and `robots.txt` files for the route paths of the prerendered pages (no leading
 * slash), or none when the configuration does not ask for them.
 * With no prerendered page there is nothing to list: `sitemap.xml` is omitted and `robots.txt`
 * carries no `Sitemap` line.
 */
export function generateSitemapFiles(pagePaths: string[], config: GeneratorConfig): GeneratedFile[] {
  if (!emitsSitemap(config)) return [];
  const base = resolveSiteUrl(config);
  const urls = pageUrls(pagePaths, base);
  const files: GeneratedFile[] = [];
  if (urls.length > 0) {
    const entries = urls.map((url) => `  <url>\n    <loc>${xmlEscape(url)}</loc>\n  </url>\n`).join('');
    files.push({
      path: SITEMAP_PATH,
      content: `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${entries}</urlset>\n`,
      overwritable: true,
    });
  }
  const sitemapLine = urls.length > 0 ? `Sitemap: ${base}/sitemap.xml\n` : '';
  files.push({ path: ROBOTS_PATH, content: `User-agent: *\nAllow: /\n${sitemapLine}`, overwritable: true });
  return files;
}
