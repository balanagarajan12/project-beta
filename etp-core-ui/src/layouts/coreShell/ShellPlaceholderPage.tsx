import { ETPCard, ETPEmptyState, ETPPageHeader } from '@/components/ui';
import styles from './ShellPlaceholderPage.module.css';

export interface ShellPlaceholderPageProps {
  title: string;
  description?: string;
}

/**
 * Standard shell content placeholder until feature implementation lands.
 */
export function ShellPlaceholderPage({ title, description }: ShellPlaceholderPageProps) {
  return (
    <div className={styles.page}>
      <ETPPageHeader
        title={title}
        description={description}
      />
      <ETPCard className={styles.card}>
        <ETPEmptyState
          title="Coming soon"
          description={`${title} will be implemented in a future release.`}
        />
      </ETPCard>
    </div>
  );
}
