/**
 * Coverage for src/generators/angular/list-gen.ts — ListGenerator emits
 * an Angular 21 standalone list component with signals, CDK a11y table,
 * @defer SSR placeholder, list animations, search + filter dropdowns,
 * sortable headers, and pagination.
 *
 * Exercises:
 *   - listColumns precedence: explicit uiMetadata.listColumns >
 *     getDefaultListColumns (first 5 non-hidden non-system fields)
 *   - displayName / pluralName fallbacks
 *   - systemFields.idField alias in track / data-testid / delete dispatch
 *   - Per-column rendering matrix: Boolean → Yes/No badge; date format →
 *     mediumDate; datetime/Instant → medium; default → {{ item.<name> }}
 *   - Sortable column gets click handler + aria-sort + arrow markup
 *   - First 2 filterable Boolean fields render filter dropdowns
 *   - Component class signals + searchSubject debounce wiring
 */

import { describe, expect, it } from 'vitest';
import { ListGenerator, generateList } from '../../../src/generators/angular/list-gen.js';
import {
  createGeneratorContext,
  type GeneratorContext,
} from '../../../src/core/generator-registry.js';
import {
  DomainMetadataSchema,
  FieldMetadataSchema,
  type DomainMetadata,
  type FieldMetadata,
} from '../../../src/models/domain-model.js';

const CTX: GeneratorContext = createGeneratorContext({});

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

function field(overrides: Partial<FieldMetadata> & { name: string; type: string }): FieldMetadata {
  return FieldMetadataSchema.parse(overrides);
}

function hiddenDomain(entityName: string): DomainMetadata {
  return domain({
    entityName,
    internalApi: { hidden: true, readOnly: false, internal: false },
  });
}

// ---------- CodeGenerator contract ----------

describe('ListGenerator — CodeGenerator metadata', () => {
  const gen = new ListGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('ListGenerator');
    expect(gen.artifactType).toBe('LIST');
    expect(gen.priority).toBe(20);
    expect(gen.supportedBackends).toEqual([]);
  });
});

// ---------- generate — path + hidden-skip ----------

describe('ListGenerator.generate — emit path + hidden-skip', () => {
  const gen = new ListGenerator();

  it('emits components/<kebab>-list.component.ts for a visible domain', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine' }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-line-list.component.ts');
    expect(file!.artifactType).toBe('LIST');
    expect(file!.overwritable).toBe(true);
  });

  it('returns null for an internalApi.hidden domain', () => {
    expect(gen.generate(hiddenDomain('Audit'), CTX)).toBeNull();
  });
});

// ---------- emitted top-level structure ----------

