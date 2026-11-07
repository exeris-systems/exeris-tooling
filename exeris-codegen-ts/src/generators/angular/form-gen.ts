/**
 * Angular Form Generator
 * Generates Angular 22+ form components with Signals from domain metadata.
 */

import type { DomainMetadata, FieldMetadata, UIGroupMetadata } from '../../models/domain-model.js';
import { modelTypeName } from '../../models/model-naming.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import type { GeneratorConfig } from '../../config.js';
import type { CodeGenerator, EnumMetadata, GeneratedFile, GeneratorContext } from '../../core/generator-registry.js';
import type { BackendType } from '../../core/backend-strategy.js';
import { primaryKeyField } from '../../core/primary-key.js';
import { outPath } from '../../core/paths.js';
import { updateOmittedFields, updateVersionField } from '../api/type-gen.js';
import { fieldRenderContext, resolveFieldRenders, toTitleCase, type FieldRenderModel } from './field-render.js';
import { testIdSegment, tsSingleQuoted, tsTemplateText } from './ts-literal.js';
import { declaredIconSpan } from './declared-icon.js';
import { entityExitRoute, entityViews, hasFormPage } from './entity-views.js';
import { MAX_PAGE_SIZE } from './list-query.js';
import { fileHeaderLines } from '../file-header.js';

export { GeneratedFile };

export class FormGenerator implements CodeGenerator {
  readonly name = 'FormGenerator';
  readonly artifactType = 'FORM' as const;
  readonly supportedBackends: BackendType[] = [];
  readonly priority = 20;

  generate(domain: DomainMetadata, context: GeneratorContext): GeneratedFile | null {
    // One component serves both the create and the edit route, so it is dropped only when both are off.
    if (!hasFormPage(entityViews(domain))) {
      return null;
    }

    const content = this.generateFormContent(domain, context);
    const fileName = `${DslMapper.toKebabCase(domain.entityName)}-form.component.ts`;
    const filePath = outPath('components', fileName);

    return {
      path: filePath,
      content,
      artifactType: 'FORM',
      overwritable: true,
    };
  }

