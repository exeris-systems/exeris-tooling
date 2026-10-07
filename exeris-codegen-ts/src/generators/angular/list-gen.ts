/**
 * Angular List Generator
 *
 * Generates one standalone, OnPush, signal-driven list component per entity.
 *
 * The generated list route pages, sorts and filters on the server and answers a page envelope
 * (ADR-096). The component holds the page, the page size, the sort and the filters as signals, reads
 * the page through an `rxResource` keyed on them, and shows the envelope it answers. It offers a
 * sortable header only for a property the route sorts on and a filter only for one it filters on,
 * by the rules of `list-query.ts`; the route refuses any other parameter with `400`, and has no
 * search.
 */

import type { ActionMetadata, DomainMetadata } from '../../models/domain-model.js';
import { modelTypeName } from '../../models/model-naming.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import type { GeneratorConfig } from '../../config.js';
import type { CodeGenerator, EnumMetadata, GeneratedFile, GeneratorContext } from '../../core/generator-registry.js';
import type { BackendType } from '../../core/backend-strategy.js';
import { outPath } from '../../core/paths.js';
import {
  fieldRenderContext,
  isEnumField,
  resolveFieldRenders,
  toTitleCase,
  type BadgeTone,
  type FieldRenderModel,
  type ListFilterKind,
} from './field-render.js';
import { DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, filterProperties, type ListQueryKind } from './list-query.js';
import { tsSingleQuoted } from './ts-literal.js';
import { entityViews } from './entity-views.js';
import { fileHeaderLines } from '../file-header.js';

export { GeneratedFile };

/** The page sizes the selector offers: within the route's 1..100, the default among them. */
const PAGE_SIZES = [10, 20, 25, 50, 100] as const;

const BADGE_BASE = 'inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ring-1 ring-inset';

/**
 * A boolean cell's "No": the kit's badge shape on its neutral surface and secondary text, which
 * follow the theme. A false value is not a failure, so it takes no danger colour.
 */
const BOOLEAN_FALSE_BADGE = 'exeris-badge bg-[rgb(var(--exeris-bg-tertiary))] text-[rgb(var(--exeris-text-secondary))]';

/** Utility classes per badge tone. Full literals, so Tailwind's source scan finds every one. */
const BADGE_TONE_CLASSES: Readonly<Record<BadgeTone, string>> = {
  blue: 'bg-blue-50 text-blue-700 ring-blue-600/20 dark:bg-blue-900/20 dark:text-blue-300',
  violet: 'bg-violet-50 text-violet-700 ring-violet-600/20 dark:bg-violet-900/20 dark:text-violet-300',
  teal: 'bg-teal-50 text-teal-700 ring-teal-600/20 dark:bg-teal-900/20 dark:text-teal-300',
  amber: 'bg-amber-50 text-amber-800 ring-amber-600/20 dark:bg-amber-900/20 dark:text-amber-300',
  pink: 'bg-pink-50 text-pink-700 ring-pink-600/20 dark:bg-pink-900/20 dark:text-pink-300',
  slate: 'bg-slate-50 text-slate-700 ring-slate-500/20 dark:bg-slate-800 dark:text-slate-300',
};

/**
 * The filter controls. The kit's field classes are full width, so a filter takes its own line on a
 * narrow screen and its natural width beside the others from `sm` on.
 */
const FILTER_SELECT_CLASS = 'exeris-select sm:w-auto';
const FILTER_INPUT_CLASS = 'exeris-input sm:w-auto';

/** A row link or action: a small ghost button, so the row's actions read as one group. */
const ROW_ACTION_CLASS = 'exeris-btn exeris-btn-ghost exeris-btn-sm';

/** `fieldName` → `FieldName`, the suffix of the signals and template references a field owns. */
function capitalize(name: string): string {
  return name.charAt(0).toUpperCase() + name.slice(1);
}

/**
 * The actions a row offers: those the service exposes as a respond-once method (not streaming)
 * and that need nothing but the row's id (no parameters). An action with parameters needs input
 * the list has no place to collect, so it stays on the service.
 */
export function rowActions(domain: DomainMetadata): ActionMetadata[] {
  return (domain.actions ?? []).filter((action) => !action.streaming && (action.params ?? []).length === 0);
}

/** One equality filter the list offers: a filter of the list route, with its control. */
interface ListFilterControl {
  /** The query parameter, and the key of the service's filter. */
  readonly name: string;
  readonly label: string;
  readonly kind: ListFilterKind;
  /** The kind of value the route parses, which decides the value the filter sends. */
  readonly valueKind: ListQueryKind;
  /** The field the filter is, absent for a foreign key no field carries. */
  readonly render?: FieldRenderModel;
}

/** Kinds the service's filter types as a number. */
const NUMBER_FILTER_KINDS: ReadonlySet<ListQueryKind> = new Set<ListQueryKind>(['long', 'int', 'short', 'byte', 'float', 'double']);

export class ListGenerator implements CodeGenerator {
  readonly name = 'ListGenerator';
  readonly artifactType = 'LIST' as const;
  readonly supportedBackends: BackendType[] = [];
  readonly priority = 20;

