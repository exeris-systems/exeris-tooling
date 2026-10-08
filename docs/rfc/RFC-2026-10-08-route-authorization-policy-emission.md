---
title: "RFC-2026-10-08: Route authorization policy emission"
type: rfc
visibility: public
owning-repo: exeris-tooling
status: draft
last-verified: 2026-10-08
---

# RFC-2026-10-08: How should the generated application compile `@RouteAccess` and `permissions` into the kernel's route policy?

| Field | Value |
| :--- | :--- |
| **Status** | **DRAFT** |
| **Author(s)** | arkstack-dev |
| **Date Opened** | 2026-10-08 |
| **Date Closed** | — |
| **Target ADR(s)** | TBD |
| **Affected Repos** | `exeris-tooling` (processor, `KernelApplicationGenerator`, a new `GeneratedRoutePolicy` emitter, OpenAPI, `exeris-codegen-ts` guards and HTTP client); `exeris-sdk` (the `-io` reader half, exeris-sdk#161 widened; one Javadoc sentence); `exeris-kernel` (two non-blocking asks) |
| **Reviewers** | — |

## Question

The SDK declares route access (`@RouteAccess`, `@ExerisDomain.permissions`, `@Action.permissions`)
and the kernel can enforce it (`HttpRoutePolicy`, `RouteRequirement`), but no generated code joins the
two, so every generated route is permit-all. **What does the generated application emit so that a
declared permission becomes a kernel route requirement, and what does every route the author did not
declare get?** Six sub-questions decide the shape: how a policy matches a request path (Q1), what an
undeclared route gets (Q2), any-of or all-of over an entity's permissions (Q3), how an action's
permissions compose with its entity's (Q4), where the policy binds (Q5), and what the generated
frontend guards and HTTP client do with it (Q6). Roles (C2) are out of scope, and the 0.10.0 cut needs
a fallback that is not empty.

## Context

ROADMAP **T53** lists seven layers of an authorization story and none of them is joined. ADR-079
removed the OpenAPI security block because the emitted application performs no authentication, and
named what re-adding it needs: extraction, a policy to emit, and a binding seam. The 0.10.0 release
plan's **J3** row holds the cut on this work. **S3** (frontend guards) and **D10** (the bearer header
code path no emitter reaches) wait on it.

Kernel 0.12.0 changed what a generated policy can be. `RouteRequirement.abstain()` and
`HttpRoutePolicy.firstDeclared(List, RouteRequirement)` let a generated policy describe only its own
routes and leave the rest to the application. Without them, a generated policy would have to answer
every route, and so would have to choose the application's unmatched-route stance for it.

Getting this wrong is costly either way. A fail-closed default returns `401` on every route of every
existing generated application, because none of them boots a security subsystem. A fail-open default
leaves a forgotten route public, which is the failure `HttpRoutePolicy` exists to make explicit. If the
generated frontend guards and the backend disagree, the user sees a page the backend refuses, or the
frontend hides a page the backend serves.

## Investigation

Kernel citations are against the `v0.12.0` tag (`git show v0.12.0:<path>`). Tooling citations are
against `main` at `ecc95d63`, SDK citations against `main` at `464bfd6` (SDK `0.13.0-SNAPSHOT`
line). Every line below was read at those revisions.

### The kernel contract

