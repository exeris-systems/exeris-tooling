/**
 * Coverage for src/generators/api/enum-gen.ts — EnumGenerator class +
 * generateEnums convenience function.
 *
 * The generator emits a single types/enums.ts file containing one
 * `enum {}` block per enum metadata entry plus a DisplayNames map, a
 * Zod schema, an Options list, and a top-level enumOptions utility.
 * Tests assert on the structural markers present in the output rather
 * than full-string snapshots — keeps the tests stable across cosmetic
 * formatting changes.
 */

import { describe, expect, it } from 'vitest';
import {
  EnumGenerator,
  generateEnums,
  type FullEnumMetadata,
} from '../../../src/generators/api/enum-gen.js';
import type { GeneratorContext } from '../../../src/core/generator-registry.js';
import { createGeneratorContext } from '../../../src/core/generator-registry.js';

const CTX: GeneratorContext = createGeneratorContext({});

function fullEnum(overrides: Partial<FullEnumMetadata> & { name: string }): FullEnumMetadata {
  return {
    qualifiedName: `app.${overrides.name}`,
    packageName: 'app',
    values: [],
    ...overrides,
  };
}

// ---------- CodeGenerator contract ----------

describe('EnumGenerator — CodeGenerator metadata', () => {
  const gen = new EnumGenerator();

  it('declares name = "EnumGenerator", artifactType = "ENUM", priority = 1, all backends', () => {
    expect(gen.name).toBe('EnumGenerator');
    expect(gen.artifactType).toBe('ENUM');
    expect(gen.priority).toBe(1);
    expect(gen.supportedBackends).toEqual([]);
  });

  it('generate() always returns null (enum generator only emits via generateAggregate)', () => {
    expect(gen.generate()).toBeNull();
  });
});

// ---------- generateAggregate — placeholder vs populated ----------