  generate(domain: DomainMetadata, context: GeneratorContext): GeneratedFile | null {
    if (!entityViews(domain).list) {
      return null;
    }

    const content = this.generateListContent(domain, context);
    const fileName = `${DslMapper.toKebabCase(domain.entityName)}-list.component.ts`;
    const filePath = outPath('components', fileName);

    return {
      path: filePath,
      content,
      artifactType: 'LIST',
      overwritable: true,
    };
  }

  private generateListContent(domain: DomainMetadata, context: GeneratorContext): string {
    const metadata = domain;
    const entityName = metadata.entityName;
    const modelName = modelTypeName(entityName);
    const kebabName = DslMapper.toKebabCase(entityName);
    const displayName = metadata.displayName ?? entityName;
    const pluralName = metadata.pluralName ?? DslMapper.pluralName(entityName);

    // The literal 'id', deliberately, not systemFields.primaryKeyField. Nothing in the pipeline
    // honours that override: KernelFlywayGenerator emits `id UUID PRIMARY KEY` unconditionally,
    // KernelRepositoryGenerator's WHERE clause is the constant " WHERE id = ?", every by-id
    // handler binds the {id} path variable, and the processor says so outright ("generators leave
    // the primary key as the literal id"). Reading it here would make this the only layer that
    // honours it, and the emitted app would then request the wrong identifier.
    const idField = 'id';

    const renderContext = fieldRenderContext(
      metadata, context.allDomains, context.config.generateDetails !== false, context.enums ?? [],
    );
    const renders = resolveFieldRenders(metadata, renderContext);
    const listColumns = this.listColumnNames(renders)
      .map((name) => renders.find((r) => r.name === name)!);

    const views = entityViews(metadata);
    // With the entity's filter switch off no field gets a control, whatever the field says.
    const filterControls = views.filter ? this.filterControls(metadata, renders, renderContext.listQuery?.filters, context.enums ?? []) : [];
    const sortable = listColumns.some((c) => c.list.sortable);
    const enumColumns = listColumns.filter((c) => c.list.cell === 'enum');
    const enumFilters = filterControls.flatMap((f) => (f.kind === 'enum' && f.render ? [f.render] : []));
    const enumTypes = [...new Set([...enumColumns, ...enumFilters].map((r) => r.list.enumType!))].sort();
    const badgeFields = [...new Map([...enumColumns, ...enumFilters].map((r) => [r.name, r])).values()];
    const actions = rowActions(metadata);
    const hasFilters = filterControls.length > 0;

    const pipes = new Set<string>();
    for (const col of listColumns) {
      if (col.list.cell === 'date' || col.list.cell === 'datetime') pipes.add('DatePipe');
      if (col.list.cell === 'number') pipes.add('DecimalPipe');
      if (col.list.cell === 'currency') pipes.add('CurrencyPipe');
      if (col.list.cell === 'percent') pipes.add('PercentPipe');
    }
    const pipeImports = [...pipes].sort();
    const numberFilters = filterControls.some((f) => NUMBER_FILTER_KINDS.has(f.valueKind));
    const serviceImports = [
      modelName, `${entityName}Service`, 'Page',
      ...(hasFilters ? [`${modelName}Filter`] : []),
      ...(sortable ? [`${modelName}SortField`] : []),
    ];

    const lines: string[] = [];

    // Header
    lines.push(
      ...fileHeaderLines({
        title: `${entityName} List Component`,
        notes: [
          '',
          `The list route pages, sorts and filters ${pluralName.toLowerCase()} on the server; this component sends`,
          'it the page, size, sort and filters it holds and shows the page it answers.',
          '',
        ],
        doNotEdit: 'last',
      }),
    );
    lines.push(``);

    // Imports
    lines.push(`import {`);
    lines.push(`  Component,`);
    lines.push(`  ChangeDetectionStrategy,`);
    lines.push(`  inject,`);
    lines.push(`  signal,`);
    lines.push(`  computed,`);
    lines.push(`  linkedSignal,`);
    if (hasFilters) {
      lines.push(`  WritableSignal,`);
    }
    lines.push(`} from '@angular/core';`);
    lines.push(`import { rxResource } from '@angular/core/rxjs-interop';`);
    if (pipeImports.length > 0) {
      lines.push(`import { ${pipeImports.join(', ')} } from '@angular/common';`);
    }
    lines.push(`import { RouterModule } from '@angular/router';`);
    lines.push(`import { ${serviceImports.join(', ')} } from '../services/${kebabName}.service';`);
    if (enumTypes.length > 0) {
      lines.push(`import { ${enumTypes.map((e) => `${e}DisplayNames`).join(', ')} } from '../types/enums';`);
    }
    lines.push(`import { httpErrorMessage } from '../core/http-error';`);
    lines.push(``);

    lines.push(`/** The page sizes the list route serves. */`);
    lines.push(`const MAX_PAGE_SIZE = ${MAX_PAGE_SIZE};`);
    lines.push(``);
    if (numberFilters) {
      lines.push(`/** A number filter's text as the number it sends; blank, or text that is no number, is no filter. */`);
      lines.push(`function numberFilter(text: string): number | undefined {`);
      lines.push(`  const value = Number(text);`);
      lines.push(`  return text.trim() === '' || !Number.isFinite(value) ? undefined : value;`);
      lines.push(`}`);
      lines.push(``);
    }
    if (badgeFields.length > 0) {
      lines.push(`/** The label and classes of one enum constant's badge. */`);
      lines.push(`interface EnumBadge {`);
      lines.push(`  readonly label: string;`);
      lines.push(`  readonly className: string;`);
      lines.push(`}`);
      lines.push(``);
      lines.push(`const NEUTRAL_BADGE = '${BADGE_BASE} ${BADGE_TONE_CLASSES.slate}';`);
      lines.push(``);
    }

    // Component
    lines.push(`@Component({`);
    lines.push(`  selector: 'app-${kebabName}-list',`);
    lines.push(`  standalone: true,`);
    lines.push(`  imports: [RouterModule${pipeImports.map((p) => `, ${p}`).join('')}],`);
    lines.push(`  changeDetection: ChangeDetectionStrategy.OnPush,`);
    lines.push(`  styles: [\``);
    lines.push(`    .row-enter { animation: row-enter-kf 200ms ease-out both; }`);
    lines.push(`    @keyframes row-enter-kf {`);
    lines.push(`      from { opacity: 0; transform: translateY(-10px); }`);
    lines.push(`      to { opacity: 1; transform: translateY(0); }`);
    lines.push(`    }`);
    lines.push(`  \`],`);
    lines.push(`  host: {`);
    lines.push(`    'class': 'block',`);
    lines.push(`  },`);
    lines.push(`  template: \``);

    // Template
    lines.push(`    <div class="space-y-6">`);
    lines.push(`      <!-- Header -->`);
    lines.push(`      <div class="sm:flex sm:items-center sm:justify-between">`);
    lines.push(`        <div>`);
    lines.push(`          <h1 class="text-2xl font-bold tracking-tight text-gray-900 dark:text-white">${pluralName}</h1>`);
    lines.push(`          <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">`);
    lines.push(`            @if (totalElements() > 0) {`);
    lines.push(`              {{ totalElements() }} {{ totalElements() === 1 ? '${displayName.toLowerCase()}' : '${pluralName.toLowerCase()}' }} found`);
    if (hasFilters) {
      lines.push(`            } @else if (filtersActive()) {`);
      lines.push(`              No ${pluralName.toLowerCase()} match`);
    }
    lines.push(`            } @else {`);
    lines.push(`              No ${pluralName.toLowerCase()} yet`);
    lines.push(`            }`);
    lines.push(`          </p>`);
    lines.push(`        </div>`);
    if (views.create) {
      lines.push(`        <div class="mt-4 sm:ml-16 sm:mt-0">`);
      lines.push(`          <a`);
      lines.push(`            routerLink="new"`);
      lines.push(`            data-testid="action-create"`);
      lines.push(`            class="exeris-btn exeris-btn-primary"`);
      lines.push(`          >`);
      lines.push(`            <svg class="-ml-0.5 mr-1.5 h-5 w-5" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">`);
      lines.push(`              <path d="M10 3a1 1 0 011 1v5h5a1 1 0 110 2h-5v5a1 1 0 11-2 0v-5H4a1 1 0 110-2h5V4a1 1 0 011-1z" />`);
      lines.push(`            </svg>`);
      lines.push(`            New ${displayName}`);
      lines.push(`          </a>`);
      lines.push(`        </div>`);
    }
    lines.push(`      </div>`);
    lines.push(``);

    lines.push(`      @if (deleteError()) {`);
    lines.push(`        <div role="alert" data-testid="delete-error" class="exeris-alert exeris-alert-danger text-sm">{{ deleteError() }}</div>`);
    lines.push(`      }`);
    if (actions.length > 0) {
      lines.push(`      @if (actionError()) {`);
      lines.push(`        <div role="alert" data-testid="action-error" class="exeris-alert exeris-alert-danger text-sm">{{ actionError() }}</div>`);
      lines.push(`      }`);
    }
    lines.push(``);

    // Filters — the row exists when it holds a control.
    if (hasFilters) {
      lines.push(`      <!-- Filters -->`);
      lines.push(`      <div class="flex flex-col gap-4 sm:flex-row sm:flex-wrap sm:items-center">`);
      for (const filter of filterControls) {
        lines.push(...this.filterControl(filter));
      }
      lines.push(`      </div>`);
      lines.push(``);
    }

    // Table with @defer for SSR
    lines.push(`      <!-- Data Table -->`);
    lines.push(`      @defer (on viewport; prefetch on idle) {`);
    lines.push(`        <div class="exeris-card overflow-hidden">`);
    lines.push(`          @if (isLoading() && items().length === 0) {`);
    lines.push(`            <!-- Loading skeleton -->`);
    lines.push(`            <div class="animate-pulse">`);
    lines.push(`              <div class="h-12 bg-gray-100 dark:bg-gray-800"></div>`);
    lines.push(`              @for (i of [1,2,3,4,5]; track i) {`);
    lines.push(`                <div class="h-16 border-t border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900">`);
    lines.push(`                  <div class="flex items-center gap-4 p-4">`);
    lines.push(`                    <div class="h-4 w-32 rounded-sm bg-gray-200 dark:bg-gray-700"></div>`);
    lines.push(`                    <div class="h-4 w-24 rounded-sm bg-gray-200 dark:bg-gray-700"></div>`);
    lines.push(`                    <div class="h-4 w-16 rounded-sm bg-gray-200 dark:bg-gray-700"></div>`);
    lines.push(`                  </div>`);
    lines.push(`                </div>`);
    lines.push(`              }`);
    lines.push(`            </div>`);
    lines.push(`          } @else if (error()) {`);
    lines.push(`            <!-- Error state -->`);
    lines.push(`            <div class="flex flex-col items-center justify-center py-12 text-center">`);
    lines.push(`              <svg class="h-12 w-12 text-red-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">`);
    lines.push(`                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />`);
    lines.push(`              </svg>`);
    lines.push(`              <p class="mt-4 text-sm font-medium text-gray-900 dark:text-white">Failed to load ${pluralName.toLowerCase()}</p>`);
    lines.push(`              <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">{{ error() }}</p>`);
    lines.push(`              <button type="button" (click)="loadData()" class="exeris-btn exeris-btn-secondary exeris-btn-sm mt-4">Try again</button>`);
    lines.push(`            </div>`);
    lines.push(`          } @else {`);
    lines.push(`            <table class="exeris-table" aria-label="${pluralName} table" data-testid="data-table" [attr.aria-busy]="isLoading()" [class.opacity-60]="isLoading()">`);
    lines.push(`              <thead>`);
    lines.push(`                <tr>`);

    // Column headers; a sortable one holds a button, so the sort is reachable by keyboard. The
    // table class sets the cells' padding, type and colours; a numeric column only aligns right.
    for (const col of listColumns) {
      lines.push(`                  <th`);
      lines.push(`                    scope="col"`);
      if (col.list.align === 'right') {
        lines.push(`                    class="text-right"`);
      }
      if (col.list.sortable) {
        lines.push(`                    [attr.aria-sort]="sortField() === '${col.name}' ? (sortDirection() === 'asc' ? 'ascending' : 'descending') : 'none'"`);
      }
      lines.push(`                  >`);
      if (col.list.sortable) {
        lines.push(`                    <button type="button" (click)="onSort('${col.name}')" data-testid="sort-${col.name}" class="inline-flex items-center gap-1 uppercase tracking-wider cursor-pointer select-none hover:text-gray-700 dark:hover:text-gray-200${col.list.align === 'right' ? ' flex-row-reverse' : ''}">`);
        lines.push(`                      <span>${col.label}</span>`);
        lines.push(`                      @if (sortField() === '${col.name}') {`);
        lines.push(`                        <svg class="h-4 w-4" [class.rotate-180]="sortDirection() === 'desc'" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">`);
        lines.push(`                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 15l7-7 7 7" />`);
        lines.push(`                        </svg>`);
        lines.push(`                      }`);
        lines.push(`                    </button>`);
      } else {
        lines.push(`                    <span>${col.label}</span>`);
      }
      lines.push(`                  </th>`);
    }
    lines.push(`                  <th scope="col" class="relative">`);
    lines.push(`                    <span class="sr-only">Actions</span>`);
    lines.push(`                  </th>`);
    lines.push(`                </tr>`);
    lines.push(`              </thead>`);
    lines.push(`              <tbody>`);
    lines.push(`                @for (item of items(); track item.${idField}; let i = $index) {`);
    lines.push(`                  <tr animate.enter="row-enter" [style.animation-delay.ms]="i * 50" class="transition-colors" [attr.data-testid]="'row-' + item.${idField}">`);

    // Data cells
    for (const col of listColumns) {
      lines.push(col.list.align === 'right' ? `                    <td class="text-right tabular-nums">` : `                    <td>`);
      lines.push(...this.cell(col, idField));
      lines.push(`                    </td>`);
    }

    // Actions
    lines.push(`                    <td class="text-right">`);
    lines.push(`                      <div class="flex justify-end gap-2">`);
    // Each row link exists only beside the route it opens; Delete is an API call and always stays.
    if (views.detail) {
      lines.push(`                        <a [routerLink]="[item.${idField}]" [attr.data-testid]="'action-view-' + item.${idField}" class="${ROW_ACTION_CLASS}">View</a>`);
    }
    if (views.edit) {
      lines.push(`                        <a [routerLink]="[item.${idField}, 'edit']" [attr.data-testid]="'action-edit-' + item.${idField}" class="${ROW_ACTION_CLASS}">Edit</a>`);
    }
    for (const action of actions) {
      const method = DslMapper.toMethodName(action.name);
      const kebab = DslMapper.toKebabCase(action.name);
      lines.push(`                        <button type="button" (click)="on${capitalize(method)}(item)" [attr.data-testid]="'action-${kebab}-' + item.${idField}" class="${ROW_ACTION_CLASS}">${toTitleCase(method)}</button>`);
    }
    lines.push(`                        <button type="button" (click)="onDelete(item)" [attr.data-testid]="'action-delete-' + item.${idField}" class="exeris-btn exeris-btn-danger exeris-btn-sm">Delete</button>`);
    lines.push(`                      </div>`);
    lines.push(`                    </td>`);
    lines.push(`                  </tr>`);
    lines.push(`                } @empty {`);
    lines.push(`                  <tr>`);
    lines.push(`                    <td colspan="${listColumns.length + 1}" class="py-12 text-center">`);
    lines.push(`                      <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">`);
    lines.push(`                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />`);
    lines.push(`                      </svg>`);
    lines.push(`                      <h3 class="mt-2 text-sm font-medium text-gray-900 dark:text-white">No ${pluralName.toLowerCase()}</h3>`);
    if (views.create) {
      lines.push(`                      <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">Get started by creating a new ${displayName.toLowerCase()}.</p>`);
      lines.push(`                      <div class="mt-6">`);
      lines.push(`                        <a routerLink="new" class="exeris-btn exeris-btn-primary">`);
      lines.push(`                          <svg class="-ml-0.5 mr-1.5 h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path d="M10 3a1 1 0 011 1v5h5a1 1 0 110 2h-5v5a1 1 0 11-2 0v-5H4a1 1 0 110-2h5V4a1 1 0 011-1z" /></svg>`);
      lines.push(`                          New ${displayName}`);
      lines.push(`                        </a>`);
      lines.push(`                      </div>`);
    }
    lines.push(`                    </td>`);
    lines.push(`                  </tr>`);
    lines.push(`                }`);
    lines.push(`              </tbody>`);
    lines.push(`            </table>`);
    lines.push(`          }`);
    lines.push(`        </div>`);
    lines.push(`      } @placeholder {`);
    lines.push(`        <!-- SSR placeholder -->`);
    lines.push(`        <div class="exeris-card h-96 animate-pulse"></div>`);
    lines.push(`      }`);
    lines.push(``);

    // Pagination
    const navButton = 'exeris-btn exeris-btn-secondary';
    lines.push(`      <!-- Pagination -->`);
    lines.push(`      @if (totalElements() > 0) {`);
    lines.push(`        <nav class="flex items-center justify-between border-t border-gray-200 dark:border-gray-700 px-4 py-3 sm:px-0" aria-label="Pagination">`);
    lines.push(`          <div class="hidden sm:flex sm:items-center sm:gap-4">`);
    lines.push(`            <p class="text-sm text-gray-700 dark:text-gray-300">`);
    lines.push(`              Showing <span class="font-medium">{{ rangeStart() }}</span>`);
    lines.push(`              to <span class="font-medium">{{ rangeEnd() }}</span>`);
    lines.push(`              of <span class="font-medium">{{ totalElements() }}</span> results`);
    lines.push(`            </p>`);
    lines.push(`            <label class="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">`);
    lines.push(`              Rows per page`);
    lines.push(`              <select #pageSizeSelect (change)="onPageSizeChange(pageSizeSelect.value)" data-testid="page-size" class="exeris-select w-auto">`);
    for (const size of PAGE_SIZES) {
      lines.push(`                <option value="${size}" [selected]="pageSize() === ${size}">${size}</option>`);
    }
    lines.push(`              </select>`);
    lines.push(`            </label>`);
    lines.push(`          </div>`);
    lines.push(`          <div class="flex flex-1 justify-between sm:justify-end gap-3">`);
    lines.push(`            <button`);
    lines.push(`              type="button"`);
    lines.push(`              (click)="onPageChange(page() - 1)"`);
    lines.push(`              [disabled]="isFirst()"`);
    lines.push(`              data-testid="pagination-prev"`);
    lines.push(`              class="${navButton}"`);
    lines.push(`            >`);
    lines.push(`              Previous`);
    lines.push(`            </button>`);
    lines.push(`            <button`);
    lines.push(`              type="button"`);
    lines.push(`              (click)="onPageChange(page() + 1)"`);
    lines.push(`              [disabled]="isLast()"`);
    lines.push(`              data-testid="pagination-next"`);
    lines.push(`              class="${navButton}"`);
    lines.push(`            >`);
    lines.push(`              Next`);
    lines.push(`            </button>`);
    lines.push(`          </div>`);
    lines.push(`        </nav>`);
    lines.push(`      }`);
    lines.push(`    </div>`);
    lines.push(`  \`,`);
    lines.push(`})`);

    // Class
    lines.push(`export class ${entityName}ListComponent {`);
    lines.push(`  private readonly service = inject(${entityName}Service);`);
    lines.push(``);
    lines.push(`  readonly deleteError = signal<string | null>(null);`);
    if (actions.length > 0) {
      lines.push(`  readonly actionError = signal<string | null>(null);`);
    }
    lines.push(``);
    lines.push(`  /** The requested page, zero-based. */`);
    lines.push(`  readonly currentPage = signal(0);`);
    lines.push(`  readonly pageSize = signal(${DEFAULT_PAGE_SIZE});`);
    if (sortable) {
      lines.push(`  /** The sorted property; \`null\` leaves the rows in id order. */`);
      lines.push(`  readonly sortField = signal<${modelName}SortField | null>(null);`);
      lines.push(`  readonly sortDirection = signal<'asc' | 'desc'>('asc');`);
    }
    for (const filter of filterControls) {
      lines.push(`  readonly ${filterSignalName(filter.name)} = signal('');`);
    }
    lines.push(``);
    if (hasFilters) {
      lines.push(`  /** The filters the route applies: each control that holds a value. */`);
      lines.push(`  private readonly filter = computed<${modelName}Filter>(() => ({`);
      for (const filter of filterControls) {
        lines.push(`    ${filter.name}: ${this.filterValue(filter, modelName)},`);
      }
      lines.push(`  }));`);
      lines.push(`  readonly filtersActive = computed(() => Object.values(this.filter()).some((value) => value !== undefined));`);
      lines.push(``);
    }
    lines.push(`  /** One page from the list route, read again whenever the page, size, sort or a filter changes. */`);
    lines.push(`  private readonly pageResource = rxResource({`);
    if (sortable) {
      lines.push(`    params: () => {`);
      lines.push(`      const sort = this.sortField();`);
      lines.push(`      return {`);
      lines.push(`        request: {`);
      lines.push(`          page: this.currentPage(),`);
      lines.push(`          size: this.pageSize(),`);
      lines.push(`          ...(sort !== null ? { sort, direction: this.sortDirection() } : {}),`);
      lines.push(`        },`);
      lines.push(`        filter: ${hasFilters ? 'this.filter()' : '{}'},`);
      lines.push(`      };`);
      lines.push(`    },`);
    } else {
      lines.push(`    params: () => ({`);
      lines.push(`      request: { page: this.currentPage(), size: this.pageSize() },`);
      lines.push(`      filter: ${hasFilters ? 'this.filter()' : '{}'},`);
      lines.push(`    }),`);
    }
    lines.push(`    stream: ({ params }) => this.service.findAll(params.request, params.filter),`);
    lines.push(`  });`);
    lines.push(``);
    lines.push(`  /** The page the route last answered, held while the next one loads. */`);
    lines.push(`  private readonly result = linkedSignal<Page<${modelName}> | undefined, Page<${modelName}> | undefined>({`);
    lines.push(`    source: () => (this.pageResource.hasValue() ? this.pageResource.value() : undefined),`);
    lines.push(`    computation: (value, previous) => value ?? previous?.value,`);
    lines.push(`  });`);
    lines.push(``);
    lines.push(`  readonly items = computed(() => this.result()?.content ?? []);`);
    lines.push(`  readonly totalElements = computed(() => this.result()?.totalElements ?? 0);`);
    lines.push(`  readonly totalPages = computed(() => this.result()?.totalPages ?? 0);`);
    lines.push(`  /** The page shown, zero-based, as the route numbered it. */`);
    lines.push(`  readonly page = computed(() => this.result()?.number ?? this.currentPage());`);
    lines.push(`  readonly isFirst = computed(() => this.result()?.first ?? true);`);
    lines.push(`  readonly isLast = computed(() => this.result()?.last ?? true);`);
    lines.push(`  /** The one-based positions of the first and last row shown, among every row the filters match. */`);
    lines.push(`  readonly rangeStart = computed(() => {`);
    lines.push(`    const result = this.result();`);
    lines.push(`    return result && result.content.length > 0 ? result.number * result.size + 1 : 0;`);
    lines.push(`  });`);
    lines.push(`  readonly rangeEnd = computed(() => {`);
    lines.push(`    const result = this.result();`);
    lines.push(`    return result ? result.number * result.size + result.content.length : 0;`);
    lines.push(`  });`);
    lines.push(`  readonly isLoading = computed(() => this.pageResource.isLoading());`);
    lines.push(`  readonly error = computed(() => {`);
    lines.push(`    const err = this.pageResource.error();`);
    lines.push(`    return err ? httpErrorMessage(err, { entity: '${tsSingleQuoted(pluralName.toLowerCase())}', action: 'load' }) : null;`);
    lines.push(`  });`);
    for (const field of badgeFields) {
      const enumType = field.list.enumType!;
      lines.push(``);
      lines.push(`  protected readonly ${field.name}Badges: Readonly<Record<string, EnumBadge>> = {`);
      for (const { value, tone } of field.list.enumValues ?? []) {
        lines.push(`    ${value}: { label: ${enumType}DisplayNames.${value}, className: '${BADGE_BASE} ${BADGE_TONE_CLASSES[tone]}' },`);
      }
      lines.push(`  };`);
    }
    lines.push(``);
    lines.push(`  loadData(): void {`);
    lines.push(`    this.pageResource.reload();`);
    lines.push(`  }`);
    lines.push(``);
    if (hasFilters) {
      lines.push(`  setFilter(target: WritableSignal<string>, value: string): void {`);
      lines.push(`    target.set(value.trim());`);
      lines.push(`    this.currentPage.set(0);`);
      lines.push(`  }`);
      lines.push(``);
    }
    if (sortable) {
      lines.push(`  onSort(field: ${modelName}SortField): void {`);
      lines.push(`    if (this.sortField() === field) {`);
      lines.push(`      this.sortDirection.update((d) => (d === 'asc' ? 'desc' : 'asc'));`);
      lines.push(`    } else {`);
      lines.push(`      this.sortField.set(field);`);
      lines.push(`      this.sortDirection.set('asc');`);
      lines.push(`    }`);
      lines.push(`    this.currentPage.set(0);`);
      lines.push(`  }`);
      lines.push(``);
    }
    lines.push(`  onPageChange(page: number): void {`);
    lines.push(`    if (page >= 0 && page < this.totalPages()) {`);
    lines.push(`      this.currentPage.set(page);`);
    lines.push(`    }`);
    lines.push(`  }`);
    lines.push(``);
    lines.push(`  /** A size outside the route's 1..MAX_PAGE_SIZE is never requested. */`);
    lines.push(`  onPageSizeChange(size: string): void {`);
    lines.push(`    const value = Number(size);`);
    lines.push(`    if (Number.isInteger(value) && value >= 1 && value <= MAX_PAGE_SIZE) {`);
    lines.push(`      this.pageSize.set(value);`);
    lines.push(`      this.currentPage.set(0);`);
    lines.push(`    }`);
    lines.push(`  }`);
    lines.push(``);
    lines.push(`  /** Reloads the page a row left; a later page left with no row gives way to the page before it. */`);
    lines.push(`  private afterRemoval(): void {`);
    lines.push(`    const page = this.page();`);
    lines.push(`    if (this.items().length === 1 && page > 0) {`);
    lines.push(`      this.currentPage.set(page - 1);`);
    lines.push(`    } else {`);
    lines.push(`      this.pageResource.reload();`);
    lines.push(`    }`);
    lines.push(`  }`);
    lines.push(``);
    if (badgeFields.length > 0) {
      lines.push(`  protected badgeOf(badges: Readonly<Record<string, EnumBadge>>, value: unknown): EnumBadge {`);
      lines.push(`    const key = String(value);`);
      lines.push(`    return badges[key] ?? { label: key, className: NEUTRAL_BADGE };`);
      lines.push(`  }`);
      lines.push(``);
    }
    for (const action of actions) {
      const method = DslMapper.toMethodName(action.name);
      lines.push(`  on${capitalize(method)}(item: ${modelName}): void {`);
      lines.push(`    this.actionError.set(null);`);
      lines.push(`    this.service.${method}(String(item.${idField})).subscribe({`);
      lines.push(`      next: () => {`);
      lines.push(`        this.pageResource.reload();`);
      lines.push(`      },`);
      lines.push(`      error: (err) => {`);
      lines.push(`        this.actionError.set(httpErrorMessage(err, { entity: '${tsSingleQuoted(displayName.toLowerCase())}', action: 'save' }));`);
      lines.push(`      },`);
      lines.push(`    });`);
      lines.push(`  }`);
      lines.push(``);
    }
    lines.push(`  onDelete(item: ${modelName}): void {`);
    lines.push(`    if (confirm('Are you sure you want to delete this ${displayName.toLowerCase()}?')) {`);
    lines.push(`      this.deleteError.set(null);`);
    lines.push(`      this.service.delete(String(item.${idField})).subscribe({`);
    lines.push(`        next: () => {`);
    lines.push(`          this.afterRemoval();`);
    lines.push(`        },`);
    lines.push(`        error: (err) => {`);
    lines.push(`          this.deleteError.set(httpErrorMessage(err, { entity: '${tsSingleQuoted(displayName.toLowerCase())}', action: 'delete' }));`);
    lines.push(`        },`);
    lines.push(`      });`);
    lines.push(`    }`);
    lines.push(`  }`);
    lines.push(`}`);

    return lines.join('\n');
  }