  private generateFormContent(domain: DomainMetadata, context: GeneratorContext): string {
    // A versioned entity's lock field is never a control: the edit form holds the loaded
    // entity's value aside and sends it with the update (type-gen's updateVersionField names it).
    const version = updateVersionField(domain);
    // The expression reading the version off a loaded entity. When the metadata does not declare
    // the field, the entity interface has no such property, so it is read through a narrowing cast.
    const readVersion = (entityExpr: string): string => {
      const name = version!.name;
      return version!.declared
        ? `${entityExpr}.${name} ?? null`
        : `(${entityExpr} as unknown as { ${name}?: number }).${name} ?? null`;
    };
    const entityName = domain.entityName;

    const modelName = modelTypeName(entityName);
    const kebabName = DslMapper.toKebabCase(entityName);
    const noun = tsSingleQuoted((domain.displayName ?? entityName).toLowerCase());
    const idField = primaryKeyField(domain);

    // No control for a system field (type-gen's viewSystemFieldNames). That includes a UNIVERSE
    // entity's shared-scope key, which is server-owned like its tenant: the repository stamps it
    // from the bound storage context and the create DTO omits it, so the form never sends it.
    // The form renders no link, so the context resolves none; a foreign key is picked from its
    // target's records when the target's service is generated.
    const renders = resolveFieldRenders(
      domain,
      fieldRenderContext(domain, context.allDomains, false, context.enums ?? [], context.config.generateServices !== false),
    );
    const createFields = renders.filter((r) => r.form.placement === 'control');
    const pickerFields = createFields.filter((r) => r.form.picker !== undefined);
    // One injected service per picked target, in field order; the entity's own service serves a
    // relationship to itself.
    const pickerTargets = [...new Set(pickerFields.map((r) => r.form.picker!.target))];
    // The `pickerOptions` helper reads the key its targets share; when their keys differ, each call
    // passes its target's key.
    const pickerKeys = [...new Set(pickerFields.map((r) => r.form.picker!.keyField))];
    const sharedPickerKey = pickerKeys.length === 1 ? pickerKeys[0] : undefined;
    const pickerServiceMember = (target: string): string =>
      target === entityName ? 'service' : `${target.charAt(0).toLowerCase()}${target.slice(1)}Service`;
    // Computed fields render read-only, for information, and are kept out of the submitted DTO.
    const computedFields = renders.filter((r) => r.form.placement === 'computed');
    const blobFields = (domain.fields ?? []).filter((f) => f.blob !== undefined);

    const lines: string[] = [];
    lines.push(
      ...fileHeaderLines({
        title: `${entityName} Form Component`,
        notes: ['Uses Angular 22 standalone components + signals'],
        doNotEdit: 'omit',
      }),
    );
    lines.push('');
    const plural = DslMapper.routePlural(entityName);
    // A routed form returns to the saved instance's detail page; without one, it leaves the entity.
    const views = entityViews(domain);
    const exitRoute = entityExitRoute(domain, views);
    const formModelName = `${entityName}FormModel`;
    const validation = formValidation(createFields);

    lines.push("import { Component, ChangeDetectionStrategy, input, output, signal, computed, effect, inject } from '@angular/core';");
    lines.push("import { rxResource } from '@angular/core/rxjs-interop';");
    lines.push(`import { ${signalFormsImports(validation).join(', ')} } from '@angular/forms/signals';`);
    lines.push("import { ActivatedRoute, Router } from '@angular/router';");
    if (blobFields.length > 0) {
      lines.push("import { firstValueFrom } from 'rxjs';");
    }
    lines.push(`import { ${modelName}, ${modelName}Create, ${modelName}Update, ${entityName}Service } from '../services/${kebabName}.service';`);
    lines.push("import { httpErrorMessage } from '../core/http-error';");
    for (const target of pickerTargets) {
      if (target === entityName) continue;
      const picker = pickerFields.find((r) => r.form.picker!.target === target)!.form.picker!;
      lines.push(`import { ${target}Service } from '../services/${picker.serviceModule}';`);
    }

    // Collect enum types used in create fields
    const enumTypes = new Set<string>();
    for (const f of createFields) {
      if (f.form.enumType) {
        enumTypes.add(f.form.enumType);
      }
    }

    if (enumTypes.size > 0) {
      const enumImports = Array.from(enumTypes).flatMap(e => [e, `${e}DisplayNames`]);
      lines.push(`import { ${enumImports.join(', ')} } from '../types/enums';`);
    }

    lines.push('');
    if (pickerFields.length > 0) {
      lines.push(...pickerOptionsHelper(sharedPickerKey));
      lines.push('');
    }
    // The value the form edits: one property per control, typed as the control holds it.
    lines.push(`interface ${formModelName} {`);
    for (const f of createFields) {
      lines.push(`  ${f.name}: ${f.form.modelType};`);
    }
    lines.push('}');
    lines.push('');
    lines.push('@Component({');
    lines.push(`  selector: 'app-${kebabName}-form',`);
    lines.push('  standalone: true,');
    lines.push('  imports: [FormField],');
    lines.push('  changeDetection: ChangeDetectionStrategy.OnPush,');
    lines.push('  template: `');
    // Loading and error of the by-id load mirror the detail view's markup.
    lines.push('    @if (isLoading()) {');
    lines.push('      <div class="animate-pulse space-y-4 mb-6" role="status" aria-label="Loading...">');
    lines.push('        <div class="h-4 bg-gray-200 dark:bg-gray-700 rounded-sm w-1/2"></div>');
    lines.push('      </div>');
    lines.push('    } @else if (loadError()) {');
    lines.push('      <div role="alert" class="exeris-alert exeris-alert-danger mb-6">');
    lines.push('        <p>{{ loadError() }}</p>');
    lines.push('        <button type="button" (click)="reload()" class="exeris-btn exeris-btn-secondary exeris-btn-sm mt-4">Try again</button>');
    lines.push('      </div>');
    lines.push('    }');
    // The form validates itself, so the browser's own validation of the bound attributes is off.
    lines.push('    <form (submit)="onSubmit($event)" novalidate class="space-y-6">');

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
    if (!hasTabs) {
      if (!hasGroups) {
        for (const f of createFields) {
          lines.push(...this.renderFormControl(f));
        }
        for (const f of computedFields) {
          lines.push(...this.renderComputedField(f));
        }
        for (const f of blobFields) {
          lines.push(...this.renderBlobControl(f));
        }
      } else {
        const sortedGroups = [...domain.uiMetadata!.groups!].sort((a, b) => (a.order ?? 999) - (b.order ?? 999));
        const groupedFieldNames = new Set<string>();

        for (const g of sortedGroups) {
          lines.push(...this.renderFormGroupSection(g, g.fields, createFields, computedFields, '      ', blobFields));
          for (const fieldName of g.fields) {
            groupedFieldNames.add(fieldName);
          }
        }

        for (const f of createFields) {
          if (!groupedFieldNames.has(f.name)) {
            lines.push(...this.renderFormControl(f));
          }
        }
        for (const f of computedFields) {
          if (!groupedFieldNames.has(f.name)) {
            lines.push(...this.renderComputedField(f));
          }
        }
        for (const f of blobFields) {
          if (!groupedFieldNames.has(f.name)) {
            lines.push(...this.renderBlobControl(f));
          }
        }
      }
    } else {
      lines.push('      <div role="tablist" aria-label="Form Tabs" class="flex border-b border-gray-200 dark:border-gray-700 mb-6 gap-2">');
      for (const t of sortedTabs) {
        const tabName = t.name ?? '';
        const tabLabel = tsTemplateText(t.label || t.name || '');
        const iconSpan = t.icon ? declaredIconSpan(t.icon, 'mr-2') : '';
        lines.push(`        <button type="button" role="tab" id="tab-${testIdSegment(tabName)}" aria-controls="panel-${testIdSegment(tabName)}" [attr.aria-selected]="activeTab() === '${tsSingleQuoted(tabName)}'" (click)="activeTab.set('${tsSingleQuoted(tabName)}')" data-testid="tab-${testIdSegment(tabName)}" class="px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors cursor-pointer" [class]="activeTab() === '${tsSingleQuoted(tabName)}' ? 'border-exeris-primary text-exeris-primary font-semibold' : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200 hover:border-gray-300'">${iconSpan}${tabLabel}</button>`);
      }
      lines.push('      </div>');

      const sortedGroups = hasGroups
        ? [...domain.uiMetadata!.groups!].sort((a, b) => (a.order ?? 999) - (b.order ?? 999))
        : [];

      for (const t of sortedTabs) {
        const tabName = t.name ?? '';
        const tabFields = t.fields ?? [];
        lines.push(`      <div role="tabpanel" id="panel-${testIdSegment(tabName)}" aria-labelledby="tab-${testIdSegment(tabName)}" [hidden]="activeTab() !== '${tsSingleQuoted(tabName)}'" data-testid="tabpanel-${testIdSegment(tabName)}" class="space-y-6">`);
        if (!hasGroups) {
          for (const fieldName of tabFields) {
            const control = createFields.find((f) => f.name === fieldName);
            if (control) {
              lines.push(...this.renderFormControl(control).map((l) => `  ${l}`));
              continue;
            }
            const computed = computedFields.find((f) => f.name === fieldName);
            if (computed) {
              lines.push(...this.renderComputedField(computed).map((l) => `  ${l}`));
              continue;
            }
            const blob = blobFields.find((f) => f.name === fieldName);
            if (blob) {
              lines.push(...this.renderBlobControl(blob, '        '));
            }
          }
        } else {
          const tabGroupedFieldNames = new Set<string>();
          for (const g of sortedGroups) {
            const gTabFieldNames = g.fields.filter((fn) => tabFields.includes(fn));
            if (gTabFieldNames.length === 0) continue;
            lines.push(...this.renderFormGroupSection(g, gTabFieldNames, createFields, computedFields, '        ', blobFields));
            for (const fn of gTabFieldNames) {
              tabGroupedFieldNames.add(fn);
            }
          }
          for (const fieldName of tabFields) {
            if (!tabGroupedFieldNames.has(fieldName)) {
              const control = createFields.find((f) => f.name === fieldName);
              if (control) {
                lines.push(...this.renderFormControl(control).map((l) => `  ${l}`));
                continue;
              }
              const computed = computedFields.find((f) => f.name === fieldName);
              if (computed) {
                lines.push(...this.renderComputedField(computed).map((l) => `  ${l}`));
                continue;
              }
              const blob = blobFields.find((f) => f.name === fieldName);
              if (blob) {
                lines.push(...this.renderBlobControl(blob, '        '));
              }
            }
          }
        }
        lines.push('      </div>');
      }

      const tabbedFieldNames = new Set<string>(sortedTabs.flatMap((t) => t.fields ?? []));
      if (!hasGroups) {
        for (const f of createFields) {
          if (!tabbedFieldNames.has(f.name)) {
            lines.push(...this.renderFormControl(f));
          }
        }
        for (const f of computedFields) {
          if (!tabbedFieldNames.has(f.name)) {
            lines.push(...this.renderComputedField(f));
          }
        }
        for (const f of blobFields) {
          if (!tabbedFieldNames.has(f.name)) {
            lines.push(...this.renderBlobControl(f));
          }
        }
      } else {
        const untabbedGroupedFieldNames = new Set<string>();
        for (const g of sortedGroups) {
          const gUntabbedFieldNames = g.fields.filter((fn) => !tabbedFieldNames.has(fn));
          if (gUntabbedFieldNames.length === 0) continue;
          lines.push(...this.renderFormGroupSection(g, gUntabbedFieldNames, createFields, computedFields, '      ', blobFields));
          for (const fn of gUntabbedFieldNames) {
            untabbedGroupedFieldNames.add(fn);
          }
        }
        for (const f of createFields) {
          if (!tabbedFieldNames.has(f.name) && !untabbedGroupedFieldNames.has(f.name)) {
            lines.push(...this.renderFormControl(f));
          }
        }
        for (const f of computedFields) {
          if (!tabbedFieldNames.has(f.name) && !untabbedGroupedFieldNames.has(f.name)) {
            lines.push(...this.renderComputedField(f));
          }
        }
        for (const f of blobFields) {
          if (!tabbedFieldNames.has(f.name) && !untabbedGroupedFieldNames.has(f.name)) {
            lines.push(...this.renderBlobControl(f));
          }
        }
      }
    }

    lines.push('      @if (error()) {');
    lines.push('        <div role="alert" data-testid="submit-error" class="exeris-alert exeris-alert-danger text-sm">{{ error() }}</div>');
    lines.push('      }');
    if (version) {
      // The server answers a stale update with 409 and no body; the only recovery is to load
      // the row as it now stands, which also picks up its current version.
      lines.push('      @if (conflict()) {');
      lines.push('        <div role="alert" data-testid="conflict-message" class="exeris-alert exeris-alert-warning text-sm">');
      lines.push('          <p>This record was changed by someone else. Reload to see the latest version.</p>');
      lines.push('          <button type="button" (click)="reload()" data-testid="reload-button" class="exeris-btn exeris-btn-secondary exeris-btn-sm mt-2">Reload</button>');
      lines.push('        </div>');
      lines.push('      }');
    }
    lines.push('      <div class="flex justify-end gap-3 pt-4 border-t border-gray-200 dark:border-gray-700">');
    lines.push('        <button type="button" (click)="onCancel()" data-testid="cancel-button" class="exeris-btn exeris-btn-secondary">Cancel</button>');
    lines.push('        <button type="submit" [disabled]="form().invalid() || saving() || (editMode() && !current())" data-testid="submit-button" class="exeris-btn exeris-btn-primary">');
    lines.push(`          @if (saving()) { Saving... } @else { {{ editMode() ? 'Update' : 'Create' }} ${entityName} }`);
    lines.push('        </button>');
    lines.push('      </div>');
    lines.push('    </form>');
    lines.push('  `,');
    lines.push('})');

    lines.push(`export class ${entityName}FormComponent {`);
    lines.push(`  private readonly service = inject(${entityName}Service);`);
    lines.push('  private readonly router = inject(Router);');
    // The form is a page when its activated route's component is this class; embedded in a
    // host template it sees the host's route instead (or none), and leaves the next step to
    // the host listening on `saved` / `cancelled`.
    lines.push(`  private readonly routed = inject(ActivatedRoute, { optional: true })?.component === ${entityName}FormComponent;`);
    for (const target of pickerTargets) {
      if (target === entityName) continue;
      lines.push(`  private readonly ${pickerServiceMember(target)} = inject(${target}Service);`);
    }
    lines.push('');

    // Generate enum arrays
    for (const enumType of Array.from(enumTypes)) {
      lines.push(`  readonly ${enumType}Values = Object.values(${enumType});`);
      lines.push(`  readonly ${enumType}DisplayNames = ${enumType}DisplayNames;`);
      lines.push('');
    }

    lines.push(`  readonly mode = input<'create' | 'edit'>('create');`);
    lines.push(`  readonly entity = input<${modelName} | null>(null);`);
    // Bound from the `:id` route parameter by withComponentInputBinding on the edit route.
    lines.push('  readonly id = input<string | undefined>();');
    if (hasTabs) {
      lines.push(`  readonly activeTab = signal<string>('${tsSingleQuoted(sortedTabs[0].name ?? '')}');`);
    }
    lines.push('');
    lines.push('  private readonly entityResource = rxResource({');
    lines.push('    params: () => this.id(),');
    lines.push('    stream: ({ params }) => this.service.findById(params),');
    lines.push('  });');
    lines.push('');
    // An embedding host's `entity` wins over the by-id load; an `id` always means edit.
    // A resource's value() throws once its load has failed, so it is read only when it holds one.
    lines.push(`  readonly current = computed<${modelName} | null>(() => this.entity() ?? (this.entityResource.hasValue() ? this.entityResource.value() : null) ?? null);`);
    lines.push("  readonly editMode = computed(() => this.id() !== undefined || this.mode() === 'edit');");
    lines.push('  readonly isLoading = computed(() => this.entityResource.isLoading());');
    lines.push('  readonly loadError = computed(() => {');
    lines.push('    const err = this.entityResource.error();');
    lines.push(`    return err ? httpErrorMessage(err, { entity: '${noun}', action: 'load' }) : null;`);
    lines.push('  });');
    lines.push('');
    lines.push(`  readonly saved = output<${modelName}>();`);
    lines.push('  readonly cancelled = output<void>();');
    lines.push('');
    lines.push('  readonly saving = signal(false);');
    lines.push('  readonly error = signal<string | null>(null);');
    for (const f of blobFields) {
      lines.push(`  readonly ${f.name}File = signal<File | null>(null);`);
    }
    if (version) {
      lines.push('  readonly conflict = signal(false);');
      lines.push('');
      lines.push(`  /** The ${version.name} the edited entity was loaded at; the update sends it back as the expected version. */`);
      const versionType = version.tsType.endsWith('| null') ? version.tsType : `${version.tsType} | null`;
      lines.push(`  private readonly loadedVersion = signal<${versionType}>(null);`);
    }
    lines.push('');
    lines.push(`  private readonly formModel = signal<${formModelName}>({`);
    for (const f of createFields) {
      lines.push(`    ${f.name}: ${f.form.initialValue},`);
    }
    lines.push('  });');
    if (validation.rules.length > 0) {
      lines.push('  readonly form = form(this.formModel, (path) => {');
      for (const rule of validation.rules) {
        lines.push(`    ${rule}`);
      }
      lines.push('  });');
    } else {
      lines.push('  readonly form = form(this.formModel);');
    }

    // A foreign-key select lists its target's records once, when the form is created: the first
    // page of the largest size the list route serves. A target with more rows offers those first
    // ones, and the value the control holds stays an option whether or not it is among them.
    for (const f of pickerFields) {
      const picker = f.form.picker!;
      const resource = `${pickerOptionsName(f.name)}Resource`;
      const keyArg = sharedPickerKey === undefined ? `, '${tsSingleQuoted(picker.keyField)}'` : '';
      const labelArg = `${keyArg}${picker.labelField ? `, (row) => row.${picker.labelField}` : ''}`;
      lines.push('');
      lines.push(`  private readonly ${resource} = rxResource({ stream: () => this.${pickerServiceMember(picker.target)}.findAll({ size: ${MAX_PAGE_SIZE} }) });`);
      lines.push(`  readonly ${pickerOptionsName(f.name)} = computed(() =>`);
      if (picker.target === entityName) {
        lines.push(`    pickerOptions(this.${resource}.hasValue() ? this.${resource}.value() : undefined${labelArg})`);
        // A record is never offered as its own parent: the edited record's id is left out.
        lines.push(`      .filter((option) => !this.editMode() || option.value !== String(this.id() ?? this.current()?.${idField})),`);
      } else {
        lines.push(`    pickerOptions(this.${resource}.hasValue() ? this.${resource}.value() : undefined${labelArg}),`);
      }
      lines.push('  );');
      const targetDomain = context.allDomains.find((d) => d.entityName === picker.target);
      const targetNoun = tsSingleQuoted((targetDomain?.pluralName ?? DslMapper.pluralName(picker.target)).toLowerCase());
      lines.push(`  readonly ${pickerErrorName(f.name)} = computed(() => {`);
      lines.push(`    const err = this.${resource}.error();`);
      lines.push(`    return err ? httpErrorMessage(err, { entity: '${targetNoun}', action: 'load' }) : null;`);
      lines.push('  });');
      // The list response's `last` is false while rows remain beyond the page it holds.
      lines.push(`  readonly ${pickerTruncatedName(f.name)} = computed(() => this.${resource}.hasValue() && this.${resource}.value().last === false);`);
      lines.push(`  readonly ${pickerUnlistedName(f.name)} = computed(() => {`);
      lines.push(`    const value = this.formModel().${f.name};`);
      lines.push(`    return value !== '' && !this.${pickerOptionsName(f.name)}().some((option) => option.value === value);`);
      lines.push('  });');
    }

    // The metadata names a computed field's dependencies but not its formula, so the form shows
    // the value the loaded entity carries and computes nothing itself.
    for (const cf of computedFields) {
      lines.push('');
      lines.push(`  readonly ${computedSignalName(cf.name)} = computed(() => this.current()?.${cf.name} ?? null);`);
    }
    lines.push('');
    lines.push('  constructor() {');
    lines.push('    effect(() => {');
    lines.push('      const entity = this.current();');
    lines.push('      if (entity && this.editMode()) {');
    lines.push('        this.formModel.set(this.toFormModel(entity));');
    if (version) {
      lines.push(`        this.loadedVersion.set(${readVersion('entity')});`);
    }
    lines.push('      }');
    lines.push('    });');
    lines.push('  }');
    lines.push('');
    // A loaded value the control cannot show (null, or absent) becomes the control's empty value.
    lines.push(`  private toFormModel(entity: ${modelName}): ${formModelName} {`);
    lines.push('    return {');
    for (const f of createFields) {
      lines.push(`      ${f.name}: entity.${f.name} ?? ${f.form.emptyValue},`);
    }
    lines.push('    };');
    lines.push('  }');
    lines.push('');
    // submit() marks every field touched and runs the action only when the form is valid.
    lines.push('  onSubmit(event: Event): void {');
    lines.push('    event.preventDefault();');
    lines.push('    void submit(this.form, () => this.save());');
    lines.push('  }');
    lines.push('');
    // The generated server names no field in a failed response, so a save resolves to no field
    // error: every failure is reported at form level.
    lines.push('  private save(): Promise<undefined> {');
    // Edit mode without a loaded entity would overwrite a row it never read.
    lines.push('    const current = this.current();');
    lines.push('    if (this.editMode() && !current) {');
    lines.push('      return Promise.resolve(undefined);');
    lines.push('    }');
    lines.push('');
    lines.push('    this.saving.set(true);');
    lines.push('    this.error.set(null);');
    if (version) lines.push('    this.conflict.set(false);');
    lines.push('');
    // A number control holds `number | null`, the DTO's own type, so the model is the payload.
    lines.push('    const data = this.formModel();');
    // The edit payload is the loaded record less the fields the update does not take from the body
    // (the server-owned fields, the read-only ones and the `inUpdate = false` ones), with the form's
    // values over it: the update writes every other domain column, so a field the form does not
    // offer (hidden, create-only) keeps its stored value. A control the update does not take is
    // taken out of the form's values as well, so the body carries exactly the fields of `…Update`.
    const serverOwned = updateOmittedFields(domain);
    const formOmitted = createFields.filter((r) => serverOwned.includes(r.name)).map((r) => r.name);
    const enteredValues = formOmitted.length > 0 ? 'entered' : 'data';
    const updateBody = [
      `  private updateBody(current: ${modelName}, data: ${formModelName}): ${modelName}Update {`,
      ...(serverOwned.length > 0
        ? [`    const { ${serverOwned.map((name, i) => `${name}: _stored${i}`).join(', ')}, ...stored } = current;`]
        : []),
      ...(formOmitted.length > 0
        ? [`    const { ${formOmitted.map((name, i) => `${name}: _entered${i}`).join(', ')}, ...entered } = data;`]
        : []),
      version
        ? `    return { ...${serverOwned.length > 0 ? 'stored' : 'current'}, ...${enteredValues}, ${version.name}: this.loadedVersion() } as ${modelName}Update;`
        : `    return { ...${serverOwned.length > 0 ? 'stored' : 'current'}, ...${enteredValues} } as ${modelName}Update;`,
      '  }',
      '',
    ];
    // The create payload leaves out a field only the edit form offers (`inCreate = false`).
    const createMembers = createFields.filter((r) => r.form.inCreate).map((r) => `${r.name}: data.${r.name}`);
    const createPayload = createMembers.length === createFields.length
      ? `data as ${modelName}Create`
      : `${createMembers.length > 0 ? `{ ${createMembers.join(', ')} }` : '{}'} as ${modelName}Create`;
    lines.push(`    const request$ = this.editMode() && current ? this.service.update(String(current.${idField}), this.updateBody(current, data)) : this.service.create(${createPayload});`);
    lines.push('');
    lines.push('    return new Promise((resolve) => {');
    lines.push('      request$.subscribe({');
    if (blobFields.length === 0) {
      lines.push('        next: (result) => {');
      lines.push('          this.saving.set(false);');
      lines.push('          resolve(undefined);');
      lines.push('          this.saved.emit(result);');
      lines.push('          if (this.routed) {');
      lines.push(views.detail
        ? `            void this.router.navigate(['/${plural}', String(result.${idField})]);`
        : `            void this.router.navigate(['${exitRoute}']);`);
      lines.push('          }');
      lines.push('        },');
    } else {
      lines.push('        next: async (result) => {');
      lines.push('          try {');
      lines.push(`            const id = String(result.${idField});`);
      for (const f of blobFields) {
        const pascal = f.name.charAt(0).toUpperCase() + f.name.slice(1);
        lines.push(`            const ${f.name} = this.${f.name}File();`);
        lines.push(`            if (${f.name}) {`);
        lines.push(`              await firstValueFrom(this.service.upload${pascal}(id, ${f.name}));`);
        lines.push('            }');
      }
      lines.push('            this.saving.set(false);');
      lines.push('            resolve(undefined);');
      lines.push('            this.saved.emit(result);');
      lines.push('            if (this.routed) {');
      lines.push(views.detail
        ? `              void this.router.navigate(['/${plural}', String(result.${idField})]);`
        : `              void this.router.navigate(['${exitRoute}']);`);
      lines.push('            }');
      lines.push('          } catch (uploadErr) {');
      lines.push('            this.saving.set(false);');
      lines.push('            resolve(undefined);');
      lines.push(`            this.error.set(httpErrorMessage(uploadErr, { entity: '${noun}', action: 'save' }));`);
      lines.push('          }');
      lines.push('        },');
    }
    lines.push('        error: (err) => {');
    lines.push('          this.saving.set(false);');
    lines.push('          resolve(undefined);');
    if (version) {
      // A conflict is about the whole row, never one field.
      lines.push("          if (this.editMode() && err?.status === 409) {");
      lines.push('            this.conflict.set(true);');
      lines.push('            return;');
      lines.push('          }');
    }
    lines.push(`          this.error.set(httpErrorMessage(err, { entity: '${noun}', action: 'save' }));`);
    lines.push('        },');
    lines.push('      });');
    lines.push('    });');
    lines.push('  }');
    lines.push('');
    lines.push(...updateBody);
    for (const f of blobFields) {
      const pascal = f.name.charAt(0).toUpperCase() + f.name.slice(1);
      lines.push(`  on${pascal}Change(event: Event): void {`);
      lines.push('    const input = event.target as HTMLInputElement;');
      lines.push(`    this.${f.name}File.set(input.files?.[0] ?? null);`);
      lines.push('  }');
      lines.push('');
    }
    lines.push('  onCancel(): void {');
    lines.push('    this.cancelled.emit();');
    lines.push('    if (this.routed) {');
    // Back to the entity being edited, or to the list when nothing was.
    if (views.detail) {
      lines.push('      const id = this.id();');
      lines.push(`      void this.router.navigate(id !== undefined ? ['/${plural}', id] : ['${exitRoute}']);`);
    } else {
      lines.push(`      void this.router.navigate(['${exitRoute}']);`);
    }
    lines.push('    }');
    lines.push('  }');
    lines.push('');
    if (!version) {
      lines.push('  reload(): void { this.entityResource.reload(); }');
    } else {
      // A form loaded by id reloads its resource, and the patch effect picks up the fresh
      // values and version. A host-supplied entity has no resource behind it, so the row is
      // fetched directly.
      lines.push('  /** Loads the row as it now stands, replacing the edited values and the expected version. */');
      lines.push('  reload(): void {');
      lines.push('    this.conflict.set(false);');
      lines.push('    if (this.id() !== undefined) {');
      lines.push('      this.entityResource.reload();');
      lines.push('      return;');
      lines.push('    }');
      lines.push('    const current = this.current();');
      lines.push('    if (!current) return;');
      lines.push(`    this.service.findById(String(current.${idField})).subscribe({`);
      lines.push('      next: (fresh) => {');
      lines.push('        this.form().reset(this.toFormModel(fresh));');
      lines.push(`        this.loadedVersion.set(${readVersion('fresh')});`);
      lines.push('      },');
      lines.push(`      error: (err) => this.error.set(httpErrorMessage(err, { entity: '${noun}', action: 'load' })),`);
      lines.push('    });');
      lines.push('  }');
    }

    lines.push('}');

    return lines.join('\n');
  }

