# ETP PostgreSQL — Capability Deployment Guide

Deployment and consumption guide for the **ETP PostgreSQL** infrastructure capability (`etp.postgresql` target version **1.0.0**).

**Related:** [README.md](README.md), [ETP_POSTGRESQL_CAPABILITY_READINESS.md](../../ETP_POSTGRESQL_CAPABILITY_READINESS.md), [postgresql-capability-contract.json](postgresql-capability-contract.json)

---

## What this capability provides

| Provides | Does not provide |
|----------|------------------|
| PostgreSQL **17** as ETP-standard relational infrastructure | ETP REST APIs or SQL proxy |
| JDBC connectivity contract and configuration conventions | Application table design or Flyway scripts |
| Database-per-application isolation pattern | Automatic DB provisioning for new apps (future) |
| Reference local Docker Compose patterns (existing repos) | A deployable Spring Boot “postgres service” jar |
| Security and operations documentation | Live credentials in the Capability Registry |

**Registry registration** is separate from running PostgreSQL. This capability describes **infrastructure**; consumers connect with their own JDBC configuration.

---

## Runtime path (applications)

For an application such as **UAM** (future):

```
UAM Application Service
    ↓
Spring Data JPA / Hibernate
    ↓
PostgreSQL JDBC Driver (org.postgresql.Driver)
    ↓
PostgreSQL 17
    ↓
Docker Compose / Kubernetes / AWS RDS / Azure Database for PostgreSQL
```

The **ETP PostgreSQL capability is NOT in the runtime transaction path.** There is no ETP middleware between the application and PostgreSQL.

---

## Future direction (not implemented)

```
Application Composition / Initializer (future)
        ↓
Select etp.postgresql capability
        ↓
Deployment resolves environment-specific PostgreSQL endpoint
        ↓
Application receives JDBC connection configuration
```

Automatic provisioning and wiring **do not exist** in the repository today. Applications currently configure `spring.datasource.*` and environment variables directly.

---

## Local Docker deployment (current repository)

PostgreSQL is **not** centralized in one root compose file. Existing infrastructure:

### Platform metadata database

| Item | Value |
|------|-------|
| Compose | `etp-platform-service/docker-compose.yml` |
| Service | `etp-platform-postgres` |
| Image | `postgres:17` |
| Container | `etp-platform-postgres` |
| Default host port | `5434` (`ETP_DB_PORT`) |
| Database | `etp_platform` |
| User | `etp_admin` |
| Password | `ETP_DB_PASSWORD` (required) |
| Volume | `etp_platform_pgdata` |
| Healthcheck | `pg_isready -U etp_admin -d etp_platform` |

```bash
cd etp-platform-service
cp .env.example .env
# Set ETP_DB_PASSWORD
docker compose up -d
```

### IAM authorization database

| Item | Value |
|------|-------|
| Compose | `capabilities/etp-iam/docker-compose.yml` |
| Service | `postgres` |
| Image | `postgres:17` |
| Container | `etp-iam-postgres` |
| Default host port | `55432` (`ETP_IAM_DB_PORT`) |
| Database | `etp_iam` |
| User | `ETP_IAM_DB_USERNAME` (default `etp_admin`) |
| Password | `ETP_IAM_DB_PASSWORD` (required) |
| Volume | `etp-postgres-data` |
| Healthcheck | Not defined in compose (consumer JDBC checks only) |

Optional **pgAdmin** on port `5050` in the same compose file (development only).

```bash
cd capabilities/etp-iam
cp .env.example .env
docker compose --env-file .env up -d
```

**Do not** point platform service at `etp_iam` or IAM at `etp_platform` — separate databases by design.

---

## Database creation

### Docker Compose (official `postgres` image)

The initial database is created from `POSTGRES_DB` on first container start:

```yaml
environment:
  POSTGRES_DB: etp_platform   # or etp_iam
  POSTGRES_USER: etp_admin
  POSTGRES_PASSWORD: ${...}
```

Additional databases (e.g. future `etp_uam`) require:

- New `POSTGRES_DB` on a new container, **or**
- `CREATE DATABASE` on an existing instance (manual/operator), **or**
- Future platform provisioning automation

ETP does **not** automate this in v1.0.0.

---

## Database user configuration

| Pattern | Current repository |
|---------|-------------------|
| Application user | Same as `POSTGRES_USER` in compose examples (`etp_admin`) |
| Privileges | Full access to application database (dev pattern) |
| Migration vs runtime user split | **Not configured** — single user for Flyway and runtime |

