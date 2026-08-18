import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/classNames';
import styles from './ETPCard.module.css';

export interface ETPCardProps extends HTMLAttributes<HTMLDivElement> {
  padding?: 'none' | 'md';
  children: ReactNode;
}

export function ETPCard({ padding = 'md', children, className, ...rest }: ETPCardProps) {
  return (
    <div className={cn(styles.card, styles[`padding-${padding}`], className)} {...rest}>
      {children}
    </div>
  );
}

export function ETPCardHeader({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn(styles.header, className)}>{children}</div>;
}

export function ETPCardBody({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn(styles.body, className)}>{children}</div>;
}

export function ETPCardFooter({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn(styles.footer, className)}>{children}</div>;
}
