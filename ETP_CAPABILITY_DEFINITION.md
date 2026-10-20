# ETP Capability Definition

What an ETP capability is, how it is registered, and when it is complete. This file lives only at the repository root.

APIs that persist this model: [ETP_APIS.md](ETP_APIS.md). Tables: [ETP_CURRENT_DATA_MODEL.md](ETP_CURRENT_DATA_MODEL.md).

---

## 1. Definition

A capability is a reusable technical building block owned by ETP. A business application is composed from capabilities and owns domain behavior and domain data.

```text
Capability          what ETP provides          etp.iam, etp.postgresql, etp.kafka
Application         what a customer needs      capability versions + configuration intent
Application instance  where it runs            not implemented
Source code         how business behavior is written
```

A capability does not become a business application because a screen can manage it. ETP Core UI administers the catalog. It is not the capability.

## 2. Identity

Registered on `POST /api/v1/capabilities`.

| Field | Rule |
|-------|------|
| `code` | Stable, unique, lowercase dotted identifier. Example: `etp.iam`. Immutable after create |
| `name` | Human-readable title |
| `description` | What the capability is for |
| `category` | Catalog grouping, for example `SECURITY` |
| `version` | Semantic version of a **capability version**, for example `1.0.0` |

The capability row and each version row have separate lifecycles.

## 3. Contract

Every version has a contract. The contract is the machine-readable description of what that version provides.

Stored in `capability_contract` as:

- an inline JSON document (`contract_document`), validated against `etp-platform-service/src/main/resources/schemas/capability-contract-base.schema.json`, or
- an external location (`contract_location` + format), or both.

The base schema allows these objects and does not require them. Additional properties are allowed.

| Section | What belongs here |
|---------|-------------------|
| `functional` | Behavior the consumer can rely on |
| `interfaces` | APIs, events, UI integration points, file exchange |
| `configuration` | Non-secret settings the consumer must supply |
| `dependencies` | Other capabilities this version needs |
| `security` | Authentication and authorization expectations |
| `nfr` | Performance, availability, limits |
| `deployment` | Runtime shape: service, infrastructure, or library |
| `operations` | Health, logs, metrics, audit |
| `compatibility` | Breaking-change and version rules |

Secrets are not part of the contract body. The contract names the setting. The environment supplies the value.

Infrastructure capabilities (PostgreSQL, Kafka, Redis) document a client connection. They do not add a proxy service in the request path.

## 4. Implementation

Optional metadata on a version: `code`, `name`, `technology`, `technologyVersion`.

Example: capability `etp.iam`, implementation code `etp-iam`, technology Keycloak. The implementation record points at the technology. It does not replace the contract.

## 5. Dependencies

A dependency points at a **capability version id**, not only a capability code.

| Field | Meaning |
|-------|---------|
| `capabilityVersionId` | Exact version required |
| `dependencyType` | For example `RUNTIME` |
| `mandatory` | Whether composition may omit it |

Version-aware dependencies are how the catalog avoids “latest” bindings.

## 6. Lifecycle

### Capability

```text
DRAFT → ACTIVE → DEPRECATED → RETIRED
```

`CANCELLED` is not a capability status. The first version that becomes `ACTIVE` moves the capability from `DRAFT` to `ACTIVE` in the same transaction.

### Capability version

```text
DRAFT
  ├── VALIDATED
  │     ├── ACTIVE → DEPRECATED → RETIRED
  │     └── CANCELLED
  └── CANCELLED
```

| Rule | Behavior |
|------|----------|
| Create | POST stores the capability and version 1 as `DRAFT` |
| Change | PATCH for metadata, capability status, a new version, or `versionStatus` |
| One ACTIVE | Only one `ACTIVE` version per capability. PostgreSQL partial unique index `uk_capability_version_one_active_per_capability` |
| Activate newer | A validated version cannot be activated when its semver is less than or equal to the current ACTIVE version. Activating a higher version deprecates the previous ACTIVE version |
| Cancel | `CANCELLED` only for a version that was never `ACTIVE` |
| Immutability | An `ACTIVE` version’s content does not change. Ship a new version |
| Delete | No DELETE endpoint |
| Audit | Create, metadata update, version create, and status change write `audit_event`. There is no audit read API |
| Invalid transition | HTTP 409, code `INVALID_LIFECYCLE_TRANSITION` |

