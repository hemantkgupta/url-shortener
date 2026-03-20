/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly URL_SHORTENER_AUTH_GOOGLE_CLIENT_ID?: string
  readonly URL_SHORTENER_FRONTEND_BASE_PATH?: string
  readonly VITE_GOOGLE_CLIENT_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface GoogleCredentialResponse {
  credential?: string
}

interface GoogleAccountsId {
  initialize(options: {
    client_id: string
    callback: (response: GoogleCredentialResponse) => void
  }): void
  renderButton(
    parent: HTMLElement,
    options: Record<string, string | number | boolean>,
  ): void
  disableAutoSelect(): void
}

interface Window {
  google?: {
    accounts: {
      id: GoogleAccountsId
    }
  }
}