describe('ListGenerator emitted content — top-level structure', () => {
  const gen = new ListGenerator();

  it('imports Component / signals + CommonModule + RouterModule + FormsModule + service + rxjs operators (no @angular/animations)', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("import {");
    expect(content).toContain('Component,');
    expect(content).toContain('signal,');
    expect(content).toContain('computed,');
    expect(content).toContain("from '@angular/core';");
    expect(content).toContain("import { CommonModule } from '@angular/common';");
    expect(content).toContain("import { RouterModule } from '@angular/router';");
    expect(content).toContain("import { FormsModule } from '@angular/forms';");
    expect(content).toContain("import { Order, OrderService, PageRequest, OrderFilter, Page } from '../services/order.service';");
    expect(content).toContain("import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';");
    expect(content).toContain("import { takeUntilDestroyed } from '@angular/core/rxjs-interop';");
    // B4: @angular/animations is deprecated in v22 — row enter is native animate.enter (no import).
    expect(content).not.toContain("from '@angular/animations'");
  });

  it('emits @Component decorator with app-<kebab>-list selector + OnPush + row-enter styles + host class (no animations metadata)', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;

    expect(content).toContain("selector: 'app-order-line-list'");
    expect(content).toContain('standalone: true');
    expect(content).toContain('ChangeDetectionStrategy.OnPush');
    // B4: native animate.enter — keyframes live in component styles, not an animations: [] trigger array.
    expect(content).not.toContain('animations: [listAnimation]');
    expect(content).toContain('.row-enter { animation: row-enter-kf 200ms ease-out both; }');
    expect(content).toContain('@keyframes row-enter-kf {');
    expect(content).toContain("'class': 'block'");
  });

  // B4: native enter animation wiring on the row (animate.enter + CSS-delay stagger).
  it('rows use native animate.enter with an $index-driven stagger; tbody drops the trigger binding', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('@for (item of items(); track item.id; let i = $index) {');
    expect(content).toContain('<tr animate.enter="row-enter" [style.animation-delay.ms]="i * 50"');
    expect(content).not.toContain('[@listAnimation]');
  });

  it('ListComponent class declares the documented signal surface (page / size / sort / filter / data / isLoading / error + computed items/totals)', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('export class OrderListComponent implements OnInit {');
    expect(content).toContain('readonly currentPage = signal(0);');
    expect(content).toContain('readonly pageSize = signal(20);');
    expect(content).toContain("readonly sortField = signal<string>('id');");
    expect(content).toContain("readonly sortDirection = signal<'asc' | 'desc'>('desc');");
    expect(content).toContain('readonly filter = signal<OrderFilter>({});');
    expect(content).toContain('readonly data = signal<Page<Order> | null>(null);');
    expect(content).toContain('readonly isLoading = signal(false);');
    expect(content).toContain('readonly error = signal<string | null>(null);');
    expect(content).toContain('readonly items = computed');
    expect(content).toContain('readonly totalElements = computed');
    expect(content).toContain('readonly totalPages = computed');
  });

  it('search debounce: searchSubject debounceTime(300) + distinctUntilChanged + takeUntilDestroyed in constructor', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('private readonly searchSubject = new Subject<string>();');
    expect(content).toContain('debounceTime(300)');
    expect(content).toContain('distinctUntilChanged()');
    expect(content).toContain('takeUntilDestroyed()');
  });

  it('loadData method invokes service.findAll with the page/sort/filter signals', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('this.service.findAll(');
    expect(content).toContain('page: this.currentPage()');
    expect(content).toContain('size: this.pageSize()');
    expect(content).toContain('sort: this.sortField()');
    expect(content).toContain('direction: this.sortDirection()');
    expect(content).toContain('this.filter()');
  });
});

// ---------- displayName / pluralName fallbacks ----------

describe('ListGenerator displayName / pluralName fallbacks', () => {
  const gen = new ListGenerator();

  it('pluralName falls back to <entityName>s; lowercased pluralName is used in template messaging', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    // Singular displayName 'Order' + plural fallback 'Orders'.
    expect(content).toContain('<h1 class="text-2xl font-bold tracking-tight text-gray-900 dark:text-white">Orders</h1>');
    expect(content).toContain('No orders yet');
    expect(content).toContain("Search orders...");
  });

  it('explicit displayName + pluralName both flow into emitted markup', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      displayName: 'Sales Order',
      pluralName: 'Sales Orders',
    }), CTX)!.content;

    expect(content).toContain('>Sales Orders</h1>');
    // The ternary in the emitted header gets both display+plural
    // lowercased at generation time.
    expect(content).toContain("'sales order' : 'sales orders'");
    expect(content).toContain('Get started by creating a new sales order.');
  });
});

// ---------- listColumns precedence ----------

