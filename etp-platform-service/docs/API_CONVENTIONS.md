# ETP API Conventions

Concise REST API conventions for ETP Platform services. Applies to `etp-platform-service` today and future capability APIs.

## URL Versioning

All public business APIs use a versioned prefix:

```
/api/v1/...
```

Examples in this service:

- `GET /api/v1/health` — platform health (`com.etp.platform.core`)
- `GET /api/v1/platform/info` — platform metadata (`com.etp.platform.core`)
- `GET /api/v1/capabilities` — capability registry list
- `GET /api/v1/capabilities/{id}` — capability aggregate detail

## Resource Naming

Use **resource-oriented nouns**, not verbs in the path.

```
GET /api/v1/applications
GET /api/v1/applications/{id}
GET /api/v1/capabilities
GET /api/v1/capabilities/{id}
```

Capability **versions** are nested in the capability aggregate in V1 (returned on GET, created/updated via PATCH on the capability). There is no separate `/capabilities/{id}/versions` collection endpoint.

Avoid verb-oriented URLs unless an explicit non-CRUD action is required (e.g. a future `POST /api/v1/application-definitions/{id}/validate`).

## HTTP Methods

| Method | Intended use |
|--------|----------------|
| `GET` | Read resource(s); idempotent |
| `POST` | Create resource or submit action |
| `PUT` | Full replacement of a resource |
| `PATCH` | Partial update of a resource |
| `DELETE` | Remove or retire a resource |

**Capability Registry V1:** There is no DELETE for capabilities. Deprecation, retirement, and cancellation are **lifecycle transitions** via `PATCH /api/v1/capabilities/{id}` (`status` or `versionStatus`).

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

### Lifecycle conflicts

Invalid capability or capability-version lifecycle transitions return:

- HTTP **409**
- Error code **`INVALID_LIFECYCLE_TRANSITION`**

Other common capability registry conflicts use **409** with codes such as `DUPLICATE_CAPABILITY_CODE`, `DUPLICATE_CAPABILITY_VERSION`, and `ACTIVE_VERSION_IMMUTABLE`.

## Validation

- Validate requests at the API boundary (`@Valid`, Jakarta Validation).
- Return consistent validation errors via `GlobalExceptionHandler` (`VALIDATION_ERROR` with `ErrorDetail` list).
- Inline capability contract documents are validated against the V1 base JSON Schema at the API boundary.
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

`BusinessException` maps to **400** or **409** depending on the error code. Validation failures map to **400**. Unhandled exceptions map to **500**.

## API Documentation

- OpenAPI/Swagger is provided via SpringDoc.
- Specification: `/api/v1/openapi`
- UI: `/api/v1/swagger-ui`
- Capability Registry operations are tagged **Capability Management** in OpenAPI.
- Do not disable OpenAPI for local development unless explicitly required for a deployment profile.

## Actuator

Operational endpoints remain under:

```
/actuator/...
```

Examples: `/actuator/health`, `/actuator/info`.

Do not expose actuator paths under `/api/v1`. Do not mix actuator and business API semantics.

## Capability APIs (implemented)

See [CAPABILITY_REGISTRY.md](CAPABILITY_REGISTRY.md) for lifecycle rules, aggregate model, and PATCH semantics.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/capabilities` | Create capability + first version (DRAFT) |
| `GET` | `/api/v1/capabilities` | List capabilities (`category`, `status` filters) |
| `GET` | `/api/v1/capabilities/{id}` | Retrieve full aggregate |
| `PATCH` | `/api/v1/capabilities/{id}` | Metadata, lifecycle, new version, version lifecycle |

## Future API Principles

As additional ETP capabilities are added:

- APIs become stable **capability boundaries**
- Capabilities should not expose internal database structure
- Business applications should consume **capability APIs**, not platform metadata tables directly
- Cross-capability dependencies should be explicit in metadata and reflected in API design
- Security and correlation context should flow through API calls where operations span components
