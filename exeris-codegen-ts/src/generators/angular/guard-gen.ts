/**
 * Angular Route Guard Generator
 *
 * Emits the auth service seed and the functional route guards that read it (ADR-105). A guard
 * checks only what a route's requirement row declares: `requireAuthenticated` for an
 * `authenticated()` route and `requireAnyPermission(...)` over the declared names, any-of, for an
 * `anyScope` route. No permission name is invented here; the names reach a guard only as the
 * arguments `app.routes.ts` passes, copied from the rows.
 */

import type { DomainMetadata, CodeGenerator, GeneratedFile, GeneratorContext } from '../../core/generator-registry.js';
import type { BackendType } from '../../core/backend-strategy.js';
import { fileHeader } from '../file-header.js';

/** The auth service template, relative to the generated tree's root: a seed file
 *  (`overwritable: false`) the consumer completes with its own authentication. */
export const AUTH_SERVICE_PATH = 'core/auth.service.ts';

/** The route guards module, relative to the generated tree's root. Generated, so overwritable. */
export const ROUTE_GUARDS_PATH = 'core/route-guards.ts';

/** The guard of a route whose requirement is `authenticated()`. */
export const REQUIRE_AUTHENTICATED = 'requireAuthenticated';

/** The guard factory of a route whose requirement is `anyScope(...)`. */
export const REQUIRE_ANY_PERMISSION = 'requireAnyPermission';

/**
 * The route guards module. A refused navigation returns `false` and stays where it is: the
 * generated app has no sign-in route to redirect to, and the consumer's auth service seed owns
 * that flow.
 */
export function generateRouteGuards(): GeneratedFile {
  const content = `${fileHeader({
    title: 'Route guards',
    adr: 'ADR-105',
    notes: [
      '',
      'Each guard admits a navigation exactly where the kernel application admits the route the',
      'page calls first. A refusal returns false and leaves the current page in place.',
    ],
  })}

import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AuthService } from './auth.service';

/** Admits a signed-in user: the page's route requires an identity and no permission. */
export const ${REQUIRE_AUTHENTICATED}: CanActivateFn = () => inject(AuthService).isAuthenticated();

/** Admits a signed-in user holding any one of \`permissions\`, the names the route declares. */
export function ${REQUIRE_ANY_PERMISSION}(...permissions: string[]): CanActivateFn {
  return () => inject(AuthService).hasAnyPermission(permissions);
}
`;
  return { path: ROUTE_GUARDS_PATH, content, artifactType: 'GUARD', overwritable: true };
}

export class GuardGenerator implements CodeGenerator {
  readonly name = 'GuardGenerator';
  readonly artifactType = 'GUARD' as const;
  readonly supportedBackends: BackendType[] = [];
  readonly priority = 15;

  generate(_domain: DomainMetadata, _context: GeneratorContext): GeneratedFile | null {
    return null;
  }

  generateAggregate(_domains: DomainMetadata[], _context: GeneratorContext): GeneratedFile[] {
    return [
      {
        path: AUTH_SERVICE_PATH,
        content: this.generateAuthService(),
        artifactType: 'GUARD',
        overwritable: false,
      },
    ];
  }

  private generateAuthService(): string {
    return `${fileHeader({
      title: 'Authentication Service',
      notes: ['NOTE: This file will NOT be overwritten. Implement actual auth logic.'],
      doNotEdit: 'omit',
    })}

import { Injectable, signal, computed } from '@angular/core';

export interface User {
  id: string;
  username: string;
  email: string;
  permissions: string[];
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _user = signal<User | null>(null);
  private readonly _token = signal<string | null>(null);

  readonly user = this._user.asReadonly();
  readonly token = this._token.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null);
  readonly permissions = computed(() => this._user()?.permissions ?? []);

  hasPermission(permission: string): boolean {
    return this.permissions().includes(permission);
  }

  hasAnyPermission(permissions: string[]): boolean {
    return permissions.some(p => this.hasPermission(p));
  }

  setUser(user: User, token: string): void {
    this._user.set(user);
    this._token.set(token);
  }

  clearUser(): void {
    this._user.set(null);
    this._token.set(null);
  }

  getToken(): string | null {
    return this._token();
  }
}
`;
  }
}
