# RFC-2026-08-28: What should the pipeline emit from a declared permission?

| Field             | Value |
|:------------------|:------|
| **Status**        | **DRAFT** |
| **Author(s)**     | ArkStack |
| **Date Opened**   | 2026-08-28 |
| **Date Closed**   | — |
| **Target ADR(s)** | TBD (one; reserve after acceptance) |
| **Affected Repos**| `exeris-tooling` (all of it), `exeris-sdk` (nothing new — the annotations already exist), `exeris-kernel` (nothing — the SPI already offers what is needed) |
| **Reviewers**     | — |

## Question

An author writes `@ExerisDomain(permissions = {"order:read"})`. Nothing happens — not at build
time, not at runtime, not in the emitted frontend. What should the pipeline emit from that
declaration, and what should it do about the four other layers of an authorization story that
already exist and are not joined to each other?

## Context

ADR-079 removed the emitted OpenAPI's `bearerAuth` requirement because every emitted route
resolves to `PERMIT_ALL` and the `401` the spec promised was unreachable. It named the follow-up
T51 as "the generated app cannot declare that a route needs a caller" — which was wrong, and the
correction is what motivates this RFC: **the declaration exists**. What is missing is everything
between it and an enforced route.

## Investigation

### Data gathered

Measured against SDK `main`, kernel `v0.11.0`, and `exeris-tooling` at `bed21ef`:

| Layer | State |
|---|---|
| `@ExerisDomain(roles, permissions)`, `@Action(roles, permissions)` | declared; Javadoc says "**declared but not extracted**" (kernel v0.11, ADR-061) |
| `DomainMetadata.roles/permissions`, `ActionMetadata.permissions`, TS Zod schema | fields exist, `@JsonProperty` and all — empty in every build |
| processor extraction | **absent** — `grep -rn "roles\|permissions" exeris-processor/src/main` returns nothing |
| `-Aexeris.strict` | was silent; four `INERT_ATTRIBUTES` entries added alongside this RFC |
| kernel route SPI | `HttpRoutePolicy` (functional, `(method, path) → RouteRequirement`); `RouteRequirement` kinds `PERMIT_ALL`, `AUTHENTICATED`, `ANY_SCOPE`, `ALL_SCOPES` |
| kernel enforcement | `RouteAuthorizationEnforcer` consults `PrincipalContext.scopes()` and **never** `roles()` |
| backend, as emitted | nothing binds `HTTP_ROUTE_POLICY`, so every route is `PERMIT_ALL` and the `SecurityInterceptor` never runs |
| frontend, as emitted | `guard-gen` emits `canView<Entity>` etc. checking `auth.hasPermission(<ENTITY>_PERMISSIONS.READ)` against **invented** constants; `app-structure-gen` attaches them to **no route** |

The last row is the one to keep in view: the generated frontend already guards on permissions the
generated backend does not check and the author did not declare — and then does not install the
guards. Whatever this RFC decides, it should leave the frontend telling the same story as the
backend.

### Constraints

1. **`roles` is not a route concept, and that is settled upstream.** The SDK Javadoc rules out a
   `ROLE_x`-to-scope convention because it would stand up a second, silently diverging authority
   model at the edge; roles resolve at the method level through the kernel's `@RequiresRole`
   (kernel ADR-014, its own build-config processor). This RFC therefore covers `permissions` only,
   and proposes nothing for `roles`.
2. **The kernel needs no change.** `HttpRoutePolicy` is a `@FunctionalInterface` and
   `RouteRequirement` already carries the four kinds. This is entirely a tooling emission problem.
3. **Emitted code is committed and may be detached** (L1/L2). A policy that only exists inside
   `Application` is unreachable to a consumer who has not detached, which is the ADR-070 problem.
4. **Backwards compatibility is a security question here, not an ergonomics one.** Every existing
   generated app runs permit-all today. Any default that tightens routes silently upgrades an app
   into refusing traffic it currently serves.

## Options Considered

### Option A: Emit a policy only where a permission is declared; everything else stays `PERMIT_ALL`

The generated policy answers `ANY_SCOPE`/`ALL_SCOPES` for routes whose entity or action declared
permissions, and `PERMIT_ALL` for every other path — including paths the app serves that the
pipeline did not emit.

