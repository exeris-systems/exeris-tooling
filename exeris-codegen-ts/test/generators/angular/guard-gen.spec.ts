/**
 * Coverage for src/generators/angular/guard-gen.ts — GuardGenerator emits
 * a per-domain functional-route-guard file (queries the AuthService for
 * permission/role) plus a cross-domain aggregate that emits the canonical
 * AuthService template (overwritable: false) and a guards/index.ts barrel.
 */

import { describe, expect, it } from 'vitest';
import { GuardGenerator, generateGuard } from '../../../src/generators/angular/guard-gen.js';
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

function hiddenDomain(entityName: string): DomainMetadata {
  return domain({
    entityName,
    internalApi: { hidden: true, readOnly: false, internal: false },
  });
}

function readOnlyDomain(entityName: string): DomainMetadata {
  return domain({
    entityName,
    internalApi: { hidden: false, readOnly: true, internal: false },
  });
}

// ---------- CodeGenerator contract ----------

describe('GuardGenerator — CodeGenerator metadata', () => {
  const gen = new GuardGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('GuardGenerator');
    expect(gen.artifactType).toBe('GUARD');
    expect(gen.priority).toBe(15);
    expect(gen.supportedBackends).toEqual([]);
  });
});

// ---------- generate — per-domain ----------

describe('GuardGenerator.generate — per-domain', () => {
  const gen = new GuardGenerator();

  it('emits guards/<kebab>.guard.ts for a visible domain', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine' }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('guards/order-line.guard.ts');
    expect(file!.artifactType).toBe('GUARD');
    expect(file!.overwritable).toBe(true);
  });

  it('returns null for an internalApi.hidden domain', () => {
    expect(gen.generate(hiddenDomain('Audit'), CTX)).toBeNull();
  });

  it('PERMISSIONS const is keyed by uppercase entityName with kebab→snake permission strings', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;

    // The const namespace uses entityName.toUpperCase() verbatim.
    expect(content).toContain('export const ORDERLINE_PERMISSIONS = {');
    // The permission STRINGS use kebab-then-snake (toKebabCase → replace '-' with '_').
    expect(content).toContain("READ: 'order_line:read'");
    expect(content).toContain("CREATE: 'order_line:create'");
    expect(content).toContain("UPDATE: 'order_line:update'");
    expect(content).toContain("DELETE: 'order_line:delete'");
  });

  it('always emits the canView guard (READ permission + ADMIN role override)', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('export const canViewOrder: CanActivateFn');
    expect(content).toContain('ORDER_PERMISSIONS.READ');
    expect(content).toContain("hasRole('ADMIN')");
    expect(content).toContain("router.createUrlTree(['/forbidden'])");
    expect(content).toContain("queryParams: { returnUrl: state.url }");
  });

  it('emits canCreate / canEdit / canDelete guards for a read-write (default) domain', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('export const canCreateOrder: CanActivateFn');
    expect(content).toContain('export const canEditOrder: CanActivateFn');
    expect(content).toContain('export const canDeleteOrder: CanActivateFn');
    expect(content).toContain('ORDER_PERMISSIONS.CREATE');
    expect(content).toContain('ORDER_PERMISSIONS.UPDATE');
    expect(content).toContain('ORDER_PERMISSIONS.DELETE');
  });

  it('OMITS canCreate / canEdit / canDelete for an internalApi.readOnly domain — only canView is emitted', () => {
    const content = gen.generate(readOnlyDomain('AuditLog'), CTX)!.content;

    // canView still fires for read-only too.
    expect(content).toContain('export const canViewAuditLog: CanActivateFn');
    // But the mutation guards are absent.
    expect(content).not.toContain('canCreateAuditLog');
    expect(content).not.toContain('canEditAuditLog');
    expect(content).not.toContain('canDeleteAuditLog');
    // Permission constants for CREATE/UPDATE/DELETE still exist (the
    // PERMISSIONS const declares the whole namespace, unconditional on
    // readOnly) — only the guard functions are gated by !isReadOnly.
    // The const keys are present even though no guard references them
    // for this read-only domain.
    // entityName 'AuditLog' → kebab 'audit-log' → snake 'audit_log'.
    expect(content).toContain("CREATE: 'audit_log:create'");
    expect(content).toContain("UPDATE: 'audit_log:update'");
    expect(content).toContain("DELETE: 'audit_log:delete'");
    expect(content).not.toContain('AUDITLOG_PERMISSIONS.CREATE');
    expect(content).not.toContain('AUDITLOG_PERMISSIONS.UPDATE');
    expect(content).not.toContain('AUDITLOG_PERMISSIONS.DELETE');
  });

  it('imports AuthService from ../core/auth.service (the file generateAggregate emits)', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    expect(content).toContain("import { AuthService } from '../core/auth.service';");
  });
});

