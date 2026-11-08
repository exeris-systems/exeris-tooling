---
title: Generate the frontend
type: howto
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-08
---

# Generate the frontend

**Audience:** your build writes processor metadata, as in [guide 01](01-add-the-tooling-to-a-build.md), and you want the Angular application the TypeScript generator writes from it, with a `@View` page and the screens of an entity laid out in tabs and groups.

**Goal:** generate the frontend of the whole corpus, read what the `presentation` scenario's `EmployeeDirectory` page and `Employee` entity become, and build and test the result.

> **Verified against** exeris-tooling `main` at commit `b70edc67` (`0.11.0-SNAPSHOT`) and `exeris-sdk-examples` at tag `v0.13.3`, 2026-11-08. The input is the metadata `SdkExamplesE2ETest` writes to `exeris-e2e-tests/target/sdk-examples/app/target/classes/exeris-metadata`, and generated snippets are quoted from the application `exeris-codegen-ts/scripts/gen-sdk-examples-app.mjs` writes from it. If a snippet and its source disagree, **the source wins and this guide is the defect**.

## Prerequisites

- Node 22 or newer, which the emitted `package.json` declares under `engines`.
- The processor metadata of your build, `target/classes/exeris-metadata/`. The TypeScript generator reads that JSON and never your Java sources.
- The `presentation` example: [`Employee.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/presentation/domain/Employee.java) and [`EmployeeDirectory.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/presentation/EmployeeDirectory.java). How the presentation annotations are written is the SDK's [presentation guide](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/docs/guides/05-declare-presentation.md).

## Steps

### 1. Generate the application

`@exeris/codegen-ts` installs the `exeris-gen` command. From the directory the application goes into:

```bash
exeris-gen generate --input <path to target/classes/exeris-metadata> --output . --tests
```

That is the command `gen-sdk-examples-app.mjs` runs, twice, into two empty directories, failing when the two outputs differ. Every switch and the `exeris-codegen.json` file that can replace them are on the [codegen-ts page](../modules/05-codegen-ts.md#command-line). With `--output .` the project scaffold, `package.json`, `angular.json` and the `tsconfig` files, is written at the root and the application under `src/app/`.

One run covers every entity and view in the metadata directory. For each entity it writes a service, a store, list, detail and form components and their types and `zod` schemas; the paths and conditions are the TypeScript section of the [generator catalogue](../generators.md#typescript-generators). `.exeris-codegen-manifest` at the output root records what the run owns, and a later run never replaces a file it does not own.

### 2. Read the page a `@View` becomes

The processor walks `EmployeeDirectory`'s regions and blocks into `view_EmployeeDirectory.json`. The Java pipeline generates nothing from a view; the TypeScript generator writes a page component and its route.

Generated: `src/app/pages/employee-directory.component.ts`, the `template` and the class body (adapted: the decorator's other members and the imports removed).

```ts
  template: `
    <main class="exeris-page" data-view="EmployeeDirectory">
      <h1 class="text-2xl font-bold font-exeris mb-6">Employees</h1>
      <section data-region="header">
        <section class="exeris-hero bg-exeris-primary text-white p-8 rounded-md" data-block="HERO"></section>
      </section>
      <section data-region="body">
        <ul class="exeris-list space-y-2" data-block="LIST"></ul>
        <article class="exeris-card p-4" data-block="CARD" (click)="invite()"></article>
      </section>
    </main>
  `,
})
export class EmployeeDirectoryPageComponent implements OnInit {
  protected readonly employeeStore = inject(EmployeeStore);

  ngOnInit(): void {
    void this.employeeStore.loadAll();
  }


  protected invite(): void {
    // TODO(@View): wire the 'invite' action — slice 1 emits the handler stub only.
  }
}
```

Each `@Region` is a `<section>` named by its slot, each `@Block` an element of its type. The `ENTITY` binding injects `EmployeeStore` and loads it; the `ACTION` binding becomes a click handler for you to fill in. `@View(route = "/people")` becomes the page's route, which `app.routes.ts` spreads in and also uses as the default redirect.

### 3. Read the screens of an entity

`Employee`'s `@Tab` and `@UIGroup` reach `uiMetadata.tabs` and `uiMetadata.groups` in `Employee.json`, and the form and detail components lay their fields out by them.

Generated: `src/app/components/employee-form.component.ts`, the tab list (adapted: the `class` bindings removed).

```html
<div role="tablist" aria-label="Form Tabs" class="flex border-b border-gray-200 dark:border-gray-700 mb-6 gap-2">
  <button type="button" role="tab" id="tab-profile" aria-controls="panel-profile" [attr.aria-selected]="activeTab() === 'profile'" (click)="activeTab.set('profile')" data-testid="tab-profile">Profile</button>
  <button type="button" role="tab" id="tab-employment" aria-controls="panel-employment" [attr.aria-selected]="activeTab() === 'employment'" (click)="activeTab.set('employment')" data-testid="tab-employment">Employment</button>
```

Inside each tab panel a group is a `<details>` element, open unless the group declares `collapsed = true`, as `contact` does. `@NavMenu(group = "People", label = "Employees", icon = "badge")` puts the entity's list under a `People` heading in the shell's navigation, `src/app/app.component.ts`. A Material icon name such as `badge` renders as a glyph of the Material Symbols font, which the scaffold installs as the `material-symbols` package and imports in `src/styles.css`; any other icon text, an emoji for example, renders as itself. The forms are Signal Forms ([ADR-093](../adr/ADR-093-emitted-forms-are-signal-forms.md)).

### 4. Point the application at the backend

Each generated service calls the entity's route on the page's own origin: `order.service.ts` holds `private readonly baseUrl = '/orders';`. `npm start` runs `ng serve --proxy-config proxy.conf.js`, and the generated proxy forwards each entity path to the kernel application, keeping a request that asks for `text/html` with the dev server, because an entity's API path is also its page route.

Generated: `proxy.conf.js`, the target and the `/orders` entry (quoted).

```js
const target = 'http://localhost:8443';
```

```js
  '/orders': { target, secure: false, changeOrigin: true, bypass },
```

## Verify

From the output directory:

```bash
npm install
npx ng build --configuration development
npx ng test --watch=false
```

This is the `sdk-examples-app` job of `.github/workflows/build.yml`, which runs the generation and these three commands on every push to `main` and every pull request against it; it is not a required check. Expect the build to finish with `Application bundle generation complete.` and every emitted spec to pass.

## Not available today

- **Block content.** The `LIST` block's `@Bind(path = "lastName")` loads the store and renders an empty `<ul>`; the `HERO` block renders an empty section. The page is structure, filled by you.
- **The `invite` action.** It is a stub, and `Employee` declares no `invite` action for it to call.

## Where this does not apply

- **A view in an application you own.** `exeris-gen init --views-only` presets a configuration that writes the pages into an existing Angular application; see the [codegen-ts page](../modules/05-codegen-ts.md).
- **Styling.** The emitted classes come from `@exeris/ui-kit` on Tailwind v4, covered by the SDK's [styling guide](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/docs/guides/09-style-generated-ui.md).
- **Trade-off you accept.** Generating the frontend from the same metadata as the backend keeps routes, field names and validation in step, and ties the screens to the entity's shape: a layout the metadata cannot express is a hand-written component beside the generated ones, not an edit to them.
