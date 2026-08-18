import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { ETPToastProvider } from '@/components/ui';
import { AuthProvider } from '@/features/auth/AuthContext';
import { routes } from './router';

const browserRouter = createBrowserRouter(routes);

export function App() {
  return (
    <AuthProvider>
      <ETPToastProvider>
        <RouterProvider router={browserRouter} />
      </ETPToastProvider>
    </AuthProvider>
  );
}
