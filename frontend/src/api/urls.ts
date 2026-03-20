import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import apiClient from './client'
import type {
  ApiError,
  AnalyticsResponse,
  LinkItem,
  ShortenRequest,
  ShortenResponse,
} from '../types/api'

// ── Helpers ─────────────────────────────────────────────────────────────────

function isoDateTimeNDaysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString()
}

function nowIsoDateTime(): string {
  return new Date().toISOString()
}

// ── Shorten URL ──────────────────────────────────────────────────────────────

export function useShortenUrl() {
  return useMutation<ShortenResponse, ApiError, ShortenRequest>({
    mutationFn: async (payload) => {
      const { data } = await apiClient.post<ShortenResponse>('/api/write/v1/urls', payload)
      return data
    },
  })
}

// ── User Links ───────────────────────────────────────────────────────────────

export function useUserLinks(enabled: boolean) {
  return useQuery<LinkItem[], ApiError>({
    queryKey: ['userLinks'],
    queryFn: async () => {
      const { data } = await apiClient.get<LinkItem[]>('/api/analytics/v1/urls', {
        params: { page: 0, size: 20 },
      })
      return data
    },
    enabled,
    staleTime: 60_000, // 1 minute
  })
}

// ── Analytics ────────────────────────────────────────────────────────────────

export function useAnalytics(shortKey: string, enabled: boolean) {
  return useQuery<AnalyticsResponse, ApiError>({
    queryKey: ['analytics', shortKey],
    queryFn: async () => {
      const from = isoDateTimeNDaysAgo(30)
      const to = nowIsoDateTime()
      const { data } = await apiClient.get<AnalyticsResponse>(
        `/api/analytics/v1/urls/${shortKey}/analytics`,
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

  return useMutation<void, ApiError, string>({
    mutationFn: async (shortKey: string) => {
      await apiClient.delete(`/api/write/v1/urls/${shortKey}`)
    },
    onSuccess: () => {
      // Invalidate the links list so the table refreshes
      void queryClient.invalidateQueries({ queryKey: ['userLinks'] })
    },
  })
}
