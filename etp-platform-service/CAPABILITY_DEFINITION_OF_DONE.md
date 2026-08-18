# ETP Capability Definition of Done

A capability is considered complete only when the applicable items below are addressed. Not every item applies to every capability type; use judgment, but do not skip items that are relevant to the capability being delivered.

## 1. Architecture

- Clear capability boundary defined
- Purpose and scope documented
- ETP vs application ownership documented
- **Capability ≠ business application** — capabilities are reusable building blocks; business applications are composed from capabilities and own business behavior
- No business-domain logic in generic capability code

## 2. Capability Identity

- Capability code and name defined
- Version defined
- Lifecycle status defined and documented (capability and version lifecycles are separate; see [docs/CAPABILITY_REGISTRY.md](docs/CAPABILITY_REGISTRY.md))
- Description and purpose documented

### Lifecycle expectations (ETP platform capabilities)

Where a capability exposes versioned resources through the platform registry:

| Level | V1 reference lifecycle |
|-------|------------------------|
| Capability | `DRAFT` → `ACTIVE` → `DEPRECATED` → `RETIRED` |
| Capability version | `DRAFT` → `VALIDATED` → `ACTIVE` → `DEPRECATED` → `RETIRED`; `DRAFT`/`VALIDATED` → `CANCELLED` (never activated) |

Implemented registry behavior:

- POST creates capability + first version in `DRAFT`.
- PATCH handles metadata, capability lifecycle, new versions, and version lifecycle.
- No DELETE — deprecation, retirement, and cancellation are lifecycle transitions.
- Lifecycle changes produce audit events (`STATUS_CHANGE`, etc.).
- Invalid transitions return HTTP 409 / `INVALID_LIFECYCLE_TRANSITION`.

## 3. Capability Contract

Where applicable, contract documents cover:

- APIs
- Events
- UI integration points
- Dependencies
- Configuration
- Security
- Non-functional requirements (NFRs)
- Deployment expectations
- Observability
- Audit requirements
- Compatibility constraints

## 4. Dependencies

- Required dependencies identified
- Optional dependencies identified
- Version constraints identified
- Dependency conflicts considered

## 5. API

Where applicable:

- API contract defined
- `/api/v1` convention followed
- Request/response models defined
- Error handling defined
- Validation implemented at API boundary
- API tests added

## 6. Data

Where applicable:

- Schema ownership documented
- Flyway migration provided
- Indexes and constraints considered
- No business data placed in platform metadata tables

## 7. Security

- Authentication requirements defined
- Authorization requirements defined
- Secrets externalized
- No credentials committed
- Security cannot be bypassed by composition or configuration

## 8. Configuration

- Non-secret configuration externalized
- Environment-specific configuration separated
- Secrets supplied through environment or secret management

## 9. Observability

Where applicable:

- Health/readiness considered
- Structured logging
- Correlation/transaction context
- Metrics
- Error visibility
- Operational support information documented

## 10. Testing

Applicable tests should include:

- Unit tests
- Integration tests
- API tests
- Negative/error scenarios

Testcontainers is not required for every capability unless PostgreSQL or containerized dependencies are part of the capability runtime.

## 11. Documentation

- README or capability documentation updated
- Configuration documented
- API usage documented (for registry capabilities, see [docs/CAPABILITY_REGISTRY.md](docs/CAPABILITY_REGISTRY.md))
- Architecture decisions recorded where relevant

## 12. Versioning & Compatibility

- Capability version defined
- Compatibility expectations documented
- Breaking changes identified and communicated

## 13. Deployment

Where applicable:

- Container/runtime requirements documented
- Deployment configuration documented
- Required infrastructure dependencies documented

## 14. Audit

Where applicable:

- Significant configuration and lifecycle changes are auditable
- Capability Registry V1 records `AuditEvent` rows for create, metadata update, version create, and status changes (no audit query API yet)

## 15. Review

Before completion:

- Architecture reviewed
- Tests pass
- Local runtime verified
- No unnecessary dependencies introduced
- No accidental business logic introduced
- No secrets committed

## Completion Checklist

- [ ] Architecture
- [ ] Identity/version
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
