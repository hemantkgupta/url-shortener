import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

loadLocalDevEnv()

const frontendPort = getPort('URL_SHORTENER_FRONTEND_PORT', 13000)
const writeServicePort = getPort('URL_SHORTENER_WRITE_SERVICE_PORT', 18082)
const analyticsServicePort = getPort('URL_SHORTENER_ANALYTICS_SERVICE_PORT', 18083)
const redirectServicePort = getPort('URL_SHORTENER_REDIRECT_SERVICE_PORT', 18080)

export default defineConfig({
  plugins: [react()],
  envPrefix: ['VITE_', 'URL_SHORTENER_'],
  server: {
    port: frontendPort,
    proxy: {
      '/api/write': {
        target: `http://localhost:${writeServicePort}`,
        rewrite: (path) => path.replace(/^\/api\/write/, ''),
      },
      '/api/analytics': {
        target: `http://localhost:${analyticsServicePort}`,
        rewrite: (path) => path.replace(/^\/api\/analytics/, ''),
      },
      '/r': {
        target: `http://localhost:${redirectServicePort}`,
        rewrite: (path) => path.replace(/^\/r/, ''),
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})

function loadLocalDevEnv() {
  const envFile = path.join(os.homedir(), '.url-shortener-local-dev')
  if (!fs.existsSync(envFile)) {
    return
  }

  const lines = fs.readFileSync(envFile, 'utf8').split(/\r?\n/)
  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) {
      continue
    }

    const separator = line.indexOf('=')
    if (separator <= 0) {
      continue
    }

    const key = line.slice(0, separator).trim()
    if (!key || process.env[key] !== undefined) {
      continue
    }

    const value = line.slice(separator + 1).trim()
    process.env[key] = stripWrappingQuotes(value)
  }
}

function stripWrappingQuotes(value: string) {
  if (
    (value.startsWith('"') && value.endsWith('"')) ||
    (value.startsWith("'") && value.endsWith("'"))
  ) {
    return value.slice(1, -1)
  }
  return value
}

function getPort(key: string, fallback: number) {
  const rawValue = process.env[key]
  if (!rawValue) {
    return fallback
  }

  const parsed = Number.parseInt(rawValue, 10)
  return Number.isFinite(parsed) ? parsed : fallback
}
