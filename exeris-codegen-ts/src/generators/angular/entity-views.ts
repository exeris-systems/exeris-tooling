/**
 * The entity-level `@UI` view switches, resolved once for every emitter that decides whether a
 * page, a route, a link or a control exists.
 *
 * The processor writes `uiMetadata` only when the entity carries a type-level `@UI`, and then
 * writes every switch: an annotation present with an attribute unset reads as on. An entity with
 * no `@UI` gets every view. So a switch is off only when it is explicitly `false`, and absent
 * metadata emits exactly the output of an entity that never mentioned `@UI`.
 *
 * The switches are presentation only. The kernel application serves every CRUD route whatever they
 * say: an entity without a detail page can still be fetched by id, and one without a create form
 * can still be created through its service.
 *
 * `exportable` is not resolved: nothing exports on either side. Nor is `searchable`: the list route
 * has no search parameter (ADR-096), so no search box is emitted.
 */

import type { DomainMetadata } from '../../models/domain-model.js';
import { DslMapper } from '../../models/dsl-mapper.js';

export interface EntityViews {
  /** The list page, its route and its sidebar link. */
  readonly list: boolean;
  /** The detail page, its route, and every link to an instance's page. */
  readonly detail: boolean;
  /** The create route and every button that opens it. */
  readonly create: boolean;
  /** The edit route and every button that opens it. */
  readonly edit: boolean;
  /** The list page's filter controls. */
  readonly filter: boolean;
}

/** Reads all five switches unconditionally, so every emitter sees the same answer. */
export function entityViews(domain: DomainMetadata): EntityViews {
  const ui = domain.uiMetadata;
  return {
    list: ui?.listView !== false,
    detail: ui?.detailView !== false,
    create: ui?.createForm !== false,
    edit: ui?.editForm !== false,
    filter: ui?.filterable !== false,
  };
}

/** The form component serves both the create and the edit route; it exists when either does. */
export function hasFormPage(views: EntityViews): boolean {
  return views.create || views.edit;
}

/**
 * Where a routed page goes when it leaves an entity without a page of its own to return to: the
 * entity's list, or the app root when the list is switched off. The root always resolves — it
 * redirects to the first routed page, or renders the shell alone.
 */
export function entityExitRoute(domain: DomainMetadata, views: EntityViews = entityViews(domain)): string {
  return views.list ? `/${DslMapper.routePlural(domain.entityName)}` : '/';
}
