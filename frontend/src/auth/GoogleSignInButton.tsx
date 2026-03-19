import { useEffect, useRef } from 'react'
import { useAuth } from './AuthProvider'

interface GoogleSignInButtonProps {
  label?: string
}

export default function GoogleSignInButton({ label }: GoogleSignInButtonProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const { clientId, googleReady, isAuthenticated, saveCredential } = useAuth()

  useEffect(() => {
    const container = containerRef.current
    if (!container || !googleReady || !clientId || isAuthenticated) {
      return
    }

    const googleAccounts = window.google?.accounts?.id
    if (!googleAccounts) {
      return
    }

    container.innerHTML = ''
    googleAccounts.initialize({
      client_id: clientId,
      callback: (response) => {
        if (response.credential) {
          saveCredential(response.credential)
        }
      },
    })
    googleAccounts.renderButton(container, {
      theme: 'outline',
      size: 'large',
      shape: 'pill',
      text: 'signin_with',
      width: 220,
    })
  }, [clientId, googleReady, isAuthenticated, saveCredential])

  if (isAuthenticated) {
    return null
  }

  if (!clientId) {
    return (
      <p className="text-sm text-amber-600 dark:text-amber-300">
        Google sign-in is not configured. Set `URL_SHORTENER_AUTH_GOOGLE_CLIENT_ID` to enable protected actions.
      </p>
    )
  }

  return (
    <div className="space-y-2">
      {label ? <p className="text-sm text-gray-500 dark:text-gray-400">{label}</p> : null}
      <div ref={containerRef} />
    </div>
  )
}
