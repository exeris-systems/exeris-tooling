/**
 * Coverage for src/generators/angular/view-gen.ts — the presentation-IR
 * (@View) emitter (RFC-2026-06-28 §3). Emits ONE standalone, signal-first
 * Angular 22 page component per ViewMetadata, plus its paired lazy route.
 *
 * The fixture is a PAGE view with two regions and a recursive CARD-in-GRID,
 * exercising ENTITY + STATIC + ACTION bindings (honoured in slice 1) and a
 * PROJECTION/expression binding (the OUT path → TODO passthrough). Asserts:
 *   - standalone component, region <section data-region="…"> in order
 *   - recursive children rendered (CARD inside GRID)
 *   - inject(<Ref>Service) for ENTITY + a signal read referencing it
 *   - a click handler stub for ACTION
 *   - TODO(@View G#) markers for the OUT bindings (never faked)
 *   - the paired route → component
 *   - determinism: same input → byte-identical output
 */

import { describe, expect, it } from 'vitest';
import { generateView, generateViewRoute } from '../../../src/generators/angular/view-gen.js';
import { ViewMetadataSchema, type ViewMetadata } from '../../../src/models/domain-model.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';

/**
 * ProductLanding (PAGE /products)
 *   ├─ header → region
 *   │     └─ HERO  @Bind(STATIC) props="Welcome"
 *   └─ body   → region
 *         ├─ GRID
 *         │     └─ CARD  @Bind(ENTITY ref=Product path=name)   (recursion)
 *         ├─ LIST  @Bind(ACTION ref=refresh-list)
 *         └─ RICH_TEXT @Bind(PROJECTION ref=Summary path=blurb) (OUT → TODO)
 */
function productLanding(): ViewMetadata {
  return ViewMetadataSchema.parse({
    name: 'ProductLanding',
    kind: 'PAGE',
    route: '/products',
    title: 'Products',
    regions: [
      {
        slot: 'header',
        components: [
          { type: 'HERO', binding: { source: 'STATIC' }, props: 'Welcome' },
        ],
      },
      {
        slot: 'body',
        components: [
          {
            type: 'GRID',
            children: [
              {
                type: 'CARD',
                binding: { source: 'ENTITY', ref: 'Product', path: 'name' },
              },
            ],
          },
          { type: 'LIST', binding: { source: 'ACTION', ref: 'refresh-list' } },
          {
            type: 'RICH_TEXT',
            binding: { source: 'PROJECTION', ref: 'Summary', path: 'blurb' },
          },
        ],
      },
    ],
  });
}

describe('generateView — component shape', () => {
  const file = generateView(productLanding(), DEFAULT_CONFIG);

  it('emits at pages/<kebab>.component.ts', () => {
    expect(file.path).toBe('pages/product-landing.component.ts');
  });

  it('is a standalone, OnPush, signal-first component with the view title', () => {
    expect(file.content).toContain('@Component({');
    expect(file.content).toContain('standalone: true,');
    expect(file.content).toContain('changeDetection: ChangeDetectionStrategy.OnPush,');
    expect(file.content).toContain("selector: 'app-product-landing-page',");
    expect(file.content).toContain('export class ProductLandingPageComponent implements OnInit {');
    expect(file.content).toContain('>Products</h1>');
  });

  it('renders region <section data-region="…"> in declaration order', () => {
    const headerIdx = file.content.indexOf('<section data-region="header">');
    const bodyIdx = file.content.indexOf('<section data-region="body">');
    expect(headerIdx).toBeGreaterThan(-1);
    expect(bodyIdx).toBeGreaterThan(-1);
    expect(headerIdx).toBeLessThan(bodyIdx);
  });

  it('maps BlockType to its element (HERO/GRID/LIST/CARD/RICH_TEXT)', () => {
    expect(file.content).toContain('data-block="HERO"');
    expect(file.content).toContain('data-block="GRID"');
    expect(file.content).toContain('data-block="LIST"');
    expect(file.content).toContain('data-block="CARD"');
    expect(file.content).toContain('data-block="RICH_TEXT"');
    // ui-kit token utility consistent with U1.
    expect(file.content).toContain('bg-exeris-primary');
  });

  it('renders recursive children — the CARD lives inside the GRID', () => {
    const gridIdx = file.content.indexOf('data-block="GRID"');
    const cardIdx = file.content.indexOf('data-block="CARD"');
    expect(gridIdx).toBeGreaterThan(-1);
    expect(cardIdx).toBeGreaterThan(gridIdx);
  });

  it('STATIC binding renders the authored props text', () => {
    expect(file.content).toContain('Welcome');
  });

  it('ENTITY binding injects the signal STORE and reads it', () => {
    // Was the RxJS service + a `current()` call that no generator produces: service-gen emits
    // findAll/findById/create/update/delete returning Observables, with no signal at all. store-gen is
    // the signal-first surface — `entities` (collection) and `selected` (single). Two generators in one
    // package had disagreed, and tsc could not see it because the call lives in a template string.
    expect(file.content).toContain(
      "import { ProductStore } from '../stores/product.store';",
    );
    expect(file.content).toContain('protected readonly productStore = inject(ProductStore);');
    // A store starts empty, so the page asks for its data — without this the emitted screen renders
    // correctly and permanently blank.
    expect(file.content).toContain('void this.productStore.loadAll();');
  });

  it('an UNBOUND collection block does not iterate — its bound child reads the selected row', () => {
    // The rule: the *collection block itself* carries the collection binding. This fixture's GRID
    // binds nothing and its CARD binds ENTITY, which says "one card showing the selected product",
    // not "a card per product" — and the emitter must not guess otherwise.
    expect(file.content).toContain('{{ productStore.selected()?.name }}');
    expect(file.content).not.toContain('current()');
  });

  it('ACTION binding emits a click handler + a handler stub method', () => {
    // kebab ref normalised to a valid camelCase method name.
    expect(file.content).toContain('(click)="refreshList()"');
    expect(file.content).toContain('protected refreshList(): void {');
    expect(file.content).toContain("// TODO(@View): wire the 'refresh-list' action");
  });

  it('PROJECTION binding (OUT) is a clearly-commented TODO passthrough, never faked', () => {
    expect(file.content).toContain('TODO(@View G1): PROJECTION binding');
    expect(file.content).toContain('ref="Summary"');
    expect(file.content).toContain('path="blurb"');
  });

  it('is deterministic — same input yields byte-identical output', () => {
    const again = generateView(productLanding(), DEFAULT_CONFIG);
    expect(again.content).toBe(file.content);
    // no timestamp / random leakage
    expect(file.content).not.toMatch(/\d{4}-\d{2}-\d{2}T/);
  });
});

