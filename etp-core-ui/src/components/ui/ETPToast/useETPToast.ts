import { useContext } from 'react';
import { ToastContext, type ToastContextValue } from './toastContext';

export function useETPToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error('useETPToast must be used within ETPToastProvider');
  }
  return ctx;
}
