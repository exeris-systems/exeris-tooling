/**
 * The access requirement of each generated route, as the frontend sees it (ADR-105).
 *
 * One row per (method, path template) of an entity that declares access, in the shape of
 * `exeris-e2e-tests/src/test/resources/contract/route-policy.json`, the file the Java build holds
 * the emitted `GeneratedRoutePolicy` to. The two builds read one table: a row here is a row the
 * kernel application's policy declares, and a route with no row is one the policy abstains on.
 */

import type { DomainMetadata } from '../models/domain-model.js';

/** `permitAll`, `authenticated` or `anyScope`: the kernel `RouteRequirement` kinds a row names. */
export type RouteRequirementKind = 'permitAll' | 'authenticated' | 'anyScope';

export interface RouteRequirement {
  readonly kind: RouteRequirementKind;
  /** Present for `anyScope` only, in declaration order; a caller holding any one is admitted. */
  readonly scopes?: readonly string[];
}

export interface RouteRequirementRow {
  /** The `entityName` of the DomainMetadata the row came from. */
  readonly entity: string;
  /** The route's method. HEAD is never a row: the policy answers HEAD from the GET rows. */
  readonly method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  /** `{base}`, `{base}/{id}`, `{base}/stream`, `{base}/{id}/stream` or `{base}/{id}/actions/{action}`. */
  readonly path: string;
  /** The action's name, present exactly when `path` ends in `/actions/{action}`. */
  readonly action?: string;
  readonly requirement: RouteRequirement;
  /** The declaration the requirement came from; the nearest one wins. */
  readonly source: 'entity' | 'action';
}

/**
 * Whether any route demands identity: a caller with no credential is refused on it. `permitAll`
 * admits everyone, so only `authenticated` and `anyScope` rows count.
 */
export function demandsIdentity(rows: readonly RouteRequirementRow[]): boolean {
  return rows.some((row) => row.requirement.kind === 'authenticated' || row.requirement.kind === 'anyScope');
}

/**
 * The rows of the application generated from `domains`.
 *
 * Rows come from declared route access only: an entity's or an action's `@RouteAccess` and
 * `permissions`. The processor writes none of them and the Java policy generator reads none,
 * so neither build declares a row and the result is empty for every input. Reading a declaration
 * here before the policy generator reads it would guard a route the kernel application serves to
 * anyone; the parity spec over `route-policy.json` holds the two to the same rows.
 */
export function deriveRouteRequirements(_domains: readonly DomainMetadata[]): RouteRequirementRow[] {
  return [];
}
