import type { TextareaHTMLAttributes } from 'react';
import { cn, describedBy } from '@/lib/classNames';
import fieldStyles from '../field.module.css';

export interface ETPTextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string;
  hint?: string;
  error?: string;
}

export function ETPTextarea({
  label,
  hint,
  error,
  id,
  className,
  rows = 4,
  ...rest
}: ETPTextareaProps) {
  const textareaId = id ?? rest.name;
  const errorId = error ? `${textareaId}-error` : undefined;
  const hintId = hint ? `${textareaId}-hint` : undefined;

  return (
    <div className={fieldStyles.field}>
      {label && (
        <label className={fieldStyles.label} htmlFor={textareaId}>
          {label}
        </label>
      )}
      <textarea
        id={textareaId}
        rows={rows}
        className={cn(fieldStyles.control, error && fieldStyles.error, className)}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(hintId, errorId)}
        {...rest}
      />
      {hint && !error && (
        <p id={hintId} className={fieldStyles.hint}>{hint}</p>
      )}
      {error && (
        <p id={errorId} className={fieldStyles.errorText} role="alert">{error}</p>
      )}
    </div>
  );
}
