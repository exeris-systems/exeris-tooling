/**
 * Coverage for src/core/backend-strategy.ts — the single KERNEL
 * BackendStrategy implementation, the singleton registry, and the
 * convenience functions.
 *
 * Spring/Quarkus/Micronaut/Vanilla strategies were removed under the
 * kernel-target-only discipline (hard-constraint #1); BackendType is now a
 * one-member union ('KERNEL'). Tests assert observable contract: the values
 * flowing through getClientConfig / getDefaultHeaders / transformPath /
 * mapError / getRetryConfig / getRealTimeConfig must match what downstream
 * code-emitters depend on.
 */

import { describe, expect, it, beforeEach } from 'vitest';
import {
  KernelStrategy,
  strategyRegistry,
  registerStrategy,
  getStrategy,
  getAllStrategies,
  hasStrategy,
  registerAllStrategies,
  type TenantContext,
} from '../../src/core/backend-strategy.js';

const FULL_CONTEXT: TenantContext = {
  tenantId: 't-123',
  userId: 'u-456',
  correlationId: 'corr-789',
  traceId: 'trace-abc',
  accessToken: 'jwt-xyz',
};

describe('KernelStrategy — BackendStrategy contract', () => {
  const instance = new KernelStrategy();

  it('reports its own type identifier', () => {
    expect(instance.type).toBe('KERNEL');
  });

  it('returns a fully-populated ClientConfig', () => {
    const config = instance.getClientConfig();
    expect(config.baseUrl).toBe('/api');
    expect(config.useFetch).toBe(true);
    expect(config.timeout).toBeGreaterThan(0);
    expect(['include', 'same-origin', 'omit']).toContain(config.credentials);
    expect(config.apiVersion).toBeTruthy();
  });

  it('includes Content-Type and Accept JSON headers by default', () => {
    const headers = instance.getDefaultHeaders({});
    expect(headers['Content-Type']).toBe('application/json');
    expect(headers['Accept']).toBe('application/json');
  });

  it('emits an Authorization Bearer header when accessToken is supplied', () => {
    const headers = instance.getDefaultHeaders({ accessToken: 'jwt-xyz' });
    expect(headers['Authorization']).toBe('Bearer jwt-xyz');
  });

  it('omits Authorization when no accessToken is in the context', () => {
    const headers = instance.getDefaultHeaders({});
    expect(headers['Authorization']).toBeUndefined();
  });

  it('returns a RetryConfig with maxRetries > 0 and standard retryable HTTP statuses', () => {
    const retry = instance.getRetryConfig();
    expect(retry.maxRetries).toBeGreaterThan(0);
    expect(retry.backoffMs).toBeGreaterThan(0);
    expect(retry.backoffMultiplier).toBeGreaterThan(1);
    expect(retry.retryableStatuses).toEqual(expect.arrayContaining([500, 502, 503, 504]));
  });

  it('reports supportsRealtime() consistently with getRealTimeConfig presence', () => {
    expect(instance.supportsRealtime()).toBe(true);
    expect(instance.getRealTimeConfig).toBeDefined();
    const rt = instance.getRealTimeConfig!();
    expect(rt.endpoint).toBeTruthy();
    expect(rt.reconnectAttempts).toBeGreaterThan(0);
    expect(rt.heartbeatIntervalMs).toBeGreaterThan(0);
  });

  it('returns at least one TypeScript import line from getRequiredImports', () => {
    const imports = instance.getRequiredImports();
    expect(imports).toBeInstanceOf(Array);
    expect(imports.length).toBeGreaterThan(0);
  });

  it('embeds the transformed baseUrl into the generated client code snippet', () => {
    const code = instance.generateClientCode('Order', '/orders');
    const expectedUrl = instance.transformPath('/api', '/orders');
    expect(code).toContain(expectedUrl);
  });

  it('mapError produces an ApiError with the response status and a non-empty code/message', () => {
    const response = new Response('', { status: 500, statusText: 'Internal Server Error' });
    const err = instance.mapError(response);
    expect(err.status).toBe(500);
    expect(err.code).toBeTruthy();
    expect(err.message).toBeTruthy();
  });
});

