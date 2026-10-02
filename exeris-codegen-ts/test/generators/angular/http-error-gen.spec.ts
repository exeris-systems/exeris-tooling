/**
 * Coverage for src/generators/angular/http-error-gen.ts — the app-wide status-to-message helper —
 * and for every emitted call site that routes a failed request through it.
 *
 * The emitted handler answers failures with a status and no body, so the helper's text is fixed
 * per status. Its behaviour is exercised by transpiling the emitted module and running it against
 * a stand-in `HttpErrorResponse`, since `@angular/common/http` is not installed here.
 */

import { describe, expect, it } from 'vitest';
import ts from 'typescript';
import {
  generateHttpErrorHelper,
  HTTP_ERROR_PATH,
  needsHttpErrorHelper,
} from '../../../src/generators/angular/http-error-gen.js';
import { generateDetail } from '../../../src/generators/angular/detail-gen.js';
import { generateList } from '../../../src/generators/angular/list-gen.js';
import { generateForm } from '../../../src/generators/angular/form-gen.js';
import { buildGeneratedFiles } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../../src/models/domain-model.js';

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

const ORDER = domain({
  entityName: 'Order',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'name', type: 'String' },
  ],
});

class FakeHttpErrorResponse {
  constructor(readonly status: number) {}
}

type Message = (err: unknown, context?: { entity?: string; action?: 'load' | 'save' | 'delete' }) => string;

/** The emitted module, transpiled and bound to the stand-in response class. */
function loadHelper(): Message {
  const source = generateHttpErrorHelper().content
    .replace("import { HttpErrorResponse } from '@angular/common/http';", '');
  const js = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports: Record<string, unknown> = {};
  new Function('exports', 'HttpErrorResponse', js)(exports, FakeHttpErrorResponse);
  return exports.httpErrorMessage as Message;
}

describe('generateHttpErrorHelper — emitted module', () => {
  it('lands under core/ and imports only HttpErrorResponse', () => {
    const file = generateHttpErrorHelper();
    expect(file.path).toBe('core/http-error.ts');
    expect(HTTP_ERROR_PATH).toBe('core/http-error.ts');
    expect(file.content).toContain("import { HttpErrorResponse } from '@angular/common/http';");
    expect(file.content.match(/^import /gm)).toHaveLength(1);
    expect(file.content).toContain("export type HttpErrorAction = 'load' | 'save' | 'delete';");
    expect(file.content).toContain('export function httpErrorMessage(err: unknown, context: HttpErrorContext = {}): string {');
  });

  it('is byte-identical across runs', () => {
    expect(generateHttpErrorHelper().content).toBe(generateHttpErrorHelper().content);
  });

  it('reads no error body', () => {
    const c = generateHttpErrorHelper().content;
    expect(c).not.toContain('.error');
    expect(c).not.toContain('.message');
  });
});

describe('httpErrorMessage — one message per status the handler answers', () => {
  const message = loadHelper();
  const order = { entity: 'order', action: 'save' as const };

  it('status 0: the server was not reached', () => {
    expect(message(new FakeHttpErrorResponse(0), order))
      .toBe('The server could not be reached. Check your connection and try again.');
  });

  it('400: rejected as invalid', () => {
    expect(message(new FakeHttpErrorResponse(400), order))
      .toBe('The server rejected the request to save the order as invalid.');
  });

  it('404: the row is gone', () => {
    expect(message(new FakeHttpErrorResponse(404), { entity: 'order', action: 'load' }))
      .toBe('The order was not found. It may have been deleted.');
  });

  it('409: a newer version exists', () => {
    expect(message(new FakeHttpErrorResponse(409), order))
      .toBe('The order was changed by someone else. Reload to see the latest version.');
  });

  it('500 and any 5xx: the server could not complete it', () => {
    expect(message(new FakeHttpErrorResponse(500), { entity: 'order', action: 'delete' }))
      .toBe('The server could not delete the order. Try again later.');
    expect(message(new FakeHttpErrorResponse(503), { entity: 'orders', action: 'load' }))
      .toBe('The server could not load the orders. Try again later.');
  });

  it('any other status names the status', () => {
    expect(message(new FakeHttpErrorResponse(418), order)).toBe('Could not save the order (status 418).');
  });

  it('an Error whose cause is the response is read through', () => {
    const wrapped = new Error('wrapped', { cause: new FakeHttpErrorResponse(404) });
    expect(message(wrapped, { entity: 'order', action: 'load' }))
      .toBe('The order was not found. It may have been deleted.');
  });

  it('a non-HTTP failure never surfaces its raw text', () => {
    expect(message(new Error('TypeError: x is undefined'), order)).toBe('Could not save the order.');
    expect(message('boom')).toBe('Could not complete the request.');
  });

  it('without context it speaks of the record', () => {
    expect(message(new FakeHttpErrorResponse(404))).toBe('The record was not found. It may have been deleted.');
    expect(message(new FakeHttpErrorResponse(500))).toBe('The server could not complete the request. Try again later.');
  });
});

