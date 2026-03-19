export interface AuthProfile {
  sub: string
  email?: string
  name?: string
  picture?: string
}

export interface AuthSession {
  credential: string
  profile: AuthProfile
}

const STORAGE_KEY = 'url-shortener.auth-session'

const fallbackStorage = new Map<string, string>()

function decodeBase64Url(value: string): string {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
  return atob(padded)
}

export function decodeJwtPayload(token: string): Record<string, unknown> {
  const [, payload] = token.split('.')
  if (!payload) {
    throw new Error('Invalid JWT payload')
  }
  return JSON.parse(decodeBase64Url(payload)) as Record<string, unknown>
}

export function buildSessionFromCredential(credential: string): AuthSession {
  const payload = decodeJwtPayload(credential)
  const sub = String(payload.sub ?? '')
  if (!sub) {
    throw new Error('Google credential is missing the subject claim')
  }

  return {
    credential,
    profile: {
      sub,
      email: typeof payload.email === 'string' ? payload.email : undefined,
      name: typeof payload.name === 'string' ? payload.name : undefined,
      picture: typeof payload.picture === 'string' ? payload.picture : undefined,
    },
  }
}

export function readStoredSession(): AuthSession | null {
  const storage = getStorage()
  if (!storage) return null

  const raw = storage.getItem(STORAGE_KEY)
  if (!raw) return null

  try {
    const parsed = JSON.parse(raw) as AuthSession
    if (!parsed.credential || !parsed.profile?.sub) {
      return null
    }
    return parsed
  } catch {
    return null
  }
}

export function writeStoredSession(session: AuthSession) {
  const storage = getStorage()
  if (!storage) return
  storage.setItem(STORAGE_KEY, JSON.stringify(session))
}

export function clearStoredSession() {
  const storage = getStorage()
  if (!storage) return
  storage.removeItem(STORAGE_KEY)
}

export function getAuthToken(): string | null {
  return readStoredSession()?.credential ?? null
}

function getStorage(): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null {
  if (typeof window !== 'undefined' && typeof window.localStorage?.getItem === 'function') {
    return window.localStorage
  }

  return {
    getItem: (key: string) => fallbackStorage.get(key) ?? null,
    setItem: (key: string, value: string) => {
      fallbackStorage.set(key, value)
    },
    removeItem: (key: string) => {
      fallbackStorage.delete(key)
    },
  }
}
