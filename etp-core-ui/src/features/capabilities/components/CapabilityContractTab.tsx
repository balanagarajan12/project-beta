import { useState } from 'react';
import { ETPButton } from '@/components/ui';
import type { CapabilityVersionDetail } from '../types';
import { ContractDocumentAccordion } from './ContractDocumentAccordion';
import styles from './CapabilityDetailTabs.module.css';

type DocumentViewMode = 'structured' | 'raw';

export interface CapabilityContractTabProps {
  version: CapabilityVersionDetail | null;
}

export function CapabilityContractTab({ version }: CapabilityContractTabProps) {
  const [viewMode, setViewMode] = useState<DocumentViewMode>('structured');

  if (!version) {
    return <p className={styles.empty}>No version selected.</p>;
  }

  const contract = version.contract;

  if (!contract) {
    return <p className={styles.empty}>No contract specified for this version.</p>;
  }

  return (
    <div className={styles.tabContent}>
      <div className={styles.tabToolbar}>
        <h3 className={styles.blockTitle}>Contract</h3>
        <div className={styles.viewToggle}>
          <ETPButton
            variant={viewMode === 'structured' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setViewMode('structured')}
          >
            Structured
          </ETPButton>
          <ETPButton
            variant={viewMode === 'raw' ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => setViewMode('raw')}
          >
            Raw JSON
          </ETPButton>
        </div>
      </div>

      <dl className={styles.metaGrid}>
        <div>
          <dt>Contract Type</dt>
          <dd>{contract.contractType}</dd>
        </div>
        <div>
          <dt>Contract Format</dt>
          <dd>{contract.contractFormat}</dd>
        </div>
        <div>
          <dt>Contract Version</dt>
          <dd>{contract.contractVersion}</dd>
        </div>
        <div>
          <dt>Location</dt>
          <dd>{contract.location ?? 'Not specified'}</dd>
        </div>
      </dl>

      {viewMode === 'structured' ? (
        <ContractDocumentAccordion document={contract.document} />
      ) : (
        <pre className={styles.rawJson} aria-label="Raw contract document">
          {JSON.stringify(contract.document ?? {}, null, 2)}
        </pre>
      )}
    </div>
  );
}
