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
    expect(file.content).toContain('export class ProductLandingPageComponent {');
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

  it('ENTITY binding injects the service and reads it as a signal', () => {
    expect(file.content).toContain(
      "import { ProductService } from '../services/product.service';",
    );
    expect(file.content).toContain('inject(ProductService)');
    expect(file.content).toContain('protected readonly productService = inject(ProductService);');
    // The signal read references the injected service by ref.
    expect(file.content).toContain('{{ productService.current()?.name }}');
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
