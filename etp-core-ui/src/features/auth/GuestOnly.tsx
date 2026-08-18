import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { ETPLoadingState } from '@/components/ui';
import { useAuth } from './AuthContext';
import { DEFAULT_AUTH_RETURN_PATH } from './returnTo';

export function GuestOnly({ children }: { children: ReactNode }) {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return <ETPLoadingState message="Restoring session…" />;
  }

  if (isAuthenticated) {
    return <Navigate to={DEFAULT_AUTH_RETURN_PATH} replace />;
  }

  return children;
}
