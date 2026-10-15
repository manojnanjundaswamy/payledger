import { api } from './api'

/**
 * Push notifications on this device: the service worker (public/sw.js) receives them, even with the app closed. The
 * browser asks for permission only when someone presses "Turn on", never by itself.
 */
export type PushState = 'unsupported' | 'blocked' | 'off' | 'on'

export function pushSupported(): boolean {
  return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

/** Registers the service worker once, at start. Harmless where unsupported. */
export function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return
  void navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => undefined)
}

export async function pushState(): Promise<PushState> {
  if (!pushSupported()) return 'unsupported'
  if (Notification.permission === 'denied') return 'blocked'
  const reg = await navigator.serviceWorker.getRegistration('/')
  const sub = await reg?.pushManager.getSubscription()
  return Notification.permission === 'granted' && sub ? 'on' : 'off'
}

function toBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const pad = '='.repeat((4 - (base64url.length % 4)) % 4)
  const raw = atob((base64url + pad).replace(/-/g, '+').replace(/_/g, '/'))
  const out = new Uint8Array(new ArrayBuffer(raw.length))
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

/** Asks the browser, subscribes with the server's key, and tells the server. */
export async function enablePush(): Promise<PushState> {
  if (!pushSupported()) return 'unsupported'
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') return permission === 'denied' ? 'blocked' : 'off'
  const reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' })
  await navigator.serviceWorker.ready
  const { publicKey } = await api<{ publicKey: string }>('/api/core/push/key')
  let sub = await reg.pushManager.getSubscription()
  // A subscription made with another server key (a reset) can't be used: start again.
  if (sub && sub.options.applicationServerKey) {
    const current = new Uint8Array(sub.options.applicationServerKey)
    const wanted = toBytes(publicKey)
    if (current.length !== wanted.length || current.some((b, i) => b !== wanted[i])) {
      await sub.unsubscribe()
      sub = null
    }
  }
  sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: toBytes(publicKey) })
  await api('/api/core/push/subscriptions', { method: 'POST', json: sub.toJSON() })
  return 'on'
}

export async function disablePush(): Promise<PushState> {
  const reg = await navigator.serviceWorker.getRegistration('/')
  const sub = await reg?.pushManager.getSubscription()
  if (sub) {
    await api('/api/core/push/subscriptions', { method: 'DELETE', json: { endpoint: sub.endpoint } }).catch(() => undefined)
    await sub.unsubscribe()
  }
  return pushSupported() ? 'off' : 'unsupported'
}

export function sendTestPush() {
  return api('/api/core/push/test', { method: 'POST' })
}
