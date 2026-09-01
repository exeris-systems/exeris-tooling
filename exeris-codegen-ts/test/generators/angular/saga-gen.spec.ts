/**
 * Coverage for src/generators/angular/saga-gen.ts — SagaGenerator emits
 * a per-domain saga UI state machine with @Injectable Signal-based
 * step tracking, accessibility announcements, and computed progress
 * estimation.
 *
 * The transport assertions this file used to carry are gone with the transport (0.8.0). They
 * pinned a client for `/api/v1/sagas/<entity>` — a contract no emitted route serves, no emitted
 * OpenAPI document lists, and the kernel flow SPI cannot back — plus the 1-second poll against
 * it. The `emits no transport` block below pins their absence instead, so re-adding a fetch is
 * a red test rather than a silent regression.
 *
 * Unique-to-saga-gen contracts pinned:
 *   - artifactType=SAGA, supportedBackends=[] (all backends)
 *   - generate() returns null when !domain.sagaMetadata (NOT on
 *     internalApi.hidden — saga check fires first, similar to
 *     event-gen's events check)
 *   - NO generateAggregate method (no barrel emitted)
 *   - generateSaga convenience hardcodes backend 'KERNEL'
 *   - Saga name fallback: sagaMetadata.name → <entityName>Saga
 *   - generateInitialSteps returns '[]' for empty steps, otherwise
 *     'SAGA_STEPS.map(s => ({ ...s }))' — shared mutable-reference
 *     guard
 */

import { describe, expect, it } from 'vitest';
import { SagaGenerator, generateSaga, sagaMachineName } from '../../../src/generators/angular/saga-gen.js';
import {
  createGeneratorContext,
  type GeneratorContext,
} from '../../../src/core/generator-registry.js';
import {
  DomainMetadataSchema,
  type DomainMetadata,
} from '../../../src/models/domain-model.js';

const CTX: GeneratorContext = createGeneratorContext({});

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

// ---------- CodeGenerator contract ----------

describe('SagaGenerator — CodeGenerator metadata', () => {
  const gen = new SagaGenerator();

  it('declares name / artifactType=SAGA / all-backends; priority undefined (defaults to 10)', () => {
    expect(gen.name).toBe('SagaGenerator');
    expect(gen.artifactType).toBe('SAGA');
    expect(gen.supportedBackends).toEqual([]);
    expect(gen.priority).toBeUndefined();
  });

  it('does NOT implement generateAggregate (no barrel file emitted)', () => {
    // Prototype-level check — distinguishes "method missing from
    // the class" from "method set to undefined on the instance".
    // The latter would pass a plain toBeUndefined() but
    // semantically isn't the same contract.
    expect(SagaGenerator.prototype).not.toHaveProperty('generateAggregate');
    // Belt-and-braces: instance accessor also undefined.
    expect(gen.generateAggregate).toBeUndefined();
  });
});

// ---------- generate — sagaMetadata-presence check ----------

