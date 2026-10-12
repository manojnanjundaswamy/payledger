import type { WidgetType } from '@/state/preferences'

type Events = { edit: undefined; newDashboard: undefined; addWidget: WidgetType }
type Handler<K extends keyof Events> = (payload: Events[K]) => void

/** Lets the command palette drive the dashboard page without prop drilling. */
const handlers: { [K in keyof Events]?: Set<Handler<K>> } = {}

export const dashboardEvents = {
  emit<K extends keyof Events>(event: K, ...payload: Events[K] extends undefined ? [] : [Events[K]]) {
    handlers[event]?.forEach((h) => (h as Handler<K>)(payload[0] as Events[K]))
  },
  on<K extends keyof Events>(event: K, handler: Handler<K>): () => void {
    const set = (handlers[event] ??= new Set() as never) as Set<Handler<K>>
    set.add(handler)
    return () => set.delete(handler)
  },
}