| # | Fact | Where |
| --- | --- | --- |
| K1 | `HttpRoutePolicy.requirementFor(method, path)` must answer every route, never `null`. It is pure, thread-safe and **allocation-free**: it returns pre-built `RouteRequirement` instances. | `exeris-kernel-spi/.../spi/http/HttpRoutePolicy.java:24-38` |
| K2 | `unmatched()` documents the choice for an undeclared route and returns `authenticated()` (fail-closed). Its `@apiNote` warns that fail-closed denies the driver's own liveness and readiness probes unless they are declared `permitAll()`. | `HttpRoutePolicy.java:63-83` |
| K3 | `firstDeclared(List<HttpRoutePolicy>, RouteRequirement whenNoneDeclares)` folds the policies in order and returns the first answer that is not an abstention. `whenNoneDeclares` is required and must not itself be an abstention. A `null` from any policy ends the fold as a defect. The list is copied once, so the fold allocates nothing per request. | `HttpRoutePolicy.java:85-144` (`@since 0.12`) |
| K4 | `RouteRequirement` has the kinds `PERMIT_ALL`, `AUTHENTICATED`, `ANY_SCOPE`, `ALL_SCOPES` and `ABSTAIN` (0.12). There is **no role kind**, by design. `permitAll()` binds neither `PrincipalContext` nor `StorageContext`. `abstain()` is meaningful only inside a composition. | `exeris-kernel-spi/.../spi/http/RouteRequirement.java:25-29, 84-106, 140-171` |
| K5 | `Execution{PROMPT, LONG_RUNNING}` and `longRunning()` (ADR-077). `longRunning()` returns shared constants on the scope-free kinds and throws on an abstention. | `RouteRequirement.java:108-125, 253-271` |
| K6 | An abstention or `null` that reaches the decision point **denies**. It is never read as "no requirement". | `exeris-kernel-core/.../core/security/RouteAuthorizationEnforcer.java:63-71` |
| K7 | The Community processor captures the policy **once, in its constructor**, inside the carrier scope at engine start. A binding made after the http subsystem starts is never seen. | `exeris-kernel-community/.../community/http/CommunityHttpRequestProcessor.java:113-122` |
| K8 | A stream open goes through the same dispatcher and the same authorization code as a request. The stream path binds no request-scoped persistence session. | `CommunityHttpRequestProcessor.java:303-325`; `CommunityHttpRequestDispatcher.java:49-55, 134-153` |
| K9 | With no policy bound, every route is `permitAll()`. A `PERMIT_ALL` requirement **skips the `SecurityInterceptor`**, so the handler runs with no `PrincipalContext` and no `StorageContext`, even when the caller sent a valid token. | `CommunityHttpRequestDispatcher.java:168-172, 179-186` |
| K10 | Any other requirement runs the interceptor. With no security provider bound, the request gets `401`, and the JFR reason is `NO_PROVIDER`. | `CommunityHttpRequestDispatcher.java:213-217` |
| K11 | The only credential the kernel reads is an `Authorization: Bearer …` header. No cookie and no query parameter is read. A native `EventSource` cannot set a header, so **an `EventSource` client cannot authenticate**. | `CommunityHttpRequestDispatcher.java:60-61, 358-397` |
| K12 | The `security` subsystem binds `SECURITY_PROVIDER` when a provider is on the classpath. When none is found it does not run, and the boot does not fail. | `exeris-kernel-community/.../bootstrap/CommunitySecuritySubsystem.java:14-40` |
| K13 | `HttpRouter` resolves a request in this order: exact routes first, then templates in registration order, then prefixes. `HEAD` falls back to `GET`. The router strips the query string before matching. | `exeris-kernel-core/.../core/http/routing/HttpRouter.java:36, 141-160, 171-186, 218-221, 225-235` |
| K14 | `PathTemplate` is **package-private**. A `{name}` placeholder matches exactly one **non-empty** segment, and a trailing slash adds an extra (empty) segment. | `exeris-kernel-core/.../core/http/routing/PathTemplate.java:25, 103-129` (comment at `:112-113`) |
| K15 | The generated policy is the component that must agree with the router on which route a request names, so it applies the router's own normalisation of the request target (K13) rather than relying on the caller's. | `HttpRouter.java:218-221`; `HttpRoutePolicy.java:58` |

### The tooling side

- The generated `Application` binds **one** HTTP slot, `HttpKernelProviders.HTTP_SERVER_HANDLER`, in the
  `ScopedValue.where(...)` that wraps the boot. `HTTP_ROUTE_POLICY` is bound nowhere
  (`exeris-codegen-java/.../kernel/KernelApplicationGenerator.java:613-640`, the binding at `:616`).
  The only mention of `HTTP_ROUTE_POLICY` in either codegen module is the log message of the emitted
  `respondTenantUnbound` (`KernelHandlerGenerator.java:648-666`).
