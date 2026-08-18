import type { InputHTMLAttributes } from 'react';
import { Search } from 'lucide-react';
import { iconSizes, iconStroke } from '@/lib/iconSizes';
import { cn } from '@/lib/classNames';
import styles from './ETPSearch.module.css';

export interface ETPSearchProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  className?: string;
}

export function ETPSearch({ className, ...rest }: ETPSearchProps) {
  return (
    <div className={styles.wrapper}>
      <Search
        className={styles.icon}
        size={iconSizes.sm}
        strokeWidth={iconStroke}
        aria-hidden="true"
      />
      <input
        type="search"
        className={cn(styles.input, className)}
        aria-label={rest['aria-label'] ?? 'Search'}
        {...rest}
      />
    </div>
  );
}