Core UI can list and open capabilities. It does not call POST, PUT, or PATCH.

## 7. Application side of the model

Tables exist and have JPA entities. No repository or REST API writes them yet.

```text
customer
  └── application
        └── application_definition      versioned composition, optional JSON document
              └── application_capability
                    └── capability_version
```

`application_capability` is the authoritative link. JSON inside `application_definition` does not replace it.

The definition says which capability versions the application needs and what it intends to configure. It does not store the PostgreSQL host, the Kafka bootstrap server, or a password. Those belong to the environment. See [ETP_DEPLOYMENT.md](ETP_DEPLOYMENT.md).

Entitlement tables from Flyway V14 are schema only.

## 8. Capabilities present in this repository

| Code | In the tree | Registry |
|------|-------------|----------|
| `etp.iam` | `capabilities/etp-iam` service | IAM README records a local ACTIVE registration of 1.0.0. Confirm in the target database before relying on the UUID |
| `etp.postgresql` | Contract and deployment docs, Compose is per service | Not registered (capability README) |
| `etp.kafka` | Contract, deployment docs, local broker | Not registered |
| `etp.redis` | Contract, deployment docs, local server | Not registered |
| Migration | `capabilities/etp-migration` service | No registry registration in the module docs |
| Exports | `capabilities/etp-exports` service | No registry registration in the module docs |

Target capabilities with no module: API Gateway, API Catalog, API-to-DB, Integration Transformation.

## 9. Definition of done

A capability is complete when every applicable item below is true. Skip an item only when it cannot apply to that capability type, and say why.

### Architecture

- Boundary, purpose, and scope are written down
- ETP owns the capability; the business application owns domain logic
- The capability is not implemented as part of one business application

### Identity and contract

- Code, name, version, and both lifecycles are defined
- The contract covers the sections in §3 that apply
- Dependencies name a version, and mandatory versus optional is explicit

### API and data

- Public HTTP surface uses `/api/v1` where the capability has an API
- Requests are validated at the boundary and errors use the service’s error model
- Schema ownership is documented. Flyway owns the schema when the capability has tables
- Platform metadata tables do not hold business rows

### Security and configuration

- Authentication and authorization requirements are stated
- Secrets are environment or secret-manager values. None are committed
- Non-secret configuration is externalized per environment
- Composition cannot turn security off

### Operations and delivery

- Health is defined. Structured logs carry correlation where calls cross a process
- Unit and API tests cover the success path and the failures callers will hit
- README or the root documents explain configuration and API use
- Breaking changes are called out against the previous version
- Container or infrastructure needs are documented
- Lifecycle and configuration changes that matter are auditable
- Tests pass and a local run was checked

### Checklist

- [ ] Architecture
- [ ] Identity and version
- [ ] Contract
- [ ] Dependencies
- [ ] API
- [ ] Data
- [ ] Security
- [ ] Configuration
- [ ] Observability
- [ ] Testing
- [ ] Documentation
- [ ] Compatibility
- [ ] Deployment
- [ ] Audit
- [ ] Review

## 10. Registry delivery status

| Area | Status |
|------|--------|
| Registry REST (GET, POST, GET by id, PUT, PATCH) | Implemented on the platform service |
| Lifecycle and audit writes | Implemented |
| Inline contract and base JSON Schema check | Implemented |
| Core UI list and details | Read-only. List filters in the browser. Details: version selector, Overview, Contract, Dependencies. Implementation is in the API response and not rendered |
| Core UI create, edit, lifecycle | Buttons are visible and disabled. No POST, PUT, or PATCH |
| JWT on registry routes | Not enforced. The OAuth2 resource-server starter is unused. Core UI still sends `Authorization: Bearer` |
| Application Registry | Not implemented |
| Entitlement services | Schema only |
