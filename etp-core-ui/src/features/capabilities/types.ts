/**
 * Capability Registry types — aligned with Platform Service capability APIs.
 */

/** GET /api/v1/capabilities — CapabilitySummaryResponse */
export interface CapabilitySummary {
  id: string;
  code: string;
  name: string;
  description: string | null;
  category: string | null;
  status: string;
  version: string | null;
  createdAt: string;
  updatedAt: string;
}

/** GET /api/v1/capabilities/{id} — CapabilityDetailResponse */

export interface CapabilityContractDocument {
  functional?: Record<string, unknown>;
  interfaces?: Record<string, unknown>;
  security?: Record<string, unknown>;
  deployment?: Record<string, unknown>;
  nfr?: Record<string, unknown>;
  operations?: Record<string, unknown>;
  compatibility?: Record<string, unknown>;
  configuration?: Record<string, unknown>;
}

export interface CapabilityContract {
  contractType: string;
  contractVersion: string;
  contractFormat: string;
  document: CapabilityContractDocument | null;
  location: string | null;
}

export interface CapabilityDependency {
  capabilityVersionId: string;
  dependencyType: string;
  mandatory: boolean;
}

export interface CapabilityImplementation {
  code: string;
  name: string;
  technology: string;
  technologyVersion: string | null;
}

export interface CapabilityVersionDetail {
  id: string;
  version: string;
  status: string;
  description: string | null;
  contract: CapabilityContract | null;
  implementation: CapabilityImplementation | null;
  dependencies: CapabilityDependency[];
}

export interface CapabilityDetail {
  id: string;
  code: string;
  name: string;
  description: string | null;
  category: string | null;
  status: string;
  version: string | null;
  createdAt: string;
  updatedAt: string;
  versions: CapabilityVersionDetail[];
}
