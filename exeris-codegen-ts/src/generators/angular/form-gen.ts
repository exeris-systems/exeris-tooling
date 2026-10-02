/**
 * Angular Form Generator
 * Generates Angular 22+ form components with Signals from domain metadata.
 */

import type { DomainMetadata, FieldMetadata } from '../../models/domain-model.js';
import { modelTypeName } from '../../models/model-naming.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import type { GeneratorConfig } from '../../config.js';
import type { CodeGenerator, GeneratedFile, GeneratorContext } from '../../core/generator-registry.js';
import type { BackendType } from '../../core/backend-strategy.js';
import { outPath } from '../../core/paths.js';
import { updateVersionField, viewSystemFieldNames } from '../api/type-gen.js';
import { tsSingleQuoted } from './ts-literal.js';

export { GeneratedFile };

export class FormGenerator implements CodeGenerator {
  readonly name = 'FormGenerator';
  readonly artifactType = 'FORM' as const;
  readonly supportedBackends: BackendType[] = [];
  readonly priority = 20;

  generate(domain: DomainMetadata, context: GeneratorContext): GeneratedFile | null {
    if (domain.internalApi?.hidden) {
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
    // Helper functions
    const getEnumTypeName = (fqcn: string): string => {
      const parts = fqcn.split('.');
      return parts[parts.length - 1];
    };

    const toTitleCase = (value: string): string => {
      return value.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase());
    };

    // Which fields carry a boolean DTO type. Keyed off DslMapper for the same reason
    // isNumericField is (see below): a test on the literal 'java.lang.Boolean' misses a
    // primitive `boolean`, whose DTO type is `boolean` just the same.
    const isBooleanField = (field: FieldMetadata): boolean => {
      const ts = DslMapper.mapType(field.type).tsType;
      return ts === 'boolean' || ts === 'boolean | null';
    };

    const mapInputType = (field: FieldMetadata): string => {
      // @Field.dataType — front-presentation hint (Wave 1A, additive). For an
      // editable form control the facet maps to the closest native input type:
      // url -> type="url"; currency/percent -> a numeric input. The default path
      // (no dataType, or any other value) is unchanged.
      if (field.dataType === 'url') return 'url';
      if (field.dataType === 'currency' || field.dataType === 'percent') return 'number';
      const type = field.type;
      if (isBooleanField(field)) return 'checkbox';
      if (type === 'java.lang.Integer' || type === 'java.lang.Long' || type === 'java.lang.Double' || type === 'java.lang.Float') return 'number';
      if (type === 'java.time.Instant' || type === 'java.time.LocalDateTime') return 'datetime-local';
      if (type === 'java.time.LocalDate') return 'date';
      return 'text';
    };

    // Which create fields carry a numeric DTO type. Reactive-form controls are seeded
    // with '' (string), so getRawValue() is statically string-typed even though
    // <input type="number"> yields a number at runtime. We coerce these fields explicitly
    // on submit so the payload is both type-correct and semantically a number.
    //
    // The predicate MUST mirror the DTO type emitted by type-gen, i.e.
    // DslMapper's tsType — NOT a hand-rolled java-type list. BigDecimal/BigInteger
    // deliberately map to `string` (precision preservation, rendered as a text
    // input), so coercing them to Number() would BOTH lose precision AND reintroduce
    // a TS2352 (number→string DTO). Keying off ts? === 'number' keeps coercion in
    // lock-step with whatever DslMapper decides is a JS number.
    const isNumericField = (field: FieldMetadata): boolean => {
      const ts = DslMapper.mapType(field.type).tsType;
      return ts === 'number' || ts === 'number | null';
    };

    const isLifecycleField = (name: string): boolean => {
      return ['active', 'onboardingStatus', 'onboardingStartedAt', 'onboardingCompletedAt', 'hierarchyLevel', 'parentTenantId', 'createdAt', 'updatedAt', 'deleted', 'version'].includes(name);
    };

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
    // No control for a system field (type-gen's viewSystemFieldNames). That includes a UNIVERSE
    // entity's shared-scope key, which is server-owned like its tenant: the repository stamps it
    // from the bound storage context and the create DTO omits it, so the form never sends it.
    const systemNames = viewSystemFieldNames(domain);
    const isSystemField = (name: string): boolean => systemNames.includes(name);

    const isEnumField = (field: FieldMetadata): boolean => {
      // Check explicit enumType first
      if (field.enumType) return true;
      // Fallback: detect enum from type pattern (e.g., "eu.exeris.foundation.domain.TenantPlan")
      const type = field.type;
      return type.includes('.') && !type.startsWith('java.') && !type.includes('Entity') && !type.includes('DTO');
    };

    const getEnumTypeFromField = (field: FieldMetadata): string | null => {
      // Always the simple enum name — the FQN (e.g. "com.shop.OrderStatus") is what
      // the metadata carries, but it must be stripped to the simple name for use as
      // a TS identifier in imports and type references (mirrors type-gen / service-gen).
      const raw = field.enumType ?? (isEnumField(field) ? field.type : null);
      if (!raw) return null;
      const parts = raw.split('.');
      return parts[parts.length - 1];
    };

    const entityName = domain.entityName;

    const modelName = modelTypeName(entityName);
    const kebabName = DslMapper.toKebabCase(entityName);
    const noun = tsSingleQuoted((domain.displayName ?? entityName).toLowerCase());
    // The literal 'id', deliberately, not systemFields.primaryKeyField. Nothing in the pipeline
    // honours that override: KernelFlywayGenerator emits `id UUID PRIMARY KEY` unconditionally,
    // KernelRepositoryGenerator's WHERE clause is the constant " WHERE id = ?", every by-id
    // handler binds the {id} path variable, and the processor says so outright ("generators leave
    // the primary key as the literal id"). Reading it here would make this the only layer that
    // honours it, and the emitted app would then request the wrong identifier.
    const idField = 'id';

    const fields = domain.fields;
    const createFields = fields.filter((f) =>
      f.inCreate !== false &&
      f.hidden !== true &&
      f.readOnly !== true &&
      !f.computed &&  // Exclude computed fields
      !isLifecycleField(f.name) &&
      !isSystemField(f.name)
    );

    // Collect computed fields that depend on create fields
    const computedFields = fields.filter((f) =>
      f.computed &&
      f.inCreate !== false &&
      !isLifecycleField(f.name) &&
      !isSystemField(f.name)
    );

    const lines: string[] = [];
    lines.push('/**');
    lines.push(` * ${entityName} Form Component`);
    lines.push(' * Generated by @exeris/codegen-ts');
    lines.push(' * Uses Angular 22 standalone components + signals');
    lines.push(' */');
    lines.push('');
    const plural = DslMapper.routePlural(entityName);

    lines.push("import { Component, ChangeDetectionStrategy, input, output, signal, computed, effect, inject } from '@angular/core';");
    lines.push("import { rxResource } from '@angular/core/rxjs-interop';");
    lines.push("import { CommonModule } from '@angular/common';");
    lines.push("import { FormsModule, ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';");
    lines.push("import { ActivatedRoute, Router } from '@angular/router';");
    lines.push(`import { ${modelName}, ${modelName}Create, ${modelName}Update, ${entityName}Service } from '../services/${kebabName}.service';`);
    lines.push("import { httpErrorMessage } from '../core/http-error';");

    // Collect enum types used in create fields
    const enumTypes = new Set<string>();
    for (const f of createFields) {
      const enumType = getEnumTypeFromField(f);
      if (enumType) {
        enumTypes.add(enumType);
      }
    }

    if (enumTypes.size > 0) {
      const enumImports = Array.from(enumTypes).flatMap(e => [e, `${e}DisplayNames`]);
      lines.push(`import { ${enumImports.join(', ')} } from '../types/enums';`);
    }

    lines.push('');
    lines.push('@Component({');
    lines.push(`  selector: 'app-${kebabName}-form',`);
    lines.push('  standalone: true,');
    lines.push('  imports: [CommonModule, FormsModule, ReactiveFormsModule],');
    lines.push('  changeDetection: ChangeDetectionStrategy.OnPush,');
    lines.push('  template: `');
    // Loading and error of the by-id load mirror the detail view's markup.
    lines.push('    @if (isLoading()) {');
    lines.push('      <div class="animate-pulse space-y-4 mb-6" role="status" aria-label="Loading...">');
    lines.push('        <div class="h-4 bg-gray-200 dark:bg-gray-700 rounded w-1/2"></div>');
    lines.push('      </div>');
    lines.push('    } @else if (loadError()) {');
    lines.push('      <div role="alert" class="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-6 mb-6">');
    lines.push('        <p class="text-red-700 dark:text-red-300">{{ loadError() }}</p>');
    lines.push('        <button type="button" (click)="reload()" class="mt-4 text-sm font-medium text-red-600">Try again</button>');
    lines.push('      </div>');
    lines.push('    }');
    lines.push('    <form [formGroup]="form" (ngSubmit)="onSubmit()" class="space-y-6">');

    for (const f of createFields) {
      const label = f.displayName ?? toTitleCase(f.name);
      const requiredMark = f.required ? '<span class="text-red-500" aria-hidden="true">*</span>' : '';
      const disabledBinding = f.readOnly ? ' [disabled]="true"' : '';
      const readonlyBinding = f.readOnly ? ' [readonly]="true"' : '';

      lines.push('      <div class="form-group">');

      const enumTypeName = getEnumTypeFromField(f);
      if (enumTypeName) {
        lines.push(`        <label for="${f.name}" class="block text-sm font-medium text-gray-700 dark:text-gray-300">${label} ${requiredMark}</label>`);
        lines.push(`        <select id="${f.name}" data-testid="field-${f.name}" formControlName="${f.name}" class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-exeris-primary focus:ring-exeris-primary dark:bg-gray-800 dark:border-gray-600 dark:text-white sm:text-sm"${disabledBinding}>`);
        lines.push('          <option value="">Select...</option>');
        lines.push(`          @for (value of ${enumTypeName}Values; track value) {`);
        lines.push(`            <option [value]="value">{{ ${enumTypeName}DisplayNames[value] }}</option>`);
        lines.push('          }');
        lines.push('        </select>');
      } else if (mapInputType(f) === 'checkbox') {
        lines.push('        <div class="flex items-center gap-2">');
        lines.push(`          <input id="${f.name}" data-testid="field-${f.name}" type="checkbox" formControlName="${f.name}" class="h-4 w-4 rounded border-gray-300 text-exeris-primary focus:ring-exeris-primary"${disabledBinding}>`);
        lines.push(`          <label for="${f.name}" class="text-sm text-gray-700 dark:text-gray-300">${label} ${requiredMark}</label>`);
        lines.push('        </div>');
      } else {
        const inputType = mapInputType(f);
        const inputExtra = inputType === 'number' ? ' inputmode="decimal"' : '';
        lines.push(`        <label for="${f.name}" class="block text-sm font-medium text-gray-700 dark:text-gray-300">${label} ${requiredMark}</label>`);
        lines.push(`        <input id="${f.name}" data-testid="field-${f.name}" type="${inputType}" formControlName="${f.name}" class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-exeris-primary focus:ring-exeris-primary dark:bg-gray-800 dark:border-gray-600 dark:text-white sm:text-sm"${disabledBinding}${inputExtra}${readonlyBinding}>`);
      }
      lines.push(`        <p class="mt-1 text-xs text-gray-500" *ngIf="form.get('${f.name}')?.invalid && form.get('${f.name}')?.touched" data-testid="error-${f.name}">`);
      lines.push(`          @if (form.get('${f.name}')?.errors?.['required']) { <span>${label} is required.</span> }`);
      lines.push(`          @if (form.get('${f.name}')?.errors?.['pattern']) { <span>Invalid format.</span> }`);
      lines.push(`          @if (form.get('${f.name}')?.errors?.['minlength']) { <span>Too short.</span> }`);
      lines.push(`          @if (form.get('${f.name}')?.errors?.['maxlength']) { <span>Too long.</span> }`);
      lines.push(`          @if (form.get('${f.name}')?.errors?.['min']) { <span>Too low.</span> }`);
      lines.push(`          @if (form.get('${f.name}')?.errors?.['max']) { <span>Too high.</span> }`);
      lines.push('        </p>');
      lines.push('      </div>');
    }

    // Render computed fields (readonly, displayed for info)
    for (const f of computedFields) {
      const label = f.displayName ?? toTitleCase(f.name);
      const dependsOn = (f.computedFrom ?? []).join(', ');

      lines.push('      <div class="form-group">');
      lines.push(`        <label for="${f.name}" class="block text-sm font-medium text-gray-700 dark:text-gray-300">${label} <span class="text-xs text-gray-500">(Auto)</span></label>`);
      lines.push(`        <input id="${f.name}" data-testid="field-${f.name}" type="${mapInputType(f)}" formControlName="${f.name}" readonly class="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-exeris-primary focus:ring-exeris-primary dark:bg-gray-800 dark:border-gray-600 dark:text-white sm:text-sm bg-gray-100 dark:bg-gray-700 cursor-not-allowed opacity-75">`);
      if (dependsOn) {
        lines.push(`        <p class="mt-1 text-xs text-gray-500">Computed from: ${dependsOn}</p>`);
      }
      lines.push('      </div>');
    }

    lines.push('      @if (error()) {');
    lines.push('        <div role="alert" data-testid="submit-error" class="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300">{{ error() }}</div>');
    lines.push('      }');
    if (version) {
      // The server answers a stale update with 409 and no body; the only recovery is to load
      // the row as it now stands, which also picks up its current version.
      lines.push('      @if (conflict()) {');
      lines.push('        <div role="alert" data-testid="conflict-message" class="rounded-md border border-amber-300 bg-amber-50 p-4 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200">');
      lines.push('          <p>This record was changed by someone else. Reload to see the latest version.</p>');
      lines.push('          <button type="button" (click)="reload()" data-testid="reload-button" class="mt-2 rounded-md bg-white px-3 py-1.5 text-sm font-medium text-amber-800 shadow-sm border border-amber-300 hover:bg-amber-100 dark:bg-gray-800 dark:text-amber-200 dark:border-amber-700">Reload</button>');
      lines.push('        </div>');
      lines.push('      }');
    }
    lines.push('      <div class="flex justify-end gap-3 pt-4 border-t border-gray-200 dark:border-gray-700">');
    lines.push('        <button type="button" (click)="onCancel()" data-testid="cancel-button" class="rounded-md bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm border border-gray-300 hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-300 dark:border-gray-600">Cancel</button>');
    lines.push('        <button type="submit" [disabled]="form.invalid || saving() || (editMode() && !current())" data-testid="submit-button" class="rounded-md bg-exeris-primary px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-exeris-primary-hover disabled:opacity-50">');
    lines.push(`          @if (saving()) { Saving... } @else { {{ editMode() ? 'Update' : 'Create' }} ${entityName} }`);
    lines.push('        </button>');
    lines.push('      </div>');
    lines.push('    </form>');
    lines.push('  `,');
    lines.push('})');

    lines.push(`export class ${entityName}FormComponent {`);
    lines.push('  private readonly fb = inject(FormBuilder);');
    lines.push(`  private readonly service = inject(${entityName}Service);`);
    lines.push('  private readonly router = inject(Router);');
    // The form is a page when its activated route's component is this class; embedded in a
    // host template it sees the host's route instead (or none), and leaves the next step to
    // the host listening on `saved` / `cancelled`.
    lines.push(`  private readonly routed = inject(ActivatedRoute, { optional: true })?.component === ${entityName}FormComponent;`);
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
    lines.push('');
    lines.push('  private readonly entityResource = rxResource({');
    lines.push('    params: () => this.id(),');
    lines.push('    stream: ({ params }) => this.service.findById(params),');
    lines.push('  });');
    lines.push('');
    // An embedding host's `entity` wins over the by-id load; an `id` always means edit.
    lines.push(`  readonly current = computed<${modelName} | null>(() => this.entity() ?? this.entityResource.value() ?? null);`);
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
    if (version) {
      lines.push('  readonly conflict = signal(false);');
      lines.push('');
      lines.push(`  /** The ${version.name} the edited entity was loaded at; the update sends it back as the expected version. */`);
      const versionType = version.tsType.endsWith('| null') ? version.tsType : `${version.tsType} | null`;
      lines.push(`  private readonly loadedVersion = signal<${versionType}>(null);`);
    }
    lines.push('');
    lines.push('  readonly form = this.fb.group({');
    for (const f of createFields) {
      const validators: string[] = [];
      if (f.required) validators.push('Validators.required');
      if (f.minLength) validators.push(`Validators.minLength(${f.minLength})`);
      if (f.maxLength) validators.push(`Validators.maxLength(${f.maxLength})`);
      if (f.pattern) validators.push(`Validators.pattern(/${f.pattern}/)`);
      if (f.min !== undefined) validators.push(`Validators.min(${f.min})`);
      if (f.max !== undefined) validators.push(`Validators.max(${f.max})`);
      const validatorsArray = validators.length ? `[${validators.join(', ')}]` : '[]';
      // A checkbox has no empty state, so a boolean control can be seeded with a real
      // boolean and needs no submit-time coercion — unlike a numeric control, which must
      // seed '' to keep "blank" distinguishable from 0 and is coerced below. A declared
      // defaultValue goes in unquoted for the same reason: `'true'` is a string.
      const defaultValue = isBooleanField(f)
        ? (String(f.defaultValue ?? 'false').trim().toLowerCase() === 'true' ? 'true' : 'false')
        : (f.defaultValue ? `'${f.defaultValue}'` : "''");
      lines.push(`    ${f.name}: [${defaultValue}, ${validatorsArray}],`);
    }
    lines.push('  });');
    lines.push('');
    lines.push('  constructor() {');

    // Generate effects for computed fields
    if (computedFields.length > 0) {
      for (const cf of computedFields) {
        const deps = cf.computedFrom ?? [];
        if (deps.length > 0) {
          lines.push(`    // Auto-sync ${cf.name} based on ${deps.join(', ')}`);
          lines.push('    effect(() => {');
          lines.push('      const values = {');
          for (const dep of deps) {
            lines.push(`        ${dep}: this.form.get('${dep}')?.value,`);
          }
          lines.push('      };');
          lines.push(`      const computed${toTitleCase(cf.name)} = this.compute${toTitleCase(cf.name)}(values);`);
          lines.push(`      this.form.get('${cf.name}')?.setValue(computed${toTitleCase(cf.name)}, { emitEvent: false });`);
          lines.push('    });');
          lines.push('');
        }
      }
    }

    lines.push('    effect(() => {');
    lines.push('      const entity = this.current();');
    lines.push('      if (entity && this.editMode()) {');
    lines.push('        this.form.patchValue(entity as any);');
    if (version) {
      lines.push(`        this.loadedVersion.set(${readVersion('entity')});`);
    }
    lines.push('      }');
    lines.push('    });');
    lines.push('  }');
    lines.push('');
    lines.push('  onSubmit(): void {');
    lines.push('    if (this.form.invalid) {');
    lines.push('      this.form.markAllAsTouched();');
    lines.push('      return;');
    lines.push('    }');
    // Edit mode without a loaded entity would overwrite a row it never read.
    lines.push('    const current = this.current();');
    lines.push('    if (this.editMode() && !current) {');
    lines.push('      return;');
    lines.push('    }');
    lines.push('');
    lines.push('    this.saving.set(true);');
    lines.push('    this.error.set(null);');
    if (version) lines.push('    this.conflict.set(false);');
    lines.push('');
    const numericCreateFields = createFields.filter(isNumericField);
    if (numericCreateFields.length > 0) {
      // Coerce string-typed numeric controls to numbers so the payload matches
      // the *Create/*Update DTO type.
      lines.push('    const raw = this.form.getRawValue();');
      lines.push('    const data = {');
      lines.push('      ...raw,');
      for (const f of numericCreateFields) {
        lines.push(`      ${f.name}: raw.${f.name} === null || raw.${f.name} === '' ? null : Number(raw.${f.name}),`);
      }
      lines.push('    };');
    } else {
      lines.push('    const data = this.form.getRawValue();');
    }
    const updatePayload = version
      ? `{ ...data, ${version.name}: this.loadedVersion() } as ${modelName}Update`
      : `data as ${modelName}Update`;
    lines.push(`    const request$ = this.editMode() && current ? this.service.update(String(current.${idField}), ${updatePayload}) : this.service.create(data as ${modelName}Create);`);
    lines.push('');
    lines.push('    request$.subscribe({');
    lines.push('      next: (result) => {');
    lines.push('        this.saving.set(false);');
    lines.push('        this.saved.emit(result);');
    lines.push('        if (this.routed) {');
    lines.push(`          void this.router.navigate(['/${plural}', String(result.${idField})]);`);
    lines.push('        }');
    lines.push('      },');
    lines.push('      error: (err) => {');
    lines.push('        this.saving.set(false);');
    if (version) {
      lines.push("        if (this.editMode() && err?.status === 409) {");
      lines.push('          this.conflict.set(true);');
      lines.push('          return;');
      lines.push('        }');
    }
    lines.push(`        this.error.set(httpErrorMessage(err, { entity: '${noun}', action: 'save' }));`);
    lines.push('      },');
    lines.push('    });');
    lines.push('  }');
    lines.push('');
    lines.push('  onCancel(): void {');
    lines.push('    this.cancelled.emit();');
    lines.push('    if (this.routed) {');
    // Back to the entity being edited, or to the list when nothing was.
    lines.push('      const id = this.id();');
    lines.push(`      void this.router.navigate(id !== undefined ? ['/${plural}', id] : ['/${plural}']);`);
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
      lines.push('        this.form.reset(fresh as any);');
      lines.push(`        this.loadedVersion.set(${readVersion('fresh')});`);
      lines.push('      },');
      lines.push(`      error: (err) => this.error.set(httpErrorMessage(err, { entity: '${noun}', action: 'load' })),`);
      lines.push('    });');
      lines.push('  }');
    }

    // Generate compute methods for computed fields
    for (const cf of computedFields) {
      const deps = cf.computedFrom ?? [];
      const methodName = `compute${toTitleCase(cf.name)}`;
      lines.push('');
      lines.push(`  private ${methodName}(values: { ${deps.map(d => `${d}: any`).join(', ')} }): any {`);
      lines.push('    // TODO: Implement computation logic');
      lines.push(`    // Depends on: ${deps.join(', ')}`);
      lines.push('    return null;');
      lines.push('  }');
    }

    lines.push('}');

    return lines.join('\n');
  }
}

export function generateForm(metadata: DomainMetadata, config: GeneratorConfig): GeneratedFile | null {
  const generator = new FormGenerator();
  const context: GeneratorContext = { config, backend: config.backend ?? 'KERNEL', allDomains: [metadata], enums: [] };
  return generator.generate(metadata, context);
}



