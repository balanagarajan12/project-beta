import { cn } from '@/lib/classNames';
import styles from './ETPLoadingState.module.css';

export interface ETPLoadingStateProps {
  message?: string;
  className?: string;
}

export function ETPLoadingState({
  message = 'Loading…',
  className,
}: ETPLoadingStateProps) {
  return (
    <div className={cn(styles.state, className)} role="status" aria-live="polite">
      <div className={styles.spinner} aria-hidden="true" />
      <p className={styles.message}>{message}</p>
    </div>
  );
}
