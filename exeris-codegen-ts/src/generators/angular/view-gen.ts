/**
 * Angular View (Presentation IR) Generator — RFC-2026-06-28 §3.
 *
 * Emits ONE standalone, signal-first Angular 22 component per `@View`
 * (`view_*.json` → ViewMetadata), under `pages/<kebab>.component.ts`, plus a
 * paired lazy route (`pages/<kebab>.route.ts`) so the page is routable. This is
 * the codegen-ts half of the build gate the SDK presentation IR opens: the SDK
 * names no Angular type (framework-neutral IR in, Angular out); the ui-kit stays
 * a consumer: its `exeris-*` design-token utilities and, where the kit defines one
 * for the block, its `.exeris-*` component class skin the emitted markup.
 *
 * Determinism (hard-constraint #3): the template is assembled in declaration
 * order (regions, then each region's component tree depth-first), with no Date /
 * random / hash-iteration leakage. Same ViewMetadata → byte-identical output.
 *
 * BlockType → element mapping (RFC §3):
 *   HERO       → <section class="exeris-hero …">
 *   CARD       → <article class="exeris-card p-4"> (the kit's component class)
 *   GRID       → <div class="exeris-grid …">
 *   LIST       → <ul class="exeris-list …">, each child in an <li> (one <li> per row when the
 *                LIST iterates an entity collection)
 *   CONTAINER  → <div class="exeris-container …">
 *   RICH_TEXT  → <div class="exeris-rich-text …">
 *   NAV        → <nav class="exeris-nav …">
 *   IMAGE      → <figure class="exeris-image …">
 *   SLOT       → <ng-content> (a named host slot)
 *   CUSTOM     → the named customType selector element; the component behind it is imported
 *                through the `customBlocks` config entry for that customType, and its
 *                `props` JSON is a class field bound as `[props]`
 *   FORM       → a placeholder block (leaf-field form emission is slice 2, RFC §5)
 * CARD is the only block the kit has a component class for. The other `exeris-<block>`
 * names are marker classes the kit does not define: they style nothing, and the
 * utilities beside them carry the look.
 *
 * Bindings HONOURED in slice 1 (RFC §3):
 *   STATIC / NONE → authored / literal structure (props text when present)
 *   ENTITY        → inject(<Ref>Service) + a signal read referencing the
 *                   generated service by `ref`
 *   ACTION        → a click handler stub calling the named action
 * Bindings OUT of slice 1 — emitted as clearly-commented TODO passthroughs (never
 * faked), each referencing the corpus gap it belongs to. Classified by the bind
 * SOURCE, never by which attributes happen to be set:
 *   PROJECTION beyond a named read     → TODO(@View G1)
 *   a data source (ENTITY / PROJECTION / ACTION / SLOT) with an expression
 *                                      → TODO(@View G1) (parameterised / relational)
 *   STREAM source (G2)                 → nothing to emit: BindSource has no STREAM
 *                                        constant, so no metadata can ask for one
 *                                        (an SDK record change comes first)
 *   mesh binding (G3)                  → TODO(@View G3)
 *   token / theme binding (G6)         → TODO(@View G6)
 * Wrong attributes: STATIC / NONE draws from nothing, so an expression / language /
 * path (or a ref, outside a SLOT block, where ref names the slot) on it is ignored.
 * That emits a comment naming them and pointing at @Block(props) — the processor
 * warns at the @Bind declaration too.
 */

import type {
  DomainMetadata,
  ViewMetadata,
  RegionMetadata,
  ComponentNodeMetadata,
  BindingMetadata,
  BlockType,
} from '../../models/domain-model.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import { primaryKeyField } from '../../core/primary-key.js';
import type { GeneratorConfig } from '../../config.js';
import type { OutputFile } from '../../orchestrator.js';
import { fileHeaderLines } from '../file-header.js';

/** The effective block type — the declared one, or the SDK's CONTAINER default. */
function effectiveType(node: ComponentNodeMetadata): BlockType {
  return node.type ?? 'CONTAINER';
}

/** The effective bind source — the declared one, or the SDK's NONE default. */
function effectiveSource(binding: BindingMetadata | undefined): string {
  return binding?.source ?? 'NONE';
}