- The subsystem list is derived by `RequiredSubsystems` (`exeris-codegen-core/.../driver/RequiredSubsystems.java:47-57`):
  `http`, `persistence`, `graph`, `flow`, `events`, `crypto`. It never contains `security`, so with a
  policy bound, a route that demands identity gets K10's `401 NO_PROVIDER`.
- The router registers, per entity and in `domains` order, `GET`/`POST {base}`, `GET`/`PUT`/`DELETE {base}/{id}`,
  and `POST {base}/{id}/actions/{action}` for each non-streaming action. Stream routes (`GET {base}/stream`,
  `POST …/actions/{action}` for a streaming action) are registered next. `configureRoutes` runs last,
  so a hand-written route can add to the table but cannot displace a generated route
  (`KernelApplicationGenerator.java:1409-1451`; contracts `exeris-e2e-tests/src/test/resources/contract/crud-routes.json`,
  `stream-routes.json`).
- The processor validates `@RouteAccess(PUBLIC)` against a non-empty `permissions` on the same element
  (`exeris-processor/.../ExerisDomainProcessor.java:1443-1518`), but extracts none of the three
  components. `@Action.permissions`, `@Action.roles`, `@ExerisDomain.permissions`, `@ExerisDomain.roles`
  and `@RouteAccess` are registered as inert (`:287`, `:295`, `:442`, `:467`, `:649`).
- The TS model declares `permissions` and `routeAccess` **on the action only**
  (`exeris-codegen-ts/src/models/domain-model.ts:84-87`). Contract coverage marks both `RESERVED`, not
  written (`src/models/contract-coverage.ts:190-200`). `DomainMetadataSchema` (`domain-model.ts:347-398`)
  declares no `roles`, no `permissions` and no `routeAccess`, and a `z.object` drops undeclared keys
  when it parses. So the entity-level half would disappear on the TS side without any error. S3 adds
  both fields to the entity schema.

### The SDK side

- `SourceModelReader` reads neither roles nor permissions. The Javadoc of `applyDomainAttributes`
  says so: "Array attributes (`tags`/`roles`/`permissions`) … are not read — the processor doesn't read
  them either" (`exeris-sdk-source-model-io/.../io/SourceModelReader.java:340-343`). It reads no
  `routeAccess` either. `grep -n permissions` in the file finds only that Javadoc line.
- **exeris-sdk#161** is OPEN and asks the reader to read `@RouteAccess` only (`DomainMetadata.routeAccess`,
  `ActionMetadata.routeAccess`, `PUBLIC` written explicitly because `ActionMetadata` is `NON_DEFAULT`
  and `PUBLIC` is ordinal 0). It does not cover `DomainMetadata.permissions` or `ActionMetadata.permissions`.
- `@Action.roles` Javadoc: "If empty, action is accessible to all authenticated users"
  (`exeris-sdk-annotations/.../annotation/Action.java:246`). `@RouteAccess` repeats that published meaning
  as the reason why an empty value cannot mean public.
- `@RouteAccess` Javadoc, "Nearest declaration wins": an action-level `@RouteAccess` overrides the
  entity's for that action's route. `Level.AUTHENTICATED` says it is "worth stating explicitly even
  where it is also the generated default". That sentence assumes a fail-closed default, which this
  RFC does not recommend for 0.10.0 (see Q2).
- `@ExerisDomain.permissions` and `@Action.permissions` do not say whether the list is any-of or all-of
  (`ExerisDomain.java:406-418`, `Action.java:252-265`). `@NavMenu.permissions` does: "User must have at
  least one of the listed permissions" (`NavMenu.java:182-188`), and `@Action.roles` says "at least one"
  (`Action.java:237`).
- `ActionMetadata` has no `roles` component. `DomainMetadata` does.

### The frontend side

