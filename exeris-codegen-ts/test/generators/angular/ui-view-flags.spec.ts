/**
 * The entity-level `@UI` view switches decide which pages, routes, links and controls exist.
 *
 * Pinned:
 *   - absent `uiMetadata` and `uiMetadata` with every switch on emit the same files, byte for byte;
 *   - listView=false: no list component, list route, sidebar link or barrel export, the default
 *     redirect skips the entity, and the detail and form leave to the app root;
 *   - detailView=false: no detail component or route, no View link, a foreign key to the entity
 *     renders as text, and the form leaves to the list;
 *   - createForm=false / editForm=false: no route and no button for that form; the form component
 *     is emitted while either is on, and neither component nor export when both are off;
 *   - searchable=false: no search box and no debounce plumbing; filterable=false: no filter control;
 *     with both off and no control, no search-and-filter row.
 */

import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles, type OutputFile } from '../../../src/orchestrator.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { DslMapper } from '../../../src/models/dsl-mapper.js';
import { DomainMetadataSchema, type DomainMetadata, type UIMetadata } from '../../../src/models/domain-model.js';

const ALL_ON: Required<Omit<UIMetadata, 'icon' | 'color'>> = {
  listView: true,
  detailView: true,
  createForm: true,
  editForm: true,
  searchable: true,
  filterable: true,
  exportable: false,
};

function tag(ui?: Partial<UIMetadata>): DomainMetadata {
  return DomainMetadataSchema.parse({
    packageName: 'com.shop',
    entityName: 'Tag',
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'label', type: 'String', searchable: true },
      { name: 'pinned', type: 'Boolean', filterable: true },
    ],
    ...(ui ? { uiMetadata: ui } : {}),
  });
}

/** Holds a MANY_TO_ONE foreign key to Tag, so the link side of Tag's detail switch is visible. */
const note = DomainMetadataSchema.parse({
  packageName: 'com.shop',
  entityName: 'Note',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'tagId', type: 'java.util.UUID' },
  ],
  relationships: [{ name: 'tagId', targetEntity: 'Tag', type: 'MANY_TO_ONE' }],
});

const TAGS = DslMapper.routePlural('Tag');

function emit(ui?: Partial<UIMetadata>, others: DomainMetadata[] = [note]): Map<string, string> {
  const files: OutputFile[] = buildGeneratedFiles([tag(ui), ...others], [], DEFAULT_CONFIG);
  return new Map(files.map((f) => [f.path, f.content]));
}

const file = (out: Map<string, string>, path: string): string => {
  const content = out.get(path);
  expect(content, `${path} is emitted`).toBeDefined();
  return content!;
};

const LIST = 'src/app/components/tag-list.component.ts';
const DETAIL = 'src/app/components/tag-detail.component.ts';
const FORM = 'src/app/components/tag-form.component.ts';
const ROUTES = 'src/app/app.routes.ts';
const SHELL = 'src/app/app.component.ts';
const BARREL = 'src/app/index.ts';

describe('@UI view switches — defaults', () => {
  it('emits the same files, byte for byte, with no uiMetadata and with every switch on', () => {
    const absent = emit();
    const on = emit(ALL_ON);
    expect([...on.keys()]).toEqual([...absent.keys()]);
    for (const [path, content] of absent) {
      expect(on.get(path), path).toBe(content);
    }
  });

  it('keeps every page, route and control when no switch is set', () => {
    const out = emit({});
    expect(out.has(LIST) && out.has(DETAIL) && out.has(FORM)).toBe(true);
    const routes = file(out, ROUTES);
    for (const path of [`'${TAGS}'`, `'${TAGS}/new'`, `'${TAGS}/:id'`, `'${TAGS}/:id/edit'`]) {
      expect(routes).toContain(`path: ${path}`);
    }
    expect(file(out, LIST)).toContain('data-testid="search-input"');
    expect(file(out, LIST)).toContain('data-testid="filter-pinned"');
  });
});

describe('@UI(listView = false)', () => {
  const out = emit({ listView: false });

  it('emits no list component, route, sidebar link or export', () => {
    expect(out.has(LIST)).toBe(false);
    expect(file(out, ROUTES)).not.toContain(`path: '${TAGS}',`);
    expect(file(out, ROUTES)).not.toContain('TagListComponent');
    expect(file(out, SHELL)).not.toContain(`routerLink="/${TAGS}"`);
    expect(file(out, BARREL)).not.toContain('TagListComponent');
  });

  it('keeps the other pages and redirects the root to the next listed entity', () => {
    expect(out.has(DETAIL) && out.has(FORM)).toBe(true);
    expect(file(out, ROUTES)).toContain(`redirectTo: '${DslMapper.routePlural('Note')}'`);
    expect(file(out, ROUTES)).toContain(`path: '${TAGS}/:id'`);
  });

  it('sends the detail and the form to the app root instead of the missing list', () => {
    expect(file(out, DETAIL)).toContain(`this.router.navigate(['/'])`);
    expect(file(out, DETAIL)).not.toContain(`navigate(['/${TAGS}'])`);
    expect(file(out, FORM)).toContain(`: ['/']`);
    expect(file(out, FORM)).not.toContain(`['/${TAGS}']`);
  });

  it('emits no redirect at all when no entity is listed and no page view exists', () => {
    const alone = emit({ listView: false }, []);
    expect(file(alone, ROUTES)).not.toContain('redirectTo');
  });
});

