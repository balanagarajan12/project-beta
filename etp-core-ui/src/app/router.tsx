import { Navigate } from 'react-router-dom';
import { AuthAwareRedirect } from '@/features/auth/AuthAwareRedirect';
import { GuestOnly } from '@/features/auth/GuestOnly';
import { LoginPage } from '@/features/auth/LoginPage';
import { RequireAuth } from '@/features/auth/RequireAuth';
import { CapabilitiesListPage } from '@/features/capabilities/CapabilitiesListPage';
import { CapabilityDetailsPage } from '@/features/capabilities/CapabilityDetailsPage';
import { CoreShellLayout } from '@/layouts/coreShell/CoreShellLayout';
import { ShellPlaceholderPage } from '@/layouts/coreShell/ShellPlaceholderPage';

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
    path: '/',
    element: (
      <RequireAuth>
        <CoreShellLayout />
      </RequireAuth>
    ),
    children: [
      {
        index: true,
        element: <Navigate to="/dashboard" replace />,
      },
      {
        path: 'dashboard',
        element: (
          <ShellPlaceholderPage
            title="Dashboard"
            description="Operational overview for the ETP platform."
          />
        ),
      },
      {
        path: 'customers',
        element: (
          <ShellPlaceholderPage
            title="Customers"
            description="Customer directory and customer-level administration."
          />
        ),
      },
      {
        path: 'capabilities',
        children: [
          {
            index: true,
            element: <CapabilitiesListPage />,
          },
          {
            path: ':capabilityId',
            element: <CapabilityDetailsPage />,
          },
        ],
      },
      {
        path: 'applications',
        element: (
          <ShellPlaceholderPage
            title="Applications"
            description="Application registry and composition."
          />
        ),
      },
      {
        path: 'platform/configuration',
        element: (
          <ShellPlaceholderPage
            title="Platform configuration"
            description="Platform-level configuration settings."
          />
        ),
      },
      {
        path: 'platform/version',
        element: (
          <ShellPlaceholderPage
            title="Platform version"
            description="ETP platform version information."
          />
        ),
      },
      {
        path: 'audit',
        element: (
          <ShellPlaceholderPage
            title="Audit"
            description="Platform audit events and activity history."
          />
        ),
      },
    ],
  },
  {
    path: '*',
    element: <AuthAwareRedirect />,
  },
];