describe('SagaGenerator.generate — sagaMetadata-presence check', () => {
  const gen = new SagaGenerator();

  it('emits sagas/<kebab>.saga.ts when domain has sagaMetadata', () => {
    const file = gen.generate(domain({
      entityName: 'OrderLine',
      sagaMetadata: { name: 'OrderLineFulfillment', steps: [] },
    }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('sagas/order-line.saga.ts');
    expect(file!.artifactType).toBe('SAGA');
    expect(file!.overwritable).toBe(true);
  });

  it('returns null when domain has NO sagaMetadata', () => {
    expect(gen.generate(domain({ entityName: 'Order' }), CTX)).toBeNull();
  });

  it('DOES NOT skip a hidden domain that has sagaMetadata (saga check fires first, mirrors event-gen)', () => {
    const file = gen.generate(domain({
      entityName: 'HiddenButSagaful',
      internalApi: { hidden: true, readOnly: false, internal: false },
      sagaMetadata: { name: 'HiddenSaga', steps: [] },
    }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('sagas/hidden-but-sagaful.saga.ts');
  });
});

// ---------- emitted top-level types + structure ----------

describe('SagaGenerator emitted content — top-level types + class skeleton', () => {
  const gen = new SagaGenerator();

  function sagaContent(stepsOverride?: Array<{ name: string; compensatingAction?: string }>): string {
    return gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: {
        name: 'OrderFulfillment',
        steps: stepsOverride ?? [{ name: 'reserveInventory' }],
      },
    }), CTX)!.content;
  }

  it('imports Angular core signals + LiveAnnouncer, and nothing for a transport it no longer has', () => {
    const content = sagaContent();

    expect(content).toContain('Injectable,');
    expect(content).toContain('signal,');
    expect(content).toContain('computed,');
    // `effect` was in this import list and called nowhere — dead in every emitted file since the
    // generator was written, unnoticed because nothing ever compiled its output.
    expect(content).not.toContain('effect,');
    expect(content).toContain("from '@angular/core';");
    expect(content).toContain("import { LiveAnnouncer } from '@angular/cdk/a11y';");
    expect(content).not.toContain('@angular/common/http');
    expect(content).not.toContain("from 'rxjs'");
  });

  it('emits SagaState union with 6 documented values', () => {
    const content = sagaContent();

    expect(content).toContain('export type SagaState =');
    for (const state of ["'IDLE'", "'RUNNING'", "'COMPLETED'", "'FAILED'", "'COMPENSATING'", "'COMPENSATED'"]) {
      expect(content).toContain(state);
    }
  });

  it('emits StepStatus union with 7 documented values', () => {
    const content = sagaContent();

    expect(content).toContain('export type StepStatus =');
    for (const status of ["'PENDING'", "'RUNNING'", "'COMPLETED'", "'FAILED'", "'COMPENSATING'", "'COMPENSATED'", "'SKIPPED'"]) {
      expect(content).toContain(status);
    }
  });

  it('emits SagaStep + SagaExecution interfaces', () => {
    const content = sagaContent();

    expect(content).toContain('export interface SagaStep {');
    expect(content).toContain('export interface SagaExecution {');
    expect(content).toContain('status: StepStatus;');
    expect(content).toContain('state: SagaState;');
  });

  it('@Injectable + <PascalSaga>StateMachine class injecting only the announcer', () => {
    const content = sagaContent();

    expect(content).toContain("@Injectable({ providedIn: 'root' })");
    expect(content).toContain('export class OrderFulfillmentStateMachine {');
    expect(content).toContain('private readonly liveAnnouncer = inject(LiveAnnouncer);');
    expect(content).not.toContain('inject(HttpClient)');
    expect(content).not.toContain('baseUrl');
  });

  it('exports the SagaStatusSnapshot the caller feeds applyStatus', () => {
    const content = sagaContent();

    expect(content).toContain('export interface SagaStatusSnapshot {');
    expect(content).toContain('state: SagaState;');
    expect(content).toContain('applyStatus(status: SagaStatusSnapshot): void {');
  });

  it('saga.name fallback: empty string → <entityName>Saga (Zod requires name as string, so undefined is unreachable; empty-string is the next-falsy value)', () => {
    // The source code `saga.name || \`${entityName}Saga\`` treats empty
    // string as falsy. The SagaMetadataSchema requires `name: string`
    // (not optional), so passing `undefined` would fail Zod validation
    // upstream. Empty string `name: ''` is the reachable falsy value
    // that exercises the fallback branch.
    const content = gen.generate(domain({
      entityName: 'Payment',
      sagaMetadata: { name: '', steps: [] },
    }), CTX)!.content;

    // PaymentSaga → PascalCase same → PaymentSagaStateMachine class.
    expect(content).toContain('export class PaymentSagaStateMachine {');
  });

  it('toPascalCase normalises snake_case + kebab-case saga names', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'order_fulfillment_v2', steps: [] },
    }), CTX)!.content;

    expect(content).toContain('export class OrderFulfillmentV2StateMachine {');
  });
});

// ---------- private signals + public readonly accessors ----------

