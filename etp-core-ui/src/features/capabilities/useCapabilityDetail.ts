import { useCallback, useEffect, useState } from 'react';
import type { ApiClientError } from '@/types/api';
import { getCapability } from './capabilityApi';
import type { CapabilityDetail } from './types';

export type CapabilityDetailErrorKind = 'not_found' | 'load_failed';

export interface CapabilityDetailState {
  capability: CapabilityDetail | null;
  loading: boolean;
  errorKind: CapabilityDetailErrorKind | null;
  reload: () => Promise<void>;
}

export function useCapabilityDetail(capabilityId: string | undefined): CapabilityDetailState {
  const [capability, setCapability] = useState<CapabilityDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [errorKind, setErrorKind] = useState<CapabilityDetailErrorKind | null>(null);

  const reload = useCallback(async () => {
    if (!capabilityId) {
      setCapability(null);
      setLoading(false);
      setErrorKind('not_found');
      return;
    }

    setLoading(true);
    setErrorKind(null);
    try {
      const data = await getCapability(capabilityId);
      setCapability(data);
    } catch (error) {
      setCapability(null);
      const apiError = error as ApiClientError;
      if (apiError.status === 404) {
        setErrorKind('not_found');
      } else {
        setErrorKind('load_failed');
      }
    } finally {
      setLoading(false);
    }
  }, [capabilityId]);

  useEffect(() => {
    reload();
  }, [reload]);

  return { capability, loading, errorKind, reload };
}
