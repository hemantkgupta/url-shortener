import { afterEach, describe, expect, it } from 'vitest'
import {
  buildSessionFromCredential,
  clearStoredSession,
  getAuthToken,
  writeStoredSession,
} from './session'

function createJwt(payload: Record<string, unknown>): string {
  const header = btoa(JSON.stringify({ alg: 'none', typ: 'JWT' }))
  const body = btoa(JSON.stringify(payload))
  return `${header}.${body}.signature`
}

describe('auth session helpers', () => {
  afterEach(() => {
    clearStoredSession()
  })

  it('builds a session from a Google credential payload', () => {
    const credential = createJwt({
      sub: 'google-subject',
      email: 'user@example.com',
      name: 'Test User',
    })

    const session = buildSessionFromCredential(credential)

    expect(session.profile.sub).toBe('google-subject')
    expect(session.profile.email).toBe('user@example.com')
    expect(session.profile.name).toBe('Test User')
  })

  it('persists and reads the bearer token', () => {
    const credential = createJwt({ sub: 'google-subject' })
    const session = buildSessionFromCredential(credential)

    writeStoredSession(session)

    expect(getAuthToken()).toBe(credential)
  })
})
