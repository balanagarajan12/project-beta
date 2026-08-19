import type { ApiClientError, ErrorResponse } from '@/types/api';
import { getApiBaseUrl } from '@/config/env';
import * as authStorage from '@/features/auth/authStorage';

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';

export interface RequestOptions {
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export interface ApiClientConfig {
  baseUrl?: string;
  defaultHeaders?: Record<string, string>;
}

async function parseErrorResponse(response: Response): Promise<ApiClientError> {
  let body: ErrorResponse | null = null;
  try {
    body = (await response.json()) as ErrorResponse;
  } catch {
    /* non-JSON error body */
  }

  const error = new Error(body?.message ?? response.statusText) as ApiClientError;
  error.status = response.status;
  error.code = body?.code ?? 'HTTP_ERROR';
  error.details = body?.details;
  return error;
}

export class ApiClient {
  private readonly baseUrl: string;
  private readonly defaultHeaders: Record<string, string>;

  constructor(config: ApiClientConfig = {}) {
    this.baseUrl = (config.baseUrl ?? getApiBaseUrl()).replace(/\/$/, '');
    this.defaultHeaders = {
      Accept: 'application/json',
      ...config.defaultHeaders,
    };
  }

  async request<T>(
    method: HttpMethod,
    path: string,
    body?: unknown,
    options: RequestOptions = {},
  ): Promise<T> {
    const url = `${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
    const headers: Record<string, string> = {
      ...this.defaultHeaders,
      ...options.headers,
    };

    const session = authStorage.getSession();
    if (session?.accessToken) {
      headers.Authorization = `Bearer ${session.accessToken}`;
    }

    const init: RequestInit = {
      method,
      headers,
      signal: options.signal,
    };

    if (body !== undefined && method !== 'GET') {
      headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }

    const response = await fetch(url, init);

    if (!response.ok) {
      throw await parseErrorResponse(response);
    }

    if (response.status === 204) {
      return undefined as T;
    }

    return (await response.json()) as T;
  }

  get<T>(path: string, options?: RequestOptions): Promise<T> {
    return this.request<T>('GET', path, undefined, options);
  }

  post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> {
    return this.request<T>('POST', path, body, options);
  }

  patch<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> {
    return this.request<T>('PATCH', path, body, options);
  }

  put<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> {
    return this.request<T>('PUT', path, body, options);
  }

  delete<T>(path: string, options?: RequestOptions): Promise<T> {
    return this.request<T>('DELETE', path, undefined, options);
  }
}

/** Default client — requires VITE_API_BASE_URL when used */
export const apiClient = new ApiClient();