- `guard-gen.ts` emits `<ENTITY>_PERMISSIONS = { READ: '<entity>:read', … }`, names the author never
  declared (`exeris-codegen-ts/src/generators/angular/guard-gen.ts:67-72`). Every guard also passes
  `auth.hasRole('ADMIN')` (`:79`, `:89`, `:98`, `:107`), a role bypass the backend edge can never
  honour (K4).
- `app-structure-gen.ts` attaches no `canActivate` to any route, so the guards install nowhere.
- D10: `KernelStrategy.getDefaultHeaders` sets `Authorization: Bearer …`
  (`src/core/backend-strategy.ts:204`, `:223-224`; interface `:95`). Its only caller is its own spec.
  No `HttpInterceptor` is emitted anywhere.
- The entity live-view client opens `new EventSource(url, { withCredentials: true })`
  (`stream-client-gen.ts:188`). The per-action stream client is already `fetch`-based, with
  `credentials: 'include'` (`action-stream-client-gen.ts:248-251`). Both send cookies, and K11 says the
  kernel reads none.

### Prior art

`exeris-spring-runtime` ADR-063 compiles Spring-shaped rules **once at startup** into a single
`HttpRoutePolicy` bound into `HTTP_ROUTE_POLICY`. It offers no `hasRole` at the edge and requires an
explicit unmatched answer. That is the same contract from another author. A generated policy that ran
beside it would be composed by `firstDeclared`, which is what ADR-061 amendment A2 added.

### Constraints

- **Allocation-free admission** (K1). The matcher walks the path in place as `PathTemplate` does. It
  creates no substring, no `split`, and no per-request `RouteRequirement`.
- **ADR-042 lockstep.** The processor may not write a component that the `-io` reader in the same
  released pair does not read.
- **Determinism and emitter parity.** One rule from `DomainMetadata` to requirement, which both
  emitters derive and a shared fixture pins.
- **Kernel target only.** The policy is an `HttpRoutePolicy` and nothing else. There is no
  authorization abstraction of tooling's own.

## Options Considered

### Q1 — How does a template match the concrete path?

- **A. The generated policy mirrors the router's rules** *(recommended)*. The emitted
  `GeneratedRoutePolicy` holds, per method, an exact-path table and then templates in the router's
  registration order. A `{x}` placeholder matches one non-empty segment, and a trailing slash adds a
  segment (K13, K14). It ignores a query component by bounding the walk at the first `?`, without a substring, as the router does (K13, K15).
  It tries stream rows before respond-once rows, as the processor does (K8), and answers `HEAD` from
  the `GET` rows (K13). Rows are emitted in the same `domains` order the router is built from, so the
  order fix in J2 ("Metadata load order") covers both. A **conformance test** in `exeris-e2e-tests`
  builds the real `HttpRouter` from the generated route set and asserts, over a path corpus (ids,
  `stream`, trailing slash, double slash, empty segment, query, `HEAD`, cross-entity overlap), that the
  policy declares a requirement exactly where the router reaches a generated handler. *Pro:* no
  kernel dependency, so it ships now. Its behaviour follows the router's, so a path the router does
  not route gets no requirement and nothing more. *Con:* it duplicates a matching rule, and only the
  conformance test keeps the copies equal.
- **B. Kernel ask: a public, template-keyed policy builder** (`HttpRoutePolicy.byTemplate()…`
  over `PathTemplate`). This would remove the copied rule. Filed as a **non-blocking** ask: A ships
  without it, and moving to B later changes no emitted behaviour.
- **C. Requirements on `HttpRouter` registration** (`route(method, path, requirement, handler)`). One
  table, no copy. But it changes the kernel's routing SPI shape, and it puts authorization on the
  router, while ADR-061 placed it in a policy that is separate from routing on purpose. Rejected for
  tooling. A kernel decision if anyone wants it.
- **D. Prefix rules** (`/api/v1/orders/**`). Simple. But it claims every path under the prefix,
  including routes the router never serves and routes the consumer adds through `configureRoutes`.
  **Rejected.**

### Q2 — What does an undeclared route get?

"Declared" means: the entity carries a `@RouteAccess` or a non-empty `permissions`, or the action
carries either.

