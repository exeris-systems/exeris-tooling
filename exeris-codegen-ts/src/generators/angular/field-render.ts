/**
 * The field-render model: how one entity field is rendered in the list, the detail view and the form.
 *
 * `list-gen`, `detail-gen` and `form-gen` obtain every per-field render decision here — whether the
 * field is shown, its label, list cell, detail display type, form control, input type, enum and
 * foreign-key link — and only print what it resolves. None of the three keeps a control or format
 * mapping of its own (ADR-047 Amendment 1, ADR-093 obligation 4).
 *
 * The model is internal to codegen-ts: it is not part of `DomainMetadata`, it is never serialised,
 * and it reads only what `FieldMetadata`, the entity's system-field flags and its relationships
 * already carry. Resolution is a pure function of its inputs, so the same metadata always yields
 * the same model.
 *
 * The detail view resolves its display type through the shared rules below (`enumTypeOf`,
 * `isBooleanType`, `temporalKindOf`, `isNumericType`), which are the rules every surface converges
 * on. Where a surface still resolves by its own rule — the form's enum test and number/date
 * inputs, the list's `java.util.Date` date-time and its `BigDecimal` / `BigInteger` number cell —
 * the difference is listed in `docs/codegen-ts-track-plan.md`.
 *
 * The list's rules: a boolean or a number is any type the DTO carries as one, an enum is a type
 * the processor emitted an enum for (the only enums `types/enums` exports), and a date-time type
 * renders with its time.
 */

import type { DomainMetadata, FieldMetadata } from '../../models/domain-model.js';
import type { EnumMetadata } from '../../core/generator-registry.js';
import { DslMapper } from '../../models/dsl-mapper.js';
import { viewSystemFieldNames } from '../api/type-gen.js';
import { foreignKeyLinks } from './relationship-links.js';

/**
 * The per-field render facet from `@View`, the second input of the model.
 *
 * `never` while the processor leaves `ComponentNodeMetadata.field` null: no caller can pass one, so
 * the slot cannot be filled by anything but the facet. When the facet is extracted this becomes its
 * record (`ViewFieldMetadata`), read beside `FieldMetadata` by `resolveFieldRender` — the facet
 * refines this model, it is not a second rendering path.
 */
export type FieldRenderOverride = never;

/** What every field of one entity resolves against. */
export interface FieldRenderContext {
  /** The entity's system fields (`viewSystemFieldNames`): no form control, no field-table row, no default column. */
  readonly systemFieldNames: readonly string[];
  /** Foreign-key fields mapped to the route prefix of their target's detail page (`foreignKeyLinks`). */
  readonly links: ReadonlyMap<string, string>;
  /**
   * The enums the processor emitted, which the app's enum module declares: a field whose type names
   * one is an enum (`enumTypeOf`), and the list takes its constants for the badges.
   */
  readonly enums?: readonly EnumMetadata[];
}

/** An enum the app's enum module (`types/enums.ts`) declares, as far as type resolution needs it. */
export interface KnownEnum {
  readonly name: string;
  readonly qualifiedName: string;
}

/** How a list cell renders its value. */
export type ListCellKind =
  | 'link' | 'boolean' | 'enum' | 'date' | 'datetime' | 'number' | 'currency' | 'percent' | 'url' | 'text';

/** The filter control a filterable list column gets, by what its cell holds. */
export type ListFilterKind = 'boolean' | 'enum' | 'text' | 'date-range' | 'number-range';

/**
 * The colour of an enum constant's badge. Without a per-constant colour in the metadata it is a
 * fixed palette taken in declaration order, so a constant keeps its colour across regenerations
 * until a constant is inserted before it.
 */
export type BadgeTone = 'blue' | 'violet' | 'teal' | 'amber' | 'pink' | 'slate';

const BADGE_TONES: readonly BadgeTone[] = ['blue', 'violet', 'teal', 'amber', 'pink', 'slate'];

/** The detail view's display type, the `type` of its emitted `FieldDisplay`. */
export type DetailDisplayType = 'text' | 'number' | 'boolean' | 'date' | 'datetime' | 'enum';

/** The `@Field.dataType` facets that render distinctly; any other value renders as the default. */
export type DataTypeFacet = 'currency' | 'percent' | 'url';

/** Where a field appears in the form: as an editable control, as a read-only computed input, or not at all. */
export type FormPlacement = 'control' | 'computed' | 'none';

/** The form control element a field binds to. */
export type FormControlKind = 'select' | 'checkbox' | 'input';

/** The DTO value kind of a form control, which decides its seed and its submit-time coercion. */
export type FormValueKind = 'boolean' | 'number' | 'text';

