# ETP Core UI

**Internal ETP platform console** for the Enterprise Technology Platform (ETP). This is the UI used by the ETP platform team — not customer-facing business applications and not the ETP Customer UI.

ETP Core UI manages platform metadata such as the Capability Registry, Application Registry (future), composition workflows (future), and platform administration.

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

## Project structure

```
etp-core-ui/
├── public/assets/          # Static assets (approved ETP logo — added later)
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

Backend API: sibling project `etp-platform-service/` (not modified by this UI).

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

The API base URL is **not** hard-coded in source. `getApiBaseUrl()` throws if the variable is missing when the API client is used.

## Run locally

```bash
npm run dev
```

Default Vite dev server: `http://localhost:5173`

Placeholder routes:

- `/login` — routing placeholder (login UI not implemented yet)
- `/dashboard` — routing placeholder (Core Shell not implemented yet)

## Build

```bash
npm run build
```

Production output: `dist/`

## Lint

```bash
npm run lint
```

## Week 2 Step 2 scope

This foundation includes:

- ETP design tokens and theme
- Reusable `ETP*` UI components
- React Router placeholders
- API client abstraction
- Environment configuration

**Not included yet:** Login screen, Core Shell, Capability Registry screens, IAM integration, Customer UI, or Business Application UIs.

## Related documentation

- [ETP Core UI Design Guidelines](../etp-platform-service/docs/ETP_CORE_UI_DESIGN_GUIDELINES.md)
- [Capability Registry API](../etp-platform-service/docs/CAPABILITY_REGISTRY.md)
- [API Conventions](../etp-platform-service/docs/API_CONVENTIONS.md)
