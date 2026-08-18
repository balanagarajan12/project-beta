import type { ReactNode } from 'react';
import { cn } from '@/lib/classNames';
import styles from './ETPBadge.module.css';

export type ETPBadgeVariant = 'default' | 'success' | 'warning' | 'error' | 'info';

export interface ETPBadgeProps {
  variant?: ETPBadgeVariant;
  children: ReactNode;
  className?: string;
}

export function ETPBadge({ variant = 'default', children, className }: ETPBadgeProps) {
  return (
    <span className={cn(styles.badge, styles[variant], className)}>
      {children}
    </span>
  );
}