export interface FieldRenderModel {
  readonly name: string;
  /** The list header and detail row label: `displayName`, else the humanized name. */
  readonly label: string;
  /** A system field of the entity; it has no column, field-table row or control. */
  readonly system: boolean;
  /** Shown in the list's default columns and the detail field table: neither hidden nor a system field. */
  readonly displayed: boolean;
  /** The route prefix of the target's detail page, for a linkable foreign key. */
  readonly link?: string;
  /** Placeholder text. Carried by the field-level hints the 0.9 processor does not extract, so always absent. */
  readonly placeholder?: undefined;
  /** Help text. Carried by the field-level hints the 0.9 processor does not extract, so always absent. */
  readonly help?: undefined;
  readonly list: {
    readonly cell: ListCellKind;
    /** Numbers, amounts and percentages align right so their digits line up. */
    readonly align: 'left' | 'right';
    readonly sortable: boolean;
    readonly filterable: boolean;
    /** The filter control of a filterable field; `undefined` when the field is not filterable. */
    readonly filter?: ListFilterKind;
    /** The simple enum name, when the cell is an enum. */
    readonly enumType?: string;
    /** The enum's constants in declaration order with their badge tone, when the cell is an enum. */
    readonly enumValues?: readonly { readonly value: string; readonly tone: BadgeTone }[];
  };
  readonly detail: {
    readonly display: DetailDisplayType;
    /** The simple enum name, when the detail view treats the field as an enum. */
    readonly enumType?: string;
    readonly dataType?: DataTypeFacet;
  };
  readonly form: {
    readonly placement: FormPlacement;
    /** The control label: `displayName`, else the title-cased name. */
    readonly label: string;
    readonly control: FormControlKind;
    /** The `<input type>`; for a select it is still resolved, and a computed input uses it. */
    readonly inputType: string;
    readonly inputMode?: 'decimal';
    /** The simple enum name, when the form treats the field as an enum. */
    readonly enumType?: string;
    readonly required: boolean;
    readonly readOnly: boolean;
    readonly value: FormValueKind;
    /** The control's initial value, as a TypeScript expression. */
    readonly initialValue: string;
    readonly computedFrom: readonly string[];
  };
  /** The metadata the model was resolved from, for what is not rendering (validators). */
  readonly field: FieldMetadata;
}

/**
 * Fields the form never offers: lifecycle and tenancy state the server owns, whatever the entity's
 * system-field block says.
 */
const LIFECYCLE_FIELDS = new Set([
  'active', 'onboardingStatus', 'onboardingStartedAt', 'onboardingCompletedAt', 'hierarchyLevel',
  'parentTenantId', 'createdAt', 'updatedAt', 'deleted', 'version',
]);

const DATA_TYPE_FACETS = new Set<string>(['currency', 'percent', 'url']);

/** Calendar-date types: a day with no time and no zone, rendered with the `mediumDate` pipe format. */
const DATE_TYPES = new Set(['LocalDate']);

/** Instant-like types: a point in time, rendered with the `medium` pipe format. */
const DATETIME_TYPES = new Set(['Instant', 'LocalDateTime', 'OffsetDateTime', 'ZonedDateTime']);

/** `camelCase` to `Title Case`, the form's label and method-name casing. */
export function toTitleCase(value: string): string {
  return value.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase());
}

/**
 * The context every field of `domain` resolves against. `detailRouted` is whether detail views are
 * emitted: without a detail route no field links.
 */
export function fieldRenderContext(
  domain: DomainMetadata,
  allDomains: readonly DomainMetadata[],
  detailRouted: boolean,
  enums: readonly EnumMetadata[] = [],
): FieldRenderContext {
  return {
    systemFieldNames: viewSystemFieldNames(domain),
    links: foreignKeyLinks(domain, allDomains, detailRouted),
    enums,
  };
}

/** Resolves every field of `domain`, in declaration order. */
export function resolveFieldRenders(domain: DomainMetadata, context: FieldRenderContext): FieldRenderModel[] {
  return (domain.fields ?? []).map((field) => resolveFieldRender(field, context));
}

/** Resolves how one field renders in the list, the detail view and the form. */
export function resolveFieldRender(
  field: FieldMetadata,
  context: FieldRenderContext,
  override?: FieldRenderOverride,
): FieldRenderModel {
  void override;
  const system = context.systemFieldNames.includes(field.name);
  const link = context.links.get(field.name);
  const dataType = field.dataType !== undefined && DATA_TYPE_FACETS.has(field.dataType)
    ? (field.dataType as DataTypeFacet)
    : undefined;
  return {
    name: field.name,
    label: DslMapper.mapField(field).label,
    system,
    displayed: !field.hidden && !system,
    link,
    list: listRender(field, link, context.enums ?? []),
    detail: {
      display: detailDisplay(field, context.enums ?? []),
      enumType: enumTypeOf(field, context.enums ?? []),
      dataType,
    },
    form: formRender(field, system),
    field,
  };
}