describe('SagaGenerator signal surface', () => {
  const gen = new SagaGenerator();

  it('declares all 8 private state signals with correctly typed defaults', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [{ name: 'step1' }] },
    }), CTX)!.content;

    expect(content).toContain('private readonly _executionId = signal<string | null>(null);');
    expect(content).toContain('private readonly _entityId = signal<string | null>(null);');
    expect(content).toContain("private readonly _state = signal<SagaState>('IDLE');");
    expect(content).toContain('private readonly _steps = signal<SagaStep[]>(SAGA_STEPS.map(s => ({ ...s })));');
    expect(content).toContain('private readonly _currentStepIndex = signal<number>(-1);');
    expect(content).toContain('private readonly _error = signal<string | null>(null);');
    expect(content).toContain('private readonly _startedAt = signal<Date | null>(null);');
    expect(content).toContain('private readonly _completedAt = signal<Date | null>(null);');
  });

  it('exposes 8 public readonly accessors via asReadonly()', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [] },
    }), CTX)!.content;

    for (const name of [
      'executionId', 'entityId', 'state', 'steps',
      'currentStepIndex', 'error', 'startedAt', 'completedAt',
    ]) {
      expect(content).toContain(`readonly ${name} = this._${name}.asReadonly();`);
    }
  });

  it('declares all computed-derived signals (currentStep / progress / completedSteps / failedSteps / isRunning / isCompleted / isFailed / isCompensated / canStart / estimatedTimeRemaining / execution)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [] },
    }), CTX)!.content;

    expect(content).toContain('readonly currentStep = computed(() => {');
    expect(content).toContain('readonly progress = computed(() => {');
    expect(content).toContain('readonly completedSteps = computed');
    expect(content).toContain('readonly failedSteps = computed');
    expect(content).toContain('readonly isRunning = computed');
    expect(content).toContain("this._state() === 'RUNNING' || this._state() === 'COMPENSATING'");
    expect(content).toContain('readonly isCompleted = computed');
    expect(content).toContain('readonly isFailed = computed');
    expect(content).toContain('readonly isCompensated = computed');
    expect(content).toContain('readonly canStart = computed');
    expect(content).toContain('readonly estimatedTimeRemaining = computed');
    expect(content).toContain('readonly execution = computed<SagaExecution | null>(() => {');
  });

  it('canStart computed includes IDLE / COMPLETED / FAILED / COMPENSATED as restartable states', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [] },
    }), CTX)!.content;

    expect(content).toContain("this._state() === 'IDLE' || this._state() === 'COMPLETED'");
    expect(content).toContain("this._state() === 'FAILED' || this._state() === 'COMPENSATED'");
  });

  it('estimatedTimeRemaining uses 5-second default when no completed steps have durations', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [] },
    }), CTX)!.content;

    expect(content).toContain('return remainingCount * 5;');
  });
});

// ---------- actions: begin / failToStart / cancelling / retrying / reset ----------

describe('SagaGenerator action methods', () => {
  const gen = new SagaGenerator();

  function content() {
    return gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [] },
    }), CTX)!.content;
  }

  it('begin: canStart guard + records the caller-supplied executionId + RUNNING/startedAt', () => {
    const c = content();

    expect(c).toContain('begin(entityId: string, executionId: string): void {');
    expect(c).toContain('if (!this.canStart()) {');
    expect(c).toContain('throw new Error(`Cannot start saga in state: ${this._state()}`);');
    expect(c).toContain('this.reset();');
    expect(c).toContain('this._entityId.set(entityId);');
    // The id comes from the caller's own start call — nothing here performs one.
    expect(c).toContain('this._executionId.set(executionId);');
    expect(c).toContain("this._state.set('RUNNING');");
    expect(c).toContain('this._startedAt.set(new Date());');
    expect(c).toContain("this.announce('Saga started', 'polite');");
  });

  it('failToStart: the caller-owned start errored before any step reported', () => {
    const c = content();

    expect(c).toContain('failToStart(error: string): void {');
    expect(c).toContain("this._state.set('FAILED');");
    expect(c).toContain('this._error.set(error);');
    expect(c).toContain("this.announce('Saga failed to start', 'assertive');");
  });

  it('cancelling: throws without an executionId; otherwise sets COMPENSATING', () => {
    const c = content();

    expect(c).toContain('cancelling(): void {');
    expect(c).toContain("throw new Error('No saga execution to cancel');");
    expect(c).toContain("this._state.set('COMPENSATING');");
    expect(c).toContain("this.announce('Cancelling saga...', 'polite');");
  });

  it('retrying: throws unless state===FAILED + has executionId; returns to RUNNING', () => {
    const c = content();

    expect(c).toContain('retrying(): void {');
    expect(c).toContain("if (!this._executionId() || this._state() !== 'FAILED') {");
    expect(c).toContain("throw new Error('Cannot retry saga');");
    expect(c).toContain("this._state.set('RUNNING');");
    expect(c).toContain('this._error.set(null);');
  });

  it('reset: resets all 8 signals to initial values (no polling left to stop)', () => {
    const c = content();

    expect(c).toContain('reset(): void {');
    expect(c).not.toContain('this.stopPolling();');
    expect(c).toContain('this._executionId.set(null);');
    expect(c).toContain('this._entityId.set(null);');
    expect(c).toContain("this._state.set('IDLE');");
    expect(c).toContain('this._currentStepIndex.set(-1);');
    expect(c).toContain('this._error.set(null);');
    expect(c).toContain('this._startedAt.set(null);');
    expect(c).toContain('this._completedAt.set(null);');
  });
});