describe('ListGenerator listColumns selection', () => {
  const gen = new ListGenerator();

  function columnNamesIn(content: string): string[] {
    // Each emitted <th> spans across multiple lines; the easiest stable
    // marker is the click handler emitted only for sortable columns OR
    // the inline label inside the header span <span>{name}</span>.
    // Use the <span>Label</span> pattern present for every column.
    const matches = [...content.matchAll(/<span>([A-Z][A-Za-z0-9 ]*)<\/span>/g)];
    return matches.map(m => m[1]);
  }

  it('explicit uiMetadata.listColumns wins over the default heuristic (first-N filter)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      uiMetadata: { listColumns: ['orderNumber'] },
      fields: [
        field({ name: 'orderNumber', type: 'String' }),
        field({ name: 'firstName', type: 'String' }),
        field({ name: 'lastName', type: 'String' }),
      ],
    }), CTX)!.content;

    const columns = columnNamesIn(content);
    expect(columns).toContain('Order Number');
    // First-name / Last-name not selected because uiMetadata pinned only orderNumber.
    expect(columns).not.toContain('First Name');
    expect(columns).not.toContain('Last Name');
  });

  it('uiMetadata.listColumns referencing non-existent field is silently dropped (no row emitted)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      uiMetadata: { listColumns: ['orderNumber', 'doesNotExist'] },
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;

    const columns = columnNamesIn(content);
    expect(columns).toEqual(['Order Number']);
  });

  it('without uiMetadata.listColumns → getDefaultListColumns: first 5 non-hidden non-system fields', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        // System fields (always excluded from default selection)
        field({ name: 'id', type: 'UUID' }),
        field({ name: 'createdAt', type: 'Instant' }),
        field({ name: 'version', type: 'Long' }),
        // Hidden field (excluded)
        field({ name: 'secret', type: 'String', hidden: true }),
        // Six business fields (only first 5 selected — see slice(0,5))
        field({ name: 'orderNumber', type: 'String' }),
        field({ name: 'total', type: 'BigDecimal' }),
        field({ name: 'status', type: 'String' }),
        field({ name: 'paidAt', type: 'Instant' }),
        field({ name: 'notes', type: 'String' }),
        field({ name: 'sixth', type: 'String' }), // dropped — past slice(0, 5)
      ],
    }), CTX)!.content;

    const columns = columnNamesIn(content);
    expect(columns).toContain('Order Number');
    expect(columns).toContain('Total');
    expect(columns).toContain('Status');
    expect(columns).toContain('Paid At');
    expect(columns).toContain('Notes');
    expect(columns).not.toContain('Sixth');
    expect(columns).not.toContain('Id');
    expect(columns).not.toContain('Created At');
    expect(columns).not.toContain('Secret');
  });

  it('empty listColumns OR empty fields → table renders with only the Actions header', () => {
    const content = gen.generate(domain({
      entityName: 'Empty',
      uiMetadata: { listColumns: [] },
    }), CTX)!.content;

    const columns = columnNamesIn(content);
    expect(columns).toEqual([]);
    // The Actions <th> sr-only label is always emitted.
    expect(content).toContain('<span class="sr-only">Actions</span>');
  });
});

// ---------- column-rendering matrix ----------

