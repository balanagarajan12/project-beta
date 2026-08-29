import { ETPBadge } from '@/components/ui';
import type { CapabilityVersionDetail } from '../types';
import { shortCapabilityId } from '../capabilityPresentation';
import styles from './CapabilityDetailTabs.module.css';

export interface CapabilityDependenciesTabProps {
  version: CapabilityVersionDetail | null;
}

export function CapabilityDependenciesTab({ version }: CapabilityDependenciesTabProps) {
  if (!version) {
    return <p className={styles.empty}>No version selected.</p>;
  }

  const dependencies = version.dependencies ?? [];

  if (dependencies.length === 0) {
    return (
      <div className={styles.tabContent}>
        <h3 className={styles.blockTitle}>Dependencies</h3>
        <p className={styles.emptyTitle}>No dependencies</p>
        <p className={styles.bodyText}>
          This version does not depend on any registered capabilities.
        </p>
      </div>
    );
  }

  return (
    <div className={styles.tabContent}>
      <h3 className={styles.blockTitle}>Dependencies</h3>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Depends on (version)</th>
              <th>Type</th>
              <th>Mandatory</th>
            </tr>
          </thead>
          <tbody>
            {dependencies.map((dependency) => (
              <tr key={`${dependency.capabilityVersionId}-${dependency.dependencyType}`}>
                <td>
                  <code className={styles.mono} title={dependency.capabilityVersionId}>
                    {shortCapabilityId(dependency.capabilityVersionId)}
                  </code>
                </td>
                <td>{dependency.dependencyType}</td>
                <td>
                  <ETPBadge variant={dependency.mandatory ? 'info' : 'default'}>
                    {dependency.mandatory ? 'Mandatory' : 'Optional'}
                  </ETPBadge>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
