import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet } from 'react-router-dom'
import { Moon, Sun, Scissors } from 'lucide-react'
import clsx from 'clsx'
import { useAuth } from '../auth/AuthProvider'
import GoogleSignInButton from '../auth/GoogleSignInButton'

function useDarkMode() {
  const [dark, setDark] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false
    const stored = localStorage.getItem('theme')
    if (stored) return stored === 'dark'
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  })

  useEffect(() => {
    const root = document.documentElement
    if (dark) {
      root.classList.add('dark')
      localStorage.setItem('theme', 'dark')
    } else {
      root.classList.remove('dark')
      localStorage.setItem('theme', 'light')
    }
  }, [dark])

  return [dark, setDark] as const
}

export default function Layout() {
  const [dark, setDark] = useDarkMode()
  const { isAuthenticated, session, signOut } = useAuth()

  return (
    <div className="min-h-screen flex flex-col bg-gray-50 dark:bg-gray-900 transition-colors duration-200">
      {/* Navigation */}
      <nav className="sticky top-0 z-50 border-b border-gray-200 dark:border-gray-700 bg-white/80 dark:bg-gray-800/80 backdrop-blur-sm">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex items-center justify-between h-16">
            {/* Logo */}
            <Link
              to="/"
              className="flex items-center gap-2 text-primary-600 dark:text-primary-400 font-bold text-xl hover:opacity-80 transition-opacity"
            >
              <Scissors className="w-5 h-5" />
              <span>snip.ly</span>
            </Link>

            {/* Nav links + toggle */}
            <div className="flex items-center gap-1 sm:gap-2">
              <NavLink
                to="/"
                end
                className={({ isActive }) =>
                  clsx(
                    'px-3 py-2 rounded-md text-sm font-medium transition-colors',
                    isActive
                      ? 'bg-primary-100 dark:bg-primary-900/40 text-primary-700 dark:text-primary-300'
                      : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700',
                  )
                }
              >
                Home
              </NavLink>
              <NavLink
                to="/dashboard"
                className={({ isActive }) =>
                  clsx(
                    'px-3 py-2 rounded-md text-sm font-medium transition-colors',
                    isActive
                      ? 'bg-primary-100 dark:bg-primary-900/40 text-primary-700 dark:text-primary-300'
                      : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700',
                  )
                }
              >
                Dashboard
              </NavLink>

              {isAuthenticated ? (
                <div className="hidden sm:flex items-center gap-2 ml-3 pl-3 border-l border-gray-200 dark:border-gray-700">
                  <div className="text-right">
                    <p className="text-xs font-medium text-gray-700 dark:text-gray-200">
                      {session?.profile.name ?? 'Signed in'}
                    </p>
                    <p className="text-[11px] text-gray-500 dark:text-gray-400">
                      {session?.profile.email ?? 'Google'}
                    </p>
                  </div>
                  <button
                    onClick={signOut}
                    className="px-3 py-2 rounded-md text-sm font-medium text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
                  >
                    Sign out
                  </button>
                </div>
              ) : (
                <div className="hidden sm:block ml-3">
                  <GoogleSignInButton />
                </div>
              )}

              {/* Dark mode toggle */}
              <button
                onClick={() => setDark((d) => !d)}
                aria-label="Toggle dark mode"
                className="ml-2 p-2 rounded-md text-gray-500 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
              >
                {dark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
              </button>
            </div>
          </div>
        </div>
      </nav>

      {/* Page content */}
      <main className="flex-1">
        <Outlet />
      </main>

      {/* Footer */}
      <footer className="border-t border-gray-200 dark:border-gray-700 py-6 text-center text-xs text-gray-400 dark:text-gray-500 space-y-2">
        {!isAuthenticated ? (
          <div className="flex justify-center">
            <GoogleSignInButton label="Sign in to manage, delete, and analyze the links you create." />
          </div>
        ) : null}
        &copy; {new Date().getFullYear()} snip.ly — Built with React &amp; Vite
      </footer>
    </div>
  )
}
