/**
 * Backend Strategy Interface
 *
 * Defines the contract for different backend implementations.
 * Each strategy handles:
 * - API client configuration
 * - Header injection (RLS, tenant, auth)
 * - Path transformation
 * - Error mapping
 * - Retry logic
 *
 * @author Exeris Team
 * @since 0.3.0
 */

// ============================================================================
// Types
// ============================================================================

export type BackendType = 'KERNEL' | 'SPRING' | 'QUARKUS' | 'MICRONAUT' | 'VANILLA';

export interface ClientConfig {
  /** Base URL for API calls */
  baseUrl: string;
  /** Use fetch API (vs XHR) */
  useFetch: boolean;
  /** Use HTTP/3 (Kernel only) */
  useHttp3: boolean;
  /** Request timeout in ms */
  timeout: number;
  /** Credentials mode */
  credentials: 'include' | 'same-origin' | 'omit';
  /** API version prefix */
  apiVersion: string;
}

export interface TenantContext {
  /** Current tenant ID for RLS */
  tenantId?: string;
  /** Current user ID */
  userId?: string;
  /** Correlation ID for distributed tracing */
  correlationId?: string;
  /** Trace ID for observability */
  traceId?: string;
  /** JWT access token */
  accessToken?: string;
}

export interface RetryConfig {
  /** Maximum retry attempts */
  maxRetries: number;
  /** Initial backoff in ms */
  backoffMs: number;
  /** Backoff multiplier for exponential backoff */
  backoffMultiplier: number;
  /** HTTP status codes that trigger retry */
  retryableStatuses: number[];
}

export interface ApiError {
  /** HTTP status code */
  status: number;
  /** Error code from backend */
  code: string;
  /** Human-readable message */
  message: string;
  /** Correlation ID for support */
  correlationId?: string;
  /** Detailed validation errors */
  details?: Record<string, string>;
}

export interface RealTimeConfig {
  /** WebSocket/WebTransport endpoint */
  endpoint: string;
  /** Reconnection strategy */
  reconnectAttempts: number;
  /** Reconnection delay in ms */
  reconnectDelayMs: number;
  /** Heartbeat interval in ms */
  heartbeatIntervalMs: number;
}

// ============================================================================
// Strategy Interface
// ============================================================================

export interface BackendStrategy {
  /** Strategy type identifier */
  readonly type: BackendType;

  /**
   * Get API client configuration.
   */
  getClientConfig(): ClientConfig;

  /**
   * Get default headers for API requests.
   * Includes RLS headers, auth tokens, correlation IDs.
   */
  getDefaultHeaders(context: TenantContext): Record<string, string>;

  /**
   * Transform API path based on backend conventions.
   * E.g., KERNEL: /api/v1/tenants, SPRING: /api/tenants
   */
  transformPath(basePath: string, entityPath: string): string;

  /**
   * Map HTTP response to structured ApiError.
   */
  mapError(response: Response, body?: unknown): ApiError;

  /**
   * Get retry/backoff configuration.
   */
  getRetryConfig(): RetryConfig;

  /**
   * Check if backend supports real-time features.
   */
  supportsRealtime(): boolean;

  /**
   * Get real-time configuration (if supported).
   */
  getRealTimeConfig?(): RealTimeConfig;

  /**
   * Generate TypeScript import statements for this strategy.
   */
  getRequiredImports(): string[];

  /**
   * Generate HTTP client code snippet for this strategy.
   */
  generateClientCode(entityName: string, entityPath: string): string;
}

// ============================================================================
// Strategy Registry
// ============================================================================

class StrategyRegistry {
  private readonly strategies = new Map<BackendType, BackendStrategy>();

  register(strategy: BackendStrategy): this {
    this.strategies.set(strategy.type, strategy);
    return this;
  }

  get(type: BackendType): BackendStrategy {
    const strategy = this.strategies.get(type);
    if (!strategy) {
      throw new Error(`No strategy registered for backend type: ${type}. Available: ${Array.from(this.strategies.keys()).join(', ')}`);
    }
    return strategy;
  }

  has(type: BackendType): boolean {
    return this.strategies.has(type);
  }

  getAll(): BackendStrategy[] {
    return Array.from(this.strategies.values());
  }

