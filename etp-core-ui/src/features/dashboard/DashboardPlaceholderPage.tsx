import { useNavigate } from 'react-router-dom';
import { ETPButton } from '@/components/ui';
import { useAuth } from '@/features/auth/AuthContext';

/**
 * Routing placeholder — dashboard UI will be implemented in a later task.
 */
export function DashboardPlaceholderPage() {
  const { logout, user } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <main style={{ padding: 'var(--etp-layout-content-padding)' }}>
      <h1 className="etp-text-h1">Dashboard (placeholder)</h1>
      <p className="etp-text-body">
        ETP Core Shell and operational dashboard will be implemented in a later task.
      </p>
      {user && (
        <p className="etp-text-body-sm">Signed in as {user.displayName}</p>
      )}
      <p className="etp-text-body-sm">
        <ETPButton type="button" variant="secondary" size="sm" onClick={handleLogout}>
          Sign out
        </ETPButton>
      </p>
    </main>
  );
}
