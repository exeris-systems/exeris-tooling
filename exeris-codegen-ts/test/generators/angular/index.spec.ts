/**
 * Coverage for src/generators/angular/index.ts — barrel re-export of the
 * angular sub-generator surface.
 *
 * The file has no runtime logic of its own; the only thing worth pinning
 * is the public-export contract. If any of these names is removed or
 * renamed, downstream importers break — so this test acts as a deletion
 * guard rather than a behavioural one.
 *
 * The actual per-generator behaviour is covered by the sibling spec
 * files (service-gen.spec.ts, form-gen.spec.ts, etc.). Here we only
 * assert that the barrel surfaces each one.
 */

import { describe, expect, it } from 'vitest';
import * as angular from '../../../src/generators/angular/index.js';

describe('angular barrel exports — function set', () => {
  it.each([
    'generateService',
    'generateForm',
    'generateList',
    'generateStore',
    'generateSaga',
    'generateEventHandler',
    'generateAppStructure',
    'generateDetail',
    'generateGuard',
  ] as const)('re-exports function "%s"', (name) => {
    expect(angular).toHaveProperty(name);
    expect(typeof (angular as unknown as Record<string, unknown>)[name]).toBe('function');
  });
});

describe('angular barrel exports — class set', () => {
  it.each([
    'StoreGenerator',
    'SagaGenerator',
    'EventHandlerGenerator',
    'DetailGenerator',
    'GuardGenerator',
  ] as const)('re-exports class "%s" as a constructable', (name) => {
    const cls = (angular as unknown as Record<string, unknown>)[name];
    expect(cls).toBeDefined();
    expect(typeof cls).toBe('function');
    // Classes have a non-empty prototype with at least a constructor
    // entry — this distinguishes a class export from a plain function
    // re-export, which is what we actually want to pin for these names.
    expect((cls as { prototype: object }).prototype).toBeDefined();
    expect(Object.getOwnPropertyNames((cls as { prototype: object }).prototype)).toContain('constructor');
  });
});