  /** The cell markup of one column, by the cell kind the render model resolved. */
  private cell(col: FieldRenderModel, idField: string): string[] {
    const value = `item.${col.name}`;
    const indent = '                      ';
    switch (col.list.cell) {
      case 'link':
        // A foreign key links to the target's detail page; an empty one renders as any other cell.
        return [
          `${indent}@if (${value}) {`,
          `${indent}  <a [routerLink]="['${col.link}', ${value}]" [attr.data-testid]="'link-${col.name}-' + item.${idField}" class="font-mono text-exeris-primary hover:text-exeris-primary-hover hover:underline">{{ ${value} }}</a>`,
          `${indent}} @else {`,
          `${indent}  {{ ${value} }}`,
          `${indent}}`,
        ];
      case 'boolean':
        return [
          `${indent}@if (${value}) {`,
          `${indent}  <span class="exeris-badge exeris-badge-success">Yes</span>`,
          `${indent}} @else {`,
          `${indent}  <span class="${BOOLEAN_FALSE_BADGE}">No</span>`,
          `${indent}}`,
        ];
      case 'enum':
        return [
          `${indent}@if (${value} !== null && ${value} !== undefined) {`,
          `${indent}  @let ${col.name}Badge = badgeOf(${col.name}Badges, ${value});`,
          `${indent}  <span [class]="${col.name}Badge.className">{{ ${col.name}Badge.label }}</span>`,
          `${indent}}`,
        ];
      case 'date':
        return [`${indent}{{ ${value} | date:'mediumDate' }}`];
      case 'datetime':
        return [`${indent}{{ ${value} | date:'medium' }}`];
      case 'number':
        return [`${indent}{{ ${value} | number }}`];
      case 'currency':
        return [`${indent}{{ ${value} | currency }}`];
      case 'percent':
        return [`${indent}{{ ${value} | percent }}`];
      case 'url':
        return [`${indent}<a [href]="${value}" class="text-exeris-primary hover:text-exeris-primary-hover underline">{{ ${value} }}</a>`];
      default:
        return [`${indent}{{ ${value} }}`];
    }
  }

