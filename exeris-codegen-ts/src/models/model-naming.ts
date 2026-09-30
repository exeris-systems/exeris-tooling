/**
 * The name an emitted module uses for an entity's own type.
 *
 * An emitted Angular module holds three kinds of identifier in one namespace: imports from
 * framework packages, declarations made by the emitters, and the entity's own type. This module
 * prevents collisions: when an entity's name matches a framework import (e.g., `Component`),
 * the entity type is renamed (e.g., `ComponentModel`).
 *
 * The reserved set is the inventory of identifiers **these emitters put into emitted modules**:
 * framework symbols imported, and helper types declared (`Page`, `PageRequest`).
 * `model-naming.spec.ts` validates the set by deriving it from freshly generated output.
 *
 * @author Exeris Team
 * @since 0.8.0
 */
export const RESERVED_MODULE_IDENTIFIERS: ReadonlySet<string> = new Set([
  // @angular/core
  'ApplicationConfig',
  'ChangeDetectionStrategy',
  'Component',
  'DestroyRef',
  'Injectable',
  'OnInit',
  // @angular/common and @angular/common/http
  'CommonModule',
  'DatePipe',
  'HttpClient',
  'HttpParams',
  // @angular/forms
  'FormBuilder',
  'FormsModule',
  'ReactiveFormsModule',
  'Validators',
  // @angular/router
  // Identifiers that the emitters import.
  'ActivatedRoute',
  'Router',
  'RouterLink',
  'RouterLinkActive',
  'RouterModule',
  'RouterOutlet',
  'Routes',
  // rxjs
  'Observable',
  'Subject',
  // declared by the emitted service module itself
  'Page',
  'PageRequest',
]);

/** The suffix an entity type takes when its own name is already spoken for. */
const MODEL_SUFFIX = 'Model';

/**
 * The identifier an emitted module uses for the entity's type.
 *
 * When there is no collision, the entity name is returned unchanged. Only the bare type is
 * renamed: `<Entity>Create`, `<Entity>Service` and component classes are already distinct
 * from framework imports and do not require renaming.
 *
 * File names, selectors and route paths keep the original entity name — they are addresses
 * rather than identifiers, and a collision in the TypeScript namespace does not affect them.
 */
export function modelTypeName(entityName: string): string {
  return RESERVED_MODULE_IDENTIFIERS.has(entityName) ? `${entityName}${MODEL_SUFFIX}` : entityName;
}
