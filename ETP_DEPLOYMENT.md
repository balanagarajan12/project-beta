# ETP Deployment and Supporting Models

How ETP is deployed, and the supporting infrastructure each deployment binds. This file lives only at the repository root.

Architecture context: [ETP-ARCHITECTURE.md](ETP-ARCHITECTURE.md) (deployment model and commercial model). Local commands: [ETP_HOW_TO.md](ETP_HOW_TO.md).

---

## 1. Two independent dimensions

Commercial entitlement and physical deployment are separate.

```text
Commercial model  →  what the customer may consume
Deployment model  →  how that consumption is isolated at runtime
```

| Commercial model (target) | Dedicated deployment | Shared deployment |
|---------------------------|----------------------|-------------------|
| Application-based | Supported | Supported |
| Capability-based | Supported | Supported |
| Full ETP platform | Supported | Supported |

The repository does not yet enforce this. Flyway V14 creates `operating_model`, `customer_operating_access`, `capability_entitlement`, `application_entitlement`, and `platform_configuration`. No Java service reads or writes those tables. There is no deployment or environment table.

## 2. Deployment models

### Dedicated

The application runtime, its data, or a capability resource is reserved for one customer, tenant, or application.

Use it when isolation, compliance, or performance requires a private runtime. Cost is higher because each boundary carries its own infrastructure.

### Shared

Several customers or applications use the same platform runtime. Isolation is logical and must be explicit: tenant id, separate database, schema, network policy, IAM assignment, or quota.

A shared deployment is not “no isolation.”

### Capability-level

Dedicated versus shared is chosen per capability, not once for the whole stack.

```text
Customer application
├── Application runtime  → dedicated or shared
├── PostgreSQL           → dedicated database (current ETP pattern)
├── IAM + Keycloak       → dedicated instance per environment
├── Kafka                → dedicated or shared broker
└── Redis                → dedicated or shared, never the system of record
```

The same capability contract is deployed with different endpoints and secrets. Application definitions store intent (which capability, which settings). Environment configuration supplies host, port, and credentials.

## 3. What this repository can actually start

There is no orchestrator that provisions a customer environment. Each module has its own Compose file and its own database. Nothing in the platform service creates Kafka topics, Redis keys, or application databases.

| Component | Kind | Local start | Default host port |
|-----------|------|-------------|-------------------|
| Platform PostgreSQL 17 | Supporting database `etp_platform` | `etp-platform-service/docker-compose.yml` | 5434 |
| Platform API | Service | `mvn spring-boot:run` in `etp-platform-service` | 8080 |
| IAM PostgreSQL 17 | Supporting database `etp_iam` | `capabilities/etp-iam/docker-compose.yml` | 55432 when `.env` is copied from `.env.example` |
| Keycloak | Supporting identity provider, realm `etp` | same IAM Compose file | 8180 |
| IAM API | Capability service `etp.iam` | `mvn spring-boot:run` in `capabilities/etp-iam` | 8081 |
| Core UI | Internal console | `npm run dev` in `etp-core-ui` | 5173 |
| Migration PostgreSQL 17 | Supporting database `etp_migration` | `capabilities/etp-migration/docker-compose.yml` | 55433 when `.env` is copied from `.env.example` |
| Migration API | Capability service | `mvn spring-boot:run` in `capabilities/etp-migration` | 8082 |
| Kafka 3.8.1 KRaft | Supporting broker | `capabilities/kafka/docker-compose.yml` | 59092 |
| Redis 7.4 | Supporting cache | `capabilities/redis/docker-compose.yml` | 56379 |
| Exports | Service, no database | run `etp-exports` | 8095 |

Copy each `.env.example` to `.env` in that module **before** `docker compose up`. Two Compose files have a different fallback port when the variable is missing:

| Module | `.env.example` and JDBC default | Compose fallback if the variable is unset |
|--------|----------------------------------|-------------------------------------------|
| IAM database | 55432 | 55445 |
| Migration database | 55433 | 55446 |

Use the `.env.example` port so the JDBC URL and the published container port match.

## 4. Supporting model: PostgreSQL

Capability code `etp.postgresql`. Contract artifacts are in `capabilities/postgresql/`. The README states the capability is **not registered** in the Capability Registry yet.

PostgreSQL is infrastructure. Applications use JDBC directly. There is no SQL proxy and no platform API that runs queries for an application.

Rules already followed by the code:

- One database per service: `etp_platform`, `etp_iam`, `etp_migration`.
- The service owns its schema through Flyway (`ddl-auto=validate`).
- Business tables are not created by the platform catalog. Migration promotion writes tables the operator created (local demonstration schema `uam`).
- Credentials come from the environment. `.env` is not committed.

Image: `postgres:17`.

A future application such as UAM gets its own database and its own Flyway module. The platform database must not absorb that schema.

Detail: `capabilities/postgresql/ETP_POSTGRESQL_CAPABILITY_DEPLOYMENT.md` and [ETP_POSTGRESQL_CAPABILITY_READINESS.md](ETP_POSTGRESQL_CAPABILITY_READINESS.md).

## 5. Supporting model: Kafka

Capability code `etp.kafka`. Artifacts in `capabilities/kafka/`. Not registered yet. Not used by platform, IAM, migration, or exports code paths.

Local broker: `apache/kafka:3.8.1`, single combined KRaft node, plaintext listener. Clients connect to `localhost:59092` (`ETP_KAFKA_BOOTSTRAP_SERVERS`). Auto-create topics is on for local development.

Applications use a Kafka client directly. ETP does not sit in the produce or consume path. Topic names and event payloads belong to the consuming application. The broker is not a system of record.

Detail: `capabilities/kafka/ETP_KAFKA_CAPABILITY_DEPLOYMENT.md`.

## 6. Supporting model: Redis

Capability code `etp.redis`. Artifacts in `capabilities/redis/`. Not registered yet. No service in this repository opens a Redis connection today.

Local server: `redis:7.4-alpine`, password required (`ETP_REDIS_PASSWORD`), host port 56379.

Redis is a cache. A miss falls through to the application’s PostgreSQL database. Do not store the only copy of business data in Redis.

Detail: `capabilities/redis/ETP_REDIS_CAPABILITY_DEPLOYMENT.md`.

## 7. Supporting model: IAM and Keycloak

`etp.iam` is a deployable capability, not a business application. The IAM service README records a local registry entry (`etp.iam` 1.0.0 ACTIVE). Treat that id as a record of one environment, not as a constant every database must contain.

Each environment runs its own IAM process, its own `etp_iam` database, and its own Keycloak realm. A customer environment does not call the internal Core UI or the internal Keycloak.

| Piece | Role |
|-------|------|
| Keycloak | Users, passwords, OAuth clients, JWT issuer. Realm `etp`. Dev command `start-dev` |
| `etp_iam` | Principals, groups, roles, permissions, tenant assignments. Flyway V1–V2 |
| IAM process | Login, client-credentials token, authorization check |
| Core UI | One consumer. Holds the access token in the browser session, not the client secret |

Login client id and secret (`ETP_IAM_KEYCLOAK_LOGIN_CLIENT_ID`, `ETP_IAM_KEYCLOAK_LOGIN_CLIENT_SECRET`) stay on the IAM process.

Phase 1 is a password grant behind IAM. It is the development login, not the later SSO design.

## 8. Service placement

| Service | Database it may use | Must not use |
|---------|---------------------|--------------|
| `etp-platform-service` | `etp_platform` | `etp_iam`, `etp_migration`, business schemas |
| `etp-iam` | `etp_iam` plus Keycloak’s own store | `etp_platform` |
| `etp-migration` | Control plane `etp_migration`; a second datasource for staging and promotion | Platform or IAM databases as its control plane |
| `etp-exports` | none | — |
| `etp-core-ui` | none (calls HTTP only) | — |

Migration’s target URL is configuration (`ETP_MIGRATION_TARGET_POSTGRESQL_URL`). The sample `.env` points the target at the same local `etp_migration` database, schema `migration_staging`. A demonstration can point the target at another PostgreSQL instance that holds schema `uam`. That split is an operator choice.

## 9. Secrets and environments

- Secrets are environment variables or a secret manager. They are not fields in an application definition and not files committed to git.
- `application.yml` / `application.properties` import an optional `.env` and fail closed when a required password is absent.
- Dev, test, and production are separate databases and separate Keycloak realms.
- Integration tests for platform and migration use Testcontainers (`postgres:17`) rather than the developer’s Compose ports.

## 10. Not deployed by this repository

- Kubernetes, RDS, ElastiCache, or MSK manifests
- API gateway or API catalog processes
- Automatic database provisioning when an application is composed
- A running UAM product
- Customer UI
- Shared multi-tenant enforcement in the platform API

Those remain target deployment work. The supporting models above are the ones the repository can start on a workstation today.
