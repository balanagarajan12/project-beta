# ETP Architecture

Engineering architecture reference for the ETP Platform Service (`etp-platform-service`).

## 1. Vision

ETP is a **technology capability platform**. It enables enterprises to compose business applications from versioned, reusable technology capabilities with explicit contracts, dependencies, and lifecycle metadata.

Long-term interaction model:

```
Requirement
  → Capability Discovery
  → Capability Selection
  → Dependency Resolution
  → Compatibility / Security / NFR Validation
  → Application Definition
  → Application Composition
  → Generated Application Foundation
  → Business Application
```

The foundation and metadata model are in place; **Capability Registry V1** provides REST APIs for catalog registration and lifecycle. Composition and generation remain future work.

## 2. Core Mental Model

| Concept | Meaning |
|---------|---------|
| **Capability Contract** | **What** a reusable technology capability provides (APIs, events, configuration, UI contracts, etc.) |
| **Application Definition** | **What** an application needs — its technology composition and capability version selections |
| **Application Instance** | **Where/how** an application runs (future; not implemented) |
| **Source Code** | **How** business behavior is implemented in generated or custom application code |

**Hard rule:** **Capability ≠ Business Application.** A capability is a reusable platform building block. A business application is composed from selected capabilities and owns customer/business-specific behavior. Capabilities must not automatically become business applications. See [docs/ETP_CORE_UI_DESIGN_GUIDELINES.md](docs/ETP_CORE_UI_DESIGN_GUIDELINES.md) §3.

Capability catalog data is **platform-owned** and populated before customer applications exist. Customer and application records are populated when customers use the Application Initializer (future).

### Application composition model

```
Customer selects capabilities
          ↓
Application Composition
          ↓
ETP Customer UI
          ↓
Configured / composed application
          ↓
Build / Package
          ↓
Deployable runtime artifacts (container images)
          ↓
Business Application runtime
```

Customer-specific business rules and logic belong to the customer/application layer, not ETP Core UI.

## 3. Logical Platform Components

Logical ETP components (not necessarily separate deployables):

| Component | Role |
|-----------|------|
| ETP UI | Platform operator and developer experience (future) |
| ETP Platform API | Core platform REST services (`com.etp.platform.core`) |
| Application Registry | Customer applications and definitions (future APIs) |
| Capability Registry | Capability catalog, versions, contracts (**V1 REST APIs implemented**) |
| Configuration | Environment and capability configuration (future) |
| Platform Metadata Store | PostgreSQL `etp_platform` database |
| Application Composition / Builder | Compose definitions into deployable foundations (future) |
| Capability Validation | Compatibility, security, NFR checks (base JSON Schema for inline contracts in V1) |
| IAM | Identity and access via **ETP IAM capability** (`etp.iam`) — a reusable capability, not a business application |
| Workflow | Workflow capability (future) |
| Integration / Messaging | Kafka, RabbitMQ, SFTP, etc. (future capabilities) |
| Observability | Metrics, tracing, logging integration (future) |
| Audit | Platform metadata change audit (**events recorded in V1**; query API future) |
| Deployment / Runtime Integration | Runtime placement and operations (future / Phase 2) |

**Important:** These are logical boundaries. They do **not** imply one microservice per component.

### Repository structure (conceptual target)

Intended separation under `etp-foundation/` — **architectural target only**; directories are created by implementation work, not by documentation:

```
etp-foundation/
│
├── etp-core-ui/              # Internal ETP Core UI (platform console)
├── etp-platform-service/     # Platform metadata APIs and registry
└── capabilities/             # Reusable capability implementations
    ├── etp-iam/
    ├── etp-workflow/
    ├── etp-rules-engine/
    ├── etp-react-ui/
    ├── etp-email/
    └── etp-kafka/
```

`etp-core-ui` and `etp-platform-service` exist today. Capability projects under `capabilities/` are the intended home for reusable capability code such as IAM, workflow, and rules engine.

### Capability reusability

Capabilities are designed for reuse across multiple consumers. Example — ETP IAM consumed by several business applications:

```
                  ETP IAM Capability
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
     Business App A  Business App B  Business App C
```

Do not implement capabilities (including IAM) as owned by a single business application.

## 4. Current Implementation

### Implemented now (`etp-platform-service`)

