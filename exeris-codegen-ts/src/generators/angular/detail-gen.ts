/**
 * Angular Detail View Generator
 *
 * Generates Angular 22+ detail view components with:
 * - Signal-based state management
 * - rxResource (RxJS-interop Resource) for data fetching
 * - Loading/error states
 * - A "Details" section, a "Related records" section and the system panel; a related record
 *   panel lists the first rows of a child the list route filters by this record's id (ADR-096)
 * - Buttons for the entity's parameterless actions
 * - Full a11y support
 * - i18n-ready strings
 */

import type { DomainMetadata, CodeGenerator, EnumMetadata, GeneratedFile, GeneratorContext } from '../../core/generator-registry.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import { modelTypeName } from '../../models/model-naming.js';
import type { GeneratorConfig } from '../../config.js';
import type { BackendType } from '../../core/backend-strategy.js';
import { declaresPrimaryKey, primaryKeyField } from '../../core/primary-key.js';
import { outPath } from '../../core/paths.js';
import { testIdSegment, tsSingleQuoted, tsTemplateText } from './ts-literal.js';
import { auditFieldNames, updateVersionField, viewSystemFieldNames } from '../api/type-gen.js';
import { fieldRenderContext, isEnumField, resolveFieldRenders, toTitleCase, type FieldRenderModel } from './field-render.js';
import { filterProperties, foreignKeyBase, foreignKeyParam } from './list-query.js';
import type { RelationshipMetadata } from '../../models/domain-model.js';
import { entityExitRoute, entityViews } from './entity-views.js';
import { fileHeaderLines } from '../file-header.js';

function renderDetailSection(params: {
  isCollapsible: boolean;
  collapsed?: boolean;
  testId?: string;
  heading: string;
  description?: string;
  fieldsMember: string;
  mtClass: string;
  renderDlRows: (member: string) => string[];
  ariaLabelledBy?: string;
}): string[] {
  const lines: string[] = [];
  const testIdAttr = params.testId ? ` data-testid="${params.testId}"` : '';
  const ariaAttr = params.ariaLabelledBy ? ` aria-labelledby="${params.ariaLabelledBy}"` : '';
  if (params.isCollapsible) {
    lines.push(`        <details${testIdAttr}${params.collapsed ? '' : ' open'} class="exeris-card overflow-hidden${params.mtClass}">`);
    lines.push(`          <summary class="exeris-card-header text-sm font-medium text-gray-500 dark:text-gray-400 cursor-pointer">${params.heading}</summary>`);
  } else {
    lines.push(`        <section${testIdAttr}${ariaAttr} class="exeris-card overflow-hidden${params.mtClass}">`);
    const idAttr = params.ariaLabelledBy ? ` id="${params.ariaLabelledBy}"` : '';
    lines.push(`          <h2${idAttr} class="exeris-card-header text-sm font-medium text-gray-500 dark:text-gray-400">${params.heading}</h2>`);
  }
  if (params.description) {
    lines.push(`          <p class="px-4 py-2 text-sm text-gray-500 dark:text-gray-400">${tsTemplateText(params.description)}</p>`);
  }
  lines.push(`          <dl class="divide-y divide-gray-200 dark:divide-gray-700">`);
  lines.push(...params.renderDlRows(params.fieldsMember));
  lines.push(`          </dl>`);
  lines.push(params.isCollapsible ? `        </details>` : `        </section>`);
  return lines;
}

export class DetailGenerator implements CodeGenerator {
  readonly name = 'DetailGenerator';
  readonly artifactType = 'DETAIL' as const;
  readonly supportedBackends: BackendType[] = [];
  readonly priority = 20;

  generate(domain: DomainMetadata, context: GeneratorContext): GeneratedFile | null {
    if (!entityViews(domain).detail) {
      return null;
    }

    const kebab = DslMapper.toKebabCase(domain.entityName);
    const content = this.generateDetailContent(domain, context);

    return {
      path: outPath('components', `${kebab}-detail.component.ts`),
      content,
      artifactType: 'DETAIL',
      overwritable: true,
    };
  }

