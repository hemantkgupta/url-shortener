import axios, { AxiosError } from 'axios'
import { getAuthToken } from '../auth/session'
import type { ApiError } from '../types/api'

const apiClient = axios.create({
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 10_000,
})

apiClient.interceptors.request.use((config) => {
  const token = getAuthToken()
  if (token) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Response interceptor: normalise API errors
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiError>) => {
    const apiErrorPayload = error.response?.data
    const apiError: ApiError = {
      title: apiErrorPayload?.title,
      detail: apiErrorPayload?.detail,
      status: apiErrorPayload?.status ?? error.response?.status,
      timestamp: apiErrorPayload?.timestamp ?? new Date().toISOString(),
      message:
        apiErrorPayload?.detail ||
        apiErrorPayload?.message ||
        error.message ||
        'An unexpected error occurred.',
    }
    return Promise.reject(apiError)
  },
)

export default apiClient
