/**
 * Angular View (Presentation IR) Generator — RFC-2026-06-28 §3.
 *
 * Emits ONE standalone, signal-first Angular 22 component per `@View`
 * (`view_*.json` → ViewMetadata), under `pages/<kebab>.component.ts`, plus a
 * paired lazy route (`pages/<kebab>.route.ts`) so the page is routable. This is
 * the codegen-ts half of the build gate the SDK presentation IR opens: the SDK
 * names no Angular type (framework-neutral IR in, Angular out); the ui-kit stays
 * a consumer (its `exeris-*` design-token utilities skin the emitted markup, per
 * U1 / T25).
 *
 * Determinism (hard-constraint #3): the template is assembled in declaration
 * order (regions, then each region's component tree depth-first), with no Date /
 * random / hash-iteration leakage. Same ViewMetadata → byte-identical output.
 *
 * BlockType → element mapping (RFC §3):
 *   HERO       → <section class="exeris-hero …">
 *   CARD       → <article class="exeris-card …">
 *   GRID       → <div class="exeris-grid …">
 *   LIST       → <ul class="exeris-list …">
 *   CONTAINER  → <div class="exeris-container …">
 *   RICH_TEXT  → <div class="exeris-rich-text …">
 *   NAV        → <nav class="exeris-nav …">
 *   IMAGE      → <figure class="exeris-image …">
 *   SLOT       → <ng-content> (a named host slot)
 *   CUSTOM     → the named customType selector element
 *   FORM       → a placeholder block (leaf-field form emission is slice 2, RFC §5)
 *
 * Bindings HONOURED in slice 1 (RFC §3):
 *   STATIC / NONE → authored / literal structure (props text when present)
 *   ENTITY        → inject(<Ref>Service) + a signal read referencing the
 *                   generated service by `ref`
 *   ACTION        → a click handler stub calling the named action
 * Bindings OUT of slice 1 — emitted as clearly-commented TODO passthroughs (never
 * faked), each referencing the corpus gap it belongs to:
 *   PROJECTION beyond a named read     → TODO(@View G1)
 *   parameterised / relational via expression (G1) → TODO(@View G1)
 *   STREAM source (G2)                 → TODO(@View G2)
 *   mesh binding (G3)                  → TODO(@View G3)
 *   token / theme binding (G6)         → TODO(@View G6)
 *
 * @author Exeris Team
 * @since 0.8.0
 */

import type {
  ViewMetadata,
  RegionMetadata,
  ComponentNodeMetadata,
  BindingMetadata,
  BlockType,
} from '../../models/domain-model.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import type { GeneratorConfig } from '../../config.js';
import type { OutputFile } from '../../orchestrator.js';

/** The effective block type — the declared one, or the SDK's CONTAINER default. */
function effectiveType(node: ComponentNodeMetadata): BlockType {
  return node.type ?? 'CONTAINER';
}

/** The effective bind source — the declared one, or the SDK's NONE default. */
function effectiveSource(binding: BindingMetadata | undefined): string {
  return binding?.source ?? 'NONE';
}

/** Indentation helper — two spaces per level, deterministic. */
function indent(level: number): string {
  return '  '.repeat(level);
}

/**
 * A simple `Foo` service-class identity for an ENTITY `ref`. The generated
 * services live at `../services/<kebab>.service` exporting `<Ref>Service`
 * (service-gen). We strip any package qualifier defensively so a ref carrying a
 * FQN (`com.shop.Product`) still resolves to the simple `Product`.
 */
function simpleRef(ref: string): string {
  const parts = ref.split('.');
  return parts[parts.length - 1];
}

/** The injected field name for an entity service (camelCase + `Service`). */
function serviceFieldName(ref: string): string {
  return `${DslMapper.toCamelCase(simpleRef(ref))}Service`;
}

/**
 * The effective route PATH for a view (RFC §5 route-assembly): the declared
 * `@View.route` with any leading slash(es) stripped (Angular child route paths
 * are relative segments), falling back to the view's kebab name. This is the
 * single source of truth shared by `generateViewRoute` (the per-view route file)
 * and the app shell's `app.routes.ts` (which redirects to it) — keep them aligned.
 */
export function viewRoutePath(view: ViewMetadata): string {
  const kebab = DslMapper.toKebabCase(view.name);
  return (view.route ?? kebab).replace(/^\/+/, '');
}

/** The exported route-array const name for a view (`<camel>Routes`). */
export function viewRouteConstName(view: ViewMetadata): string {
  return `${DslMapper.toCamelCase(view.name)}Routes`;
}

