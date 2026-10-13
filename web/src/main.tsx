import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import { Toaster, toast } from 'sonner'
import { ApiError, UnauthorizedError } from '@/lib/api'
import { createAppRouter } from './router'
import './styles.css'

function onError(error: Error) {
  // A Free limit, wherever it is hit: say which one, and where Premium and the trial are.
  if (error instanceof ApiError && error.code === 'plan-limit') {
    void queryClient.invalidateQueries({ queryKey: ['plan'] })
    toast('That needs Premium', {
      description: error.message.charAt(0).toUpperCase() + error.message.slice(1) + '.',
      action: { label: 'See plans', onClick: () => void router.navigate({ to: '/settings', search: { tab: 'plan' } }) },
    })
    return
  }
  if (error instanceof UnauthorizedError) {
    // The session expired (or was ended elsewhere): go back to sign in, once.
    queryClient.clear()
    toast('Your session ended', { description: 'Please sign in again.' })
    void router.navigate({ to: '/' })
  }
}

const queryClient: QueryClient = new QueryClient({
  queryCache: new QueryCache({ onError }),
  mutationCache: new MutationCache({ onError }),
  defaultOptions: { queries: { retry: (count, error) => !(error instanceof UnauthorizedError) && count < 2, refetchOnWindowFocus: false } },
})
const router = createAppRouter(queryClient)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <Toaster position="bottom-right" closeButton toastOptions={{
        classNames: {
          toast: '!rounded-card !border-border !bg-surface !text-fg !shadow-lift',
          description: '!text-muted',
          actionButton: '!bg-accent !text-accent-fg',
        },
      }} />
    </QueryClientProvider>
  </StrictMode>,
)
