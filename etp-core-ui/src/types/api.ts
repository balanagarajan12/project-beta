/**
 * Shared API response shapes aligned with etp-platform-service common API model.
 * Feature-specific domain types belong in feature module type folders.
 */

export interface ApiResponse<T> {
  data: T;
  message: string | null;
  timestamp: string;
}

export interface ErrorDetail {
  field?: string;
  message: string;
}

export interface ErrorResponse {
  code: string;
  message: string;
  timestamp: string;
  details?: ErrorDetail[];
}

export interface ApiClientError extends Error {
  status: number;
  code: string;
  details?: ErrorDetail[];
}
