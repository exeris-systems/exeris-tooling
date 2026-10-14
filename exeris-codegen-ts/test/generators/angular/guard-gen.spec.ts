/**
 * Coverage for src/generators/angular/guard-gen.ts — GuardGenerator emits no route guard and no
 * permission constant: it writes only the auth service seed. A guard checks names the author
 * declared, so none exists until a declared name reaches the generator.
 */

import { describe, expect, it } from 'vitest';
import { GuardGenerator, AUTH_SERVICE_PATH } from '../../../src/generators/angular/guard-gen.js';
import {
  createGeneratorContext,
  type GeneratorContext,
} from '../../../src/core/generator-registry.js';
import {
  DomainMetadataSchema,
  type DomainMetadata,
} from '../../../src/models/domain-model.js';

const CTX: GeneratorContext = createGeneratorContext({});

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

describe('GuardGenerator — CodeGenerator metadata', () => {
  const gen = new GuardGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('GuardGenerator');
    expect(gen.artifactType).toBe('GUARD');
    expect(gen.priority).toBe(15);
    expect(gen.supportedBackends).toEqual([]);
  });
});

describe('GuardGenerator.generate — per-domain', () => {
  const gen = new GuardGenerator();

  it('emits no file for a domain, read-write or read-only', () => {
    expect(gen.generate(domain({ entityName: 'Order' }), CTX)).toBeNull();
    expect(
      gen.generate(domain({ entityName: 'AuditLog', internalApi: { readOnly: true, internal: false } }), CTX),
    ).toBeNull();
  });
});

describe('GuardGenerator.generateAggregate — auth service seed', () => {
  const gen = new GuardGenerator();
  const files = gen.generateAggregate([domain({ entityName: 'Order' }), domain({ entityName: 'OrderLine' })], CTX);

  it('emits only core/auth.service.ts, a seed the consumer owns', () => {
    expect(files.map(f => f.path)).toEqual([AUTH_SERVICE_PATH]);
    expect(files[0]!.overwritable).toBe(false);
    expect(files[0]!.artifactType).toBe('GUARD');
  });

  it('has the documented public surface', () => {
    const content = files[0]!.content;
    expect(content).toContain('export class AuthService');
    expect(content).toContain('hasPermission(permission: string): boolean');
    expect(content).toContain('hasAnyPermission(permissions: string[]): boolean');
    expect(content).toContain('setUser(user: User, token: string): void');
    expect(content).toContain('clearUser(): void');
    expect(content).toContain('getToken(): string | null');
    expect(content).toContain('isAuthenticated = computed');
    expect(content).toContain('will NOT be overwritten');
  });

  it('emits no guards barrel for an empty domain list', () => {
    expect(gen.generateAggregate([], CTX).map(f => f.path)).toEqual([AUTH_SERVICE_PATH]);
  });
});

describe('GuardGenerator — no invented permission, no role bypass', () => {
  const gen = new GuardGenerator();
  const all = [
    ...gen.generateAggregate([domain({ entityName: 'Order' })], CTX),
    gen.generate(domain({ entityName: 'Order' }), CTX),
  ].filter((f): f is NonNullable<typeof f> => f !== null);

  it.each([/_PERMISSIONS/, /hasRole/, /'ADMIN'/, /canActivate/, /:read'|:create'|:update'|:delete'/])(
    'no emitted file matches %s',
    (pattern) => {
      for (const f of all) expect(f.content).not.toMatch(pattern);
    },
  );
});
