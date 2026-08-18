import { Link } from 'react-router-dom';

/**
 * Routing placeholder — dashboard UI will be implemented in a later task.
 */
export function DashboardPlaceholderPage() {
  return (
    <main style={{ padding: 'var(--etp-layout-content-padding)' }}>
      <h1 className="etp-text-h1">Dashboard (placeholder)</h1>
      <p className="etp-text-body">
        ETP Core Shell and operational dashboard will be implemented in a later task.
      </p>
      <p className="etp-text-body-sm">
        <Link to="/login">Back to login placeholder</Link>
      </p>
    </main>
  );
}
