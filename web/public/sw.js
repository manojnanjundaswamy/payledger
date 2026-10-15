/*
 * PayLedger's service worker: notifications only (no offline caching, so a new version is never stuck behind an old
 * cache). A push arrives encrypted by the server; the browser decrypts it and hands us {title, body, link, tag}.
 * With the app open and in front, the page shows it (with its chime); otherwise the phone or desktop does.
 */
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

self.addEventListener('push', (event) => {
  let data = {}
  try { data = event.data ? event.data.json() : {} } catch { data = { title: 'PayLedger', body: event.data ? event.data.text() : '' } }
  const title = data.title || 'PayLedger'
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    const visible = windows.find((w) => w.visibilityState === 'visible' && w.focused)
    if (visible) {
      visible.postMessage({ type: 'push', title, body: data.body || '', link: data.link || '/dashboard' })
      return
    }
    await self.registration.showNotification(title, {
      body: data.body || '',
      icon: '/icon-192.png',
      badge: '/badge-96.png',
      tag: data.tag || undefined,
      renotify: !!data.tag,
      vibrate: [70, 40, 70, 40, 160],
      timestamp: Date.now(),
      data: { link: data.link || '/dashboard' },
    })
  })())
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const link = (event.notification.data && event.notification.data.link) || '/dashboard'
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    const open = windows.find((w) => new URL(w.url).origin === self.location.origin)
    if (open) {
      await open.focus()
      open.postMessage({ type: 'open', link })
      return
    }
    await self.clients.openWindow(link)
  })())
})
