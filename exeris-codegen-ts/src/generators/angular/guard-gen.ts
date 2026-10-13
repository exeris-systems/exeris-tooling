/**
 * Angular Route Guard Generator
 *
 * Emits no route guard. A guard may check only names the author declared, and no declared
 * permission reaches this generator, so a guard would authorize against names it invented.
 * The generator emits the auth service seed the guards will read.
 */

import type { DomainMetadata, CodeGenerator, GeneratedFile, GeneratorContext } from '../../core/generator-registry.js';
import type { BackendType } from '../../core/backend-strategy.js';
import { fileHeader } from '../file-header.js';

/** The auth service template, relative to the generated tree's root: a seed file
 *  (`overwritable: false`) the consumer completes with its own authentication. */
export const AUTH_SERVICE_PATH = 'core/auth.service.ts';

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
