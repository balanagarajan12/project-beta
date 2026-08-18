import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { iconSizes, iconStroke } from '@/lib/iconSizes';
import { cn } from '@/lib/classNames';
import { ToastContext, type ToastItem, type ToastVariant } from './toastContext';
import styles from './ETPToast.module.css';

export function ETPToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const showToast = useCallback((message: string, variant: ToastVariant = 'default') => {
    const id = crypto.randomUUID();
    setToasts((prev) => [...prev, { id, message, variant }]);
    window.setTimeout(() => removeToast(id), 5000);
  }, [removeToast]);

  const value = useMemo(() => ({ showToast }), [showToast]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      {createPortal(
        <div className={styles.container} aria-live="polite" aria-label="Notifications">
          {toasts.map((toast) => (
            <div
              key={toast.id}
              className={cn(styles.toast, toast.variant !== 'default' && styles[toast.variant])}
              role="status"
            >
              <span className={styles.message}>{toast.message}</span>
              <button
                type="button"
                className={styles.close}
                aria-label="Dismiss notification"
                onClick={() => removeToast(toast.id)}
              >
                <X size={iconSizes.sm} strokeWidth={iconStroke} />
              </button>
            </div>
          ))}
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  );
}
