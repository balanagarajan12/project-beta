import { NavLink, useLocation } from 'react-router-dom';
import { cn } from '@/lib/classNames';
import { coreNavigationSections, isNavItemActive } from './navigation';
import styles from './CoreSidebar.module.css';

export interface CoreSidebarProps {
  collapsed: boolean;
}

export function CoreSidebar({ collapsed }: CoreSidebarProps) {
  const { pathname } = useLocation();

  return (
    <nav
      className={cn(styles.sidebar, collapsed && styles.sidebarCollapsed)}
      aria-label="Primary navigation"
    >
      {coreNavigationSections.map((section) => (
        <div key={section.id} className={styles.section}>
          {section.label && !collapsed && (
            <div className={styles.sectionLabel}>{section.label}</div>
          )}
          <ul className={styles.navList}>
            {section.items.map((item) => {
              const isActive = isNavItemActive(pathname, item.path);
              const Icon = item.icon;

              return (
                <li key={item.id}>
                  <NavLink
                    to={item.path}
                    className={cn(
                      styles.navLink,
                      collapsed && styles.navLinkCollapsed,
                      isActive && styles.navLinkActive,
                    )}
                    aria-current={isActive ? 'page' : undefined}
                    title={collapsed ? item.label : undefined}
                  >
                    <Icon className={styles.navIcon} aria-hidden />
                    {!collapsed && <span className={styles.navLabel}>{item.label}</span>}
                  </NavLink>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );
}