  /**
   * The list's filters: the list route's filters (`filterProperties`), except a hidden field's.
   * Fields come in declaration order, then the foreign keys no field carries in relationship order.
   */
  private filterControls(
    metadata: DomainMetadata,
    renders: readonly FieldRenderModel[],
    kinds: ReadonlyMap<string, ListFilterKind> | undefined,
    enums: readonly EnumMetadata[],
  ): ListFilterControl[] {
    const properties = filterProperties(metadata, (field) => isEnumField(field, enums));
    const controls: ListFilterControl[] = [];
    for (const render of renders) {
      const property = properties.find((p) => p.name === render.name);
      if (!property || render.field.hidden) continue;
      controls.push({ name: property.name, label: render.label, kind: kinds?.get(property.name) ?? 'text', valueKind: property.kind, render });
    }
    for (const rel of metadata.relationships ?? []) {
      const property = properties.find((p) => p.relationship === rel);
      if (!property || renders.some((r) => r.name === property.name)) continue;
      controls.push({ name: property.name, label: DslMapper.humanize(rel.name), kind: kinds?.get(property.name) ?? 'text', valueKind: property.kind });
    }
    return controls;
  }

  /** The value one filter sends, read from its control's text: blank is no filter. */
  private filterValue(filter: ListFilterControl, modelName: string): string {
    const text = `this.${filterSignalName(filter.name)}()`;
    if (filter.kind === 'boolean') return `${text} === '' ? undefined : ${text} === 'true'`;
    if (NUMBER_FILTER_KINDS.has(filter.valueKind)) return `numberFilter(${text})`;
    if (filter.kind === 'enum') return `(${text} || undefined) as ${modelName}Filter['${filter.name}']`;
    return `${text} || undefined`;
  }

