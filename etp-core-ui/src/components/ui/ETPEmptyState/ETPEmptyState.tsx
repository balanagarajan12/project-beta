import type { ReactNode } from 'react';
import { cn } from '@/lib/classNames';
import styles from './ETPEmptyState.module.css';

export interface ETPEmptyStateProps {
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}

export function ETPEmptyState({
  title,
  description,
  action,
  className,
}: ETPEmptyStateProps) {
  return (
    <div className={cn(styles.state, className)}>
      <h2 className={styles.title}>{title}</h2>
      {description && <p className={styles.description}>{description}</p>}
      {action && <div className={styles.actions}>{action}</div>}
    </div>
  );
}
