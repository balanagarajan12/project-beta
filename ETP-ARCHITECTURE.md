# ETP Architecture
## Canonical Architecture & Engineering Context

**Status:** Living architecture document  
**Purpose:** Architecture and engineering context for ETP. Start with [ETP_CONCEPTUAL.md](ETP_CONCEPTUAL.md) for what ETP is. Companion documents at the repository root cover the data model, APIs, deployment, capability definition, and local configuration. `CLAUDE.md` is the handoff for coding agents.

These files live only at `etp-foundation/` (the repository root). Do not copy them back into `etp-platform-service/` or a capability folder. Module READMEs and capability runbooks stay next to the code they describe.

| Document | Role |
|----------|------|
| [ETP_CONCEPTUAL.md](ETP_CONCEPTUAL.md) | What ETP is, who it is for, and why capabilities exist |
| [ETP-ARCHITECTURE.md](ETP-ARCHITECTURE.md) | Target architecture and what the repository implements today |
| [ETP_ENGINEERING_MODEL.md](ETP_ENGINEERING_MODEL.md) | Technology stack, mental models, logical components, repository layout, guardrails |
| [ETP_CURRENT_DATA_MODEL.md](ETP_CURRENT_DATA_MODEL.md) | Databases and tables that exist in code |
| [ETP_APIS.md](ETP_APIS.md) | HTTP APIs implemented in this repository |
| [ETP_DEPLOYMENT.md](ETP_DEPLOYMENT.md) | Deployment models and supporting infrastructure |
| [ETP_CAPABILITY_DEFINITION.md](ETP_CAPABILITY_DEFINITION.md) | Capability contract, lifecycle, and definition of done |
| [ETP_HOW_TO.md](ETP_HOW_TO.md) | Step-by-step local configuration |
| [CLAUDE.md](CLAUDE.md) | Instructions for Claude and other coding agents |

---

## Current implementation in this repository

The sections after this one describe the **target** operating model. This section records what the code actually contains. When they differ, the code and this section win for “what runs today.”

### Repository layout

```text
etp-foundation/
├── etp-platform-service/     Spring Boot platform metadata and Capability Registry
├── etp-core-ui/              Internal platform console (React)
└── capabilities/
    ├── etp-iam/              Authentication and authorization service + Keycloak
    ├── etp-migration/        CSV/SFTP → staging → reconciliation → promotion
    ├── etp-exports/          JSON API → CSV/XLSX download
    ├── postgresql/           PostgreSQL 17 capability contract and compose guidance
    ├── kafka/                Kafka 3.8.1 (KRaft) local broker
    └── redis/                Redis 7.4 local cache
```

### What is running code

| Area | State |
|------|--------|
| Platform service (Java 21, Spring Boot 4.1), Flyway V1–V14, database `etp_platform` | Implemented |
| Capability Registry REST API, lifecycle, inline JSON contracts, audit writes | Implemented |
| Core UI shell, login, read-only capability list and details | Implemented. Write actions are on screen and disabled. Other shell routes are placeholders |
| ETP IAM (`etp.iam`): login, client-credentials token, authorization check, Keycloak | Implemented |
| Migration control plane, filesystem and single-file SFTP CSV, staging, reconciliation, promotion | Implemented. Execute, reconcile, promotion preparation, and promotion return HTTP 202 and finish in the background. One phase does not start the next. Repository Flyway is V1–V11; startup applies pending scripts. Core UI has no migration screens |
| Export service `POST /api/v1/exports` | Implemented |
| PostgreSQL, Kafka, and Redis local infrastructure (Compose) | Implemented as infrastructure, not as request-path services |
| Application Registry APIs, composition, generated applications | Not implemented |
| Operating-model and entitlement tables | Schema only (Flyway V14). No Java API |
| API Gateway, API Catalog, API-to-DB service | Target capabilities. No service in this repository |
| Platform API JWT enforcement | Not enforced. `SecurityConfig` permits the registry and does not install a JWT resource server. Core UI still sends the IAM Bearer token |
| Customer UI and business-application authentication | Not implemented |

UAM in this repository is a **local canonical schema** used by the migration capability to demonstrate promotion. It is not a production UAM product.

### Authentication that exists today

Phase 1 uses one login screen on ETP Core UI. Core UI does not call Keycloak and does not hold the Keycloak client secret.

```text
ETP Core UI
    → POST /api/v1/auth/login
ETP IAM
    → Keycloak password grant (confidential client, server-side)
    → JWT
ETP IAM → Core UI session → /dashboard
```

