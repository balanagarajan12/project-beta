import type { CapabilityContractDocument } from './types';

export const CONTRACT_DOCUMENT_SECTIONS: Array<{
  key: keyof CapabilityContractDocument;
  label: string;
}> = [
  { key: 'functional', label: 'Functional' },
  { key: 'interfaces', label: 'Interfaces' },
  { key: 'security', label: 'Security' },
  { key: 'deployment', label: 'Deployment' },
  { key: 'nfr', label: 'NFR' },
  { key: 'operations', label: 'Operations' },
  { key: 'compatibility', label: 'Compatibility' },
  { key: 'configuration', label: 'Configuration' },
];

export function isEmptyRecord(value: Record<string, unknown> | undefined | null): boolean {
  if (!value) {
    return true;
  }
  return Object.keys(value).length === 0;
}

export function formatContractSectionValue(value: unknown): string[] {
  if (value === null || value === undefined) {
    return [];
  }
  if (Array.isArray(value)) {
    return value.map((item) => String(item));
  }
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>).flatMap(([key, nested]) => {
      if (Array.isArray(nested)) {
        return nested.map((item) => `${key}: ${String(item)}`);
      }
      if (nested && typeof nested === 'object') {
        return [`${key}: ${JSON.stringify(nested)}`];
      }
      return [`${key}: ${String(nested)}`];
    });
  }
  return [String(value)];
}
