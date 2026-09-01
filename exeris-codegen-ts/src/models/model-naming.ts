/**
 * The name an emitted module uses for an entity's own type.
 *
 * <p>An emitted Angular module holds three kinds of identifier in one namespace: what it imports
 * from a framework package, what it declares itself, and the entity's type. Nothing stopped the
 * third from being spelled like the first two, so an entity named `Component` produced a form
 * component that imported `Component` twice — once from `@angular/core`, once from its own
 * service — and did not compile.
 *
 * The reserved set below is not a list of framework exports to track against Angular releases. It
 * is the inventory of identifiers **these emitters put into an emitted module**, which is a set
 * this repository controls: framework symbols they import, and helper types they declare
 * (`Page`, `PageRequest`). `model-naming.spec.ts` derives the same set from freshly generated
 * output and fails when this constant no longer covers it, so a new import or helper type is
 * caught here rather than in a consumer's build.
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
  // `Router` joined when detail-gen was wired (it navigates after delete); nothing emitted
  // before that imported it. model-naming.spec derives this set from freshly generated output
  // and failed on exactly this identifier, which is the mechanism working rather than a surprise.
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
 * <p>Returns the entity name unchanged in every case but the collision, so emitted output for an
 * ordinary entity is byte-identical to what it was before this existed. Only the bare type is
 * renamed: `<Entity>Create`, `<Entity>Service` and the component classes are already distinct from
 * anything an emitted module imports, and renaming them would churn identifiers to no purpose.
 *
 * <p>File names, selectors and route paths keep the original entity name — they are addresses
 * rather than identifiers, and a collision in the TypeScript namespace says nothing about them.
 */
export function modelTypeName(entityName: string): string {
  return RESERVED_MODULE_IDENTIFIERS.has(entityName) ? `${entityName}${MODEL_SUFFIX}` : entityName;
}
