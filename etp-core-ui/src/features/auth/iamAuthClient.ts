import { AuthError, type AuthSession, type AuthUser, type LoginCredentials } from './types';

export interface IamLoginResponse {
  user: AuthUser;
  accessToken: string;
  expiresAt: string;
}

interface IamErrorBody {
  error?: string;
  message?: string;
}

function mapIamLoginResponse(
  body: IamLoginResponse,
  rememberMe: boolean,
): AuthSession {
  return {
    user: body.user,
    accessToken: body.accessToken,
    expiresAt: body.expiresAt,
    rememberMe,
  };
}

export async function loginWithIam(
  iamBaseUrl: string,
  credentials: LoginCredentials,
): Promise<AuthSession> {
  const url = `${iamBaseUrl.replace(/\/$/, '')}/api/v1/auth/login`;

  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username: credentials.username.trim(),
        password: credentials.password,
      }),
    });

    if (!response.ok) {
      let body: IamErrorBody | null = null;
      try {
        body = (await response.json()) as IamErrorBody;
      } catch {
        /* non-JSON error */
      }

      if (response.status === 401) {
        throw new AuthError(
          'INVALID_CREDENTIALS',
          body?.message ?? 'Invalid email or username, or password.',
        );
      }

      if (response.status === 502 || response.status === 503) {
        throw new AuthError(
          'SERVICE_UNAVAILABLE',
          'Authentication service is temporarily unavailable. Please try again later.',
        );
      }

      throw new AuthError(
        'UNKNOWN',
        body?.message ?? 'An unexpected error occurred. Please try again.',
      );
    }

    const body = (await response.json()) as IamLoginResponse;
    return mapIamLoginResponse(body, credentials.rememberMe);
  } catch (error) {
    if (error instanceof AuthError) {
      throw error;
    }

    if (error instanceof TypeError) {
      throw new AuthError(
        'NETWORK_ERROR',
        'Unable to connect to the authentication service. Check your connection and try again.',
      );
    }

    throw new AuthError('UNKNOWN', 'An unexpected error occurred. Please try again.');
  }
}
