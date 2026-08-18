export interface LoginCredentials {
  username: string;
  password: string;
  rememberMe: boolean;
}

export interface AuthUser {
  id: string;
  username: string;
  displayName: string;
  email: string;
  roles: string[];
}

export interface AuthSession {
  user: AuthUser;
  accessToken: string;
  expiresAt: string;
  rememberMe: boolean;
}

export type AuthErrorCode =
  | 'INVALID_CREDENTIALS'
  | 'AUTH_DISABLED'
  | 'SESSION_EXPIRED'
  | 'UNKNOWN';

export class AuthError extends Error {
  readonly code: AuthErrorCode;

  constructor(code: AuthErrorCode, message: string) {
    super(message);
    this.name = 'AuthError';
    this.code = code;
  }
}

export function isAuthError(error: unknown): error is AuthError {
  return error instanceof AuthError;
}
