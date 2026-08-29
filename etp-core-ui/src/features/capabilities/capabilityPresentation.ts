import type { ETPBadgeVariant } from '@/components/ui/ETPBadge/ETPBadge';
import type { CapabilitySummary } from './types';

/** Human-readable status label — preserves API value semantics. */
export function formatCapabilityStatusLabel(status: string): string {
  return status.replace(/_/g, ' ');
}

/** Map API lifecycle status to badge variant. */
export function capabilityStatusVariant(status: string): ETPBadgeVariant {
  switch (status.toUpperCase()) {
    case 'ACTIVE':
      return 'success';
    case 'DEPRECATED':
      return 'warning';
    case 'RETIRED':
    case 'CANCELLED':
      return 'error';
    case 'DRAFT':
    case 'VALIDATED':
      return 'info';
    default:
      return 'default';
  }
}

export function formatCapabilityUpdatedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
}

export function shortCapabilityId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}

export interface CapabilityListFilters {
  search: string;
  status: string;
  category: string;
}

export function filterCapabilities(
  capabilities: CapabilitySummary[],
  filters: CapabilityListFilters,
): CapabilitySummary[] {
  const query = filters.search.trim().toLowerCase();

  return capabilities.filter((capability) => {
    if (filters.status && capability.status !== filters.status) {
      return false;
    }
    if (filters.category && capability.category !== filters.category) {
      return false;
    }
    if (!query) {
      return true;
    }

    const haystack = [
      capability.name,
      capability.code,
      capability.description ?? '',
      capability.id,
      capability.category ?? '',
      capability.status,
      capability.version ?? '',
    ]
      .join(' ')
      .toLowerCase();

    return haystack.includes(query);
  });
}

export function uniqueSortedValues(values: (string | null | undefined)[]): string[] {
  return [...new Set(values.filter((v): v is string => Boolean(v)))].sort((a, b) =>
    a.localeCompare(b),
  );
}
