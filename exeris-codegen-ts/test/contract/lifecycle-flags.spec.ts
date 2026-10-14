/**
 * The TypeScript `Create` and `Update` types of an entity written by the real processor, against the
 * Java bodies. `exeris-e2e-tests/src/test/resources/contract/lifecycle-flags/` holds the fields the
 * processor writes for a `@Field(inCreate = false)` and a `@Field(inUpdate = false)` field (a false
 * flag is an absent key on the wire) and the property names of the Java create and update bodies;
 * `LifecycleFlagsContractE2ETest` holds the Java side to the same files.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { createGeneratorContext } from '../../src/core/generator-registry.js';
import { withPrimaryKey } from '../../src/core/primary-key.js';
import { TypeGenerator } from '../../src/generators/api/type-gen.js';
import { FormGenerator } from '../../src/generators/angular/form-gen.js';
import { DomainMetadataSchema } from '../../src/models/domain-model.js';

// A missing file must fail the suite, not skip it: a check that cannot find its contract proves nothing.
const CONTRACT = new URL('../../../exeris-e2e-tests/src/test/resources/contract/lifecycle-flags/', import.meta.url);
const read = (file: string): unknown => JSON.parse(readFileSync(fileURLToPath(new URL(file, CONTRACT)), 'utf8'));
const metadata = DomainMetadataSchema.parse(read('exeris-metadata/Ticket.json'));
const java = (read('java-bodies.json') as Record<string, { create: string[]; update: string[] }>)['Ticket']!;

const domain = withPrimaryKey(metadata);
const context = createGeneratorContext({ generateZod: true });
const types = new TypeGenerator().generate(domain, context)!.content;

/** The member names of `export interface <name> {`. */
function interfaceMembers(name: string): string[] {
  const start = types.indexOf(`export interface ${name} {`);
  expect(start, `interface ${name}`).toBeGreaterThan(-1);
  return [...types.slice(start, types.indexOf('\n}', start)).matchAll(/^ {2}(\w+)\??:/gm)].map((m) => m[1]!).sort();
}

/** The names the `Omit<Ticket, …>` of `export type <name>` drops from the entity. */
function omitted(name: string): string[] {
  const declaration = new RegExp(`export type ${name} = ([^;]*);`).exec(types)![1]!;
  const omit = /^Omit<\w+, ([^>]*)>/.exec(declaration);
  return omit ? [...omit[1]!.matchAll(/'(\w+)'/g)].map((m) => m[1]!) : [];
}

describe('the processor-written Ticket: inCreate = false and inUpdate = false', () => {
  it('reads a field without the keys as left out of both bodies', () => {
    const fixed = metadata.fields.find((f) => f.name === 'fixed')!;
    expect([fixed.inCreate, fixed.inUpdate]).toEqual([false, false]);
  });

  it('TicketCreate carries the properties of the Java create body', () => {
    const create = interfaceMembers('TicketCreate');
    expect(create).toEqual(java.create);
    expect(create).not.toContain('slug');
    expect(create).not.toContain('fixed');
  });

  it('TicketUpdate carries the properties of the Java update body', () => {
    const entity = interfaceMembers('Ticket');
    const update = entity.filter((p) => !omitted('TicketUpdate').includes(p));
    expect(update.filter((p) => p !== 'id')).toEqual(java.update);
    expect(update).not.toContain('code');
    expect(update).not.toContain('fixed');
  });

  it('the form offers a field only to the form its flags name', () => {
    const form = new FormGenerator().generate(domain, context)!.content;
    expect(form).toContain('field-title');
    expect(form).toContain('field-code');
    expect(form).toContain('field-slug');
    expect(form).not.toContain('field-fixed');
  });
});
