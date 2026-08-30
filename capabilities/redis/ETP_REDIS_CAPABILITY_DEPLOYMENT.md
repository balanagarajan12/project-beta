# ETP Redis — Capability Deployment Guide

Deployment and consumption guide for the **ETP Redis** infrastructure capability (`etp.redis` target version **1.0.0**).

**Related:** [README.md](README.md), [redis-capability-contract.json](redis-capability-contract.json)

---

## Purpose

ETP Redis documents **in-memory cache infrastructure** for ETP applications. It defines how consumers connect to Redis directly via client libraries — not through an ETP proxy.

| Provides | Does not provide |
|----------|------------------|
| Redis **7.4** as ETP-standard cache infrastructure | ETP REST APIs or cache proxy |
| Redis wire-protocol connectivity contract | Application cache keys or TTL policies |
| Configuration and secret-reference conventions | Cache-aside logic or invalidation rules |
| Local Docker Compose for development | Automatic Redis provisioning (future) |
| Security and operations documentation | Live credentials in the Capability Registry |

**Registry registration** is separate from running Redis. This capability describes **infrastructure**; consumers connect with their own Redis client configuration.

---

## Architecture

### Runtime path (no ETP Redis in request path)

```
Application Service
    ↓
Redis Client (Lettuce, Jedis, etc.)
    ↓
Redis 7.4
    ↓
Docker / Kubernetes / AWS ElastiCache / Azure Cache for Redis
```

**etp.redis is NOT in the runtime request path.**

### UAM cache-aside (application-owned — not implemented in this capability)

```
UAM Business Service
      ↓
   Redis
   /   \
 HIT   MISS
  │      │
  │      ▼
  │   PostgreSQL (etp.postgresql — separate capability)
  │
  └──→ Result
```

UAM (future) owns: cached data, keys, TTL, invalidation, and HIT/MISS behavior. ETP Redis provides the Redis server and connection conventions.

---

## Capability boundary

| Layer | Responsibilities |
|-------|------------------|
| **ETP Redis capability** | Contract, deployment docs, local dev compose, configuration conventions, operational model |
| **Redis infrastructure** | Server runtime, network, auth credentials, persistence policy per environment |
| **Application** | Client integration, cache keys, TTL, invalidation, cache-aside, readiness policy |

---

## Redis version

| Item | Value |
|------|-------|
| Version | **7.4** |
| Docker image | `redis:7.4-alpine` |
| Rationale | Current stable Redis 7 line; widely available on Docker Hub; compatible with Spring Data Redis / Lettuce. No prior Redis version existed in the ETP repository. |

---

## Local Docker deployment

Redis did not exist in ETP before this capability. Local development uses capability-specific compose:

| Item | Value |
|------|-------|
| Compose | `capabilities/redis/docker-compose.yml` |
| Service | `redis` |
| Image | `redis:7.4-alpine` |
| Container | `etp-redis` |
| Default host port | `56379` (`ETP_REDIS_PORT`) |
| Container port | `6379` |
| Password | `ETP_REDIS_PASSWORD` (required) |
| Volume | `etp-redis-data` |
| Healthcheck | `redis-cli -a $REDIS_PASSWORD ping` |

```bash
cd capabilities/redis
cp .env.example .env
# Set ETP_REDIS_PASSWORD
docker compose --env-file .env up -d
```

Verify:

```bash
docker compose exec redis redis-cli -a "$ETP_REDIS_PASSWORD" ping
# PONG
```

### Persistence (development)

The named volume `etp-redis-data` enables default Redis RDB snapshots to `/data`. Suitable for dev warm-up. For pure ephemeral cache testing, operators may adjust `command` to disable persistence — not the default in v1.0.0 compose.

### Platform compose

**Do not** add Redis to `etp-platform-service/docker-compose.yml` unless explicitly required. Redis capability artifacts live under `capabilities/redis/`.

---

## Configuration

### Separation of concerns