  private generateDetailContent(domain: DomainMetadata, context: GeneratorContext): string {
    const { entityName } = domain;
    const modelName = modelTypeName(entityName);
    const kebab = DslMapper.toKebabCase(entityName);
    const displayName = domain.displayName ?? entityName;
    const noun = tsSingleQuoted(displayName.toLowerCase());
    const idField = primaryKeyField(domain);

    const renders = resolveFieldRenders(
      domain,
      fieldRenderContext(domain, context.allDomains, context.config.generateDetails !== false, context.enums ?? []),
    );
    const displayFields = renders.filter((r) => r.displayed);
    const related = relatedRecords(domain, context);
    const relatedServices = relatedServiceMembers(domain, related);
    const actions = detailActions(domain);
    // Every field's enum is imported, a hidden or system one included, in declaration order.
    const enumTypes = [...new Set(renders.flatMap((r) => (r.detail.enumType ? [r.detail.enumType] : [])))];
    const hasLinks = renders.some((r) => r.link !== undefined);
    const views = entityViews(domain);

    const lines: string[] = [];

    lines.push(...fileHeaderLines({ title: `${entityName} Detail View Component` }));
    lines.push(``);
    lines.push(`import {`);
    lines.push(`  Component,`);
    lines.push(`  ChangeDetectionStrategy,`);
    lines.push(`  inject,`);
    lines.push(`  signal,`);
    lines.push(`  computed,`);
    lines.push(`  input,`);
    lines.push(`} from '@angular/core';`);
    lines.push(`import { rxResource } from '@angular/core/rxjs-interop';`);
    const panelRows = systemPanelRows(domain);
    const stamps = panelRows.filter((row) => row.kind === 'date');
    const undeclaredRows = panelRows.filter((row) => !row.declared);
    const hasDateFields = displayFields.some((f) => f.detail.display === 'date' || f.detail.display === 'datetime');
    const usesDatePipe = stamps.length > 0 || hasDateFields;
    // DatePipe is only imported when something renders a date: Angular reports an unused
    // standalone import against the template.
    lines.push(usesDatePipe
      ? `import { CommonModule, DatePipe } from '@angular/common';`
      : `import { CommonModule } from '@angular/common';`);
    lines.push(`import { RouterModule, Router } from '@angular/router';`);
    lines.push(`import { ${entityName}Service } from '../services/${kebab}.service';`);
    for (const target of [...relatedServices.keys()].filter((t) => t !== entityName)) {
      lines.push(`import { ${target}Service } from '../services/${DslMapper.toKebabCase(target)}.service';`);
    }
    lines.push(`import { httpErrorMessage } from '../core/http-error';`);
    lines.push(`import type { ${modelName} } from '../types/${kebab}.types';`);

    if (enumTypes.length > 0) {
      lines.push(`import { ${enumTypes.flatMap(e => [e, `${e}DisplayNames`]).join(', ')} } from '../types/enums';`);
    }
    lines.push(``);

    lines.push(`interface FieldDisplay {`);
    lines.push(`  name: keyof ${modelName};`);
    lines.push(`  label: string;`);
    lines.push(`  type: 'text' | 'number' | 'boolean' | 'date' | 'datetime' | 'enum';`);
    lines.push(`  enumType?: string;`);
    lines.push(`  dataType?: 'currency' | 'percent' | 'url';`);
    if (hasLinks) {
      lines.push(`  link?: string;`);
    }
    lines.push(`}`);
    lines.push(``);

    const hasTabs = (domain.uiMetadata?.tabs?.length ?? 0) > 0;
    const sortedTabs = hasTabs
      ? domain.uiMetadata!.tabs!.map((tab, index) => ({
          tab,
          index,
          order: tab.order ?? 999,
        }))
          .sort((a, b) => a.order - b.order || a.index - b.index)
          .map(({ tab }) => tab)
      : [];

    const hasGroups = (domain.uiMetadata?.groups?.length ?? 0) > 0;
    const sortedGroups = hasGroups
      ? [...domain.uiMetadata!.groups!].sort((a, b) => (a.order ?? 999) - (b.order ?? 999))
      : [];
    const groupedFieldNames = new Set<string>();
    const groupFieldsMap = new Map<string, FieldRenderModel[]>();
    if (hasGroups) {
      for (const g of sortedGroups) {
        const gFields: FieldRenderModel[] = [];
        for (const fieldName of g.fields) {
          const f = displayFields.find((df) => df.name === fieldName);
          if (f) {
            gFields.push(f);
            groupedFieldNames.add(fieldName);
          }
        }
        groupFieldsMap.set(g.name, gFields);
      }
    }
    const ungroupedFields = hasGroups
      ? displayFields.filter((f) => !groupedFieldNames.has(f.name))
      : [];

    const fieldDisplayEntry = (field: FieldRenderModel): string => {
      const { display, enumType, dataType } = field.detail;
      return `  { name: '${field.name}' as keyof ${modelName}, label: '${tsSingleQuoted(field.label)}', type: '${display}'${enumType ? `, enumType: '${enumType}'` : ''}${dataType ? `, dataType: '${dataType}'` : ''}${field.link !== undefined ? `, link: '${field.link}'` : ''} },`;
    };

    lines.push(`const DISPLAY_FIELDS: FieldDisplay[] = [`);
    for (const field of displayFields) {
      lines.push(fieldDisplayEntry(field));
    }
    lines.push(`];`);
    lines.push(``);

    const tabClassProperties: Array<{ member: string; constName: string }> = [];
    if (hasTabs) {
      const emitConst = (constName: string, member: string, fields: FieldRenderModel[]): void => {
        tabClassProperties.push({ member, constName });
        lines.push(`const ${constName}: FieldDisplay[] = [`);
        for (const field of fields) {
          lines.push(fieldDisplayEntry(field));
        }
        lines.push(`];`);
        lines.push(``);
      };

      for (const t of sortedTabs) {
        const tabName = t.name ?? '';
        const tFields = (t.fields ?? []).map((fn) => displayFields.find((df) => df.name === fn)).filter((f): f is FieldRenderModel => f !== undefined);
        if (hasGroups) {
          for (const g of sortedGroups) {
            const gFields = (g.fields ?? []).filter((fn) => (t.fields ?? []).includes(fn)).map((fn) => displayFields.find((df) => df.name === fn)).filter((f): f is FieldRenderModel => f !== undefined);
            if (gFields.length > 0) {
              const constName = `TAB_${testIdSegment(tabName).replace(/-/g, '_').toUpperCase()}_GROUP_${DslMapper.toKebabCase(g.name).replace(/-/g, '_').toUpperCase()}_FIELDS`;
              const member = `tab${DslMapper.toTypeName(tabName)}Group${DslMapper.toTypeName(g.name)}Fields`;
              emitConst(constName, member, gFields);
            }
          }
          const tabUngrouped = tFields.filter((f) => !sortedGroups.some((g) => g.fields.includes(f.name)));
          if (tabUngrouped.length > 0) {
            const constName = `TAB_${testIdSegment(tabName).replace(/-/g, '_').toUpperCase()}_UNGROUPED_FIELDS`;
            const member = `tab${DslMapper.toTypeName(tabName)}UngroupedFields`;
            emitConst(constName, member, tabUngrouped);
          }
        } else {
          if (tFields.length > 0) {
            const constName = `TAB_${testIdSegment(tabName).replace(/-/g, '_').toUpperCase()}_FIELDS`;
            const member = `tab${DslMapper.toTypeName(tabName)}Fields`;
            emitConst(constName, member, tFields);
          }
        }
      }

      const allTabbedNames = new Set(sortedTabs.flatMap((t) => t.fields ?? []));
      const untabbedDisplayFields = displayFields.filter((f) => !allTabbedNames.has(f.name));
      if (untabbedDisplayFields.length > 0) {
        if (hasGroups) {
          for (const g of sortedGroups) {
            const gUntabbed = (g.fields ?? []).filter((fn) => !allTabbedNames.has(fn)).map((fn) => displayFields.find((df) => df.name === fn)).filter((f): f is FieldRenderModel => f !== undefined);
            if (gUntabbed.length > 0) {
              const constName = `UNTABBED_GROUP_${DslMapper.toKebabCase(g.name).replace(/-/g, '_').toUpperCase()}_FIELDS`;
              const member = `untabbedGroup${DslMapper.toTypeName(g.name)}Fields`;
              emitConst(constName, member, gUntabbed);
            }
          }
          const untabbedUngrouped = untabbedDisplayFields.filter((f) => !sortedGroups.some((g) => g.fields.includes(f.name)));
          if (untabbedUngrouped.length > 0) {
            const constName = `UNTABBED_FIELDS`;
            const member = `untabbedFields`;
            emitConst(constName, member, untabbedUngrouped);
          }
        } else {
          const constName = `UNTABBED_FIELDS`;
          const member = `untabbedFields`;
          emitConst(constName, member, untabbedDisplayFields);
        }
      }
    } else if (hasGroups) {
      for (const g of sortedGroups) {
        const constName = `GROUP_${DslMapper.toKebabCase(g.name).replace(/-/g, '_').toUpperCase()}_FIELDS`;
        lines.push(`const ${constName}: FieldDisplay[] = [`);
        for (const field of groupFieldsMap.get(g.name)!) {
          lines.push(fieldDisplayEntry(field));
        }
        lines.push(`];`);
        lines.push(``);
      }
      if (ungroupedFields.length > 0) {
        lines.push(`const UNGROUPED_FIELDS: FieldDisplay[] = [`);
        for (const field of ungroupedFields) {
          lines.push(fieldDisplayEntry(field));
        }
        lines.push(`];`);
        lines.push(``);
      }
    }

    lines.push(`@Component({`);
    lines.push(`  selector: 'app-${kebab}-detail',`);
    lines.push(`  standalone: true,`);
    lines.push(`  imports: [CommonModule, RouterModule${usesDatePipe ? ', DatePipe' : ''}],`);
    lines.push(`  changeDetection: ChangeDetectionStrategy.OnPush,`);
    lines.push(`  template: \``);
    lines.push(`    <article role="article" [attr.aria-labelledby]="'detail-title'" [attr.aria-busy]="isLoading()" class="max-w-4xl mx-auto">`);
    lines.push(`      @if (isLoading()) {`);
    lines.push(`        <div class="animate-pulse space-y-4" role="status" aria-label="Loading...">`);
    lines.push(`          <div class="h-8 bg-gray-200 dark:bg-gray-700 rounded-sm w-1/3"></div>`);
    lines.push(`          <div class="h-4 bg-gray-200 dark:bg-gray-700 rounded-sm w-1/2"></div>`);
    lines.push(`        </div>`);
    lines.push(`      } @else if (error()) {`);
    lines.push(`        <div role="alert" class="exeris-alert exeris-alert-danger">`);
    lines.push(`          <p>{{ error() }}</p>`);
    lines.push(`          <button (click)="reload()" class="exeris-btn exeris-btn-secondary exeris-btn-sm mt-4">Try again</button>`);
    lines.push(`        </div>`);
    lines.push(`      } @else if (entity()) {`);
    lines.push(`        @if (deleteError()) {`);
    lines.push(`          <div role="alert" data-testid="delete-error" class="exeris-alert exeris-alert-danger mb-6 text-sm">{{ deleteError() }}</div>`);
    lines.push(`        }`);
    if (actions.length > 0) {
      lines.push(`        @if (actionError()) {`);
      lines.push(`          <div role="alert" data-testid="action-error" class="exeris-alert exeris-alert-danger mb-6 text-sm">{{ actionError() }}</div>`);
      lines.push(`        }`);
    }
    lines.push(`        <header class="mb-8 flex items-center justify-between">`);
    lines.push(`          <h1 id="detail-title" class="text-2xl font-bold text-gray-900 dark:text-white">{{ getTitle() }}</h1>`);
    lines.push(`          <nav class="flex gap-3">`);
    for (const action of actions) {
      lines.push(`            <button type="button" (click)="runAction('${action.methodName}')" [disabled]="actionPending()" data-testid="action-${action.kebabName}" class="exeris-btn exeris-btn-secondary">${action.label}</button>`);
    }
    if (views.edit) {
      lines.push(`            <a [routerLink]="['edit']" class="exeris-btn exeris-btn-primary">Edit</a>`);
    }
    lines.push(`            <button (click)="onDelete()" class="exeris-btn exeris-btn-danger">Delete</button>`);
    lines.push(`          </nav>`);
    lines.push(`        </header>`);
    const renderDlRows = (fieldsVar: string): string[] => {
      const rows: string[] = [];
      rows.push(`            @for (field of ${fieldsVar}; track field.name) {`);
      rows.push(`              <div class="px-4 py-4 sm:grid sm:grid-cols-3 sm:gap-4 sm:px-6">`);
      rows.push(`                <dt class="text-sm font-medium text-gray-500 dark:text-gray-400">{{ field.label }}</dt>`);
      rows.push(`                <dd class="mt-1 text-sm text-gray-900 dark:text-white sm:mt-0 sm:col-span-2">`);
      const sw = hasLinks ? '  ' : '';
      if (hasLinks) {
        rows.push(`                  @if (field.link && rawValue(field, entity()) !== null) {`);
        rows.push(`                    <a [routerLink]="[field.link, rawValue(field, entity())]" [attr.data-testid]="'link-' + field.name" class="font-mono text-exeris-primary hover:underline">{{ rawValue(field, entity()) }}</a>`);
        rows.push(`                  } @else {`);
      }
      rows.push(`${sw}                  @switch (field.dataType) {`);
      rows.push(`${sw}                    @case ('currency') { {{ numericValue(field, entity()) | currency }} }`);
      rows.push(`${sw}                    @case ('percent') { {{ numericValue(field, entity()) | percent }} }`);
      rows.push(`${sw}                    @case ('url') { <a [href]="rawValue(field, entity())" class="text-exeris-primary hover:underline">{{ rawValue(field, entity()) }}</a> }`);
      if (hasDateFields) {
        rows.push(`${sw}                    @default {`);
        rows.push(`${sw}                      @switch (field.type) {`);
        rows.push(`${sw}                        @case ('date') { {{ (dateValue(field, entity()) | date:'mediumDate') ?? '—' }} }`);
        rows.push(`${sw}                        @case ('datetime') { {{ (dateValue(field, entity()) | date:'medium') ?? '—' }} }`);
        rows.push(`${sw}                        @default { {{ formatValue(field, entity()) }} }`);
        rows.push(`${sw}                      }`);
        rows.push(`${sw}                    }`);
      } else {
        rows.push(`${sw}                    @default { {{ formatValue(field, entity()) }} }`);
      }
      rows.push(`${sw}                  }`);
      if (hasLinks) {
        rows.push(`                  }`);
      }
      rows.push(`                </dd>`);
      rows.push(`              </div>`);
      rows.push(`            }`);
      return rows;
    };

    if (!hasTabs) {
      if (!hasGroups) {
        lines.push(`        <section aria-labelledby="details-title" class="exeris-card overflow-hidden">`);
        lines.push(`          <h2 id="details-title" class="exeris-card-header text-sm font-medium text-gray-500 dark:text-gray-400">Details</h2>`);
        lines.push(`          <dl class="divide-y divide-gray-200 dark:divide-gray-700">`);
        lines.push(...renderDlRows('displayFields'));
        lines.push(`          </dl>`);
        lines.push(`        </section>`);
      } else {
        let isFirst = true;
        for (const g of sortedGroups) {
          const heading = tsTemplateText(g.label || g.name);
          const member = `group${DslMapper.toTypeName(g.name)}Fields`;
          const isCollapsible = g.collapsible === true;
          const mtClass = isFirst ? '' : ' mt-8';
          isFirst = false;

          if (isCollapsible) {
            lines.push(`        <details data-testid="group-${testIdSegment(g.name)}"${g.collapsed ? '' : ' open'} class="exeris-card overflow-hidden${mtClass}">`);
            lines.push(`          <summary class="exeris-card-header text-sm font-medium text-gray-500 dark:text-gray-400 cursor-pointer">${heading}</summary>`);
          } else {
            lines.push(`        <section data-testid="group-${testIdSegment(g.name)}" class="exeris-card overflow-hidden${mtClass}">`);
            lines.push(`          <h2 class="exeris-card-header text-sm font-medium text-gray-500 dark:text-gray-400">${heading}</h2>`);
          }
          if (g.description) {
            lines.push(`          <p class="px-4 py-2 text-sm text-gray-500 dark:text-gray-400">${tsTemplateText(g.description)}</p>`);
          }
          lines.push(`          <dl class="divide-y divide-gray-200 dark:divide-gray-700">`);
          lines.push(...renderDlRows(member));
          lines.push(`          </dl>`);
          lines.push(isCollapsible ? `        </details>` : `        </section>`);
        }

        if (ungroupedFields.length > 0) {
          const mtClass = isFirst ? '' : ' mt-8';
          lines.push(`        <section aria-labelledby="details-title" class="exeris-card overflow-hidden${mtClass}">`);
          lines.push(`          <h2 id="details-title" class="exeris-card-header text-sm font-medium text-gray-500 dark:text-gray-400">Details</h2>`);
          lines.push(`          <dl class="divide-y divide-gray-200 dark:divide-gray-700">`);
          lines.push(...renderDlRows('ungroupedFields'));
          lines.push(`          </dl>`);
          lines.push(`        </section>`);
        }
      }
    } else {
      lines.push('        <div role="tablist" aria-label="Detail Tabs" class="flex border-b border-gray-200 dark:border-gray-700 mb-6 gap-2">');
      for (const t of sortedTabs) {
        const tabName = t.name ?? '';
        const tabLabel = tsTemplateText(t.label || t.name || '');
        const iconSpan = t.icon ? `<span class="mr-2">${tsTemplateText(t.icon)}</span>` : '';
        lines.push(`          <button type="button" role="tab" id="tab-${testIdSegment(tabName)}" aria-controls="panel-${testIdSegment(tabName)}" [attr.aria-selected]="activeTab() === '${tsSingleQuoted(tabName)}'" (click)="activeTab.set('${tsSingleQuoted(tabName)}')" data-testid="tab-${testIdSegment(tabName)}" class="px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors cursor-pointer" [class]="activeTab() === '${tsSingleQuoted(tabName)}' ? 'border-exeris-primary text-exeris-primary font-semibold' : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200 hover:border-gray-300'">${iconSpan}${tabLabel}</button>`);
      }
      lines.push('        </div>');

      const allTabbedNames = new Set(sortedTabs.flatMap((t) => t.fields ?? []));
      for (const t of sortedTabs) {
        const tabName = t.name ?? '';
        lines.push(`        <div role="tabpanel" id="panel-${testIdSegment(tabName)}" aria-labelledby="tab-${testIdSegment(tabName)}" [hidden]="activeTab() !== '${tsSingleQuoted(tabName)}'" data-testid="tabpanel-${testIdSegment(tabName)}">`);
        let isFirstInTab = true;
        if (!hasGroups) {
          const tFields = (t.fields ?? []).map((fn) => displayFields.find((df) => df.name === fn)).filter((f): f is FieldRenderModel => f !== undefined);
          if (tFields.length > 0) {
            const member = `tab${DslMapper.toTypeName(tabName)}Fields`;
            lines.push(...renderDetailSection({ isCollapsible: false, heading: 'Details', ariaLabelledBy: 'details-title', fieldsMember: member, mtClass: '', renderDlRows }));
          }
        } else {
          for (const g of sortedGroups) {
            const gFields = (g.fields ?? []).filter((fn) => (t.fields ?? []).includes(fn)).map((fn) => displayFields.find((df) => df.name === fn)).filter((f): f is FieldRenderModel => f !== undefined);
            if (gFields.length === 0) continue;
            const heading = tsTemplateText(g.label || g.name);
            const member = `tab${DslMapper.toTypeName(tabName)}Group${DslMapper.toTypeName(g.name)}Fields`;
            const mtClass = isFirstInTab ? '' : ' mt-8';
            isFirstInTab = false;
            lines.push(...renderDetailSection({ isCollapsible: g.collapsible === true, collapsed: g.collapsed, testId: 'group-' + testIdSegment(g.name), heading, description: g.description, fieldsMember: member, mtClass, renderDlRows }));
          }
          const tFields = (t.fields ?? []).map((fn) => displayFields.find((df) => df.name === fn)).filter((f): f is FieldRenderModel => f !== undefined);
          const tabUngrouped = tFields.filter((f) => !sortedGroups.some((g) => g.fields.includes(f.name)));
          if (tabUngrouped.length > 0) {
            const member = `tab${DslMapper.toTypeName(tabName)}UngroupedFields`;
            const mtClass = isFirstInTab ? '' : ' mt-8';
            lines.push(...renderDetailSection({ isCollapsible: false, heading: 'Details', ariaLabelledBy: 'details-title', fieldsMember: member, mtClass, renderDlRows }));
          }
        }
        lines.push('        </div>');
      }

      const untabbedDisplayFields = displayFields.filter((f) => !allTabbedNames.has(f.name));
      if (untabbedDisplayFields.length > 0) {
        if (!hasGroups) {
          lines.push(...renderDetailSection({ isCollapsible: false, heading: 'Details', ariaLabelledBy: 'details-title', fieldsMember: 'untabbedFields', mtClass: ' mt-8', renderDlRows }));
        } else {
          for (const g of sortedGroups) {
            const gUntabbed = (g.fields ?? []).filter((fn) => !allTabbedNames.has(fn)).map((fn) => displayFields.find((df) => df.name === fn)).filter((f): f is FieldRenderModel => f !== undefined);
            if (gUntabbed.length === 0) continue;
            const heading = tsTemplateText(g.label || g.name);
            const member = `untabbedGroup${DslMapper.toTypeName(g.name)}Fields`;
            lines.push(...renderDetailSection({ isCollapsible: g.collapsible === true, collapsed: g.collapsed, testId: 'group-' + testIdSegment(g.name), heading, description: g.description, fieldsMember: member, mtClass: ' mt-8', renderDlRows }));
          }
          const untabbedUngrouped = untabbedDisplayFields.filter((f) => !sortedGroups.some((g) => g.fields.includes(f.name)));
          if (untabbedUngrouped.length > 0) {
            lines.push(...renderDetailSection({ isCollapsible: false, heading: 'Details', ariaLabelledBy: 'details-title', fieldsMember: 'untabbedFields', mtClass: ' mt-8', renderDlRows }));
          }
        }
      }
    }
    if (related.length > 0) {
      lines.push(`        <section aria-labelledby="related-title" class="exeris-card mt-8">`);
      lines.push(`          <h2 id="related-title" class="exeris-card-header text-sm font-medium text-gray-500 dark:text-gray-400">Related records</h2>`);
      lines.push(`          <ul class="exeris-card-body divide-y divide-gray-200 dark:divide-gray-700">`);
      for (const rel of related) {
        lines.push(`            <li class="py-3 text-sm">`);
        lines.push(`              <div class="flex items-center justify-between">`);
        lines.push(`                <span class="font-medium text-gray-900 dark:text-white">${rel.label}</span>`);
        if (rel.listRoute) {
          // The list opens on every row: it filters by what its own controls hold.
          lines.push(`                <a routerLink="${rel.listRoute}" data-testid="related-${rel.testId}" class="text-exeris-primary hover:underline">View all ${rel.targetLabel}</a>`);
        }
        lines.push(`              </div>`);
        if (rel.fetch) {
          const { member, labelField, detailRoute, keyField } = rel.fetch;
          const text = labelField ? `{{ row.${labelField} || row.${keyField} }}` : `{{ row.${keyField} }}`;
          lines.push(`              @if (${member}Resource.error()) {`);
          lines.push(`                <p role="alert" class="mt-2 text-gray-500 dark:text-gray-400">${rel.targetLabel} could not be loaded.</p>`);
          lines.push(`              } @else {`);
          lines.push(`                <ul class="mt-2 space-y-1" data-testid="related-${rel.testId}-rows">`);
          lines.push(`                  @for (row of ${member}(); track row.${keyField}) {`);
          lines.push(`                    <li>`);
          if (detailRoute) {
            lines.push(`                      <a [routerLink]="['${detailRoute}', row.${keyField}]" [attr.data-testid]="'related-${rel.testId}-' + row.${keyField}" class="text-exeris-primary hover:underline">${text}</a>`);
          } else {
            lines.push(`                      <span [attr.data-testid]="'related-${rel.testId}-' + row.${keyField}">${text}</span>`);
          }
          lines.push(`                    </li>`);
          lines.push(`                  } @empty {`);
          lines.push(`                    <li class="text-gray-500 dark:text-gray-400">None</li>`);
          lines.push(`                  }`);
          lines.push(`                </ul>`);
          lines.push(`              }`);
        }
        lines.push(`            </li>`);
      }
      lines.push(`          </ul>`);
      lines.push(`        </section>`);
    }
    lines.push(`        <section aria-labelledby="system-title" class="exeris-card mt-8">`);
    lines.push(`          <h2 id="system-title" class="exeris-card-header text-sm font-medium text-gray-500 dark:text-gray-400">System Information</h2>`);
    lines.push(`          <dl class="exeris-card-body grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">`);
    lines.push(`            <div><dt class="text-gray-400">ID</dt><dd class="font-mono text-gray-600 dark:text-gray-300">{{ entity()?.${idField} }}</dd></div>`);
    // A row the entity interface does not declare is read off systemInfo(), the narrowing cast
    // emitted below, so the template type-checks under strictTemplates.
    for (const row of panelRows) {
      const read = `${row.declared ? 'entity()' : 'systemInfo()'}?.${row.name}`;
      if (row.kind === 'date') {
        lines.push(`            @if (${read}) { <div><dt class="text-gray-400">${row.label}</dt><dd>{{ ${read} | date:'medium' }}</dd></div> }`);
      } else {
        // A version of 0 is a value, so the guard is a null check rather than truthiness.
        lines.push(`            @if (${read} != null) { <div><dt class="text-gray-400">${row.label}</dt><dd class="font-mono text-gray-600 dark:text-gray-300">{{ ${read} }}</dd></div> }`);
      }
    }
    lines.push(`          </dl>`);
    lines.push(`        </section>`);
    lines.push(`      }`);
    lines.push(`    </article>`);
    lines.push(`  \`,`);
    lines.push(`})`);

    lines.push(`export class ${entityName}DetailComponent {`);
    lines.push(`  private readonly router = inject(Router);`);
    lines.push(`  private readonly service = inject(${entityName}Service);`);
    for (const [target, member] of relatedServices) {
      if (target !== entityName) lines.push(`  private readonly ${member} = inject(${target}Service);`);
    }
    lines.push(``);
    lines.push(`  readonly id = input.required<string>();`);
    lines.push(`  readonly displayFields = DISPLAY_FIELDS;`);
    if (hasTabs) {
      lines.push(`  readonly activeTab = signal<string>('${tsSingleQuoted(sortedTabs[0].name ?? '')}');`);
      for (const prop of tabClassProperties) {
        lines.push(`  readonly ${prop.member} = ${prop.constName};`);
      }
    } else if (hasGroups) {
      for (const g of sortedGroups) {
        const member = `group${DslMapper.toTypeName(g.name)}Fields`;
        const constName = `GROUP_${DslMapper.toKebabCase(g.name).replace(/-/g, '_').toUpperCase()}_FIELDS`;
        lines.push(`  readonly ${member} = ${constName};`);
      }
      if (ungroupedFields.length > 0) {
        lines.push(`  readonly ungroupedFields = UNGROUPED_FIELDS;`);
      }
    }
    if (undeclaredRows.length > 0) {
      const members = undeclaredRows
        .map((row) => `${row.name}?: ${row.kind === 'date' ? 'string' : 'number'}`)
        .join('; ');
      lines.push(`  /** The server-owned fields the ${modelName} interface does not declare, read through a narrowing cast. */`);
      lines.push(`  readonly systemInfo = computed(() => this.entity() as unknown as { ${members} } | null);`);
    }
    lines.push(``);
    lines.push(`  private readonly entityResource = rxResource({`);
    lines.push(`    params: () => this.id(),`);
    lines.push(`    stream: ({ params }) => this.service.findById(params),`);
    lines.push(`  });`);
    lines.push(``);
    // A resource's value() throws once its load has failed, so it is read only when it holds one.
    lines.push(`  readonly entity = computed(() => (this.entityResource.hasValue() ? this.entityResource.value() : null) ?? null);`);
    lines.push(`  readonly isLoading = computed(() => this.entityResource.isLoading());`);
    lines.push(`  readonly error = computed(() => {`);
    lines.push(`    const err = this.entityResource.error();`);
    lines.push(`    return err ? httpErrorMessage(err, { entity: '${noun}', action: 'load' }) : null;`);
    lines.push(`  });`);
    for (const rel of related) {
      if (!rel.fetch) continue;
      const { member, target, param } = rel.fetch;
      lines.push(``);
      lines.push(`  /** The first ${RELATED_PAGE_SIZE} ${tsTemplateText(rel.targetLabel)} whose ${param} is this record's id. */`);
      lines.push(`  protected readonly ${member}Resource = rxResource({`);
      lines.push(`    params: () => this.id(),`);
      lines.push(`    stream: ({ params }) => this.${relatedServices.get(target)}.findAll({ size: ${RELATED_PAGE_SIZE} }, { ${param}: params }),`);
      lines.push(`  });`);
      lines.push(`  readonly ${member} = computed(() => (this.${member}Resource.hasValue() ? this.${member}Resource.value().content : []));`);
    }
    if (related.some((rel) => rel.fetch)) lines.push(``);
    lines.push(`  readonly deleteError = signal<string | null>(null);`);
    if (actions.length > 0) {
      lines.push(`  readonly actionError = signal<string | null>(null);`);
      lines.push(`  readonly actionPending = signal(false);`);
      lines.push(``);
      lines.push(`  /** The entity's parameterless actions, by service method name. */`);
      lines.push(`  private readonly actionCalls = {`);
      for (const action of actions) {
        lines.push(`    ${action.methodName}: (id: string) => this.service.${action.methodName}(id),`);
      }
      lines.push(`  };`);
    }
    lines.push(``);

    for (const enumType of enumTypes) {
      // Per-enum DisplayNames map exposed as a class field for the
      // formatValue() switch. Identifier mirrors the enum name with
      // the FIRST char lowercased only (camelCase) — using a full
      // .toLowerCase() here would produce `orderstatusDisplayNames`
      // for OrderStatus, breaking the casing convention and making
      // multi-cap enums (e.g. OAuthClient → oauthclientDisplayNames)
      // unreadable. Lower-first preserves all subsequent capitals.
      const fieldName = enumType.charAt(0).toLowerCase() + enumType.slice(1) + 'DisplayNames';
      lines.push(`  private readonly ${fieldName} = ${enumType}DisplayNames;`);
    }
    if (enumTypes.length > 0) lines.push(``);

    const nameField = displayFields.find((f) => f.name === 'name' || f.name === 'title');
    lines.push(`  getTitle(): string {`);
    lines.push(`    const entity = this.entity();`);
    lines.push(`    if (!entity) return '';`);
    lines.push(`    return ${nameField ? `entity.${nameField.name} ?? String(entity.${idField})` : `String(entity.${idField})`};`);
    lines.push(`  }`);
    lines.push(``);

    // The currency/percent pipes accept `string | number | null | undefined`, never `unknown`.
    // Angular type-checks every @switch branch whether or not a field selects it, so `rawValue`
    // feeding those two pipes made EVERY emitted detail component fail with TS2769 — six errors
    // in the three-entity sample, none of which anything had ever compiled.
    lines.push(`  numericValue(field: FieldDisplay, entity: ${modelName} | null): number | null {`);
    lines.push(`    const value = this.rawValue(field, entity);`);
    // Null/absent stays null so the pipes render nothing, rather than Number(null) === 0
    // turning a missing amount into $0.00 — formatValue shows an em-dash for the same case.
    lines.push(`    if (value === null || value === undefined || value === '') return null;`);
    lines.push(`    const numeric = Number(value);`);
    lines.push(`    return Number.isFinite(numeric) ? numeric : null;`);
    lines.push(`  }`);
    lines.push(``);

    if (hasDateFields) {
      // DatePipe accepts `string | number | Date | null | undefined`, never `unknown`; an empty
      // value is null so the template shows the em-dash.
      lines.push(`  dateValue(field: FieldDisplay, entity: ${modelName} | null): string | number | null {`);
      lines.push(`    const value = this.rawValue(field, entity);`);
      lines.push(`    if (value === null || value === undefined || value === '') return null;`);
      lines.push(`    return typeof value === 'number' ? value : String(value);`);
      lines.push(`  }`);
      lines.push(``);
    }

    lines.push(`  rawValue(field: FieldDisplay, entity: ${modelName} | null): unknown {`);
    lines.push(`    if (!entity) return null;`);
    lines.push(`    return entity[field.name as keyof ${modelName}] ?? null;`);
    lines.push(`  }`);
    lines.push(``);

    lines.push(`  formatValue(field: FieldDisplay, entity: ${modelName} | null): string {`);
    lines.push(`    if (!entity) return '';`);
    lines.push(`    const value = entity[field.name as keyof ${modelName}];`);
    lines.push(`    if (value === null || value === undefined) return '—';`);
    lines.push(`    switch (field.type) {`);
    lines.push(`      case 'boolean': return value ? 'Yes' : 'No';`);
    lines.push(`      case 'enum': return this.getEnumDisplayName(field.enumType ?? '', value as string);`);
    // A collection field renders its items comma-separated, and a map as its JSON.
    lines.push(`      default: return Array.isArray(value) ? value.join(', ') : typeof value === 'object' ? JSON.stringify(value) : String(value);`);
    lines.push(`    }`);
    lines.push(`  }`);
    lines.push(``);

    lines.push(`  private getEnumDisplayName(enumType: string, value: string): string {`);
    lines.push(`    switch (enumType) {`);
    for (const enumType of enumTypes) {
      lines.push(`      case '${enumType}': return this.${enumType.charAt(0).toLowerCase() + enumType.slice(1)}DisplayNames[value as keyof typeof this.${enumType.charAt(0).toLowerCase() + enumType.slice(1)}DisplayNames] ?? value;`);
    }
    lines.push(`      default: return value;`);
    lines.push(`    }`);
    lines.push(`  }`);
    lines.push(``);

    lines.push(`  reload(): void { this.entityResource.reload(); }`);
    lines.push(``);

    if (actions.length > 0) {
      // A successful action reloads the entity rather than trusting the response body: the
      // action route answers with whatever the action method returns.
      lines.push(`  runAction(name: keyof typeof this.actionCalls): void {`);
      lines.push(`    this.actionError.set(null);`);
      lines.push(`    this.actionPending.set(true);`);
      lines.push(`    this.actionCalls[name](this.id()).subscribe({`);
      lines.push(`      next: () => {`);
      lines.push(`        this.actionPending.set(false);`);
      lines.push(`        this.entityResource.reload();`);
      lines.push(`      },`);
      lines.push(`      error: (err) => {`);
      lines.push(`        this.actionPending.set(false);`);
      lines.push(`        this.actionError.set(httpErrorMessage(err, { entity: '${noun}' }));`);
      lines.push(`      },`);
      lines.push(`    });`);
      lines.push(`  }`);
      lines.push(``);
    }

    lines.push(`  onDelete(): void {`);
    lines.push(`    if (confirm('Are you sure you want to delete this ${displayName.toLowerCase()}?')) {`);
    lines.push(`      this.deleteError.set(null);`);
    lines.push(`      this.service.delete(this.id()).subscribe({`);
    // The list route, or the app root when the list is switched off: a route the table declares.
    lines.push(`        next: () => this.router.navigate(['${entityExitRoute(domain, views)}']),`);
    lines.push(`        error: (err) => this.deleteError.set(httpErrorMessage(err, { entity: '${noun}', action: 'delete' })),`);
    lines.push(`      });`);
    lines.push(`    }`);
    lines.push(`  }`);
    lines.push(`}`);

    return lines.join('\n');
  }
}

