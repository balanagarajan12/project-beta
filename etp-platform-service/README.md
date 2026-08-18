# ETP Platform Service

Backend foundation for the Enterprise Technology Platform (ETP). This service is the primary developer entry point for the ETP platform backend.

## ETP Purpose

ETP is an **enterprise technology composition platform**. It provides reusable technology capabilities that business applications can consume, so teams can build on a consistent technology foundation instead of re-implementing platform concerns in every project.

ETP is **not**:

- a business application
- a low-code replacement
- an IDE
- a replacement for source control
- a generic business-process platform

**Core principle:**

> ETP owns reusable technology capabilities. Business applications own business logic.

## Architecture Boundary

### ETP owns

- reusable technology capabilities (catalog, versions, contracts, implementations, dependencies)
- capability contracts
- platform metadata
- common APIs and error conventions
- security/IAM integration patterns (foundation only in Week 1)
- workflow, integration, and messaging patterns (planned capabilities)
- configuration patterns
- audit foundations
- observability foundations
- application composition (future)

### Business applications own

- business domain model
- business rules
- business workflows
- business transactions
- business-specific screens
- business data

## Current Platform Foundation

Implemented in this repository today:

- **Spring Boot 4.1** on **Java 21**
- **PostgreSQL 17** (`etp_platform` database)
- **Flyway** schema migrations (13 migrations, 11 tables, no seed data)
- **JPA/Hibernate** entities with `ddl-auto=validate`
- **REST APIs** — platform health/info (`com.etp.platform.core`) and **Capability Registry V1** (`/api/v1/capabilities`)
- **Capability Registry V1** — create, list, retrieve, and PATCH capabilities with version lifecycle, inline JSONB contracts, audit on lifecycle changes, and single-ACTIVE-version enforcement
- **Spring Boot Actuator** — operational health
- **Docker Compose** — local PostgreSQL
- **Common API/error model** — `ApiResponse`, `ErrorResponse`, `GlobalExceptionHandler`
- **Platform metadata persistence** — customer, application, capability, and audit entity model
- **Spring Security** baseline with OAuth2 Resource Server dependency (Keycloak not integrated)
- **OpenAPI/Swagger** via SpringDoc

**Not implemented yet** (planned capabilities and services): Capability Registry UI, Application Registry APIs, Application Initializer, Application Composer, Keycloak integration, workflow, Kafka, RabbitMQ, SFTP, AI, generated applications, and ETP UI.

**Implemented:** Capability Registry **REST APIs** and lifecycle (see [docs/CAPABILITY_REGISTRY.md](docs/CAPABILITY_REGISTRY.md)). IAM token enforcement on capability APIs is deferred.

## Technology Stack

- Java 21
- Spring Boot 4.1
- Maven
- Spring Web MVC
- Spring Data JPA
- PostgreSQL 17 (platform database)
- Flyway
- H2 (optional `local` profile for lightweight development without PostgreSQL)
- Spring Validation
- Spring Security + OAuth2 Resource Server (dependency present; Keycloak integration deferred)
- Spring Boot Actuator
- OpenAPI / Swagger (springdoc-openapi 3.x)

## ETP Platform Database

The ETP Platform service uses its own PostgreSQL database, separate from ETP-IAM:

| Service | Database |
|---------|----------|
| ETP Platform | `etp_platform` |
| ETP-IAM | `etp_iam` |

Do not connect this service to the IAM database.

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ETP_DB_URL` | JDBC URL for PostgreSQL | `jdbc:postgresql://localhost:5434/etp_platform` |
| `ETP_DB_USERNAME` | Database username | `etp_admin` |
| `ETP_DB_PASSWORD` | Database password | *(required for PostgreSQL)* |
| `ETP_SERVER_PORT` | HTTP port | `8080` |
| `ETP_DB_PORT` | Docker host port for PostgreSQL | `5434` |

Copy `.env.example` to `.env` and set `ETP_DB_PASSWORD` before starting Docker Compose.

## PostgreSQL Setup (Docker Compose)

Start PostgreSQL 17 with a persistent volume:

```bash
cp .env.example .env
# Edit .env and set ETP_DB_PASSWORD

docker compose up -d
```

Verify the database is healthy:

```bash
docker compose ps
```

Default host port is `5434` to avoid conflicting with other local PostgreSQL instances (for example ETP-IAM on `5432` or other services on `5433`).

Stop PostgreSQL:

```bash
docker compose down
```

## Current Package Structure

```
com.etp.platform
├── EtpPlatformServiceApplication.java
├── core                    # Platform REST endpoints and platform_version entity
│   ├── dto                 # HealthResponse, PlatformInfoResponse
│   ├── HealthController.java
│   ├── PlatformController.java
│   └── PlatformVersion.java
├── application             # Customer/application metadata JPA entities
├── capability              # Capability catalog entities, APIs, lifecycle, validation
│   ├── api                 # CapabilityController, request/response DTOs
│   ├── domain              # Lifecycle enums and validators
│   ├── repository
│   ├── service
│   └── validation          # JSON Schema contract document validation
├── common                  # Shared API models, exceptions, audit
│   ├── api
│   ├── exception
│   └── audit
├── environment             # Infrastructure health (database connectivity)
└── security                # Security configuration
```

Reserved for future work (no Java packages yet): `composition`.

## Data Ownership

