import { useCallback, useEffect, useState } from 'react';
import { listCapabilities } from './capabilityApi';
import type { CapabilitySummary } from './types';

export function useCapabilitiesList() {
  const [capabilities, setCapabilities] = useState<CapabilitySummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listCapabilities();
      setCapabilities(data);
    } catch {
      setError('Unable to load capabilities.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  return { capabilities, loading, error, reload };
}