/** The rows a related-records panel shows: at most this many, the rest behind "View all". */
const RELATED_PAGE_SIZE = 10;

/** One relationship in the detail view's "Related records" section. */
interface RelatedRecords {
  /** The relationship's label: its humanized name. */
  label: string;
  /** The target's plural, as the list page names it. */
  targetLabel: string;
  testId: string;
  /** The absolute route of the target's list page, when one is emitted. */
  listRoute?: string;
  /** How the panel reads this record's children, when the list route can filter them. */
  fetch?: RelatedFetch;
}

/** The children of one record, read through the target's service filtered by the back-reference. */
interface RelatedFetch {
  /** The target's simple entity name; its service is `<target>Service`. */
  target: string;
  /** The filter parameter: `<base>Id` of the target's MANY_TO_ONE back to this entity. */
  param: string;
  /** The component member holding the rows; its resource is `<member>Resource`. */
  member: string;
  /** The target's primary-key field: what a row is tracked, linked and identified by. */
  keyField: string;
  /** The target field a row is labelled with, when the relationship names one the target declares. */
  labelField?: string;
  /** The route prefix of the target's detail page, when one is emitted. */
  detailRoute?: string;
}

/**
 * One entry per ONE_TO_MANY relationship, in declaration order.
 *
 * A panel lists the record's children when the target is a loaded domain with an `id`, its service
 * is generated, and it has one MANY_TO_ONE back to this entity — the one `mappedBy` names, else the
 * only one — whose `<base>Id` the target's list route filters on. It reads them through
 * `findAll({ size }, { <base>Id: id })`. The entry links to the target's list page when that is
 * emitted. A relationship with neither has no entry. A MANY_TO_MANY has none: the generated
 * backend keeps no join table for it. ONE_TO_ONE and MANY_TO_ONE point at a single record and are
 * linked from the field table when the foreign key is a field.
 */
