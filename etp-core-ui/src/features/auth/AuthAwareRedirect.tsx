import { Navigate } from 'react-router-dom';
import { ETPLoadingState } from '@/components/ui';
import { useAuth } from './AuthContext';
import { DEFAULT_AUTH_RETURN_PATH } from './returnTo';

/**
 * Root and unknown routes: send authenticated users to the dashboard,
 * guests to login.
 */
export function AuthAwareRedirect() {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return <ETPLoadingState message="Restoring session…" />;
  }

  return (
    <Navigate to={isAuthenticated ? DEFAULT_AUTH_RETURN_PATH : '/login'} replace />
  );
}
