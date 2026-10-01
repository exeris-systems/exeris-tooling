/**
 * The emitted app is Tailwind CSS v4 only. Every class the generators write — in a `class`
 * attribute, a `routerLinkActive` list, a `[class.x]` binding or a stylesheet `@apply` — is a v4
 * utility, a kit `.exeris-*` class or a marker class, and none of them is a name v4 removed, a bare
 * name v4 keeps only as a deprecated alias, or a name whose v4 meaning changed.
 *
 * The scan runs over the same two apps the CI `ng build` compiles: the full sample and the
 * view-only one, generated from the shared fixture.
 *
 * Renamed pairs whose v3 name is still a valid v4 name (`shadow-sm`, `rounded-sm`, …) cannot be
 * caught by name, so the last block pins the places that use them to their v4 spelling.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../src/orchestrator.js';
import { DomainMetadataSchema, ViewMetadataSchema } from '../src/models/domain-model.js';
import { DEFAULT_CONFIG } from '../src/config.js';
// @ts-expect-error -- a plain ES module shared with scripts/gen-sample-app.mjs; it has no types.
import { sampleInputs } from '../scripts/sample-fixtures.mjs';

const { domains, enums, peers, config, views } = sampleInputs({ DomainMetadataSchema, ViewMetadataSchema, DEFAULT_CONFIG });

const apps = {
  full: buildGeneratedFiles(domains, enums, config, [], peers),
  'view-only': buildGeneratedFiles([], [], DEFAULT_CONFIG, views, []),
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

const scanned = (path: string) => /\.(ts|html|css)$/.test(path);

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
    const pkg = JSON.parse(files.find((f) => f.path === './package.json')!.content);
    expect(pkg.devDependencies.tailwindcss).toBe('^4.0.0');
    expect(pkg.devDependencies['@tailwindcss/postcss']).toBe('^4.0.0');
    for (const v3 of ['autoprefixer', 'postcss-import']) {
      expect(pkg.devDependencies[v3], v3).toBeUndefined();
      expect(pkg.dependencies[v3], v3).toBeUndefined();
    }
    const postcss = JSON.parse(files.find((f) => f.path === './.postcssrc.json')!.content);
    expect(postcss).toEqual({ plugins: { '@tailwindcss/postcss': {} } });
  });
});

/**
 * Every emitted form control (`<input>`, `<select>`, `<textarea>`), with its whole tag even when
 * it spans lines, and its class tokens. `type="hidden"` and `type="radio"` are left out: a hidden
 * input draws nothing, and no generator emits a radio.
 */
function formControls(content: string): Array<{ tag: string; classes: string[] }> {
  return [...content.matchAll(/<(?:input|select|textarea)\b[^>]*>/g)]
    .map((m) => m[0])
    .filter((tag) => !/\btype="(?:hidden|radio)"/.test(tag))
    .map((tag) => ({ tag, classes: (/\bclass="([^"]*)"/.exec(tag)?.[1] ?? '').split(/\s+/).filter(Boolean) }));
}

const BORDER_COLOUR = /^border-(?:exeris-[a-z-]+|[a-z]+-\d{2,3}|white|black)$/;
const BORDER_WIDTH = /^border(?:-\d+)?$/;
const RING_COLOUR = /^ring-(?:exeris-[a-z-]+|[a-z]+-\d{2,3}|white|black)$/;
const RING_WIDTH = /^ring(?:-\d+)?$/;

/** The tokens a control carries under one variant (`''` for the base state), prefix removed. */
const under = (classes: string[], variant: string) =>
  classes
    .filter((c) => c.slice(0, c.lastIndexOf(':') + 1) === variant)
    .map((c) => c.slice(variant.length));

describe.each(Object.entries(apps))('the %s app draws its form controls without a forms plugin', (_name, files) => {
  const controls = files.filter((f) => scanned(f.path)).flatMap((f) => formControls(f.content).map((c) => ({ ...c, path: f.path })));

  it('a control that colours its border also gives the border a width', () => {
    const offenders = controls
      .filter((c) => c.classes.some((t) => BORDER_COLOUR.test(t.slice(t.lastIndexOf(':') + 1))))
      .filter((c) => !under(c.classes, '').some((t) => BORDER_WIDTH.test(t)))
      .map((c) => `${c.path}: ${c.tag}`);
    expect(offenders).toEqual([]);
  });

  it('a control that colours its ring also gives the ring a width, in the same state', () => {
    const offenders = controls.flatMap((c) =>
      ['', 'focus:'].flatMap((variant) => {
        const tokens = under(c.classes, variant);
        const coloured = tokens.some((t) => RING_COLOUR.test(t));
        const wide = tokens.some((t) => RING_WIDTH.test(t)) || (variant !== '' && under(c.classes, '').some((t) => RING_WIDTH.test(t)));
        return coloured && !wide ? [`${c.path} (${variant || 'base'}): ${c.tag}`] : [];
      }),
    );
    expect(offenders).toEqual([]);
  });
});

describe('the v3 sizes are kept under their v4 names', () => {
  const full = apps.full;
  const at = (path: string) => full.find((f) => f.path === path)!.content;

  it('the shell header keeps the v3 default shadow, now shadow-sm', () => {
    expect(at('src/app/app.component.ts')).toContain('<header class="bg-white dark:bg-gray-800 shadow-sm">');
  });

  it('the detail panel keeps the v3 default shadow, now shadow-sm, and its skeleton bars rounded-sm', () => {
    const detail = at('src/app/components/order-detail.component.ts');
    expect(detail).toContain('bg-white dark:bg-gray-800 shadow-sm rounded-lg overflow-hidden');
    expect(detail).toContain('bg-gray-200 dark:bg-gray-700 rounded-sm w-1/3');
  });

  it('form controls and buttons keep the v3 small shadow, now shadow-xs, and the checkbox rounded-sm', () => {
    const form = at('src/app/components/order-form.component.ts');
    expect(form).not.toMatch(/\bshadow-sm\b/);
    expect(form).toContain('shadow-xs');
    expect(form).toContain('h-4 w-4 rounded-sm border border-gray-300');
  });

  it('the list keeps the v3 small shadow, now shadow-xs, and its skeleton bars rounded-sm', () => {
    const list = at('src/app/components/order-list.component.ts');
    expect(list).not.toMatch(/\bshadow-sm\b/);
    expect(list).toContain('shadow-xs');
    expect(list).toContain('h-4 w-32 rounded-sm bg-gray-200');
    // v4's `outline-2` sets the outline style itself, so the bare `outline` beside it is gone.
    expect(list).not.toMatch(/focus-visible:outline /);
  });
});