/** Java simple names whose values carry a time of day as well as a date. */
const LIST_DATETIME_TYPES = new Set(['Instant', 'LocalDateTime', 'OffsetDateTime', 'ZonedDateTime', 'Date']);

/** Java simple names the DTO carries as a string to keep their precision, but which hold a number. */
const LIST_DECIMAL_TYPES = new Set(['BigDecimal', 'BigInteger']);

/**
 * The enum a field's type names, among those the processor emitted. Only these are exported by
 * `types/enums`, so a type that merely looks like an enum renders as text.
 */
function listEnum(field: FieldMetadata, enums: readonly EnumMetadata[]): EnumMetadata | undefined {
  const type = field.enumType ?? field.type;
  return enums.find((e) => e.qualifiedName === type) ?? enums.find((e) => e.name === type);
}

/**
 * The list cell. Booleans and numbers follow the DTO type type-gen emits, so a primitive and its
 * wrapper, simple or qualified, render alike. An explicit `format` decides date against date-time;
 * otherwise `LocalDate` is a date and the instant-like types are date-times.
 */
function listCell(field: FieldMetadata, link: string | undefined, isEnum: boolean): ListCellKind {
  if (link) return 'link';
  const ts = DslMapper.mapType(field.type).tsType;
  if (ts === 'boolean' || ts === 'boolean | null') return 'boolean';
  if (isEnum) return 'enum';
  if (field.format === 'date') return 'date';
  if (field.format === 'datetime') return 'datetime';
  if (field.dataType === 'currency') return 'currency';
  if (field.dataType === 'percent') return 'percent';
  if (field.dataType === 'url') return 'url';
  const simple = simpleName(field.type);
  if (simple === 'LocalDate') return 'date';
  if (LIST_DATETIME_TYPES.has(simple)) return 'datetime';
  if (ts === 'number' || ts === 'number | null' || LIST_DECIMAL_TYPES.has(simple)) return 'number';
  return 'text';
}

function listFilter(cell: ListCellKind): ListFilterKind {
  switch (cell) {
    case 'boolean': return 'boolean';
    case 'enum': return 'enum';
    case 'date':
    case 'datetime': return 'date-range';
    case 'number':
    case 'currency':
    case 'percent': return 'number-range';
    default: return 'text';
  }
}

function listRender(
  field: FieldMetadata,
  link: string | undefined,
  enums: readonly EnumMetadata[],
): FieldRenderModel['list'] {
  const enumMeta = link ? undefined : listEnum(field, enums);
  const cell = listCell(field, link, enumMeta !== undefined);
  const enumValues = cell === 'enum' && enumMeta
    ? [...enumMeta.values]
        .sort((a, b) => a.ordinal - b.ordinal)
        .map((v, i) => ({ value: v.name, tone: BADGE_TONES[i % BADGE_TONES.length] }))
    : undefined;
  return {
    cell,
    align: cell === 'number' || cell === 'currency' || cell === 'percent' ? 'right' : 'left',
    sortable: field.sortable,
    filterable: field.filterable,
    filter: field.filterable ? listFilter(cell) : undefined,
    enumType: cell === 'enum' ? enumMeta?.name : undefined,
    enumValues,
  };
}

function detailDisplay(field: FieldMetadata, enums: readonly KnownEnum[]): DetailDisplayType {
  if (enumTypeOf(field, enums)) return 'enum';
  if (isBooleanType(field.type)) return 'boolean';
  const temporal = temporalKindOf(field);
  if (temporal) return temporal;
  if (isNumericType(field.type)) return 'number';
  return 'text';
}

/**
 * The enum rule: the simple name of the enum a field holds, or `undefined` when it holds none.
 *
 * A field is an enum when it carries an explicit `enumType`, or when its type names an enum the
 * app's enum module declares — a qualified type by its qualified name, a simple type by its simple
 * name. The type's name is never guessed from: an enum the module does not declare cannot be
 * imported from it, and a type named like an enum (`…Status`) may be an entity or a record.
 */
export function enumTypeOf(field: FieldMetadata, enums: readonly KnownEnum[]): string | undefined {
  if (field.enumType) return simpleName(field.enumType);
  const type = field.type;
  const known = type.includes('.')
    ? enums.find((e) => e.qualifiedName === type)
    : enums.find((e) => e.name === type);
  return known?.name;
}