  private renderFormGroupSection(
    g: UIGroupMetadata,
    fieldNames: string[],
    createFields: FieldRenderModel[],
    computedFields: FieldRenderModel[],
    indent: string,
    blobFields: FieldMetadata[] = [],
  ): string[] {
    const lines: string[] = [];
    const heading = tsTemplateText(g.label || g.name);
    const isCollapsible = g.collapsible === true;
    if (isCollapsible) {
      lines.push(`${indent}<details data-testid="group-${testIdSegment(g.name)}"${g.collapsed ? '' : ' open'} class="space-y-4">`);
      lines.push(`${indent}  <summary class="cursor-pointer text-lg font-medium text-gray-900 dark:text-white">${heading}</summary>`);
    } else {
      lines.push(`${indent}<section data-testid="group-${testIdSegment(g.name)}" class="space-y-4">`);
      lines.push(`${indent}  <h3 class="text-lg font-medium text-gray-900 dark:text-white">${heading}</h3>`);
    }
    if (g.description) {
      lines.push(`${indent}  <p class="text-sm text-gray-500 dark:text-gray-400">${tsTemplateText(g.description)}</p>`);
    }
    const extraIndent = indent === '      ' ? '  ' : '    ';
    for (const fieldName of fieldNames) {
      const control = createFields.find((f) => f.name === fieldName);
      if (control) {
        lines.push(...this.renderFormControl(control).map((l) => `${extraIndent}${l}`));
        continue;
      }
      const computed = computedFields.find((f) => f.name === fieldName);
      if (computed) {
        lines.push(...this.renderComputedField(computed).map((l) => `${extraIndent}${l}`));
        continue;
      }
      const blob = blobFields.find((f) => f.name === fieldName);
      if (blob) {
        lines.push(...this.renderBlobControl(blob, `${indent}  `));
      }
    }
    lines.push(isCollapsible ? `${indent}</details>` : `${indent}</section>`);
    return lines;
  }

