---
title: "0.11.0 migration step: sagas register named compensation methods, validate step orders, and extract triggers"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

### Sagas: the compensation hook takes the declared name, step order is checked, triggers are read

**Compensation.** The generated saga flow names each step's compensation hook after
`@SagaStep.compensation` and registers it: `builder.step("reserveStock", this::reserveStock,
this::releaseStock)` with `protected FlowOutcome releaseStock(FlowContext context)`, where it emitted a
`compensateReserveStock` stub. A subclass that overrode `compensate<Step>` renames its override to the
declared name. The `@Saga` host type must declare that method, with no parameter or one `FlowContext`
parameter; otherwise `javac` fails with `EXT-PROC-1025`.

**Step order.** `@SagaStep.order` runs 1..N without a gap, and a value is shared only by steps that are
all `parallel = true`. A saga numbered from 0 fails with `EXT-PROC-1028`, a gap with `EXT-PROC-1027`, and
a shared order among sequential steps with `EXT-PROC-1026`. Renumber from 1.

**Trigger.** `@Saga.trigger` is written to `SagaMetadata.trigger` (type by constant name, `schedule`,
`source`/`sourceName`, `topic`). The deprecated constants `SCHEDULE`, `HTTP` and `COMMAND` are read as
`SCHEDULED`, `API` and `MANUAL` with the warning `EXT-PROC-1109`. No emitter reads the trigger yet, and
`-Aexeris.strict` reports it, together with the seven `@Saga` compensation attributes that are now written
to `SagaMetadata` and read by no emitter.

**Retries and timeout.** A declared `maxRetries = 0` emits `MAX_RETRIES = 0`, where it emitted 3. A
`@Saga` without `timeout` keeps the SDK default `PT30M`.
