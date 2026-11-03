/**
 * Coverage for src/generators/angular/guard-gen.ts — GuardGenerator writes the auth service seed,
 * and `generateRouteGuards` the two functional guards `app.routes.ts` attaches. No guard carries a
 * permission name of its own: the names arrive as the arguments the routes pass, copied from the
 * declared route access.
 */

import { describe, expect, it } from 'vitest';
import ts from 'typescript';
import {
  GuardGenerator,
  AUTH_SERVICE_PATH,
  ROUTE_GUARDS_PATH,
  generateRouteGuards,
} from '../../../src/generators/angular/guard-gen.js';
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

describe('generateRouteGuards', () => {
  const file = generateRouteGuards();

  it('is the generated core/route-guards.ts, overwritten on every run', () => {
    expect(file.path).toBe(ROUTE_GUARDS_PATH);
    expect(file.path).toBe('core/route-guards.ts');
    expect(file.overwritable).toBe(true);
    expect(file.artifactType).toBe('GUARD');
  });

  it('declares functional guards over the auth service', () => {
    expect(file.content).toContain("import { CanActivateFn } from '@angular/router';");
    expect(file.content).toContain("import { AuthService } from './auth.service';");
    expect(file.content).toContain('export const requireAuthenticated: CanActivateFn = () => inject(AuthService).isAuthenticated();');
    expect(file.content).toContain('export function requireAnyPermission(...permissions: string[]): CanActivateFn {');
    expect(file.content).toContain('return () => inject(AuthService).hasAnyPermission(permissions);');
  });

  it('names no permission and no role of its own', () => {
    for (const pattern of [/'[a-z]+:[a-z]+'/, /hasRole/, /'ADMIN'/, /_PERMISSIONS/]) {
      expect(file.content).not.toMatch(pattern);
    }
  });

  it('is byte-identical across runs', () => {
    expect(generateRouteGuards()).toEqual(file);
  });

  describe('behaviour of the emitted guards', () => {
    interface Auth {
      isAuthenticated(): boolean;
      hasAnyPermission(permissions: string[]): boolean;
    }
    const load = (auth: Auth) => {
      const js = ts.transpileModule(file.content.replace(/^import .*;$/gm, ''), {
        compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
      }).outputText;
      const exports: Record<string, unknown> = {};
      new Function('exports', 'inject', 'AuthService', js)(exports, () => auth, class {});
      return exports as {
        requireAuthenticated: () => boolean;
        requireAnyPermission: (...p: string[]) => () => boolean;
      };
    };
    const user = (permissions: string[] | null): Auth => ({
      isAuthenticated: () => permissions !== null,
      hasAnyPermission: (wanted) => permissions !== null && wanted.some((p) => permissions.includes(p)),
    });

    it('requireAuthenticated admits a signed-in user and refuses with false otherwise', () => {
      expect(load(user([])).requireAuthenticated()).toBe(true);
      expect(load(user(null)).requireAuthenticated()).toBe(false);
    });

    it('requireAnyPermission admits a user holding any one of the names', () => {
      expect(load(user(['order:write'])).requireAnyPermission('order:read', 'order:write')()).toBe(true);
      expect(load(user(['order:approve'])).requireAnyPermission('order:read', 'order:write')()).toBe(false);
      expect(load(user(null)).requireAnyPermission('order:read')()).toBe(false);
    });
  });
});
