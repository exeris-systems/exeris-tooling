/**
 * The bearer interceptor (ADR-105): emitted, with the auth service it reads the token from, and
 * registered in `app.config.ts` exactly when some route demands identity.
 *
 * The route requirements are injected here, read from `contract/route-policy.example.json`: the
 * metadata declares no access, so the derived rows are empty and a real build emits nothing.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG, type GeneratorConfig } from '../../../src/config.js';
import { DomainMetadataSchema } from '../../../src/models/domain-model.js';
import { generateAuthInterceptor, AUTH_INTERCEPTOR_PATH } from '../../../src/generators/angular/auth-interceptor-gen.js';
import type { RouteRequirementRow } from '../../../src/core/route-requirements.js';

const EXAMPLE_URL = new URL(
  '../../../../exeris-e2e-tests/src/test/resources/contract/route-policy.example.json',
  import.meta.url,
);
const exampleRows = (JSON.parse(readFileSync(fileURLToPath(EXAMPLE_URL), 'utf8')) as { routes: RouteRequirementRow[] })
  .routes;

const domains = ['Order', 'Invoice', 'Catalog'].map((entityName) =>
  DomainMetadataSchema.parse({ packageName: 'com.shop', entityName, path: `/${entityName.toLowerCase()}s` }),
);

const INTERCEPTOR = `src/app/${AUTH_INTERCEPTOR_PATH}`;
const AUTH_SERVICE = 'src/app/core/auth.service.ts';
const APP_CONFIG = 'src/app/app.config.ts';

function content(files: { path: string; content: string }[], path: string): string {
  const file = files.find((f) => f.path === path);
  expect(file, `${path} is emitted`).toBeDefined();
  return file!.content;
}

describe('generateAuthInterceptor', () => {
  it('is a functional interceptor that sends AuthService.token() as a bearer header', () => {
    const { path, content: ts } = generateAuthInterceptor(DEFAULT_CONFIG);
    expect(path).toBe('core/auth.interceptor.ts');
    expect(ts).toContain("import { HttpInterceptorFn } from '@angular/common/http';");
    expect(ts).toContain("import { AuthService } from './auth.service';");
    expect(ts).toContain('export const authInterceptor: HttpInterceptorFn = (req, next) => {');
    expect(ts).toContain('const token = inject(AuthService).token();');
    expect(ts).toContain('if (token === null) return next(req);');
    expect(ts).toContain('return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));');
    // The token source is the auth service, never a storage read of its own.
    expect(ts).not.toMatch(/localStorage|sessionStorage|document\.cookie/);
  });

  it('sends the token only under the prefix the generated services call', () => {
    expect(generateAuthInterceptor(DEFAULT_CONFIG).content).toContain("const API_PREFIX = '/';");
    expect(generateAuthInterceptor({ ...DEFAULT_CONFIG, apiBasePath: '/api' }).content).toContain("const API_PREFIX = '/api/';");
    expect(generateAuthInterceptor({ ...DEFAULT_CONFIG, apiBasePath: '/api/' }).content).toContain("const API_PREFIX = '/api/';");
    expect(generateAuthInterceptor(DEFAULT_CONFIG).content).toContain(
      "if (!req.url.startsWith(API_PREFIX) || req.url.startsWith('//')) return next(req);",
    );
  });

  it('escapes the prefix into its literal', () => {
    expect(generateAuthInterceptor({ ...DEFAULT_CONFIG, apiBasePath: "/a'b" }).content).toContain(
      "const API_PREFIX = '/a\\'b/';",
    );
  });

  it('is byte-identical across runs', () => {
    expect(generateAuthInterceptor(DEFAULT_CONFIG).content).toBe(generateAuthInterceptor(DEFAULT_CONFIG).content);
  });
});

describe('buildGeneratedFiles — the bearer interceptor follows the route requirements', () => {
  it('emits and registers the interceptor when some route demands identity', () => {
    const files = buildGeneratedFiles(domains, [], DEFAULT_CONFIG, [], [], exampleRows);
    expect(content(files, INTERCEPTOR)).toContain('export const authInterceptor: HttpInterceptorFn');
    expect(content(files, AUTH_SERVICE)).toContain('readonly token = this._token.asReadonly();');
    expect(files.find((f) => f.path === AUTH_SERVICE)?.overwritable).toBe(false);

    const config = content(files, APP_CONFIG);
    expect(config).toContain("import { provideHttpClient, withInterceptors } from '@angular/common/http';");
    expect(config).toContain("import { authInterceptor } from './core/auth.interceptor';");
    expect(config).toContain('provideHttpClient(withInterceptors([authInterceptor])),');
    expect(config).not.toContain('provideHttpClient(),');
  });

  it('emits neither file, and app.config.ts registers no interceptor, when no route demands identity', () => {
    const derived = buildGeneratedFiles(domains, [], DEFAULT_CONFIG);
    const open = exampleRows.filter((row) => row.requirement.kind === 'permitAll');
    for (const rows of [[], open]) {
      const files = buildGeneratedFiles(domains, [], DEFAULT_CONFIG, [], [], rows);
      expect(files).toEqual(derived);
    }
    expect(derived.map((f) => f.path)).not.toContain(INTERCEPTOR);
    expect(derived.map((f) => f.path)).not.toContain(AUTH_SERVICE);
    const config = content(derived, APP_CONFIG);
    expect(config).toContain("import { provideHttpClient } from '@angular/common/http';");
    expect(config).toContain('provideHttpClient(),');
    expect(config).not.toContain('withInterceptors');
    expect(config).not.toContain('authInterceptor');
  });

  it('with the scaffold off, emits the interceptor at the tree root for the consumer to register', () => {
    const noScaffold: GeneratorConfig = { ...DEFAULT_CONFIG, scaffold: false };
    const files = buildGeneratedFiles(domains, [], noScaffold, [], [], exampleRows);
    expect(content(files, AUTH_INTERCEPTOR_PATH)).toContain('export const authInterceptor');
    expect(files.map((f) => f.path)).toContain('core/auth.service.ts');
    expect(files.map((f) => f.path)).not.toContain(APP_CONFIG);
  });

  it('keeps the SSG hydration provider beside the registered interceptor', () => {
    const ssg: GeneratorConfig = { ...DEFAULT_CONFIG, render: 'ssg' };
    const config = content(buildGeneratedFiles(domains, [], ssg, [], [], exampleRows), APP_CONFIG);
    expect(config).toContain('provideHttpClient(withInterceptors([authInterceptor])),');
    expect(config).toContain('provideClientHydration(withEventReplay()),');
  });
});