describe('EnumGenerator.generateAggregate — placeholder vs populated', () => {
  const gen = new EnumGenerator();

  it('with no enums in context → emits a single placeholder file at types/enums.ts', () => {
    const ctx = createGeneratorContext({}); // enums defaults to []
    const files = gen.generateAggregate([], ctx);

    expect(files).toHaveLength(1);
    expect(files[0].path).toBe('types/enums.ts');
    expect(files[0].artifactType).toBe('ENUM');
    expect(files[0].overwritable).toBe(true);
    expect(files[0].content).toContain('Placeholder');
    expect(files[0].content).toContain('export {}');
    // Placeholder still imports zod (consistent header shape).
    expect(files[0].content).toContain("import { z } from 'zod'");
  });

  it('with a single populated enum → emits enum block + DisplayNames + Schema + Options + utility', () => {
    const ctx = createGeneratorContext({}, [], [
      fullEnum({
        name: 'OrderStatus',
        values: [
          { name: 'PENDING', displayName: 'Pending', ordinal: 0 },
          { name: 'COMPLETED', displayName: 'Completed', ordinal: 1 },
        ],
      }),
    ]);

    const content = gen.generateAggregate([], ctx)[0].content;

    expect(content).toContain('export enum OrderStatus {');
    expect(content).toContain("PENDING = 'PENDING'");
    expect(content).toContain("COMPLETED = 'COMPLETED'");
    expect(content).toContain('export const OrderStatusDisplayNames: Record<OrderStatus, string>');
    expect(content).toContain('export const OrderStatusSchema = z.nativeEnum(OrderStatus)');
    expect(content).toContain('export const OrderStatusOptions');
    // Top-level utility appears once at the end.
    expect(content).toContain('export function enumOptions');
  });

  it('multi-enum metadata produces one block per enum, plus a single trailing enumOptions utility', () => {
    const ctx = createGeneratorContext({}, [], [
      fullEnum({ name: 'Role', values: [{ name: 'ADMIN', displayName: 'Admin', ordinal: 0 }] }),
      fullEnum({ name: 'Priority', values: [{ name: 'HIGH', displayName: 'High', ordinal: 0 }] }),
    ]);

    const content = gen.generateAggregate([], ctx)[0].content;

    expect(content).toContain('export enum Role {');
    expect(content).toContain('export enum Priority {');
    // The shared utility appears exactly once regardless of enum count.
    expect((content.match(/export function enumOptions/g) ?? []).length).toBe(1);
  });

  it('emits a /** @deprecated */ marker for deprecated enum values', () => {
    const ctx = createGeneratorContext({}, [], [
      fullEnum({
        name: 'LegacyStatus',
        values: [
          { name: 'OLD', displayName: 'Old', ordinal: 0, deprecated: true },
          { name: 'NEW', displayName: 'New', ordinal: 1 },
        ],
      }),
    ]);

    const content = gen.generateAggregate([], ctx)[0].content;

    // The @deprecated JSDoc comment appears immediately above OLD,
    // not above NEW.
    const deprecatedIdx = content.indexOf('/** @deprecated */');
    const oldIdx = content.indexOf("OLD = 'OLD'");
    const newIdx = content.indexOf("NEW = 'NEW'");
    expect(deprecatedIdx).toBeGreaterThan(-1);
    expect(deprecatedIdx).toBeLessThan(oldIdx);
    expect(deprecatedIdx).toBeGreaterThan(content.indexOf('export enum LegacyStatus'));
    // Only one deprecation marker, not one per value.
    expect((content.match(/@deprecated/g) ?? []).length).toBe(1);
    // NEW is below the deprecation comment but unaffected.
    expect(newIdx).toBeGreaterThan(oldIdx);
  });

  it('enum block JSDoc reflects the optional description when supplied', () => {
    const ctx = createGeneratorContext({}, [], [
      fullEnum({
        name: 'Currency',
        description: 'ISO 4217 currency codes',
        values: [{ name: 'USD', displayName: 'US Dollar', ordinal: 0 }],
      }),
    ]);

    const content = gen.generateAggregate([], ctx)[0].content;
    expect(content).toContain('/** ISO 4217 currency codes */');
  });

  it('description omitted when not supplied (no empty JSDoc)', () => {
    const ctx = createGeneratorContext({}, [], [
      fullEnum({ name: 'Bare', values: [{ name: 'X', displayName: 'X', ordinal: 0 }] }),
    ]);

    const content = gen.generateAggregate([], ctx)[0].content;
    // No JSDoc above "export enum Bare {"
    const enumLineIdx = content.indexOf('export enum Bare');
    const sliceBefore = content.slice(0, enumLineIdx);
    expect(sliceBefore).not.toMatch(/\/\*\*[\s\S]*?\*\/\s*$/);
  });

  it('values array literally undefined → destructuring default `values = []` keeps the loop safe', () => {
    // The fullEnum() helper injects `values: []` by default, so to
    // actually exercise the production destructuring default we have
    // to construct an enum metadata object with `values` explicitly
    // unset. The `as unknown as FullEnumMetadata` cast lets us pass
    // a structurally-malformed fixture to verify the runtime
    // tolerates it.
    const ctx = createGeneratorContext({}, [], [
      { name: 'Empty', qualifiedName: 'app.Empty', packageName: 'app' } as unknown as FullEnumMetadata,
    ]);

    const content = gen.generateAggregate([], ctx)[0].content;
    expect(content).toContain('export enum Empty {');
    // Empty body — open brace immediately followed by close brace
    // (possibly with whitespace).
    expect(content).toMatch(/export enum Empty \{\s*\}/);
  });

  it('falls back to humanized name when displayName is missing on a value', () => {
    // humanize splits on underscore + before each uppercase, then
    // title-cases each piece. For a kebab-stripped lowercase value
    // like "pending" the result is "Pending"; for camelCase
    // "waitingForApproval" the result is "Waiting For Approval".
    // (humanize's behavior on SCREAMING_SNAKE_CASE is degraded —
    // each letter ends up space-separated — but that's a separate
    // concern; this test covers the displayName-empty fallback
    // branch firing at all.)
    const ctx = createGeneratorContext({}, [], [
      fullEnum({
        name: 'Status',
        values: [
          { name: 'waitingForApproval', displayName: '', ordinal: 0 },
        ],
      }),
    ]);

    const content = gen.generateAggregate([], ctx)[0].content;
    expect(content).toContain('Waiting For Approval');
  });

  it('respects an explicitly-supplied displayName (no humanize fallback)', () => {
    const ctx = createGeneratorContext({}, [], [
      fullEnum({
        name: 'Status',
        values: [
          { name: 'WAITING_FOR_APPROVAL', displayName: 'Awaiting Sign-Off', ordinal: 0 },
        ],
      }),
    ]);

    const content = gen.generateAggregate([], ctx)[0].content;
    expect(content).toContain('Awaiting Sign-Off');
    expect(content).not.toContain('Waiting For Approval');
  });

  it('emits a $localize i18n marker including the enum name + value name in the message id', () => {
    const ctx = createGeneratorContext({}, [], [
      fullEnum({
        name: 'OrderStatus',
        values: [{ name: 'PENDING', displayName: 'Pending', ordinal: 0 }],
      }),
    ]);

    const content = gen.generateAggregate([], ctx)[0].content;
    expect(content).toContain('$localize`:@@enum.OrderStatus.PENDING:Pending`');
  });
});

// ---------- generateEnums convenience function ----------

describe('generateEnums — top-level convenience function', () => {
  it('routes through EnumGenerator + returns the first emitted file', () => {
    const file = generateEnums(
      [fullEnum({
        name: 'Priority',
        values: [{ name: 'LOW', displayName: 'Low', ordinal: 0 }],
      })],
      CTX.config,
    );

    expect(file.path).toBe('types/enums.ts');
    expect(file.artifactType).toBe('ENUM');
    expect(file.content).toContain('export enum Priority');
  });

  it('with an empty enums list emits the placeholder file (same as generateAggregate)', () => {
    const file = generateEnums([], CTX.config);
    expect(file.content).toContain('Placeholder');
  });

  it('falls back to KERNEL backend when config.backend is undefined (placeholder path still produces a valid file)', () => {
    // config is a Partial — explicitly unset backend → undefined →
    // the `?? "KERNEL"` fallback fires inside generateEnums. The
    // function has no observable side-effect that exposes the
    // resolved backend, so we assert on file emission (which
    // would crash if the inner context construction tripped on the
    // undefined backend) AND on the canonical placeholder content
    // shape (proof the call reached the generator).
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    const file = generateEnums([], partialConfig);
    expect(file.path).toBe('types/enums.ts');
    expect(file.content).toContain('Placeholder');
  });
});