- **For:** no existing app changes behaviour unless its author declared something. The declaration
  becomes the switch, which is what an author writing `permissions = {…}` expects.
- **Against:** an app that declares permissions on *some* entities leaves the rest open, and the
  policy says so explicitly rather than by omission — a reader may still read it as "everything is
  covered".

### Option B: Emit a policy that is `AUTHENTICATED` by default, `PERMIT_ALL` only where nothing is declared on any entity

Mirrors `HttpRoutePolicy.unmatched()`, which returns `authenticated()`.

- **For:** matches the kernel's own idea of a sensible unmatched default; an app that starts
  declaring permissions gets a closed edge rather than a half-open one.
- **Against:** the moment one entity declares a permission, every *other* emitted route starts
  demanding identity — including routes an existing app serves anonymously today. That is a
  behaviour change no author asked for, delivered by regenerating.

### Option C: Emit the table, bind nothing, and let the consumer install it

Emit `<App>RoutePolicy` as a component with a `RuntimeComponents` factory, and leave the
`ScopedValue` binding to the consumer.

- **For:** no behaviour change at all; the consumer opts in explicitly.
- **Against:** it is the inert-output failure mode this repo keeps recording — a generated artefact
  that nothing runs. ADR-070 exists precisely because "emitted but unreachable" is a defect.

### Option D (do nothing)

Leave all five layers unjoined and keep the strict warnings as the only signal.

- **For:** honest; costs nothing.
- **Against:** the emitted frontend keeps guarding on invented names, and an author's declared
  permission keeps meaning nothing.

## Recommendation

**Option A, with the policy bound by the emitted `Application` and reachable through
`RuntimeComponents`.** It is the only option where the author's declaration is both the switch and
the content, and the only one that cannot change an existing app that declared nothing.

Sketch of what that means concretely, for review rather than as a commitment:

- `<Entity>` with `permissions = {"order:read"}` yields, for its emitted routes, a
  `RouteRequirement.requiringAnyScope(Set.of("order:read"))`.
- `@Action(permissions = {"order:approve"})` overrides for that action's route only.
- Path matching is by emitted template, not by string equality: the dispatcher passes a concrete
  path (`/orders/9f3…`), so the emitted policy needs the same template match the router already
  performs. **This is the largest unknown in the sketch** and needs a spike before an ADR.
- The FE guards read the declared permission names instead of inventing them, and
  `app-structure-gen` attaches them to the routes it emits — so front and back state the same rule.
- The OpenAPI security block returns for the protected routes only, which is the last step and
  closes ADR-079's loop.

### Why not the alternatives?

B trades a security-shaped default for a silent behaviour change on regeneration, which is the one
kind of change this pipeline must not make by itself. C ships an artefact nothing runs. D leaves
the frontend lying.

### Risks of the recommendation

- **`ANY_SCOPE` vs `ALL_SCOPES` is unresolved.** "Permissions required to execute this action"
  reads like ALL; "default permissions required to access this entity's API" reads like ANY. The
  ADR must pick one per site and say why, or the SDK Javadoc must.
- **Partial coverage.** An app that protects two entities out of ten has an edge that is mostly
  open, and a policy file that looks authoritative. The emitted policy should say so in a comment
  that names the uncovered routes.
- **Detachment.** After `exeris:detach` the policy is the consumer's file; a later `permissions`
  change in the domain no longer reaches it. This is the general L2 problem, but it is sharper for
  a security artefact, and the ADR should say whether the policy is detachable at all.

## Decision Record

Pending. On acceptance: reserve one ADR number in `exeris-docs/adr-index.md`, delete the four
`INERT_ATTRIBUTES` entries for `permissions` in the change that starts consuming them (the `roles`
entries stay), and record the spike outcome for path matching.

## Open questions / follow-ups

1. Path-template matching in emitted code — spike required.
2. `ANY_SCOPE` vs `ALL_SCOPES`, per site.
3. Whether the emitted policy is detachable, and what a stale detached policy should do.
4. What `roles` compiles into, if anything — currently nothing, deliberately (**not** this RFC).
5. **D10** and **D11** intersect this: the TS `getDefaultHeaders` bearer path and the unwired
   Handlebars templates are both parts of the same unjoined story.