- **A. `permitAll()` from the generated policy itself.** This is today's behaviour. But the generated
  policy would then answer for routes it did not describe, the misuse `abstain()`'s `@apiNote` names.
- **B. Unmatched → `AUTHENTICATED`** (`HttpRoutePolicy.unmatched()`). Fail-closed. But no generated
  application boots a security subsystem, so every route of every existing application would return
  `401 NO_PROVIDER` (K10), and so would the driver's own probe routes (K2). Even with `security` added,
  a deployment with no `SecurityProvider` on the classpath would answer `401` everywhere.
- **C. The generated policy abstains on undeclared routes, and the fallback is an overridable
  `protected RouteRequirement unmatchedRoutes()` on `Application`, default `permitAll()`**
  *(recommended)*. The generated policy only states what the author declared, and the
  fail-open/fail-closed choice belongs to the application, in one method. The default is fail-open
  and is **documented as such** in the method's Javadoc and in the emitted `Application` Javadoc.
  Flipping the default is left to a pre-1.0 ADR, which needs the probe-route story and a `security`
  default.
- **D. A processor option** (`-Aexeris.routes.unmatched=authenticated`). This makes the security
  stance a build flag, invisible in source, and still needs C's seam to take effect. Rejected.

**Tensions recorded, not resolved here:**

