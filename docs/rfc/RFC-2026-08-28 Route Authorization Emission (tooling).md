# RFC-2026-08-28: What should the pipeline emit from a declared permission?

| Field             | Value |
|:------------------|:------|
| **Status**        | **DRAFT** — deferred to the 0.9.0 train, targeted at the nominal kernel/SDK shape rather than a 0.11 workaround |
| **Author(s)**     | ArkStack |
| **Date Opened**   | 2026-08-28 |
| **Date Closed**   | — |
| **Target ADR(s)** | TBD (one; reserve after acceptance) |
| **Affected Repos**| `exeris-tooling` (all of it); `exeris-kernel` **0.12** and `exeris-sdk` **0.12** carry the two prerequisites below |
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

### What kernel 0.12 changes, and why this RFC waits for it

Two of the three findings this RFC measured against kernel 0.11 are being answered at the platform
level, on the kernel 0.12 / SDK 0.12 train (founder ruling, 2026-08-28):

- **A fifth `RouteRequirement` kind, `ABSTAIN`** — "I do not describe this route" — plus a
  combinator, `HttpRoutePolicy.firstMatch(generated, handWritten, fallback)` with a **mandatory**
  fallback. Totality is preserved and moves to the composite instead of burdening every component.
  Additive, but `spi.http` is on the stable surface, so it goes through the API gate and likely
  earns an amendment to ADR-061.
- **A denial reason on the JFR event** — `NO_PROVIDER` / `NO_TOKEN` / `TOKEN_REJECTED`. The HTTP
  status stays `401`, which is correct for the caller; what changes is that `NO_PROVIDER` — the one
  that means *this application is misassembled* — becomes readable without guessing.

Those are precisely the two measurements that shaped Option B′. `ABSTAIN` + `firstMatch` is a
better version of the delegating composite B′ proposed, provided by the platform rather than
hand-rolled into emitted code; and a legible `NO_PROVIDER` turns "the app answers 401 to
everything" from an indistinguishable outage into a diagnosable wiring fault. **Building B′'s
composite against 0.11 would therefore mean emitting machinery the platform supersedes one train
later** — which is how this repository's inert-output findings (D10, D11, the unwired Handlebars
templates) got written in the first place.

So this RFC targets the nominal shape and the slice moves to **0.9.0**, gated on kernel 0.12 and
SDK 0.12 being final — the release-ordering rule forbids shipping against a cross-repo SNAPSHOT.

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

**Fail-closed for the routes the pipeline emits, `ABSTAIN` for everything else, composed by the
kernel's `firstMatch` with a fallback the consumer must choose.** Deferred to 0.9.0, on the kernel
0.12 / SDK 0.12 train.

Concretely, once those land:

- The emitted policy answers `ANY_SCOPE`/`ALL_SCOPES` where a permission is declared,
  `AUTHENTICATED` for the other routes **it emitted**, and `ABSTAIN` for every path it does not
  know. It never speaks for a route it did not generate — which is a property it can now *state*
  rather than approximate.
- `Application` composes `firstMatch(generated, handWritten, fallback)`. The fallback is mandatory,
  so the decision about an undescribed route is made once, explicitly, by the consumer — which is
  what ADR-061 asks for and what no amount of emitted cleverness could supply.
- A misassembled app is diagnosable: `NO_PROVIDER` on the JFR event says "this application has no
  identity wiring", instead of a `401` that looks like every other `401`.

The reasoning, stated plainly because it is a security posture and not an ergonomics preference:

| | Opt-in (A) | Fail-closed for emitted routes |
|---|---|---|
| Forgetting to declare | endpoint is **open**, silently | endpoint answers **401**, loudly |
| Existing generated apps | unchanged | must declare public routes, or install identity, before regenerating |
| Needs a new SDK declaration | no | **yes** — nothing says "this route is public" today (SDK 0.12) |
| Governs hand-written routes | no | no — `ABSTAIN` is the mechanism, not a convention |
| Undescribed route | decided by omission | decided **once, by the consumer**, in the mandatory fallback |

