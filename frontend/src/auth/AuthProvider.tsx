import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import {
  AuthSession,
  buildSessionFromCredential,
  clearStoredSession,
  readStoredSession,
  writeStoredSession,
} from './session'

interface AuthContextValue {
  clientId: string
  googleReady: boolean
  session: AuthSession | null
  isAuthenticated: boolean
  saveCredential: (credential: string) => void
  signOut: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<AuthSession | null>(() => readStoredSession())
  const [googleReady, setGoogleReady] = useState<boolean>(() => Boolean(window.google?.accounts?.id))
  const clientId = import.meta.env.URL_SHORTENER_AUTH_GOOGLE_CLIENT_ID
    ?? import.meta.env.VITE_GOOGLE_CLIENT_ID
    ?? ''

  useEffect(() => {
    if (window.google?.accounts?.id) {
      setGoogleReady(true)
      return
    }

    const timer = window.setInterval(() => {
      if (window.google?.accounts?.id) {
        setGoogleReady(true)
        window.clearInterval(timer)
      }
    }, 250)

    return () => window.clearInterval(timer)
  }, [])

  const saveCredential = useCallback((credential: string) => {
    const nextSession = buildSessionFromCredential(credential)
    writeStoredSession(nextSession)
    setSession(nextSession)
  }, [])

  const signOut = useCallback(() => {
    clearStoredSession()
    window.google?.accounts.id.disableAutoSelect()
    setSession(null)
  }, [])

  const value = useMemo<AuthContextValue>(() => ({
    clientId,
    googleReady,
    session,
    isAuthenticated: session !== null,
    saveCredential,
    signOut,
  }), [clientId, googleReady, saveCredential, session, signOut])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return context
}
