export interface ShortenRequest {
  longUrl: string
  customKey?: string
  ttlDays?: number
}

export interface ShortenResponse {
  shortUrl: string
  shortKey: string
  longUrl: string
  expiresAt: string
  createdAt: string
}

export interface AnalyticsResponse {
  shortKey: string
  totalClicks: number
  clicksByDay: Record<string, number>
  clicksByCountry: Record<string, number>
  clicksByDevice: Record<string, number>
  clicksByReferrer: Record<string, number>
  periodFrom: string
  periodTo: string
}

export interface LinkItem {
  shortUrl: string
  shortKey: string
  longUrl: string
  createdAt: string
  expiresAt: string | null
  totalClicks?: number
}

export interface ApiError {
  title?: string
  detail?: string
  message: string
  status?: number
  timestamp?: string
}
