/**
 * HTTP Error Message Helper Generator
 *
 * Emits the one app-wide function every emitted component, store and saga uses to turn a failed
 * request into a user-facing message. The emitted handler answers each failure with a status (400
 * malformed or rejected input, 404 absent row, 409 stale version or, on a delete, a row other rows
 * still reference, 422 a relationship naming a row that does not exist, 500 anything the server
 * could not complete; no 401/403, because no route binds a policy). Only the 422 carries a body,
 * a problem object naming the relationship, and the message does not depend on it: the status and
 * the action are all the message is built from. Status 0 is the browser's report that no response
 * arrived.
 *
 * The emitted text is a constant: no metadata reaches it, so it is byte-identical across runs
 * and across apps.
 */

import type { GeneratorConfig } from '../../config.js';
import type { DomainMetadata } from '../../models/domain-model.js';
import { outPath } from '../../core/paths.js';
import { fileHeaderLines } from '../file-header.js';

/** Where the helper lands, relative to the Angular sourceRoot `src/app/`. */
export const HTTP_ERROR_PATH = outPath('core', 'http-error.ts');

/**
 * The helper is consumed only by the per-entity detail, list, form, store and saga emitters,
 * so an app with no entity, or with all of those emitters off, gets none.
 */
export function needsHttpErrorHelper(domains: DomainMetadata[], config: GeneratorConfig): boolean {
  const consumerOn = config.generateDetails || config.generateLists || config.generateForms
    || config.generateStores || config.generateSagas;
  return consumerOn && domains.length > 0;
}

export function generateHttpErrorHelper(): { path: string; content: string } {
  const lines: string[] = [];
  lines.push(
    ...fileHeaderLines({
      title: 'HTTP error messages',
      notes: [
        '',
        'A message is built from the status of the failed request and from what the request was',
        'doing; no response body is read.',
      ],
    }),
  );
  lines.push(``);
  lines.push(`import { HttpErrorResponse } from '@angular/common/http';`);
  lines.push(``);
  lines.push(`/** What the failed request was doing; it only chooses the wording. */`);
  lines.push(`export type HttpErrorAction = 'load' | 'save' | 'delete';`);
  lines.push(``);
  lines.push(`export interface HttpErrorContext {`);
  lines.push(`  /** The noun as it reads mid-sentence, e.g. 'order' or 'orders'. */`);
  lines.push(`  entity?: string;`);
  lines.push(`  action?: HttpErrorAction;`);
  lines.push(`}`);
  lines.push(``);
  lines.push(`/**`);
  lines.push(` * A user-facing message for a failed request. Pure: the same status and context always give`);
  lines.push(` * the same text.`);
  lines.push(` */`);
  lines.push(`export function httpErrorMessage(err: unknown, context: HttpErrorContext = {}): string {`);
  lines.push(`  const subject = context.entity ? \`the \${context.entity}\` : 'the record';`);
  lines.push(`  const Subject = subject.charAt(0).toUpperCase() + subject.slice(1);`);
  lines.push(`  const task = context.action ? \`\${context.action} \${subject}\` : 'complete the request';`);
  lines.push(`  const response = toHttpErrorResponse(err);`);
  lines.push(`  if (!response) {`);
  lines.push(`    return \`Could not \${task}.\`;`);
  lines.push(`  }`);
  lines.push(`  const status = response.status;`);
  lines.push(`  if (status === 0) {`);
  lines.push(`    return 'The server could not be reached. Check your connection and try again.';`);
  lines.push(`  }`);
  lines.push(`  if (status === 400) {`);
  lines.push(`    return \`The server rejected the request to \${task} as invalid.\`;`);
  lines.push(`  }`);
  lines.push(`  if (status === 404) {`);
  lines.push(`    return \`\${Subject} was not found. It may have been deleted.\`;`);
  lines.push(`  }`);
  lines.push(`  if (status === 409 && context.action === 'delete') {`);
  lines.push(`    return \`\${Subject} is still referenced by other records. Remove or change them first.\`;`);
  lines.push(`  }`);
  lines.push(`  if (status === 409) {`);
  lines.push(`    return \`\${Subject} was changed by someone else. Reload to see the latest version.\`;`);
  lines.push(`  }`);
  lines.push(`  if (status === 422) {`);
  lines.push(`    return \`\${Subject} refers to a related record that does not exist.\`;`);
  lines.push(`  }`);
  lines.push(`  if (status >= 500) {`);
  lines.push(`    return \`The server could not \${task}. Try again later.\`;`);
  lines.push(`  }`);
  lines.push(`  return \`Could not \${task} (status \${status}).\`;`);
  lines.push(`}`);
  lines.push(``);
  lines.push(`/** A resource may hand over the response itself or an Error whose cause holds it. */`);
  lines.push(`function toHttpErrorResponse(err: unknown): HttpErrorResponse | null {`);
  lines.push(`  if (err instanceof HttpErrorResponse) return err;`);
  lines.push(`  if (err instanceof Error && err.cause instanceof HttpErrorResponse) return err.cause;`);
  lines.push(`  return null;`);
  lines.push(`}`);
  lines.push(``);
  return { path: HTTP_ERROR_PATH, content: lines.join('\n') };
}