  private renderBlobControl(f: FieldMetadata, indent = '      '): string[] {
    const label = f.displayName ?? toTitleCase(f.name);
    const contentTypes = f.blob?.contentTypes ?? [];
    const accept = contentTypes.length > 0 ? ` accept="${contentTypes.join(',')}"` : '';
    const pascal = f.name.charAt(0).toUpperCase() + f.name.slice(1);
    const result: string[] = [];

    result.push(`${indent}<div class="form-group">`);
    result.push(`${indent}  <label for="${f.name}" class="exeris-label">${label}</label>`);
    result.push(`${indent}  <input id="${f.name}" data-testid="field-${f.name}" type="file"${accept} (change)="on${pascal}Change($event)" class="exeris-input mt-1">`);
    result.push(`${indent}</div>`);
    return result;
  }

  private renderFormControl(f: FieldRenderModel): string[] {
    const { label, control, inputType, inputMode, enumType: enumTypeName } = f.form;
    const requiredMark = f.form.required ? '<span class="text-red-500" aria-hidden="true">*</span>' : '';
    const binding = `[formField]="form.${f.name}"`;
    const state = `form.${f.name}()`;
    const errorClass = `[class.exeris-input-error]="${state}.invalid() && ${state}.touched()"`;
    const result: string[] = [];

    result.push('      <div class="form-group">');

    if (control === 'select') {
      result.push(`        <label for="${f.name}" class="exeris-label">${label} ${requiredMark}</label>`);
      result.push(`        <select id="${f.name}" data-testid="field-${f.name}" ${binding} class="exeris-select mt-1" ${errorClass}>`);
      result.push('          <option value="">Select...</option>');
      result.push(`          @for (value of ${enumTypeName}Values; track value) {`);
      result.push(`            <option [value]="value">{{ ${enumTypeName}DisplayNames[value] }}</option>`);
      result.push('          }');
      result.push('        </select>');
    } else if (control === 'picker') {
      result.push(`        <label for="${f.name}" class="exeris-label">${label} ${requiredMark}</label>`);
      result.push(`        <select id="${f.name}" data-testid="field-${f.name}" ${binding} class="exeris-select mt-1" ${errorClass} [attr.aria-describedby]="${pickerTruncatedName(f.name)}() ? 'options-truncated-${f.name}' : null">`);
      result.push(f.form.required
        ? '          <option value="" disabled>Select...</option>'
        : '          <option value="">—</option>');
      result.push(`          @if (${pickerUnlistedName(f.name)}()) {`);
      result.push(`            <option [value]="${state}.value()">{{ ${state}.value() }}</option>`);
      result.push('          }');
      result.push(`          @for (option of ${pickerOptionsName(f.name)}(); track option.value) {`);
      result.push('            <option [value]="option.value">{{ option.label }}</option>');
      result.push('          }');
      result.push('        </select>');
      result.push(`        @if (${pickerErrorName(f.name)}()) {`);
      result.push(`          <p role="alert" class="exeris-error-text" data-testid="options-error-${f.name}">{{ ${pickerErrorName(f.name)}() }}</p>`);
      result.push('        }');
      result.push(`        @if (${pickerTruncatedName(f.name)}()) {`);
      result.push(`          <p id="options-truncated-${f.name}" class="exeris-help-text" data-testid="options-truncated-${f.name}">Showing the first ${MAX_PAGE_SIZE} options only.</p>`);
      result.push('        }');
    } else if (control === 'checkbox') {
      result.push('        <div class="flex items-center gap-2">');
      result.push(`          <input id="${f.name}" data-testid="field-${f.name}" type="checkbox" ${binding} class="exeris-checkbox">`);
      result.push(`          <label for="${f.name}" class="exeris-label">${label} ${requiredMark}</label>`);
      result.push('        </div>');
    } else {
      const inputExtra = inputMode ? ` inputmode="${inputMode}"` : '';
      result.push(`        <label for="${f.name}" class="exeris-label">${label} ${requiredMark}</label>`);
      result.push(`        <input id="${f.name}" data-testid="field-${f.name}" type="${inputType}" ${binding} class="exeris-input mt-1" ${errorClass}${inputExtra}>`);
    }
    result.push(`        @if (${state}.invalid() && ${state}.touched()) {`);
    result.push(`          <p class="exeris-error-text" data-testid="error-${f.name}">`);
    result.push(`            @if (${state}.getError('required')) { <span>${label} is required.</span> }`);
    result.push(`            @if (${state}.getError('pattern') || ${state}.getError('parse')) { <span>Invalid format.</span> }`);
    result.push(`            @if (${state}.getError('minLength')) { <span>Too short.</span> }`);
    result.push(`            @if (${state}.getError('maxLength')) { <span>Too long.</span> }`);
    result.push(`            @if (${state}.getError('min')) { <span>Too low.</span> }`);
    result.push(`            @if (${state}.getError('max')) { <span>Too high.</span> }`);
    result.push('          </p>');
    result.push('        }');
    result.push('      </div>');

    if (!f.form.inCreate) {
      return [
        '      @if (editMode()) {',
        ...result.map((line) => `  ${line}`),
        '      }',
      ];
    }
    return result;
  }

