/**
 * Coverage for src/models/model-naming.ts, and the property it exists to hold:
 * no entity name produces an emitted module that declares or imports the same
 * identifier twice.
 *
 * The interesting part is where the candidate names come from. They are not a
 * hand-written list of Angular exports — they are read back out of freshly
 * generated output, so a new framework import or a new emitted helper type
 * enters the candidate set on its own, and this spec fails if
 * RESERVED_MODULE_IDENTIFIERS has not kept up.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../../src/orchestrator.js';
import { DomainMetadataSchema } from '../../src/models/domain-model.js';
import { DEFAULT_CONFIG } from '../../src/config.js';
import { modelTypeName, RESERVED_MODULE_IDENTIFIERS } from '../../src/models/model-naming.js';

const IMPORT = /import\s+(?:type\s+)?\{([^}]*)\} from '([^']*)'/g;
/** `const` and `function` introduce a value; `class` and `enum` introduce both. */
const VALUE_DECL = /^export (const|function) (\w+)/gm;
const DUAL_DECL = /^export (class|enum) (\w+)/gm;
const TYPE_DECL = /^export (?:interface|type) (\w+)/gm;

const ENUMS = [{
  name: 'Status',
  packageName: 'com.shop',
  values: [{ name: 'NEW', displayName: 'New', ordinal: 0 }],
}];

function appFor(entityName: string) {
  const domain = DomainMetadataSchema.parse({
    packageName: 'com.shop',
    entityName,
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'name', type: 'String' },
      { name: 'when', type: 'java.time.Instant' },
      { name: 'status', type: 'com.shop.Status', enumType: 'com.shop.Status' },
    ],
    actions: [{ name: 'approve', methodName: 'approve' }],
  });
  return buildGeneratedFiles([domain], ENUMS, DEFAULT_CONFIG);
}

/**
 * Names bound in one module's scope, counted per namespace.
 *
 * TypeScript keeps values and types apart, which is why the emitted enums file may write
 * `export const Status` beside `export type Status` and still compile. A name bound twice in the
 * *same* namespace is the error this looks for.
 */
function collisionsIn(content: string): string[] {
  const values = new Map<string, string>();
  const types = new Map<string, string>();
  const found: string[] = [];

  const bind = (name: string, origin: string, asValue: boolean, asType: boolean) => {
    if (asValue && values.has(name)) {
      found.push(`'${name}' bound as a value by ${values.get(name)} and ${origin}`);
      return;
    }
    if (asType && types.has(name)) {
      found.push(`'${name}' bound as a type by ${types.get(name)} and ${origin}`);
      return;
    }
    if (asValue) values.set(name, origin);
    if (asType) types.set(name, origin);
  };

  for (const m of content.matchAll(IMPORT)) {
    const wholeClauseIsTypeOnly = /import\s+type\s/.test(m[0]);
    for (const raw of m[1].split(',')) {
      const entry = raw.trim();
      if (!entry) continue;
      const typeOnly = wholeClauseIsTypeOnly || /^type\s/.test(entry);
      const name = entry.replace(/^type\s+/, '').split(/\s+as\s+/).pop()?.trim();
      if (name) bind(name, `import '${m[2]}'`, !typeOnly, true);
    }
  }
  for (const m of content.matchAll(VALUE_DECL)) bind(m[2], `export ${m[1]}`, true, false);
  for (const m of content.matchAll(DUAL_DECL)) bind(m[2], `export ${m[1]}`, true, true);
  for (const m of content.matchAll(TYPE_DECL)) bind(m[1], 'a type declaration', false, true);
  return found;
}

function collisionsFor(entityName: string): string[] {
  return appFor(entityName).flatMap((file) =>
    collisionsIn(file.content).map((c) => `${file.path}: ${c}`));
}

describe('modelTypeName', () => {
  it('leaves an ordinary entity name alone', () => {
    expect(modelTypeName('Order')).toBe('Order');
    expect(modelTypeName('Product')).toBe('Product');
  });

  it('renames only a name an emitted module already binds', () => {
    expect(modelTypeName('Component')).toBe('ComponentModel');
    expect(modelTypeName('Page')).toBe('PageModel');
    expect(modelTypeName('Observable')).toBe('ObservableModel');
  });
});