describe('generateView — OUT binding markers (G1/G2/G6)', () => {
  it('expression-carrying binding → TODO(@View G1)', () => {
    const view = ViewMetadataSchema.parse({
      name: 'ExprView',
      regions: [{ slot: 'main', components: [
        { type: 'LIST', binding: { source: 'ENTITY', ref: 'Order', expression: 'lines of currentOrder' } },
      ] }],
    });
    const f = generateView(view, DEFAULT_CONFIG);
    expect(f.content).toContain('TODO(@View G1): parameterised/relational binding via expression');
  });

  it('STREAM/expression language → TODO(@View G2)', () => {
    const view = ViewMetadataSchema.parse({
      name: 'StreamView',
      regions: [{ slot: 'main', components: [
        { type: 'LIST', binding: { source: 'ENTITY', ref: 'Tick', language: 'sse' } },
      ] }],
    });
    const f = generateView(view, DEFAULT_CONFIG);
    expect(f.content).toContain('TODO(@View G2)');
  });

  it('SLOT binding → ng-content host slot + TODO(@View G6)', () => {
    const view = ViewMetadataSchema.parse({
      name: 'SlotView',
      regions: [{ slot: 'main', components: [
        { type: 'SLOT', binding: { source: 'SLOT', ref: 'aside' } },
      ] }],
    });
    const f = generateView(view, DEFAULT_CONFIG);
    expect(f.content).toContain('<ng-content select="[slot=aside]"></ng-content>');
    expect(f.content).toContain('TODO(@View G6)');
  });
});

describe('generateView — CUSTOM + FORM blocks', () => {
  it('CUSTOM renders the named customType selector element', () => {
    const view = ViewMetadataSchema.parse({
      name: 'CustomView',
      regions: [{ slot: 'main', components: [
        { type: 'CUSTOM', customType: 'StarRating' },
      ] }],
    });
    const f = generateView(view, DEFAULT_CONFIG);
    expect(f.content).toContain('<star-rating></star-rating>');
  });

  it('FORM is a placeholder block (leaf-field form emission is slice 2)', () => {
    const view = ViewMetadataSchema.parse({
      name: 'FormView',
      regions: [{ slot: 'main', components: [{ type: 'FORM' }] }],
    });
    const f = generateView(view, DEFAULT_CONFIG);
    expect(f.content).toContain('exeris-form-placeholder');
    expect(f.content).toContain('FORM block — leaf-field form emission defers');
  });
});

describe('generateView — no ENTITY bindings omits inject import', () => {
  it('does not import inject when no ENTITY ref exists', () => {
    const view = ViewMetadataSchema.parse({
      name: 'StaticOnly',
      regions: [{ slot: 'main', components: [{ type: 'HERO', binding: { source: 'STATIC' } }] }],
    });
    const f = generateView(view, DEFAULT_CONFIG);
    expect(f.content).toContain("import { Component, ChangeDetectionStrategy } from '@angular/core';");
    expect(f.content).not.toContain('inject(');
  });
});