  private renderComputedField(f: FieldRenderModel): string[] {
    const label = f.form.label;
    const dependsOn = f.form.computedFrom.join(', ');
    const result: string[] = [];

    result.push('      <div class="form-group">');
    result.push(`        <label for="${f.name}" class="exeris-label">${label} <span class="text-xs text-gray-500">(Auto)</span></label>`);
    result.push(`        <input id="${f.name}" data-testid="field-${f.name}" type="${f.form.inputType}" [value]="${computedSignalName(f.name)}() ?? ''" readonly class="exeris-input mt-1 bg-[rgb(var(--exeris-bg-tertiary))] cursor-not-allowed opacity-75">`);
    if (dependsOn) {
      result.push(`        <p class="exeris-help-text">Computed from: ${dependsOn}</p>`);
    }
    result.push('      </div>');
    return result;
  }
}

/**
 * `allDomains` is the app's domain set: a foreign key whose target is in it, with a generated
 * service, is picked from the target's records.
 */
export function generateForm(
  metadata: DomainMetadata,
  config: GeneratorConfig,
  enums: EnumMetadata[] = [],
  allDomains: DomainMetadata[] = [metadata],
): GeneratedFile | null {
  const generator = new FormGenerator();
  const context: GeneratorContext = { config, backend: config.backend ?? 'KERNEL', allDomains, enums };
  return generator.generate(metadata, context);
}