function relatedRecords(domain: DomainMetadata, context: GeneratorContext): RelatedRecords[] {
  const listsGenerated = context.config.generateLists !== false;
  const servicesGenerated = context.config.generateServices !== false;
  const entries: RelatedRecords[] = [];
  for (const rel of domain.relationships ?? []) {
    if (rel.type !== 'ONE_TO_MANY') continue;
    const target = simpleEntityName(rel.targetEntity);
    const targetDomain = context.allDomains.find((d) => d.entityName === target);
    if (!targetDomain) continue;
    const views = entityViews(targetDomain);
    const listRoute = listsGenerated && views.list ? `/${DslMapper.routePlural(target)}` : undefined;
    const backReference = servicesGenerated ? childBackReference(rel, domain, targetDomain) : undefined;
    const param = backReference ? foreignKeyParam(backReference.name) : undefined;
    const filterable = param !== undefined && declaresPrimaryKey(targetDomain)
      && filterProperties(targetDomain, (f) => isEnumField(f, context.enums ?? [])).some((p) => p.name === param);
    const labelField = rel.displayField && targetDomain.fields.some((f) => f.name === rel.displayField)
      ? rel.displayField
      : undefined;
    const fetch: RelatedFetch | undefined = filterable
      ? {
          target,
          param: param!,
          keyField: primaryKeyField(targetDomain),
          member: `related${rel.name.charAt(0).toUpperCase()}${rel.name.slice(1)}`,
          ...(labelField ? { labelField } : {}),
          ...(views.detail ? { detailRoute: `/${DslMapper.routePlural(target)}` } : {}),
        }
      : undefined;
    if (!listRoute && !fetch) continue;
    entries.push({
      label: tsTemplateText(DslMapper.humanize(rel.name)),
      targetLabel: tsTemplateText(DslMapper.pluralName(target)),
      testId: DslMapper.toKebabCase(rel.name),
      ...(listRoute ? { listRoute } : {}),
      ...(fetch ? { fetch } : {}),
    });
  }
  return entries;
}