/** STATIC and NONE both mean "draws from nothing": the node is authored content. */
function isStaticSource(source: string): boolean {
  return source === 'STATIC' || source === 'NONE';
}

/**
 * The attributes a STATIC / NONE binding carries that it cannot use, as `[name, value]` pairs in a
 * fixed order. `ref` is exempt on a SLOT block, where it names the slot whatever the source.
 */
function wrongAttributesOnStatic(
  source: string,
  type: BlockType,
  binding: BindingMetadata | undefined,
): Array<[string, string]> {
  if (!binding || !isStaticSource(source)) {
    return [];
  }
  const found: Array<[string, string]> = [];
  if (binding.ref && type !== 'SLOT') found.push(['ref', binding.ref]);
  if (binding.path) found.push(['path', binding.path]);
  if (binding.expression) found.push(['expression', binding.expression]);
  if (binding.language) found.push(['language', binding.language]);
  return found;
}

/**
 * A view the generator cannot emit as a component that compiles: a CUSTOM block whose component
 * `customBlocks` does not name, or whose `props` are not JSON, or a class name `customBlocks`
 * imports from two modules. The message names the view and what to correct, so the run fails at
 * generation, where the cause is visible, instead of at `ng build`.
 */
export class ViewGenerationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ViewGenerationError';
  }
}