describe('@UI(detailView = false)', () => {
  const out = emit({ detailView: false });

  it('emits no detail component, route or export', () => {
    expect(out.has(DETAIL)).toBe(false);
    expect(file(out, ROUTES)).not.toContain(`path: '${TAGS}/:id',`);
    expect(file(out, ROUTES)).not.toContain('TagDetailComponent');
    expect(file(out, BARREL)).not.toContain('TagDetailComponent');
  });

  it('drops the View link from the list and keeps Edit and Delete', () => {
    const list = file(out, LIST);
    expect(list).not.toContain("'action-view-'");
    expect(list).toContain("'action-edit-'");
    expect(list).toContain("'action-delete-'");
  });

  it('renders a foreign key to the entity as text in the referencing list and detail', () => {
    const noteList = file(out, 'src/app/components/note-list.component.ts');
    const noteDetail = file(out, 'src/app/components/note-detail.component.ts');
    expect(noteList).not.toContain(`'/${TAGS}'`);
    expect(noteDetail).not.toContain(`'/${TAGS}'`);
    // With the switch on, the same field links.
    expect(file(emit(), 'src/app/components/note-list.component.ts')).toContain(`['/${TAGS}', item.tagId]`);
  });

  it('sends the form to the list after a save and on cancel', () => {
    const form = file(out, FORM);
    expect(form).toContain(`void this.router.navigate(['/${TAGS}']);`);
    expect(form).not.toContain(`['/${TAGS}', String(result.id)]`);
    expect(form).not.toContain(`['/${TAGS}', id]`);
  });
});

describe('@UI(createForm = false) / @UI(editForm = false)', () => {
  it('createForm=false drops the create route and every create button, and keeps the form for edit', () => {
    const out = emit({ createForm: false });
    expect(file(out, ROUTES)).not.toContain(`path: '${TAGS}/new'`);
    expect(file(out, ROUTES)).toContain(`path: '${TAGS}/:id/edit'`);
    const list = file(out, LIST);
    expect(list).not.toContain('routerLink="new"');
    expect(list).not.toContain('data-testid="action-create"');
    expect(list).not.toContain('Get started by creating');
    expect(out.has(FORM)).toBe(true);
  });

  it('editForm=false drops the edit route and the Edit links, and keeps the form for create', () => {
    const out = emit({ editForm: false });
    expect(file(out, ROUTES)).not.toContain(`path: '${TAGS}/:id/edit'`);
    expect(file(out, ROUTES)).toContain(`path: '${TAGS}/new'`);
    expect(file(out, LIST)).not.toContain("'action-edit-'");
    expect(file(out, DETAIL)).not.toContain(`[routerLink]="['edit']"`);
    expect(out.has(FORM)).toBe(true);
  });

  it('both off: no form component and no export', () => {
    const out = emit({ createForm: false, editForm: false });
    expect(out.has(FORM)).toBe(false);
    expect(file(out, ROUTES)).not.toContain('TagFormComponent');
    expect(file(out, BARREL)).not.toContain('TagFormComponent');
  });
});

describe('@UI(searchable = false) / @UI(filterable = false)', () => {
  it('searchable=false drops the search box and its debounce plumbing, and keeps the filters', () => {
    const list = file(emit({ searchable: false }), LIST);
    expect(list).not.toContain('search-input');
    expect(list).not.toContain('searchSubject');
    expect(list).not.toContain('onSearch');
    expect(list).not.toContain("from 'rxjs'");
    expect(list).not.toContain('takeUntilDestroyed');
    expect(list).toContain('data-testid="filter-pinned"');
  });

  it('filterable=false drops the filter controls whatever the fields say, and keeps search', () => {
    const list = file(emit({ filterable: false }), LIST);
    expect(list).not.toContain('filter-pinned');
    expect(list).not.toContain('filterPinned');
    expect(list).toContain('data-testid="search-input"');
  });

  it('both off: no search-and-filter row at all', () => {
    const list = file(emit({ searchable: false, filterable: false }), LIST);
    expect(list).not.toContain('<!-- Search & Filters -->');
    // ngModel is bound by nothing, so FormsModule is neither imported nor declared.
    expect(list).not.toContain('FormsModule');
  });
});
