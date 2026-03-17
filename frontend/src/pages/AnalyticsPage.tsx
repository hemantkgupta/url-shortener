import { useParams, Link } from 'react-router-dom'
import { ArrowLeft, Loader2, AlertCircle } from 'lucide-react'
import AnalyticsPanel from '../components/AnalyticsPanel'
import { useAnalytics } from '../api/urls'

export default function AnalyticsPage() {
  const { shortKey = '' } = useParams<{ shortKey: string }>()

  const { isLoading, error } = useAnalytics(shortKey, Boolean(shortKey))

  return (
    <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
      {/* Breadcrumb */}
      <div className="mb-8">
        <Link
          to="/dashboard"
          className="inline-flex items-center gap-1.5 text-sm font-medium text-gray-500 dark:text-gray-400 hover:text-primary-600 dark:hover:text-primary-400 transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          Back to Dashboard
        </Link>
      </div>

      {/* Page header */}
      <div className="mb-8">
        <h1 className="text-2xl sm:text-3xl font-bold text-gray-900 dark:text-gray-100 tracking-tight">
          Analytics
        </h1>
        {shortKey && (
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Short key:{' '}
            <span className="font-mono text-primary-600 dark:text-primary-400">{shortKey}</span>
          </p>
        )}
      </div>

      {/* Loading state */}
      {isLoading && (
        <div className="flex items-center justify-center py-20 gap-2 text-gray-400 dark:text-gray-500">
          <Loader2 className="w-5 h-5 animate-spin" />
          <span className="text-sm">Loading analytics…</span>
        </div>
      )}

      {/* Error state */}
      {!isLoading && error && (
        <div className="flex items-center gap-3 rounded-xl border border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-900/20 px-5 py-4">
          <AlertCircle className="w-5 h-5 text-red-500 dark:text-red-400 flex-shrink-0" />
          <span className="text-sm text-red-600 dark:text-red-400">
            {(error as { message?: string }).message || 'Failed to load analytics. Please try again.'}
          </span>
        </div>
      )}

      {/* Analytics panel */}
      {!isLoading && !error && shortKey && (
        <div className="bg-white dark:bg-gray-800 rounded-2xl border border-gray-200 dark:border-gray-700 shadow-sm p-6 sm:p-8">
          <AnalyticsPanel shortKey={shortKey} />
        </div>
      )}
    </div>
  )
}