// ---------- generateAggregate — AuthService + barrel ----------

describe('GuardGenerator.generateAggregate — AuthService + barrel', () => {
  const gen = new GuardGenerator();

  it('always emits both core/auth.service.ts AND guards/index.ts', () => {
    const files = gen.generateAggregate([domain({ entityName: 'Order' })], CTX);

    expect(files).toHaveLength(2);
    expect(files.map(f => f.path)).toEqual(['core/auth.service.ts', 'guards/index.ts']);
  });

  it('auth.service.ts is marked overwritable: false (user customisation expected)', () => {
    const files = gen.generateAggregate([domain({ entityName: 'Order' })], CTX);
    const authFile = files.find(f => f.path === 'core/auth.service.ts')!;

    expect(authFile.overwritable).toBe(false);
    expect(authFile.artifactType).toBe('GUARD');
  });

  it('barrel index.ts is overwritable (regenerated whenever the domain set changes)', () => {
    const files = gen.generateAggregate([domain({ entityName: 'Order' })], CTX);
    const barrel = files.find(f => f.path === 'guards/index.ts')!;

    expect(barrel.overwritable).toBe(true);
  });

  it('AuthService template has the documented public surface (signals, hasRole, hasPermission, setUser, clearUser, getToken)', () => {
    const files = gen.generateAggregate([domain({ entityName: 'Order' })], CTX);
    const content = files.find(f => f.path === 'core/auth.service.ts')!.content;

    expect(content).toContain('export class AuthService');
    expect(content).toContain('@Injectable({ providedIn: \'root\' })');
    expect(content).toContain('hasRole(role: string): boolean');
    expect(content).toContain('hasPermission(permission: string): boolean');
    expect(content).toContain('hasAnyPermission(permissions: string[]): boolean');
    expect(content).toContain('setUser(user: User, token: string): void');
    expect(content).toContain('clearUser(): void');
    expect(content).toContain('getToken(): string | null');
    expect(content).toContain('isAuthenticated = computed');
    // The "NOT be overwritten" notice is the marker that downstream
    // consumers know they can safely customise this file.
    expect(content).toContain('will NOT be overwritten');
  });

  it('barrel exports every visible-domain guard module (kebab path)', () => {
    const files = gen.generateAggregate([
      domain({ entityName: 'Order' }),
      domain({ entityName: 'OrderLine' }),
    ], CTX);

    const barrel = files.find(f => f.path === 'guards/index.ts')!.content;
    expect(barrel).toContain("export * from './order.guard';");
    expect(barrel).toContain("export * from './order-line.guard';");
  });

  it('barrel filters out hidden domains', () => {
    const files = gen.generateAggregate([
      domain({ entityName: 'Order' }),
      hiddenDomain('Audit'),
    ], CTX);

    const barrel = files.find(f => f.path === 'guards/index.ts')!.content;
    expect(barrel).toContain("./order.guard");
    expect(barrel).not.toContain("./audit.guard");
  });

  it('barrel emits header even when the visible-domain list is empty (zero exports)', () => {
    const files = gen.generateAggregate([], CTX);
    const barrel = files.find(f => f.path === 'guards/index.ts')!.content;

    expect(barrel).toContain('Route Guards - Barrel Export');
    expect(barrel).not.toMatch(/export \* from/);
  });
});

// ---------- generateGuard convenience ----------

describe('generateGuard — top-level convenience function', () => {
  it('routes through GuardGenerator and returns the per-domain file', () => {
    const file = generateGuard(domain({ entityName: 'Order' }), CTX.config);

    expect(file.path).toBe('guards/order.guard.ts');
    expect(file.content).toContain('export const canViewOrder');
  });

  it('falls back to KERNEL backend when config.backend is undefined (still emits per-domain file)', () => {
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    const file = generateGuard(domain({ entityName: 'Order' }), partialConfig);

    expect(file.path).toBe('guards/order.guard.ts');
    expect(file.content).toContain('export const canViewOrder');
  });
});
