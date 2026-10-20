# ETP PostgreSQL Capability

**Capability code:** `etp.postgresql`  
**Target version:** `1.0.0`  
**Implementation code:** `postgresql`

| Registry status | Not registered yet — artifacts prepared for review |
|-----------------|------------------------------------------------------|

ETP PostgreSQL is an **infrastructure capability**: it documents how ETP uses PostgreSQL 17 as relational database infrastructure. It is **not** a Spring Boot service, **not** in the application request path, and **not** a SQL proxy.

## Purpose

- Standard ETP relational datastore (PostgreSQL 17)
- JDBC connectivity for platform and application services
- Database-per-service/application isolation pattern
- Deployment, security, and configuration conventions

## Architecture

```
Application Service (e.g. UAM, IAM, Platform)
        ↓
Spring Data JPA / Hibernate
        ↓
PostgreSQL JDBC Driver
        ↓
PostgreSQL 17 (Docker / K8s / RDS / Azure — deployment-specific)
```

The **ETP PostgreSQL capability** (registry contract + deployment docs) describes this stack. It does **not** sit in the runtime transaction path.

## Current implementation in repository

| Item | Detail |
|------|--------|
| Software | PostgreSQL **17** (`postgres:17` Docker image) |
| Local dev | Separate compose Postgres per stack — see deployment guide |
| Consumers | `etp-platform-service` → `etp_platform`; `etp-iam` → `etp_iam` |
| Migrations | **Consumer-owned** Flyway (`classpath:db/migration`) |
| Pool | Spring Boot default HikariCP |

## What applications consume

- JDBC URL, database name, username, password (env or secret manager)
- Direct connection via `spring.datasource.*` — no ETP database broker
- Own schema, entities, repositories, and Flyway migrations

Example future consumer: **UAM** → dedicated database (e.g. `etp_uam`) → own Flyway module.

## Out of scope (v1.0.0)

- ETP REST APIs for SQL or database administration
- New Java/JVM “PostgreSQL capability service”
- Automatic application database provisioning (future Application Composition)
- Modifying `etp_platform` or `etp_iam` schemas via this capability package
- UAM implementation

## Artifacts

| File | Purpose |
|------|---------|
| `postgresql-capability-contract.json` | Inline `contract.document` for registry |
| `postgresql-capability-registration.json` | `CreateCapabilityRequest` payload (review before POST) |
| `ETP_POSTGRESQL_CAPABILITY_DEPLOYMENT.md` | Deployment and consumption guide |

## Related

- [ETP_POSTGRESQL_CAPABILITY_READINESS.md](../../ETP_POSTGRESQL_CAPABILITY_READINESS.md) — analysis
- [ETP IAM capability](../etp-iam/) — reference onboarding pattern (`etp.iam` ACTIVE)
- [ETP APIs](../../ETP_APIS.md)