`POST /api/v1/auth/token` is service-to-service `client_credentials` only. It is not the human login API. OIDC Authorization Code + PKCE, token refresh, and `/auth/me` are not implemented.

After login, Core UI keeps `{ user, accessToken, expiresAt, rememberMe }` in `localStorage` when Remember Me is checked, and in `sessionStorage` otherwise. The password is not stored. Logout clears that browser session only. A development mock (`VITE_MOCK_AUTH_ENABLED`) can skip IAM entirely.

### What Core UI actually operates

The console is React 19 on Vite. Authenticated routes sit inside the core shell.

| Route | Backed by |
|-------|-----------|
| `/login` | IAM `POST /api/v1/auth/login`, or the mock when enabled |
| `/capabilities` | `GET /api/v1/capabilities`. Search, category, status, and paging run in the browser |
| `/capabilities/:capabilityId` | `GET /api/v1/capabilities/{id}`. Version selector plus Overview, Contract, and Dependencies |
| `/dashboard`, `/customers`, `/applications`, `/platform/configuration`, `/platform/version`, `/audit` | Placeholder copy. No platform API |

The details view does not render implementation metadata. A dependency is shown as a shortened version id, not as a capability code. The structured contract view omits the document’s `dependencies` section; Raw JSON shows the full document.

Create, edit, clone, lifecycle, and delete controls are rendered and disabled. The UI never calls POST, PUT, or PATCH. Application, customer, audit, and entitlement tables have entities or SQL only. Core UI does not read them.

### Ownership rule already in force

`etp_platform` stores platform metadata. `etp_iam` stores identity and authorization data. Migration stores its control plane in `etp_migration`. Business tables (including a local `uam` schema) are owned by the consuming application or by the operator-created target, not by the platform catalog.

---

## 1. Purpose and Vision

ETP is a capability-based application platform intended to provide reusable platform capabilities on which business applications can be built, configured, deployed, and operated.

The platform separates:

1. **Platform capabilities** — reusable technical capabilities provided by ETP.
2. **Business applications** — applications that own their business/domain behavior.
3. **Platform administration** — ETP-owned administration and operational experiences.
4. **Customer administration** — tenant/customer-facing administration experiences.
5. **Environment infrastructure** — environment-specific runtime details and secrets.

The objective is to avoid rebuilding common technical infrastructure for every business application while preserving clear ownership of business logic and domain data.

---

# 1. Core Operating Model

ETP follows a capability-based model.

A business application declares the platform capabilities it requires.

Example:

```text
UAM
 ├── etp.api-gateway
 ├── etp.api-catalog
 ├── etp.postgresql
 ├── etp.api-db-service
 ├── etp.kafka
 ├── etp.redis
 └── etp.iam
```

The application expresses **what capability it needs and its capability-specific configuration intent**.

ETP/environment configuration supplies **where and how that capability is physically provided in a particular environment**.

This distinction is fundamental.

### Application-level intent

Examples:

- required capability: `etp.postgresql`
- database/schema requirements
- API authentication mode
- Kafka topic intent
- cache usage/configuration intent

### Environment-level infrastructure

Examples:

- PostgreSQL endpoint
- database credentials
- Kafka bootstrap servers
- Redis endpoint
- infrastructure-specific connection details
- environment-specific secrets

Secrets must not be stored as plaintext in the application definition. ETP should store references to secret/configuration management where appropriate.

---

# 2. ETP Layers

Conceptually ETP consists of several layers.

```text
                    ┌─────────────────────────┐
                    │    Business Apps        │
                    │                         │
                    │ UAM / Future Apps       │
                    └────────────┬────────────┘
                                 │
                  consume ETP capabilities
                                 │
        ┌────────────────────────┴────────────────────────┐
        │              ETP Capabilities                   │
        │                                                 │
        │ API Gateway │ API Catalog │ IAM │ PostgreSQL   │
        │ Kafka │ Redis │ API-DB Service │ Transformation│
        └────────────────────────┬────────────────────────┘
                                 │
        ┌────────────────────────┴────────────────────────┐
        │          ETP Platform Services / Core           │
        │                                                 │
        │ Capability Registry │ Application Registry      │
        │ Configuration │ Deployment │ Entitlement        │
        │ Platform Operations │ Environment Management    │
        └─────────────────────────────────────────────────┘
```

The exact physical decomposition may evolve; the logical ownership boundaries should remain clear.

---

# 3. ETP Core UI

**ETP Core UI** is the ETP-owned platform administration experience.

It is intended for platform administrators and operators.