1. `@Action.roles` says empty means "accessible to all authenticated users", and `@RouteAccess.Level.AUTHENTICATED`
   calls itself "also the generated default". Under C, an undeclared route is public. The SDK
   Javadoc and the generated default disagree until the flip ADR. The SDK change is one sentence in
   `RouteAccess.java` ("the generated default is the application's `unmatchedRoutes()`, permit-all
   unless overridden").
2. A tenant-partitioned entity with no declaration stays `PERMIT_ALL`. The interceptor never runs,
   so no `StorageContext` is bound and the handler answers `500` through `respondTenantUnbound`
   (`KernelHandlerGenerator.java:648`). That is today's behaviour, and C does not change it. The fix
   on the author's side is `@RouteAccess(AUTHENTICATED)` on the entity. Deriving `AUTHENTICATED`
   automatically for tenant-scoped entities is an open question (see the end of this RFC).

### Q3 — `ANY_SCOPE` or `ALL_SCOPES` for an entity's `permissions`?

- **A. `ANY_SCOPE`** *(recommended)*. The SDK precedent is any-of (`@NavMenu.permissions`,
  `@Action.roles`). Under all-of, adding a permission to a list *narrows* access, which reads backwards
  to an author listing who may enter.
- **B. `ALL_SCOPES`.** Strictest. But a two-element list would require both scopes on every call, and
  no SDK surface reads a permission list that way.

The requirement applies **uniformly** to every route of the entity: list, create, get, update, delete,
each action without its own declaration, and the live-view stream. A per-verb split (read scopes for
`GET`, write scopes for mutations) needs an SDK carrier that does not exist (`readPermissions` /
`writePermissions` or similar). That is a follow-up, not a 0.10.0 question. The SDK Javadoc of both
`permissions` attributes must say any-of. That sentence goes into the widened exeris-sdk#161.

### Q4 — How do an action's permissions compose with its entity's?

- **A. Nearest declaration wins** *(recommended)*. An action with its own `@RouteAccess` or non-empty
  `permissions` gets only its own requirement. An action without either inherits the entity's. This
  applies the rule `@RouteAccess` already states ("Nearest declaration wins") to `permissions`. The
  processor's `PUBLIC`-versus-permissions refusal already assumes this inheritance
  (`ExerisDomainProcessor.java:1443-1503`). *Consequence, stated:* an action declaring
  `{"order:approve"}` on an entity requiring `{"order:read"}` admits a caller holding only
  `order:approve`.
- **B. Both must hold.** That means one of the entity's scopes **and** one of the action's. It cannot
  be expressed with a single `RouteRequirement`: `ALL_SCOPES` over the concatenation is all-of, not
  any-of-each. It would need a new kernel kind. Rejected.
- **C. Union.** Either list admits. A holder of `order:read` could approve. This widens access, so it
  is rejected.

Resolution table for one route, nearest element first:

| Element declares | Requirement |
| --- | --- |
| `@RouteAccess(PUBLIC)` | `permitAll()` (the processor already refuses it beside `permissions`) |
| non-empty `permissions` (any `@RouteAccess(AUTHENTICATED)` alongside is redundant) | `requiringAnyScope(permissions)` |
| `@RouteAccess(AUTHENTICATED)`, no `permissions` | `authenticated()` |
| nothing → the entity's row; entity declares nothing | `abstain()` (Q2 C) |

### Q5 — Where does the policy bind?

- **A. In the `Application` boot chain** *(recommended)*. The chain becomes
  `ScopedValue.where(HTTP_SERVER_HANDLER, edgeHandler()).where(HttpKernelProviders.HTTP_ROUTE_POLICY, routePolicy())`.
  It binds there because the processor reads the slot once, at engine start (K7). Three
  `protected` hooks are emitted:
  - `routePolicy()` returns `HttpRoutePolicy.firstDeclared(List.of(applicationPolicy(), GeneratedRoutePolicy.INSTANCE), unmatchedRoutes())`.
  - `applicationPolicy()` is the consumer's own rules, abstaining everywhere by default. It is listed
    first, so the application keeps the last word on any route, which `firstDeclared`'s Javadoc gives
    as the reason for its order (K3). This also covers a consumer route added through
    `configureRoutes` that one of the generated templates would otherwise claim.
  - `unmatchedRoutes()` is Q2's fallback.

  `GeneratedRoutePolicy` is a pure function of the metadata, so it is built before boot, needs no
  forwarding, and is a `static final` instance with pre-built requirements.
- **B. A forwarding policy over a slot filled after composition**, the shape of the forwarding edge
  handler (`KernelApplicationGenerator.java:88-92`). This would let `applicationPolicy()` read
  `RuntimeComponents`. But the policy is consulted on every request, so each request would pay a
  volatile read. There would also be a window where the slot is empty and something has to answer,
  and nothing needs this today. Rejected for now. A can move to B without changing a hook signature.
- **C. Status quo.** No binding, and the consumer edits generated code to add one (ADR-079 §"A binding
  seam"). Rejected: it is the gap T53 exists to close.

**Also decided with A:** `RequiredSubsystems` adds `security` to the emitted subsystem list when any
route resolves to something other than `permitAll()` or an abstention. Without it, every
identity-demanding route returns `401 NO_PROVIDER` (K10) even with a provider on the classpath. With
no provider, the subsystem does not run (K12) and the route fails closed, which is correct.

**Open, not decided here:** whether stream rows declare `.longRunning()` (ADR-077). On Community it
changes nothing, because the stream path binds no session (K8). Another driver may react to it. The
rows are emitted `PROMPT` unless the ADR decides otherwise.

### Q6 — What do the frontend guards read, and how does D10 resolve?

- **Guards read the declared names.** Both emitters derive each route's requirement from
  `DomainMetadata` by the Q4 table. A new shared fixture, `exeris-e2e-tests/src/test/resources/contract/route-policy.json`,
  is the sibling of `crud-routes.json` and `stream-routes.json`. It pins (metadata → per-route
  requirement) for both builds. A guard checks `auth.hasAnyPermission(declared)` (any-of, Q3).
  A route that requires `authenticated()` checks `isAuthenticated()`. A public or undeclared route
  gets no guard. `app-structure-gen` attaches `canActivate`. The TS `DomainMetadataSchema` gets the
  entity-level `permissions` and `routeAccess` it lacks.
- **Delete** the invented `<ENTITY>_PERMISSIONS` constants and the `hasRole('ADMIN')` bypass.
  The bypass admits on the frontend a user whom the backend answers `403`.
- **D10 resolves as "wire it, conditionally".** An emitted `HttpInterceptorFn` adds
  `Authorization: Bearer <AuthService.token()>`, and it is emitted and registered only when some route
  demands identity. `KernelStrategy.getDefaultHeaders` is deleted. The interceptor is its one consumer.
- **Consequence for streams:** a native `EventSource` cannot send the header (K11). The entity
  live-view route of an entity that demands identity needs a `fetch`-based client, the shape the
  per-action stream client already has, and that client sends the bearer header. An undeclared or
  public live-view keeps `EventSource`.

### C2 — roles

Out of scope. `RouteRequirement` has no role kind (K4), and the SDK rules out a `ROLE_x`-to-scope
convention. Roles go to the kernel's method-level `@RequiresRole` (kernel ADR-014) on generated
services. That needs the SDK reader to read roles, and `ActionMetadata` to get a `roles` carrier. It
is a follow-up, and C2's `readRoles` / `writeRoles` / `bypassRoles` go with it.

## Testing

- **Router conformance** (Q1): `exeris-e2e-tests` builds `HttpRouter` from the generated route set
  and checks `GeneratedRoutePolicy` against the path corpus described in Q1 A. A case fails when
  the policy declares a requirement where the router reaches no generated handler, or abstains where
  it does.
- **Composition**: `firstDeclared` order, with `applicationPolicy()` overriding a generated row, and
  `unmatchedRoutes()` answering an undeclared route, both against the kernel's own
  `RouteAuthorizationEnforcer.decide(policy, …)`. An abstention never reaches the enforcer unfolded
  (K6).
- **Allocation**: a JFR or `ThreadMXBean` allocation check over `requirementFor` on a hit, a miss,
  and a path with a query string.
- **Admission end to end**: `KernelCodegenCompileTest` for the emitted shape, and one kernel-backed
  test per row kind (`PERMIT_ALL`, `AUTHENTICATED`, `ANY_SCOPE`) asserting `200` / `401` / `403`.
  This includes a stream open and a list request with `?page=0`.
- **Parity**: `route-policy.json` read by a Java e2e test and by a `codegen-ts` spec. The ADR-042
  processor/reader parity suite gets cases for entity, action and inherited permissions and
  `routeAccess`.
- **Determinism**: regenerate twice and diff. Rows are emitted from sorted, ordered metadata.

## Recommendation

**Emit a `GeneratedRoutePolicy` that mirrors the kernel router's matching, declares a requirement only
on routes the author declared (nearest declaration wins, any-of over `permissions`), and abstains
everywhere else. Bind it in the `Application` boot chain through `firstDeclared(List.of(applicationPolicy(),
GeneratedRoutePolicy.INSTANCE), unmatchedRoutes())`, with `unmatchedRoutes()` defaulting to a
documented fail-open `permitAll()`.**

This is the only option set that ships against kernel 0.12.0 without changing the behaviour of an
application that declares nothing. Q2 C plus Q5 A mean that an application without a declaration gets
a policy bound that answers exactly what the unbound slot answered (K9). The first declared permission
changes exactly the routes it covers. The fail-open default is the one real cost. It is stated in the
emitted Javadoc and confined to one overridable method, and the pre-1.0 flip has a named place to
happen.

### The cut — the J3 fallback

The release plan's fallback, "ship the route policy only for what the reader already carries", is
**empty**. The reader carries no `permissions` and no `routeAccess`, and the processor writes neither,
so a policy built from what the reader carries has no rows.

The real fallback is **J3a, the seam only**:

- `routePolicy()`, `applicationPolicy()` and `unmatchedRoutes()` on `Application`;
- `GeneratedRoutePolicy`, abstaining everywhere;
- the `HTTP_ROUTE_POLICY` binding.

Behaviour is identical to today (K9). The processor writes nothing new, so J3a is ADR-042-neutral, and
a consumer gets the binding seam that ADR-079 records as missing. Extraction and the rows follow in the
first release whose SDK pin carries the reader half.

### Upstream asks

- **exeris-sdk#161, widened**: the reader also reads `DomainMetadata.permissions`,
  `ActionMetadata.permissions` and an explicit `PUBLIC`, with processor/reader parity cases for entity,
  action and inherited. Both `permissions` Javadocs say any-of. The `RouteAccess.Level.AUTHENTICATED`
  sentence about the generated default is corrected. It must land in a final SDK before 0.10.0 cuts
  for J3 to ship in full.
- **exeris-kernel, non-blocking**: a public template-keyed policy builder (Q1 B).

### PR order

| PR | Content | Gate |
| --- | --- | --- |
| **J3-0** | The ADR accepting this RFC (number reserved in `exeris-docs/adr-index.md` first). | none |
| **J3-1** | J3a: the seam, `GeneratedRoutePolicy` abstaining everywhere, the binding. A `docs/generators.md` row (ADR-097). | J3-0 |
| **J3-2** | The router-mirroring matcher, emitted from metadata by the Q4 table, plus `route-policy.json` and the conformance test. In every real build the metadata is still empty, so the emitted policy still abstains everywhere. | J3-1 |
| **J3-3** | Processor extraction of `permissions` and `routeAccess`, `security` derivation, removal of the five inert entries. | exeris-sdk#161 widened, in a final SDK |
| **J3-4** | An ADR-079 amendment: the OpenAPI security scheme and per-operation requirement, `401`/`403` in the response sets, only on operations whose row demands identity. | J3-3 |
| **S3** | Entity-level `permissions` and `routeAccess` in the TS `DomainMetadataSchema`; guards from `route-policy.json`, `canActivate`, the conditional bearer interceptor, D10 deletion, the `fetch` live-view client for identity-requiring streams. | J3-2 (shape), J3-3 (data) |

J3a is J3-0 plus J3-1, and it holds the cut. J3-2 changes no emitted behaviour while the metadata is
empty, so it ships in 0.10.0 when it is ready and does not hold the cut. J3-3, J3-4 and S3's data half
ship in 0.10.0 only if the widened reader is in a final SDK first. Otherwise they move whole to 0.11.0.

### Why not the alternatives?

- **Q1 D (prefix rules):** it claims routes the router never serves.
- **Q2 B (fail-closed now):** every existing application answers `401` on every route.
- **Q4 B / C:** B cannot be expressed in one requirement, and C widens access.
- **Q5 B:** a per-request volatile read and an empty window, for a need nobody has.

### Risks of the recommendation

- **Fail-open default.** A new entity with no declaration is public. It is visible in the emitted
  Javadoc and overridable, but it is the default.
- **Copied matching rule.** The conformance test is the only guard. A kernel router change that the
  test's corpus does not exercise could separate the policy from the router silently. Q1 B removes
  the risk.
- **A consumer route claimed by a generated template.** An exact route added through `configureRoutes`
  under an entity's path (for example `GET /api/v1/orders/export`) is matched by the generated
  `/api/v1/orders/{id}` row and gets the entity's requirement. This fails closed when the entity
  declares permissions, and is public when the entity is `@RouteAccess(PUBLIC)`. `applicationPolicy()`
  comes first and is the fix.

## Decision Record

| Field | Value |
| :--- | :--- |
| **Outcome** | — |
| **Date** | — |
| **Resulting ADR(s)** | — |
| **Notes** | — |

## Open questions / follow-ups

- Default `AUTHENTICATED` for tenant-scoped entities, which would turn `500 respondTenantUnbound`
  into `401`. This conflicts with a consumer that binds `STORAGE_CONTEXT` around dispatch by hand,
  which the emitted message offers as an alternative. Owner: the T53 ADR or a follow-up.
- The flip of `unmatchedRoutes()` to fail-closed, with the driver probe routes declared `permitAll()`.
  Owner: a pre-1.0 ADR.
- Per-verb permissions (read/write split). Needs an SDK carrier first.
- Roles through `@RequiresRole` on generated services (C2). Needs the SDK reader to read roles and an
  `ActionMetadata.roles` carrier.
- `.longRunning()` on stream rows (ADR-077). Settled by the T53 ADR or ADR-044 Amendment 2.
