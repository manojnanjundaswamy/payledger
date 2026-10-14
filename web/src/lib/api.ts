/**
 * Thin fetch wrapper for the gateway. The browser only has a session cookie; writes echo the
 * XSRF-TOKEN cookie in the X-XSRF-TOKEN header (Spring Security SPA mode).
 */
export class ApiError extends Error {
  /** @param requestId the server's id for the request, to quote to support */
  constructor(readonly status: number, readonly code: string, message: string, readonly requestId?: string) {
    super(message)
  }
}

export class UnauthorizedError extends ApiError {
  constructor() {
    super(401, 'unauthorized', 'Your session has ended. Please sign in again.')
  }
}

function xsrfToken(): string | undefined {
  return document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length)
}

export async function api<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const { json, headers, ...rest } = init
  const method = (rest.method ?? 'GET').toUpperCase()
  const h = new Headers(headers)
  h.set('Accept', 'application/json')
  if (json !== undefined) h.set('Content-Type', 'application/json')
  if (method !== 'GET' && method !== 'HEAD') {
    const token = xsrfToken()
    if (token) h.set('X-XSRF-TOKEN', decodeURIComponent(token))
  }
  const response = await fetch(path, {
    ...rest,
    method,
    headers: h,
    credentials: 'same-origin',
    // The gateway answers a redirect to Keycloak when it needs the user to sign in again (their
    // refresh token expired). A fetch can't follow that cross-site; treat it like a 401.
    redirect: 'manual',
    body: json !== undefined ? JSON.stringify(json) : rest.body,
  })
  if (response.status === 401 || response.type === 'opaqueredirect') throw new UnauthorizedError()
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    throw new ApiError(response.status, problem.code ?? String(response.status), problem.detail ?? response.statusText,
      problem.requestId ?? response.headers?.get('X-Request-Id') ?? undefined)
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T)
}
