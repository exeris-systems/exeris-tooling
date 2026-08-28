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

### Option B: Secure by default — every emitted route is `AUTHENTICATED` unless declared open

Mirrors `HttpRoutePolicy.unmatched()`, which returns `authenticated()`.

- **For:** the failure mode of forgetting to declare is a `401`, which is loud and safe, rather
  than an open endpoint, which is silent and not. That is the direction every mature framework
  chose, and it is the same class of defect ADR-079 just closed — a security story that reads as
  present and is absent.
- **For:** it follows the kernel's own stated opinion instead of inventing a second one.
- **Against — it needs a declaration that does not exist.** Opt-out is meaningless without a way to
  say "this route is public"; the SDK has no such attribute, so this option carries a cross-repo
  prerequisite that Option A does not.
- **Against — it does not merely tighten, it can stop the app serving.** Measured:
  `CommunityHttpRequestDispatcher` builds the interceptor only when a `SecurityProvider` is
  present, and `SecurityInterceptor.intercept` returns `false` — dispatcher writes `401` — when
  the provider is absent, throws, or no bearer header arrives. An emitted app has no identity
  wiring by default, so flipping this default answers `401` to **every** request until the
  consumer installs a provider.
- **Against — one policy governs every path, including the ones we did not emit.**
  `HttpKernelProviders.httpRoutePolicy()` holds a single `HttpRoutePolicy`, and the dispatcher asks
  it about every request. A generated policy that answers `AUTHENTICATED` for anything it does not
  recognise would silently close a consumer's hand-written routes, a health endpoint, and the docs
  path. This is the constraint that reshapes the whole question, and it is what Option B′ answers.

### Option B′: Secure by default **for the routes we emit**, transparent for everything else

The emitted policy answers `ANY_SCOPE`/`ALL_SCOPES` where permissions are declared,
`AUTHENTICATED` for every other route **the pipeline emitted**, and delegates anything it does not
recognise to a fallback supplied at construction (default `permitAll()`).

- **For:** fail-closed where the pipeline is the authority, and silent where it is not. A
  hand-written route keeps whatever it had.
- **For:** the blast radius is exactly the surface the author described with `@ExerisDomain`, which
  is the surface they can reason about.
- **Against:** still a behaviour change on regeneration for emitted routes — smaller, but real, and
  it still needs the "this one is public" declaration Option B needs.
- **Against:** delegation makes the emitted policy a composite, which is more machinery than a
  lookup table and one more thing to get wrong.

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

**The end state should be Option B′ — fail-closed for emitted routes — reached through a flag whose
default is today's behaviour until 1.0.** Concretely: emit the policy under Option A's rule now
(`PERMIT_ALL` unless a permission is declared), add `-Aexeris.routeDefault=permitAll|authenticated`
in the same change, and make `authenticated` the default at the 1.0 train with a migration entry.

The reasoning, stated plainly because it is a security posture and not an ergonomics preference:

| | Opt-in (A) | Opt-out (B′) |
|---|---|---|
| Forgetting to declare | endpoint is **open**, silently | endpoint answers **401**, loudly |
| Existing generated apps | unchanged | must declare public routes, or install identity, before regenerating |
| Needs a new SDK declaration | no | **yes** — nothing says "this route is public" today |
| Governs hand-written routes | no | no (that is what B′ fixes over B) |
| Matches the kernel's own default | no | yes (`unmatched()` → `authenticated()`) |

A fails in the direction that hurts: an author who declares permissions on nine entities and
forgets the tenth ships the tenth open, and nothing in the build says so. B′ fails in the direction
that is merely inconvenient: the tenth answers `401` until someone declares it public. **Between a
defect that exposes and a defect that annoys, the pipeline should choose the second** — that is the
same principle ADR-078's build gate and ADR-079's removed claim both rest on.

What stops B′ from being the immediate answer is not the principle but the sequence: it needs the
public-route declaration first (an `exeris-sdk` change), and flipping it without one would leave an
author no way to say "this is the login endpoint". So the flag is not a hedge — it is the honest
order of operations, and the ADR should state the train at which the default flips rather than
leaving it to drift.

### Why not the alternatives?

**Plain A, permanently.** It makes the absence of a declaration the most permissive outcome, which
is backwards, and it leaves a policy file that protects two entities out of ten reading as though
it were the policy.

**Plain B, now.** Two measured objections, either of which is enough on its own: without identity
wiring the app answers `401` to everything, and a single bound policy answering `AUTHENTICATED` for
unrecognised paths would close routes this pipeline never emitted.

**C** ships an artefact nothing runs. **D** leaves the emitted frontend guarding on invented names.

### Risks of the recommendation

- **A flag with a scheduled flip is a promise, and promises drift.** The 1.0 migration entry has to
  be written when the flag lands, not when it flips.
- **`ANY_SCOPE` vs `ALL_SCOPES` is unresolved.** "Permissions required to execute this action" reads
  like ALL; "default permissions required to access this entity's API" reads like ANY.
- **Partial coverage.** Under the `permitAll` default an app that protects two entities has a mostly
  open edge and a policy file that looks authoritative; the emitted policy should name the uncovered
  routes in a comment rather than leave them to inference.
- **Detachment.** After `exeris:detach` the policy is the consumer's file, and a later `permissions`
  change no longer reaches it. Sharper here than for any other artefact, because the stale copy is
  the one deciding who gets in.

## Decision Record

Pending. On acceptance: reserve one ADR number in `exeris-docs/adr-index.md`, delete the four
`INERT_ATTRIBUTES` entries for `permissions` in the change that starts consuming them (the `roles`
entries stay), and record the spike outcome for path matching.

## Open questions / follow-ups

Two of these block the ADR. The rest can be settled in it.

1. **(Blocking)** Path-template matching in emitted code — spike required.
2. **(Blocking) Where a boot-time provider joins the composition root.** ADR-070's
   `RuntimeComponents` reaches components the lifecycle constructs, not a `ScopedValue` bound around
   `KernelBootstrap.boot(...)`. Three shapes, none free:
   - **(a) Extend the seam** — `RuntimeComponents` gains a `createRoutePolicy()` the emitted
     `Application` reads before binding. Keeps one composition root, but it is an ADR-070 amendment
     in substance: the seam would carry a member of a different kind, resolved earlier than the rest.
   - **(b) Make the boot chain overridable** — a `protected` method on `Application` returning the
     policy, defaulting to the generated one. Cheapest, and it leaves the consumer with two seams to
     learn instead of one.
   - **(c) Bind nothing** — Option C above, already rejected as inert output.

   This is the same problem T48 solved for the event publisher, one layer earlier in the boot
   sequence, and it is worth deciding for *providers in general* rather than for this policy alone.
3. **The public-route declaration** — opt-out is meaningless without one, and the SDK has no
   attribute for it. `exeris-sdk`'s call, ours to consume.
4. `ANY_SCOPE` vs `ALL_SCOPES`, per site.
5. Whether the emitted policy is detachable, and what a stale detached policy should do.
6. What `roles` compiles into, if anything — currently nothing, deliberately (**not** this RFC).
7. **D10** and **D11** intersect this: the TS `getDefaultHeaders` bearer path and the unwired
   Handlebars templates are both parts of the same unjoined story.