describe('orchestrator — the helper is emitted when an entity is', () => {
  const helperPath = `src/app/${HTTP_ERROR_PATH}`;

  it('emits it once for an app with an entity', () => {
    const files = buildGeneratedFiles([ORDER], [], DEFAULT_CONFIG);
    expect(files.filter((f) => f.path === helperPath)).toHaveLength(1);
  });

  it('emits none for a zero-entity app', () => {
    expect(buildGeneratedFiles([], [], DEFAULT_CONFIG).some((f) => f.path === helperPath)).toBe(false);
  });

  it('emits none when no emitter that imports it is on', () => {
    const config = {
      ...DEFAULT_CONFIG,
      generateDetails: false,
      generateLists: false,
      generateForms: false,
      generateStores: false,
      generateSagas: false,
    };
    expect(needsHttpErrorHelper([ORDER], config)).toBe(false);
    expect(buildGeneratedFiles([ORDER], [], config).some((f) => f.path === helperPath)).toBe(false);
  });
});

describe('call sites — every emitted error display goes through httpErrorMessage', () => {
  it('detail: the load error and the delete error, with no alert()', () => {
    const c = generateDetail(ORDER, DEFAULT_CONFIG).content;
    expect(c).toContain("import { httpErrorMessage } from '../core/http-error';");
    expect(c).toContain("return err ? httpErrorMessage(err, { entity: 'order', action: 'load' }) : null;");
    expect(c).toContain('readonly deleteError = signal<string | null>(null);');
    expect(c).toContain("error: (err) => this.deleteError.set(httpErrorMessage(err, { entity: 'order', action: 'delete' })),");
    expect(c).toContain('data-testid="delete-error"');
    expect(c).toContain('{{ deleteError() }}');
    expect(c).not.toContain('alert(');
    expect(c).not.toContain('err.message');
  });

  it('list: the page load and the delete, with no alert()', () => {
    const c = generateList(ORDER, DEFAULT_CONFIG)!.content;
    expect(c).toContain("import { httpErrorMessage } from '../core/http-error';");
    expect(c).toContain("this.error.set(httpErrorMessage(err, { entity: 'orders', action: 'load' }));");
    expect(c).toContain('readonly deleteError = signal<string | null>(null);');
    expect(c).toContain("this.deleteError.set(httpErrorMessage(err, { entity: 'order', action: 'delete' }));");
    expect(c).toContain('data-testid="delete-error"');
    expect(c).not.toContain('alert(');
    expect(c).not.toContain('err.message');
  });

  it('form: the load and the submit error, and the submit error is shown', () => {
    const c = generateForm(ORDER, DEFAULT_CONFIG)!.content;
    expect(c).toContain("import { httpErrorMessage } from '../core/http-error';");
    expect(c).toContain("return err ? httpErrorMessage(err, { entity: 'order', action: 'load' }) : null;");
    expect(c).toContain("this.error.set(httpErrorMessage(err, { entity: 'order', action: 'save' }));");
    expect(c).toContain('@if (error()) {');
    expect(c).toContain('data-testid="submit-error"');
    expect(c).not.toContain('err?.message');
  });

  it('form: a versioned edit still treats 409 as a conflict before the generic message', () => {
    const versioned = domain({
      entityName: 'Order',
      versioned: true,
      fields: [
        { name: 'id', type: 'java.util.UUID' },
        { name: 'name', type: 'String' },
        { name: 'version', type: 'Long' },
      ],
    });
    const c = generateForm(versioned, DEFAULT_CONFIG)!.content;
    const conflict = c.indexOf('if (this.editMode() && err?.status === 409) {');
    const generic = c.indexOf("this.error.set(httpErrorMessage(err, { entity: 'order', action: 'save' }));");
    expect(conflict).toBeGreaterThan(-1);
    expect(generic).toBeGreaterThan(conflict);
    // The direct re-fetch of a host-supplied entity reports through the helper too.
    expect(c).toContain("error: (err) => this.error.set(httpErrorMessage(err, { entity: 'order', action: 'load' })),");
  });

  it('a quote in the display name cannot close the literal', () => {
    const c = generateDetail(domain({ entityName: 'Order', displayName: "Buyer's Order" }), DEFAULT_CONFIG).content;
    expect(c).toContain("entity: 'buyer\\'s order'");
  });
});
