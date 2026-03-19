import { useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { Loader2, Plus } from 'lucide-react'
import GoogleSignInButton from '../auth/GoogleSignInButton'
import { useAuth } from '../auth/AuthProvider'
import LinkTable from '../components/LinkTable'
import AnalyticsPanel from '../components/AnalyticsPanel'
import { useUserLinks } from '../api/urls'

interface LocationState {
  selectedKey?: string
}

export default function DashboardPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { isAuthenticated } = useAuth()
  const locationState = location.state as LocationState | null

  const [selectedKey, setSelectedKey] = useState<string | null>(
    locationState?.selectedKey ?? null,
  )

  const { data, isLoading, error } = useUserLinks(isAuthenticated)

  const links = data ?? []

  if (!isAuthenticated) {
    return (
      <div className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-16 text-center space-y-4">
        <h1 className="text-3xl font-bold text-gray-900 dark:text-gray-100">Dashboard</h1>
        <p className="text-sm text-gray-500 dark:text-gray-400">
          Sign in with Google to view your links, delete them, and access analytics.
        </p>
        <div className="flex justify-center">
          <GoogleSignInButton />
        </div>
      </div>
    )
  }

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
      {/* Page header */}
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl sm:text-3xl font-bold text-gray-900 dark:text-gray-100 tracking-tight">
            My Links
          </h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Manage and track all your shortened URLs
          </p>
        </div>
        <button
          onClick={() => navigate('/')}
          className="inline-flex items-center gap-2 px-4 py-2.5 rounded-lg text-sm font-semibold text-white bg-primary-600 hover:bg-primary-700 active:bg-primary-800 dark:bg-primary-500 dark:hover:bg-primary-600 transition-colors focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2"
        >
          <Plus className="w-4 h-4" />
          Create new link
        </button>
      </div>

      {/* Loading state */}
      {isLoading && (
        <div className="flex items-center justify-center py-20 gap-2 text-gray-400 dark:text-gray-500">
          <Loader2 className="w-5 h-5 animate-spin" />
          <span className="text-sm">Loading your links…</span>
        </div>
      )}

      {/* Error state */}
      {!isLoading && error && (
        <div className="rounded-xl border border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-900/20 px-5 py-4 text-sm text-red-600 dark:text-red-400">
          {(error as { message?: string }).message || 'Failed to load links. Please try again.'}
        </div>
      )}

      {/* Table */}
      {!isLoading && !error && (
        <LinkTable
          links={links}
          isLoading={isLoading}
          error={error}
          selectedKey={selectedKey}
          onSelectKey={setSelectedKey}
        />
      )}

      {/* Analytics panel */}
      {selectedKey && (
        <div className="mt-8 bg-white dark:bg-gray-800 rounded-2xl border border-gray-200 dark:border-gray-700 shadow-sm p-6 sm:p-8">
          <div className="flex items-center justify-between mb-6">
            <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
              Analytics —{' '}
              <span className="text-primary-600 dark:text-primary-400 font-mono text-base">
                {selectedKey}
              </span>
            </h2>
            <button
              onClick={() => navigate(`/analytics/${selectedKey}`)}
              className="text-xs font-medium text-primary-600 dark:text-primary-400 hover:underline"
            >
              Full page view
            </button>
          </div>
          <AnalyticsPanel shortKey={selectedKey} />
        </div>
      )}
    </div>
  )
}
