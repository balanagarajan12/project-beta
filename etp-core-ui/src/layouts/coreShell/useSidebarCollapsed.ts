import { useCallback, useEffect, useState } from 'react';

const STORAGE_KEY = 'etp-core-sidebar-collapsed';

function readStoredCollapsed(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
}

export function useSidebarCollapsed() {
  const [collapsed, setCollapsed] = useState(readStoredCollapsed);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, String(collapsed));
    } catch {
      /* ignore quota / private mode */
    }
  }, [collapsed]);

  const toggle = useCallback(() => {
    setCollapsed((value) => !value);
  }, []);

  return { collapsed, toggle };
}
