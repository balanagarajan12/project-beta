import { cn } from '@/lib/classNames';
import styles from './ETPFilter.module.css';

export interface ETPFilterOption {
  value: string;
  label: string;
}

export interface ETPFilterProps {
  label?: string;
  options: ETPFilterOption[];
  value?: string;
  onChange?: (value: string) => void;
  className?: string;
}

export function ETPFilter({
  label = 'Filter',
  options,
  value,
  onChange,
  className,
}: ETPFilterProps) {
  return (
    <div className={cn(styles.group, className)} role="group" aria-label={label}>
      <span className={styles.label}>{label}</span>
      {options.map((option) => {
        const selected = value === option.value;
        return (
          <button
            key={option.value}
            type="button"
            className={cn(styles.chip, selected && styles.selected)}
            aria-pressed={selected}
            onClick={() => onChange?.(option.value)}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