// ---------- the transport that is deliberately absent ----------

describe('SagaGenerator emits no transport', () => {
  const gen = new SagaGenerator();

  const c = gen.generate(domain({
    entityName: 'Order',
    sagaMetadata: { name: 'OrderSaga', steps: [{ name: 'reserveInventory' }] },
  }), CTX)!.content;

  // Measured before this generator was wired: zero saga routes in the emitted Java application,
  // zero saga paths in the emitted OpenAPI, and no per-execution handle anywhere in the kernel
  // flow SPI. A client for those URLs would 404 in every generated app.
  it.each([
    ['a saga base URL', '/api/v1/sagas'],
    ['a start endpoint', '/start'],
    ['a cancel endpoint', '/cancel'],
    ['a retry endpoint', '/retry'],
    ['a status endpoint', '/status'],
    ['an HttpClient', 'HttpClient'],
    ['a poll timer', 'setInterval'],
  ])('emits no %s', (_label, needle) => {
    expect(c).not.toContain(needle);
  });

  // `$localize` is a global the emitted app cannot resolve: it declares "polyfills": [] and no
  // @angular/localize. This was 14 TS2304 errors on the first `ng build` after wiring.
  it('emits no $localize', () => {
    expect(c).not.toContain('$localize');
  });
});

// ---------- applyStatus: the consumer-driven fold ----------

describe('SagaGenerator applyStatus', () => {
  const gen = new SagaGenerator();

  function content() {
    return gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [] },
    }), CTX)!.content;
  }

  it('merges snapshot steps onto local steps + announces step changes + handles terminal states', () => {
    const c = content();

    expect(c).toContain('applyStatus(status: SagaStatusSnapshot): void {');
    // Merge logic
    expect(c).toContain('const serverStep = status.steps.find(s => s.name === step.name);');
    expect(c).toContain('status: serverStep.status,');
    expect(c).toContain('this._steps.set(updatedSteps);');
    // Current step index update
    expect(c).toContain("const runningIdx = updatedSteps.findIndex(s => s.status === 'RUNNING');");
    // Step-change announcement
    expect(c).toContain('this.announce(`Running step: ${step.label}`, \'polite\');');
    // Terminal-state branches
    expect(c).toContain("if (status.state === 'COMPLETED' ||");
    expect(c).toContain("status.state === 'FAILED' ||");
    expect(c).toContain("status.state === 'COMPENSATED'");
    expect(c).toContain("this.announce('Saga completed successfully', 'polite');");
    expect(c).toContain("this.announce('Saga failed', 'assertive');");
    expect(c).toContain("this.announce('Saga was compensated', 'polite');");
  });
});

// ---------- helpers: announce + extractErrorMessage ----------

describe('SagaGenerator helper methods', () => {
  const gen = new SagaGenerator();

  function content() {
    return gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [] },
    }), CTX)!.content;
  }

  it('announce delegates to liveAnnouncer.announce with the provided priority', () => {
    const c = content();

    expect(c).toContain("private announce(message: string, priority: 'polite' | 'assertive'): void {");
    expect(c).toContain('this.liveAnnouncer.announce(message, priority);');
  });

  it('extractErrorMessage: 3-arm helper, public because the caller now holds the rejection', () => {
    const c = content();

    expect(c).toContain('extractErrorMessage(err: unknown): string {');
    expect(c).not.toContain('private extractErrorMessage');
    expect(c).toContain('if (err instanceof Error) {');
    expect(c).toContain('return err.message;');
    expect(c).toContain("typeof err === 'object' && err !== null && 'message' in err");
    expect(c).toContain("return 'An unknown error occurred';");
  });
});

// ---------- generateStepDefinitions + generateInitialSteps ----------

