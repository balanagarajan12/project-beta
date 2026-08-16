# ETP Platform Service

Backend foundation for the Enterprise Technology Platform (ETP). This service establishes the core Spring Boot structure, API conventions, security baseline, and operational endpoints for the platform.

## Technology Stack

- Java 21
- Spring Boot 4.1
- Maven
- Spring Web MVC
- Spring Data JPA
- PostgreSQL 17 (platform database)
- H2 (optional `local` profile for lightweight development)
- Spring Validation
- Spring Security
- OAuth2 Resource Server (dependency present; Keycloak integration deferred)
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

## Package Structure

```
com.etp.platform
├── core              # Platform-level REST endpoints (health, info)
├── application       # Business application domain (future)
├── capability        # Platform capabilities (future)
├── environment       # Environment and infrastructure (database health)
├── composition       # Application composition (future)
├── security          # Security configuration
└── common            # Shared API models and exception handling
```

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

Hibernate schema validation is enabled (`spring.jpa.hibernate.ddl-auto=validate`). No business tables exist yet; an empty database is sufficient for connectivity validation.

### Local profile (in-memory H2)

For lightweight local development without PostgreSQL:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The service starts on port `8080` by default (`ETP_SERVER_PORT` overrides).

## Available APIs

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/health` | Application health status |
| GET | `/api/v1/platform/info` | Platform name, version, and description |
| GET | `/actuator/health` | Spring Boot Actuator health (includes database connectivity) |
| GET | `/api/v1/openapi` | OpenAPI specification |
| GET | `/api/v1/swagger-ui` | Swagger UI |

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

Tests use in-memory H2. The `local` profile integration test verifies the application starts with the local development database configuration.