  /** The markup of one filter control. Every control writes its signal through `setFilter`. */
  private filterControl(filter: ListFilterControl): string[] {
    const signalName = filterSignalName(filter.name);
    const ref = `${signalName}Control`;
    const label = filter.label;
    const out: string[] = [];
    if (filter.kind === 'boolean' || filter.kind === 'enum') {
      out.push(`        <select`);
      out.push(`          #${ref}`);
      out.push(`          (change)="setFilter(${signalName}, ${ref}.value)"`);
      out.push(`          aria-label="Filter by ${label}"`);
      out.push(`          data-testid="filter-${filter.name}"`);
      out.push(`          class="${FILTER_SELECT_CLASS}"`);
      out.push(`        >`);
      out.push(`          <option value="">All ${label}</option>`);
      if (filter.kind === 'boolean') {
        out.push(`          <option value="true">Yes</option>`);
        out.push(`          <option value="false">No</option>`);
      } else {
        for (const { value } of filter.render?.list.enumValues ?? []) {
          out.push(`          <option value="${value}">{{ ${filter.name}Badges['${value}'].label }}</option>`);
        }
      }
      out.push(`        </select>`);
      return out;
    }
    // A text value is applied when it is committed (Enter or leaving the field): the route matches
    // whole values, so a partly typed one would match nothing.
    out.push(`        <input`);
    out.push(`          #${ref}`);
    if (filter.kind === 'date') {
      out.push(`          type="date"`);
    } else if (filter.kind === 'number') {
      out.push(`          type="text"`);
      out.push(`          inputmode="${filter.valueKind === 'long' || filter.valueKind === 'int' ? 'numeric' : 'decimal'}"`);
    } else {
      out.push(`          type="text"`);
    }
    out.push(`          (change)="setFilter(${signalName}, ${ref}.value)"`);
    if (filter.kind !== 'date') out.push(`          placeholder="Filter by ${label}"`);
    out.push(`          aria-label="Filter by ${label}"`);
    out.push(`          data-testid="filter-${filter.name}"`);
    out.push(`          class="${FILTER_INPUT_CLASS}"`);
    out.push(`        />`);
    return out;
  }

  /**
   * The first five non-hidden, non-system fields, in declaration order. No metadata selects list
   * columns: field-level `@UI(displayInList)` would, and the processor does not extract it.
   */
  private listColumnNames(renders: readonly FieldRenderModel[]): string[] {
    return renders
      .filter((r) => r.displayed)
      .slice(0, 5)
      .map((f) => f.name);
  }
}

/** `status` → `filterStatus`: the signal holding one filter control's text. */
function filterSignalName(name: string): string {
  return `filter${capitalize(name)}`;
}

export function generateList(
  metadata: DomainMetadata,
  config: GeneratorConfig,
  allDomains: DomainMetadata[] = [metadata],
  enums: EnumMetadata[] = [],
): GeneratedFile | null {
  const generator = new ListGenerator();
  const context: GeneratorContext = { config, backend: config.backend ?? 'KERNEL', allDomains, enums };
  return generator.generate(metadata, context);
}
