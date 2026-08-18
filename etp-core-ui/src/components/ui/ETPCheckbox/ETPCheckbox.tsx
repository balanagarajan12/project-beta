import type { InputHTMLAttributes } from 'react';
import { cn } from '@/lib/classNames';
import styles from './ETPCheckbox.module.css';

export interface ETPCheckboxProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: string;
}

export function ETPCheckbox({ label, className, id, disabled, ...rest }: ETPCheckboxProps) {
  const checkboxId = id ?? rest.name;

  return (
    <label
      className={cn(styles.checkbox, disabled && styles.disabled, className)}
      htmlFor={checkboxId}
    >
      <input
        type="checkbox"
        id={checkboxId}
        className={styles.input}
        disabled={disabled}
        {...rest}
      />
      <span className={styles.label}>{label}</span>
    </label>
  );
}
