# ETP — Conceptual Overview

A short introduction to the Enterprise Technology Platform. Read this first. The other root documents go deeper: architecture, data, APIs, deployment, and how to run it locally.

---

## What ETP is

ETP is a capability-based platform. Business applications are built, configured, deployed, and operated on capabilities that ETP provides once and reuses.

Every business application needs the same kinds of technical foundation: a database, identity and access, messaging, caching, an API front door, and a way to move data in. Building that foundation again for each application is slow and produces systems that behave differently.

ETP keeps five concerns apart:

1. **Platform capabilities** — reusable technology ETP provides.
2. **Business applications** — products that own their domain behavior.
3. **Platform administration** — how ETP operators run the platform.
4. **Customer administration** — how a customer’s own administrators work, in that customer’s experience.
5. **Environment infrastructure** — the real hosts, ports, and secrets for a given environment.

```text
Business application  →  uses capabilities  →  ETP provides them
```

The objective is to stop rebuilding common technical infrastructure, without taking ownership of business logic or business data away from the application.

## Purpose

- Give every application the same technical starting point.
- Keep platform concerns separate from business rules.
- Make capabilities discoverable, versioned, and replaceable without rewriting the application.
- Let one capability serve many applications.
- Let platform operators administer the catalog, and let application teams build the product.

## What ETP is not

ETP is not a business application. It does not manage addresses, orders, or any other domain.

ETP is not a low-code tool, an IDE, or a replacement for source control. Applications remain normal software that teams write, test, and deploy.

ETP is not a place to store business data. The platform remembers customers, applications, capabilities, entitlements, and platform configuration. Each application remembers its own records.

ETP is not three different platforms for three ways of selling it. How a customer buys ETP changes what they are entitled to use. It does not change the technical model underneath.

## The main idea

Two roles, kept apart:

| | ETP capability | Business application |
|--|----------------|----------------------|
| Owns | Reusable technology | Domain model, rules, screens, and data |
| Example | `etp.iam`, `etp.postgresql`, `etp.kafka` | UAM and later domain applications |
| Changes when | The platform improves a shared service | The business changes |

A capability can serve many applications. An application must not hide a private copy of identity, database access, or messaging inside itself when ETP already provides that capability.

```text
                 ETP IAM
                    |
        +-----------+-----------+
        |           |           |
   Application A  Application B  Application C
```

Use this test when deciding where something belongs.

**Put it in ETP** when it is reusable across applications, infrastructure-oriented, a common technical concern, deployable on its own, and governed the same way everywhere.

**Put it in the business application** when it is specific to the domain, a business rule, tied to that application’s entities, part of its business workflow, or unique to that domain.

That split stops ETP from turning into one large business-logic platform.

## How an application uses ETP

An application declares the capabilities it needs, and the intent for each one. It does not declare where that capability runs.

```text
UAM
 ├── etp.api-gateway          shared front door for its APIs
 ├── etp.api-catalog          where those APIs are discovered
 ├── etp.postgresql           a database; UAM owns the schema
 ├── etp.api-db-service       a technical framework for API-to-database work
 ├── etp.kafka                address events; UAM owns their meaning
 ├── etp.redis                a cache, not the system of record
 └── etp.iam                  callers must be authenticated
```

UAM (Unified Addressability Management) is the first business application pictured on ETP. It is not itself a capability. Gateway, catalog, and the API-to-database service are part of that target picture. They are not running services in this repository yet.

Application intent is things like “PostgreSQL is required,” “this API uses IAM,” “these events exist,” or “this data may be cached.”

Environment configuration is the PostgreSQL host, the Kafka brokers, the Redis endpoint, and the credentials. The path from definition to a running system is:

```text
Application definition
        |
        |  which capability, and what is intended
        v
Capability
        |
        |  bound in an environment
        v
Environment configuration
        |
        v
Actual infrastructure
```

Secrets are references, not plaintext values stored in the application definition or in source control.

## One application, several ways of working

Not every function of an application is an HTTP API. The way a function runs belongs to that function, not to the application as a whole. One application can mix them:

```text
UAM
 ├── Address API       → API
 ├── Address Event     → Event
 ├── Admin screen      → UI
 └── Bulk import       → SFTP
```

Two capabilities that sound similar stay separate. The **API Catalog** is where APIs are registered and found. The **API Gateway** is where API traffic is routed and controlled. Discovering an API and carrying its traffic are different jobs.

Where data must be reshaped on the way in or out, ETP offers a shared transformation capability. The mapping machinery is platform technology. The meaning of a field still belongs to the application.

## Who is responsible

| Who | Owns |
|-----|------|
| ETP platform | The catalog, capability lifecycle, application records, environments, entitlements, platform configuration, and shared infrastructure |
| Capability team | One capability: its contract, versions, security expectations, operations, and compatibility. Applications use it; they do not own its internals |
| Application team | That product’s source, domain model, APIs, events, schema, business rules, tests, and releases |
| Customer / tenant | Its users, its business data, its branding, and configuration inside what the subscription allows |
| Platform administrator | Customers, capabilities, applications, deployments, plans, and day-to-day platform operations. This work lives in ETP Core UI |

A platform administrator does not need to edit an application’s domain code to run the platform. An application developer does not need unrestricted access to shared infrastructure to build the application.

