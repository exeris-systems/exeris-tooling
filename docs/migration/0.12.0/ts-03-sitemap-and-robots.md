---
title: "0.12.0 migration step: `exeris-codegen-ts`: sitemap.xml and robots.txt for a prerendered site"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-28
---

### `exeris-codegen-ts`: sitemap.xml and robots.txt for a prerendered site

Under `render: 'ssg'` with the scaffold on, two keys in `exeris-codegen.json` ask for a sitemap:

```json
{ "render": "ssg", "sitemap": true, "siteUrl": "https://example.com" }
```

The run then writes `src/sitemap.xml` and `src/robots.txt`, and the generated `angular.json` copies both to the root of the build output. For two `PAGE` views, `home` and `about-us`, the files are:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>https://example.com/about-us</loc>
  </url>
  <url>
    <loc>https://example.com/home</loc>
  </url>
</urlset>
```

```
User-agent: *
Allow: /
Sitemap: https://example.com/sitemap.xml
```

The sitemap lists the pages the static build prerenders: `PAGE` views whose route has no parameter or wildcard and that read no entity data. A parameterised page, an entity page, a `SECTION` view and the `''` redirect page are not listed. The URLs are sorted, XML-escaped and percent-encoded per path segment, and the files carry no `lastmod`, so the bytes depend on the metadata and the configuration only. A path prefix in `siteUrl` (`https://example.com/docs`) is kept. With no page to list, `sitemap.xml` is not written and `robots.txt` has no `Sitemap` line.

`sitemap: true` without `siteUrl`, or with a `siteUrl` that is not an absolute `http(s)` URL without a query or fragment, stops generation with `SitemapConfigError` and writes nothing: the generator knows no host to build absolute URLs from.

Ownership: `src/sitemap.xml` and `src/robots.txt` are generator-owned. Every run rewrites them, and a run that no longer produces them deletes them; edits to them are lost, so a hand-written `robots.txt` belongs in an app that leaves `sitemap` off. `angular.json` is a seed the generator writes once, so an app that already has one must add the two asset entries itself, next to the favicon entry:

```json
{ "glob": "robots.txt", "input": "src", "output": "/" },
{ "glob": "sitemap.xml", "input": "src", "output": "/" }
```

The change does not apply to an app with `render: 'csr'` (the default), to one with `scaffold: false` (the consumer owns the app and its assets; nothing is emitted), or to one that leaves `sitemap` unset: its output is unchanged. The two keys are file-only and have no CLI flag.
