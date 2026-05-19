---
name: exeris-tooling-kernel-target-discipline
description: Single-target discipline for exeris-tooling. Use whenever a PR proposes a new generator shape, a backend-strategy parameter, or anything that could reintroduce the Spring/Quarkus/Micronaut/Vanilla multi-backend abstraction removed in 0.1.0.
---

# Exeris Tooling Kernel-Target Discipline

## Purpose
The 0.1.0 release deliberately removed Spring/Quarkus/Micronaut/Vanilla generators and deleted the multi-backend abstraction. This skill is the gate that keeps the single-target story intact under PR pressure.

## When to Use
- Any PR adding a new `*Generator` class.
- Any PR adding a backend-selection parameter, registry key, or feature flag in `exeris-codegen-core`.
- Any PR whose stated motivation is "support framework X" or "host on runtime Y".
- Any PR that touches the `GeneratorRegistry` discovery / strategy surface.

## Required Inputs
- PR diff and stated motivation.
- Target downstream user (if motivation is "user X needs Spring hosting").
- Current `GeneratorRegistry` shape.

## Review Procedure
1. **Detect backend-specific output** — scan emitted text for Spring `@RestController`, Quarkus `@Path`, Micronaut `@Controller`, plain `HttpServlet`, JAX-RS `@Path`. Any match is a hard signal of multi-backend regression.
2. **Detect backend-specific imports** — emitted Java imports should target `eu.exeris.kernel.*` (and supporting JDK / Jackson). Imports targeting `org.springframework.*`, `io.quarkus.*`, `io.micronaut.*`, `jakarta.ws.rs.*`, `javax.ws.rs.*` are a hard signal.
3. **Detect strategy reintroduction** — search for `BackendStrategy`, `BackendType` (re-added), `if (backend == ...)` branching, multiple parallel `Kernel*Generator` / `Spring*Generator` shapes.
4. **Check the motivation** — if the user need is "Spring hosting", the right answer is "use `exeris-spring-runtime` for migration", not "regenerate Spring code here". Cite the cross-repo split.
5. **ADR check** — multi-target reintroduction requires a NEW ADR (single-target was the 0.1.0 decision). Don't allow it through a `*Generator` PR.
6. **Decision and report** — produce one of: `APPROVE`, `CONDITIONAL`, `REJECT`.

## Decision Logic
- **APPROVE**: Output targets `eu.exeris.kernel.*` only; no backend-strategy parameter; motivation is kernel-aligned.
- **CONDITIONAL**: Motivation is a downstream need that has a non-codegen answer (e.g. point at `exeris-spring-runtime`); recommend redirecting the PR.
- **REJECT**: Backend-specific output / imports / strategy reintroduction without a new ADR; assume rollback intent.

## Completion Criteria
- Output scanned for backend-specific shape.
- Imports scanned for framework targeting.
- Strategy / registry shape audited.
- Cross-repo redirect proposed when motivation is a different repo's job.
- Verdict and ADR requirement recorded.

## Review Output Template
1. **Scope analysed** (generators / registry / core touched)
2. **Backend-shape findings** (annotations, imports, strategy)
3. **Motivation audit** (kernel-aligned vs cross-repo redirect)
4. **ADR requirement** (none / new ADR required)
5. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
6. **Required actions** (precise and minimal)

## Non-Negotiable Rules
- Never approve a `Spring*Generator` / `Quarkus*Generator` / `Micronaut*Generator` / `Vanilla*Generator` without a new ADR overriding the 0.1.0 single-target decision.
- Never approve a `BackendStrategy` reintroduction.
- Always redirect Spring-hosting motivation to `exeris-spring-runtime`.