/** The validation rules of a form schema, and the `@angular/forms/signals` functions they call. */
interface FormValidation {
  readonly rules: string[];
  readonly used: ReadonlySet<string>;
}

/**
 * The schema rules `FieldMetadata` declares, field by field in declaration order, each field's
 * rules in the order required, minLength, maxLength, pattern, min, max, disabled.
 *
 * - `required` is not applied to a checkbox. Signal Forms counts `false` as empty, which would
 *   force the box to be ticked; a required boolean only has to hold a boolean, which it always does.
 * - length and pattern apply to text controls only: a number control holds a number, and a
 *   length or pattern constraint is declared on character sequences.
 * - min and max bound a number control directly. A text control holding a decimal string is bounded
 *   by its parsed value; a blank or unparseable value passes.
 * - `disabled` applies to a field with `inUpdate = false` while the form edits, and to a field with
 *   `inCreate = false` while it creates. A disabled field takes no input and is not validated, so a
 *   required field the current mode does not offer never blocks it.
 */
function formValidation(fields: readonly FieldRenderModel[]): FormValidation {
  const rules: string[] = [];
  const used = new Set<string>();
  const add = (rule: string, ...fns: string[]): void => {
    rules.push(rule);
    for (const fn of fns) used.add(fn);
  };
  for (const { name, form, field: f } of fields) {
    const path = `path.${name}`;
    if (f.required && form.value !== 'boolean') add(`required(${path});`, 'required');
    if (form.value === 'text') {
      if (f.minLength) add(`minLength(${path}, ${f.minLength});`, 'minLength');
      if (f.maxLength) add(`maxLength(${path}, ${f.maxLength});`, 'maxLength');
      if (f.pattern) add(`pattern(${path}, /${f.pattern}/);`, 'pattern');
      if (f.min !== undefined) {
        add(`validate(${path}, ({ value }) => (parseFloat(value()) < ${f.min} ? minError(${f.min}) : undefined));`, 'validate', 'minError');
      }
      if (f.max !== undefined) {
        add(`validate(${path}, ({ value }) => (parseFloat(value()) > ${f.max} ? maxError(${f.max}) : undefined));`, 'validate', 'maxError');
      }
    } else if (form.value === 'number') {
      if (f.min !== undefined) add(`min(${path}, ${f.min});`, 'min');
      if (f.max !== undefined) add(`max(${path}, ${f.max});`, 'max');
    }
    if (!form.inUpdate) add(`disabled(${path}, { when: () => this.editMode() });`, 'disabled');
    if (!form.inCreate) add(`disabled(${path}, { when: () => !this.editMode() });`, 'disabled');
  }
  return { rules, used };
}