Responsibilities include:

- customer/tenant management
- capability registry management
- application registry management
- deployments and environments
- platform configuration
- entitlements/subscriptions
- pricing/plans
- platform operations
- platform-level administration

ETP Core UI is **not** the customer business application UI.

---

# 4. ETP Customer UI

**ETP Customer UI** is a separate customer-facing administration experience.

It is tenant-specific/configured for the customer and should not automatically inherit the ETP Core UI visual identity.

Customer UI may use customer-specific:

- branding
- colors
- fonts
- logos
- layout
- component styling
- UX conventions

The architectural separation between ETP platform administration and customer-facing administration must be preserved.

---

# 5. Business Applications

Business applications are applications built on top of ETP capabilities.

Examples include UAM and future domain applications.

A business application:

- declares required ETP capabilities
- owns its domain model
- owns its business rules
- owns its business workflows
- owns its domain database schema
- owns its tables/entities
- owns repositories
- owns database migrations
- implements domain-specific APIs/functions

ETP provides reusable technical capabilities and frameworks but does not take ownership of the business application's domain model.

---

# 6. Application Model

The application model should represent an application as a consumer of capabilities.

Conceptually:

```text
Application
    │
    ├── Application Metadata
    │
    ├── Application Functions / Interfaces
    │
    └── Capability Requirements
             │
             ├── Capability
             ├── Version/Compatibility
             └── Configuration Intent
```

The application model should not contain environment-specific infrastructure details.

For example:

```text
Application:
    UAM

Capability:
    etp.postgresql

Intent:
    database required
    schema ownership = UAM
```

Environment configuration can then map that requirement to the actual PostgreSQL service.

---

# 7. Capability Model

A capability is a reusable technical service/functionality that ETP exposes to applications.

Examples:

```text
etp.api-gateway
etp.api-catalog
etp.postgresql
etp.api-db-service
etp.kafka
etp.redis
etp.iam
etp.integration-transformation
```

A capability should have a clear contract covering, as applicable:

- capability identity
- capability type/category
- supported versions
- interface/API
- configuration model
- lifecycle
- dependencies
- provisioning/runtime behavior
- security model
- operational characteristics

The capability registry is responsible for making capabilities discoverable and manageable.

---

# 8. Execution Mechanisms

Applications may expose or consume functionality through different execution mechanisms.

The architecture should not assume that every function is an HTTP API.

Examples include:

```text
API
Event
UI
SFTP
```

The execution mechanism belongs with the interface/functionality being exposed or invoked rather than being treated as a single application-wide property.

An application can therefore contain multiple functions with different execution mechanisms.

Example:

```text
UAM
 ├── Address API       → API
 ├── Address Event     → Event
 ├── Admin Screen      → UI
 └── Bulk Import       → SFTP
```

This keeps the model extensible for future execution mechanisms.

---

# 9. API Gateway Capability

The API Gateway is an ETP platform capability.

It provides the common gateway layer for APIs exposed through ETP.

Responsibilities may include:

- routing
- authentication integration
- authorization integration
- API policies
- throttling/rate limiting
- request/response controls
- API exposure
- traffic management
- observability

Business applications should not need to independently implement common gateway concerns.

---

# 10. API Catalog Capability

The API Catalog provides discoverability and management of APIs exposed through ETP.

It should support concepts such as:

- API registration
- API metadata
- API versions
- API lifecycle
- API documentation/discovery
- ownership
- visibility
- consumption information

The API Catalog is distinct from the API Gateway:

```text
API Catalog
    = discover/manage APIs

API Gateway
    = execute/manage API traffic
```

They may integrate closely but represent different concerns.

---

# 11. API Processing

API processing refers to the processing that occurs around API execution, beyond basic gateway routing.

Potential concerns include:

- request validation
- mapping
- transformation
- enrichment
- business-rule processing
- orchestration
- persistence interaction
- response transformation

Where appropriate, these concerns should be implemented using reusable ETP processing capabilities/frameworks rather than duplicating infrastructure logic across applications.

---

# 12. Integration Transformation

The term **Integration Transformation** is used for the transformation capability previously discussed as TMF Processing / Transformation.

It provides reusable transformation and integration-processing functions.

Potential responsibilities include:

- request/response transformation
- data mapping
- format conversion
- enrichment
- integration-specific transformation
- protocol/data mediation where appropriate

Business-specific transformation rules remain owned by the consuming business application when they are domain-specific.

---

# 13. API-to-DB Service Capability

The API-to-DB service is a reusable framework/service capability for applications that need structured access between APIs and databases.