/**
 * The target's MANY_TO_ONE back to `domain` that a ONE_TO_MANY's rows are held by: the one
 * `mappedBy` names (by relationship name, field name, `<base>` or `<base>Id`), else the only one there is.
 */
function childBackReference(
  rel: RelationshipMetadata,
  domain: DomainMetadata,
  target: DomainMetadata,
): RelationshipMetadata | undefined {
  const candidates = (target.relationships ?? []).filter(
    (r) => r.type === 'MANY_TO_ONE' && simpleEntityName(r.targetEntity) === domain.entityName,
  );
  if (rel.mappedBy) {
    return candidates.find(
      (r) => [r.name, r.fieldName, foreignKeyBase(r.name), foreignKeyParam(r.name)].includes(rel.mappedBy),
    );
  }
  return candidates.length === 1 ? candidates[0] : undefined;
}

/** The processor's fallback may record a qualified name; entity names are simple names. */
function simpleEntityName(name: string): string {
  return name.includes('.') ? name.split('.').pop()! : name;
}

/**
 * The service member each fetched target is read through, by target: the component's own
 * `service` for this entity, else `<camel(target)>Service`, one per target.
 */
function relatedServiceMembers(domain: DomainMetadata, related: readonly RelatedRecords[]): Map<string, string> {
  const members = new Map<string, string>();
  for (const rel of related) {
    if (!rel.fetch || members.has(rel.fetch.target)) continue;
    members.set(
      rel.fetch.target,
      rel.fetch.target === domain.entityName ? 'service' : `${DslMapper.toCamelCase(rel.fetch.target)}Service`,
    );
  }
  return members;
}