A fails in the direction that hurts: an author who declares permissions on nine entities and
forgets the tenth ships the tenth open, and nothing in the build says so. Fail-closed fails in the
direction that is merely inconvenient. **Between a defect that exposes and a defect that annoys,
the pipeline should choose the second** — the same principle ADR-078's build gate and ADR-079's
removed claim both rest on.

### On the `-Aexeris.routeDefault` flag this RFC previously recommended

Dropped as load-bearing, kept as an escape hatch at most. It existed to buy a migration window for
two problems the platform now solves: a policy that had to answer for paths it never emitted, and a
`401` nobody could attribute. What remains is an ordinary breaking change to emitted behaviour,
announced in the migration guide for the train that carries it — which is what the 0.x versioning
policy already provides for. A flag with a scheduled flip is a promise that drifts; a migration
entry is a fact.

### Why not the alternatives?

**Plain A, permanently.** It makes the absence of a declaration the most permissive outcome, which
is backwards, and it leaves a policy file that protects two entities out of ten reading as though
it were the policy.

**Option B′ against 0.11.** Not wrong, and now unnecessary: it hand-rolled into emitted code the
composite the SPI is about to provide. Shipping it would put a second, weaker combinator in every
generated app one train before the real one arrives, and this repository has enough emitted
machinery nobody runs.

**Plain B against 0.11.** Two measured objections, either sufficient alone: without identity wiring
the app answers `401` to everything with no way to tell that apart from a rejected token, and a
single bound policy answering `AUTHENTICATED` for unrecognised paths would close routes this
pipeline never emitted. Kernel 0.12 removes both.

**C** ships an artefact nothing runs. **D** leaves the emitted frontend guarding on invented names.

### Risks of the recommendation

- **The slice now spans three repositories and two trains.** Kernel 0.12 (`ABSTAIN`, `firstMatch`,
  the JFR reason), SDK 0.12 (the public-route declaration), tooling 0.9.0. If either upstream half
  slips, this RFC's recommendation does not degrade gracefully — it degrades back into Option B′,
  and the decision to wait has to be taken again rather than assumed.
- **`ANY_SCOPE` vs `ALL_SCOPES` is unresolved.** "Permissions required to execute this action" reads
  like ALL; "default permissions required to access this entity's API" reads like ANY.
- **Partial coverage.** Under the `permitAll` default an app that protects two entities has a mostly
  open edge and a policy file that looks authoritative; the emitted policy should name the uncovered
  routes in a comment rather than leave them to inference.
- **Detachment.** After `exeris:detach` the policy is the consumer's file, and a later `permissions`
  change no longer reaches it. Sharper here than for any other artefact, because the stale copy is
  the one deciding who gets in.

## Decision Record

Pending, and deliberately not taken yet: the recommendation depends on two upstream changes that
are decided but not shipped. On acceptance — expected once kernel 0.12 and SDK 0.12 are final —
reserve one ADR number in `exeris-docs/adr-index.md`, cite the ADR-061 amendment the kernel writes
for the `spi.http` addition, delete the two `INERT_ATTRIBUTES` entries for `permissions` in the
change that starts consuming them (the `roles` entries stay), and record the spike outcome for path
matching.

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
3. **The public-route declaration** — fail-closed is meaningless without one. Assigned to SDK 0.12
   on the same train as the kernel half; this RFC assumes it and does not design it.
4. `ANY_SCOPE` vs `ALL_SCOPES`, per site.
5. Whether the emitted policy is detachable, and what a stale detached policy should do.
6. What `roles` compiles into, if anything — currently nothing, deliberately (**not** this RFC).
7. **D10** and **D11** intersect this: the TS `getDefaultHeaders` bearer path and the unwired
   Handlebars templates are both parts of the same unjoined story.