| Area | Status |
|------|--------|
| Spring Boot 4.1 service foundation | Implemented |
| Platform metadata data model (11 tables) | Implemented |
| Flyway migrations (13) | Implemented |
| JPA entities | Implemented |
| `GET /api/v1/health` (`com.etp.platform.core`) | Implemented |
| `GET /api/v1/platform/info` (`com.etp.platform.core`) | Implemented |
| **Capability Registry V1 APIs** (`/api/v1/capabilities`) | Implemented |
| Capability and version lifecycle (incl. `CANCELLED`) | Implemented |
| Inline JSONB contract documents + external references | Implemented |
| JSON Schema validation (V1 base schema) | Implemented |
| Single ACTIVE version per capability (app + PostgreSQL index) | Implemented |
| Lifecycle audit events (`AuditEvent` table) | Implemented |
| Common API/error model | Implemented |
| Database connectivity health indicator | Implemented |
| Spring Security baseline | Implemented |
| OAuth2 Resource Server dependency | Present; Keycloak not integrated |
| Actuator health | Implemented |
| OpenAPI / Swagger (SpringDoc) | Implemented |
| Docker Compose local PostgreSQL | Implemented |
| PostgreSQL integration tests (Testcontainers) | Implemented (requires Docker) |

Detailed Capability Registry behavior: [docs/CAPABILITY_REGISTRY.md](docs/CAPABILITY_REGISTRY.md).

### Future (not in this repository yet)

- Capability Registry **UI**
- Application Registry APIs
- Application Builder / Initializer / Composer
- ETP UI Foundation
- IAM capability integration (Keycloak)
- Workflow capability (e.g. Flowable)
- Messaging capabilities (Kafka, RabbitMQ)
- Integration capabilities (SFTP, email, etc.)
- Observability platform integration
- Deployment/runtime integration
- Application Instance model and APIs
- Audit query APIs

## 5. Capability Registry V1 — Lifecycle

Capability lifecycle and version lifecycle are related but independent.

### Capability lifecycle

```
DRAFT → ACTIVE → DEPRECATED → RETIRED
```

The first version transition to `ACTIVE` automatically moves the parent capability from `DRAFT` to `ACTIVE` (audited). There is no `CANCELLED` status at the capability level in V1.

### Capability version lifecycle

```
DRAFT
  ├── VALIDATED
  │     ├── ACTIVE → DEPRECATED → RETIRED
  │     └── CANCELLED
  └── CANCELLED
```

Key rules:

- POST creates capability + first version in `DRAFT`.
- Further versions and lifecycle changes use PATCH on `/api/v1/capabilities/{id}`.
- Only one `ACTIVE` version per capability; activating a higher semver deprecates the previous `ACTIVE` version in one transaction.
- A validated version cannot be activated if its semver is ≤ the current `ACTIVE` version.
- `CANCELLED` applies only to versions that never were `ACTIVE`.
- `ACTIVE` version content is immutable; changes require a new version.
- Invalid transitions return HTTP 409 / `INVALID_LIFECYCLE_TRANSITION`.

PostgreSQL enforces the single-ACTIVE invariant via partial unique index `uk_capability_version_one_active_per_capability` (`V13` migration).

## 6. Current Data Model

All tables live in `etp_platform`. UUID primary keys. No seed data in migrations.

| Entity | Purpose |
|--------|---------|
| **Customer** | Enterprise customer for whom ETP builds applications |
| **Application** | Application belonging to a customer |
| **ApplicationDefinition** | Versioned technology composition for an application (includes optional JSONB document; relational capability links required) |
| **ApplicationCapability** | Exact capability version used by an application definition |
| **Capability** | ETP-owned reusable technology capability (e.g. `etp.iam`) |
| **CapabilityVersion** | Version of a capability with lifecycle status |
| **CapabilityContract** | Contract artifacts for a capability version — inline JSONB (`contract_document`) or external references (`contract_location` + `contract_format`) |
| **CapabilityImplementation** | Technology implementation behind a capability version |
| **CapabilityDependency** | Version-aware dependency between capability versions |
| **PlatformVersion** | Version of ETP itself |
| **AuditEvent** | Audit record for platform metadata changes |

### Key relationships

```
CUSTOMER
  → APPLICATION
    → APPLICATION_DEFINITION
      → APPLICATION_CAPABILITY
        → CAPABILITY_VERSION
          → CAPABILITY
```

Capability catalog (`CAPABILITY` → `CAPABILITY_VERSION` → contracts/implementations/dependencies) is independent of customer data. This supports queries such as: *which customer applications use capability X version Y?*

`APPLICATION_CAPABILITY` is the authoritative relational link; JSONB in `APPLICATION_DEFINITION` does not replace it.

## 7. API Principles

Current conventions:

- Versioned base path: `/api/v1`
- REST resource-oriented design
- Wrapped responses via `ApiResponse<T>` where used
- Errors via `ErrorResponse` / `ErrorDetail` and `GlobalExceptionHandler`
- Operational endpoints under `/actuator` (not mixed with business APIs)
- OpenAPI at `/api/v1/openapi`, Swagger UI at `/api/v1/swagger-ui`
- Capability Registry: aggregate-oriented API; no DELETE; lifecycle via PATCH

