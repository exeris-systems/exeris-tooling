/**
 * Angular List Generator
 *
 * Generates one standalone, OnPush, signal-driven list component per entity.
 *
 * The generated server's list route answers with the entity's whole collection as a JSON array and
 * reads no query parameter: it neither pages, sorts, searches nor filters. The component therefore
 * loads the collection once and does all four in the browser, over every row the server returned,
 * through `computed` signals. Nothing it offers depends on a parameter the server ignores.
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
  resolveFieldRenders,
  toTitleCase,
  type BadgeTone,
  type FieldRenderModel,
} from './field-render.js';
import { tsSingleQuoted } from './ts-literal.js';
import { entityViews } from './entity-views.js';

export { GeneratedFile };

/** The page sizes the selector offers; the default is one of them. */
const PAGE_SIZES = [10, 20, 25, 50] as const;
const DEFAULT_PAGE_SIZE = 20;

const BADGE_BASE = 'inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ring-1 ring-inset';

/** Utility classes per badge tone. Full literals, so Tailwind's source scan finds every one. */
const BADGE_TONE_CLASSES: Readonly<Record<BadgeTone, string>> = {
  blue: 'bg-blue-50 text-blue-700 ring-blue-600/20 dark:bg-blue-900/20 dark:text-blue-300',
  violet: 'bg-violet-50 text-violet-700 ring-violet-600/20 dark:bg-violet-900/20 dark:text-violet-300',
  teal: 'bg-teal-50 text-teal-700 ring-teal-600/20 dark:bg-teal-900/20 dark:text-teal-300',
  amber: 'bg-amber-50 text-amber-800 ring-amber-600/20 dark:bg-amber-900/20 dark:text-amber-300',
  pink: 'bg-pink-50 text-pink-700 ring-pink-600/20 dark:bg-pink-900/20 dark:text-pink-300',
  slate: 'bg-slate-50 text-slate-700 ring-slate-500/20 dark:bg-slate-800 dark:text-slate-300',
};