describe('generateViewRoute — paired lazy route', () => {
  it('emits pages/<kebab>.route.ts → the component, route from @View.route', () => {
    const f = generateViewRoute(productLanding(), DEFAULT_CONFIG);
    expect(f.path).toBe('pages/product-landing.route.ts');
    expect(f.content).toContain("import { Routes } from '@angular/router';");
    expect(f.content).toContain('export const productLandingRoutes: Routes = [');
    // Leading slash stripped — Angular child route paths are relative.
    expect(f.content).toContain("path: 'products',");
    expect(f.content).toContain(
      "loadComponent: () => import('./product-landing.component').then((m) => m.ProductLandingPageComponent),",
    );
    expect(f.content).toContain("title: 'Products',");
  });

  it('falls back to the kebab name when no route is declared', () => {
    const view = ViewMetadataSchema.parse({ name: 'NoRoute' });
    const f = generateViewRoute(view, DEFAULT_CONFIG);
    expect(f.content).toContain("path: 'no-route',");
  });

  it('escapes the title as a TS string literal, not HTML', () => {
    const view = ViewMetadataSchema.parse({ name: 'Shop', route: '/shop', title: "Tom's Books & More" });
    const f = generateViewRoute(view, DEFAULT_CONFIG);
    // The route is a TypeScript file — the title is a single-quoted string literal,
    // so & stays literal (NOT &amp;) and the apostrophe is backslash-escaped.
    expect(f.content).toContain("title: 'Tom\\'s Books & More',");
    expect(f.content).not.toContain('&amp;');
  });
});

describe('generateView — a kebab-case @View.name emits VALID TypeScript identifiers', () => {
  /**
   * The regression guard for the bug Stellar's first real @View hit: `@View.name` was interpolated
   * raw into the class and route-const names, so the natural kebab name (it doubles as the file name
   * and the `data-view` value) emitted `export class commander-rosterPageComponent` — TS1005, the file
   * did not parse. Exactly the bug `DslMapper.toMethodName` was introduced to prevent for @Action
   * names, one identifier kind later.
   *
   * The existing fixture above cannot catch it: `ProductLanding` is already PascalCase, so the raw
   * interpolation happened to produce a legal identifier.
   */
  const kebabView: ViewMetadata = ViewMetadataSchema.parse({
    name: 'commander-roster',
    kind: 'PAGE',
    route: '/roster',
    title: 'Commander Roster',
    regions: [],
  });

  it('the component class name is PascalCase, not the raw kebab name', () => {
    const file = generateView(kebabView, DEFAULT_CONFIG);
    expect(file.content).toContain('export class CommanderRosterPageComponent');
    expect(file.content).not.toContain('commander-rosterPageComponent');
    // The kebab form still names the file and the markup — only the identifier is normalised.
    expect(file.path).toBe('pages/commander-roster.component.ts');
    expect(file.content).toContain('data-view="commander-roster"');
    expect(file.content).toContain("selector: 'app-commander-roster-page',");
  });

  it('the route const is camelCase and loads the class the component actually exports', () => {
    const route = generateViewRoute(kebabView, DEFAULT_CONFIG);
    expect(route.content).toContain('export const commanderRosterRoutes: Routes = [');
    expect(route.content).not.toContain('commander-rosterRoutes');
    // The two files must agree on the class name or loadComponent resolves to undefined at runtime.
    expect(route.content).toContain('.then((m) => m.CommanderRosterPageComponent)');
  });
});

describe('generateView — a collection block BOUND to an entity iterates its store signal', () => {
  /**
   * The other half of the `current()` fix. A LIST/GRID that carries the ENTITY binding is a
   * collection: it must emit `@for` over the store's `entities()` signal, and its children must read
   * the loop variable. Emitting the read without the iteration was the original shape — a `<ul>` whose
   * children rendered once, so a roster showed one row.
   */
  const roster: ViewMetadata = ViewMetadataSchema.parse({
    name: 'commander-roster',
    kind: 'PAGE',
    route: '/roster',
    title: 'Commander Roster',
    regions: [
      {
        slot: 'content',
        components: [
          {
            type: 'LIST',
            binding: { source: 'ENTITY', ref: 'Commander' },
            children: [
              {
                type: 'CARD',
                binding: { source: 'ENTITY', ref: 'Commander', path: 'name' },
                children: [],
              },
            ],
          },
        ],
      },
    ],
  });

  const file = generateView(roster, DEFAULT_CONFIG);

  it('emits @for over the store collection with a stable track key', () => {
    expect(file.content).toContain('@for (commander of commanderStore.entities(); track commander.id) {');
  });

  it('the row reads the loop variable, not the selected row', () => {
    expect(file.content).toContain('{{ commander.name }}');
    expect(file.content).not.toContain('commanderStore.selected()');
  });

  it('is deterministic — same input yields byte-identical output', () => {
    expect(generateView(roster, DEFAULT_CONFIG).content).toBe(file.content);
  });
});