Detailed conventions: [docs/API_CONVENTIONS.md](docs/API_CONVENTIONS.md). Capability Registry detail: [docs/CAPABILITY_REGISTRY.md](docs/CAPABILITY_REGISTRY.md).

## 8. Security Boundary

### ETP IAM as a capability

**ETP IAM is a capability**, not a business application. It provides authentication and authorization platform services, can be selected during application composition, and may be consumed by multiple business applications and by ETP Customer UI where appropriate.

Intended integration architecture:

```
ETP IAM capability (etp.iam)
        ↓
OAuth2 / OIDC contracts
        ↓
Keycloak (or compatible identity provider)
```

### ETP Core UI authentication vs IAM capability

**ETP Core UI authentication integration ≠ ETP IAM capability implementation.**

- ETP Core UI **consumes** authentication services through the IAM capability.
- ETP Core UI must **not** embed customer-specific IAM business logic.
- During early development, ETP Core UI may use **temporary development/mock authentication** until the real IAM capability is available.
- Mock authentication is **not** production authentication and must be **replaceable** without redesigning the approved Login UI.

### ETP Core UI authentication phases

Frontend progression (see [docs/ETP_CORE_UI_DESIGN_GUIDELINES.md](docs/ETP_CORE_UI_DESIGN_GUIDELINES.md) §3 for detail). **Steps 1A and 1B do not implement the IAM capability.**

| Step | Focus |
|------|--------|
| **1A** | Auth contracts, session/storage abstraction, `AuthProvider`, mock auth, Remember Me, session restoration, logout |
| **1B** | Route protection, guest-only login, post-login redirect, logout handling |
| **1C** | Replace mock auth with real ETP IAM capability integration |

### Platform API security (current)

**Current status:** `spring-boot-starter-security-oauth2-resource-server` is on the classpath. `SecurityConfig` permits health, platform info, actuator health, OpenAPI paths, and **capability registry APIs** until IAM integration. **Keycloak is not integrated.** No token validation against a live issuer is configured.

## 9. Local Runtime

| Component | Configuration |
|-----------|---------------|
| Application | Port `8080` (`ETP_SERVER_PORT`) |
| PostgreSQL | Host port `5434` → container `5432` (`ETP_DB_PORT`) |
| Database | `etp_platform`, user `etp_admin` |
| Schema | Flyway `classpath:db/migration` (13 migrations) |
| JPA | `ddl-auto=validate` (default profile) |
| Timezone | JDBC `TimeZone=UTC` |

**Local profile (`local`):** in-memory H2, Flyway disabled, `ddl-auto=none` — for lightweight startup without PostgreSQL.

## 10. Architecture Guardrails

1. **Capability ownership** — ETP owns reusable technology capabilities; business applications own business logic and domain data. **Capability ≠ business application** — capabilities must not automatically become business applications.
2. **Explicit versions** — Capabilities and application definitions are versioned; dependencies reference specific capability versions.
3. **Explicit contracts** — Capability contracts should be machine-readable (OpenAPI, AsyncAPI, JSON Schema, etc.) where applicable.
4. **Metadata vs business data** — `etp_platform` holds platform metadata only.
5. **API-first UI** — UIs consume APIs; they do not access databases directly.
6. **Technology abstraction** — Implementations (Keycloak, Flowable, etc.) sit behind capability abstractions.
7. **Security** — Authentication and authorization cannot be bypassed by composition or generated code.
8. **Correlation** — Distributed operations should propagate correlation/transaction context for observability and audit.
9. **Deployment shape** — Logical components do not mandate microservice boundaries; avoid premature decomposition.
10. **Generated code quality** — Generated applications are normal maintainable source code, not opaque binaries.
11. **Secrets** — Never embed secrets in generated artifacts or source control.
12. **Validation gate** — Application definitions require validation before future publication/composition.

## 11. Roadmap Position

**Foundation:** Service scaffold, metadata model, Flyway, JPA, health APIs, documentation, local PostgreSQL.

**Capability Registry V1 (current):** REST APIs, lifecycle (including `CANCELLED`), inline JSONB contracts, audit on lifecycle changes, single-ACTIVE enforcement.

**Next major work:** Application Registry APIs, Application Initializer, ETP Core UI authentication foundation (Steps 1A–1B), IAM capability integration (Step 1C for Core UI; IAM capability implementation separately), Capability Registry UI, and composition workflows.

This document does not restate the full multi-week ETP roadmap.
