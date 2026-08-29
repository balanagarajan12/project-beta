import { Bell, CircleHelp, PanelLeft, PanelLeftClose } from 'lucide-react';
import { useETPToast } from '@/components/ui';
import { iconSizes, iconStroke } from '@/lib/iconSizes';
import { ShellBrandBlock } from './ShellBrandBlock';
import { UserMenu } from './UserMenu';
import styles from './CoreHeader.module.css';

export interface CoreHeaderProps {
  sidebarCollapsed: boolean;
  onToggleSidebar: () => void;
}

export function CoreHeader({ sidebarCollapsed, onToggleSidebar }: CoreHeaderProps) {
  const { showToast } = useETPToast();

  const handlePlaceholderAction = (label: string) => {
    showToast(`${label} will be available in a future release.`, 'info');
  };

  return (
    <header className={styles.header}>
      <div className={styles.chromeLeft}>
        <button
          type="button"
          className={styles.iconButton}
          onClick={onToggleSidebar}
          aria-label={sidebarCollapsed ? 'Expand navigation' : 'Collapse navigation'}
          aria-expanded={!sidebarCollapsed}
        >
          {sidebarCollapsed ? (
            <PanelLeft size={iconSizes.nav} strokeWidth={iconStroke} aria-hidden />
          ) : (
            <PanelLeftClose size={iconSizes.nav} strokeWidth={iconStroke} aria-hidden />
          )}
        </button>

        <ShellBrandBlock />
      </div>

      <div className={styles.right}>
        <div className={styles.contextChip} aria-label="Environment selection">
          <span className={styles.contextLabel}>Environment</span>
          <span className={styles.contextValue}>—</span>
        </div>

        <button
          type="button"
          className={styles.iconButton}
          aria-label="Notifications"
          onClick={() => handlePlaceholderAction('Notifications')}
        >
          <Bell size={iconSizes.nav} strokeWidth={iconStroke} aria-hidden />
        </button>

        <button
          type="button"
          className={styles.iconButton}
          aria-label="Help"
          onClick={() => handlePlaceholderAction('Help')}
        >
          <CircleHelp size={iconSizes.nav} strokeWidth={iconStroke} aria-hidden />
        </button>

        <UserMenu />
      </div>
    </header>
  );
}
