import { useState } from 'react'
import { Scissors } from 'lucide-react'
import ShortenForm from '../components/ShortenForm'
import ResultCard from '../components/ResultCard'
import type { ShortenResponse } from '../types/api'

export default function HomePage() {
  const [result, setResult] = useState<ShortenResponse | null>(null)

  return (
    <div className="flex flex-col items-center justify-center px-4 py-16 sm:py-24">
      <div className="w-full max-w-xl">
        {/* Header */}
        <div className="text-center mb-10">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-primary-100 dark:bg-primary-900/40 mb-5">
            <Scissors className="w-7 h-7 text-primary-600 dark:text-primary-400" />
          </div>
          <h1 className="text-3xl sm:text-4xl font-bold text-gray-900 dark:text-gray-100 tracking-tight">
            Shorten your URL
          </h1>
          <p className="mt-3 text-base text-gray-500 dark:text-gray-400">
            Paste a long URL and get a short link instantly
          </p>
        </div>

        {/* Form card */}
        <div className="bg-white dark:bg-gray-800 rounded-2xl border border-gray-200 dark:border-gray-700 shadow-sm p-6 sm:p-8">
          <ShortenForm onSuccess={setResult} />
        </div>

        {/* Result */}
        {result && (
          <div className="mt-6">
            <ResultCard result={result} />
          </div>
        )}
      </div>
    </div>
  )
}
