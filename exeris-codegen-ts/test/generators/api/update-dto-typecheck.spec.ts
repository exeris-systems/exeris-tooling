/**
 * Type-checks the emitted `<Entity>Update` against what the generated server's PUT accepts: the
 * handler decodes the body into the whole entity and the request update writes every column but the
 * server-owned and the read-only ones, so a body that drops a required field must not compile, a
 * body that names a read-only field must not compile, and the record as read less those fields
 * must.
 */

import ts from 'typescript';
import { describe, it, expect } from 'vitest';
import { TypeGenerator } from '../../../src/generators/api/type-gen.js';
import { createGeneratorContext } from '../../../src/core/generator-registry.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../../src/models/domain-model.js';

const ctx = createGeneratorContext({ generateZod: false });

function typesOf(metadata: DomainMetadata): string {
  // The enum barrel is out of scope here: the fixture declares no enum field.
  return new TypeGenerator().generate(metadata, ctx)!.content;
}

/** Diagnostics for `usage`, compiled against the emitted types module. */
function diagnostics(types: string, usage: string): string[] {
  const files: Record<string, string> = { '/types.ts': types, '/usage.ts': usage };
  const options: ts.CompilerOptions = { strict: true, noEmit: true, target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, types: [] };
  const host = ts.createCompilerHost(options);
  const readFile = host.readFile.bind(host);
  const fileExists = host.fileExists.bind(host);
  const getSourceFile = host.getSourceFile.bind(host);
  host.readFile = (name) => files[name] ?? readFile(name);
  host.fileExists = (name) => name in files || fileExists(name);
  host.getSourceFile = (name, version) =>
    name in files ? ts.createSourceFile(name, files[name], version) : getSourceFile(name, version);
  const program = ts.createProgram(['/usage.ts'], options, host);
  return ts.getPreEmitDiagnostics(program)
    .filter((d) => d.file?.fileName === '/usage.ts')
    .map((d) => ts.flattenDiagnosticMessageText(d.messageText, '\n'));
}

const ledger = DomainMetadataSchema.parse({
  packageName: 'com.shop',
  entityName: 'Ledger',
  versioned: true,
  dataScope: 'TENANT',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'title', type: 'String', required: true },
    { name: 'code', type: 'String', readOnly: true, required: true },
    { name: 'note', type: 'String', required: true },
    { name: 'tenantId', type: 'java.util.UUID' },
  ],
});

describe('<Entity>Update — the PUT body the generated server accepts', () => {
  const types = typesOf(ledger);

  it('accepts the loaded record with its version', () => {
    const usage = [
      "import type { Ledger, LedgerUpdate } from './types';",
      'declare const loaded: Omit<Ledger, "id" | "tenantId" | "code">;',
      'export const body: LedgerUpdate = { ...loaded, title: "renamed", version: 3 };',
    ].join('\n');
    expect(diagnostics(types, usage)).toEqual([]);
  });

  it('refuses a body that leaves out a required field', () => {
    const usage = [
      "import type { LedgerUpdate } from './types';",
      'export const body: LedgerUpdate = { title: "renamed", version: 3 };',
    ].join('\n');
    expect(diagnostics(types, usage).join('\n')).toContain("Property 'note' is missing");
  });

  it('refuses a body that names a read-only field', () => {
    const usage = [
      "import type { LedgerUpdate } from './types';",
      'export const body: LedgerUpdate = { title: "renamed", note: "n", version: 3, code: "x" };',
    ].join('\n');
    expect(diagnostics(types, usage).join('\n')).toContain("'code' does not exist");
  });

  it('refuses a body without the version the edit was loaded at', () => {
    const usage = [
      "import type { Ledger, LedgerUpdate } from './types';",
      'declare const loaded: Omit<Ledger, "version">;',
      'export const body: LedgerUpdate = { ...loaded };',
    ].join('\n');
    expect(diagnostics(types, usage).join('\n')).toContain("Property 'version' is missing");
  });

  it('carries neither the key nor the owner', () => {
    const usage = [
      "import type { LedgerUpdate } from './types';",
      'type HasKey = "id" extends keyof LedgerUpdate ? true : false;',
      'type HasOwner = "tenantId" extends keyof LedgerUpdate ? true : false;',
      'export const key: HasKey = false;',
      'export const owner: HasOwner = false;',
    ].join('\n');
    expect(diagnostics(types, usage)).toEqual([]);
  });
});
