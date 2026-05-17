/**
 * Coverage for src/core/backend-strategy.ts — every BackendStrategy
 * implementation (KERNEL / SPRING / QUARKUS / MICRONAUT / VANILLA),
 * the singleton registry, and the convenience functions.
 *
 * Tests assert observable contract: the values flowing through
 * getClientConfig / getDefaultHeaders / transformPath / mapError /
 * getRetryConfig / getRealTimeConfig must match what downstream
 * code-emitters depend on. Snapshot-style structural assertions
 * avoid coupling to whitespace inside generated code strings.
 */

import { describe, expect, it, beforeEach } from 'vitest';
import {
  KernelStrategy,
  SpringStrategy,
  QuarkusStrategy,
  MicronautStrategy,
  VanillaStrategy,
  strategyRegistry,
  registerStrategy,
  getStrategy,
  getAllStrategies,
  hasStrategy,
  registerAllStrategies,
  type BackendStrategy,
  type BackendType,
  type TenantContext,
} from '../../src/core/backend-strategy.js';

const FULL_CONTEXT: TenantContext = {
  tenantId: 't-123',
  userId: 'u-456',
  correlationId: 'corr-789',
  traceId: 'trace-abc',
  accessToken: 'jwt-xyz',
};

describe('BackendStrategy contract — every implementation', () => {
  // Strategies share an interface; parameterising guards against any
  // implementation drifting from the shape downstream emitters expect.
  const strategies: Array<{ type: BackendType; instance: BackendStrategy }> = [
    { type: 'KERNEL', instance: new KernelStrategy() },
    { type: 'SPRING', instance: new SpringStrategy() },
    { type: 'QUARKUS', instance: new QuarkusStrategy() },
    { type: 'MICRONAUT', instance: new MicronautStrategy() },
    { type: 'VANILLA', instance: new VanillaStrategy() },
  ];

  describe.each(strategies)('$type', ({ type, instance }) => {
    it('reports its own type identifier', () => {
      expect(instance.type).toBe(type);
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
      // Every strategy should at least handle 500-class server errors.
      expect(retry.retryableStatuses).toEqual(expect.arrayContaining([500, 502, 503, 504]));
    });

    it('reports supportsRealtime() consistently with getRealTimeConfig presence', () => {
      const supports = instance.supportsRealtime();
      if (supports) {
        expect(instance.getRealTimeConfig).toBeDefined();
        const rt = instance.getRealTimeConfig!();
        expect(rt.endpoint).toBeTruthy();
        expect(rt.reconnectAttempts).toBeGreaterThan(0);
        expect(rt.heartbeatIntervalMs).toBeGreaterThan(0);
      } else {
        // VANILLA explicitly does not support realtime; getRealTimeConfig is optional.
        expect(supports).toBe(false);
      }
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

describe('SpringStrategy specifics — X-TenantID header, error body shape', () => {
  const spring = new SpringStrategy();

  it('uses HTTP/2 (useHttp3=false) and an unversioned base path transformation', () => {
    expect(spring.getClientConfig().useHttp3).toBe(false);
    // Spring transformPath does NOT prepend the apiVersion segment.
    expect(spring.transformPath('/api', '/orders')).toBe('/api/orders');
  });

  it('uses X-TenantID (uppercase ID) header convention when tenantId is provided', () => {
    const headers = spring.getDefaultHeaders({ tenantId: 't-1' });
    expect(headers['X-TenantID']).toBe('t-1');
    expect(headers['X-Tenant-Id']).toBeUndefined();
  });

  it('mapError reads code from body.error and details from body.errors', () => {
    const err = spring.mapError(
      new Response('', { status: 400 }),
      { error: 'VALIDATION', message: 'Bad', errors: { field: 'required' } },
    );
    expect(err.code).toBe('VALIDATION');
    expect(err.message).toBe('Bad');
    expect(err.details).toEqual({ field: 'required' });
  });

  it('mapError falls back to SPRING_ERROR + response.statusText when body is absent', () => {
    const err = spring.mapError(new Response('', { status: 500, statusText: 'Internal Server Error' }));
    expect(err.code).toBe('SPRING_ERROR');
    expect(err.message).toBe('Internal Server Error');
  });
});

describe('QuarkusStrategy specifics — errorCode + violations + versioned paths', () => {
  const quarkus = new QuarkusStrategy();

  it('uses apiVersion-prefixed paths', () => {
    expect(quarkus.transformPath('/api', '/items')).toBe('/api/v1/items');
  });

  it('uses X-Tenant-ID (dashed, uppercase ID) header convention', () => {
    const headers = quarkus.getDefaultHeaders({ tenantId: 't-q' });
    expect(headers['X-Tenant-ID']).toBe('t-q');
  });

  it('mapError reads errorCode + violations from body', () => {
    const err = quarkus.mapError(
      new Response('', { status: 422 }),
      { errorCode: 'CONSTRAINT', message: 'Failed', violations: { age: 'min 18' } },
    );
    expect(err.code).toBe('CONSTRAINT');
    expect(err.details).toEqual({ age: 'min 18' });
  });

  it('mapError falls back to QUARKUS_ERROR code when body is absent', () => {
    expect(quarkus.mapError(new Response('', { status: 500 })).code).toBe('QUARKUS_ERROR');
  });
});

describe('MicronautStrategy specifics — lowercase tenant-id, HAL-style _embedded errors', () => {
  const micronaut = new MicronautStrategy();

  it('uses lowercase tenant-id header convention', () => {
    expect(micronaut.getDefaultHeaders({ tenantId: 't-m' })['tenant-id']).toBe('t-m');
  });

  it('mapError reads details from body._embedded.errors (HAL convention)', () => {
    const err = micronaut.mapError(
      new Response('', { status: 400 }),
      { code: 'BAD', _embedded: { errors: { field: 'required' } } },
    );
    expect(err.details).toEqual({ field: 'required' });
  });

  it('mapError leaves details undefined when _embedded.errors is missing', () => {
    const err = micronaut.mapError(new Response('', { status: 500 }), { code: 'X' });
    expect(err.details).toBeUndefined();
  });

  it('mapError falls back to MICRONAUT_ERROR when body lacks a code', () => {
    expect(micronaut.mapError(new Response('', { status: 500 })).code).toBe('MICRONAUT_ERROR');
  });

  it('uses same-origin path transformation (no version segment)', () => {
    expect(micronaut.transformPath('/api', '/items')).toBe('/api/items');
  });
});

describe('VanillaStrategy specifics — minimal contract, no realtime', () => {
  const vanilla = new VanillaStrategy();

  it('uses same-origin credentials (no cookies cross-origin)', () => {
    expect(vanilla.getClientConfig().credentials).toBe('same-origin');
  });

  it('emits ONLY Content-Type / Accept / optional Authorization — no tenant headers', () => {
    const headers = vanilla.getDefaultHeaders({ tenantId: 't-1', userId: 'u-1' });
    expect(headers['X-Tenant-Id']).toBeUndefined();
    expect(headers['X-TenantID']).toBeUndefined();
    expect(headers['tenant-id']).toBeUndefined();
  });

  it('supportsRealtime() returns false (no getRealTimeConfig)', () => {
    expect(vanilla.supportsRealtime()).toBe(false);
  });

  it('mapError uses HTTP_ERROR as the generic code', () => {
    expect(vanilla.mapError(new Response('', { status: 500 })).code).toBe('HTTP_ERROR');
  });

  it('mapError prefers body.message, otherwise response.statusText', () => {
    const withBody = vanilla.mapError(new Response('', { status: 400, statusText: 'Bad' }), { message: 'From body' });
    expect(withBody.message).toBe('From body');

    const withoutBody = vanilla.mapError(new Response('', { status: 400, statusText: 'Bad' }));
    expect(withoutBody.message).toBe('Bad');
  });

  it('generateClientCode emits a vanilla fetch-based snippet (no Angular injection)', () => {
    const code = vanilla.generateClientCode('Order', '/orders');
    expect(code).toContain('fetchJson');
    expect(code).toContain('fetch(url');
    expect(code).not.toContain('inject(');
  });
});

describe('StrategyRegistry + convenience functions', () => {
  beforeEach(() => {
    // Each test gets a clean registry baseline. The module-level
    // registerAllStrategies() initial call still leaves all 5 default
    // strategies registered; clear-then-re-register-some lets us
    // isolate registry behaviour from defaults.
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
    registerStrategy(new VanillaStrategy());
    expect(strategyRegistry.getAllTypes().sort()).toEqual(['KERNEL', 'VANILLA']);
  });

  it('get throws with a message naming the missing type + the available list', () => {
    registerStrategy(new KernelStrategy());
    expect(() => getStrategy('SPRING')).toThrow(/No strategy registered for backend type: SPRING/);
    expect(() => getStrategy('SPRING')).toThrow(/Available: KERNEL/);
  });

  it('has returns false for an unregistered type', () => {
    expect(hasStrategy('VANILLA')).toBe(false);
  });

  it('register returns the registry (chainable)', () => {
    const result = strategyRegistry.register(new KernelStrategy());
    expect(result).toBe(strategyRegistry);
  });

  it('clear removes every registration; getAll returns empty array', () => {
    registerStrategy(new KernelStrategy());
    registerStrategy(new VanillaStrategy());
    strategyRegistry.clear();
    expect(getAllStrategies()).toEqual([]);
    expect(hasStrategy('KERNEL')).toBe(false);
  });

  it('registerAllStrategies populates the registry with all 5 default strategies', () => {
    registerAllStrategies();
    expect(strategyRegistry.getAllTypes().sort()).toEqual(
      ['KERNEL', 'MICRONAUT', 'QUARKUS', 'SPRING', 'VANILLA'],
    );
  });
});
