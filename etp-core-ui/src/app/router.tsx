import { AuthAwareRedirect } from '@/features/auth/AuthAwareRedirect';
import { GuestOnly } from '@/features/auth/GuestOnly';
import { LoginPage } from '@/features/auth/LoginPage';
import { RequireAuth } from '@/features/auth/RequireAuth';
import { DashboardPlaceholderPage } from '@/features/dashboard/DashboardPlaceholderPage';

export const routes = [
  {
    path: '/login',
    element: (
      <GuestOnly>
        <LoginPage />
      </GuestOnly>
    ),
  },
  {
    path: '/dashboard',
    element: (
      <RequireAuth>
        <DashboardPlaceholderPage />
      </RequireAuth>
    ),
  },
  {
    path: '/',
    element: <AuthAwareRedirect />,
  },
  {
    path: '*',
    element: <AuthAwareRedirect />,
  },
];
