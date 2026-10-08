---
title: "ADR-105 — The generated application compiles the declared route access into the kernel's route policy"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-105
---

# ADR-105 — The generated application compiles the declared route access into the kernel's route policy

- **Status:** ACCEPTED (2026-10-08) · accepted-on-merge per the per-repo pattern (ADR-047 / ADR-058)
- **Deciders:** the founder; `exeris-tooling` (policy emitter, `Application` seam, Angular guards)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen — per-repo; the SDK work it needs is an `exeris-sdk` issue
  (exeris-sdk#161, widened), not a stub
- **Visibility:** public
- **Milestone:** 0.10.0 (`docs/0.10.0-release-plan.md`, wave J3, with S3 for the frontend)
- **Relates to:** ADR-079 (the OpenAPI security block and the binding seam it names as missing),
  ADR-042 (the processor and the SDK `-io` reader agree on metadata), ADR-044 (stream routes),
  ADR-070 (the composition-root seam), ADR-097 (the generator catalogue), ADR-077 (kernel
  `RouteRequirement.Execution`), ADR-015 and ADR-092 (output stability)
- **Supersedes / superseded by:** —
- **Decided in:** `docs/rfc/RFC-2026-10-08-route-authorization-policy-emission.md`

## Context and Problem Statement

The SDK declares route access: `@RouteAccess`, `@ExerisDomain.permissions` and
`@Action.permissions`. The kernel can enforce it: `HttpRoutePolicy` answers a `RouteRequirement` for
every method and path. No generated code joins the two, so every generated route is permit-all.

- The generated `Application` binds one HTTP slot, `HttpKernelProviders.HTTP_SERVER_HANDLER`, in
  the `ScopedValue.where(...)` that wraps the boot (`KernelApplicationGenerator`). It binds
  `HTTP_ROUTE_POLICY` nowhere. With no policy bound, the kernel answers `permitAll()` for every
  route, and a `PERMIT_ALL` route skips the `SecurityInterceptor`, so the handler runs with no
  `PrincipalContext` and no `StorageContext` even when the caller sent a valid token.
- The processor validates `@RouteAccess(PUBLIC)` against a non-empty `permissions`
  (`ExerisDomainProcessor`) but extracts none of the three declarations; the five attributes are
  registered as inert under `-Aexeris.strict`. The SDK `-io` reader (`SourceModelReader`) reads
  neither `permissions` nor `routeAccess`, so exeris-sdk#161, which asks for `routeAccess` only,
  is not enough.
- `RequiredSubsystems` derives `http`, `persistence`, `graph`, `flow`, `events` and `crypto`; it never
  derives `security`. A route that demands identity would answer `401` with reason `NO_PROVIDER`
  even with a provider on the classpath.
- The Angular `guard-gen.ts` emits `<ENTITY>_PERMISSIONS` constants the author never declared, and
  every guard also passes `auth.hasRole('ADMIN')`, a role bypass the backend edge cannot honour
  because `RouteRequirement` has no role kind. `app-structure-gen.ts` attaches no `canActivate`, so
  no guard is installed. `KernelStrategy.getDefaultHeaders` is the only code that sets
  `Authorization: Bearer ...`, and nothing but its own spec calls it (D10).
- ADR-079 removed the OpenAPI security block because the emitted application authenticates nobody,
  and named what re-adding it needs: extraction, a policy to emit, and a binding seam.

Kernel 0.12.0 made a partial policy possible: `RouteRequirement.abstain()` and
`HttpRoutePolicy.firstDeclared(List, RouteRequirement)` let a generated policy describe only the
routes the author declared and leave the rest to the application.

Either default for an undeclared route is costly. Fail-closed answers `401` on every route of every
existing generated application, because none boots a security subsystem. Fail-open leaves a forgotten
route public.

**The question this ADR answers:** what does the generated application emit so that a declared
permission becomes a kernel route requirement, and what does every route the author did not declare
get?

## 🏁 The Decision

**The generated application emits a `GeneratedRoutePolicy` that mirrors the kernel router's
matching, declares a requirement only on routes the author declared (nearest declaration wins,
any-of over `permissions`), and abstains everywhere else. The `Application` binds it as
`HTTP_ROUTE_POLICY` through `firstDeclared(List.of(applicationPolicy(), GeneratedRoutePolicy.INSTANCE),
unmatchedRoutes())`, where `unmatchedRoutes()` defaults to a documented fail-open `permitAll()`.**

An application that declares nothing gets a policy bound that answers exactly what the unbound slot
answered. The first declared permission changes exactly the routes it covers.

**Concrete obligations — matching (Q1):**

1. **The policy mirrors the router.** `GeneratedRoutePolicy` holds, per method, an exact-path table
   and then templates in the router's registration order. It follows these rules of `HttpRouter`:
   - exact routes are tried before templates, and templates in registration order;
   - a `{x}` placeholder matches exactly one non-empty segment;
   - a trailing slash adds an extra segment;
   - the query component is ignored: the walk is bounded at the first `?`, without a substring;
   - `HEAD` is answered from the `GET` rows;
   - stream rows are tried before respond-once rows, as the processor registers them.
2. **Rows follow the router's order.** Rows are emitted in the same `domains` order the router is
   built from, from sorted, ordered metadata (ADR-015 determinism).
3. **Allocation-free.** `requirementFor` walks the path in place. It creates no substring, no
   `split`, and no per-request `RouteRequirement`; every requirement is pre-built in a
   `static final` table.
4. **A conformance test holds the copy.** A test in `exeris-e2e-tests` builds the kernel's real
   `HttpRouter` from the generated route set and asserts, over a path corpus (ids, `stream`,
   trailing slash, double slash, empty segment, query component, `HEAD`, cross-entity overlap), that
   the policy declares a requirement exactly where the router reaches a generated handler, and
   abstains where it does not. A corpus case fails in both directions.
5. **Kernel ask B is non-blocking.** A public, template-keyed policy builder in the kernel would
   remove the copied rule. It is filed as an ask; this ADR ships without it, and moving to it later
   changes no emitted behaviour.

**Concrete obligations — undeclared routes (Q2):**

6. **The generated policy abstains on an undeclared route.** "Declared" means the entity carries a
   `@RouteAccess` or a non-empty `permissions`, or the action carries either. The generated policy
   never answers for a route it did not describe.
7. **`unmatchedRoutes()` is the application's stance.** The generated `Application` emits
   `protected RouteRequirement unmatchedRoutes()`, overridable, default `permitAll()`. The fallback
   is one method.
8. **The fail-open default is documented.** The Javadoc of `unmatchedRoutes()` and of the emitted
   `Application` states that an undeclared route is public. Flipping the default is left to a
   pre-1.0 ADR, which needs the probe-route story and a `security` default.
9. **Two tensions are recorded, not resolved.**
   - `@Action.roles` says an empty value means "accessible to all authenticated users", and
     `@RouteAccess.Level.AUTHENTICATED` calls itself "also the generated default". Under obligation 7
     an undeclared route is public. The SDK sentence becomes "the generated default is the
     application's `unmatchedRoutes()`, permit-all unless overridden"; it travels in exeris-sdk#161.
   - A tenant-partitioned entity with no declaration stays `PERMIT_ALL`. The interceptor never runs,
     so no `StorageContext` is bound and the handler answers `500` through `respondTenantUnbound`.
     That is today's behaviour and is unchanged. The author's fix is
     `@RouteAccess(AUTHENTICATED)` on the entity.

**Concrete obligations — scope kind and composition (Q3, Q4):**

10. **`permissions` is any-of.** A non-empty `permissions` list becomes
    `RouteRequirement.requiringAnyScope(permissions)` (`ANY_SCOPE`). The SDK precedent is any-of
    (`@NavMenu.permissions`, `@Action.roles`); under all-of, adding a permission would narrow
    access. Both `permissions` Javadocs say any-of; the sentences travel in exeris-sdk#161.
11. **Uniform over an entity's routes.** The requirement applies to every route of the entity:
    list, create, get, update, delete, each action without its own declaration, and the live-view
    stream route. A per-verb split needs an SDK carrier that does not exist and is a follow-up.
12. **Nearest declaration wins.** An action with its own `@RouteAccess` or non-empty `permissions`
    gets only its own requirement; its non-empty list replaces the entity's. An action with neither
    inherits the entity's. The resolution table, nearest element first:

    | Element declares | Requirement |
    | --- | --- |
    | `@RouteAccess(PUBLIC)` | `permitAll()` |
    | non-empty `permissions` | `requiringAnyScope(permissions)` |
    | `@RouteAccess(AUTHENTICATED)`, no `permissions` | `authenticated()` |
    | nothing, entity declares something | the entity's row |
    | nothing, entity declares nothing | `abstain()` |

13. **The consequence is stated.** An action declaring `{"order:approve"}` on an entity requiring
    `{"order:read"}` admits a caller holding only `order:approve`.

**Concrete obligations — binding (Q5):**

14. **The binding is in the boot chain.** The emitted chain becomes
    `ScopedValue.where(HTTP_SERVER_HANDLER, edgeHandler()).where(HttpKernelProviders.HTTP_ROUTE_POLICY, routePolicy())`.
    The kernel's HTTP processor captures the policy once, at engine start, so a binding made after
    the http subsystem starts is never seen.
15. **Three `protected` hooks.**
    - `routePolicy()` returns `HttpRoutePolicy.firstDeclared(List.of(applicationPolicy(),
      GeneratedRoutePolicy.INSTANCE), unmatchedRoutes())`.
    - `applicationPolicy()` is the consumer's own rules and abstains everywhere by default. It is
      listed first, so the application keeps the last word on any route, including a route added
      through `configureRoutes` that a generated template would otherwise claim.
    - `unmatchedRoutes()` is obligation 7.
16. **`GeneratedRoutePolicy.INSTANCE` is built before boot.** It is a pure function of the metadata,
    a `static final` instance with pre-built requirements; it needs no forwarding.
17. **`security` is derived.** `RequiredSubsystems` adds `security` to the emitted subsystem list
    when any row resolves to something other than `permitAll()` or an abstention. With no provider on
    the classpath the subsystem does not run and the route fails closed, which is correct.
18. **Stream rows and `.longRunning()` are decided in J3-2** (ADR-077). Until then stream rows are
    emitted `PROMPT`. On the Community driver the choice changes nothing, because the stream path
    binds no request session.
19. **Generator catalogue.** `GeneratedRoutePolicy` takes a `docs/generators.md` row and a
    catalogue row in the pull request that emits it (ADR-097).

**Concrete obligations — frontend (Q6):**

20. **One shared contract file.** `exeris-e2e-tests/src/test/resources/contract/route-policy.json`
    is the sibling of `crud-routes.json` and `stream-routes.json`. It pins metadata to
    per-route requirement for both builds, and a Java e2e test and a `codegen-ts` spec read it.
21. **Guards read the declared names.** A guard checks `auth.hasAnyPermission(declared)` (any-of).
    A route whose requirement is `authenticated()` checks `isAuthenticated()`. A public or undeclared
    route gets no guard. `app-structure-gen` attaches `canActivate`. The TS `DomainMetadataSchema`
    gains the entity-level `permissions` and `routeAccess` it lacks; a `z.object` drops undeclared
    keys, so the entity-level half would otherwise disappear without an error.
22. **The invented constants and the role bypass are deleted.** The `<ENTITY>_PERMISSIONS` constants
    and the `auth.hasRole('ADMIN')` bypass go. The bypass admits on the frontend a user whom the
    backend refuses.
23. **D10: the bearer interceptor is wired, conditionally.** An emitted `HttpInterceptorFn` adds
    `Authorization: Bearer <AuthService.token()>`. It is emitted and registered only when some route
    demands identity. `KernelStrategy.getDefaultHeaders` is deleted; the interceptor is its one
    consumer.
24. **Identity-requiring stream routes use the `fetch`-based client.** The kernel reads only an
    `Authorization: Bearer` header, and a native `EventSource` cannot set one. The live-view route of
    an entity that demands identity is served by a `fetch`-based client, the shape the per-action
    stream client already has, and that client sends the bearer header. An undeclared or public
    live-view keeps `EventSource`.

**Concrete obligations — scope limits and ADR-042:**

25. **Roles are out of scope (C2).** `RouteRequirement` has no role kind. Roles go to the kernel's
    method-level `@RequiresRole` on generated services, which needs the SDK reader to read roles and
    an `ActionMetadata.roles` carrier. A follow-up.
26. **Extraction waits on the SDK reader half (ADR-042).** The processor extracts `permissions` and
    `routeAccess` only in the release whose pinned SDK `-io` reader reads them the same way. The
    reader half is exeris-sdk#161, widened: `DomainMetadata.permissions`, `ActionMetadata.permissions`
    and an explicit `PUBLIC`, with processor/reader parity cases for entity, action and inherited
    declarations.
27. **The cut fallback is J3a.** If the widened reader is not in a final SDK before 0.10.0 cuts,
    J3 ships the seam only: `routePolicy()`, `applicationPolicy()`, `unmatchedRoutes()`, the
    `HTTP_ROUTE_POLICY` binding, and a `GeneratedRoutePolicy` that abstains everywhere. Behaviour is
    identical to today and the processor writes nothing new. Extraction, the table's data and the
    OpenAPI security block move to 0.11.0.

## Consequences

### ✅ Positive Outcomes

- **[+] A declaration has an effect.** `@RouteAccess` and `permissions` become kernel route
  requirements, and `-Aexeris.strict` stops reporting the five attributes as inert.
- **[+] No behaviour change for an application that declares nothing.** The bound policy answers what
  the unbound slot answered.
- **[+] The application keeps the last word.** `applicationPolicy()` is consulted first and
  `unmatchedRoutes()` is one method.
- **[+] The frontend and the backend read one table.** Guards no longer invent names or admit a role
  the edge cannot honour.
- **[+] ADR-079's missing seam exists.** The binding ships even if extraction slips.

### ⚠️ Trade-offs

- **[-] Fail-open by default.** A new entity with no declaration is public. The Javadoc says so and
  one method overrides it, but it is the default.
- **[-] A copied matching rule.** The conformance test is the only guard. A kernel router change
  outside the test's corpus could separate the policy from the router silently. Kernel ask B removes
  the risk.
- **[-] A consumer route can be claimed by a generated template.** An exact route added through
  `configureRoutes` under an entity's path (for example `GET /api/v1/orders/export`) is matched by
  the generated `/api/v1/orders/{id}` row and gets the entity's requirement. This fails closed when
  the entity declares permissions and is public when the entity is `@RouteAccess(PUBLIC)`.
  `applicationPolicy()` is the fix.
- **[-] Action permissions replace the entity's.** Obligation 13.

### 📋 What is NOT in scope

- **Roles.** Obligation 25.
- **Per-verb permissions** (a read/write split). Needs an SDK carrier first.
- **Flipping `unmatchedRoutes()` to fail-closed**, with the driver probe routes declared
  `permitAll()`. A pre-1.0 ADR.
- **Defaulting `AUTHENTICATED` for tenant-scoped entities**, which would turn the `500` from
  `respondTenantUnbound` into `401`. It conflicts with a consumer that binds `STORAGE_CONTEXT` around
  dispatch by hand. A follow-up.
- **The OpenAPI security scheme and per-operation requirement.** An ADR-079 amendment, J3-4.

### 🚫 Non-Goals

- **Prefix rules** (`/api/v1/orders/**`). Rejected: they claim paths the router never serves and
  routes the consumer adds through `configureRoutes`.
- **Requirements on `HttpRouter` registration.** Rejected for tooling: it changes the kernel's
  routing SPI, and ADR-061 placed authorization in a policy separate from routing on purpose.
- **`permitAll()` from the generated policy itself.** Rejected: the policy would answer for routes it
  did not describe.
- **Fail-closed now** (`HttpRoutePolicy.unmatched()`). Rejected: every route of every existing
  application, and the driver's probe routes, would answer `401`.
- **A processor option for the unmatched stance** (`-Aexeris.routes.unmatched`). Rejected: the
  security stance becomes a build flag, invisible in source.
- **Both-must-hold or union composition of action and entity permissions.** Rejected: the first
  cannot be expressed in one `RouteRequirement`, the second widens access.
- **A forwarding policy over a slot filled after composition.** Rejected for now: a volatile read on
  every request and an empty window, for a need nobody has. The hooks can move to it without a
  signature change.

### ⚠️ Risks and Assumptions

- **Assumes:** kernel 0.12.0's `abstain()` and `firstDeclared`, and a policy slot read once at
  engine start (obligation 14).
- **Assumes:** a deployment that declares a permission also puts a `SecurityProvider` on the
  classpath. Without one the route answers `401`, which fails closed.
- **Risk:** the ADR-042 gate. Extraction waits on a final SDK; if it does not arrive, obligation 27
  applies and the table has no rows in 0.10.0.
- **Risk:** the conformance corpus misses a router case. Mitigated by testing in both directions and
  by kernel ask B.
- **Reversed by:** a kernel policy builder keyed by template (obligations 1 to 5 reduce to a
  transcription), or a pre-1.0 decision to fail closed (obligations 7 and 8).

## Cross-references

- **ADR-079** — this ADR supplies the policy and the binding seam; the OpenAPI security block is
  re-added last, by amendment.
- **ADR-042** — obligations 26 and 27.
- **ADR-044** — the stream routes the policy covers; obligation 18 may be recorded there as an
  amendment instead.
- **ADR-070** — the `Application` seam the hooks extend.
- **ADR-097** — obligation 19.
- **ADR-077** — kernel `RouteRequirement.Execution`.
- **ADR-015** and **ADR-092** — determinism and output stability, Java and TypeScript.
- Kernel v0.12.0, `exeris-kernel-spi/.../spi/http/HttpRoutePolicy.java` (`requirementFor` `:24-38`,
  `unmatched` `:63-83`, `firstDeclared` `:85-144`) and `RouteRequirement.java` (kinds `:25-29`,
  `abstain` and `requiringAnyScope` `:84-106`, `:140-171`);
  `exeris-kernel-core/.../core/http/routing/HttpRouter.java` (resolution order `:36`, `:141-186`;
  request-target handling `:218-235`) and `PathTemplate.java` (`:25`, `:103-129`);
  `exeris-kernel-core/.../core/security/RouteAuthorizationEnforcer.java:63-71` (an abstention that
  reaches the decision point denies);
  `exeris-kernel-community/.../community/http/CommunityHttpRequestProcessor.java:113-122` and
  `CommunityHttpRequestDispatcher.java:168-217, 358-397`;
  `exeris-kernel-community/.../bootstrap/CommunitySecuritySubsystem.java:14-40`.
- Tooling, by symbol: `KernelApplicationGenerator`, `RequiredSubsystems`, `KernelHandlerGenerator`
  (`respondTenantUnbound`), `ExerisDomainProcessor`, `guard-gen.ts`, `app-structure-gen.ts`,
  `stream-client-gen.ts`, `action-stream-client-gen.ts`, `KernelStrategy.getDefaultHeaders`,
  `DomainMetadataSchema`.
- SDK, by symbol: `RouteAccess`, `ExerisDomain#permissions`, `Action#permissions`, `Action#roles`,
  `SourceModelReader`.

## Engineering Protocol

1. **J3-1, this ADR.** Accepts RFC-2026-10-08. No code.
2. **J3-2, the seam (J3a).** `routePolicy()`, `applicationPolicy()`, `unmatchedRoutes()`, the
   `HTTP_ROUTE_POLICY` binding, and a `GeneratedRoutePolicy` that abstains everywhere, with its
   `docs/generators.md` and catalogue rows. Decides stream rows and `.longRunning()` (obligation 18).
   Output for an application that declares nothing is behaviourally identical to today. This is the
   cut fallback and it holds the cut. A migration fragment under `docs/migration/0.10.0/` records the
   new hooks.
3. **J3-3, extraction, table and conformance.** The router-mirroring matcher emitted from metadata by
   the obligation 12 table, `route-policy.json`, the conformance test, processor extraction of
   `permissions` and `routeAccess`, the `security` derivation, and removal of the five inert
   entries. Gated on exeris-sdk#161, widened, in a final SDK (ADR-042). Otherwise it moves whole to
   0.11.0. Determinism re-run required.
4. **S3, TypeScript.** Obligations 20 to 24, after J3-2 for shape and J3-3 for data. Parity check
   against `route-policy.json` required.
5. **J3-4, ADR-079 amendment.** The OpenAPI security scheme and per-operation requirement, and
   `401`/`403` in the response sets, only on operations whose row demands identity. Gated on J3-3.
6. **Upstream asks.** exeris-sdk#161 widened (reader, two Javadoc sentences, parity cases);
   exeris-kernel, non-blocking, a template-keyed policy builder (obligation 5).
7. Migration owner: `exeris-tooling`, target 0.10.0, release-plan wave J3.
