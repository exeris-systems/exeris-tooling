# ADR-079 — The emitted OpenAPI describes no authentication, because the emitted application performs none

- **Status:** ACCEPTED (2026-08-28)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen (emitted artefact contract)
- **Visibility:** public
- **Milestone:** 0.8.0 (backlog item **D8**)
- **Supersedes / superseded by:** —

## Context

The emitted OpenAPI document attached a `bearerAuth` requirement to every operation of every
entity and declared `401` on every operation. Three sites set it:

| Site | What it emitted |
|---|---|
| `OpenApiSecurityBuilder.buildSecuritySchemes` | an HTTP `bearer` scheme, `bearerFormat: JWT` |
| `OpenApiGenerator` (both write paths) + `OpenApiComponentsBuilder` | the scheme, and a document-level requirement naming it |
| `OpenApiPathsBuilder.buildResponses` | `401 Unauthorized`, on every operation |

`grep -rn UNAUTHORIZED exeris-codegen-java/src/main/java/` returns nothing, which was the first
signal. It is not sufficient evidence on its own: the kernel, not the emitted handler, is what
answers `401` in this architecture. So the claim was measured against the kernel the emitted app
actually runs on.

### Measured, against kernel 0.11

1. `CommunityHttpRequestProcessor` reads `HttpKernelProviders.httpRoutePolicy()` — an `Optional`
   over a `ScopedValue` slot the **application** binds — and hands the result to
   `CommunityHttpRequestDispatcher`.
2. With nothing bound, `CommunityHttpRequestDispatcher#authorize` resolves every route to
   `RouteRequirement.permitAll()`. Its own comment states the consequence: *"an application that
   declares nothing carries no edge authorization at all."*
3. A `PERMIT_ALL` route is admitted **without running the `SecurityInterceptor`**. The dispatcher
   says so directly: *"No requirement means no interceptor run, so the handler sees no
   `PRINCIPAL_CONTEXT` even when the caller presented a valid token."* No token is read, no
   identity is bound, and the `401` the dispatcher is otherwise perfectly able to write is
   unreachable for that route.
4. No emitter binds `HTTP_ROUTE_POLICY` — zero references across `exeris-codegen-java` and
   `exeris-codegen-core`. The emitted `Application` binds exactly one HTTP slot,
   `HttpKernelProviders.HTTP_SERVER_HANDLER`, around the boot call.

So every emitted route is permit-all, and the emitted spec advertised an authentication that
provably does not happen. This is the most consequential shape of the "spec over-promises"
defect that ADR-076 named in the write-rejection path, because the reader most likely to trust
it is the one deciding whether the endpoint is safe to expose.

Second, smaller half, same root cause — one response set for every operation shape:

- the collection `GET` and the create `POST` declared `404` with no id in the path to miss;
- the collection `GET` declared `400` while parsing no id and decoding no body;
- a **versioned** `PUT` declared `404` *and* `409`, where ADR-076's emitted catch raises the
  conflict alone.

## 🏁 The Decision

**Emit no security requirement and no security scheme, and declare per operation exactly the
statuses that operation's emitted handler can answer.**

`OpenApiSecurityBuilder` is deleted; the requirement is removed from both `OpenApiGenerator`
write paths and the scheme from `OpenApiComponentsBuilder`. `buildResponses` is replaced by a
`Responses` set named at each call site:

| Route | Declares |
|---|---|
| `GET` collection | `200`, `500` |
| `POST` collection | `201`, `400`, `500` |
| `GET /{id}` | `200`, `400`, `404`, `500` |
| `PUT /{id}` (unversioned) | `200`, `400`, `404`, `500` |
| `PUT /{id}` (versioned) | `200`, `400`, `409`, `500` |
| `DELETE /{id}` | `204`, `400`, `404`, `500` |
| `POST /{id}/actions/…` | `200`, `400`, `404`, `500` (+ `409` when versioned) |

`500` closes every set because every emitted route ends in a `catch (RuntimeException)` that
answers it, and a tenant-partitioned entity answers it from the tenant guard as well.

### Why removed rather than gated on a declaration

The alternative was to keep the block and emit it only where an author declared that a route
needs identity. It is unavailable twice over.

