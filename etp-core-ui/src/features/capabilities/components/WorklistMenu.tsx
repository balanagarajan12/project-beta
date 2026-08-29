import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { ChevronDown, MoreVertical } from 'lucide-react';
import { iconSizes, iconStroke } from '@/lib/iconSizes';
import { cn } from '@/lib/classNames';
import styles from './WorklistMenu.module.css';

export interface WorklistMenuItem {
  id: string;
  label: string;
  disabled?: boolean;
  destructive?: boolean;
  onSelect?: () => void;
}

export interface WorklistMenuGroup {
  items: WorklistMenuItem[];
}

export interface WorklistMenuProps {
  label: string;
  groups: WorklistMenuGroup[];
  iconOnly?: boolean;
  align?: 'left' | 'right';
  className?: string;
  triggerClassName?: string;
  icon?: ReactNode;
}

export function WorklistMenu({
  label,
  groups,
  iconOnly = false,
  align = 'right',
  className,
  triggerClassName,
  icon,
}: WorklistMenuProps) {
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);

  useEffect(() => {
    if (!open) {
      return;
    }

    const handlePointerDown = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        close();
      }
    };

    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close();
      }
    };

    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleEscape);
    };
  }, [open, close]);

  const handleSelect = (item: WorklistMenuItem) => {
    if (item.disabled) {
      return;
    }
    close();
    item.onSelect?.();
  };

  return (
    <div className={cn(styles.menu, className)} ref={menuRef}>
      <button
        type="button"
        className={cn(styles.trigger, iconOnly && styles.iconTrigger, triggerClassName)}
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={iconOnly ? label : undefined}
      >
        {icon ?? (
          iconOnly ? (
            <MoreVertical size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden />
          ) : (
            <>
              <span>{label}</span>
              <ChevronDown size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden />
            </>
          )
        )}
      </button>

      {open && (
        <div
          className={cn(styles.panel, align === 'left' && styles.panelLeft)}
          role="menu"
          aria-label={label}
        >
          {groups.map((group, groupIndex) => (
            <div key={groupIndex}>
              {groupIndex > 0 && <div className={styles.divider} role="separator" />}
              <div className={styles.group}>
                {group.items.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    role="menuitem"
                    className={cn(
                      styles.menuItem,
                      item.destructive && styles.destructive,
                      item.disabled && styles.disabled,
                    )}
                    disabled={item.disabled}
                    onClick={() => handleSelect(item)}
                  >
                    {item.label}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