describe('SagaGenerator step-definitions emission', () => {
  const gen = new SagaGenerator();

  it('empty steps → SAGA_STEPS: SagaStep[] = [] + _steps initial array is empty []', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [] },
    }), CTX)!.content;

    expect(content).toContain('const SAGA_STEPS: SagaStep[] = [];');
    expect(content).toContain('private readonly _steps = signal<SagaStep[]>([]);');
  });

  it('populated steps → SAGA_STEPS array with PENDING status + a plain humanised label per step', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: {
        name: 'OrderFulfillment',
        steps: [
          { name: 'reserveInventory' },
          { name: 'chargePayment' },
        ],
      },
    }), CTX)!.content;

    expect(content).toContain("name: 'reserveInventory',");
    expect(content).toContain("label: 'Reserve Inventory',");
    expect(content).toContain("status: 'PENDING',");
    expect(content).toContain("name: 'chargePayment',");
    expect(content).toContain("label: 'Charge Payment',");
  });

  it("a step name carrying a quote cannot close the literal it is emitted into", () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      // Step names are consumer-authored metadata. Before the labels stopped being $localize
      // tagged templates they sat inside a template literal; as single-quoted literals an
      // unescaped apostrophe would end the string and emit a syntax error into the app.
      sagaMetadata: { name: 'OrderSaga', steps: [{ name: "o'brien", compensatingAction: "undo'it" }] },
    }), CTX)!.content;

    expect(content).toContain("name: 'o\\'brien',");
    expect(content).toContain("compensatingAction: 'undo\\'it',");
  });

  it('step with compensatingAction → emits compensatingAction: \'<action>\'; without → emits undefined literal', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: {
        name: 'OrderSaga',
        steps: [
          { name: 'reserve', compensatingAction: 'releaseInventory' },
          { name: 'notify' }, // no compensatingAction
        ],
      },
    }), CTX)!.content;

    expect(content).toContain("compensatingAction: 'releaseInventory',");
    expect(content).toContain('compensatingAction: undefined,');
  });

  it('non-empty steps → _steps initial uses SAGA_STEPS.map(s => ({ ...s })) (defensive clone to avoid shared mutable state)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [{ name: 'step1' }] },
    }), CTX)!.content;

    // The {...s} spread per step avoids the multiple-instance-of-state-
    // machine-sharing-step-mutations bug.
    expect(content).toContain('signal<SagaStep[]>(SAGA_STEPS.map(s => ({ ...s })))');
    // And reset() uses the same defensive clone pattern.
    expect((content.match(/SAGA_STEPS\.map\(s => \(\{ \.\.\.s \}\)\)/g) ?? []).length).toBe(2);
  });
});

// ---------- generateSaga convenience ----------

describe('generateSaga — top-level convenience function', () => {
  it('returns the per-domain file for a domain with sagaMetadata', () => {
    const file = generateSaga(
      domain({
        entityName: 'Order',
        sagaMetadata: { name: 'OrderSaga', steps: [] },
      }),
      CTX.config,
    );

    expect(file).not.toBeNull();
    expect(file!.path).toBe('sagas/order.saga.ts');
    expect(file!.content).toContain('export class OrderSagaStateMachine');
  });

  it('returns null for a domain with no sagaMetadata (matches the SagaGenerator.generate contract)', () => {
    expect(generateSaga(domain({ entityName: 'Order' }), CTX.config)).toBeNull();
  });

  it('hardcodes backend "KERNEL" inside the convenience context', () => {
    const file = generateSaga(
      domain({
        entityName: 'Order',
        sagaMetadata: { name: 'OrderSaga', steps: [] },
      }),
      { ...CTX.config, backend: 'KERNEL' },
    );

    expect(file).not.toBeNull();
    expect(file!.path).toBe('sagas/order.saga.ts');
  });
});

// ---------- sagaMachineName: the barrel's authority for the class name ----------

describe('sagaMachineName', () => {
  it('is null for a domain that declares no saga (the barrel emits no row for it)', () => {
    expect(sagaMachineName(domain({ entityName: 'Order' }))).toBeNull();
  });

  it('derives the machine name from the saga name', () => {
    expect(sagaMachineName(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'order-fulfilment', steps: [] },
    }))).toBe('OrderFulfilmentStateMachine');
  });

  it('falls back to <entityName>Saga when the saga name is empty', () => {
    expect(sagaMachineName(domain({
      entityName: 'Order',
      sagaMetadata: { name: '', steps: [] },
    }))).toBe('OrderSagaStateMachine');
  });

  // The barrel names this class; the file declares it. They are derived from the same function
  // precisely so they cannot drift the way the detail route's plural did (#192).
  it('names the class the emitted file actually declares', () => {
    const d = domain({ entityName: 'Order', sagaMetadata: { name: '', steps: [] } });
    const content = new SagaGenerator().generate(d, CTX)!.content;
    expect(content).toContain(`export class ${sagaMachineName(d)} {`);
  });
});