It may provide reusable mechanisms for:

- database interaction
- transaction handling
- validation
- business rules
- transformations
- reusable service logic
- common persistence patterns

However, it must not take ownership of the business application's domain.

The business application remains responsible for:

- domain entities
- schema
- tables
- repositories
- migrations
- domain-specific rules

The capability provides the technical framework; the application provides the domain behavior.

---

# 14. PostgreSQL Capability

`etp.postgresql` is a platform infrastructure capability.

An application declares that it requires PostgreSQL.

The application owns its business data model.

Example:

```text
UAM
 └── etp.postgresql
       └── UAM-owned schema
            ├── address
            ├── location
            ├── related domain entities
            └── application tables
```

ETP provides access to PostgreSQL as a capability; it should not impose ownership of the UAM domain schema.

Environment-specific connection details belong to environment configuration/secret management.

---

# 15. Kafka Capability

`etp.kafka` provides event streaming/messaging capabilities.

Applications can use Kafka for event-driven execution.

The application owns its event semantics and domain events.

ETP provides the reusable Kafka infrastructure/capability and common integration mechanisms.

Example:

```text
UAM
 └── etp.kafka
      ├── AddressCreated
      ├── AddressUpdated
      └── AddressDeleted
```

Topic naming, event contracts, retention, and operational policies should follow platform standards while preserving application/domain ownership.

---

# 16. Redis Capability

`etp.redis` provides caching and fast-access data capabilities.

Typical use cases include:

- application caching
- frequently accessed reference data
- short-lived state
- performance optimization

Redis should not automatically become the system of record for business data.

The business application's persistence model remains its responsibility.

---

# 17. IAM Capability

`etp.iam` provides identity and access-management integration.

For APIs, IAM should support mechanisms such as OAuth 2.0 / OpenID Connect where appropriate.

Responsibilities may include:

- authentication
- token validation
- identity propagation
- authorization integration
- roles/scopes/permissions
- application/client identity

The API Gateway and IAM capability should work together.

Conceptually:

```text
Client
  │
  ▼
IAM
  │ token
  ▼
API Gateway
  │
  ▼
Business Application
```

Application-specific authorization decisions remain part of the application's security/domain requirements.

---

# 18. UAM — Unified Addressability Management

UAM is a **business application built on ETP**.

It is not itself an ETP platform capability.

The initial UAM implementation is expected to support TMF APIs including:

- TMF 645
- TMF 673
- TMF 674
- TMF 675

UAM should consume appropriate ETP capabilities rather than implementing duplicate platform infrastructure.

Initial capability dependencies discussed:

```text
UAM
 ├── API Gateway
 ├── API Catalog
 ├── PostgreSQL
 ├── API-to-DB Service
 ├── Kafka
 ├── Redis
 ├── IAM
 └── Integration Transformation
```

UAM owns its domain model and business behavior.

---

# 19. UAM Logical Architecture

A conceptual flow is:

```text
External Consumer
       │
       ▼
   IAM / OAuth
       │
       ▼
  API Gateway
       │
       ▼
    UAM APIs
       │
       ├──────────────► Integration Transformation
       │
       ▼
 API-to-DB Service
       │
       ▼
 UAM PostgreSQL
       │
       ├──────────────► Redis
       │
       └──────────────► Kafka
```

The exact implementation should be determined during detailed design.

---

# 20. Data Ownership Principle

A critical ETP principle is:

> The platform owns platform data. The business application owns business data.

ETP platform data may include:

- applications
- capabilities
- environments
- deployments
- subscriptions
- entitlements
- platform configuration

UAM data belongs to UAM.

This prevents the platform database from becoming a generic dumping ground for every business application's domain model.

---

# 21. Configuration Separation

Configuration should be separated into two categories.

### Application configuration

Describes application intent.

Examples:

- required capabilities
- capability-specific settings
- API behavior
- application-level feature configuration

### Environment configuration

Describes deployment/runtime specifics.

Examples:

- endpoints
- infrastructure identifiers
- credentials
- secrets
- environment-specific overrides

A useful conceptual model:

```text
Application Definition
        │
        │ capability requirement
        ▼
Capability
        │
        │ environment binding
        ▼
Environment Configuration
        │
        ▼
Actual Infrastructure
```

ETP should store secret references rather than plaintext secrets wherever applicable.

---

# 22. Platform vs Application Boundary

The following rule should guide architectural decisions:

### Put it in ETP when it is:

- reusable across multiple applications
- infrastructure/platform-oriented
- a common technical concern
- independently deployable/provisionable
- governed consistently across applications

### Put it in the Business Application when it is:

- domain-specific
- business-rule-specific
- tied to the application's business entities
- part of the application's business workflow
- unique to the application's domain

Avoid turning ETP into a monolithic business-logic platform.

---

# 23. Development Principles

All contributors should:

1. Understand the architecture before modifying shared platform components.
2. Preserve capability/application ownership boundaries.
3. Avoid duplicating existing platform capabilities.
4. Avoid placing business-domain logic into generic platform services.
5. Avoid storing secrets in source control.
6. Keep configuration environment-aware.
7. Add automated tests for meaningful behavior.
8. Keep changes focused and reviewable.
9. Use Git branches and pull requests for team development.
10. Update architecture documentation when a significant architectural decision changes.

---

# 24. Git and Team Development Model

Each developer should have an independent local clone.

Do not share a single working directory between developers.

Recommended flow:

```text
main
  │
  ├── feature/uam-api
  ├── feature/uam-db
  ├── feature/api-gateway
  ├── feature/kafka
  └── feature/customer-ui
```

Typical workflow:

```bash
git pull
git checkout -b feature/<name>

# implement
# test

git add .
git commit -m "<message>"
git push -u origin feature/<name>
```

Changes should normally enter the main development branch through pull requests and review.

The exact branching model can evolve as the team grows.

---

# 25. AI-Assisted Development

AI coding tools are development tools, not the source of architectural truth.

Cursor, Claude Code, or other coding agents may be used against the same Git repository.

The repository documentation is the source of truth.

Recommended agent behavior:

1. Read this document before significant architectural changes.
2. Inspect the existing implementation.
3. Identify affected modules and dependencies.
4. Propose a plan for non-trivial changes.
5. Avoid changing unrelated areas.
6. Run relevant tests/builds.
7. Explain significant architectural changes.
8. Update documentation when the architecture changes.

For Claude Code, `CLAUDE.md` should provide concise project instructions and point to this document.

For Cursor, project rules should point developers/agents to this architecture document.

---


# 26. ETP Commercial Models

ETP supports three commercial/consumption models.

The commercial model determines **what the customer is entitled to consume**. It does not create three different technical architectures.

All three models use the same underlying:

- Capability Registry
- Application Model
- Deployment Model
- Environment Model
- Entitlement model
- ETP platform services

The commercial model controls entitlement and consumption scope.

## 23.1 Application-Based — “I want this application”

**Customer buys:** A specific Business Application.

**Customer can:** Use the application and the ETP capabilities required/entitled for that application.

```text
Customer
   ↓
Application Subscription
   ↓
Business Application
   ↓
Required ETP Capabilities
```

Example:

```text
Customer
   ↓
Unified Addressability Management subscription
   ↓
UAM
   ↓
Required ETP Capabilities
```

The customer does not automatically receive unrestricted access to every ETP capability.

This model is:

> **“Give me this application.”**

## 23.2 Capability-Based — “Give me these building blocks”

**Customer buys:** A set of ETP Capabilities.

**Customer can:** Use the entitled capabilities across multiple business applications.

```text
Customer
   ↓
Capability Subscription
   ↓
IAM + API Gateway + PostgreSQL + Kafka + Redis + ...
   ↓
Application A
Application B
Application C
```

The customer can use the entitled capabilities across multiple applications.

This directly supports the ETP vision of reusable capabilities supporting multiple applications.

This model is:

> **“Give me these building blocks.”**

## 23.3 Full ETP Platform — “Give me the platform”

**Customer buys:** The ETP Platform.

**Customer can:** Access a broad/full capability ecosystem and compose multiple applications using the platform.

```text
Customer
   ↓
ETP Platform Subscription
   ↓
Broad / Full Capability Entitlement
   ↓
Application Builder / Composition
   ↓
Application A
Application B
Application C
...
```

The customer gets broad access to the ETP platform and its capability ecosystem, allowing them to discover, compose, and build multiple applications.

This model is:

> **“Give me the platform.”**

## 23.4 Commercial Model Principle

The three models represent different **commercial entitlement scopes**, not different technical architectures.

```text
                 ETP Commercial Model
                         │
          ┌──────────────┼──────────────┐
          │              │              │
     Application       Capability      Full ETP
       Based             Based         Platform
          │              │              │
          └──────────────┼──────────────┘
                         │
                         ▼
              Same ETP Technical Model
                         │
        ┌────────────────┼────────────────┐
        │                │                │
 Capability Registry  Application     Deployment
                      Model            Model
```