  getAllTypes(): BackendType[] {
    return Array.from(this.strategies.keys());
  }

  clear(): void {
    this.strategies.clear();
  }
}

// Singleton registry instance
export const strategyRegistry = new StrategyRegistry();

// ============================================================================
// Convenience Functions
// ============================================================================

export function registerStrategy(strategy: BackendStrategy): void {
  strategyRegistry.register(strategy);
}

export function getStrategy(type: BackendType): BackendStrategy {
  return strategyRegistry.get(type);
}

export function getAllStrategies(): BackendStrategy[] {
  return strategyRegistry.getAll();
}

export function hasStrategy(type: BackendType): boolean {
  return strategyRegistry.has(type);
}

// ============================================================================
// KERNEL Strategy (Exeris Kernel - HTTP/3 + RLS)
// ============================================================================

export class KernelStrategy implements BackendStrategy {
  readonly type = 'KERNEL' as const;

  getClientConfig(): ClientConfig {
    return {
      baseUrl: '/api',
      useFetch: true,
      useHttp3: true,
      timeout: 30000,
      credentials: 'include',
      apiVersion: 'v1',
    };
  }

  getDefaultHeaders(context: TenantContext): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    };

    // RLS headers for multi-tenancy
    if (context.tenantId) {
      headers['X-Tenant-Id'] = context.tenantId;
    }
    if (context.userId) {
      headers['X-User-Id'] = context.userId;
    }
    if (context.correlationId) {
      headers['X-Correlation-Id'] = context.correlationId;
    }
    if (context.traceId) {
      headers['X-Trace-Id'] = context.traceId;
    }
    if (context.accessToken) {
      headers['Authorization'] = `Bearer ${context.accessToken}`;
    }

    return headers;
  }

  transformPath(basePath: string, entityPath: string): string {
    const config = this.getClientConfig();
    return `${basePath}/${config.apiVersion}${entityPath}`;
  }

  mapError(response: Response, body?: unknown): ApiError {
    const errorBody = body as Record<string, unknown> | undefined;
    return {
      status: response.status,
      code: response.headers.get('X-Error-Code') ?? errorBody?.code as string ?? 'KERNEL_ERROR',
      message: errorBody?.message as string ?? response.statusText,
      correlationId: response.headers.get('X-Correlation-Id') ?? undefined,
      details: errorBody?.details as Record<string, string> ?? undefined,
    };
  }

  getRetryConfig(): RetryConfig {
    return {
      maxRetries: 3,
      backoffMs: 100,
      backoffMultiplier: 2,
      retryableStatuses: [408, 429, 500, 502, 503, 504],
    };
  }

  supportsRealtime(): boolean {
    return true;
  }

  getRealTimeConfig(): RealTimeConfig {
    return {
      endpoint: '/api/v1/events/stream',
      reconnectAttempts: 5,
      reconnectDelayMs: 1000,
      heartbeatIntervalMs: 30000,
    };
  }

  getRequiredImports(): string[] {
    return [
      "import { HttpClient, HttpParams, HttpHeaders } from '@angular/common/http';",
      "import { inject, Injectable, signal, computed } from '@angular/core';",
      "import { TenantContextService } from '../core/tenant-context.service';",
    ];
  }

  generateClientCode(entityName: string, entityPath: string): string {
    const baseUrl = this.transformPath('/api', entityPath);
    return `
  private readonly http = inject(HttpClient);
  private readonly tenantContext = inject(TenantContextService);
  
  private readonly baseUrl = '${baseUrl}';

  private getHeaders(): HttpHeaders {
    const context = this.tenantContext.getContext();
    let headers = new HttpHeaders()
      .set('Content-Type', 'application/json')
      .set('Accept', 'application/json');
    
    if (context.tenantId) {
      headers = headers.set('X-Tenant-Id', context.tenantId);
    }
    if (context.correlationId) {
      headers = headers.set('X-Correlation-Id', context.correlationId);
    }
    
    return headers;
  }`;
  }
}

// ============================================================================
// SPRING Strategy (Spring Boot REST)
// ============================================================================

export class SpringStrategy implements BackendStrategy {
  readonly type = 'SPRING' as const;

