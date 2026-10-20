# ETP Engineering Model

Technology stack, mental models, logical components, repository layout, and architecture guardrails. This file lives only at the repository root.

What ETP is for: [ETP_CONCEPTUAL.md](ETP_CONCEPTUAL.md). What the code does today: [ETP-ARCHITECTURE.md](ETP-ARCHITECTURE.md).

Rows marked **Target** are the intended model. They are not running code unless the status column says they are.

---

## 1. Technology stack

### Platform service (`etp-platform-service`)

| Piece | Version / choice |
|-------|------------------|
| Language | Java 21 |
| Framework | Spring Boot 4.1.0 |
| Build | Maven |
| HTTP | Spring Web MVC |
| Persistence | Spring Data JPA, Hibernate `ddl-auto=validate` |
| Database | PostgreSQL 17, database `etp_platform` |
| Schema | Flyway V1–V14 |
| Lightweight profile | `local` uses in-memory H2 and disables Flyway |
| Security | Spring Security. OAuth2 resource-server starter is present and not enabled |
| Operations | Actuator health |
| API docs | springdoc-openapi 3.1.0 |
| Tests | JUnit, Testcontainers 1.21.0 with `postgres:17` when Docker is available |
| Local database | Docker Compose, image `postgres:17`, host port 5434 |

### Core UI (`etp-core-ui`)

| Piece | Version / choice |
|-------|------------------|
| Language | TypeScript |
| UI | React 19 |
| Build | Vite 8 |
| Routing | React Router 7 |
| Icons | Lucide |
| Styling | ETP design tokens and CSS modules |
| Lint | oxlint |
| Component library | None. No Material UI, Ant Design, or similar |

The UI talks to HTTP APIs only. It does not open PostgreSQL.

### Capabilities in this repository

| Module | Stack |
|--------|--------|
| `capabilities/etp-iam` | Java 21, Spring Boot 4.1.0, PostgreSQL 17 (`etp_iam`), Keycloak, Flyway V1–V2 |
| `capabilities/etp-migration` | Java 21, Spring Boot 4.1.0, PostgreSQL 17 (`etp_migration`), Flyway V1–V5, Apache Commons CSV |
| `capabilities/etp-exports` | Java 21, Spring Boot 4.0.0, Apache POI. No database |
| `capabilities/postgresql` | PostgreSQL 17 image and a capability contract. Not a Java service |
| `capabilities/kafka` | Apache Kafka 3.8.1, KRaft, single local broker |
| `capabilities/redis` | Redis 7.4 |

---

## 2. Core mental model

Four ideas, kept separate:

| Concept | Question it answers | In this repository |
|---------|---------------------|--------------------|
| **Capability contract** | What does this reusable technology provide? | Stored on a capability version as inline JSON and/or an external location |
| **Application definition** | What does this application need? | Tables and JPA entities exist. No REST API and no Core UI workflow |
| **Application instance** | Where and how is this application running? | Not implemented |
| **Source code** | How is the business behavior written? | Normal application source. ETP does not generate it yet |

```text
Requirement
  → Capability discovery
  → Capability selection
  → Dependency resolution
  → Compatibility, security, and NFR checks
  → Application definition
  → Application composition
  → Generated application foundation
  → Business application
```

Only the first hop exists as a product surface: the Capability Registry and the read-only Core UI list. Composition and generation are target steps.

**A capability is not a business application.** A capability is a building block ETP owns. A business application is composed from selected capability versions and owns the customer’s domain behavior. Registering IAM, PostgreSQL, or Kafka does not create a business product.

The capability catalog is platform-owned and can exist before any customer application does.

---

## 3. Application mental model

An application is a consumer of capabilities. It is not a copy of the platform.

```text
Customer
  └── Application
        ├── metadata            name, code, ownership
        ├── functions           API, event, UI, SFTP — each function chooses its own
        └── capability requirements
              ├── capability code
              ├── exact version
              └── configuration intent
```

The definition says **what** is required. The environment says **where** it runs.

| Belongs in the application definition | Belongs in the environment |
|---------------------------------------|----------------------------|
| `etp.postgresql` is required | JDBC host, database name, password |
| API calls must be authenticated | Keycloak issuer and client secret |
| These events will be published | Kafka bootstrap servers |
| This data may be cached | Redis host and password |

Secrets are not fields of the definition.

Target composition flow, not implemented:

```text
Customer selects capability versions
        ↓
Application composition
        ↓
ETP Customer UI          (separate from Core UI; not built)
        ↓
Build and package
        ↓
Deployable runtime
        ↓
Business application
```

Customer-specific business rules stay in the application. They do not move into Core UI because the customer selected a capability.

The link that will record “this definition uses this capability version” is the `application_capability` table. It is unused by any service or screen today. JSON stored on the definition does not replace that link.

---

## 4. Logical platform components

These are ownership boundaries. They are not a requirement to deploy one process per row.