describe('ListGenerator per-column rendering matrix', () => {
  const gen = new ListGenerator();

  it('Boolean column renders Yes / No pill badges (no raw {{ value }})', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      uiMetadata: { listColumns: ['flag'] },
      fields: [field({ name: 'flag', type: 'Boolean' })],
    }), CTX)!.content;

    expect(content).toContain('@if (item.flag)');
    expect(content).toContain('>Yes<');
    expect(content).toContain('>No<');
    expect(content).not.toContain('{{ item.flag }}');
  });

  it.each([
    // Branch order in list-gen.ts:281-285:
    //   if (format === 'date' || type.includes('Date')) → mediumDate
    //   else if (format === 'datetime' || type.includes('DateTime') || type.includes('Instant')) → medium
    //   else → raw {{ item.<name> }}
    // QUIRK: every type whose name contains 'Date' matches the FIRST
    // arm before the second can be checked. That includes
    // LocalDateTime / OffsetDateTime / ZonedDateTime — all route
    // through the mediumDate (date-only) format despite carrying time
    // components. Pre-existing source behaviour; pinned here.
    // Each row's second column is the EXPECTED emitted pipe pattern
    // for that type — Instant is the only multi-component temporal
    // that escapes the QUIRK because its name doesn't contain "Date".
    ['Instant',        'medium',     "Instant doesn't contain 'Date' → reaches the medium-datetime arm"],
    ['LocalDate',      'mediumDate', 'pure date type → mediumDate'],
    ['LocalDateTime',  'mediumDate', "QUIRK: contains 'Date' substring → first arm wins, time component lost"],
    ['OffsetDateTime', 'mediumDate', 'QUIRK: same as LocalDateTime'],
    ['ZonedDateTime',  'mediumDate', 'QUIRK: same as LocalDateTime'],
  ])('temporal type %s → date pipe with %s pattern (%s)', (fieldType, expectedPattern) => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      uiMetadata: { listColumns: ['at'] },
      fields: [field({ name: 'at', type: fieldType })],
    }), CTX)!.content;

    expect(content).toContain(`{{ item.at | date:'${expectedPattern}' }}`);
  });

  it('explicit format=datetime on a non-Date-substring type DOES route through the datetime arm', () => {
    // The only way to reach the datetime arm with the current branch
    // ordering is either (a) field.type contains 'Instant' (no Date
    // substring), or (b) field.format === 'datetime' AND field.type
    // does NOT include 'Date'. A plain String field with
    // format: 'datetime' covers (b).
    const content = gen.generate(domain({
      entityName: 'Thing',
      uiMetadata: { listColumns: ['ts'] },
      fields: [field({ name: 'ts', type: 'String', format: 'datetime' })],
    }), CTX)!.content;
    expect(content).toContain("{{ item.ts | date:'medium' }}");
  });

  it('default column renders {{ item.<name> }} interpolation (no pipe)', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      uiMetadata: { listColumns: ['title'] },
      fields: [field({ name: 'title', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('{{ item.title }}');
    expect(content).not.toContain('item.title | date');
  });
});

// ---------- @Field.dataType render facets (Wave 1A) ----------

describe('ListGenerator @Field.dataType render facets', () => {
  const gen = new ListGenerator();

  it("dataType 'currency' renders the | currency pipe", () => {
    const content = gen.generate(domain({
      entityName: 'Invoice',
      uiMetadata: { listColumns: ['amount'] },
      fields: [field({ name: 'amount', type: 'BigDecimal', dataType: 'currency' })],
    }), CTX)!.content;

    expect(content).toContain('{{ item.amount | currency }}');
  });

  it("dataType 'percent' renders the | percent pipe", () => {
    const content = gen.generate(domain({
      entityName: 'Stat',
      uiMetadata: { listColumns: ['rate'] },
      fields: [field({ name: 'rate', type: 'Double', dataType: 'percent' })],
    }), CTX)!.content;

    expect(content).toContain('{{ item.rate | percent }}');
  });

  it("dataType 'url' renders an <a [href]> anchor instead of raw interpolation", () => {
    const content = gen.generate(domain({
      entityName: 'Site',
      uiMetadata: { listColumns: ['homepage'] },
      fields: [field({ name: 'homepage', type: 'String', dataType: 'url' })],
    }), CTX)!.content;

    expect(content).toContain('<a [href]="item.homepage"');
    expect(content).toContain('>{{ item.homepage }}</a>');
  });

  it('absent dataType keeps the default {{ item.<name> }} path (no pipe / anchor)', () => {
    const content = gen.generate(domain({
      entityName: 'Plain',
      uiMetadata: { listColumns: ['note'] },
      fields: [field({ name: 'note', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('{{ item.note }}');
    expect(content).not.toContain('| currency');
    expect(content).not.toContain('| percent');
    expect(content).not.toContain('[href]="item.note"');
  });
});

// ---------- sortable columns ----------

describe('ListGenerator sortable column markers', () => {
  const gen = new ListGenerator();

  it('sortable=true column gets click handler + aria-sort attribute + arrow SVG', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      uiMetadata: { listColumns: ['orderNumber'] },
      fields: [field({ name: 'orderNumber', type: 'String', sortable: true })],
    }), CTX)!.content;

    expect(content).toContain("(click)=\"onSort('orderNumber')\"");
    expect(content).toContain("[attr.aria-sort]=\"sortField() === 'orderNumber'");
    expect(content).toContain("@if (sortField() === 'orderNumber')");
    expect(content).toContain('cursor-pointer select-none');
  });

  it('sortable=false (default) column gets NO click handler / NO aria-sort / NO arrow', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      uiMetadata: { listColumns: ['orderNumber'] },
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;

    expect(content).not.toContain("(click)=\"onSort('orderNumber')\"");
    expect(content).not.toContain('aria-sort');
  });
});

// ---------- systemFields.idField alias ----------

describe('ListGenerator systemFields.idField alias propagation', () => {
  const gen = new ListGenerator();

  it('default idField "id" used in track / data-testid attrs / sortField initial / delete dispatch', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('@for (item of items(); track item.id; let i = $index)');
    expect(content).toContain("'row-' + item.id");
    expect(content).toContain("readonly sortField = signal<string>('id');");
    expect(content).toContain('this.service.delete(String(item.id))');
  });

  it('custom systemFields.idField overrides all 4 reference sites', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      systemFields: { idField: 'uuid' },
    }), CTX)!.content;

    expect(content).toContain('@for (item of items(); track item.uuid; let i = $index)');
    expect(content).toContain("'row-' + item.uuid");
    expect(content).toContain("readonly sortField = signal<string>('uuid');");
    expect(content).toContain('this.service.delete(String(item.uuid))');
  });
});