Therefore, the commercial model must not result in separate technical architectures. It determines what the customer can discover, consume, compose, deploy, or administer.

# 27. ETP Operating Model

ETP is operated as a shared platform that provides governed, reusable capabilities to multiple business applications and customers/tenants.

The operating model separates responsibilities between:

- **ETP Platform**
- **Business Application**
- **Customer/Tenant**
- **Environment/Infrastructure**
- **Operations/Platform Administration**

The objective is to provide strong platform governance without centralizing business-domain ownership.

## 23.1 Platform Responsibility

ETP is responsible for providing and operating common platform capabilities.

Examples include:

- capability registration and lifecycle
- application registration
- environment management
- deployment orchestration/integration
- platform configuration
- entitlement/subscription management
- security/IAM integration
- API Gateway
- API Catalog
- shared infrastructure capabilities
- platform observability and operations
- platform-level policies and governance

ETP should provide standardized consumption patterns so applications do not need to independently solve common infrastructure concerns.

## 23.2 Business Application Responsibility

A business application is responsible for its business/domain behavior.

Responsibilities include:

- domain model
- business rules
- business workflows
- domain APIs
- domain events
- database schema
- entities
- repositories
- migrations
- application-specific configuration intent
- application-level authorization rules
- application testing

The application consumes ETP capabilities through defined contracts.

## 23.3 Customer/Tenant Responsibility

A customer/tenant consumes applications and platform capabilities according to its subscription, entitlement, configuration, and access rights.

Customer-specific concerns may include:

- tenant configuration
- users and access
- application configuration within permitted boundaries
- customer-specific business data
- customer-specific UI branding
- application usage

The exact tenant isolation model must be defined per capability and application.

## 23.4 Platform Administrator

Platform administrators operate the ETP platform.

Typical responsibilities:

```text
Platform Administrator
    │
    ├── Customers / Tenants
    ├── Capabilities
    ├── Applications
    ├── Environments
    ├── Deployments
    ├── Entitlements
    ├── Plans / Subscriptions
    ├── Platform Configuration
    └── Operations
```

These activities belong primarily in ETP Core UI and associated platform services.

## 23.5 Application Team

The application team owns the lifecycle of the business application.

```text
Application Team
    │
    ├── Application source
    ├── Domain model
    ├── APIs
    ├── Events
    ├── Database schema
    ├── Business logic
    ├── Tests
    └── Application release
```

The application team should not need platform administrator privileges merely to develop or maintain its business logic.

## 23.6 Capability Team

A capability team owns a reusable ETP capability.

Examples:

```text
API Gateway Team
PostgreSQL Capability Team
Kafka Capability Team
IAM Capability Team
API Catalog Team
```

The capability team is responsible for:

- capability implementation
- capability contract
- supported versions
- configuration contract
- operational behavior
- security requirements
- compatibility
- documentation
- lifecycle/versioning

Applications consume the capability without taking ownership of its underlying implementation.

## 23.7 Separation of Duties

The operating model should preserve separation between:

```text
Platform Governance
        │
        ▼
ETP Platform

Business Ownership
        │
        ▼
Business Applications

Infrastructure Operation
        │
        ▼
Environment / Infrastructure
```

A platform administrator should not need to understand or modify an application's domain implementation to provision or operate the platform.

Likewise, an application developer should not need unrestricted access to underlying shared infrastructure.

---

# 28. ETP Deployment Model

ETP supports different deployment/tenancy models independently of the commercial model.

The primary deployment models are:

```text
Deployment Model
│
├── Dedicated
│
└── Shared
```

The commercial model determines **what the customer is entitled to consume**.

The deployment model determines **how the application and supporting capabilities are physically/runtime deployed and isolated**.

Therefore:

```text
Commercial Model
      +
Deployment Model
      ↓
Customer's ETP Service
```

A customer purchasing an application does not inherently imply dedicated deployment, and a customer purchasing the full ETP platform does not inherently imply shared deployment. The appropriate deployment model depends on requirements such as scale, isolation, security, compliance, cost, and operational model.

## 25.1 Dedicated Deployment

In a dedicated deployment, the application and/or required runtime resources are dedicated to a specific customer, tenant, application, or deployment boundary.

```text
Customer / Tenant
       │
       ▼
Dedicated Application
       │
       ├── Dedicated Runtime
       ├── Dedicated Data Resources
       └── Dedicated Capability Resources*
```

`*` Whether a capability is dedicated or shared is capability-specific.

