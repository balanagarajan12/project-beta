# CLAUDE.md

Handoff for Claude and other coding agents working in `etp-foundation`. Read this file first, then the root documents it names. Inspect the code before changing it. These instructions outrank older copies that used to live under `etp-platform-service/`.

---

## 1. Where the ETP documents are

The required platform documents exist **only** at the repository root:

| File | Use it for |
|------|------------|
| [ETP_CONCEPTUAL.md](ETP_CONCEPTUAL.md) | Plain-language introduction: purpose, capabilities, and what ETP is not. Read this before the architecture |
| [ETP-ARCHITECTURE.md](ETP-ARCHITECTURE.md) | Target architecture. The section **Current implementation in this repository** is what the code does today |
| [ETP_ENGINEERING_MODEL.md](ETP_ENGINEERING_MODEL.md) | Technology stack, core and application mental models, logical components, repository structure, guardrails |
| [ETP_CURRENT_DATA_MODEL.md](ETP_CURRENT_DATA_MODEL.md) | Tables that Flyway actually creates |
| [ETP_APIS.md](ETP_APIS.md) | HTTP endpoints |
| [ETP_DEPLOYMENT.md](ETP_DEPLOYMENT.md) | Dedicated / shared / capability-level deployment and supporting infrastructure |
| [ETP_CAPABILITY_DEFINITION.md](ETP_CAPABILITY_DEFINITION.md) | Capability contract, lifecycle, definition of done |
| [ETP_HOW_TO.md](ETP_HOW_TO.md) | Local configuration steps |

Do not recreate these files under `etp-platform-service/`, `etp-core-ui/`, or `capabilities/`. When architecture, schema, or API behavior changes, update the root file.

Module files that stay next to the code:

- `etp-platform-service/README.md` — how to build and run the platform service
- `etp-platform-service/docs/ETP_CORE_UI_DESIGN_GUIDELINES.md` — Core UI visual system
- `etp-core-ui/README.md` — how to run the console
- `capabilities/*/README.md` and `capabilities/*/ETP_*_DEPLOYMENT.md` — that capability’s runbook
- `capabilities/etp-migration/docs/` — migration request bodies, control-plane columns, and the local UAM demonstration
- `capabilities/etp-iam/README.md` — realm setup and IAM API detail

`capabilities/etp-migration/docs/CLAUDE_CONTEXT.md` is the migration-module handoff. It does not replace this file.

---

## 2. What this repository is

ETP is a capability platform. Business applications consume capabilities. They do not own platform infrastructure, and a capability is not a business application.

Implemented today:

- `etp-platform-service` — Capability Registry on `/api/v1/capabilities`, health, platform info. Database `etp_platform`. Port 8080. Flyway V1–V14.
- `etp-core-ui` — React 19 console. Port 5173. Login plus read-only capability list and details (`GET` only). Create, edit, and lifecycle controls are disabled. Dashboard, customers, applications, configuration, version, and audit are placeholders with no API.
- `capabilities/etp-iam` — `POST /api/v1/auth/login`, `POST /api/v1/auth/token` (`client_credentials` only), `POST /api/v1/authorization/check`. Database `etp_iam`. Port 8081. Keycloak realm `etp` on port 8180.
- `capabilities/etp-migration` — configure, activate, execute to staging, reconcile, promote. Database `etp_migration`. Port 8082. Flyway V1–V5 owns the control plane only.
- `capabilities/etp-exports` — `POST /api/v1/exports`. Port 8095. No database.
- `capabilities/postgresql`, `kafka`, `redis` — infrastructure contracts and Compose. Kafka and Redis are not called by the Java services. Those three capabilities are not registered yet (their READMEs).

Not implemented: Application Registry APIs, application composer, Customer UI, API gateway, API catalog, platform JWT enforcement, entitlement services (V14 tables only), OIDC Authorization Code + PKCE, `/auth/me`, `/auth/refresh`.

UAM in this repo is a local schema used to demonstrate migration promotion. Do not describe it as a production product.

---

## 3. Rules that are easy to break

1. **Capability ≠ business application.** Do not put domain rules in `etp-platform-service` or in a generic capability.
2. **Three databases.** Platform code uses `etp_platform`. IAM uses `etp_iam`. Migration control plane uses `etp_migration`. Do not point one service at another service’s database.
3. **Flyway owns the schema.** Hibernate `ddl-auto` is `validate`. Add a migration instead of changing tables from JPA.
4. **IAM schema comes from Flyway** `V1__iam_schema.sql` and `V2__platform_dev_seed.sql`. Do not treat `schema_updated.sql` as the startup path.
5. **Registry lifecycle.** No DELETE. One ACTIVE version per capability. ACTIVE version content is immutable. Invalid transitions are HTTP 409 `INVALID_LIFECYCLE_TRANSITION`.
6. **Human login and service tokens are different endpoints.** Core UI uses `/api/v1/auth/login` only. It stores the access token and sends it to the platform, but `SecurityConfig` does not validate JWTs. Do not describe the registry as protected.
7. **Secrets stay in `.env` or a secret manager.** Do not commit them and do not add them to capability contract JSON or application-definition JSON.
8. **V14 entitlement tables have no Java API.** Do not document or call entitlement workflows as if they exist.
9. **Customer, application, and application-definition tables have entities and no REST API.** Do not invent controllers for them unless the task asks for that feature.
10. **Migration does not promote inside execute.** Execute loads staging. Promotion is a separate POST after reconciliation.
11. **SFTP configuration uses `credentialRef`.** Do not add password fields to the source JSON.
12. **Core UI design.** Follow `ETP_CORE_UI_DESIGN_GUIDELINES.md`. Do not add a second component library. Do not turn a disabled catalog action into a live POST or PATCH unless the task asks for that. Do not add entitlement or operating-model navigation; the shell comment forbids it, and those tables have no API.

---

## 4. Local ports

| Port | Process |
|------|---------|
| 5173 | Core UI |
| 5434 | `etp_platform` |
| 55432 | `etp_iam` (only if `.env` sets `ETP_IAM_DB_PORT`; Compose fallback without it is 55445) |
| 55433 | `etp_migration` (Compose fallback without `.env` is 55446) |
| 8080 | Platform API |
| 8081 | IAM API |
| 8082 | Migration API |
| 8095 | Exports |
| 8180 | Keycloak |
| 56379 | Redis |
| 59092 | Kafka |

Steps: [ETP_HOW_TO.md](ETP_HOW_TO.md).

---

## 5. When you change behavior

Update the root document that owns the fact, in the same change:

- what ETP is, or who it is for → `ETP_CONCEPTUAL.md`
- new or removed endpoint → `ETP_APIS.md`
- new table or column → `ETP_CURRENT_DATA_MODEL.md`
- lifecycle or contract rule → `ETP_CAPABILITY_DEFINITION.md`
- port, Compose service, or deployment rule → `ETP_DEPLOYMENT.md` and, if the workstation steps change, `ETP_HOW_TO.md`
- implemented versus target → the **Current implementation** section of `ETP-ARCHITECTURE.md`
- stack, mental model, repository layout, or a guardrail → `ETP_ENGINEERING_MODEL.md`
- a rule an agent would violate → this file

Leave capability runbooks in the module folder, and point them at the root document instead of pasting the platform architecture again.
