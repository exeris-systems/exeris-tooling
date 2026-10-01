/**
 * Coverage for src/core/scaffold-needs.ts — the one predicate that decides which backend pieces
 * the app scaffold wires.
 */

import { describe, expect, it } from 'vitest';
import { BACKEND_SCAFFOLD_NEEDS, deriveScaffoldNeeds } from '../../src/core/scaffold-needs.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../src/models/domain-model.js';

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

const file = (content: string) => ({ content });

describe('deriveScaffoldNeeds — backend', () => {
  it('no entity and no HTTP import → no backend', () => {
    const needs = deriveScaffoldNeeds([], [file("import { Component } from '@angular/core';\n")]);
    expect(needs.backend).toBe(false);
  });

  it('an emitted HTTP client import is a backend, whatever emitted it', () => {
    expect(deriveScaffoldNeeds([], [file("import { HttpClient } from '@angular/common/http';\n")]).backend).toBe(true);
    expect(
      deriveScaffoldNeeds([], [file("import { provideHttpClientTesting } from '@angular/common/http/testing';\n")]).backend,
    ).toBe(true);
  });

  it('a visible entity is a backend even when no client emitter ran (the consumer calls its API)', () => {
    expect(deriveScaffoldNeeds([domain({ entityName: 'Order' })], []).backend).toBe(true);
  });

  it('a hidden entity alone is no backend', () => {
    const hidden = domain({ entityName: 'Ledger', internalApi: { hidden: true, readOnly: false, internal: false } });
    expect(deriveScaffoldNeeds([hidden], []).backend).toBe(false);
  });

  it('ignores an indented or commented mention — only a declaration at column 0 counts', () => {
    const content = [
      "import { Component } from '@angular/core';",
      '  template: `',
      "    import { HttpClient } from '@angular/common/http';",
      '  `,',
      "// import { HttpClient } from '@angular/common/http';",
    ].join('\n');
    expect(deriveScaffoldNeeds([], [file(content)]).backend).toBe(false);
  });

  it('the default needs are those of an app with a backend', () => {
    expect(BACKEND_SCAFFOLD_NEEDS.backend).toBe(true);
  });
});

describe('deriveScaffoldNeeds — packages', () => {
  it('collects package names from named, type-only, multi-line, re-export and side-effect imports', () => {
    const content = [
      "import { z } from 'zod';",
      "import type { Signal } from '@angular/core';",
      'import {',
      '  LiveAnnouncer,',
      "} from '@angular/cdk/a11y';",
      "export * from './types/enums';",
      "import 'zone-free-polyfill';",
    ].join('\n');
    const { packages } = deriveScaffoldNeeds([], [file(content)]);
    expect([...packages].sort()).toEqual(['@angular/cdk', '@angular/core', 'zod', 'zone-free-polyfill']);
  });

  it('a relative or dynamic import is no package', () => {
    const content = [
      "import { X } from '../stores/x.store';",
      "export const routes = [{ loadComponent: () => import('./x.component') }];",
    ].join('\n');
    expect(deriveScaffoldNeeds([], [file(content)]).packages.size).toBe(0);
  });
});
