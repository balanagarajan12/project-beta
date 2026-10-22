# ETP How To — local configuration

Step-by-step configuration for a workstation. Commands assume PowerShell and this repository at `C:\Users\balan\etp-foundation`. Replace `change_me` passwords before you start. Do not commit `.env` files.

Canonical references: [ETP_DEPLOYMENT.md](ETP_DEPLOYMENT.md), [ETP_APIS.md](ETP_APIS.md).

Each module is configured in its own directory. Copy `.env.example` to `.env` in that directory before Compose. IAM and migration Compose files publish a different database port when the variable is missing (55445 and 55446). The steps below use the `.env.example` ports, which match the JDBC defaults.

---

## 1. Platform database and API

Directory: `etp-platform-service`.

1. Copy the environment file and set a password.

```powershell
cd C:\Users\balan\etp-foundation\etp-platform-service
copy .env.example .env
```

2. In `.env`, set `ETP_DB_PASSWORD`. Leave the other values unless a port is already taken.

| Variable | Value in `.env.example` |
|----------|-------------------------|
| `ETP_DB_PASSWORD` | required |
| `ETP_DB_PORT` | `5434` |
| `ETP_DB_URL` | `jdbc:postgresql://localhost:5434/etp_platform` |
| `ETP_DB_USERNAME` | `etp_admin` |

3. Start PostgreSQL 17.

```powershell
docker compose up -d
docker compose ps
```

4. Start the platform service. Flyway applies V1–V14 on startup.

```powershell
mvn spring-boot:run
```

Default HTTP port is 8080 (`ETP_SERVER_PORT`).

5. Check it.

```powershell
curl http://localhost:8080/api/v1/health
curl http://localhost:8080/api/v1/platform/info
```

Swagger UI: `http://localhost:8080/api/v1/swagger-ui`.

A profile named `local` starts with in-memory H2 and Flyway disabled. Use it only when you do not need the PostgreSQL schema:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

---

## 2. IAM database, Keycloak, and IAM API

Directory: `capabilities\etp-iam`.

IAM is required for Core UI login. It uses its own database, `etp_iam`, not `etp_platform`.

1. Copy the environment file.

```powershell
cd C:\Users\balan\etp-foundation\capabilities\etp-iam
copy .env.example .env
```

2. Set the secrets in `.env`.

| Variable | Example purpose |
|----------|-----------------|
| `ETP_IAM_DB_PASSWORD` | PostgreSQL password |
| `ETP_IAM_DB_PORT` | `55432` (keep this; it matches `ETP_IAM_DB_URL`) |
| `ETP_IAM_DB_URL` | `jdbc:postgresql://localhost:55432/etp_iam` |
| `ETP_IAM_KEYCLOAK_PORT` | `8180` |
| `ETP_IAM_KEYCLOAK_ADMIN_USERNAME` | Keycloak bootstrap admin |
| `ETP_IAM_KEYCLOAK_ADMIN_PASSWORD` | Keycloak bootstrap admin password |
| `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_ID` | `etp-core-ui-login` |
| `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_SECRET` | Confidential client secret. Stays on the IAM server |
| `ETP_IAM_KEYCLOAK_ISSUER_URI` | `http://localhost:8180/realms/etp` |
| `ETP_IAM_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:5174` |
| `ETP_IAM_SERVER_PORT` | `8081` |

3. Start PostgreSQL, Keycloak, and pgAdmin.

```powershell
docker compose up -d
```

Keycloak in this Compose file runs `start-dev`. Realm `etp`, the login client, and a development user are not created by Compose itself. Create them in Keycloak (or the IAM module’s documented bootstrap) so that `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_ID` exists as a confidential client with Direct Access Grants enabled, and a user exists to sign in. Follow `capabilities/etp-iam/README.md` for the realm steps used by this module.

4. Start IAM. Flyway applies `V1__iam_schema.sql` and `V2__platform_dev_seed.sql`.

```powershell
mvn spring-boot:run
```

5. Check login only after the realm and user exist.

```powershell
curl -Method POST http://localhost:8081/api/v1/auth/login `
  -ContentType "application/json" `
  -Body '{"username":"<user>","password":"<password>"}'
```

Swagger UI: `http://localhost:8081/swagger-ui/index.html`.

Service-to-service tokens use `POST /api/v1/auth/token` with `grant_type` `client_credentials`. Do not use that endpoint for the Core UI login screen.

---

## 3. Core UI

Directory: `etp-core-ui`.

1. Install and copy the environment file.

```powershell
cd C:\Users\balan\etp-foundation\etp-core-ui
npm install
copy .env.example .env.local
```

2. Set the API bases in `.env.local`. No trailing slash.

```text
VITE_API_BASE_URL=http://localhost:8080
VITE_IAM_BASE_URL=http://localhost:8081
VITE_MOCK_AUTH_ENABLED=false
```

`VITE_MOCK_AUTH_ENABLED=true` skips real IAM. Leave it `false` when you are checking the login path.

3. Start the dev server.

```powershell
npm run dev
```

Open `http://localhost:5173/login`. Sign in with the Keycloak user. Remember Me keeps the session in `localStorage`; leaving it unchecked keeps it in `sessionStorage` until the tab closes. The next page is `/dashboard`, which is a placeholder.

Open `/capabilities` to read the registry. Search and filters run in the browser after one `GET /api/v1/capabilities`. Open a row for Overview, Contract, and Dependencies. Create, Edit, and the other write actions are on the page and do nothing. Customers, Applications, Configuration, Version, and Audit in the sidebar are the same kind of placeholder.

