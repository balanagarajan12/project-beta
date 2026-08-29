import { Outlet } from 'react-router-dom';
import { CoreHeader } from './CoreHeader';
import { CoreSidebar } from './CoreSidebar';
import styles from './CoreShellLayout.module.css';
import { useSidebarCollapsed } from './useSidebarCollapsed';

export function CoreShellLayout() {
  const { collapsed, toggle } = useSidebarCollapsed();

  return (
    <div className={styles.shell}>
      <CoreHeader sidebarCollapsed={collapsed} onToggleSidebar={toggle} />
      <div className={styles.body}>
        <CoreSidebar collapsed={collapsed} />
        <main className={styles.main} id="main-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
