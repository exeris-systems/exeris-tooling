/**
 * The emitted app is Tailwind CSS v4 only. The scaffold carries no v3 directive, JavaScript config
 * or v3 PostCSS setup; no emitted template — the shell, the `@View` pages and the entity list,
 * detail and form components — writes a class name v4 removed, keeps only as a deprecated alias, or
 * changed the meaning of; and the typography plugin is installed exactly when a template uses
 * `prose`.
 *
 * Every form control is drawn with a border. v4's preflight resets borders to `0 solid` and ships
 * no forms plugin, so a control that names only a border or ring colour renders without one. An
 * emitted `<input>`, `<select>` or `<textarea>` therefore carries the kit's field class
 * (`exeris-input`, `exeris-select`, `exeris-textarea`, `exeris-checkbox`), which sets its border,
 * ring and padding; or, styled by utilities, it sets a border or ring width beside the colour.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles, type OutputFile } from '../src/orchestrator.js';
import { DomainMetadataSchema, ViewMetadataSchema } from '../src/models/domain-model.js';
import { DEFAULT_CONFIG } from '../src/config.js';

/**
 * An entity whose list, detail and form emit every control kind and panel: a filter per filter
 * kind, an enum badge, a boolean badge, a row and header action, a computed field and the
 * conflict panel of a versioned entity.
 */
const order = DomainMetadataSchema.parse({
  entityName: 'Order',
  packageName: 'com.shop',
  versioned: true,
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'note', type: 'String', filterable: true, required: true },
    { name: 'paid', type: 'java.lang.Boolean', filterable: true },
    { name: 'total', type: 'java.lang.Integer', filterable: true },
    { name: 'placedOn', type: 'java.time.LocalDate', filterable: true },
    { name: 'status', type: 'com.shop.OrderStatus', filterable: true },
    { name: 'summary', type: 'String', computed: true, computedFrom: ['note'] },
    { name: 'version', type: 'java.lang.Long' },
  ],
  actions: [{ name: 'markPaid', methodName: 'markPaid' }],
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
  entity: buildGeneratedFiles([order], [orderStatus], DEFAULT_CONFIG),
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

const scanned = (path: string) => /\.(ts|html|css)$/.test(path);

/** The kit field class each control element carries; a checkbox input carries `exeris-checkbox`. */
function kitFieldClass(tag: string, type: string | undefined): string {
  if (tag === 'select') return 'exeris-select';
  if (tag === 'textarea') return 'exeris-textarea';
  return type === 'checkbox' ? 'exeris-checkbox' : 'exeris-input';
}

/** A non-zero border or ring width utility: `border`, `border-2`, `border-x`, `ring-1`, … */
const EDGE_WIDTH = /^(?:border(?:-[xytrbse])?(?:-[1-9]\d*)?|ring(?:-[1-9]\d*)?)$/;
/** A border or ring utility that sets neither a width nor a style, position or offset: a colour. */
const EDGE_COLOUR = /^(?:border|ring)-(?!(?:[xytrbse]-)?\d+$|[xytrbse]$|inset$|offset-|solid$|dashed$|dotted$|double$|hidden$|none$)/;

/**
 * Every `<input>`, `<select>` and `<textarea>` a file writes, but a hidden or radio input, that
 * neither carries its kit field class nor draws its own edge: a border or ring colour beside a
 * border or ring width.
 */