| Concern | When | Examples |
|---------|------|----------|
| **Application definition** | Composition | Select `etp.redis` v1.0.0; required vs optional; logical cache tier intent |
| **Environment configuration** | Deployment | Host, port, TLS, secret references — resolves Docker vs K8s vs ElastiCache vs Azure |
| **Application implementation** | Runtime | `spring.data.redis.*`, key prefixes, TTL, cache-aside code |

Never store plaintext passwords in application definitions or registry payloads.

### Application consumption (Spring Boot example)

```properties
spring.data.redis.host=${ETP_REDIS_HOST:localhost}
spring.data.redis.port=${ETP_REDIS_PORT:56379}
spring.data.redis.password=${ETP_REDIS_PASSWORD}
spring.data.redis.database=0
# spring.data.redis.ssl.enabled=true  # non-dev when TLS endpoint provided
```

Optional pool and timeout tuning:

```properties
spring.data.redis.timeout=2000ms
spring.data.redis.lettuce.pool.max-active=8
```

### Environment variables (local)

| Variable | Purpose |
|----------|---------|
| `ETP_REDIS_HOST` | Redis hostname (default `localhost` for local compose) |
| `ETP_REDIS_PORT` | Host-mapped port (default `56379`) |
| `ETP_REDIS_PASSWORD` | Redis `requirepass` password |

---

## Authentication

| Environment | v1.0.0 repository default |
|-------------|---------------------------|
| Local Docker | `requirepass` via `ETP_REDIS_PASSWORD` |
| ACL users | Not configured in compose — Redis 7 ACLs supported by server, deployment responsibility |
| TLS | Not configured in local compose |

---

## Health / readiness

| Layer | Approach |
|-------|----------|
| **Docker** | Healthcheck in `docker-compose.yml` — `redis-cli PING` with auth |
| **Application** | Spring Boot Actuator Redis health when `spring-boot-starter-data-redis` and health contributor are configured |
| **ETP service** | No separate ETP Redis health microservice |

Consumers that treat cache as optional should document degrade-to-backing-store behavior in their own readiness policy.

---

## Environment-specific deployment (targets — not in repository)

| Environment | Target implementation | Repository status |
|-------------|----------------------|-------------------|
| DEV | Docker Compose (`capabilities/redis/`) | **Implemented** |
| QA | Redis on Kubernetes (operator/Helm) | Documented target only |
| PROD | AWS ElastiCache for Redis | Documented target only |
| PROD | Azure Cache for Redis | Documented target only |

Applications receive connection configuration from the deployment environment. They should not hardcode infrastructure type.

### Future composition (not implemented)

```
Application Definition
      ↓
Select etp.redis v1.0.0
      ↓
Deployment resolves environment-specific Redis
      ↓
Application receives endpoint + secret reference
      ↓
Application runtime → Redis client → Redis
```

---

## Security

### Must have for v1.0.0 readiness

- Password authentication in local compose (`requirepass`)
- Secret via environment variable / secret manager reference — not registry plaintext
- Network isolation guidance for non-dev (documented)
- Application isolation via key prefix or dedicated instance (documented)

### Future hardening

- TLS for all non-dev endpoints
- Redis ACL users with least privilege per application
- Private-network-only Redis endpoints
- Automated secret rotation
- Redis Cluster / Sentinel for HA (deployment-specific)

---

## Operational considerations

- Start Redis before consumers that require cache connectivity at startup.
- Monitor memory usage and eviction policy at infrastructure layer (deployment-specific).
- Backup/persistence: optional for pure cache tiers; policy defined per environment.
- No numeric SLOs defined in repository for v1.0.0.

---

## Dependencies

Registry `dependencies`: `[]` — Redis is infrastructure, not a dependency on another registered ETP capability.

Applications using cache-aside may depend on **etp.postgresql** (or other stores) at the **application** level; that is separate from the Redis capability registry dependency list.

---

## Out of scope

- Registering the capability (separate step)
- UAM implementation, cache keys, TTLs
- Redis REST proxy or JVM broker service
- AWS/Azure/Kubernetes automation in this repository
- Changes to `etp_platform` or `etp_iam` schemas
