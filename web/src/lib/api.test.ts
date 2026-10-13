import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, UnauthorizedError } from './api'

function respond(response: Partial<Response>) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, type: 'basic', json: async () => ({}), ...response }))
}

describe('api', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('turns a redirect to the sign-in page into UnauthorizedError, like a 401', async () => {
    respond({ ok: false, status: 0, type: 'opaqueredirect' })
    await expect(api('/api/core/me')).rejects.toBeInstanceOf(UnauthorizedError)
    respond({ ok: false, status: 401 })
    await expect(api('/api/core/me')).rejects.toBeInstanceOf(UnauthorizedError)
  })

  it('never follows redirects itself', async () => {
    respond({ json: async () => ({ id: 1 }) })
    await expect(api('/api/core/me')).resolves.toEqual({ id: 1 })
    expect(vi.mocked(fetch).mock.calls[0]![1]).toMatchObject({ redirect: 'manual', credentials: 'same-origin' })
  })

  it('reads the stable code from problem+json', async () => {
    respond({ ok: false, status: 503, statusText: 'Service Unavailable', json: async () => ({ code: 'core-service-unavailable', detail: 'try again' }) })
    const error = await api('/api/core/me').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 503, code: 'core-service-unavailable', message: 'try again' })
  })
})
