import type { LucideIcon } from 'lucide-react';
import {
  AppWindow,
  Boxes,
  ClipboardList,
  GitBranch,
  LayoutDashboard,
  Settings,
  Users,
} from 'lucide-react';

export interface CoreNavItem {
  id: string;
  label: string;
  path: string;
  icon: LucideIcon;
}

export interface CoreNavSection {
  id: string;
  label?: string;
  items: CoreNavItem[];
}

/** Primary Core shell navigation — keep top-level minimal; no entitlements or operating models. */
export const coreNavigationSections: CoreNavSection[] = [
  {
    id: 'overview',
    label: 'OVERVIEW',
    items: [
      { id: 'dashboard', label: 'Dashboard', path: '/dashboard', icon: LayoutDashboard },
    ],
  },
  {
    id: 'platform',
    label: 'PLATFORM',
    items: [
      { id: 'customers', label: 'Customers', path: '/customers', icon: Users },
      { id: 'capabilities', label: 'Capabilities', path: '/capabilities', icon: Boxes },
      { id: 'applications', label: 'Applications', path: '/applications', icon: AppWindow },
    ],
  },
  {
    id: 'platform-management',
    label: 'PLATFORM MANAGEMENT',
    items: [
      {
        id: 'platform-configuration',
        label: 'Configuration',
        path: '/platform/configuration',
        icon: Settings,
      },
      {
        id: 'platform-version',
        label: 'Version',
        path: '/platform/version',
        icon: GitBranch,
      },
      { id: 'audit', label: 'Audit', path: '/audit', icon: ClipboardList },
    ],
  },
];

export function isNavItemActive(pathname: string, itemPath: string): boolean {
  if (itemPath === '/dashboard') {
    return pathname === '/dashboard';
  }
  return pathname === itemPath || pathname.startsWith(`${itemPath}/`);
}