/**
 * The import specifier the app shell uses to pull a view's route const, relative
 * to `src/app/` (where `app.routes.ts` lives): `./pages/<kebab>.route`. Matches
 * the `pages/<kebab>.route.ts` path `generateViewRoute` emits.
 */
export function viewRouteImportPath(view: ViewMetadata): string {
  return `./pages/${DslMapper.toKebabCase(view.name)}.route`;
}

/** The effective ViewKind — the declared one, or the SDK's PAGE default. */
export function effectiveViewKind(view: ViewMetadata): string {
  return view.kind ?? 'PAGE';
}

/** Whether a view is a PAGE (a top-level routable destination, eligible for the
 *  default redirect + a sidebar nav link). Non-PAGE kinds still get a route. */
export function isPageView(view: ViewMetadata): boolean {
  return effectiveViewKind(view) === 'PAGE';
}

interface ViewGenState {
  /** ENTITY refs to inject as <Ref>Service (deduped, declaration-ordered). */
  readonly entityRefs: string[];
  /** ACTION refs to emit click-handler stubs for (deduped, declaration-ordered). */
  readonly actionRefs: string[];
}

/** Collect the ENTITY + ACTION refs across the whole tree, in declaration order. */
function collectBindings(view: ViewMetadata): ViewGenState {
  const entityRefs: string[] = [];
  const actionRefs: string[] = [];

  const visit = (node: ComponentNodeMetadata): void => {
    const source = effectiveSource(node.binding);
    const ref = node.binding?.ref;
    if (ref) {
      if (source === 'ENTITY' && !entityRefs.includes(ref)) {
        entityRefs.push(ref);
      } else if (source === 'ACTION' && !actionRefs.includes(ref)) {
        actionRefs.push(ref);
      }
    }
    for (const child of node.children) {
      visit(child);
    }
  };

  for (const region of view.regions) {
    for (const node of region.components) {
      visit(node);
    }
  }
  return { entityRefs, actionRefs };
}

/** The opening / closing tag + base class for a BlockType (CUSTOM/SLOT handled by caller). */
function blockTag(type: BlockType): { tag: string; cls: string } {
  switch (type) {
    case 'HERO':
      return { tag: 'section', cls: 'exeris-hero bg-exeris-primary text-white p-8 rounded-md' };
    case 'CARD':
      return { tag: 'article', cls: 'exeris-card rounded-md border border-gray-200 dark:border-gray-700 p-4 shadow-sm' };
    case 'GRID':
      return { tag: 'div', cls: 'exeris-grid grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4' };
    case 'LIST':
      return { tag: 'ul', cls: 'exeris-list space-y-2' };
    case 'RICH_TEXT':
      return { tag: 'div', cls: 'exeris-rich-text prose dark:prose-invert max-w-none' };
    case 'NAV':
      return { tag: 'nav', cls: 'exeris-nav flex gap-4' };
    case 'IMAGE':
      return { tag: 'figure', cls: 'exeris-image' };
    case 'FORM':
      // FORM defers to the existing form vocabulary (slice 2, RFC §5). Slice 1
      // emits a placeholder block, never a faked form.
      return { tag: 'div', cls: 'exeris-form-placeholder rounded-md border border-dashed border-gray-300 dark:border-gray-600 p-4 text-sm text-gray-500' };
    case 'CONTAINER':
    default:
      return { tag: 'div', cls: 'exeris-container' };
  }
}

/**
 * Render one component node (and its children, recursively) to template lines.
 * Bindings are honoured per the slice-1 contract; OUT bindings emit a
 * TODO(@View G#) HTML comment passthrough rather than faking the data path.
 */