/** The boolean rule: the primitive, the wrapper and the qualified wrapper alike. */
export function isBooleanType(type: string): boolean {
  return type === 'boolean' || type === 'Boolean' || type === 'java.lang.Boolean';
}

/**
 * The temporal rule: `'date'` for a calendar date, `'datetime'` for a point in time, `undefined`
 * otherwise. An explicit `format` of `date` or `datetime` decides first; else the type's simple
 * name does — `LocalDate` is a date, `Instant`, `LocalDateTime`, `OffsetDateTime` and
 * `ZonedDateTime` are date-times. A date renders with the `mediumDate` pipe format and a date-time
 * with `medium`.
 */
export function temporalKindOf(field: FieldMetadata): 'date' | 'datetime' | undefined {
  if (field.format === 'date' || field.format === 'datetime') return field.format;
  const name = simpleName(field.type);
  if (DATE_TYPES.has(name)) return 'date';
  if (DATETIME_TYPES.has(name)) return 'datetime';
  return undefined;
}

/**
 * The number rule: the type's DTO type is a TypeScript `number`. `BigDecimal` and `BigInteger`
 * map to `string` for precision and are not numbers here.
 */
export function isNumericType(type: string): boolean {
  const ts = DslMapper.mapType(type).tsType;
  return ts === 'number' || ts === 'number | null';
}

/** The form's enum test: an explicit `enumType`, else a qualified non-JDK type that names no entity or DTO. */
function formEnumType(field: FieldMetadata): string | undefined {
  const type = field.type;
  const raw = field.enumType
    ?? (type.includes('.') && !type.startsWith('java.') && !type.includes('Entity') && !type.includes('DTO') ? type : undefined);
  return raw ? simpleName(raw) : undefined;
}

function simpleName(name: string): string {
  const parts = name.split('.');
  return parts[parts.length - 1];
}

/**
 * The control value kind follows the DTO type type-gen emits (`DslMapper.mapType`), never a list of
 * Java names: BigDecimal maps to `string` for precision and must stay a text value, while a
 * primitive `boolean` or `long` is a boolean or number exactly as its wrapper is.
 */
function formValueKind(field: FieldMetadata): FormValueKind {
  const ts = DslMapper.mapType(field.type).tsType;
  if (ts === 'boolean' || ts === 'boolean | null') return 'boolean';
  if (ts === 'number' || ts === 'number | null') return 'number';
  return 'text';
}

/** The `<input type>`: the `dataType` facet first, then the value kind, then the qualified Java type. */
function formInputType(field: FieldMetadata, value: FormValueKind): string {
  if (field.dataType === 'url') return 'url';
  if (field.dataType === 'currency' || field.dataType === 'percent') return 'number';
  const type = field.type;
  if (value === 'boolean') return 'checkbox';
  if (type === 'java.lang.Integer' || type === 'java.lang.Long' || type === 'java.lang.Double' || type === 'java.lang.Float') return 'number';
  if (type === 'java.time.Instant' || type === 'java.time.LocalDateTime') return 'datetime-local';
  if (type === 'java.time.LocalDate') return 'date';
  return 'text';
}

/**
 * A checkbox has no empty state, so a boolean control is seeded with a real boolean; every other
 * control seeds `''` so that blank stays distinguishable from a value, or the declared default as a
 * string literal.
 */
function formInitialValue(field: FieldMetadata, value: FormValueKind): string {
  if (value === 'boolean') {
    return String(field.defaultValue ?? 'false').trim().toLowerCase() === 'true' ? 'true' : 'false';
  }
  return field.defaultValue ? `'${field.defaultValue}'` : "''";
}

function formPlacement(field: FieldMetadata, system: boolean): FormPlacement {
  if (field.inCreate === false || LIFECYCLE_FIELDS.has(field.name) || system) return 'none';
  if (field.computed) return 'computed';
  if (field.hidden === true || field.readOnly === true) return 'none';
  return 'control';
}

function formRender(field: FieldMetadata, system: boolean): FieldRenderModel['form'] {
  const value = formValueKind(field);
  const inputType = formInputType(field, value);
  const enumType = formEnumType(field);
  return {
    placement: formPlacement(field, system),
    label: field.displayName ?? toTitleCase(field.name),
    control: enumType ? 'select' : inputType === 'checkbox' ? 'checkbox' : 'input',
    inputType,
    inputMode: inputType === 'number' ? 'decimal' : undefined,
    enumType,
    required: Boolean(field.required),
    readOnly: Boolean(field.readOnly),
    value,
    initialValue: formInitialValue(field, value),
    computedFrom: field.computedFrom ?? [],
  };
}