/** A button in the detail view's header, running one of the entity's actions on the shown record. */
interface DetailAction {
  /** The service method, the name `service-gen` gives the action. */
  methodName: string;
  kebabName: string;
  label: string;
}

/**
 * The actions the detail view offers, in declaration order: those the service exposes as a method
 * (every action but a streaming one) that need nothing but the record's id. An action taking
 * parameters needs input the detail view has no control for, and is left to the service.
 */
function detailActions(domain: DomainMetadata): DetailAction[] {
  return (domain.actions ?? [])
    .filter((action) => !action.streaming && (action.params ?? []).length === 0)
    .map((action) => {
      const methodName = DslMapper.toMethodName(action.name);
      return { methodName, kebabName: DslMapper.toKebabCase(action.name), label: tsTemplateText(toTitleCase(methodName)) };
    });
}


/** A row of the detail view's system panel, after the id. */
interface SystemPanelRow {
  name: string;
  label: string;
  kind: 'date' | 'number';
  /** Whether the entity interface carries the property; an undeclared one is read through a cast. */
  declared: boolean;
}

/**
 * The audit stamps and the version the system panel shows, in that order.
 *
 * An `audited` entity always shows both stamps under the names `auditFieldNames` resolves: the
 * generated repository writes them on every row whether or not the entity declares them. On any
 * other entity a stamp shows only when the entity declares it and it is a system field, so a field
 * the field table leaves out appears here instead of nowhere. A `versioned` entity shows its version.
 */
function systemPanelRows(metadata: DomainMetadata): SystemPanelRow[] {
  const declared = new Set(metadata.fields.map((f) => f.name));
  const system = viewSystemFieldNames(metadata);
  const audit = auditFieldNames(metadata);
  const rows: SystemPanelRow[] = [];
  const stamps: Array<[string, string]> = [[audit.createdAt, 'Created'], [audit.updatedAt, 'Updated']];
  for (const [name, label] of stamps) {
    if (metadata.audited || (declared.has(name) && system.includes(name))) {
      rows.push({ name, label, kind: 'date', declared: declared.has(name) });
    }
  }
  const version = updateVersionField(metadata);
  if (version) rows.push({ name: version.name, label: 'Version', kind: 'number', declared: version.declared });
  return rows;
}

export function generateDetail(
  metadata: DomainMetadata,
  config: GeneratorConfig,
  allDomains: DomainMetadata[] = [metadata],
  enums: EnumMetadata[] = [],
): GeneratedFile | null {
  const generator = new DetailGenerator();
  const context: GeneratorContext = { config, backend: config.backend ?? 'KERNEL', allDomains, enums };
  return generator.generate(metadata, context);
}