describe('emitted modules bind each identifier once', () => {
  it('holds for an ordinary entity', () => {
    expect(collisionsFor('Order')).toEqual([]);
  });

  it('holds for every identifier the generated app itself brings into scope', () => {
    // Candidates are read back out of generated output rather than listed here: whatever the
    // emitters import from a package, plus the helper types they declare.
    const candidates = new Set<string>(['Page', 'PageRequest']);
    for (const file of appFor('Order')) {
      for (const m of file.content.matchAll(IMPORT)) {
        if (m[2].startsWith('.')) continue;
        for (const raw of m[1].split(',')) {
          const name = raw.trim().replace(/^type\s+/, '').split(/\s+as\s+/).pop()?.trim();
          if (name && /^[A-Z]/.test(name)) candidates.add(name);
        }
      }
    }

    expect(candidates.size).toBeGreaterThan(15);
    const broken = [...candidates].sort().filter((name) => collisionsFor(name).length > 0);
    expect(broken).toEqual([]);
  });

  it('covers each of those candidates in the reserved set', () => {
    // The set is what makes the rename fire; a candidate missing from it would only show up as
    // a collision above, and this says which one instead of leaving it to be diagnosed.
    const fromOutput = [...new Set(
      appFor('Order').flatMap((file) => [...file.content.matchAll(IMPORT)]
        .filter((m) => !m[2].startsWith('.'))
        .flatMap((m) => m[1].split(',')
          .map((raw) => raw.trim().replace(/^type\s+/, '').split(/\s+as\s+/).pop()?.trim() ?? '')))
    )].filter((name) => /^[A-Z]/.test(name));

    expect(fromOutput.filter((name) => !RESERVED_MODULE_IDENTIFIERS.has(name))).toEqual([]);
  });
});

/** `export interface X`, `export const X`, and re-exports written as `export { A, B } from '…'`. */
const EXPORT_DECL = /^export (?:declare )?(?:interface|class|const|type|enum|function) (\w+)/gm;
const EXPORT_FROM = /^export\s+(?:type\s+)?\{([^}]*)\} from '([^']*)'/gm;

function resolveFrom(fromPath: string, spec: string): string {
  const dir = fromPath.split('/').slice(0, -1);
  for (const part of spec.split('/')) {
    if (part === '.') continue;
    else if (part === '..') dir.pop();
    else dir.push(part);
  }
  return `${dir.join('/')}.ts`;
}

describe('every emitted import resolves to something emitted', () => {
  it.each(['CustomerEntity', 'Component', 'Page'])('names an export the target module actually declares (%s)', (entity) => {
    // The collision spec above cannot see this failure mode: an import that binds a name once,
    // but a name the target module does not export. That is exactly how the `Entity`-suffix strip
    // stayed hidden — the types module declared `Customer`, every importer asked for
    // `CustomerEntity`, and no single file looked wrong on its own.
    const files = new Map(appFor(entity).map((f) => [f.path, f.content]));
    const exportsOf = new Map<string, Set<string>>();
    for (const [path, content] of files) {
      const names = new Set<string>();
      for (const m of content.matchAll(EXPORT_DECL)) names.add(m[1]);
      for (const m of content.matchAll(EXPORT_FROM)) {
        for (const raw of m[1].split(',')) {
          const name = raw.trim().replace(/^type\s+/, '').split(/\s+as\s+/).pop()?.trim();
          if (name) names.add(name);
        }
      }
      exportsOf.set(path, names);
    }

    // A re-export reads from the target module exactly like an import does. Leaving them out is
    // what let the emitted `src/app/index.ts` barrel ask for `<Entity>Filter` after the service
    // began exporting `<Entity>ModelFilter` — a break only `ng build` saw.
    const reads = (content: string) => [
      ...[...content.matchAll(IMPORT)].map((m) => ({ clause: m[1], from: m[2] })),
      ...[...content.matchAll(EXPORT_FROM)].map((m) => ({ clause: m[1], from: m[2] })),
    ];

    const dangling: string[] = [];
    for (const [path, content] of files) {
      for (const { clause, from } of reads(content)) {
        if (!from.startsWith('.')) continue;
        const target = resolveFrom(path, from);
        const available = exportsOf.get(target);
        if (!available) continue; // not an emitted module (ui-kit, a path we do not own)
        for (const raw of clause.split(',')) {
          const name = raw.trim().replace(/^type\s+/, '').split(/\s+as\s+/)[0]?.trim();
          if (name && !available.has(name)) dangling.push(`${path} reads '${name}' from ${target}, which does not export it`);
        }
      }
    }

    expect(dangling).toEqual([]);
  });
});
