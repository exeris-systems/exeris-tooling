/**
 * Bearer Interceptor Generator
 *
 * Emits the functional `HttpInterceptorFn` that sends the signed-in user's token to the kernel
 * application (ADR-105). The kernel reads identity from an `Authorization: Bearer` header only, so
 * a route that demands identity is reachable from the generated frontend only through this header.
 *
 * The token is `AuthService.token()`, the signal of the auth service seed the consumer completes
 * with its own sign-in; the interceptor never reads storage itself. The orchestrator emits it only
 * when `demandsIdentity` holds for the application's route requirements; otherwise neither file is
 * written and `app.config.ts` registers no interceptor.
 */

import type { GeneratorConfig } from '../../config.js';
import { outPath } from '../../core/paths.js';
import { fileHeaderLines } from '../file-header.js';
import { tsSingleQuoted } from './ts-literal.js';

/** Where the interceptor lands, relative to the Angular sourceRoot `src/app/`. */
export const AUTH_INTERCEPTOR_PATH = outPath('core', 'auth.interceptor.ts');

/** The name `app.config.ts` registers through `withInterceptors`. */
export const AUTH_INTERCEPTOR_NAME = 'authInterceptor';

export function generateAuthInterceptor(config: GeneratorConfig): { path: string; content: string } {
  // Every emitted service calls `${apiBasePath}${path}`, and an entity path starts with '/'.
  const base = config.apiBasePath;
  const prefix = tsSingleQuoted(base.endsWith('/') ? base : `${base}/`);
  const lines: string[] = [];
  lines.push(
    ...fileHeaderLines({
      title: 'Bearer token interceptor',
      notes: [
        '',
        'Sends the signed-in user\'s token to the kernel application, which reads identity from',
        'an Authorization: Bearer header only. Registered in app.config.ts.',
      ],
    }),
  );
  lines.push(``);
  lines.push(`import { HttpInterceptorFn } from '@angular/common/http';`);
  lines.push(`import { inject } from '@angular/core';`);
  lines.push(`import { AuthService } from './auth.service';`);
  lines.push(``);
  lines.push(`/** The prefix of every route the generated services call. */`);
  lines.push(`const API_PREFIX = '${prefix}';`);
  lines.push(``);
  lines.push(`/**`);
  lines.push(` * A request to the generated API carries the token while one is held. A request anywhere else,`);
  lines.push(` * including a protocol-relative URL to another host, never does.`);
  lines.push(` */`);
  lines.push(`export const ${AUTH_INTERCEPTOR_NAME}: HttpInterceptorFn = (req, next) => {`);
  lines.push(`  if (!req.url.startsWith(API_PREFIX) || req.url.startsWith('//')) return next(req);`);
  lines.push(`  const token = inject(AuthService).token();`);
  lines.push(`  if (token === null) return next(req);`);
  lines.push(`  return next(req.clone({ setHeaders: { Authorization: \`Bearer \${token}\` } }));`);
  lines.push(`};`);
  lines.push(``);
  return { path: AUTH_INTERCEPTOR_PATH, content: lines.join('\n') };
}
