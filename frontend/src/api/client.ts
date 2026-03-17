import axios, { AxiosError } from 'axios'
import type { ApiError } from '../types/api'

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 10_000,
})

// Response interceptor: normalise API errors
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiError>) => {
    const apiError: ApiError = error.response?.data ?? {
      error_code: 'UNKNOWN_ERROR',
      message: error.message || 'An unexpected error occurred.',
      timestamp: new Date().toISOString(),
    }
    // Attach the structured error so callers can read it
    return Promise.reject(apiError)
  },
)

export default apiClient
