import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'

/** core-service ProgressService: a streak, badges, points and a level, and this week's challenge. */
export interface Badge {
  key: string; group: string; title: string; how: string; icon: string; points: number
  earnedAt: string | null; progress: number | null; target: number | null
}
export interface Streak { current: number; best: number; today: boolean; days: string[] }
export interface Challenge { key: string; title: string; how: string; progress: number; target: number; endsOn: string; done: boolean }
export interface Progress {
  points: number; level: string; levelFloor: number; nextAt: number | null; nextLevel: string | null
  streak: Streak; challenge: Challenge; badges: Badge[]; fresh: string[]
}

export function useProgress(enabled = true) {
  return useQuery({ queryKey: ['progress'], queryFn: () => api<Progress>('/api/core/me/progress'), enabled, staleTime: 30_000 })
}
