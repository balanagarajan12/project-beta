import type { ReactNode } from 'react';
import { cn } from '@/lib/classNames';
import styles from './ETPPageHeader.module.css';

export interface ETPPageHeaderProps {
  title: string;
  description?: string;
  actions?: ReactNode;
  className?: string;
}

export function ETPPageHeader({ title, description, actions, className }: ETPPageHeaderProps) {
  return (
    <header className={cn(styles.header, className)}>
      <div className={styles.titleBlock}>
        <h1 className={styles.title}>{title}</h1>
        {description && <p className={styles.description}>{description}</p>}
      </div>
      {actions && <div className={styles.actions}>{actions}</div>}
    </header>
  );
}
