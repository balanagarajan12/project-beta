/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_IAM_BASE_URL: string;
  readonly VITE_MOCK_AUTH_ENABLED?: string;
  readonly VITE_MOCK_AUTH_USERNAME?: string;
  readonly VITE_MOCK_AUTH_PASSWORD?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
