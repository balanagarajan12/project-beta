import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { ETPLoadingState } from '@/components/ui';
import { useAuth } from './AuthContext';
import { buildLoginPathWithReturnTo } from './returnTo';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated, isLoading } = useAuth();
  const location = useLocation();

  if (isLoading) {
    return <ETPLoadingState message="Restoring session…" />;
  }

  if (!isAuthenticated) {
    const attemptedPath = `${location.pathname}${location.search}${location.hash}`;
    return <Navigate to={buildLoginPathWithReturnTo(attemptedPath)} replace />;
  }

  return children;
}
