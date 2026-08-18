import type { InputHTMLAttributes, ReactNode } from 'react';
import { cn, describedBy } from '@/lib/classNames';
import fieldStyles from '../field.module.css';

export interface ETPInputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  hint?: string;
  error?: string;
  success?: boolean;
  leadingIcon?: ReactNode;
  trailingIcon?: ReactNode;
  trailingActionLabel?: string;
  onTrailingAction?: () => void;
  inputSize?: 'md' | 'lg';
}

export function ETPInput({
  label,
  hint,
  error,
  success,
  leadingIcon,
  trailingIcon,
  trailingActionLabel,
  onTrailingAction,
  inputSize = 'md',
  id,
  className,
  ...rest
}: ETPInputProps) {
  const inputId = id ?? rest.name;
  const errorId = error ? `${inputId}-error` : undefined;
  const hintId = hint ? `${inputId}-hint` : undefined;

  return (
    <div className={fieldStyles.field}>
      {label && (
        <label className={fieldStyles.label} htmlFor={inputId}>
          {label}
        </label>
      )}
      <div className={fieldStyles.wrapper}>
        {leadingIcon && <span className={fieldStyles.iconSlot}>{leadingIcon}</span>}
        <input
          id={inputId}
          className={cn(
            fieldStyles.control,
            inputSize === 'lg' && fieldStyles.controlLg,
            leadingIcon ? fieldStyles.withIcon : undefined,
            trailingIcon ? fieldStyles.withTrailingIcon : undefined,
            error && fieldStyles.error,
            success && fieldStyles.success,
            className,
          )}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(hintId, errorId)}
          {...rest}
        />
        {trailingIcon && (
          <span className={fieldStyles.trailingSlot}>
            {onTrailingAction ? (
              <button
                type="button"
                className={fieldStyles.trailingButton}
                onClick={onTrailingAction}
                aria-label={trailingActionLabel ?? 'Toggle'}
              >
                {trailingIcon}
              </button>
            ) : (
              trailingIcon
            )}
          </span>
        )}
      </div>
      {hint && !error && (
        <p id={hintId} className={fieldStyles.hint}>{hint}</p>
      )}
      {error && (
        <p id={errorId} className={fieldStyles.errorText} role="alert">{error}</p>
      )}
    </div>
  );
}
