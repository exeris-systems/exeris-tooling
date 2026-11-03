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

type AccessDeclaring = { readonly routeAccess?: 'PUBLIC' | 'AUTHENTICATED'; readonly permissions?: readonly string[] };

/**
 * One element's requirement from its own declarations: `PUBLIC` admits anyone, a non-empty
 * `permissions` is any-of over them (repeats dropped, declaration order kept), `AUTHENTICATED`
 * without permissions needs an identity, and nothing yields no requirement.
 */
function ownRequirement(element: AccessDeclaring): RouteRequirement | undefined {
  if (element.routeAccess === 'PUBLIC') return { kind: 'permitAll' };
  const scopes = [...new Set(element.permissions ?? [])];
  if (scopes.length > 0) return { kind: 'anyScope', scopes };
  if (element.routeAccess === 'AUTHENTICATED') return { kind: 'authenticated' };
  return undefined;
}

/**
 * The rows of the application generated from `domains`.
 *
 * Every route of an entity carries the entity's requirement, except an action route whose action
 * declares its own: the nearest declaration wins and an action's list replaces the entity's
 * (ADR-105 obligations 10 to 12). A route with no requirement has no row. Rows follow the router's
 * registration order: each entity's respond-once routes (`GET {base}`, `GET {base}/{id}`,
 * `POST {base}`, `PUT {base}/{id}`, `DELETE {base}/{id}`, then each respond-once action), entity
 * by entity, then the stream routes (each streaming action, then the live view and the spectate
 * route of a real-time entity), entity by entity.
 */
export function deriveRouteRequirements(domains: readonly DomainMetadata[]): RouteRequirementRow[] {
  const rows: RouteRequirementRow[] = [];
  const push = (
    entity: string,
    method: RouteRequirementRow['method'],
    path: string,
    requirement: RouteRequirement | undefined,
    source: RouteRequirementRow['source'],
    action?: string,
  ): void => {
    if (!requirement) return;
    rows.push(action === undefined
      ? { entity, method, path, requirement, source }
      : { entity, method, path, action, requirement, source });
  };
  const actionRow = (domain: DomainMetadata, action: DomainMetadata['actions'][number]): void => {
    const own = ownRequirement(action);
    push(domain.entityName, 'POST', ACTION_PATH, own ?? ownRequirement(domain), own ? 'action' : 'entity', action.name);
  };

  for (const domain of domains) {
    const entity = ownRequirement(domain);
    for (const [method, path] of CRUD_ROUTES) push(domain.entityName, method, path, entity, 'entity');
    for (const action of domain.actions) if (!action.streaming) actionRow(domain, action);
  }
  for (const domain of domains) {
    const entity = ownRequirement(domain);
    for (const action of domain.actions) if (action.streaming) actionRow(domain, action);
    if (domain.realTimeApi) {
      push(domain.entityName, 'GET', '{base}/stream', entity, 'entity');
      push(domain.entityName, 'GET', '{base}/{id}/stream', entity, 'entity');
    }
  }
  return rows;
}

const ACTION_PATH = '{base}/{id}/actions/{action}';

const CRUD_ROUTES: readonly (readonly [RouteRequirementRow['method'], string])[] = [
  ['GET', '{base}'],
  ['GET', '{base}/{id}'],
  ['POST', '{base}'],
  ['PUT', '{base}/{id}'],
  ['DELETE', '{base}/{id}'],
];
