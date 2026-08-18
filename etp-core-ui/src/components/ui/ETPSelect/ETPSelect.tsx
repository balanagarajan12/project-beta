import type { SelectHTMLAttributes } from 'react';
import { cn, describedBy } from '@/lib/classNames';
import fieldStyles from '../field.module.css';

export interface ETPSelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface ETPSelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
  hint?: string;
  error?: string;
  options: ETPSelectOption[];
  placeholder?: string;
}

export function ETPSelect({
  label,
  hint,
  error,
  options,
  placeholder,
  id,
  className,
  ...rest
}: ETPSelectProps) {
  const selectId = id ?? rest.name;
  const errorId = error ? `${selectId}-error` : undefined;
  const hintId = hint ? `${selectId}-hint` : undefined;

  return (
    <div className={fieldStyles.field}>
      {label && (
        <label className={fieldStyles.label} htmlFor={selectId}>
          {label}
        </label>
      )}
      <select
        id={selectId}
        className={cn(fieldStyles.control, error && fieldStyles.error, className)}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(hintId, errorId)}
        {...rest}
      >
        {placeholder && (
          <option value="" disabled>
            {placeholder}
          </option>
        )}
        {options.map((opt) => (
          <option key={opt.value} value={opt.value} disabled={opt.disabled}>
            {opt.label}
          </option>
        ))}
      </select>
      {hint && !error && (
        <p id={hintId} className={fieldStyles.hint}>{hint}</p>
      )}
      {error && (
        <p id={errorId} className={fieldStyles.errorText} role="alert">{error}</p>
      )}
    </div>
  );
}
