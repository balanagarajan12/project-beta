import { Link } from 'react-router-dom';
import { cn } from '@/lib/classNames';
import styles from './ETPBreadcrumb.module.css';

export interface ETPBreadcrumbItem {
  label: string;
  href?: string;
}

export interface ETPBreadcrumbProps {
  items: ETPBreadcrumbItem[];
  className?: string;
}

export function ETPBreadcrumb({ items, className }: ETPBreadcrumbProps) {
  return (
    <nav className={cn(styles.nav, className)} aria-label="Breadcrumb">
      <ol className={styles.list}>
        {items.map((item, index) => {
          const isLast = index === items.length - 1;
          return (
            <li key={`${item.label}-${index}`} className={styles.item}>
              {index > 0 && <span className={styles.separator} aria-hidden="true">/</span>}
              {item.href && !isLast ? (
                <Link to={item.href} className={styles.link}>{item.label}</Link>
              ) : (
                <span className={isLast ? styles.current : undefined} aria-current={isLast ? 'page' : undefined}>
                  {item.label}
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