**There is no declaration.** The SDK's only security-adjacent annotations are `@RowLevelSecurity`
and `@TenantId`, and both scope *data* — which rows a bound context may see — not the edge.
Nothing in the annotation surface says "this route requires a caller."

**And a gated claim would still be false.** The closest candidate is the tenant-partitioned
entity, where the emitted handler genuinely cannot serve a request without a bound context. But
what it emits for that case is `respondTenantUnbound` → `500`, not `401`: with no route policy
the interceptor never runs, so nothing rejects the anonymous caller and the request reaches a
handler that cannot serve it. A spec claiming `401` there would name the wrong status for the
one case that seemed to justify it.

### What re-adding the block would need

A slice of its own, with both halves it lacks today:

1. **A declaration surface** — an annotation an author writes to say a route requires identity,
   and a metadata field carrying it.
2. **A binding seam** — `HTTP_ROUTE_POLICY` is a `ScopedValue`, so the only place to bind one is
   the `ScopedValue.where(...)` chain inside the emitted `Application`. ADR-070's
   `RuntimeComponents` seam does not reach it: it carries `create*` factories and a
   `configureRoutes` hook, neither of which can install a provider around boot. A consumer today
   cannot bind a policy without editing generated code.

Recorded here so the follow-up is scoped by the measurement rather than rediscovered from the
spec.

### Carried in the same change: the tenant-guard message

The emitted `respondTenantUnbound` told the consumer to *"install the kernel SecurityInterceptor
… ahead of this router"*. At 0.11 that is inoperative: the interceptor is already constructed
into the dispatcher, and what decides whether it runs is the route requirement. The message now
names the operative step — bind `HttpKernelProviders.HTTP_ROUTE_POLICY`, or bind
`KernelProviders.STORAGE_CONTEXT` around the dispatch — and says why nothing binds a context
today. Same defect as the spec, in a different artefact: emitted text asserting a runtime shape
that is not there.

## Consequences

### ✅ Positive Outcomes

- A client generator fed the emitted spec no longer emits an `Authorization` header for a server
  that ignores it, and a reviewer reading the spec sees the security posture the app actually has.
- The response sets are now derivable from the handler: each call site names what its route
  answers, so a future route shape that answers something new has one place to say so.
- The versioned `PUT` finally agrees with ADR-076's emitted catch instead of declaring both
  statuses.

### ⚠️ Trade-offs

- **The spec now says the API is unauthenticated, and it is.** That is a truthful document about
  an unfinished capability, not a regression — but a consumer who read the old spec as a promise
  will read the change as one being withdrawn. The migration entry states it in those terms.
- A deployment that *does* front the app with identity — by editing the generated `Application`
  to bind a policy — now has a spec that under-describes it. That direction is the safe one: a
  spec that under-promises fails closed for the reader.
- `401` disappears from the response sets even though the kernel can write it. It returns when
  tooling emits a policy that makes it reachable.

### 📋 What is NOT in scope

- Emitting an `HttpRoutePolicy`, or any annotation that would declare one.
- `403`. `RouteAuthorizationEnforcer` can answer it, and for the same reason nothing emitted can
  reach it.
- The `null`-valued fields the swagger YAML mapper writes into the emitted document. Noted while
  measuring, recorded as a separate finding, not fixed here.

## Cross-references

- **ADR-076** — the write-rejection status decision; same defect family (a spec naming a status
  the code cannot give), different artefact.
- **ADR-070** — the `RuntimeComponents` seam, and the reason it does not reach a provider bound
  around boot.
- **kernel ADR-061** — the removal of the compiled-in `/secure` prefix convention, which is why an
  application that declares nothing carries no edge authorization.

### Verification

- `OpenApiGeneratorTest#emitsNoSecurityBlock` — both write paths, model and emitted text.
- `OpenApiPathsBuilderTest#noOperationDeclaresAnUnreachableUnauthorized` — every operation of
  every route shape, CRUD and action, versioned and not.
- `OpenApiPathsBuilderTest#responseSetsFollowTheRouteShape` — `containsOnlyKeys` per route, so an
  added status fails rather than passing unnoticed.
- `OpenApiComponentsBuilderTest#buildsThreeSchemas` — no scheme on the per-entity components.
- Each was perturbed and observed to fail: re-adding the document requirement, re-adding `401` to
  the by-id `GET`, and re-adding `404` to the collection `GET`.