/**
 * The `@angular/forms/signals` imports of a form, in a fixed order. Every one is stable in the
 * Angular major the emitted `package.json` pins (ADR-093): no experimental or developer-preview
 * symbol is emitted.
 */
const SIGNAL_FORMS_IMPORT_ORDER = [
  'form', 'FormField', 'submit',
  'required', 'minLength', 'maxLength', 'pattern', 'min', 'max', 'validate', 'minError', 'maxError',
  'disabled',
] as const;

function signalFormsImports(validation: FormValidation): string[] {
  const always = new Set(['form', 'FormField', 'submit']);
  return SIGNAL_FORMS_IMPORT_ORDER.filter((name) => always.has(name) || validation.used.has(name));
}

/** `fullName` to `FullName`, the suffix of a computed field's member names. */
function memberSuffix(name: string): string {
  return toTitleCase(name).replace(/ /g, '');
}

/**
 * The `pickerOptions` helper of a form with foreign-key selects. Given the key every target shares,
 * it reads that property; without one, each call passes its target's key.
 */
function pickerOptionsHelper(sharedKey: string | undefined): string[] {
  const read = (row: string): string => (sharedKey === undefined ? `${row}[key]` : `${row}.${sharedKey}`);
  const signature = sharedKey === undefined
    ? [
        'function pickerOptions<K extends string, T extends { [P in K]?: unknown }>(',
        '  page: { content: T[] } | undefined,',
        '  key: K,',
      ]
    : [
        `function pickerOptions<T extends { ${sharedKey}?: unknown }>(`,
        '  page: { content: T[] } | undefined,',
      ];
  const doc = sharedKey === undefined
    ? [
        ' * The options of a foreign-key select: one per record of the page the list route answered that',
        ' * has a `key`, valued by it and labelled by `label` (the relationship\'s display field), or by',
        ' * the key when that is empty.',
      ]
    : [
        ' * The options of a foreign-key select: one per record of the page the list route answered that',
        ' * has an id, valued by the id and labelled by `label` (the relationship\'s display field), or by',
        ' * the id when that is empty.',
      ];
  return [
    '/**',
    ...doc,
    ' */',
    ...signature,
    `  label: (row: T) => unknown = (row) => ${read('row')},`,
    '): { value: string; label: string }[] {',
    '  const rows = page?.content ?? [];',
    `  return rows.filter((row) => ${read('row')} != null && ${read('row')} !== '').map((row) => {`,
    `    const value = String(${read('row')});`,
    '    const text = label(row);',
    "    return { value, label: text == null || String(text) === '' ? value : String(text) };",
    '  });',
    '}',
  ];
}

/** The options signal of a foreign-key select. */
function pickerOptionsName(name: string): string {
  return `${name}Options`;
}

/** The message of a foreign-key select's failed options request, or `null`. */
function pickerErrorName(name: string): string {
  return `${name}OptionsError`;
}

/** Whether a foreign-key select's target has rows beyond the page the select lists. */
function pickerTruncatedName(name: string): string {
  return `${name}OptionsTruncated`;
}

/** Whether a foreign-key select's value is missing from its options, which then still show it. */
function pickerUnlistedName(name: string): string {
  return `${name}Unlisted`;
}

function computedSignalName(name: string): string {
  return `computed${memberSuffix(name)}`;
}
