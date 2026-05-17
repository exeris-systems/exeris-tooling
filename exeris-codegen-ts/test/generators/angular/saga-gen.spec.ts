/**
 * Coverage for src/generators/angular/saga-gen.ts — SagaGenerator emits
 * a per-domain saga UI state machine with @Injectable Signal-based
 * step tracking, polling, accessibility announcements, and computed
 * progress estimation.
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
import { SagaGenerator, generateSaga } from '../../../src/generators/angular/saga-gen.js';
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

  it('imports Angular core signals + LiveAnnouncer + HttpClient + firstValueFrom', () => {
    const content = sagaContent();

    expect(content).toContain('Injectable,');
    expect(content).toContain('signal,');
    expect(content).toContain('computed,');
    expect(content).toContain('effect,');
    expect(content).toContain("from '@angular/core';");
    expect(content).toContain("import { LiveAnnouncer } from '@angular/cdk/a11y';");
    expect(content).toContain("import { HttpClient } from '@angular/common/http';");
    expect(content).toContain("import { firstValueFrom } from 'rxjs';");
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

  it('@Injectable + <PascalSaga>StateMachine class with the right baseUrl + http/liveAnnouncer inject', () => {
    const content = sagaContent();

    expect(content).toContain("@Injectable({ providedIn: 'root' })");
    expect(content).toContain('export class OrderFulfillmentStateMachine {');
    expect(content).toContain('private readonly http = inject(HttpClient);');
    expect(content).toContain('private readonly liveAnnouncer = inject(LiveAnnouncer);');
    expect(content).toContain("private readonly baseUrl = '/api/v1/sagas/order';");
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

// ---------- actions: start / cancel / retry / reset ----------

describe('SagaGenerator action methods', () => {
  const gen = new SagaGenerator();

  function content() {
    return gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [] },
    }), CTX)!.content;
  }

  it('start: async + canStart guard + reset/_entityId/_state/_startedAt + http.post → executionId + startPolling', () => {
    const c = content();

    expect(c).toContain('async start(entityId: string, params?: Record<string, unknown>): Promise<string> {');
    expect(c).toContain('if (!this.canStart()) {');
    expect(c).toContain('throw new Error(`Cannot start saga in state: ${this._state()}`);');
    expect(c).toContain('this.reset();');
    expect(c).toContain('this._entityId.set(entityId);');
    expect(c).toContain("this._state.set('RUNNING');");
    expect(c).toContain('this._startedAt.set(new Date());');
    expect(c).toContain('$localize`:@@saga.started:Saga started`');
    expect(c).toContain('this.http.post<{ executionId: string }>(');
    expect(c).toContain('this._executionId.set(response.executionId);');
    expect(c).toContain('this.startPolling();');
  });

  it('cancel: throws when no executionId; otherwise POSTs to /cancel + sets COMPENSATING', () => {
    const c = content();

    expect(c).toContain('async cancel(): Promise<void> {');
    expect(c).toContain("throw new Error('No saga execution to cancel');");
    expect(c).toContain("this._state.set('COMPENSATING');");
    expect(c).toContain('$localize`:@@saga.cancelling:Cancelling saga...`');
    expect(c).toContain('${execId}/cancel');
  });

  it('retry: throws unless state===FAILED + has executionId; POSTs to /retry + restartPolling', () => {
    const c = content();

    expect(c).toContain('async retry(): Promise<void> {');
    expect(c).toContain("if (!execId || this._state() !== 'FAILED') {");
    expect(c).toContain("throw new Error('Cannot retry saga');");
    expect(c).toContain("this._state.set('RUNNING');");
    expect(c).toContain('this._error.set(null);');
    expect(c).toContain('${execId}/retry');
  });

  it('reset: stops polling + resets all 8 signals to initial values', () => {
    const c = content();

    expect(c).toContain('reset(): void {');
    expect(c).toContain('this.stopPolling();');
    expect(c).toContain('this._executionId.set(null);');
    expect(c).toContain('this._entityId.set(null);');
    expect(c).toContain("this._state.set('IDLE');");
    expect(c).toContain('this._currentStepIndex.set(-1);');
    expect(c).toContain('this._error.set(null);');
    expect(c).toContain('this._startedAt.set(null);');
    expect(c).toContain('this._completedAt.set(null);');
  });
});

// ---------- polling: startPolling / stopPolling / pollStatus ----------

describe('SagaGenerator polling lifecycle', () => {
  const gen = new SagaGenerator();

  function content() {
    return gen.generate(domain({
      entityName: 'Order',
      sagaMetadata: { name: 'OrderSaga', steps: [] },
    }), CTX)!.content;
  }

  it('startPolling sets setInterval at 1000ms after stopPolling guard', () => {
    const c = content();

    expect(c).toContain('private startPolling(): void {');
    expect(c).toContain('this.stopPolling();');
    expect(c).toContain('this.pollingInterval = setInterval(() => {');
    expect(c).toContain('this.pollStatus();');
    expect(c).toContain('}, 1000);');
  });

  it('stopPolling clears the interval guard', () => {
    const c = content();

    expect(c).toContain('private stopPolling(): void {');
    expect(c).toContain('if (this.pollingInterval) {');
    expect(c).toContain('clearInterval(this.pollingInterval);');
    expect(c).toContain('this.pollingInterval = null;');
  });

  it('pollStatus: GET status + merges server steps onto local steps + announces step changes + handles terminal states', () => {
    const c = content();

    expect(c).toContain('private async pollStatus(): Promise<void> {');
    expect(c).toContain('${execId}/status');
    // Merge logic
    expect(c).toContain('const serverStep = status.steps.find(s => s.name === step.name);');
    expect(c).toContain('status: serverStep.status,');
    expect(c).toContain('this._steps.set(updatedSteps);');
    // Current step index update
    expect(c).toContain("const runningIdx = updatedSteps.findIndex(s => s.status === 'RUNNING');");
    // Step-change announcement
    expect(c).toContain('$localize`:@@saga.step.running:Running step: ${step.label}`');
    // Terminal-state branches
    expect(c).toContain("if (status.state === 'COMPLETED' ||");
    expect(c).toContain("status.state === 'FAILED' ||");
    expect(c).toContain("status.state === 'COMPENSATED'");
    expect(c).toContain('$localize`:@@saga.completed:Saga completed successfully`');
    expect(c).toContain('$localize`:@@saga.failed:Saga failed`');
    expect(c).toContain('$localize`:@@saga.compensated:Saga was compensated`');
    // Doesn't crash on transient errors
    expect(c).toContain("console.error('Failed to poll saga status:', err);");
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

  it('extractErrorMessage: 3-arm helper (Error / message-bearing object / $localize fallback)', () => {
    const c = content();

    expect(c).toContain('private extractErrorMessage(err: unknown): string {');
    expect(c).toContain('if (err instanceof Error) {');
    expect(c).toContain('return err.message;');
    expect(c).toContain("typeof err === 'object' && err !== null && 'message' in err");
    expect(c).toContain('$localize`:@@error.unknown:An unknown error occurred`');
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

  it('populated steps → SAGA_STEPS array with PENDING status + $localize label per step', () => {
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
    expect(content).toContain('label: $localize`:@@saga.step.reserveInventory:Reserve Inventory`');
    expect(content).toContain("status: 'PENDING',");
    expect(content).toContain("name: 'chargePayment',");
    expect(content).toContain('label: $localize`:@@saga.step.chargePayment:Charge Payment`');
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

  it('hardcodes backend "KERNEL" inside the convenience context (does NOT read config.backend)', () => {
    const file = generateSaga(
      domain({
        entityName: 'Order',
        sagaMetadata: { name: 'OrderSaga', steps: [] },
      }),
      { ...CTX.config, backend: 'SPRING' },
    );

    expect(file).not.toBeNull();
    expect(file!.path).toBe('sagas/order.saga.ts');
  });
});