| Experience | Who it is for |
|------------|----------------|
| ETP Core UI | Platform administrators and engineers. Internal console. Not the customer’s product |
| ETP Customer UI | A customer’s own administrators, with that customer’s branding. Separate from Core UI. Not built yet |
| Business application | The people who do the business. Built on ETP capabilities, not inside Core UI |

## How a customer consumes ETP

There are three commercial models. All three sit on the same capability registry, the same application model, and the same deployment model. The model only changes **what the customer is entitled to consume**.

| Model | The customer is saying | What they receive |
|-------|------------------------|-------------------|
| Application-based | “Give me this application.” | One business application, plus the capabilities that application is entitled to use. Not the whole catalog |
| Capability-based | “Give me these building blocks.” | A named set of capabilities, usable across several of their applications |
| Full platform | “Give me the platform.” | Broad access to the capability ecosystem, so they can compose more than one application |

Buying an application does not by itself mean a private stack. Buying the whole platform does not by itself mean a shared stack.

## How it is deployed

Deployment answers a different question: **how** the entitled application and capabilities are isolated at runtime.

- **Dedicated** — the application runtime, its database, or a capability is reserved for one customer, tenant, or application. Used when isolation, compliance, or performance requires it.
- **Shared** — several customers use the same platform runtime, with isolation that is still explicit (separate database, tenant, or access control). Shared does not mean “no isolation.”

The choice can differ by capability. One customer might run a private database and a shared gateway. Another might run a private gateway as well. Which combination is offered is a product and operational decision. The architecture allows both.

Detail of the models and of what this repository can start locally: [ETP_DEPLOYMENT.md](ETP_DEPLOYMENT.md).

## What a capability is

A capability is a named, versioned building block with a contract: what it offers, how to configure it, and what it depends on. An application binds to a specific version, not to “whatever is newest.”

| Capability | Plain meaning |
|------------|----------------|
| PostgreSQL | Relational database. The application owns its tables, entities, and migrations |
| IAM | Sign-in and permission checks. OAuth and OpenID Connect where that fits. Keycloak is the identity provider behind the capability |
| Kafka | Event streaming. The application owns the meaning of each event |
| Redis | A cache for hot or short-lived data. Not the place that keeps the only copy of business data |
| API Gateway | Shared front door: routing, policies, limits. Target, not a service in this repository yet |
| API Catalog | Where APIs are registered and discovered. Separate from the gateway. Target |
| API-to-database | A reusable framework for structured access between an API and a database. The framework is ETP’s. The domain schema is the application’s. Target |
| Integration transformation | Shared mapping and format conversion. Domain-specific rules stay with the application. The migration capability is the part of this idea that exists in the repository today |

Capabilities move through a life of their own: drafted, validated, active, then later deprecated and retired.

## How the platform is organized

```text
Business applications
        |
        |  use
        v
ETP capabilities          IAM, PostgreSQL, Kafka, Redis, and others
        |
        |  registered and governed by
        v
ETP platform              Capability catalog, application records,
                          configuration, entitlements, operations
```

Three kinds of data stay separate:

- **Platform** — which customers, applications, capabilities, and entitlements exist.
- **IAM** — who the principals are and what they may do.
- **Application** — the business records. ETP does not design those tables.

> The platform owns platform data. The business application owns business data.

## Remember

1. Applications consume capabilities. They do not own the platform infrastructure.
2. Capabilities provide technology. Applications provide business behavior.
3. Application configuration says what is needed. Environment configuration says where it runs.
4. Do not store plaintext secrets in an application definition or in source control.
5. The application owns its schema, entities, repositories, and migrations.
6. Core UI administers the platform. Customer UI is a different experience.
7. API, event, screen, and file transfer can coexist in one application.
8. The API Catalog and the API Gateway are different capabilities.
9. Identity is a reusable capability, not a feature buried in one product.

## Where the work stands

The picture above is the target. In this repository today you can:

- register a capability through the platform API, and look it up in Core UI
- sign in to Core UI through IAM. The catalog screens are read-only. Dashboard, customers, applications, configuration, version, and audit are placeholders
- run PostgreSQL, Kafka, and Redis locally as supporting infrastructure
- load files into a database with the migration capability
- download API results as CSV or Excel with the export service

You cannot yet compose an application from the catalog and deploy it as a generated product. Application records, entitlements, and the commercial models exist as design and, in part, as database tables. The screens and APIs that operate them are still ahead. API Gateway, API Catalog, and the API-to-database service are not services in this repository yet.

## Where to go next

| If you want | Read |
|-------------|------|
| The full architecture and the rules that must not drift | [ETP-ARCHITECTURE.md](ETP-ARCHITECTURE.md) |
| Stack, mental models, components, and guardrails | [ETP_ENGINEERING_MODEL.md](ETP_ENGINEERING_MODEL.md) |
| How to run the platform on a workstation | [ETP_HOW_TO.md](ETP_HOW_TO.md) |
| What “done” means for a capability | [ETP_CAPABILITY_DEFINITION.md](ETP_CAPABILITY_DEFINITION.md) |
| The HTTP APIs | [ETP_APIS.md](ETP_APIS.md) |
| The databases | [ETP_CURRENT_DATA_MODEL.md](ETP_CURRENT_DATA_MODEL.md) |
| How capabilities are deployed | [ETP_DEPLOYMENT.md](ETP_DEPLOYMENT.md) |
