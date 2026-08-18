import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/classNames';
import styles from './ETPButton.module.css';

export type ETPButtonVariant = 'primary' | 'secondary' | 'ghost' | 'destructive';
export type ETPButtonSize = 'sm' | 'md' | 'lg';

export interface ETPButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ETPButtonVariant;
  size?: ETPButtonSize;
  loading?: boolean;
  fullWidth?: boolean;
  children: ReactNode;
}

export function ETPButton({
  variant = 'primary',
  size = 'md',
  loading = false,
  fullWidth = false,
  disabled,
  className,
  children,
  type = 'button',
  ...rest
}: ETPButtonProps) {
  return (
    <button
      type={type}
      className={cn(
        styles.button,
        styles[variant],
        styles[size],
        loading && styles.loading,
        fullWidth && styles.fullWidth,
        className,
      )}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading && <span className={styles.spinner} aria-hidden="true" />}
      {!loading && children}
      {loading && <span className="sr-only">Loading</span>}
    </button>
  );
}
