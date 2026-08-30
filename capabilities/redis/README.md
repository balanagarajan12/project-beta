# ETP Redis Capability

**Capability code:** `etp.redis`  
**Target version:** `1.0.0`  
**Implementation code:** `redis`

| Registry status | Not registered yet — artifacts prepared for review |

ETP Redis is an **infrastructure capability**: it documents how ETP uses Redis 7.4 as in-memory cache infrastructure. It is **not** a Spring Boot service, **not** in the application request path, and **not** a cache proxy or REST API.

## Purpose

- Standard ETP cache infrastructure (Redis 7.4)
- Redis client connectivity for platform and application services
- Key-prefix / logical-database isolation conventions
- Deployment, security, and configuration conventions

## Architecture

```
Application Service (e.g. UAM)
        ↓
Redis Client (e.g. Lettuce / Spring Data Redis)
        ↓
Redis 7.4 (Docker / K8s / ElastiCache / Azure — deployment-specific)
```

Cache-aside (application-owned):

```
UAM Business Service
      ↓
   Redis
   /   \
 HIT   MISS
  │      │
  │      ▼
  │   PostgreSQL (separate capability — etp.postgresql)
  │
  └──→ Result
```

The **ETP Redis capability** (registry contract + deployment docs) describes infrastructure. It does **not** sit in the runtime transaction path.

## Runtime consumption

Applications configure `spring.data.redis.*` (or equivalent) with environment-resolved host, port, password, and optional TLS. No ETP middleware.

## Deployment model

| Environment | Target (documented) | In repository |
|-------------|---------------------|---------------|
| DEV | Docker Compose | `capabilities/redis/docker-compose.yml` |
| QA | Kubernetes | Future / deployment-specific |
| PROD | AWS ElastiCache, Azure Cache for Redis | Future / deployment-specific |

## Current implementation

| Item | Detail |
|------|--------|
| Software | Redis **7.4** (`redis:7.4-alpine`) |
| Local dev | `capabilities/redis/docker-compose.yml` |
| Default host port | `56379` (`ETP_REDIS_PORT`) |
| Auth | `requirepass` via `ETP_REDIS_PASSWORD` |
| Persistence | Named volume `etp-redis-data` (default RDB) |
| Consumers | None yet in repository |

## Out of scope (v1.0.0)

- ETP REST APIs for cache or Redis administration
- Redis proxy Spring Boot service
- UAM cache keys, TTLs, or cache-aside implementation
- Automatic Redis provisioning via Application Composition (future)
- Modifying `etp_platform` or `etp_iam` schemas

## Artifacts

| File | Purpose |
|------|---------|
| `redis-capability-contract.json` | Inline `contract.document` for registry |
| `redis-capability-registration.json` | `CreateCapabilityRequest` payload (review before POST) |
| `ETP_REDIS_CAPABILITY_DEPLOYMENT.md` | Deployment and consumption guide |
| `docker-compose.yml` | Local Redis for development |
| `.env.example` | Local environment template |

## Related

- [ETP PostgreSQL capability](../postgresql/) — reference infrastructure pattern (`etp.postgresql`)
- [ETP IAM capability](../etp-iam/) — reference onboarding pattern (`etp.iam` ACTIVE)
- [Capability Registry](../../etp-platform-service/docs/CAPABILITY_REGISTRY.md)