// ---------- filter dropdowns ----------

describe('ListGenerator filter dropdowns (Boolean only, first 2)', () => {
  const gen = new ListGenerator();

  it('first 2 filterable Boolean fields render filter <select> dropdowns', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'active', type: 'Boolean', filterable: true }),
        field({ name: 'paid', type: 'Boolean', filterable: true }),
        field({ name: 'shipped', type: 'Boolean', filterable: true }), // 3rd — skipped
      ],
    }), CTX)!.content;

    expect(content).toContain('data-testid="filter-active"');
    expect(content).toContain('data-testid="filter-paid"');
    expect(content).not.toContain('data-testid="filter-shipped"');
  });

  it('non-Boolean filterable field does NOT get a dropdown in the template (even within the first-2 slice)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'status', type: 'String', filterable: true }),
        field({ name: 'priority', type: 'Integer', filterable: true }),
      ],
    }), CTX)!.content;

    expect(content).not.toContain('data-testid="filter-status"');
    expect(content).not.toContain('data-testid="filter-priority"');
  });

  it('class declares the filter<Name> string fields for each Boolean filter (uppercase first char)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'active', type: 'Boolean', filterable: true })],
    }), CTX)!.content;

    expect(content).toContain("filterActive: string = '';");
  });

  it('onFilterChange method updates the filter signal with parsed Boolean (string "true" → true, anything else → undefined)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'active', type: 'Boolean', filterable: true })],
    }), CTX)!.content;

    expect(content).toContain("active: this.filterActive ? this.filterActive === 'true' : undefined,");
  });
});

// ---------- generateList convenience ----------

describe('generateList — top-level convenience function', () => {
  it('returns the per-domain file for a visible domain', () => {
    const file = generateList(domain({ entityName: 'Order' }), CTX.config);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-list.component.ts');
  });

  it('returns null for a hidden domain', () => {
    expect(generateList(hiddenDomain('Audit'), CTX.config)).toBeNull();
  });

  it('falls back to KERNEL backend when config.backend is undefined (still emits per-domain file)', () => {
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    const file = generateList(domain({ entityName: 'Order' }), partialConfig);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-list.component.ts');
  });
});
