# ETP Capability Definition of Done

A capability is considered complete only when the applicable items below are addressed. Not every item applies to every capability type; use judgment, but do not skip items that are relevant to the capability being delivered.

## 1. Architecture

- Clear capability boundary defined
- Purpose and scope documented
- ETP vs application ownership documented
- No business-domain logic in generic capability code

## 2. Capability Identity

- Capability code and name defined
- Version defined
- Lifecycle status defined
- Description and purpose documented

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
- API usage documented
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
