import { StrictMode } from 'react'
import { registerServiceWorker } from '@/lib/push'
import { createRoot } from 'react-dom/client'
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import { Toaster, toast } from 'sonner'
import { ApiError, UnauthorizedError } from '@/lib/api'
import { createAppRouter } from './router'
import './styles.css'

// After a new version is deployed, an open tab may ask for a page file that no longer exists. Reload once to get the
// new version instead of showing an error (sessionStorage stops a reload loop if the file is really missing).
window.addEventListener('vite:preloadError', (event) => {
  try {
    if (sessionStorage.getItem('reloaded-for-update') === location.pathname) return
    sessionStorage.setItem('reloaded-for-update', location.pathname)
  } catch { /* storage blocked: reload anyway */ }
  event.preventDefault()
  location.reload()
})

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
  // Our side failed: say so, with the reference support needs to find exactly this request.
  if (error instanceof ApiError && error.status >= 500 && !(error instanceof UnauthorizedError)) {
    toast.error('Something went wrong on our side', {
      description: error.requestId ? `Try again in a moment. If it keeps happening, quote reference ${error.requestId.slice(0, 8)} to support.` : 'Try again in a moment.',
    })
    return
  }
  // Guest pages have their own session (a cookie from a join link); they say so themselves.
  if (error instanceof UnauthorizedError && location.pathname.startsWith('/guest')) return
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

registerServiceWorker()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <Toaster position="top-right" closeButton toastOptions={{
        classNames: {
          toast: '!rounded-card !border-border !bg-surface !text-fg !shadow-lift',
          description: '!text-muted',
          actionButton: '!bg-accent !text-accent-fg',
        },
      }} />
    </QueryClientProvider>
  </StrictMode>,
)
