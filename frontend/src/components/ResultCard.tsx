import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Check, Copy, ExternalLink, BarChart2 } from 'lucide-react'
import clsx from 'clsx'
import type { ShortenResponse } from '../types/api'

interface ResultCardProps {
  result: ShortenResponse
}

function formatDate(iso: string): string {
  try {
    return new Intl.DateTimeFormat(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    }).format(new Date(iso))
  } catch {
    return iso
  }
}

function truncate(str: string, maxLen: number): string {
  return str.length > maxLen ? str.slice(0, maxLen) + '…' : str
}

export default function ResultCard({ result }: ResultCardProps) {
  const [copied, setCopied] = useState(false)
  const navigate = useNavigate()

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(result.short_url)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Fallback: select text (clipboard API not available in some contexts)
    }
  }

  return (
    <div
      className={clsx(
        'rounded-xl border border-gray-200 dark:border-gray-700',
        'bg-white dark:bg-gray-800 shadow-sm p-6 space-y-4',
        'animate-[fadeIn_0.3s_ease-out]',
      )}
    >
      <h3 className="text-sm font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide">
        Your shortened URL
      </h3>

      {/* Short URL box */}
      <div className="flex items-center gap-2">
        <div className="flex-1 min-w-0 bg-primary-50 dark:bg-primary-900/30 border border-primary-200 dark:border-primary-800 rounded-lg px-4 py-3">
          <a
            href={result.short_url}
            target="_blank"
            rel="noopener noreferrer"
            className="text-primary-700 dark:text-primary-300 font-semibold text-base hover:underline break-all"
          >
            {result.short_url}
          </a>
        </div>
        <button
          onClick={handleCopy}
          aria-label={copied ? 'Copied!' : 'Copy URL'}
          className={clsx(
            'flex-shrink-0 flex items-center gap-1.5 px-4 py-3 rounded-lg text-sm font-medium transition-all',
            copied
              ? 'bg-green-100 dark:bg-green-900/40 text-green-700 dark:text-green-300 border border-green-300 dark:border-green-700'
              : 'bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-300 border border-gray-200 dark:border-gray-600 hover:bg-gray-200 dark:hover:bg-gray-600',
          )}
        >
          {copied ? (
            <>
              <Check className="w-4 h-4" />
              Copied!
            </>
          ) : (
            <>
              <Copy className="w-4 h-4" />
              Copy
            </>
          )}
        </button>
      </div>

      {/* Original URL */}
      <div className="text-sm text-gray-500 dark:text-gray-400 flex items-start gap-1.5">
        <ExternalLink className="w-3.5 h-3.5 mt-0.5 flex-shrink-0" />
        <span title={result.long_url} className="break-all">
          {truncate(result.long_url, 80)}
        </span>
      </div>

      {/* Meta row */}
      <div className="flex flex-wrap items-center justify-between gap-3 pt-2 border-t border-gray-100 dark:border-gray-700">
        <span className="text-xs text-gray-400 dark:text-gray-500">
          {result.expires_at
            ? `Expires ${formatDate(result.expires_at)}`
            : 'No expiration'}
        </span>

        <button
          onClick={() =>
            navigate('/dashboard', { state: { selectedKey: result.short_key } })
          }
          className="flex items-center gap-1.5 text-xs font-medium text-primary-600 dark:text-primary-400 hover:underline"
        >
          <BarChart2 className="w-3.5 h-3.5" />
          View Analytics
        </button>
      </div>
    </div>
  )
}