const FILTER_SELECT_CLASS = 'rounded-md border-0 py-2 pl-3 pr-10 text-gray-900 ring-1 ring-inset ring-gray-300 focus:ring-2 focus:ring-exeris-primary dark:bg-gray-800 dark:text-white dark:ring-gray-600 sm:text-sm';
const FILTER_INPUT_CLASS = 'rounded-md border-0 py-2 px-3 text-gray-900 ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-exeris-primary dark:bg-gray-800 dark:text-white dark:ring-gray-600 sm:text-sm';

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

    const renders = resolveFieldRenders(
      metadata,
      fieldRenderContext(metadata, context.allDomains, context.config.generateDetails !== false, context.enums ?? []),
    );
    const listColumns = this.listColumnNames(renders)
      .map((name) => renders.find((r) => r.name === name)!);

    const views = entityViews(metadata);
    // With the entity's filter switch off no field gets a control, whatever the field says.
    const filterControls = views.filter ? renders.filter((r) => r.list.filter !== undefined) : [];
    // Search matches the fields marked searchable, else every column the list shows.
    const searchFields = views.search
      ? (renders.some((r) => r.field.searchable) ? renders.filter((r) => r.field.searchable) : listColumns)
      : [];
    const sortable = listColumns.some((c) => c.list.sortable);
    const enumColumns = listColumns.filter((c) => c.list.cell === 'enum');
    const enumFilters = filterControls.filter((r) => r.list.filter === 'enum');
    const enumTypes = [...new Set([...enumColumns, ...enumFilters].map((r) => r.list.enumType!))].sort();
    const badgeFields = [...new Map([...enumColumns, ...enumFilters].map((r) => [r.name, r])).values()];
    const actions = rowActions(metadata);
    const hasControls = views.search || filterControls.length > 0;

    const pipes = new Set<string>();
    for (const col of listColumns) {
      if (col.list.cell === 'date' || col.list.cell === 'datetime') pipes.add('DatePipe');
      if (col.list.cell === 'number') pipes.add('DecimalPipe');
      if (col.list.cell === 'currency') pipes.add('CurrencyPipe');
      if (col.list.cell === 'percent') pipes.add('PercentPipe');
    }
    const pipeImports = [...pipes].sort();

    const helpers = new Set<string>();
    if (searchFields.length > 0 || filterControls.some((r) => r.list.filter === 'text')) helpers.add('textOf');
    if (filterControls.some((r) => r.list.filter === 'date-range')) helpers.add('inDayRange');
    if (filterControls.some((r) => r.list.filter === 'number-range')) helpers.add('inNumberRange');
    if (sortable) helpers.add('compareValues');

    const lines: string[] = [];

    // Header
    lines.push(`/**`);
    lines.push(` * ${entityName} List Component`);
    lines.push(` * Generated by @exeris/codegen-ts`);
    lines.push(` *`);
    lines.push(` * The server's list route returns every ${displayName.toLowerCase()} at once and reads no query`);
    lines.push(` * parameter, so paging, sorting, search and filters run here, over every loaded row.`);
    lines.push(` *`);
    lines.push(` * DO NOT EDIT - This file is auto-generated`);
    lines.push(` */`);
    lines.push(``);

    // Imports
    lines.push(`import {`);
    lines.push(`  Component,`);
    lines.push(`  ChangeDetectionStrategy,`);
    lines.push(`  inject,`);
    lines.push(`  signal,`);
    lines.push(`  computed,`);
    lines.push(`  OnInit,`);
    if (filterControls.length > 0) {
      lines.push(`  WritableSignal,`);
    }
    lines.push(`} from '@angular/core';`);
    if (pipeImports.length > 0) {
      lines.push(`import { ${pipeImports.join(', ')} } from '@angular/common';`);
    }
    lines.push(`import { RouterModule } from '@angular/router';`);
    lines.push(`import { ${modelName}, ${entityName}Service, Page } from '../services/${kebabName}.service';`);
    if (enumTypes.length > 0) {
      lines.push(`import { ${enumTypes.map((e) => `${e}DisplayNames`).join(', ')} } from '../types/enums';`);
    }
    lines.push(`import { httpErrorMessage } from '../core/http-error';`);
    lines.push(``);

    // Module-level helpers
    lines.push(`/**`);
    lines.push(` * The rows of a list response. The server answers the list route with a JSON array; a paged`);
    lines.push(` * envelope is read through its content.`);
    lines.push(` */`);
    lines.push(`function listRows<T>(result: Page<T> | T[]): T[] {`);
    lines.push(`  return Array.isArray(result) ? result : (result.content ?? []);`);
    lines.push(`}`);
    lines.push(``);
    if (helpers.has('textOf')) {
      lines.push(`/** A value as lower-case text for a contains match; absent values are empty. */`);
      lines.push(`function textOf(value: unknown): string {`);
      lines.push(`  return value === null || value === undefined ? '' : String(value).toLowerCase();`);
      lines.push(`}`);
      lines.push(``);
    }
    if (helpers.has('inDayRange')) {
      lines.push(`/**`);
      lines.push(` * Whether an ISO date or date-time falls within an inclusive range of calendar days, compared`);
      lines.push(` * on its leading \`yyyy-MM-dd\`. An empty bound is open; with a bound set, an absent value is out.`);
      lines.push(` */`);
      lines.push(`function inDayRange(value: unknown, from: string, to: string): boolean {`);
      lines.push(`  if (from === '' && to === '') return true;`);
      lines.push(`  if (value === null || value === undefined || value === '') return false;`);
      lines.push(`  const day = String(value).slice(0, 10);`);
      lines.push(`  return (from === '' || day >= from) && (to === '' || day <= to);`);
      lines.push(`}`);
      lines.push(``);
    }
    if (helpers.has('inNumberRange')) {
      lines.push(`/** Whether a number lies within an inclusive range. An empty bound is open; with a bound set, an absent value is out. */`);
      lines.push(`function inNumberRange(value: unknown, min: string, max: string): boolean {`);
      lines.push(`  if (min === '' && max === '') return true;`);
      lines.push(`  if (value === null || value === undefined || value === '') return false;`);
      lines.push(`  const n = Number(value);`);
      lines.push(`  return (min === '' || n >= Number(min)) && (max === '' || n <= Number(max));`);
      lines.push(`}`);
      lines.push(``);
    }
    if (helpers.has('compareValues')) {
      lines.push(`/**`);
      lines.push(` * Orders two cell values: absent values last in either direction, numbers by value, everything`);
      lines.push(` * else as text with numeric collation, so ISO dates and decimal strings order correctly.`);
      lines.push(` */`);
      lines.push(`function compareValues(a: unknown, b: unknown, direction: 1 | -1): number {`);
      lines.push(`  const aAbsent = a === null || a === undefined || a === '';`);
      lines.push(`  const bAbsent = b === null || b === undefined || b === '';`);
      lines.push(`  if (aAbsent || bAbsent) return aAbsent === bAbsent ? 0 : aAbsent ? 1 : -1;`);
      lines.push(`  if (typeof a === 'number' && typeof b === 'number') return direction * (a - b);`);
      lines.push(`  return direction * String(a).localeCompare(String(b), undefined, { numeric: true });`);
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
    lines.push(`            } @else if (rows().length > 0) {`);
    lines.push(`              No ${pluralName.toLowerCase()} match`);
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
      lines.push(`            class="inline-flex items-center rounded-md bg-exeris-primary px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-exeris-primary-hover transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-exeris-primary"`);
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
    lines.push(`        <div role="alert" data-testid="delete-error" class="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300">{{ deleteError() }}</div>`);
    lines.push(`      }`);
    if (actions.length > 0) {
      lines.push(`      @if (actionError()) {`);
      lines.push(`        <div role="alert" data-testid="action-error" class="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300">{{ actionError() }}</div>`);
      lines.push(`      }`);
    }
    lines.push(``);

    // Search & Filters — the row exists when it holds a control.
    if (hasControls) {
      lines.push(`      <!-- Search & Filters -->`);
      lines.push(`      <div class="flex flex-col gap-4 sm:flex-row sm:flex-wrap sm:items-center">`);
      if (views.search) {
        lines.push(`        <div class="relative flex-1">`);
        lines.push(`          <div class="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3">`);
        lines.push(`            <svg class="h-5 w-5 text-gray-400" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">`);
        lines.push(`              <path fill-rule="evenodd" d="M9 3.5a5.5 5.5 0 100 11 5.5 5.5 0 000-11zM2 9a7 7 0 1112.452 4.391l3.328 3.329a.75.75 0 11-1.06 1.06l-3.329-3.328A7 7 0 012 9z" clip-rule="evenodd" />`);
        lines.push(`            </svg>`);
        lines.push(`          </div>`);
        lines.push(`          <input`);
        lines.push(`            #searchBox`);
        lines.push(`            type="search"`);
        lines.push(`            (input)="onSearch(searchBox.value)"`);
        lines.push(`            placeholder="Search ${pluralName.toLowerCase()}..."`);
        lines.push(`            aria-label="Search ${pluralName.toLowerCase()}"`);
        lines.push(`            data-testid="search-input"`);
        lines.push(`            class="block w-full rounded-md border-0 py-2 pl-10 pr-3 text-gray-900 ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-exeris-primary dark:bg-gray-800 dark:text-white dark:ring-gray-600 sm:text-sm sm:leading-6"`);
        lines.push(`          />`);
        lines.push(`        </div>`);
      }
      for (const field of filterControls) {
        lines.push(...this.filterControl(field));
      }
      lines.push(`      </div>`);
      lines.push(``);
    }

    // Table with @defer for SSR
    lines.push(`      <!-- Data Table -->`);
    lines.push(`      @defer (on viewport; prefetch on idle) {`);
    lines.push(`        <div class="overflow-hidden rounded-lg border border-gray-200 shadow-sm dark:border-gray-700">`);
    lines.push(`          @if (isLoading() && items().length === 0) {`);
    lines.push(`            <!-- Loading skeleton -->`);
    lines.push(`            <div class="animate-pulse">`);
    lines.push(`              <div class="h-12 bg-gray-100 dark:bg-gray-800"></div>`);
    lines.push(`              @for (i of [1,2,3,4,5]; track i) {`);
    lines.push(`                <div class="h-16 border-t border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900">`);
    lines.push(`                  <div class="flex items-center gap-4 p-4">`);
    lines.push(`                    <div class="h-4 w-32 rounded bg-gray-200 dark:bg-gray-700"></div>`);
    lines.push(`                    <div class="h-4 w-24 rounded bg-gray-200 dark:bg-gray-700"></div>`);
    lines.push(`                    <div class="h-4 w-16 rounded bg-gray-200 dark:bg-gray-700"></div>`);
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
    lines.push(`              <button type="button" (click)="loadData()" class="mt-4 text-sm font-medium text-exeris-primary hover:text-exeris-primary-hover">Try again</button>`);
    lines.push(`            </div>`);
    lines.push(`          } @else {`);
    lines.push(`            <table class="min-w-full divide-y divide-gray-200 dark:divide-gray-700" aria-label="${pluralName} table" data-testid="data-table">`);
    lines.push(`              <thead class="bg-gray-50 dark:bg-gray-800">`);
    lines.push(`                <tr>`);

    // Column headers; a sortable one holds a button, so the sort is reachable by keyboard.
    for (const col of listColumns) {
      const align = col.list.align === 'right' ? 'text-right' : 'text-left';
      lines.push(`                  <th`);
      lines.push(`                    scope="col"`);
      lines.push(`                    class="px-6 py-3.5 ${align} text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400"`);
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
    lines.push(`                  <th scope="col" class="relative py-3.5 pl-3 pr-6">`);
    lines.push(`                    <span class="sr-only">Actions</span>`);
    lines.push(`                  </th>`);
    lines.push(`                </tr>`);
    lines.push(`              </thead>`);
    lines.push(`              <tbody class="divide-y divide-gray-200 bg-white dark:divide-gray-700 dark:bg-gray-900">`);
    lines.push(`                @for (item of items(); track item.${idField}; let i = $index) {`);
    lines.push(`                  <tr animate.enter="row-enter" [style.animation-delay.ms]="i * 50" class="hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors" [attr.data-testid]="'row-' + item.${idField}">`);

    // Data cells
    for (const col of listColumns) {
      const align = col.list.align === 'right' ? ' text-right tabular-nums' : '';
      lines.push(`                    <td class="whitespace-nowrap px-6 py-4 text-sm text-gray-900 dark:text-gray-100${align}">`);
      lines.push(...this.cell(col, idField));
      lines.push(`                    </td>`);
    }

    // Actions
    lines.push(`                    <td class="whitespace-nowrap py-4 pl-3 pr-6 text-right text-sm">`);
    lines.push(`                      <div class="flex justify-end gap-3">`);
    // Each row link exists only beside the route it opens; Delete is an API call and always stays.
    if (views.detail) {
      lines.push(`                        <a [routerLink]="[item.${idField}]" [attr.data-testid]="'action-view-' + item.${idField}" class="text-exeris-primary hover:text-exeris-primary-hover dark:text-exeris-primary dark:hover:text-exeris-primary-hover font-medium">View</a>`);
    }
    if (views.edit) {
      lines.push(`                        <a [routerLink]="[item.${idField}, 'edit']" [attr.data-testid]="'action-edit-' + item.${idField}" class="text-exeris-primary hover:text-exeris-primary-hover dark:text-exeris-primary dark:hover:text-exeris-primary-hover font-medium">Edit</a>`);
    }
    for (const action of actions) {
      const method = DslMapper.toMethodName(action.name);
      const kebab = DslMapper.toKebabCase(action.name);
      lines.push(`                        <button type="button" (click)="on${capitalize(method)}(item)" [attr.data-testid]="'action-${kebab}-' + item.${idField}" class="text-exeris-primary hover:text-exeris-primary-hover dark:text-exeris-primary dark:hover:text-exeris-primary-hover font-medium">${toTitleCase(method)}</button>`);
    }
    lines.push(`                        <button type="button" (click)="onDelete(item)" [attr.data-testid]="'action-delete-' + item.${idField}" class="text-red-600 hover:text-red-900 dark:text-red-400 dark:hover:text-red-300 font-medium">Delete</button>`);
    lines.push(`                      </div>`);
    lines.push(`                    </td>`);
    lines.push(`                  </tr>`);
    lines.push(`                } @empty {`);
    lines.push(`                  <tr>`);
    lines.push(`                    <td colspan="${listColumns.length + 1}" class="px-6 py-12 text-center">`);
    lines.push(`                      <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">`);
    lines.push(`                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />`);
    lines.push(`                      </svg>`);
    lines.push(`                      <h3 class="mt-2 text-sm font-medium text-gray-900 dark:text-white">No ${pluralName.toLowerCase()}</h3>`);
    if (views.create) {
      lines.push(`                      <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">Get started by creating a new ${displayName.toLowerCase()}.</p>`);
      lines.push(`                      <div class="mt-6">`);
      lines.push(`                        <a routerLink="new" class="inline-flex items-center rounded-md bg-exeris-primary px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-exeris-primary-hover">`);
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
    lines.push(`        <div class="h-96 rounded-lg border border-gray-200 dark:border-gray-700 animate-pulse bg-gray-100 dark:bg-gray-800"></div>`);
    lines.push(`      }`);
    lines.push(``);

    // Pagination
    const navButton = 'relative inline-flex items-center rounded-md bg-white dark:bg-gray-800 px-3 py-2 text-sm font-semibold text-gray-900 dark:text-gray-100 ring-1 ring-inset ring-gray-300 dark:ring-gray-600 hover:bg-gray-50 dark:hover:bg-gray-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors';
    lines.push(`      <!-- Pagination -->`);
    lines.push(`      @if (totalElements() > 0) {`);
    lines.push(`        <nav class="flex items-center justify-between border-t border-gray-200 dark:border-gray-700 px-4 py-3 sm:px-0" aria-label="Pagination">`);
    lines.push(`          <div class="hidden sm:flex sm:items-center sm:gap-4">`);
    lines.push(`            <p class="text-sm text-gray-700 dark:text-gray-300">`);
    lines.push(`              Showing <span class="font-medium">{{ (page() * pageSize()) + 1 }}</span>`);
    lines.push(`              to <span class="font-medium">{{ Math.min((page() + 1) * pageSize(), totalElements()) }}</span>`);
    lines.push(`              of <span class="font-medium">{{ totalElements() }}</span> results`);
    lines.push(`            </p>`);
    lines.push(`            <label class="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">`);
    lines.push(`              Rows per page`);
    lines.push(`              <select #pageSizeSelect (change)="onPageSizeChange(pageSizeSelect.value)" data-testid="page-size" class="${FILTER_SELECT_CLASS}">`);
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
    lines.push(`              [disabled]="page() === 0"`);
    lines.push(`              data-testid="pagination-prev"`);
    lines.push(`              class="${navButton}"`);
    lines.push(`            >`);
    lines.push(`              Previous`);
    lines.push(`            </button>`);
    lines.push(`            <button`);
    lines.push(`              type="button"`);
    lines.push(`              (click)="onPageChange(page() + 1)"`);
    lines.push(`              [disabled]="page() >= totalPages() - 1"`);
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
    lines.push(`export class ${entityName}ListComponent implements OnInit {`);
    lines.push(`  private readonly service = inject(${entityName}Service);`);
    lines.push(``);
    lines.push(`  /** Every row the server returned. */`);
    lines.push(`  readonly rows = signal<${modelName}[]>([]);`);
    lines.push(`  readonly isLoading = signal(false);`);
    lines.push(`  readonly error = signal<string | null>(null);`);
    lines.push(`  readonly deleteError = signal<string | null>(null);`);
    if (actions.length > 0) {
      lines.push(`  readonly actionError = signal<string | null>(null);`);
    }
    lines.push(``);
    if (views.search) {
      lines.push(`  readonly searchQuery = signal('');`);
    }
    for (const field of filterControls) {
      for (const name of this.filterSignalNames(field)) {
        lines.push(`  readonly ${name} = signal('');`);
      }
    }
    if (sortable) {
      lines.push(`  readonly sortField = signal<keyof ${modelName} | null>(null);`);
      lines.push(`  readonly sortDirection = signal<'asc' | 'desc'>('asc');`);
    }
    lines.push(`  readonly pageSize = signal(${DEFAULT_PAGE_SIZE});`);
    lines.push(`  readonly currentPage = signal(0);`);
    lines.push(``);

    // Filtering
    const predicates = this.predicates(searchFields, filterControls);
    lines.push(`  /** The rows that pass the search and every filter. */`);
    if (predicates.reads.length === 0) {
      lines.push(`  readonly filtered = computed(() => this.rows());`);
    } else {
      lines.push(`  readonly filtered = computed(() => {`);
      for (const read of predicates.reads) {
        lines.push(`    ${read}`);
      }
      lines.push(`    return this.rows().filter((item) =>`);
      predicates.tests.forEach((test, index) => {
        lines.push(`      ${index === 0 ? '' : '&& '}${test}`);
      });
      lines.push(`    );`);
      lines.push(`  });`);
    }
    lines.push(``);
    if (sortable) {
      lines.push(`  /** The filtered rows in the order of the sorted column; unsorted, the server's order. */`);
      lines.push(`  readonly sorted = computed(() => {`);
      lines.push(`    const rows = this.filtered();`);
      lines.push(`    const field = this.sortField();`);
      lines.push(`    if (field === null) return rows;`);
      lines.push(`    const direction: 1 | -1 = this.sortDirection() === 'asc' ? 1 : -1;`);
      lines.push(`    return [...rows].sort((a, b) => compareValues(a[field], b[field], direction));`);
      lines.push(`  });`);
      lines.push(``);
    }
    const ordered = sortable ? 'sorted' : 'filtered';
    lines.push(`  readonly totalElements = computed(() => this.filtered().length);`);
    lines.push(`  readonly totalPages = computed(() => Math.ceil(this.totalElements() / this.pageSize()));`);
    lines.push(`  /** The page shown: the requested one, held within the pages the filtered rows fill. */`);
    lines.push(`  readonly page = computed(() => Math.min(this.currentPage(), Math.max(this.totalPages() - 1, 0)));`);
    lines.push(`  readonly items = computed(() => {`);
    lines.push(`    const start = this.page() * this.pageSize();`);
    lines.push(`    return this.${ordered}().slice(start, start + this.pageSize());`);
    lines.push(`  });`);
    lines.push(``);
    lines.push(`  protected readonly Math = Math;`);
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
    lines.push(`  ngOnInit(): void {`);
    lines.push(`    this.loadData();`);
    lines.push(`  }`);
    lines.push(``);
    lines.push(`  loadData(): void {`);
    lines.push(`    this.isLoading.set(true);`);
    lines.push(`    this.error.set(null);`);
    lines.push(`    this.service.findAll().subscribe({`);
    lines.push(`      next: (result) => {`);
    lines.push(`        this.rows.set(listRows(result));`);
    lines.push(`        this.isLoading.set(false);`);
    lines.push(`      },`);
    lines.push(`      error: (err) => {`);
    lines.push(`        this.error.set(httpErrorMessage(err, { entity: '${tsSingleQuoted(pluralName.toLowerCase())}', action: 'load' }));`);
    lines.push(`        this.isLoading.set(false);`);
    lines.push(`      },`);
    lines.push(`    });`);
    lines.push(`  }`);
    lines.push(``);
    if (views.search) {
      lines.push(`  onSearch(query: string): void {`);
      lines.push(`    this.searchQuery.set(query);`);
      lines.push(`    this.currentPage.set(0);`);
      lines.push(`  }`);
      lines.push(``);
    }
    if (filterControls.length > 0) {
      lines.push(`  setFilter(target: WritableSignal<string>, value: string): void {`);
      lines.push(`    target.set(value);`);
      lines.push(`    this.currentPage.set(0);`);
      lines.push(`  }`);
      lines.push(``);
    }
    if (sortable) {
      lines.push(`  onSort(field: keyof ${modelName}): void {`);
      lines.push(`    if (this.sortField() === field) {`);
      lines.push(`      this.sortDirection.update((d) => (d === 'asc' ? 'desc' : 'asc'));`);
      lines.push(`    } else {`);
      lines.push(`      this.sortField.set(field);`);
      lines.push(`      this.sortDirection.set('asc');`);
      lines.push(`    }`);
      lines.push(`  }`);
      lines.push(``);
    }
    lines.push(`  onPageChange(page: number): void {`);
    lines.push(`    if (page >= 0 && page < this.totalPages()) {`);
    lines.push(`      this.currentPage.set(page);`);
    lines.push(`    }`);
    lines.push(`  }`);
    lines.push(``);
    lines.push(`  onPageSizeChange(size: string): void {`);
    lines.push(`    this.pageSize.set(Number(size));`);
    lines.push(`    this.currentPage.set(0);`);
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
      lines.push(`        this.loadData();`);
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
    lines.push(`          this.loadData();`);
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
          `${indent}  <span class="inline-flex items-center rounded-full bg-green-50 px-2 py-1 text-xs font-medium text-green-700 ring-1 ring-inset ring-green-600/20 dark:bg-green-900/20 dark:text-green-400 dark:ring-green-500/20">Yes</span>`,
          `${indent}} @else {`,
          `${indent}  <span class="inline-flex items-center rounded-full bg-gray-50 px-2 py-1 text-xs font-medium text-gray-600 ring-1 ring-inset ring-gray-500/10 dark:bg-gray-800 dark:text-gray-400">No</span>`,
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
        return [`${indent}<a [href]="${value}" class="text-exeris-primary hover:text-indigo-900 dark:text-indigo-400 dark:hover:text-indigo-300 underline">{{ ${value} }}</a>`];
      default:
        return [`${indent}{{ ${value} }}`];
    }
  }

  /** The signals one filter control writes: one value, or the two bounds of a range. */
  private filterSignalNames(field: FieldRenderModel): string[] {
    const base = `filter${capitalize(field.name)}`;
    switch (field.list.filter) {
      case 'date-range': return [`${base}From`, `${base}To`];
      case 'number-range': return [`${base}Min`, `${base}Max`];
      default: return [base];
    }
  }

  /** The markup of one filter control. Every control writes its signal through `setFilter`. */
  private filterControl(field: FieldRenderModel): string[] {
    const base = `filter${capitalize(field.name)}`;
    const label = field.label;
    const out: string[] = [];
    const input = (signalName: string, ref: string, type: string, testid: string, aria: string, placeholder: string): void => {
      out.push(`        <input`);
      out.push(`          #${ref}`);
      out.push(`          type="${type}"`);
      out.push(`          (input)="setFilter(${signalName}, ${ref}.value)"`);
      if (placeholder) out.push(`          placeholder="${placeholder}"`);
      out.push(`          aria-label="${aria}"`);
      out.push(`          data-testid="${testid}"`);
      out.push(`          class="${FILTER_INPUT_CLASS}"`);
      out.push(`        />`);
    };
    switch (field.list.filter) {
      case 'boolean':
      case 'enum': {
        const ref = `${base}Control`;
        out.push(`        <select`);
        out.push(`          #${ref}`);
        out.push(`          (change)="setFilter(${base}, ${ref}.value)"`);
        out.push(`          aria-label="Filter by ${label}"`);
        out.push(`          data-testid="filter-${field.name}"`);
        out.push(`          class="${FILTER_SELECT_CLASS}"`);
        out.push(`        >`);
        out.push(`          <option value="">All ${label}</option>`);
        if (field.list.filter === 'boolean') {
          out.push(`          <option value="true">Yes</option>`);
          out.push(`          <option value="false">No</option>`);
        } else {
          for (const { value } of field.list.enumValues ?? []) {
            out.push(`          <option value="${value}">{{ ${field.name}Badges['${value}'].label }}</option>`);
          }
        }
        out.push(`        </select>`);
        break;
      }
      case 'date-range':
        input(`${base}From`, `${base}FromControl`, 'date', `filter-${field.name}-from`, `${label} from`, '');
        input(`${base}To`, `${base}ToControl`, 'date', `filter-${field.name}-to`, `${label} to`, '');
        break;
      case 'number-range':
        input(`${base}Min`, `${base}MinControl`, 'number', `filter-${field.name}-min`, `${label} minimum`, `Min ${label}`);
        input(`${base}Max`, `${base}MaxControl`, 'number', `filter-${field.name}-max`, `${label} maximum`, `Max ${label}`);
        break;
      default:
        input(base, `${base}Control`, 'search', `filter-${field.name}`, `Filter by ${label}`, `Filter by ${label}`);
    }
    return out;
  }

  /**
   * The body of the `filtered` computed: the signal reads it starts with, and one test per
   * control, in field declaration order.
   */
  private predicates(
    searchFields: readonly FieldRenderModel[],
    filterControls: readonly FieldRenderModel[],
  ): { reads: string[]; tests: string[] } {
    const reads: string[] = [];
    const tests: string[] = [];
    if (searchFields.length > 0) {
      reads.push(`const query = this.searchQuery().trim().toLowerCase();`);
      const values = searchFields.map((f) => `item.${f.name}`).join(', ');
      tests.push(`(query === '' || [${values}].some((value) => textOf(value).includes(query)))`);
    }
    for (const field of filterControls) {
      const base = `filter${capitalize(field.name)}`;
      const value = `item.${field.name}`;
      switch (field.list.filter) {
        case 'boolean':
          reads.push(`const ${field.name}Filter = this.${base}();`);
          tests.push(`(${field.name}Filter === '' || ${value} === (${field.name}Filter === 'true'))`);
          break;
        case 'enum':
          reads.push(`const ${field.name}Filter = this.${base}();`);
          tests.push(`(${field.name}Filter === '' || String(${value}) === ${field.name}Filter)`);
          break;
        case 'date-range':
          tests.push(`inDayRange(${value}, this.${base}From(), this.${base}To())`);
          break;
        case 'number-range':
          tests.push(`inNumberRange(${value}, this.${base}Min(), this.${base}Max())`);
          break;
        default:
          reads.push(`const ${field.name}Filter = this.${base}().trim().toLowerCase();`);
          tests.push(`(${field.name}Filter === '' || textOf(${value}).includes(${field.name}Filter))`);
      }
    }
    return { reads, tests };
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