  getClientConfig(): ClientConfig {
    return {
      baseUrl: '/api',
      useFetch: true,
      useHttp3: false,
      timeout: 30000,
      credentials: 'include',
      apiVersion: 'v1',
    };
  }

  getDefaultHeaders(context: TenantContext): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    };

    if (context.accessToken) {
      headers['Authorization'] = `Bearer ${context.accessToken}`;
    }
    if (context.tenantId) {
      headers['X-TenantID'] = context.tenantId;
    }

    return headers;
  }

  transformPath(basePath: string, entityPath: string): string {
    return `${basePath}${entityPath}`;
  }

  mapError(response: Response, body?: unknown): ApiError {
    const errorBody = body as Record<string, unknown> | undefined;
    return {
      status: response.status,
      code: errorBody?.error as string ?? 'SPRING_ERROR',
      message: errorBody?.message as string ?? response.statusText,
      details: errorBody?.errors as Record<string, string> ?? undefined,
    };
  }

  getRetryConfig(): RetryConfig {
    return {
      maxRetries: 3,
      backoffMs: 200,
      backoffMultiplier: 2,
      retryableStatuses: [408, 429, 500, 502, 503, 504],
    };
  }

  supportsRealtime(): boolean {
    return true;
  }

  getRealTimeConfig(): RealTimeConfig {
    return {
      endpoint: '/api/events',
      reconnectAttempts: 5,
      reconnectDelayMs: 2000,
      heartbeatIntervalMs: 30000,
    };
  }

  getRequiredImports(): string[] {
    return [
      "import { HttpClient, HttpParams } from '@angular/common/http';",
      "import { inject, Injectable, signal } from '@angular/core';",
    ];
  }

  generateClientCode(entityName: string, entityPath: string): string {
    const baseUrl = this.transformPath('/api', entityPath);
    return `
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '${baseUrl}';`;
  }
}

// ============================================================================
// QUARKUS Strategy (Quarkus JAX-RS)
// ============================================================================

export class QuarkusStrategy implements BackendStrategy {
  readonly type = 'QUARKUS' as const;

  getClientConfig(): ClientConfig {
    return {
      baseUrl: '/api',
      useFetch: true,
      useHttp3: false,
      timeout: 30000,
      credentials: 'include',
      apiVersion: 'v1',
    };
  }

  getDefaultHeaders(context: TenantContext): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    };

    if (context.accessToken) {
      headers['Authorization'] = `Bearer ${context.accessToken}`;
    }
    if (context.tenantId) {
      headers['X-Tenant-ID'] = context.tenantId;
    }

    return headers;
  }

  transformPath(basePath: string, entityPath: string): string {
    const config = this.getClientConfig();
    return `${basePath}/${config.apiVersion}${entityPath}`;
  }

  mapError(response: Response, body?: unknown): ApiError {
    const errorBody = body as Record<string, unknown> | undefined;
    return {
      status: response.status,
      code: errorBody?.errorCode as string ?? 'QUARKUS_ERROR',
      message: errorBody?.message as string ?? response.statusText,
      details: errorBody?.violations as Record<string, string> ?? undefined,
    };
  }

  getRetryConfig(): RetryConfig {
    return {
      maxRetries: 3,
      backoffMs: 100,
      backoffMultiplier: 1.5,
      retryableStatuses: [408, 429, 500, 502, 503, 504],
    };
  }

  supportsRealtime(): boolean {
    return true;
  }

  getRealTimeConfig(): RealTimeConfig {
    return {
      endpoint: '/api/v1/sse/events',
      reconnectAttempts: 3,
      reconnectDelayMs: 1500,
      heartbeatIntervalMs: 25000,
    };
  }

  getRequiredImports(): string[] {
    return [
      "import { HttpClient, HttpParams } from '@angular/common/http';",
      "import { inject, Injectable, signal } from '@angular/core';",
    ];
  }

  generateClientCode(entityName: string, entityPath: string): string {
    const baseUrl = this.transformPath('/api', entityPath);
    return `
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '${baseUrl}';`;
  }
}

// ============================================================================
// MICRONAUT Strategy (Micronaut HTTP)
// ============================================================================

export class MicronautStrategy implements BackendStrategy {
  readonly type = 'MICRONAUT' as const;

