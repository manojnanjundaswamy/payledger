import { queryOptions, useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'

export interface Session {
  authenticated: boolean
  email: string | null
  name: string | null
  loginUrl: string | null
  signupUrl: string | null
  accountUrl: string | null
  /** "Continue with Google", when Google sign-in is set up on this server */
  googleUrl?: string | null
}

export const sessionQuery = queryOptions({
  queryKey: ['session'],
  queryFn: () => api<Session>('/api/session'),
  staleTime: 60_000,
})

export function useSession() {
  return useQuery(sessionQuery)
}

export async function signOut() {
  const { redirectUrl } = await api<{ redirectUrl: string }>('/logout', { method: 'POST' })
  window.location.assign(redirectUrl)
}