function bareControls(path: string, content: string): string[] {
  const offenders: string[] = [];
  for (const m of content.matchAll(/<(input|select|textarea)\b([^>]*)>/g)) {
    const [tag, attrs] = [m[1], m[2]];
    const type = /\btype="([^"]*)"/.exec(attrs)?.[1];
    if (type === 'hidden' || type === 'radio') continue;
    const classes = (/\bclass="([^"]*)"/.exec(attrs)?.[1] ?? '').split(/\s+/).filter((t) => t.length > 0);
    if (classes.includes(kitFieldClass(tag, type))) continue;
    const bare = classes.map((t) => t.slice(t.lastIndexOf(':') + 1));
    if (bare.some((t) => EDGE_COLOUR.test(t)) && bare.some((t) => EDGE_WIDTH.test(t))) continue;
    offenders.push(`${path}: <${tag}${type ? ` type="${type}"` : ''}> class="${classes.join(' ')}"`);
  }
  return offenders;
}

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

  it('draws every form control with a border: the kit field class, or an edge width beside its colour', () => {
    const offenders = files.filter((f) => scanned(f.path)).flatMap((f) => bareControls(f.path, f.content));
    expect(offenders).toEqual([]);
  });

  it('carries no v3 directive and no JavaScript config', () => {
    for (const f of files) {
      expect(f.content, f.path).not.toMatch(/@tailwind\s+(base|components|utilities)/);
      expect(f.path).not.toMatch(/tailwind\.config\.[cm]?[jt]s$/);
    }
  });

  it('installs the v4 PostCSS plugin and none of the v3 PostCSS setup', () => {
    const pkg = JSON.parse(fileAt(files, 'package.json'));
    expect(pkg.devDependencies.tailwindcss).toBe('^4.0.0');
    expect(pkg.devDependencies['@tailwindcss/postcss']).toBe('^4.0.0');
    for (const v3 of ['autoprefixer', 'postcss-import']) {
      expect(pkg.devDependencies[v3], v3).toBeUndefined();
      expect(pkg.dependencies[v3], v3).toBeUndefined();
    }
    expect(JSON.parse(fileAt(files, '.postcssrc.json'))).toEqual({ plugins: { '@tailwindcss/postcss': {} } });
  });

  it('the shell header carries shadow-sm, not the bare v3 shadow', () => {
    expect(fileAt(files, 'src/app/app.component.ts')).toContain('<header class="bg-white dark:bg-gray-800 shadow-sm">');
  });
});

/** The view-only app renders a RICH_TEXT block (`prose dark:prose-invert`); the entity app none. */
/** The entity app's list, detail and form are inside the scan, and their controls are kit fields. */
describe('the entity components are scanned and style their controls through the kit', () => {
  it('a control with an edge colour and no edge width is reported; with a width, or the kit class, it is not', () => {
    const at = (markup: string) => bareControls('x.ts', markup);
    expect(at('<input type="text" class="mt-1 block w-full rounded-md border-gray-300 focus:ring-exeris-primary">')).toHaveLength(1);
    expect(at('<select class="rounded-md border-0 ring-inset ring-gray-300">')).toHaveLength(1);
    expect(at('<input type="checkbox" class="exeris-input">')).toHaveLength(1);
    expect(at('<input type="text" class="rounded-md border border-gray-300">')).toEqual([]);
    expect(at('<select class="ring-1 ring-inset ring-gray-300">')).toEqual([]);
    expect(at('<input type="checkbox" class="exeris-checkbox">')).toEqual([]);
    expect(at('<input type="hidden">')).toEqual([]);
  });

  const components = apps.entity.filter((f) => f.path.startsWith('src/app/components/'));

  it('the list, detail and form are emitted and scanned', () => {
    const paths = components.filter((f) => scanned(f.path)).map((f) => f.path);
    for (const view of ['list', 'detail', 'form']) {
      expect(paths).toContain(`src/app/components/order-${view}.component.ts`);
    }
  });

  it('every control they write is a kit field, and there is one of each kind', () => {
    const controls = components.flatMap((f) => [...f.content.matchAll(/<(input|select)\b[^>]*>/g)].map((m) => m[0]));
    expect(controls.length).toBeGreaterThan(8);
    for (const kit of ['exeris-input', 'exeris-select', 'exeris-checkbox']) {
      expect(controls.some((c) => c.includes(`class="${kit}`)), kit).toBe(true);
    }
    expect(components.flatMap((f) => bareControls(f.path, f.content))).toEqual([]);
  });
});

describe('the typography plugin is installed exactly when a template uses prose', () => {
  const pkg = (files: OutputFile[]) => JSON.parse(fileAt(files, 'package.json'));

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