  getClientConfig(): ClientConfig {
    return {
      baseUrl: '/api',
      useFetch: true,
      useHttp3: false,
      timeout: 30000,
      credentials: 'include',
      apiVersion: 'v1',
    };
  }

  getDefaultHeaders(context: TenantContext): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    };

    if (context.accessToken) {
      headers['Authorization'] = `Bearer ${context.accessToken}`;
    }
    if (context.tenantId) {
      headers['tenant-id'] = context.tenantId;
    }

    return headers;
  }

  transformPath(basePath: string, entityPath: string): string {
    return `${basePath}${entityPath}`;
  }

  mapError(response: Response, body?: unknown): ApiError {
    const errorBody = body as Record<string, unknown> | undefined;
    return {
      status: response.status,
      code: errorBody?.code as string ?? 'MICRONAUT_ERROR',
      message: errorBody?.message as string ?? response.statusText,
      details: (errorBody?._embedded as Record<string, unknown>)?.errors as Record<string, string> ?? undefined,
    };
  }

  getRetryConfig(): RetryConfig {
    return {
      maxRetries: 3,
      backoffMs: 150,
      backoffMultiplier: 2,
      retryableStatuses: [408, 429, 500, 502, 503, 504],
    };
  }

  supportsRealtime(): boolean {
    return true;
  }

  getRealTimeConfig(): RealTimeConfig {
    return {
      endpoint: '/ws/events',
      reconnectAttempts: 5,
      reconnectDelayMs: 1000,
      heartbeatIntervalMs: 30000,
    };
  }

  getRequiredImports(): string[] {
    return [
      "import { HttpClient, HttpParams } from '@angular/common/http';",
      "import { inject, Injectable, signal } from '@angular/core';",
    ];
  }

  generateClientCode(entityName: string, entityPath: string): string {
    const baseUrl = this.transformPath('/api', entityPath);
    return `
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '${baseUrl}';`;
  }
}

// ============================================================================
// VANILLA Strategy (Pure Fetch - Framework Agnostic)
// ============================================================================

export class VanillaStrategy implements BackendStrategy {
  readonly type = 'VANILLA' as const;

  getClientConfig(): ClientConfig {
    return {
      baseUrl: '/api',
      useFetch: true,
      useHttp3: false,
      timeout: 30000,
      credentials: 'same-origin',
      apiVersion: 'v1',
    };
  }

  getDefaultHeaders(context: TenantContext): Record<string, string> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    };

    if (context.accessToken) {
      headers['Authorization'] = `Bearer ${context.accessToken}`;
    }

    return headers;
  }

  transformPath(basePath: string, entityPath: string): string {
    return `${basePath}${entityPath}`;
  }

  mapError(response: Response, body?: unknown): ApiError {
    const errorBody = body as Record<string, unknown> | undefined;
    return {
      status: response.status,
      code: 'HTTP_ERROR',
      message: errorBody?.message as string ?? response.statusText,
    };
  }

  getRetryConfig(): RetryConfig {
    return {
      maxRetries: 2,
      backoffMs: 500,
      backoffMultiplier: 2,
      retryableStatuses: [500, 502, 503, 504],
    };
  }

  supportsRealtime(): boolean {
    return false;
  }

  getRequiredImports(): string[] {
    return [
      "// Vanilla fetch - no Angular dependencies",
    ];
  }

  generateClientCode(entityName: string, entityPath: string): string {
    const baseUrl = this.transformPath('/api', entityPath);
    return `
  private readonly baseUrl = '${baseUrl}';

  private async fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
    const response = await fetch(url, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        ...options?.headers,
      },
      credentials: 'same-origin',
    });
    
    if (!response.ok) {
      throw new Error(\`HTTP error: \${response.status}\`);
    }
    
    return response.json();
  }`;
  }
}

// ============================================================================
// Auto-register all strategies
// ============================================================================

export function registerAllStrategies(): void {
  strategyRegistry.register(new KernelStrategy());
  strategyRegistry.register(new SpringStrategy());
  strategyRegistry.register(new QuarkusStrategy());
  strategyRegistry.register(new MicronautStrategy());
  strategyRegistry.register(new VanillaStrategy());
}

// Initialize strategies on module load
registerAllStrategies();
