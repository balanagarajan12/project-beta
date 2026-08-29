import { apiClient } from '@/services/apiClient';
import type { ApiResponse } from '@/types/api';
import type { CapabilityDetail, CapabilitySummary } from './types';

/** GET /api/v1/capabilities — list capability summaries. */
export async function listCapabilities(): Promise<CapabilitySummary[]> {
  const response = await apiClient.get<ApiResponse<CapabilitySummary[]>>(
    '/api/v1/capabilities',
  );
  return response.data ?? [];
}

/** GET /api/v1/capabilities/{id} — retrieve full capability aggregate. */
export async function getCapability(capabilityId: string): Promise<CapabilityDetail> {
  const response = await apiClient.get<ApiResponse<CapabilityDetail>>(
    `/api/v1/capabilities/${capabilityId}`,
  );
  return response.data;
}
