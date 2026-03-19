import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Copy, Trash2, ExternalLink, Check, AlertCircle, Plus } from 'lucide-react'
import clsx from 'clsx'
import type { LinkItem } from '../types/api'
import { useDeleteUrl } from '../api/urls'

interface LinkTableProps {
  links: LinkItem[]
  isLoading: boolean
  error: Error | null
  selectedKey: string | null
  onSelectKey: (key: string | null) => void
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

function truncate(str: string, max: number) {
  return str.length > max ? str.slice(0, max) + '…' : str
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  async function handleCopy(e: React.MouseEvent) {
    e.stopPropagation()
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      /* ignore */
    }
  }

  return (
    <button
      onClick={handleCopy}
      aria-label={copied ? 'Copied' : 'Copy short URL'}
      title={copied ? 'Copied!' : 'Copy'}
      className={clsx(
        'p-1.5 rounded transition-colors',
        copied
          ? 'text-green-600 dark:text-green-400'
          : 'text-gray-400 hover:text-gray-700 dark:hover:text-gray-200',
      )}
    >
      {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
    </button>
  )
}

function SkeletonRow() {
  return (
    <tr className="animate-pulse">
      {[1, 2, 3, 4, 5].map((i) => (
        <td key={i} className="px-4 py-3">
          <div className="h-4 bg-gray-200 dark:bg-gray-700 rounded w-full" />
        </td>
      ))}
    </tr>
  )
}

export default function LinkTable({
  links,
  isLoading,
  error,
  selectedKey,
  onSelectKey,
}: LinkTableProps) {
  const { mutate: deleteUrl, isPending: isDeleting } = useDeleteUrl()

  function handleDelete(e: React.MouseEvent, shortKey: string) {
    e.stopPropagation()
    if (!window.confirm(`Delete "${shortKey}"? This cannot be undone.`)) return
    deleteUrl(shortKey, {
      onSuccess: () => {
        if (selectedKey === shortKey) onSelectKey(null)
      },
    })
  }

  if (error) {
    return (
      <div className="flex items-center gap-2 text-red-500 dark:text-red-400 py-8 justify-center">
        <AlertCircle className="w-5 h-5" />
        <span className="text-sm">{error.message || 'Failed to load links.'}</span>
      </div>
    )
  }

  if (!isLoading && links.length === 0) {
    return (
      <div className="text-center py-16 text-gray-400 dark:text-gray-500">
        <p className="text-base font-medium">No links yet.</p>
        <p className="text-sm mt-1">Shorten your first URL to get started.</p>
        <Link
          to="/"
          className="mt-4 inline-flex items-center gap-1.5 text-sm font-medium text-primary-600 dark:text-primary-400 hover:underline"
        >
          <Plus className="w-4 h-4" />
          Shorten a URL
        </Link>
      </div>
    )
  }

  return (
    <div className="overflow-x-auto rounded-xl border border-gray-200 dark:border-gray-700">
      <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
        <thead className="bg-gray-50 dark:bg-gray-800/60">
          <tr>
            {['Short URL', 'Original URL', 'Created', 'Expires', 'Clicks', 'Actions'].map(
              (col) => (
                <th
                  key={col}
                  className="px-4 py-3 text-left text-xs font-semibold text-gray-500 dark:text-gray-400 uppercase tracking-wide whitespace-nowrap"
                >
                  {col}
                </th>
              ),
            )}
          </tr>
        </thead>
        <tbody className="bg-white dark:bg-gray-900 divide-y divide-gray-100 dark:divide-gray-800">
          {isLoading ? (
            <>
              <SkeletonRow />
              <SkeletonRow />
              <SkeletonRow />
            </>
          ) : (
            links.map((link) => (
              <tr
                key={link.shortKey}
                onClick={() =>
                  onSelectKey(selectedKey === link.shortKey ? null : link.shortKey)
                }
                className={clsx(
                  'cursor-pointer transition-colors',
                  selectedKey === link.shortKey
                    ? 'bg-primary-50 dark:bg-primary-900/20'
                    : 'hover:bg-gray-50 dark:hover:bg-gray-800/40',
                )}
              >
                {/* Short URL */}
                <td className="px-4 py-3 whitespace-nowrap">
                  <div className="flex items-center gap-1">
                    <a
                      href={link.shortUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      onClick={(e) => e.stopPropagation()}
                      className="text-primary-600 dark:text-primary-400 text-sm font-medium hover:underline flex items-center gap-0.5"
                    >
                      {link.shortKey}
                      <ExternalLink className="w-3 h-3 ml-0.5 opacity-60" />
                    </a>
                    <CopyButton text={link.shortUrl} />
                  </div>
                </td>

                {/* Original URL */}
                <td className="px-4 py-3 max-w-xs">
                  <span
                    title={link.longUrl}
                    className="text-sm text-gray-600 dark:text-gray-300 block truncate"
                  >
                    {truncate(link.longUrl, 50)}
                  </span>
                </td>

                {/* Created At */}
                <td className="px-4 py-3 whitespace-nowrap text-sm text-gray-500 dark:text-gray-400">
                  {formatDate(link.createdAt)}
                </td>

                {/* Expires At */}
                <td className="px-4 py-3 whitespace-nowrap text-sm text-gray-500 dark:text-gray-400">
                  {link.expiresAt ? formatDate(link.expiresAt) : '—'}
                </td>

                {/* Clicks */}
                <td className="px-4 py-3 whitespace-nowrap text-sm text-gray-700 dark:text-gray-300 font-medium">
                  {link.totalClicks !== undefined ? link.totalClicks.toLocaleString() : '—'}
                </td>

                {/* Actions */}
                <td className="px-4 py-3 whitespace-nowrap">
                  <button
                    onClick={(e) => handleDelete(e, link.shortKey)}
                    disabled={isDeleting}
                    aria-label="Delete link"
                    title="Delete"
                    className="p-1.5 rounded text-gray-400 hover:text-red-500 dark:hover:text-red-400 transition-colors disabled:opacity-40"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}
