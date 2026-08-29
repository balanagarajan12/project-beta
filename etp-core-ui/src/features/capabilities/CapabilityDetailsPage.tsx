import { useEffect, useMemo, useState } from 'react';
import { ArrowLeft } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';
import {
  ETPBadge,
  ETPButton,
  ETPErrorState,
  ETPEmptyState,
  ETPLoadingState,
  ETPSelect,
  ETPTab,
  ETPTabList,
  ETPTabPanel,
  ETPTabs,
} from '@/components/ui';
import { iconSizes, iconStroke } from '@/lib/iconSizes';
import { CapabilityContractTab } from './components/CapabilityContractTab';
import { CapabilityDependenciesTab } from './components/CapabilityDependenciesTab';
import { CapabilityOverviewTab } from './components/CapabilityOverviewTab';
import { WorklistMenu } from './components/WorklistMenu';
import {
  capabilityStatusVariant,
  formatCapabilityStatusLabel,
} from './capabilityPresentation';
import {
  findVersionById,
  pickDefaultVersionId,
} from './capabilityVersionSelection';
import { useCapabilityDetail } from './useCapabilityDetail';
import styles from './CapabilityDetailsPage.module.css';

export function CapabilityDetailsPage() {
  const { capabilityId } = useParams<{ capabilityId: string }>();
  const { capability, loading, errorKind, reload } = useCapabilityDetail(capabilityId);
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(null);

  useEffect(() => {
    if (capability) {
      setSelectedVersionId(pickDefaultVersionId(capability));
    }
  }, [capability]);

  const selectedVersion = useMemo(
    () => (capability ? findVersionById(capability, selectedVersionId) : null),
    [capability, selectedVersionId],
  );

  const versionOptions = useMemo(
    () =>
      capability?.versions.map((version) => ({
        value: version.id,
        label: `${version.version} · ${formatCapabilityStatusLabel(version.status)}`,
      })) ?? [],
    [capability],
  );

  const backAction = (
    <Link to="/capabilities" className={styles.backLink}>
      <ETPButton variant="secondary" size="sm">
        <ArrowLeft size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden />
        Back to Capabilities
      </ETPButton>
    </Link>
  );

  if (loading) {
    return (
      <div className={styles.page}>
        <div className={styles.backRow}>{backAction}</div>
        <ETPLoadingState message="Loading capability details…" className={styles.state} />
      </div>
    );
  }

  if (errorKind === 'not_found') {
    return (
      <div className={styles.page}>
        <div className={styles.backRow}>{backAction}</div>
        <ETPEmptyState
          title="Capability not found"
          description="The requested capability does not exist or is no longer available."
          className={styles.state}
          action={
            <Link to="/capabilities">
              <ETPButton variant="secondary" size="sm">Back to Capabilities</ETPButton>
            </Link>
          }
        />
      </div>
    );
  }

  if (errorKind === 'load_failed' || !capability) {
    return (
      <div className={styles.page}>
        <div className={styles.backRow}>{backAction}</div>
        <ETPErrorState
          title="Unable to load capability details."
          className={styles.state}
          action={
            <div className={styles.errorActions}>
              <ETPButton variant="secondary" size="sm" onClick={() => reload()}>
                Retry
              </ETPButton>
              <Link to="/capabilities">
                <ETPButton variant="ghost" size="sm">Back to Capabilities</ETPButton>
              </Link>
            </div>
          }
        />
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <div className={styles.backRow}>{backAction}</div>

      <header className={styles.header}>
        <div className={styles.titleRow}>
          <h1 className={styles.capabilityName}>{capability.name}</h1>
          <div className={styles.versionSelect}>
            <ETPSelect
              label="Version"
              id="capability-version"
              value={selectedVersionId ?? ''}
              onChange={(event) => setSelectedVersionId(event.target.value)}
              disabled={versionOptions.length === 0}
              options={versionOptions}
              placeholder="No versions"
            />
          </div>
        </div>

        <div className={styles.codeRow}>
          <code className={styles.mono}>{capability.code}</code>
          <WorklistMenu
            label="More actions"
            iconOnly
            groups={[
              {
                items: [
                  { id: 'edit', label: 'Edit Capability', disabled: true },
                  { id: 'lifecycle', label: 'Lifecycle Actions', disabled: true },
                  { id: 'export', label: 'Export', disabled: true },
                  { id: 'delete', label: 'Delete', disabled: true, destructive: true },
                ],
              },
            ]}
          />
        </div>

        <div className={styles.badgeRow}>
          {capability.category && (
            <ETPBadge variant="default">{capability.category}</ETPBadge>
          )}
          <ETPBadge variant={capabilityStatusVariant(capability.status)}>
            {formatCapabilityStatusLabel(capability.status)}
          </ETPBadge>
        </div>

        {capability.description && (
          <p className={styles.description}>{capability.description}</p>
        )}
      </header>

      <ETPTabs defaultTabId="overview" className={styles.tabs}>
        <ETPTabList>
          <ETPTab id="overview">Overview</ETPTab>
          <ETPTab id="contract">Contract</ETPTab>
          <ETPTab id="dependencies">Dependencies</ETPTab>
        </ETPTabList>

        <ETPTabPanel id="overview">
          <CapabilityOverviewTab capability={capability} version={selectedVersion} />
        </ETPTabPanel>
        <ETPTabPanel id="contract">
          <CapabilityContractTab version={selectedVersion} />
        </ETPTabPanel>
        <ETPTabPanel id="dependencies">
          <CapabilityDependenciesTab version={selectedVersion} />
        </ETPTabPanel>
      </ETPTabs>
    </div>
  );
}
