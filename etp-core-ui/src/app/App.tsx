import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { ETPToastProvider } from '@/components/ui';
import { routes } from './router';

const browserRouter = createBrowserRouter(routes);

export function App() {
  return (
    <ETPToastProvider>
      <RouterProvider router={browserRouter} />
    </ETPToastProvider>
  );
}