function renderNode(node: ComponentNodeMetadata, level: number): string[] {
  const lines: string[] = [];
  const type = effectiveType(node);
  const pad = indent(level);
  const source = effectiveSource(node.binding);
  const binding = node.binding;

  // --- OUT bindings: a clearly-commented TODO passthrough (never faked) ---
  // expression-carrying bindings are the G1 parameterised/relational fork; a
  // PROJECTION beyond a named read, STREAM, mesh and token/theme are G1/G2/G3/G6.
  if (binding?.expression) {
    lines.push(`${pad}<!-- TODO(@View G1): parameterised/relational binding via expression="${escapeAttr(binding.expression)}" is out of slice 1; emit a real read once the SKU corpus fixes the @Bind(via=…) shape -->`);
  }
  if (source === 'PROJECTION') {
    // A named projection read is the most a slice-1 emitter can honour; anything
    // richer (joins, params) is G1.
    lines.push(`${pad}<!-- TODO(@View G1): PROJECTION binding ref="${escapeAttr(binding?.ref ?? '')}" path="${escapeAttr(binding?.path ?? '')}" — only a named read is modelled in slice 1 -->`);
  }
  if (source === 'SLOT') {
    lines.push(`${pad}<!-- TODO(@View G6): SLOT/host-fill binding is the token/theme + composition fork; emitted as an ng-content host slot below -->`);
  }
  if (binding?.language) {
    lines.push(`${pad}<!-- TODO(@View G2): STREAM/expression language="${escapeAttr(binding.language)}" binding is out of slice 1 (pairs with the SSE emitter, ADR-044) -->`);
  }

  // --- CUSTOM: the named customType selector element (escape hatch) ---
  if (type === 'CUSTOM') {
    const selector = node.customType ? DslMapper.toKebabCase(simpleRef(node.customType)) : 'app-custom-block';
    if (node.children.length === 0) {
      lines.push(`${pad}<${selector}></${selector}>`);
    } else {
      lines.push(`${pad}<${selector}>`);
      for (const child of node.children) {
        lines.push(...renderNode(child, level + 1));
      }
      lines.push(`${pad}</${selector}>`);
    }
    return lines;
  }

  // --- SLOT: a named ng-content host slot ---
  if (type === 'SLOT') {
    const slotName = node.binding?.ref ? ` select="[slot=${escapeAttr(node.binding.ref)}]"` : '';
    lines.push(`${pad}<ng-content${slotName}></ng-content>`);
    return lines;
  }

  const { tag, cls } = blockTag(type);
  const dataBlock = ` data-block="${type}"`;

  // Authored / literal content for STATIC / NONE: render props text if present.
  const propsText = (source === 'STATIC' || source === 'NONE') && node.props ? node.props : null;

  // ENTITY: a signal read referencing the generated service. We emit the read in
  // the template as an interpolation off the injected service signal.
  let entityRead: string | null = null;
  if (source === 'ENTITY' && binding?.ref) {
    const field = serviceFieldName(binding.ref);
    const path = binding.path ? `?.${binding.path}` : '';
    entityRead = `{{ ${field}.current()${path} }}`;
  }

  // ACTION: a click handler calling the named action method.
  const actionAttr = source === 'ACTION' && binding?.ref ? ` (click)="${actionMethodName(binding.ref)}()"` : '';

  if (node.children.length === 0 && !entityRead && !propsText) {
    if (type === 'FORM') {
      lines.push(`${pad}<${tag} class="${cls}"${dataBlock}${actionAttr}>`);
      lines.push(`${pad}  <!-- TODO(@View): FORM block — leaf-field form emission defers to the existing form vocabulary (slice 2, RFC §5) -->`);
      lines.push(`${pad}</${tag}>`);
    } else {
      lines.push(`${pad}<${tag} class="${cls}"${dataBlock}${actionAttr}></${tag}>`);
    }
    return lines;
  }

  lines.push(`${pad}<${tag} class="${cls}"${dataBlock}${actionAttr}>`);
  if (type === 'FORM') {
    lines.push(`${pad}  <!-- TODO(@View): FORM block — leaf-field form emission defers to the existing form vocabulary (slice 2, RFC §5) -->`);
  }
  if (propsText) {
    lines.push(`${pad}  ${escapeText(propsText)}`);
  }
  if (entityRead) {
    lines.push(`${pad}  ${entityRead}`);
  }
  for (const child of node.children) {
    lines.push(...renderNode(child, level + 1));
  }
  lines.push(`${pad}</${tag}>`);
  return lines;
}

/** Render one region as a <section data-region="slot"> wrapper holding its nodes. */
function renderRegion(region: RegionMetadata, level: number): string[] {
  const pad = indent(level);
  const slot = region.slot ?? 'region';
  const lines: string[] = [];
  lines.push(`${pad}<section data-region="${escapeAttr(slot)}">`);
  for (const node of region.components) {
    lines.push(...renderNode(node, level + 1));
  }
  lines.push(`${pad}</section>`);
  return lines;
}

/** A valid camelCase action method name from an ACTION ref (kebab/snake-safe). */
function actionMethodName(ref: string): string {
  return DslMapper.toMethodName(simpleRef(ref));
}

