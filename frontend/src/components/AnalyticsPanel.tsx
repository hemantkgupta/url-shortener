import { Loader2, TrendingUp, Globe, Monitor } from 'lucide-react'
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  BarChart,
  Bar,
  CartesianGrid,
} from 'recharts'
import { useAnalytics } from '../api/urls'

interface AnalyticsPanelProps {
  shortKey: string
}

function Section({ title, icon }: { title: string; icon: React.ReactNode }) {
  return (
    <div className="flex items-center gap-2 mb-3">
      <span className="text-primary-600 dark:text-primary-400">{icon}</span>
      <h4 className="text-sm font-semibold text-gray-700 dark:text-gray-300">{title}</h4>
    </div>
  )
}

export default function AnalyticsPanel({ shortKey }: AnalyticsPanelProps) {
  const { data, isLoading, error } = useAnalytics(shortKey, true)

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12 gap-2 text-gray-400">
        <Loader2 className="w-5 h-5 animate-spin" />
        <span className="text-sm">Loading analytics…</span>
      </div>
    )
  }

  if (error) {
    return (
      <p className="text-sm text-red-500 dark:text-red-400 text-center py-8">
        Failed to load analytics: {(error as { message?: string }).message}
      </p>
    )
  }

  if (!data || data.totalClicks === 0) {
    return (
      <p className="text-sm text-gray-400 dark:text-gray-500 text-center py-10">
        No analytics data yet for this link.
      </p>
    )
  }

  // Clicks per day — sorted chronologically
  const clicksByDay = Object.entries(data.clicksByDay)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, count]) => ({ date: date.slice(5), count })) // "MM-DD"

  // Top 5 by device
  const deviceData = Object.entries(data.clicksByDevice)
    .sort(([, a], [, b]) => b - a)
    .slice(0, 5)
    .map(([name, value]) => ({ name, value }))

  // Top 5 by country
  const countryData = Object.entries(data.clicksByCountry)
    .sort(([, a], [, b]) => b - a)
    .slice(0, 5)
    .map(([name, value]) => ({ name, value }))

  return (
    <div className="space-y-6">
      {/* Total clicks */}
      <div className="flex items-center gap-4">
        <div className="bg-primary-50 dark:bg-primary-900/30 rounded-xl px-6 py-4 inline-flex flex-col items-center">
          <span className="text-4xl font-bold text-primary-700 dark:text-primary-300">
            {data.totalClicks.toLocaleString()}
          </span>
          <span className="text-xs text-gray-500 dark:text-gray-400 mt-1 uppercase tracking-wide">
            Total Clicks
          </span>
        </div>
        <div className="text-xs text-gray-400 dark:text-gray-500">
          <p>Period: {data.periodFrom} → {data.periodTo}</p>
        </div>
      </div>

      {/* Clicks per day line chart */}
      {clicksByDay.length > 0 && (
        <div>
          <Section title="Clicks per day (last 30 days)" icon={<TrendingUp className="w-4 h-4" />} />
          <ResponsiveContainer width="100%" height={200}>
            <LineChart data={clicksByDay}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
              <XAxis
                dataKey="date"
                tick={{ fontSize: 11 }}
                tickLine={false}
                axisLine={false}
              />
              <YAxis
                allowDecimals={false}
                tick={{ fontSize: 11 }}
                tickLine={false}
                axisLine={false}
                width={32}
              />
              <Tooltip
                contentStyle={{
                  borderRadius: '8px',
                  border: 'none',
                  boxShadow: '0 4px 12px rgba(0,0,0,0.1)',
                  fontSize: '12px',
                }}
              />
              <Line
                type="monotone"
                dataKey="count"
                stroke="#6366f1"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4, fill: '#6366f1' }}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* Device + Country charts */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
        {/* By device */}
        {deviceData.length > 0 && (
          <div>
            <Section title="By device" icon={<Monitor className="w-4 h-4" />} />
            <ResponsiveContainer width="100%" height={160}>
              <BarChart data={deviceData} layout="vertical">
                <XAxis type="number" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                <YAxis
                  type="category"
                  dataKey="name"
                  tick={{ fontSize: 11 }}
                  tickLine={false}
                  axisLine={false}
                  width={64}
                />
                <Tooltip
                  contentStyle={{
                    borderRadius: '8px',
                    border: 'none',
                    boxShadow: '0 4px 12px rgba(0,0,0,0.1)',
                    fontSize: '12px',
                  }}
                />
                <Bar dataKey="value" fill="#818cf8" radius={[0, 4, 4, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}

        {/* By country */}
        {countryData.length > 0 && (
          <div>
            <Section title="By country (top 5)" icon={<Globe className="w-4 h-4" />} />
            <ResponsiveContainer width="100%" height={160}>
              <BarChart data={countryData} layout="vertical">
                <XAxis type="number" tick={{ fontSize: 11 }} tickLine={false} axisLine={false} />
                <YAxis
                  type="category"
                  dataKey="name"
                  tick={{ fontSize: 11 }}
                  tickLine={false}
                  axisLine={false}
                  width={64}
                />
                <Tooltip
                  contentStyle={{
                    borderRadius: '8px',
                    border: 'none',
                    boxShadow: '0 4px 12px rgba(0,0,0,0.1)',
                    fontSize: '12px',
                  }}
                />
                <Bar dataKey="value" fill="#a5b4fc" radius={[0, 4, 4, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>
    </div>
  )
}