Platform service and IAM must both be running. The platform service allows origin `http://localhost:5173`. It does not check the Bearer token Core UI attaches.

---

## 4. Register a capability (API)

Core UI will not POST. Use the platform API. Example shape:

```powershell
curl -Method POST http://localhost:8080/api/v1/capabilities `
  -ContentType "application/json" `
  -Body '{"code":"etp.example","name":"Example","category":"PLATFORM","version":{"version":"1.0.0"},"contract":{"contractType":"COMPOSITE","contractVersion":"1.0","contractFormat":"JSON","document":{"functional":{}}}}'
```

The new capability and version are `DRAFT`. Move the version with PATCH:

```json
{
  "versionStatus": {
    "versionId": "<capability-version-uuid>",
    "status": "VALIDATED"
  }
}
```

Then PATCH again with `"status": "ACTIVE"`. The first activation also moves the capability to `ACTIVE`. Rules: [ETP_CAPABILITY_DEFINITION.md](ETP_CAPABILITY_DEFINITION.md).

PostgreSQL, Kafka, and Redis registration payloads, when you are ready to submit them, are the JSON files in `capabilities/postgresql`, `capabilities/kafka`, and `capabilities/redis`. Those READMEs say the capabilities are not registered yet. Read the payload before you POST it.

---

## 5. Migration (optional)

Directory: `capabilities\etp-migration`.

1. Copy `.env.example` to `.env` and set `ETP_MIGRATION_DB_PASSWORD`. Keep `ETP_MIGRATION_DB_PORT=55433`.

2. Start the database and the service.

```powershell
cd C:\Users\balan\etp-foundation\capabilities\etp-migration
docker compose up -d
mvn spring-boot:run
```

Default port is 8082. That Maven goal publishes Swagger UI at `http://localhost:8082/swagger-ui/index.html` without a JWT. Business APIs still require a JWT. The sample target URL in `.env.example` is the same database, schema `migration_staging`.

3. For local calls without a JWT, start with the `dev` profile. Outside `dev`, send `Authorization: Bearer <access token>` from IAM or Keycloak.

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

4. Configuration order:

1. `POST /api/v1/migration-configurations` (definition stays DRAFT).
2. `POST /api/v1/migrations/{id}/activate`.
3. `POST /api/v1/migrations/{id}/execute` returns 202 and a `runId` as soon as the run is stored. Poll `GET /api/v1/migration-runs/{runId}` until it finishes loading staging.
4. `POST /api/v1/migration-runs/{runId}/reconcile` returns 202. Poll `GET /api/v1/migration-runs/{runId}/reconciliation`.
5. `POST /api/v1/migration-runs/{runId}/promotion/prepare` returns 202. Poll `GET /api/v1/migration-runs/{runId}/promotion`.
6. `POST /api/v1/migration-runs/{runId}/promote` returns 202. Poll the same promotion GET. A finished phase does not start the next one.

Filesystem sources use a CSV under `etp.migration.storage.root` (default `./data/migration`). SFTP sources send `credentialRef`, not a password. Field-level JSON is in `capabilities/etp-migration/docs/API_GUIDE.md` and `capabilities/etp-migration/docs/USER_GUIDE.md`.

A canonical UAM load needs the operator schema and tables (see `capabilities/etp-migration/local/uam-target.sql`) and `target.mode` `UAM` in the definition. That schema is a local stand-in.

---

## 6. Kafka (optional)

Directory: `capabilities\kafka`.

```powershell
cd C:\Users\balan\etp-foundation\capabilities\kafka
copy .env.example .env
docker compose up -d
```

Broker: `localhost:59092`. No application in this repository consumes it until you add a client. Topics may be auto-created locally.

---

## 7. Redis (optional)

Directory: `capabilities\redis`.

```powershell
cd C:\Users\balan\etp-foundation\capabilities\redis
copy .env.example .env
```

Set `ETP_REDIS_PASSWORD`, then:

```powershell
docker compose up -d
```

Port 56379. No service in this repository connects to Redis yet. Use it as a cache beside PostgreSQL, not as the only copy of business data.

---

## 8. Exports (optional)

Directory: `capabilities\etp-exports\etp-exports`. Port 8095. No database.

```powershell
cd C:\Users\balan\etp-foundation\capabilities\etp-exports\etp-exports
mvn spring-boot:run
```

```powershell
curl -Method POST http://localhost:8095/api/v1/exports `
  -ContentType "application/json" `
  -Body '{"path":"https://jsonplaceholder.typicode.com/users","httpMethod":"GET","fileType":"csv"}' `
  -OutFile export.csv
```

Before any shared environment, set `etp.export.allowed-hosts` in `application.yml` so the service cannot call arbitrary hosts. The local file leaves that list empty.

---

## 9. Stop

In each directory where you started Compose:

```powershell
docker compose down
```

`docker compose down` keeps the named volume. Adding `-v` deletes the database volume.

---

## 10. Ports on one machine

| Port | Process |
|------|---------|
| 5173 | Core UI |
| 5434 | Platform PostgreSQL |
| 55432 | IAM PostgreSQL |
| 55433 | Migration PostgreSQL |
| 5050 | pgAdmin (IAM Compose) |
| 8080 | Platform API |
| 8081 | IAM API |
| 8082 | Migration API |
| 8095 | Exports |
| 8180 | Keycloak |
| 56379 | Redis |
| 59092 | Kafka |
