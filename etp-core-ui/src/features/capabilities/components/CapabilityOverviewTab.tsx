import type { CapabilityDetail, CapabilityVersionDetail } from '../types';
import { formatCapabilityUpdatedAt } from '../capabilityPresentation';
import styles from './CapabilityDetailTabs.module.css';

export interface CapabilityOverviewTabProps {
  capability: CapabilityDetail;
  version: CapabilityVersionDetail | null;
}

export function CapabilityOverviewTab({ capability, version }: CapabilityOverviewTabProps) {
  if (!version) {
    return <p className={styles.empty}>No version selected.</p>;
  }

  return (
    <div className={styles.tabContent}>
      {version.description && (
        <div className={styles.block}>
          <h3 className={styles.blockTitle}>Version Description</h3>
          <p className={styles.bodyText}>{version.description}</p>
        </div>
      )}

      <details className={styles.disclosure}>
        <summary className={styles.disclosureSummary}>Technical details</summary>
        <dl className={styles.metaGrid}>
          <div>
            <dt>Version ID</dt>
            <dd><code className={styles.mono}>{version.id}</code></dd>
          </div>
          <div>
            <dt>Capability ID</dt>
            <dd><code className={styles.mono}>{capability.id}</code></dd>
          </div>
          <div>
            <dt>Created</dt>
            <dd>{formatCapabilityUpdatedAt(capability.createdAt)}</dd>
          </div>
          <div>
            <dt>Updated</dt>
            <dd>{formatCapabilityUpdatedAt(capability.updatedAt)}</dd>
          </div>
        </dl>
      </details>
    </div>
  );
}