- **ETP Platform database (`etp_platform`)** stores **platform metadata** — customers, applications, application definitions, capability catalog, capability versions, contracts, dependencies, platform versions, and audit events.
- **Business application databases** own **business/domain data** for each deployed application.
- **Application Definition** represents **what** an application needs (technology composition, capability versions, configuration references).
- **Application Instance** (future) will represent **where/how** an application runs. It is not implemented in Week 1.

Platform metadata tables must not become a store for business-domain data.

## Architecture Guardrails

1. ETP owns technology capabilities; applications own business logic.
2. Capabilities have explicit versions.
3. Capability dependencies are explicit and version-aware.
4. Capability contracts should be machine-readable where applicable.
5. Platform metadata must not become business data.
6. UI communicates through APIs, not directly with databases.
7. Underlying technology should remain behind capability abstractions where practical.
8. Security cannot be bypassed.
9. Significant distributed transactions should carry correlation context.
10. Logical component boundaries do not automatically imply microservices.
11. Avoid premature microservice decomposition.
12. Generated applications remain standard maintainable source code.
13. Secrets must never be embedded in generated artifacts.
14. Validation is required before future application composition/publication.

See [ARCHITECTURE.md](ARCHITECTURE.md) for engineering detail and [docs/API_CONVENTIONS.md](docs/API_CONVENTIONS.md) for API conventions.

## Phase Boundary

**Implemented:** Capability Registry REST APIs and lifecycle (see [docs/CAPABILITY_REGISTRY.md](docs/CAPABILITY_REGISTRY.md)).

Intentionally **not** implemented:

- Application Initializer
- Application Builder / Composer
- Capability Registry UI
- full ETP UI
- Keycloak implementation integration
- Workflow implementation
- Kafka implementation
- RabbitMQ implementation
- SFTP implementation
- AI capability discovery
- generated applications
- managed-services / customer operational functionality

These are future roadmap items.

## Build

```bash
mvn clean package
```

## Run

### With PostgreSQL (default)

Set database environment variables and start PostgreSQL first:

```bash
export ETP_DB_PASSWORD=your_password
export ETP_DB_URL=jdbc:postgresql://localhost:5434/etp_platform
export ETP_DB_USERNAME=etp_admin

docker compose up -d
mvn spring-boot:run
```

Flyway applies schema migrations on startup. Hibernate schema validation is enabled (`spring.jpa.hibernate.ddl-auto=validate`).

### Local profile (in-memory H2)

For lightweight local development without PostgreSQL (Flyway disabled; JPA validation not enforced against PostgreSQL schema):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The service starts on port `8080` by default (`ETP_SERVER_PORT` overrides).

## Available APIs

### Platform (`com.etp.platform.core`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/health` | Application health status |
| GET | `/api/v1/platform/info` | Platform name, version, and description |
| GET | `/actuator/health` | Spring Boot Actuator health (includes database connectivity) |
| GET | `/api/v1/openapi` | OpenAPI specification |
| GET | `/api/v1/swagger-ui` | Swagger UI |

### Capability Registry V1

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/capabilities` | Create capability with first version (DRAFT) |
| GET | `/api/v1/capabilities` | List capabilities (`category`, `status` optional) |
| GET | `/api/v1/capabilities/{id}` | Retrieve full capability aggregate |
| PATCH | `/api/v1/capabilities/{id}` | Update metadata, lifecycle, new version, or version status |

There is no DELETE endpoint. Deprecation, retirement, and cancellation are lifecycle operations via PATCH.

Lifecycle summary:

- **Capability:** `DRAFT` → `ACTIVE` → `DEPRECATED` → `RETIRED`
- **Version:** `DRAFT` → `VALIDATED` → `ACTIVE` → `DEPRECATED` → `RETIRED`; `DRAFT`/`VALIDATED` → `CANCELLED` (terminal; only if never ACTIVE)

See [docs/CAPABILITY_REGISTRY.md](docs/CAPABILITY_REGISTRY.md) for full lifecycle rules and examples.

### Example responses

**GET /api/v1/health**

```json
{
  "data": {
    "status": "UP",
    "service": "etp-platform-service"
  },
  "message": null,
  "timestamp": "2026-08-16T08:00:00Z"
}
```

**GET /api/v1/platform/info**

```json
{
  "data": {
    "name": "Enterprise Technology Platform",
    "version": "0.1.0-SNAPSHOT",
    "description": "ETP Platform Service foundation"
  },
  "message": null,
  "timestamp": "2026-08-16T08:00:00Z"
}
```

## Tests

```bash
mvn test
```

PostgreSQL integration tests use Testcontainers when Docker is available. `CapabilityVersionLifecyclePostgreSQLTest` and `CapabilityPostLocalPostgreSQLTest` exercise the registry against real PostgreSQL on `localhost:5434` when available. The `local` profile test verifies startup with in-memory H2.

## Further Reading

- [ARCHITECTURE.md](ARCHITECTURE.md) — platform architecture, IAM as capability, repository structure, authentication phases
- [docs/ETP_CORE_UI_DESIGN_GUIDELINES.md](docs/ETP_CORE_UI_DESIGN_GUIDELINES.md) — ETP Core UI design system, three UI layers, authentication phases (Step 1A–1C)
- [docs/CAPABILITY_REGISTRY.md](docs/CAPABILITY_REGISTRY.md) — Capability Registry V1 APIs and lifecycle
- [CAPABILITY_DEFINITION_OF_DONE.md](CAPABILITY_DEFINITION_OF_DONE.md) — capability delivery standard
- [docs/API_CONVENTIONS.md](docs/API_CONVENTIONS.md) — REST API conventions
