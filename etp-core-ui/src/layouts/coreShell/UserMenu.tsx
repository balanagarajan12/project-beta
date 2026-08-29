import { ChevronDown, LogOut } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/features/auth/AuthContext';
import { formatUserRole } from './formatUserRole';
import styles from './UserMenu.module.css';

function initials(displayName: string): string {
  const parts = displayName.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return '?';
  }
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase();
  }
  return `${parts[0][0] ?? ''}${parts[1][0] ?? ''}`.toUpperCase();
}

export function UserMenu() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
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

  const handleLogout = () => {
    close();
    logout();
    navigate('/login', { replace: true });
  };

  if (!user) {
    return null;
  }

  const roleLabel = formatUserRole(user.roles);

  return (
    <div className={styles.menu} ref={menuRef}>
      <button
        type="button"
        className={styles.trigger}
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label="User menu"
      >
        <span className={styles.avatar}>{initials(user.displayName)}</span>
        <span className={styles.userInfo}>
          <span className={styles.userName}>{user.displayName}</span>
          <span className={styles.userRole}>{roleLabel}</span>
        </span>
        <ChevronDown className={styles.chevron} aria-hidden />
      </button>

      {open && (
        <div className={styles.panel} role="menu">
          <div className={styles.userBlock}>
            <div className={styles.displayName}>{user.displayName}</div>
            <div className={styles.role}>{roleLabel}</div>
            {user.email && <div className={styles.email}>{user.email}</div>}
          </div>
          <button
            type="button"
            className={styles.menuItem}
            role="menuitem"
            onClick={handleLogout}
          >
            <LogOut className={styles.menuIcon} aria-hidden />
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
