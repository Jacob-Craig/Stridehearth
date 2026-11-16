import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { createAppRouter } from './router';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

function InnerApp() {
  const auth = useAuth();

  // Created exactly once. Rebuilding the router when auth changed swapped in a new instance
  // mid-load, which under StrictMode never ran its initial load and rendered nothing (#63).
  // Auth reaches the routes through RouterProvider's context instead, and `invalidate()` re-runs
  // the route guards and loaders whenever it changes.
  const [router] = useState(() => createAppRouter({ queryClient, auth }));

  useEffect(() => {
    if (auth.isLoading && auth.user === null) return;
    router.invalidate();
  }, [router, auth.user, auth.isLoading]);

  return <RouterProvider router={router} context={{ queryClient, auth }} />;
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <InnerApp />
      </AuthProvider>
    </QueryClientProvider>
  );
}

export default App;