/** Code-unit order: independent of the host locale, so emitted import order is too. */
function byCodeUnit(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** One emitted `blockProps<N>` field: its name and the TypeScript literal it holds. */
interface BlockPropsField {
  readonly name: string;
  readonly literal: string;
}

/** A `customBlocks` entry: the module a CUSTOM block's component is imported from, and its class. */
interface CustomBlockEntry {
  readonly import: string;
  readonly symbol: string;
}

/**
 * Per-view render state. Fields are numbered and CUSTOM imports recorded in render order (regions,
 * then each region's tree depth-first), so the same view always yields the same names.
 */
interface RenderContext {
  readonly viewName: string;
  /** The loaded entities, which a binding's ref names. */
  readonly domains: readonly DomainMetadata[];
  readonly customBlocks: Readonly<Record<string, CustomBlockEntry>>;
  /** customType → its config entry, for every CUSTOM block the view renders. */
  readonly usedBlocks: Map<string, CustomBlockEntry>;
  readonly blockProps: BlockPropsField[];
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

/** The injected field name for an entity's signal store (camelCase + `Store`). */
function storeFieldName(ref: string): string {
  return `${DslMapper.toCamelCase(simpleRef(ref))}Store`;
}

/** The `@for` loop variable for an entity collection (camelCase of the entity name). */
function itemVarName(ref: string): string {
  return DslMapper.toCamelCase(simpleRef(ref));
}

/**
 * The `@for` track key: the primary key of the bound entity, on which the emitted `<Entity>Store`
 * keys its own state. Tracking by index instead would defeat the point of `@for` on a signal
 * collection.
 */
function trackField(ref: string, ctx: RenderContext): string {
  const simple = simpleRef(ref);
  return primaryKeyField(ctx.domains.find((d) => d.entityName === simple));
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

/**
 * The exported route-array const name for a view (`<camel>Routes`).
 *
 * Uses `toMethodName`, not `toCamelCase`: a view name is author-chosen kebab by convention,
 * and `toCamelCase` only lower-cases the first character. Ensures the exported const parses.
 */
export function viewRouteConstName(view: ViewMetadata): string {
  return `${DslMapper.toMethodName(view.name)}Routes`;
}

/**
 * The emitted page-component class name for a view.
 *
 * Shared by the component and its route file so the `loadComponent` import always names the class the
 * component file actually exports — deriving it twice is how they could drift.
 */
export function viewComponentClassName(view: ViewMetadata): string {
  return `${DslMapper.toTypeName(view.name)}PageComponent`;
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

/**
 * Whether a view's page reads entity data: a node bound to an ENTITY injects that entity's store and
 * loads it on init, which calls the kernel API.
 */
export function viewReadsEntityData(view: ViewMetadata): boolean {
  return collectBindings(view).entityRefs.length > 0;
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
      // The kit's component class carries the surface, border, radius and shadow; only the
      // padding stays a utility, because `.exeris-card` has none and its body padding lives on a
      // nested `.exeris-card-body` the block tree does not model.
      return { tag: 'article', cls: 'exeris-card p-4' };
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
function renderNode(
  node: ComponentNodeMetadata,
  level: number,
  ctx: RenderContext,
  where: string,
  itemVar?: string,
): string[] {
  const lines: string[] = [];
  const type = effectiveType(node);
  const pad = indent(level);
  const source = effectiveSource(node.binding);
  const binding = node.binding;

  // --- Diagnostics, classified by bind SOURCE ---
  //
  // Keyed on the source, never on which attributes are set. STATIC / NONE draws from nothing:
  // its attributes are the author's mistake, and the comment says where the content belongs. An
  // `expression` is the G1 relational fork only on a data source, and a `language` alone is no
  // stream — BindSource has no STREAM constant to express one.
  const wrongAttrs = wrongAttributesOnStatic(source, type, binding);
  if (wrongAttrs.length > 0) {
    const named = wrongAttrs.map(([k, v]) => `${k}="${escapeAttr(v)}"`).join(' ');
    lines.push(`${pad}<!-- @View: wrong attribute — @Bind(source = ${source}) draws from nothing, so ${named} ${wrongAttrs.length === 1 ? 'is' : 'are'} ignored; authored content belongs in @Block(props) -->`);
  }

  // --- OUT bindings: a clearly-commented TODO passthrough (never faked) ---
  // an expression on a data source is the G1 parameterised/relational fork; a
  // PROJECTION beyond a named read, mesh and token/theme are G1/G3/G6.
  if (binding?.expression && !isStaticSource(source)) {
    const lang = binding.language ? ` language="${escapeAttr(binding.language)}"` : '';
    lines.push(`${pad}<!-- TODO(@View G1): parameterised/relational binding via expression="${escapeAttr(binding.expression)}"${lang} is out of slice 1; emit a real read once the SKU corpus fixes the @Bind(via=…) shape -->`);
  }
  if (source === 'PROJECTION') {
    // A named projection read is the most a slice-1 emitter can honour; anything
    // richer (joins, params) is G1.
    lines.push(`${pad}<!-- TODO(@View G1): PROJECTION binding ref="${escapeAttr(binding?.ref ?? '')}" path="${escapeAttr(binding?.path ?? '')}" — only a named read is modelled in slice 1 -->`);
  }
  if (source === 'SLOT') {
    lines.push(`${pad}<!-- TODO(@View G6): SLOT/host-fill binding is the token/theme + composition fork; emitted as an ng-content host slot below -->`);
  }

  // --- CUSTOM: the named customType selector element (escape hatch) ---
  if (type === 'CUSTOM') {
    const customType = resolveCustomBlock(node, ctx, where);
    const selector = DslMapper.toKebabCase(simpleRef(customType));
    const propsAttr = customBlockPropsBinding(node, ctx, where);
    if (node.children.length === 0) {
      lines.push(`${pad}<${selector}${propsAttr}></${selector}>`);
    } else {
      lines.push(`${pad}<${selector}${propsAttr}>`);
      node.children.forEach((child, i) => {
        lines.push(...renderNode(child, level + 1, ctx, `${where}.children[${i}]`));
      });
      lines.push(`${pad}</${selector}>`);
    }
    return lines;
  }

  // --- SLOT: a named ng-content host slot ---
  // NOTE: <ng-content> only projects when the component is used as a child in a
  // parent template. A PAGE-kind view is a routed destination (never composed into
  // a parent), so a SLOT in a PAGE renders nothing — it is meaningful only in the
  // composable kinds (SECTION/COMPONENT/FRAGMENT). Suppressing it per-kind is a
  // slice-2 refinement (needs the enclosing view kind threaded into renderNode).
  if (type === 'SLOT') {
    const slotName = node.binding?.ref ? ` select="[slot=${escapeAttr(node.binding.ref)}]"` : '';
    lines.push(`${pad}<ng-content${slotName}></ng-content>`);
    return lines;
  }

  const { tag, cls } = blockTag(type);
  // A <ul> holds only <li>: every item a LIST renders is wrapped in one.
  const isList = type === 'LIST';
  const dataBlock = ` data-block="${type}"`;

  // Authored / literal content for STATIC / NONE: render props text if present.
  const propsText = isStaticSource(source) && node.props ? node.props : null;

  // ENTITY: a signal read off the generated STORE.
  //
  // `store-gen` is the signal-first surface: `<Entity>Store` exposes `entities` (a readonly
  // signal of the collection) and `selected` (the single entity). A LIST binds the collection
  // and iterates; anything else reads the selected row.
  const isCollection = type === 'LIST' || type === 'GRID';
  let entityRead: string | null = null;
  let iteration: { open: string; close: string; item: string } | null = null;
  if (source === 'ENTITY' && binding?.ref) {
    const field = storeFieldName(binding.ref);
    if (isCollection) {
      const item = itemVarName(binding.ref);
      iteration = {
        open: `@for (${item} of ${field}.entities(); track ${item}.${trackField(binding.ref, ctx)}) {`,
        close: '}',
        item,
      };
    } else if (binding.path) {
      // Inside a @for, the row IS the loop variable — never read the store's `selected`.
      entityRead = itemVar
        ? `{{ ${itemVar}.${binding.path} }}`
        : `{{ ${field}.selected()?.${binding.path} }}`;
    }
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
    lines.push(isList ? `${pad}  <li>${escapeText(propsText)}</li>` : `${pad}  ${escapeText(propsText)}`);
  }
  if (entityRead) {
    lines.push(isList ? `${pad}  <li>${entityRead}</li>` : `${pad}  ${entityRead}`);
  }
  const childVar = iteration ? iteration.item : itemVar;
  const renderChild = (child: ComponentNodeMetadata, i: number, childLevel: number): string[] =>
    renderNode(child, childLevel, ctx, `${where}.children[${i}]`, childVar);
  if (iteration) {
    lines.push(`${pad}  ${iteration.open}`);
    if (isList) {
      // One <li> per row: the children are the row's content.
      lines.push(`${pad}    <li>`);
      node.children.forEach((child, i) => lines.push(...renderChild(child, i, level + 3)));
      lines.push(`${pad}    </li>`);
    } else {
      node.children.forEach((child, i) => lines.push(...renderChild(child, i, level + 1)));
    }
    lines.push(`${pad}  ${iteration.close}`);
  } else if (isList) {
    // One <li> per child: each child is an item of the list.
    node.children.forEach((child, i) => {
      lines.push(`${pad}  <li>`);
      lines.push(...renderChild(child, i, level + 2));
      lines.push(`${pad}  </li>`);
    });
  } else {
    node.children.forEach((child, i) => lines.push(...renderChild(child, i, level + 1)));
  }
  lines.push(`${pad}</${tag}>`);
  return lines;
}

/**
 * The customType of a CUSTOM block, after checking `customBlocks` maps it to the component that
 * renders it. Records the entry so the view imports it. Throws when the block names no customType,
 * or names one the config does not map: the emitted element would otherwise be unknown to Angular.
 */
function resolveCustomBlock(node: ComponentNodeMetadata, ctx: RenderContext, where: string): string {
  const customType = node.customType;
  if (!customType) {
    throw new ViewGenerationError(
      `view '${ctx.viewName}': the CUSTOM block at ${where} declares no customType, so no ` +
        `customBlocks entry can name the component that renders it`,
    );
  }
  const entry = Object.prototype.hasOwnProperty.call(ctx.customBlocks, customType)
    ? ctx.customBlocks[customType]
    : undefined;
  if (!entry) {
    throw new ViewGenerationError(
      `view '${ctx.viewName}': the CUSTOM block at ${where} has customType '${customType}', and ` +
        `customBlocks has no entry for it; add "customBlocks": { "${customType}": ` +
        `{ "import": "<module specifier>", "symbol": "<exported component class>" } } to the config`,
    );
  }
  ctx.usedBlocks.set(customType, entry);
  return customType;
}

/**
 * The `[props]` binding of a CUSTOM block, and the `blockProps<N>` field it reads, numbered in render
 * order. A block with no props gets neither. Throws when the props are not JSON.
 */
function customBlockPropsBinding(node: ComponentNodeMetadata, ctx: RenderContext, where: string): string {
  // The processor writes no props for a blank @Block(props), so an empty string is the same absence.
  if (!node.props) {
    return '';
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(node.props);
  } catch (e) {
    throw new ViewGenerationError(
      `view '${ctx.viewName}': the CUSTOM block at ${where} (customType '${node.customType}') has ` +
        `props that are not valid JSON (${String(e)}): ${node.props}`,
    );
  }
  const name = `blockProps${ctx.blockProps.length + 1}`;
  ctx.blockProps.push({ name, literal: JSON.stringify(parsed) });
  return ` [props]="${name}"`;
}

/**
 * The CUSTOM block components a view imports, one per distinct `{ import, symbol }`, ordered by
 * symbol then module specifier. Throws when two entries bind the same symbol from different modules:
 * the emitted file would declare the identifier twice.
 */
function customBlockImports(ctx: RenderContext): CustomBlockEntry[] {
  const distinct: CustomBlockEntry[] = [];
  for (const entry of ctx.usedBlocks.values()) {
    if (!distinct.some((d) => d.symbol === entry.symbol && d.import === entry.import)) {
      distinct.push({ import: entry.import, symbol: entry.symbol });
    }
  }
  distinct.sort((a, b) => byCodeUnit(a.symbol, b.symbol) || byCodeUnit(a.import, b.import));
  for (let i = 1; i < distinct.length; i++) {
    if (distinct[i].symbol === distinct[i - 1].symbol) {
      throw new ViewGenerationError(
        `view '${ctx.viewName}': customBlocks imports '${distinct[i].symbol}' from both ` +
          `'${distinct[i - 1].import}' and '${distinct[i].import}'; one class name can be imported ` +
          `from one module only`,
      );
    }
  }
  return distinct;
}

/** Render one region as a <section data-region="slot"> wrapper holding its nodes. */
function renderRegion(region: RegionMetadata, level: number, ctx: RenderContext, where: string): string[] {
  const pad = indent(level);
  const slot = region.slot ?? 'region';
  const lines: string[] = [];
  lines.push(`${pad}<section data-region="${escapeAttr(slot)}">`);
  region.components.forEach((node, i) => {
    lines.push(...renderNode(node, level + 1, ctx, `${where}.components[${i}]`));
  });
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

/** Escape a value for an emitted single-quoted TypeScript string literal (route
 *  path / title in the .route.ts file) — NOT an HTML context, so no entity encoding. */
function escapeTsStr(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

/**
 * Emit one standalone Angular component for a view. Returns a single OutputFile
 * at `pages/<kebab>.component.ts` (re-rooted under src/app by the orchestrator).
 * `domains` are the loaded entities: a collection bound to one of them is tracked by its key.
 */
export function generateView(
  view: ViewMetadata,
  config: GeneratorConfig,
  domains: readonly DomainMetadata[] = [],
): OutputFile {
  const kebab = DslMapper.toKebabCase(view.name);
  const className = viewComponentClassName(view);
  const selector = `app-${kebab}-page`;
  const title = view.title ?? view.name;
  const { entityRefs, actionRefs } = collectBindings(view);

  // The template is rendered before the header: it decides which CUSTOM components are imported
  // and which blockProps fields the class declares.
  const ctx: RenderContext = {
    viewName: view.name,
    domains,
    customBlocks: config.customBlocks ?? {},
    usedBlocks: new Map(),
    blockProps: [],
  };
  const templateLines: string[] = [];
  view.regions.forEach((region, i) => {
    templateLines.push(...renderRegion(region, 3, ctx, `regions[${i}]`));
  });
  const blockImports = customBlockImports(ctx);

  const lines: string[] = [];
  lines.push(
    ...fileHeaderLines({
      title: `${view.name} Page Component (presentation IR → Angular 22).`,
      notes: ['Standalone, signal-first; emitted from the framework-neutral @View IR (RFC-2026-06-28).'],
      doNotEdit: 'on-provenance',
    }),
  );
  lines.push('');
  const coreImports = ['Component', 'ChangeDetectionStrategy'];
  if (entityRefs.length > 0) {
    coreImports.push('inject', 'OnInit');
  }
  lines.push(`import { ${coreImports.join(', ')} } from '@angular/core';`);
  lines.push("import { CommonModule } from '@angular/common';");
  // ENTITY bindings reference the generated signal STORES by ref (store-gen), not the RxJS services:
  // the template reads `entities()` / `selected()`, which only the store exposes.
  for (const ref of entityRefs) {
    const simple = simpleRef(ref);
    lines.push(`import { ${simple}Store } from '../stores/${DslMapper.toKebabCase(simple)}.store';`);
  }
  for (const block of blockImports) {
    lines.push(`import { ${block.symbol} } from '${escapeTsStr(block.import)}';`);
  }
  lines.push('');
  lines.push('@Component({');
  lines.push(`  selector: '${selector}',`);
  lines.push('  standalone: true,');
  lines.push(`  imports: [${['CommonModule', ...blockImports.map((b) => b.symbol)].join(', ')}],`);
  lines.push('  changeDetection: ChangeDetectionStrategy.OnPush,');
  lines.push('  template: `');
  lines.push(`    <main class="exeris-page" data-view="${escapeAttr(view.name)}">`);
  if (config.viewHeading !== 'none') {
    lines.push(`      <h1 class="text-2xl font-bold font-exeris mb-6">${escapeText(title)}</h1>`);
  }
  lines.push(...templateLines);
  lines.push('    </main>');
  lines.push('  `,');
  lines.push('})');
  const implementsClause = entityRefs.length > 0 ? ' implements OnInit' : '';
  lines.push(`export class ${className}${implementsClause} {`);
  // ENTITY stores injected — signal-first, as store-gen emits them.
  for (const ref of entityRefs) {
    const simple = simpleRef(ref);
    lines.push(`  protected readonly ${storeFieldName(ref)} = inject(${simple}Store);`);
  }
  // CUSTOM block props, parsed from the IR's JSON and bound as each block's `props` input.
  if (entityRefs.length > 0 && ctx.blockProps.length > 0) {
    lines.push('');
  }
  for (const field of ctx.blockProps) {
    lines.push(`  protected readonly ${field.name} = ${field.literal};`);
  }
  // A store starts empty, so the page has to ask for its data. Without this the template renders a
  // correct, permanently blank screen — the failure mode that is hardest to tell from a backend that
  // is down.
  if (entityRefs.length > 0) {
    lines.push('');
    lines.push('  ngOnInit(): void {');
    for (const ref of entityRefs) {
      lines.push(`    void this.${storeFieldName(ref)}.loadAll();`);
    }
    lines.push('  }');
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
  const className = viewComponentClassName(view);
  // Effective route path — declared `@View.route` minus any leading slash, else
  // kebab name. Shared with the app shell via viewRoutePath so they stay aligned.
  const path = viewRoutePath(view);
  const title = view.title ?? view.name;

  const lines: string[] = [];
  lines.push(
    ...fileHeaderLines({
      title: `Route for the ${view.name} page (presentation IR → Angular 22).`,
      notes: ['Imported + spread into app.routes.ts by the app shell (RFC-2026-06-28 §5).'],
      doNotEdit: 'on-provenance',
    }),
  );
  lines.push('');
  lines.push("import { Routes } from '@angular/router';");
  lines.push('');
  lines.push(`export const ${viewRouteConstName(view)}: Routes = [`);
  lines.push('  {');
  lines.push(`    path: '${escapeTsStr(path)}',`);
  lines.push(`    loadComponent: () => import('./${kebab}.component').then((m) => m.${className}),`);
  lines.push(`    title: '${escapeTsStr(title)}',`);
  lines.push('  },');
  lines.push('];');
  lines.push('');

  return { path: `pages/${kebab}.route.ts`, content: lines.join('\n') };
}