/** Escape a value for an HTML attribute (double-quote context). */
function escapeAttr(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** Escape a value for HTML text content. Angular treats `{{ }}` specially, so we
 *  leave braces alone (authored props is trusted literal text) but neutralise tags. */
function escapeText(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * Emit one standalone Angular component for a view. Returns a single OutputFile
 * at `pages/<kebab>.component.ts` (re-rooted under src/app by the orchestrator).
 */
export function generateView(view: ViewMetadata, _config: GeneratorConfig): OutputFile {
  const kebab = DslMapper.toKebabCase(view.name);
  const className = `${view.name}PageComponent`;
  const selector = `app-${kebab}-page`;
  const title = view.title ?? view.name;
  const { entityRefs, actionRefs } = collectBindings(view);

  const lines: string[] = [];
  lines.push('/**');
  lines.push(` * ${view.name} Page Component (presentation IR → Angular 22).`);
  lines.push(' * Generated by @exeris/codegen-ts — DO NOT EDIT.');
  lines.push(' * Standalone, signal-first; emitted from the framework-neutral @View IR (RFC-2026-06-28).');
  lines.push(' */');
  lines.push('');
  const coreImports = ['Component', 'ChangeDetectionStrategy'];
  if (entityRefs.length > 0) {
    coreImports.push('inject');
  }
  lines.push(`import { ${coreImports.join(', ')} } from '@angular/core';`);
  lines.push("import { CommonModule } from '@angular/common';");
  // ENTITY bindings reference the generated services by ref.
  for (const ref of entityRefs) {
    const simple = simpleRef(ref);
    lines.push(`import { ${simple}Service } from '../services/${DslMapper.toKebabCase(simple)}.service';`);
  }
  lines.push('');
  lines.push('@Component({');
  lines.push(`  selector: '${selector}',`);
  lines.push('  standalone: true,');
  lines.push('  imports: [CommonModule],');
  lines.push('  changeDetection: ChangeDetectionStrategy.OnPush,');
  lines.push('  template: `');
  lines.push(`    <main class="exeris-page" data-view="${escapeAttr(view.name)}">`);
  lines.push(`      <h1 class="text-2xl font-bold font-exeris mb-6">${escapeText(title)}</h1>`);
  for (const region of view.regions) {
    lines.push(...renderRegion(region, 3));
  }
  lines.push('    </main>');
  lines.push('  `,');
  lines.push('})');
  lines.push(`export class ${className} {`);
  // ENTITY services injected (signal-first: services expose a `current()` signal).
  for (const ref of entityRefs) {
    const simple = simpleRef(ref);
    lines.push(`  protected readonly ${serviceFieldName(ref)} = inject(${simple}Service);`);
  }
  if (entityRefs.length > 0 && actionRefs.length > 0) {
    lines.push('');
  }
  // ACTION click-handler stubs calling the named action.
  for (const ref of actionRefs) {
    const method = actionMethodName(ref);
    lines.push('');
    lines.push(`  protected ${method}(): void {`);
    lines.push(`    // TODO(@View): wire the '${escapeText(ref)}' action — slice 1 emits the handler stub only.`);
    lines.push('  }');
  }
  lines.push('}');
  lines.push('');

  return { path: `pages/${kebab}.component.ts`, content: lines.join('\n') };
}

/**
 * Emit the paired lazy route for a view → its component, so the page is
 * routable on its own. The app shell's `app.routes.ts` imports this const and
 * spreads it into its routes array (RFC §5 route-assembly, wired in
 * app-structure-gen). A view without a `route` falls back to its kebab name.
 */
export function generateViewRoute(view: ViewMetadata, _config: GeneratorConfig): OutputFile {
  const kebab = DslMapper.toKebabCase(view.name);
  const className = `${view.name}PageComponent`;
  // Effective route path — declared `@View.route` minus any leading slash, else
  // kebab name. Shared with the app shell via viewRoutePath so they stay aligned.
  const path = viewRoutePath(view);
  const title = view.title ?? view.name;

  const lines: string[] = [];
  lines.push('/**');
  lines.push(` * Route for the ${view.name} page (presentation IR → Angular 22).`);
  lines.push(' * Generated by @exeris/codegen-ts — DO NOT EDIT.');
  lines.push(' * Imported + spread into app.routes.ts by the app shell (RFC-2026-06-28 §5).');
  lines.push(' */');
  lines.push('');
  lines.push("import { Routes } from '@angular/router';");
  lines.push('');
  lines.push(`export const ${viewRouteConstName(view)}: Routes = [`);
  lines.push('  {');
  lines.push(`    path: '${escapeAttr(path)}',`);
  lines.push(`    loadComponent: () => import('./${kebab}.component').then((m) => m.${className}),`);
  lines.push(`    title: '${escapeAttr(title)}',`);
  lines.push('  },');
  lines.push('];');
  lines.push('');

  return { path: `pages/${kebab}.route.ts`, content: lines.join('\n') };
}
