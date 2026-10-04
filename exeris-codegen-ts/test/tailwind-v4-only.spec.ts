/**
 * The emitted app is Tailwind CSS v4 only. The scaffold carries no v3 directive, JavaScript config
 * or v3 PostCSS setup; the shell and the `@View` pages write no class name v4 removed, keeps only as
 * a deprecated alias, or changed the meaning of; and the typography plugin is installed exactly when
 * a template uses `prose`.
 *
 * The entity components under `src/app/components/` are outside the class scan; their classes are
 * the subject of the codegen-ts plan's P20.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles, type OutputFile } from '../src/orchestrator.js';
import { DomainMetadataSchema, ViewMetadataSchema } from '../src/models/domain-model.js';
import { DEFAULT_CONFIG } from '../src/config.js';

const order = DomainMetadataSchema.parse({
  entityName: 'Order',
  packageName: 'com.shop',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'note', type: 'String' },
  ],
});

const views = [
  ViewMetadataSchema.parse({
    name: 'Home',
    regions: [
      {
        components: [
          { type: 'HERO', binding: { source: 'STATIC' }, props: 'Welcome' },
          { type: 'GRID', children: [{ type: 'CARD', binding: { source: 'STATIC' }, props: 'Authored' }] },
        ],
      },
    ],
  }),
  ViewMetadataSchema.parse({
    name: 'About',
    regions: [{ components: [{ type: 'RICH_TEXT', binding: { source: 'STATIC' }, props: 'About' }] }],
  }),
];

const apps: Record<string, OutputFile[]> = {
  entity: buildGeneratedFiles([order], [], DEFAULT_CONFIG),
  'view-only': buildGeneratedFiles([], [], DEFAULT_CONFIG, views),
};

/**
 * v3 names that are not v4 names: removed utilities, and the bare forms the v4 scale renamed
 * (`shadow` is `shadow-sm`, `rounded` is `rounded-sm`, …), which v4 still compiles only as
 * deprecated aliases. `ring` and `outline-none` are listed because v4 changed what they draw: a
 * 1px ring instead of 3px (`ring-3`), and a real `outline-style: none` instead of the v3
 * forced-colors-safe outline (`outline-hidden`).
 */
const V3_ONLY = new Set([
  'shadow',
  'drop-shadow',
  'blur',
  'backdrop-blur',
  'rounded',
  'ring',
  'outline-none',
  'overflow-ellipsis',
  'decoration-slice',
  'decoration-clone',
]);
const V3_ONLY_PATTERN = /^(?:(?:bg|text|border|divide|ring|placeholder)-opacity-\d+|flex-(?:shrink|grow)(?:-\d+)?)$/;

/** Every class token a file writes, with its variant prefixes (`dark:`, `sm:hover:`) removed. */
function classTokens(content: string): string[] {
  const lists: string[] = [];
  for (const m of content.matchAll(/\b(?:class|routerLinkActive)="([^"]*)"/g)) lists.push(m[1]);
  for (const m of content.matchAll(/\[class\.([^\]]+)\]/g)) lists.push(m[1]);
  for (const m of content.matchAll(/@apply\s+([^;]+);/g)) lists.push(m[1]);
  return lists
    .flatMap((list) => list.split(/\s+/))
    .filter((t) => t.length > 0)
    .map((t) => t.slice(t.lastIndexOf(':') + 1));
}

const scanned = (path: string) => /\.(ts|html|css)$/.test(path) && !path.startsWith('src/app/components/');

const fileAt = (files: OutputFile[], path: string) => files.find((f) => f.path === path)!.content;

describe.each(Object.entries(apps))('the %s app is Tailwind v4 only', (_name, files) => {
  it('scans a non-trivial set of class tokens', () => {
    const tokens = files.filter((f) => scanned(f.path)).flatMap((f) => classTokens(f.content));
    expect(tokens.length).toBeGreaterThan(20);
  });

  it('writes no class v4 removed or renamed away', () => {
    const offenders = files
      .filter((f) => scanned(f.path))
      .flatMap((f) =>
        classTokens(f.content)
          .filter((t) => V3_ONLY.has(t) || V3_ONLY_PATTERN.test(t))
          .map((t) => `${f.path}: ${t}`),
      );
    expect(offenders).toEqual([]);
  });

  it('carries no v3 directive and no JavaScript config', () => {
    for (const f of files) {
      expect(f.content, f.path).not.toMatch(/@tailwind\s+(base|components|utilities)/);
      expect(f.path).not.toMatch(/tailwind\.config\.[cm]?[jt]s$/);
    }
  });

  it('installs the v4 PostCSS plugin and none of the v3 PostCSS setup', () => {
    const pkg = JSON.parse(fileAt(files, './package.json'));
    expect(pkg.devDependencies.tailwindcss).toBe('^4.0.0');
    expect(pkg.devDependencies['@tailwindcss/postcss']).toBe('^4.0.0');
    for (const v3 of ['autoprefixer', 'postcss-import']) {
      expect(pkg.devDependencies[v3], v3).toBeUndefined();
      expect(pkg.dependencies[v3], v3).toBeUndefined();
    }
    expect(JSON.parse(fileAt(files, './.postcssrc.json'))).toEqual({ plugins: { '@tailwindcss/postcss': {} } });
  });

  it('the shell header carries shadow-sm, not the bare v3 shadow', () => {
    expect(fileAt(files, 'src/app/app.component.ts')).toContain('<header class="bg-white dark:bg-gray-800 shadow-sm">');
  });
});

/** The view-only app renders a RICH_TEXT block (`prose dark:prose-invert`); the entity app none. */
describe('the typography plugin is installed exactly when a template uses prose', () => {
  const pkg = (files: OutputFile[]) => JSON.parse(fileAt(files, './package.json'));

  it('an app with a RICH_TEXT block installs it beside tailwindcss and loads it after the imports', () => {
    const files = apps['view-only'];
    expect(files.some((f) => /class="[^"]*\bprose\b/.test(f.content))).toBe(true);
    expect(pkg(files).devDependencies['@tailwindcss/typography']).toBe('^0.5.20');
    expect(pkg(files).dependencies['@tailwindcss/typography']).toBeUndefined();
    expect(fileAt(files, 'src/styles.css')).toContain(
      '@import "tailwindcss";\n@import "@exeris/ui-kit/theme";\n@import "@exeris/ui-kit/styles";\n@plugin "@tailwindcss/typography";\n',
    );
  });

  it('an app without one carries neither the dependency nor the directive', () => {
    const files = apps.entity;
    expect(files.some((f) => /\bprose\b/.test(f.content))).toBe(false);
    expect(pkg(files).devDependencies['@tailwindcss/typography']).toBeUndefined();
    expect(pkg(files).dependencies['@tailwindcss/typography']).toBeUndefined();
    expect(fileAt(files, 'src/styles.css')).not.toContain('@plugin');
  });
});
