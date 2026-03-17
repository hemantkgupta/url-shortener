import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import apiClient from './client'
import type {
  AnalyticsResponse,
  LinkItem,
  ShortenRequest,
  ShortenResponse,
} from '../types/api'

// ── Helpers ─────────────────────────────────────────────────────────────────

function isoDateNDaysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().split('T')[0]
}

function todayIso(): string {
  return new Date().toISOString().split('T')[0]
}

// ── Shorten URL ──────────────────────────────────────────────────────────────

export function useShortenUrl() {
  return useMutation<ShortenResponse, Error, ShortenRequest>({
    mutationFn: async (payload) => {
      const { data } = await apiClient.post<ShortenResponse>('/v1/urls', payload)
      return data
    },
  })
}

// ── User Links ───────────────────────────────────────────────────────────────

export function useUserLinks(userId?: number) {
  return useQuery<LinkItem[], Error>({
    queryKey: ['userLinks', userId],
    queryFn: async () => {
      const params: Record<string, string | number> = { page: 0, size: 20 }
      if (userId !== undefined) params.userId = userId
      const { data } = await apiClient.get<LinkItem[]>('/v1/urls', { params })
      return data
    },
    staleTime: 60_000, // 1 minute
  })
}

// ── Analytics ────────────────────────────────────────────────────────────────

export function useAnalytics(shortKey: string, enabled: boolean) {
  return useQuery<AnalyticsResponse, Error>({
    queryKey: ['analytics', shortKey],
    queryFn: async () => {
      const from = isoDateNDaysAgo(30)
      const to = todayIso()
      const { data } = await apiClient.get<AnalyticsResponse>(
        `/v1/urls/${shortKey}/analytics`,
        { params: { from, to } },
      )
      return data
    },
    enabled: enabled && Boolean(shortKey),
    staleTime: 5 * 60_000, // 5 minutes
  })
}

// ── Delete URL ───────────────────────────────────────────────────────────────

export function useDeleteUrl() {
  const queryClient = useQueryClient()

  return useMutation<void, Error, string>({
    mutationFn: async (shortKey: string) => {
      await apiClient.delete(`/v1/urls/${shortKey}`)
    },
    onSuccess: () => {
      // Invalidate the links list so the table refreshes
      void queryClient.invalidateQueries({ queryKey: ['userLinks'] })
    },
  })
}
