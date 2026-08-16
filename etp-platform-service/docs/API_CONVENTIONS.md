# ETP API Conventions

Concise REST API conventions for ETP Platform services. Applies to `etp-platform-service` today and future capability APIs.

## URL Versioning

All public business APIs use a versioned prefix:

```
/api/v1/...
```

Examples in this service:

- `GET /api/v1/health`
- `GET /api/v1/platform/info`

## Resource Naming

Use **resource-oriented nouns**, not verbs in the path.

```
GET /api/v1/applications
GET /api/v1/applications/{id}
GET /api/v1/capabilities/{capabilityId}/versions
```

Avoid verb-oriented URLs unless an explicit non-CRUD action is required (e.g. a future `POST /api/v1/application-definitions/{id}/validate`).

## HTTP Methods

| Method | Intended use |
|--------|----------------|
| `GET` | Read resource(s); idempotent |
| `POST` | Create resource or submit action |
| `PUT` | Full replacement of a resource |
| `PATCH` | Partial update of a resource |
| `DELETE` | Remove or retire a resource |

## Response Model

Use the shared wrapper where applicable:

- `com.etp.platform.common.api.ApiResponse<T>` — success payloads with optional message and timestamp

Example shape:

```json
{
  "data": { ... },
  "message": null,
  "timestamp": "2026-08-16T08:00:00Z"
}
```

Not every endpoint must use `ApiResponse` if a standard (e.g. Actuator) format is required.

## Error Handling

Use the existing error model — do not introduce a parallel structure:

- `com.etp.platform.common.api.ErrorResponse`
- `com.etp.platform.common.api.ErrorDetail`
- `com.etp.platform.common.exception.GlobalExceptionHandler`
- `com.etp.platform.common.exception.BusinessException` for domain errors with codes

Errors return `ErrorResponse` with code, message, timestamp, and optional field-level `details`.

## Validation

- Validate requests at the API boundary (`@Valid`, Jakarta Validation).
- Return consistent validation errors via `GlobalExceptionHandler` (`VALIDATION_ERROR` with `ErrorDetail` list).
- Do not rely on database constraints alone for user-facing validation.

## HTTP Status Codes

| Code | Use |
|------|-----|
| `200` | Successful read or update with body |
| `201` | Resource created |
| `202` | Accepted for asynchronous processing |
| `204` | Successful action with no body |
| `400` | Bad request, business rule violation, validation failure |
| `401` | Authentication required or failed |
| `403` | Authenticated but not authorized |
| `404` | Resource not found |
| `409` | Conflict (duplicate, invalid state transition) |
| `422` | Use only when semantically appropriate and consistently applied |
| `500` | Unexpected server error |

The current `GlobalExceptionHandler` maps `BusinessException` and validation failures to `400`, and unhandled exceptions to `500`.

## API Documentation

- OpenAPI/Swagger is provided via SpringDoc.
- Specification: `/api/v1/openapi`
- UI: `/api/v1/swagger-ui`
- Do not disable OpenAPI for local development unless explicitly required for a deployment profile.

## Actuator

Operational endpoints remain under:

```
/actuator/...
```

Examples: `/actuator/health`, `/actuator/info`.

Do not expose actuator paths under `/api/v1`. Do not mix actuator and business API semantics.

## Future API Principles

As ETP capabilities are added:

- APIs become stable **capability boundaries**
- Capabilities should not expose internal database structure
- Business applications should consume **capability APIs**, not platform metadata tables directly
- Cross-capability dependencies should be explicit in metadata and reflected in API design
- Security and correlation context should flow through API calls where operations span components
