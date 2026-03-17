import { useState } from 'react'
import { ChevronDown, ChevronUp, Link2, Loader2 } from 'lucide-react'
import clsx from 'clsx'
import { useShortenUrl } from '../api/urls'
import type { ShortenResponse } from '../types/api'

interface ShortenFormProps {
  onSuccess: (result: ShortenResponse) => void
}

function isValidUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

export default function ShortenForm({ onSuccess }: ShortenFormProps) {
  const [longUrl, setLongUrl] = useState('')
  const [customKey, setCustomKey] = useState('')
  const [ttlDays, setTtlDays] = useState<string>('')
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [validationError, setValidationError] = useState<string | null>(null)

  const { mutate, isPending, error } = useShortenUrl()

  const apiError = error as { message?: string } | null

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setValidationError(null)

    const trimmed = longUrl.trim()
    if (!trimmed) {
      setValidationError('Please enter a URL.')
      return
    }
    if (!isValidUrl(trimmed)) {
      setValidationError('Please enter a valid URL starting with http:// or https://')
      return
    }

    mutate(
      {
        long_url: trimmed,
        custom_key: customKey.trim() || undefined,
        ttl_days: ttlDays ? parseInt(ttlDays, 10) : undefined,
      },
      {
        onSuccess: (data) => {
          onSuccess(data)
        },
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-4">
      {/* Main URL input */}
      <div>
        <label htmlFor="long-url" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1.5">
          Long URL
        </label>
        <div className="relative">
          <span className="absolute inset-y-0 left-0 flex items-center pl-3 pointer-events-none text-gray-400">
            <Link2 className="w-4 h-4" />
          </span>
          <input
            id="long-url"
            type="url"
            value={longUrl}
            onChange={(e) => {
              setLongUrl(e.target.value)
              setValidationError(null)
            }}
            placeholder="Paste your long URL here..."
            className={clsx(
              'w-full pl-10 pr-4 py-3 rounded-lg border text-sm transition-colors',
              'bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100',
              'placeholder-gray-400 dark:placeholder-gray-500',
              'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent',
              validationError
                ? 'border-red-400 dark:border-red-500'
                : 'border-gray-300 dark:border-gray-600 hover:border-gray-400 dark:hover:border-gray-500',
            )}
          />
        </div>
        {validationError && (
          <p className="mt-1.5 text-xs text-red-500 dark:text-red-400">{validationError}</p>
        )}
      </div>

      {/* Advanced options toggle */}
      <div>
        <button
          type="button"
          onClick={() => setShowAdvanced((v) => !v)}
          className="flex items-center gap-1 text-xs font-medium text-primary-600 dark:text-primary-400 hover:underline"
        >
          {showAdvanced ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
          Advanced options
        </button>

        {showAdvanced && (
          <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-3">
            {/* Custom alias */}
            <div>
              <label
                htmlFor="custom-key"
                className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1"
              >
                Custom alias
              </label>
              <input
                id="custom-key"
                type="text"
                value={customKey}
                onChange={(e) => setCustomKey(e.target.value)}
                placeholder="e.g. my-link"
                maxLength={64}
                className={clsx(
                  'w-full px-3 py-2 rounded-lg border text-sm transition-colors',
                  'bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100',
                  'placeholder-gray-400 dark:placeholder-gray-500',
                  'border-gray-300 dark:border-gray-600',
                  'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent',
                  'hover:border-gray-400 dark:hover:border-gray-500',
                )}
              />
            </div>

            {/* TTL days */}
            <div>
              <label
                htmlFor="ttl-days"
                className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1"
              >
                Expires after (days)
              </label>
              <input
                id="ttl-days"
                type="number"
                min={1}
                max={3650}
                value={ttlDays}
                onChange={(e) => setTtlDays(e.target.value)}
                placeholder="e.g. 30"
                className={clsx(
                  'w-full px-3 py-2 rounded-lg border text-sm transition-colors',
                  'bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100',
                  'placeholder-gray-400 dark:placeholder-gray-500',
                  'border-gray-300 dark:border-gray-600',
                  'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent',
                  'hover:border-gray-400 dark:hover:border-gray-500',
                )}
              />
            </div>
          </div>
        )}
      </div>

      {/* API error */}
      {apiError && (
        <p className="text-xs text-red-500 dark:text-red-400 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg px-3 py-2">
          {apiError.message ?? 'Something went wrong. Please try again.'}
        </p>
      )}

      {/* Submit */}
      <button
        type="submit"
        disabled={isPending}
        className={clsx(
          'w-full flex items-center justify-center gap-2',
          'px-6 py-3 rounded-lg text-sm font-semibold text-white',
          'bg-primary-600 hover:bg-primary-700 active:bg-primary-800',
          'dark:bg-primary-500 dark:hover:bg-primary-600',
          'transition-colors focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2',
          'disabled:opacity-60 disabled:cursor-not-allowed',
        )}
      >
        {isPending ? (
          <>
            <Loader2 className="w-4 h-4 animate-spin" />
            Shortening…
          </>
        ) : (
          'Shorten URL'
        )}
      </button>
    </form>
  )
}
