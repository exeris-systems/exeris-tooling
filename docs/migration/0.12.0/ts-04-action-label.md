---
title: "0.12.0 migration step: `exeris-codegen-ts`: an action button is labelled by `@Action(label)`"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-28
---

### `exeris-codegen-ts`: an action button is labelled by `@Action(label)`

The action buttons of the detail view and of the list rows read `ActionMetadata.displayName`, which the SDK reader writes from `@Action(label)`. An action with `label = "Send it out"` now reads:

```html
<button type="button" (click)="runAction('dispatch')" [disabled]="actionPending()" data-testid="action-dispatch" class="exeris-btn exeris-btn-secondary">Send it out</button>
```

`@Action(label)` has no default, so every action the processor writes carries a label and every generated action button takes it. Only metadata with a blank or absent `displayName` keeps the title-cased method name. The action is still addressed by its name: the `data-testid`, the handler and the request path do not change. A test that finds a button by its text needs the declared label.
