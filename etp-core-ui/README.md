# ETP Core UI

**Internal ETP platform console** for the Enterprise Technology Platform (ETP). Primary users: ETP technical team, platform administrators, and engineering/operations users.

ETP Core UI is **not** the customer-facing business application platform and **not** ETP Customer UI. It manages platform metadata such as the Capability Registry, Application Registry (future), composition workflows (future), and platform administration.

## Design system

All visual and interaction standards are defined in:

[ETP Core UI Design Guidelines](../etp-platform-service/docs/ETP_CORE_UI_DESIGN_GUIDELINES.md)

Do not introduce ad hoc colors, fonts, or spacing outside the ETP design tokens.

## Technology stack

| Technology | Role |
|------------|------|
| React 19 | UI framework |
| TypeScript | Type-safe development |
| Vite | Build tool and dev server |
| React Router | Client-side routing |
| Lucide | Icon library |
| CSS custom properties | Design tokens and theme |

No third-party UI component framework (Material UI, Ant Design, etc.) — the ETP design system is implemented in this repository.

## Authentication (Phase 1 — implemented)

Phase 1 uses **one login screen** on the approved ETP Core UI login page (username + password). There is **no** second Keycloak-hosted login screen. Core UI does **not** call Keycloak directly.

```
ETP Core UI (Sign In)
      ↓  POST /api/v1/auth/login
ETP IAM (capabilities/etp-iam)
      ↓  server-side Keycloak Direct Access Grant
Keycloak
      ↓  JWT access token
ETP IAM → Core UI (AuthSession) → /dashboard
```

| Step | Focus | Status |
|------|--------|--------|
| **1A** | Auth contracts, session/storage, `AuthProvider`, Remember Me, logout | Complete |
| **1B** | Route protection (`RequireAuth`, `GuestOnly`), `returnTo`, post-login redirect | Complete |
| **1C Phase 1** | Real username/password via ETP IAM | Implemented |

**Not implemented (Phase 2+):** SSO, OIDC Authorization Code + PKCE, cross-application SSO, Customer UI auth, business application auth, MFA, federation, token refresh, Keycloak end-session logout.

Detail: [ETP Architecture §8](../etp-platform-service/ARCHITECTURE.md), [ETP IAM README](../capabilities/etp-iam/README.md).

## Project structure

```
etp-core-ui/
├── public/assets/          # Static assets (approved ETP logo, login reference)
├── src/
│   ├── app/                # App shell, router configuration
│   ├── assets/             # Bundled assets
│   ├── components/ui/      # Reusable ETP design system components
│   ├── config/             # Environment configuration
│   ├── features/           # Feature modules (auth, dashboard, capabilities, …)
│   ├── layouts/            # Shared layouts (Core Shell — future)
│   ├── lib/                # Shared utilities
│   ├── services/           # API client foundation
│   ├── styles/             # Tokens, theme, typography
│   ├── types/              # Shared TypeScript types
│   └── main.tsx
├── .env.example
├── package.json
├── vite.config.ts
└── README.md
```

| Backend | Role |
|---------|------|
| `etp-platform-service/` | Platform REST API (capability registry, etc.) |
| `capabilities/etp-iam/` | IAM capability — human login and authorization |

## Install

```bash
cd etp-core-ui
npm install
```

## Environment configuration

Copy `.env.example` to `.env.local` (or set variables in your environment):

```bash
cp .env.example .env.local
```

| Variable | Description |
|----------|-------------|
| `VITE_API_BASE_URL` | Base URL for `etp-platform-service` REST API (no trailing slash). Example: `http://localhost:8080` |
| `VITE_IAM_BASE_URL` | Base URL for ETP IAM capability (no trailing slash). Example: `http://localhost:8081` |
| `VITE_MOCK_AUTH_ENABLED` | `true` = optional dev mock login; `false` = real IAM `POST /api/v1/auth/login` |
| `VITE_MOCK_AUTH_USERNAME` | Mock username (only when mock enabled) |
| `VITE_MOCK_AUTH_PASSWORD` | Mock password (only when mock enabled) |

The API base URL is **not** hard-coded in source. `getApiBaseUrl()` throws if `VITE_API_BASE_URL` is missing when the platform API client is used.

## Run locally

```bash
npm run dev
```

Default Vite dev server: `http://localhost:5173`

Routes (Phase 1):

- `/login` — approved login UI; guest-only; username/password authentication via ETP IAM
- `/dashboard` — protected; requires authenticated session

For end-to-end login, ETP IAM and Keycloak must be running with a configured realm user and matching IAM `principals` row. See [capabilities/etp-iam/README.md](../capabilities/etp-iam/README.md).

## Build

```bash
npm run build
```

Production output: `dist/`

## Lint

```bash
npm run lint
```

## Current scope

**Implemented:**

- ETP design tokens and theme
- Reusable `ETP*` UI components
- Approved login screen (visual baseline frozen)
- Phase 1 authentication (1A–1C Phase 1)
- Route protection and session handling
- API client with Bearer token from session

**Not included yet:** Core Shell, Capability Registry screens, Customer UI, business application UIs, SSO, OIDC/PKCE.

## Related documentation

- [ETP Core UI Design Guidelines](../etp-platform-service/docs/ETP_CORE_UI_DESIGN_GUIDELINES.md) — design system, authentication phases
- [ETP Architecture](../etp-platform-service/ARCHITECTURE.md) — IAM as capability, Phase 1 auth, deployment principle
- [ETP IAM README](../capabilities/etp-iam/README.md) — IAM APIs, identity model, Keycloak setup
- [Capability Registry API](../etp-platform-service/docs/CAPABILITY_REGISTRY.md)
- [API Conventions](../etp-platform-service/docs/API_CONVENTIONS.md)