Dedicated deployment can provide stronger isolation and greater control, at the cost of potentially higher infrastructure and operational overhead.

Typical drivers include:

- high-isolation requirements
- regulatory/compliance requirements
- dedicated performance requirements
- customer-specific infrastructure requirements
- large-scale deployments

## 25.2 Shared Deployment

In a shared deployment, multiple applications and/or customers share platform/runtime infrastructure while maintaining logical isolation.

```text
                Shared ETP Environment
                         │
        ┌────────────────┼────────────────┐
        │                │                │
    Customer A       Customer B       Customer C
        │                │                │
      App A            App B            App C
        │                │                │
        └────────────────┼────────────────┘
                         │
              Shared Platform Capabilities
```

Shared deployment is intended to improve:

- infrastructure utilization
- operational efficiency
- scalability
- standardization
- cost efficiency

Isolation must be explicitly implemented and governed.

Potential isolation mechanisms include:

- tenant identifiers
- database/schema isolation
- separate databases
- namespace isolation
- network policies
- identity and access controls
- resource quotas
- application-level authorization

## 25.3 Capability-Level Deployment

Dedicated/shared deployment should not necessarily be treated as one binary choice for the entire ETP stack.

Different capabilities may have different deployment characteristics.

For example:

```text
Customer UAM
    │
    ├── Application Runtime → Dedicated
    ├── PostgreSQL          → Dedicated
    ├── API Gateway         → Shared
    ├── IAM                 → Shared
    ├── API Catalog         → Shared
    ├── Kafka               → Shared
    └── Redis               → Shared
```

Another customer may require a different combination:

```text
Customer UAM
    │
    ├── Application Runtime → Dedicated
    ├── PostgreSQL          → Dedicated
    ├── API Gateway         → Dedicated
    ├── IAM                 → Dedicated
    └── remaining platform capabilities → Shared
```

The deployment architecture should therefore support capability-specific deployment and isolation policies.

## 25.4 Commercial Model × Deployment Model

The commercial and deployment dimensions are independent.

| Commercial Model | Dedicated Deployment | Shared Deployment |
|---|---|---|
| Application-Based | Supported | Supported |
| Capability-Based | Supported | Supported |
| Full ETP Platform | Supported | Supported |

The actual combinations offered commercially can be constrained by product packaging, security, compliance, infrastructure availability, or operational policy.

The architectural principle is:

> **Commercial entitlement and physical/runtime deployment are separate dimensions.**

## 25.5 Deployment Flow

The deployment flow remains common across commercial models:

```text
Customer Entitlement
        │
        ▼
Application / Capability Selection
        │
        ▼
Application Definition
        │
        ▼
Capability Requirements
        │
        ▼
Target Environment
        │
        ▼
Deployment / Provisioning
        │
        ▼
Dedicated or Shared Runtime
        │
        ▼
Running Application
```

The entitlement check determines whether the customer is allowed to consume the selected application/capability.

The deployment model determines how the selected application and capabilities are provisioned and isolated.

# 29. End-to-End ETP Operating and Deployment Flow

The complete conceptual flow is:

```text
                 ┌─────────────────────┐
                 │ Platform Admin      │
                 │ ETP Core UI         │
                 └──────────┬──────────┘
                            │
                   register/configure
                            │
                            ▼
                 ┌─────────────────────┐
                 │ ETP Platform        │
                 │                     │
                 │ Application         │
                 │ Capability          │
                 │ Environment         │
                 │ Entitlement         │
                 └──────────┬──────────┘
                            │
                     deploy application
                            │
                            ▼
                 ┌─────────────────────┐
                 │ Environment         │
                 │                     │
                 │ Capability Bindings │
                 └──────────┬──────────┘
                            │
                            ▼
       ┌────────────────────────────────────────┐
       │ Runtime Capabilities                   │
       │                                        │
       │ API Gateway │ IAM │ PostgreSQL         │
       │ Kafka │ Redis │ API Catalog            │
       │ Integration Transformation             │
       └────────────────────┬───────────────────┘
                            │
                            ▼
                 ┌─────────────────────┐
                 │ Business Application│
                 │                     │
                 │ UAM / Future Apps   │
                 └─────────────────────┘
```

This flow reinforces the central ETP principle:

> The platform manages and provides capabilities; the application consumes those capabilities and owns its business behavior.

---

# 30. Operational Model

ETP operations should provide visibility across both platform capabilities and deployed applications.

Important operational concerns include:

