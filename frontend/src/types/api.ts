export interface ShortenRequest {
  long_url: string;
  custom_key?: string;
  ttl_days?: number;
}

export interface ShortenResponse {
  short_url: string;
  short_key: string;
  long_url: string;
  expires_at: string; // ISO-8601
  created_at: string;
}

export interface AnalyticsResponse {
  short_key: string;
  total_clicks: number;
  clicks_by_day: Record<string, number>;
  clicks_by_country: Record<string, number>;
  clicks_by_device: Record<string, number>;
  clicks_by_referrer: Record<string, number>;
  period_from: string;
  period_to: string;
}

export interface LinkItem {
  short_key: string;
  short_url: string;
  long_url: string;
  created_at: string;
  expires_at: string | null;
  total_clicks?: number;
}

export interface ApiError {
  error_code: string;
  message: string;
  timestamp: string;
}
