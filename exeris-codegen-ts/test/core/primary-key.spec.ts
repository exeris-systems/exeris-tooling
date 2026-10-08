/**
 * The primary key: one resolver names it, and no other source under `src/` spells it out as a
 * literal.
 *
 * The route path variable (`:id`, `{id}`) and the by-id method names are not the key's field name
 * and stay out of this scan: it looks for the quoted name `'id'` and for a read of `.id` off an
 * entity row.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { DEFAULT_PRIMARY_KEY_FIELD, declaresPrimaryKey, primaryKeyField } from '../../src/core/primary-key.js';
import { DomainMetadataSchema } from '../../src/models/domain-model.js';

const SRC = join(dirname(fileURLToPath(import.meta.url)), '../../src');
const RESOLVER = 'core/primary-key.ts';
/** The metadata schema declares the default of the attribute the resolver reads. */
const SCHEMA = 'models/domain-model.ts';

const QUOTED_KEY = /(['"`])id\1/;
const ROW_KEY_READ = /\b(?:row|item|e|entity|current|result|selected)\??\.id\b/;

/** The source with its comment lines removed: prose may name the key, code may not. */
const code = (path: string): string =>
  readFileSync(join(SRC, path), 'utf8')
    .split('\n')
    .filter((line) => !/^\s*(\/\/|\*|\/\*)/.test(line))
    .join('\n');

const domain = (fields: { name: string; type: string }[]) =>
  DomainMetadataSchema.parse({ entityName: 'Order', packageName: 'com.shop', fields });

describe('primaryKeyField', () => {
  it('resolves to the default key name', () => {
    expect(primaryKeyField(domain([]))).toBe(DEFAULT_PRIMARY_KEY_FIELD);
    expect(DEFAULT_PRIMARY_KEY_FIELD).toBe('id');
  });

  it('declaresPrimaryKey is true only for an entity that declares a field of that name', () => {
    expect(declaresPrimaryKey(domain([{ name: 'id', type: 'java.util.UUID' }]))).toBe(true);
    expect(declaresPrimaryKey(domain([{ name: 'name', type: 'java.lang.String' }]))).toBe(false);
  });
});

describe('the primary key is named only by the resolver', () => {
  const sources = (readdirSync(SRC, { recursive: true }) as string[])
    .filter((p) => p.endsWith('.ts'))
    .map((p) => relative(SRC, join(SRC, p)).split(sep).join('/'));
  const scanned = sources.filter((p) => p !== RESOLVER && p !== SCHEMA);

  it('scans the generator sources', () => {
    expect(sources).toContain(RESOLVER);
    expect(sources.filter((p) => p.startsWith('generators/') && p.endsWith('-gen.ts')).length).toBeGreaterThan(15);
  });

  it('no other source under src/ contains the quoted key name', () => {
    const offenders = scanned.filter((p) => QUOTED_KEY.test(code(p)));
    expect(offenders).toEqual([]);
  });

  it('no other source under src/ reads the key off an entity row as a literal property', () => {
    const offenders = scanned.filter((p) => ROW_KEY_READ.test(code(p)));
    expect(offenders).toEqual([]);
  });

  it('the resolver contains the quoted key name once', () => {
    expect(readFileSync(join(SRC, RESOLVER), 'utf8').split("'id'").length - 1).toBe(1);
  });
});