describe('KernelStrategy specifics — RLS headers + HTTP/3 + correlation', () => {
  const kernel = new KernelStrategy();

  it('uses HTTP/3 and versioned paths (matches Kernel runtime conventions)', () => {
    const config = kernel.getClientConfig();
    expect(config.useHttp3).toBe(true);
    expect(config.apiVersion).toBe('v1');
  });

  it('propagates every RLS / observability header when the full TenantContext is supplied', () => {
    const headers = kernel.getDefaultHeaders(FULL_CONTEXT);
    expect(headers['X-Tenant-Id']).toBe('t-123');
    expect(headers['X-User-Id']).toBe('u-456');
    expect(headers['X-Correlation-Id']).toBe('corr-789');
    expect(headers['X-Trace-Id']).toBe('trace-abc');
    expect(headers['Authorization']).toBe('Bearer jwt-xyz');
  });

  it('transformPath prepends the apiVersion segment between basePath and entityPath', () => {
    expect(kernel.transformPath('/api', '/orders')).toBe('/api/v1/orders');
  });

  it('mapError prefers X-Error-Code response header, then body.code, then KERNEL_ERROR fallback', () => {
    // Header preferred.
    const withHeader = new Response('', {
      status: 400,
      headers: { 'X-Error-Code': 'HEADER_CODE' },
    });
    expect(kernel.mapError(withHeader, { code: 'BODY_CODE' }).code).toBe('HEADER_CODE');

    // No header → fall back to body.code.
    const noHeader = new Response('', { status: 400 });
    expect(kernel.mapError(noHeader, { code: 'BODY_CODE' }).code).toBe('BODY_CODE');

    // No header, no body.code → KERNEL_ERROR.
    expect(kernel.mapError(noHeader).code).toBe('KERNEL_ERROR');
  });

  it('mapError surfaces X-Correlation-Id from the response headers when present', () => {
    const response = new Response('', {
      status: 500,
      headers: { 'X-Correlation-Id': 'trace-xyz' },
    });
    expect(kernel.mapError(response).correlationId).toBe('trace-xyz');
  });

  it('mapError leaves correlationId undefined when the header is absent', () => {
    expect(kernel.mapError(new Response('', { status: 500 })).correlationId).toBeUndefined();
  });

  it('mapError pulls details from body.details when present', () => {
    const details = { email: 'invalid', name: 'required' };
    const err = kernel.mapError(new Response('', { status: 400 }), { details });
    expect(err.details).toEqual(details);
  });

  it('getRealTimeConfig points at the kernel event-stream endpoint', () => {
    expect(kernel.getRealTimeConfig().endpoint).toBe('/api/v1/events/stream');
  });

  it('generateClientCode wires up HttpClient + TenantContextService for the requested entity', () => {
    const code = kernel.generateClientCode('Order', '/orders');
    expect(code).toContain('HttpClient');
    expect(code).toContain('TenantContextService');
    expect(code).toContain('/api/v1/orders');
    expect(code).toContain('X-Tenant-Id');
    expect(code).toContain('X-Correlation-Id');
  });
});

describe('StrategyRegistry + convenience functions', () => {
  beforeEach(() => {
    // Each test gets a clean registry baseline. The module-level
    // registerAllStrategies() initial call leaves KERNEL registered;
    // clear-then-re-register lets us isolate registry behaviour.
    strategyRegistry.clear();
  });

  it('register adds a strategy that get + has + getAll can subsequently observe', () => {
    const k = new KernelStrategy();
    registerStrategy(k);

    expect(hasStrategy('KERNEL')).toBe(true);
    expect(getStrategy('KERNEL')).toBe(k);
    expect(getAllStrategies()).toContain(k);
  });

  it('getAllTypes returns every registered type', () => {
    registerStrategy(new KernelStrategy());
    expect(strategyRegistry.getAllTypes()).toEqual(['KERNEL']);
  });

  it('get throws with a message naming the missing type + the available list', () => {
    // Empty registry: even the single supported type is absent.
    expect(() => getStrategy('KERNEL')).toThrow(/No strategy registered for backend type: KERNEL/);
    expect(() => getStrategy('KERNEL')).toThrow(/Available:/);
  });

  it('has returns false for an unregistered type', () => {
    expect(hasStrategy('KERNEL')).toBe(false);
  });

  it('register returns the registry (chainable)', () => {
    const result = strategyRegistry.register(new KernelStrategy());
    expect(result).toBe(strategyRegistry);
  });

  it('clear removes every registration; getAll returns empty array', () => {
    registerStrategy(new KernelStrategy());
    strategyRegistry.clear();
    expect(getAllStrategies()).toEqual([]);
    expect(hasStrategy('KERNEL')).toBe(false);
  });

  it('registerAllStrategies populates the registry with the KERNEL strategy', () => {
    registerAllStrategies();
    expect(strategyRegistry.getAllTypes()).toEqual(['KERNEL']);
  });
});
