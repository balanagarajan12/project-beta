import type { ReactNode } from 'react';
import { cn } from '@/lib/classNames';
import styles from './ETPErrorState.module.css';

export interface ETPErrorStateProps {
  title: string;
  description?: string;
  code?: string;
  action?: ReactNode;
  className?: string;
}

export function ETPErrorState({
  title,
  description,
  code,
  action,
  className,
}: ETPErrorStateProps) {
  return (
    <div className={cn(styles.state, className)} role="alert">
      <h2 className={styles.title}>{title}</h2>
      {description && <p className={styles.description}>{description}</p>}
      {code && <p className={styles.code}>Code: {code}</p>}
      {action && <div className={styles.actions}>{action}</div>}
    </div>
  );
}
