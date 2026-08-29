import type { CapabilityDetail, CapabilityVersionDetail } from './types';

/** Picks initial version: capability.version match → ACTIVE → first listed. */
export function pickDefaultVersionId(capability: CapabilityDetail): string | null {
  if (capability.versions.length === 0) {
    return null;
  }

  if (capability.version) {
    const matched = capability.versions.find((v) => v.version === capability.version);
    if (matched) {
      return matched.id;
    }
  }

  const active = capability.versions.find((v) => v.status === 'ACTIVE');
  if (active) {
    return active.id;
  }

  return capability.versions[0].id;
}

export function findVersionById(
  capability: CapabilityDetail,
  versionId: string | null,
): CapabilityVersionDetail | null {
  if (!versionId) {
    return null;
  }
  return capability.versions.find((v) => v.id === versionId) ?? null;
}