Production should use least-privilege users and secret manager injection (deployment-specific).

---

## Credentials and secrets

| Variable (examples) | Service |
|----------------------|---------|
| `ETP_DB_URL`, `ETP_DB_USERNAME`, `ETP_DB_PASSWORD` | Platform |
| `ETP_IAM_DB_URL`, `ETP_IAM_DB_USERNAME`, `ETP_IAM_DB_PASSWORD` | IAM |

- Never commit `.env` with real passwords
- Never put live secrets in Capability Registry `contract.document`
- `.env.example` files use placeholders only

**TLS/SSL:** Not configured in default JDBC URLs. Use `sslmode` and certificates in deployed environments (operator responsibility).

---

## JDBC connection configuration

### Platform service

From `etp-platform-service/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: ${ETP_DB_URL:jdbc:postgresql://localhost:5434/etp_platform}
    username: ${ETP_DB_USERNAME:etp_admin}
    password: ${ETP_DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      data-source-properties:
        options: "-c TimeZone=UTC"
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
```

### IAM service

From `capabilities/etp-iam/src/main/resources/application.properties`:

```properties
spring.datasource.url=${ETP_IAM_DB_URL:jdbc:postgresql://localhost:55432/etp_iam?options=-c%20TimeZone%3DUTC}
spring.datasource.username=${ETP_IAM_DB_USERNAME:etp_admin}
spring.datasource.password=${ETP_IAM_DB_PASSWORD}
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

### Future UAM (pattern only — not implemented)

```properties
# Illustrative — UAM not in repository
spring.datasource.url=${ETP_UAM_DB_URL:jdbc:postgresql://localhost:PORT/etp_uam}
spring.datasource.username=${ETP_UAM_DB_USERNAME}
spring.datasource.password=${ETP_UAM_DB_PASSWORD}
```

Convention: prefix env vars by application (`ETP_<APP>_DB_*`).

---

## Health and readiness

| Layer | Mechanism |
|-------|-----------|
| PostgreSQL container (platform) | Docker `pg_isready` healthcheck |
| PostgreSQL container (IAM) | No compose healthcheck |
| Consumer services | Spring Actuator `management.health.db.enabled=true` |
| Consumer services | Custom `DatabaseConnectivityHealthIndicator` (`connection.isValid(2)`) |

There is **no** dedicated ETP PostgreSQL service `/actuator/health` endpoint. Readiness is observed from **consumers** and optionally from container healthchecks.

**Startup order:** Start PostgreSQL before consumer JVMs that run Flyway on startup.

---

## Migration responsibility

| Owner | Responsibility |
|-------|----------------|
| **PostgreSQL capability** | Database server availability |
| **Consumer application** | Schema design, Flyway SQL, JPA entities, repositories |

| Consumer | Flyway location | Notes |
|----------|-----------------|-------|
| Platform | `etp-platform-service/src/main/resources/db/migration/` | V1–V14 |
| IAM | `capabilities/etp-iam/src/main/resources/db/migration/` | V1 schema, V2 dev seed |

`ddl-auto=validate` — Hibernate does not create schema; Flyway must run first.

---

## Application consumption checklist

1. Provision PostgreSQL 17 (compose, RDS, etc.)
2. Create database and user for the application
3. Set JDBC URL, username, password via environment
4. Add Flyway migrations in the application module
5. Configure Spring Data JPA with `ddl-auto=validate`
6. Start application after PostgreSQL is reachable
7. Register `etp.postgresql` in application composition metadata (after capability is ACTIVE in registry)

---

## Environment-specific deployment

| Environment | Repository support |
|-------------|-------------------|
| Local Docker Compose | **Implemented** — platform and IAM compose files |
| Kubernetes | Not defined in repository |
| AWS RDS | Not defined in repository |
| Azure Database for PostgreSQL | Not defined in repository |

Operators map the same JDBC configuration model to managed services: endpoint, database name, user, secret, optional TLS.

---

## Capability Registry (preparation)

| Field | Value |
|-------|-------|
| Code | `etp.postgresql` |
| Version | `1.0.0` |
| Category | `DATA` |
| Registration payload | `postgresql-capability-registration.json` |
| Contract document | `postgresql-capability-contract.json` |

Registration and lifecycle activation are **manual steps** after review — not performed by this guide.

---

*Infrastructure capability documentation. No changes to existing `etp-platform-service` or `etp-iam` Docker Compose files are required for v1.0.0.*
