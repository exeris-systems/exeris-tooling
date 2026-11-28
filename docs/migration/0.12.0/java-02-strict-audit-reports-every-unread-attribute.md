---
title: "0.12.0 migration step: -Aexeris.strict reports every attribute no generator reads"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-28
---

### `-Aexeris.strict` reports every attribute no generator reads

Under `-Aexeris.strict`, the processor warns, as `EXT-PROC-1201`, on each attribute below when the
source sets it explicitly, and as `EXT-PROC-1202` on `@InternalApi`. An attribute left at its default
is silent. Generated output does not change: each attribute listed already had no effect, and the
build now says so.

| Annotation | Reported attributes |
| --- | --- |
| `@InternalApi` | the annotation itself: the processor reads only its presence and no generator reads that |
| `@Validation` | `asyncValidator`, `customValidator`, `decimalMax`, `decimalMin`, `email`, `future`, `futureOrPresent`, `groups`, `message`, `negative`, `negativeOrZero`, `past`, `pastOrPresent`, `positive`, `positiveOrZero`, `url`, `uuid` |
| `@Relationship` | `allowCreate`, `allowInlineCreate`, `allowInlineEdit`, `autocompleteThreshold`, `cascadeUpdate`, `dependsOn`, `dependsOnField`, `display`, `displayFields`, `displayTemplate`, `fetchType`, `filter`, `icon`, `labelField`, `orderBy`, `required`, `searchFields`, `searchable` |
| `@Graph` | the attributes the disposition registry holds as unread, among them `idProperty`, `syncToGraph`, `similarityAlgorithm` and `graphName`; `nodeClass` is read |
| `@GraphEdge` | `description`, `inverseType`, `properties`, `propertyMappings`, `staticProperties`, `upsert`, `weighted` |
| `@View` | `titleKey`, `layout` |
| `@Action`, `@ActionParam`, `@GraphProperty`, `@ExerisDomain`, `@Saga`, `@SagaStep`, `@UIGroup`, `@PrimaryKey` | the attributes the disposition registry holds as unread, for example `@Action.timeout`, `@ExerisDomain.cacheable`, `@SagaStep.parallel` and `@PrimaryKey.strategy` |

A warning reads, for example:

```text
EXT-PROC-1201: @View.titleKey is set but no code generator consumes it — it is carried into ViewMetadata and the TypeScript view generator does not read it, so the generated view ignores it. (reported because -Aexeris.strict is enabled)
```

`@ExerisDomain.module`, `@ActionParam.label` and `@SagaStep.order` are required and have no
default, so they are not reported. `@Action.roles` was reported before and its text now states that
the SDK removes it at 1.0; `@TenantId.exposeInApi` and `@SoftDelete.retentionPeriod` state the
coverage plan's proposed disposition.

`@SagaStep.parallel` and `@SagaStep.timeout` warn under strict as well: the emitted flow is a linear
chain with no per-step deadline, so neither changes it.

`@GraphEdge.bidirectional`, `direction`, `staticWeight` and `weightField` are not reported: they
reach the emitted `GraphEdgeDescriptor`. The node `field` facet of a `@View` is not reported
either, because the SDK has no attribute that sets it.

This does not apply to a build without `-Aexeris.strict`, which emits no warning for any of them. A
build that passes `-Werror` together with `-Aexeris.strict` fails on each reported attribute: delete
the attribute, or drop `-Aexeris.strict` for that compilation.