- deployment status
- capability health
- application health
- environment health
- API availability
- event/messaging health
- database availability
- cache health
- authentication/authorization health
- logs
- metrics
- traces
- alerts
- audit events

The exact observability stack is an implementation decision, but observability should be treated as a platform-wide concern rather than reimplemented independently by every application.

## 26.1 Application Operations

Application teams should be able to determine:

- whether their application is deployed
- deployed version
- environment
- capability dependencies
- runtime health
- application logs/metrics
- deployment history
- failures
- rollback status

## 26.2 Platform Operations

Platform operators should be able to determine:

- capability health
- environment health
- deployment status
- resource utilization
- platform-level failures
- tenant/application impact
- security events
- infrastructure dependencies

---

# 31. Operating and Deployment Guardrails

The following principles apply to both operating and deployment models:

1. **Application definitions must remain environment-independent wherever practical.**
2. **Environment-specific infrastructure must be resolved during deployment/configuration.**
3. **Secrets must never be committed to source control.**
4. **Capabilities must expose stable, versioned contracts.**
5. **Applications own domain data and business behavior.**
6. **Platform capabilities own reusable technical infrastructure.**
7. **Deployments should be repeatable and automatable.**
8. **Development, test, and production environments must be independently manageable.**
9. **Application and capability versions should be independently lifecycle-managed where compatible.**
10. **Operational visibility should cover the dependency chain from application to platform capability to infrastructure.**

---



Not every implementation choice is an architecture decision.

Record decisions when they affect:

- platform/application boundaries
- capability contracts
- data ownership
- security model
- configuration model
- integration patterns
- deployment model
- major technology choices
- cross-application standards

When a decision changes, update this document rather than allowing conflicting assumptions to remain in individual conversations.

---

# 32. Current Scope vs Future Evolution

The architecture is intentionally extensible.

Future capabilities may include additional:

- databases
- messaging systems
- caches
- integration protocols
- API mechanisms
- event mechanisms
- UI capabilities
- file transfer capabilities
- observability capabilities
- security capabilities
- workflow/orchestration capabilities

New capabilities should follow the same principle:

```text
Reusable technical capability
            ↓
Registered in ETP
            ↓
Consumed by applications
            ↓
Application owns domain behavior
```

---

# 33. Open / Evolving Areas

The following areas should be treated as implementation/design work rather than assumed to be fully finalized:

- exact physical microservice/module decomposition
- capability provisioning lifecycle
- exact application/capability database schema
- environment binding implementation
- secret-management provider/integration
- complete API Catalog implementation
- exact API Gateway technology/configuration
- detailed IAM provider/configuration
- UAM detailed domain model
- TMF API implementation details
- Kafka topic governance
- Redis key/cache governance
- deployment/orchestration implementation
- entitlement/subscription implementation
- complete ETP Core UI feature set

These should be resolved through explicit architecture/design decisions as implementation progresses.

---

# 34. Golden Rules

The following rules should be treated as the highest-level ETP architectural guardrails:

### Rule 1
**Applications consume capabilities; they do not own platform infrastructure.**

### Rule 2
**Capabilities provide reusable technical functionality; applications provide business functionality.**

### Rule 3
**Application configuration expresses intent; environment configuration supplies runtime specifics.**

### Rule 4
**Never store plaintext secrets in application definitions or source control.**

### Rule 5
**Business applications own their domain data, schema, entities, repositories, and migrations.**

### Rule 6
**ETP Core UI is for platform administration; Customer UI is a separate customer-facing experience.**

### Rule 7
**Execution mechanisms belong to individual functions/interfaces, allowing one application to support API, Event, UI, SFTP, and future mechanisms.**

### Rule 8
**API Catalog and API Gateway are separate concerns.**

### Rule 9
**IAM/security should be a reusable platform capability, with OAuth/OIDC integrated where appropriate.**

### Rule 10
**Git and repository documentation—not an individual developer's AI memory—are the shared source of truth for the team.**

---

## 31. Document Maintenance

This document is a living artifact.

When an architectural decision is changed:

1. update this document at the repository root;
2. update the matching root document (`ETP_CURRENT_DATA_MODEL.md`, `ETP_APIS.md`, `ETP_DEPLOYMENT.md`, `ETP_CAPABILITY_DEFINITION.md`, or `ETP_HOW_TO.md`);
3. update `CLAUDE.md` if agent behavior is affected;
4. communicate the change through the team's normal review process.

Do not add a second copy under `etp-platform-service/`.

The objective is that a new developer—or a new AI coding agent—can clone the repository and understand the intended architecture without depending on private conversation history.