| Component | Role | Today |
|-----------|------|--------|
| ETP Core UI | Internal console for platform operators | Shell, login, read-only capability catalog. Other routes are placeholders |
| ETP Customer UI | Customer-facing administration, customer branding | Not built |
| ETP Platform API | Platform metadata services | `etp-platform-service`: health, platform info, Capability Registry |
| Capability Registry | Catalog, versions, contracts, dependencies | REST API implemented. Core UI is read-only |
| Application Registry | Customers, applications, definitions | Tables and entities only. No API |
| Configuration | Environment and capability binding | `platform_configuration` is schema only |
| Entitlement | What a customer may consume | V14 tables only. No service and no screen |
| Platform metadata store | System of record for the rows above | PostgreSQL `etp_platform` |
| Capability validation | Contract shape, compatibility, security, NFR | Base JSON Schema for an inline contract. No compatibility engine |
| IAM | Authentication and authorization | `capabilities/etp-iam` plus Keycloak. A capability, not a business application |
| PostgreSQL, Kafka, Redis | Shared infrastructure capabilities | Local Compose and contracts. Applications connect directly. Kafka and Redis have no Java consumer in this repo |
| Migration | File to staging to promotion | `capabilities/etp-migration` |
| Exports | API response to CSV or XLSX | `capabilities/etp-exports` |
| API Gateway, API Catalog, API-to-database, workflow | Target capabilities | No module |
| Audit | Record of metadata changes | Writes on registry lifecycle. No query API. Core UI Audit page is a placeholder |
| Application composition | Turn a definition into a foundation | Not implemented |
| Deployment / runtime | Place an instance in an environment | Not implemented |

---

## 5. Repository structure

What is in the tree today:

```text
etp-foundation/
├── ETP_CONCEPTUAL.md
├── ETP-ARCHITECTURE.md
├── ETP_ENGINEERING_MODEL.md          this file
├── ETP_CURRENT_DATA_MODEL.md
├── ETP_APIS.md
├── ETP_DEPLOYMENT.md
├── ETP_CAPABILITY_DEFINITION.md
├── ETP_HOW_TO.md
├── CLAUDE.md
├── etp-platform-service/             platform metadata API and registry
│   └── docs/ETP_CORE_UI_DESIGN_GUIDELINES.md
├── etp-core-ui/                      internal console
└── capabilities/
    ├── etp-iam/
    ├── etp-migration/
    ├── etp-exports/etp-exports/
    ├── postgresql/                   contract and deployment notes
    ├── kafka/
    └── redis/
```

Platform service packages:

```text
com.etp.platform
├── core                 health, platform info, platform_version entity
├── capability           catalog, lifecycle, contract validation, registry API
├── application          customer and application entities (no repositories, no API)
├── common               ApiResponse, errors, audit
├── environment          database health indicator
└── security             stateless filter chain, CORS
```

There is no `composition` package.

Core UI features:

```text
src/
├── app                  router
├── features/auth        login, session, route protection
├── features/capabilities
├── layouts/coreShell    header, sidebar, placeholders
├── components/ui        ETP* components
└── services/apiClient   platform HTTP client
```

Capability code lives under `capabilities/`. Do not put a new reusable capability inside `etp-platform-service` or inside one business application.

---

## 6. Architecture guardrails

1. **Capability ownership.** ETP owns reusable technology. The business application owns domain behavior and domain data. A capability does not become a business application.
2. **Explicit versions.** Capabilities and application definitions are versioned. A dependency points at a capability version id, not at “latest.”
3. **Explicit contracts.** What a version provides is a machine-readable contract. The V1 registry checks inline documents against the base JSON Schema.
4. **Metadata versus business data.** `etp_platform` holds platform metadata only. `etp_iam` holds identity and authorization. `etp_migration` holds the migration control plane. Business tables stay in the application database.
5. **API-first UI.** Core UI calls HTTP APIs. It does not open a database connection.
6. **Technology behind the capability.** Keycloak, PostgreSQL, Kafka, and Redis sit behind a capability contract. Callers depend on the contract, not on a private copy of the technology.
7. **Security is not optional.** Composition and generated code, when they exist, cannot bypass authentication or authorization. Today the platform registry does not enforce JWT. That is a gap to close, not a pattern to copy.
8. **One active version.** Only one `ACTIVE` version of a capability. Its content does not change. Ship a new version.
9. **No delete for the catalog.** Deprecation, retirement, and cancellation are lifecycle transitions.
10. **Intent versus environment.** Application configuration names the capability and the intent. Hosts, ports, and secrets come from the environment and are not committed.
11. **Logical components are not microservices.** The table in §4 does not require a new deployable for every row.
12. **Generated applications stay source code.** When generation exists, the result is normal maintainable code, not an opaque binary.
13. **Correlation.** A call that crosses a process should carry a correlation id. The audit table has the column. The registry writer does not fill it yet.
14. **Validation before publication.** A definition is not composed or published until it has passed validation. That gate is not implemented.
15. **Documentation lives at the root.** Platform architecture, this model, the data model, APIs, deployment, and the capability definition are not copied back into a module.
