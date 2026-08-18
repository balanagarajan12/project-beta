import { Navigate } from 'react-router-dom';
import { LoginPage } from '@/features/auth/LoginPage';
import { DashboardPlaceholderPage } from '@/features/dashboard/DashboardPlaceholderPage';

export const routes = [
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/dashboard',
    element: <DashboardPlaceholderPage />,
  },
  {
    path: '/',
    element: <Navigate to="/login" replace />,
  },
  {
    path: '*',
    element: <Navigate to="/login" replace />,
  },
];
