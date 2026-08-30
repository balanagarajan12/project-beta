# ETP Kafka — Capability Deployment Guide

Deployment and consumption guide for the **ETP Kafka** infrastructure capability (`etp.kafka` target version **1.0.0**).

**Related:** [README.md](README.md), [kafka-capability-contract.json](kafka-capability-contract.json)

---

## Purpose

ETP Kafka documents **event streaming infrastructure** for ETP applications with both **producer** and **consumer** support. Applications connect via Kafka client libraries — not through an ETP proxy.

| Provides | Does not provide |
|----------|------------------|
| Apache Kafka **3.8.1** broker infrastructure (KRaft) | ETP REST APIs or Kafka proxy |
| Producer and consumer wire-protocol connectivity | Application topic names or consumer groups |
| Topic/partition/replication/retention conventions | Event processing or business rules |
| Configuration and secret-reference conventions | Confluent Schema Registry (v1.0.0) |
| Local Docker Compose for development | Live credentials in the Capability Registry |

**etp.kafka is NOT in the runtime message path as a proxy.**

---

## Architecture

### Producer

```
Application
    ↓
Kafka Producer Client
    ↓
Kafka Broker
    ↓
Topic
    ↓
Partition
```

### Consumer

```
Kafka Broker
    ↓
Topic
    ↓
Partition
    ↓
Consumer Group
    ↓
Kafka Consumer Client
    ↓
Application
```

### Capability model

```
etp.kafka
   ├── Producer — Application → Kafka
   └── Consumer — Kafka → Application
```

---

## Kafka implementation

| Item | Value |
|------|-------|
| Distribution | **Apache Kafka** (official image, not Confluent Platform bundle) |
| Version | **3.8.1** |
| Mode | **KRaft** (no Zookeeper) |
| Docker image | `apache/kafka:3.8.1` |
| Rationale | Official ASF image; KRaft simplifies local single-node dev; Apache 2.0 license; spring-kafka compatible; no prior Kafka in ETP repository |

Alternatives considered but not used for v1.0.0 local dev: Confluent (extra licensing/ops surface), Bitnami (image policy changes), Redpanda (Kafka-compatible but not Apache Kafka).

---

## Local Docker deployment

Kafka did not exist in ETP before this capability.

| Item | Value |
|------|-------|
| Compose | `capabilities/kafka/docker-compose.yml` |
| Service | `kafka` |
| Container | `etp-kafka` |
| Image | `apache/kafka:3.8.1` |
| Host port | `59092` (`ETP_KAFKA_PORT`) |
| Bootstrap (host) | `localhost:59092` |
| Listeners | PLAINTEXT_HOST for host clients; internal PLAINTEXT for broker |
| Supporting services | **None** (KRaft — no Zookeeper) |
| Persistence | Volume `etp-kafka-data` |
| Auto-create topics | `true` (dev convenience) |
| Healthcheck | `kafka-broker-api-versions.sh` |

```bash
cd capabilities/kafka
cp .env.example .env
docker compose --env-file .env up -d
```

Verify broker:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092
```

### Producer smoke test

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic dev.smoke-test
# Type messages, Ctrl+C to exit
```

### Consumer smoke test

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic dev.smoke-test \
  --from-beginning \
  --group dev-smoke-group
```

Both flows confirm: **Producer → Kafka** and **Kafka → Consumer** without an ETP middleware layer.

### Topic creation (recommended for non-dev)

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --topic my.application.events \
  --partitions 3 \
  --replication-factor 1
```

Local compose uses replication factor 1 (single broker).

---

## Topic ownership

| Layer | Owns |
|-------|------|
| **Kafka infrastructure** | Broker availability, topic creation tooling, partitions, replication, retention, log dirs |
| **Application** | Topic business name, event type, producer/consumer role, consumer group, payload, semantics, processing |

Do **not** define UAM-specific topics in the generic Kafka capability artifacts.

---

## Schema Registry

**Not included in v1.0.0.**

- Kafka transports message bytes (commonly JSON UTF-8 strings or byte arrays).
- Payload validation belongs in application/event processing (ETP JSON Schema Validation at application layer).
- Confluent Schema Registry / Avro enforcement is a **future optional** integration — not deployed in this repository.

---

## Application consumption

### Configuration layers

| Layer | When | Content |
|-------|------|---------|
| **A. Capability dependency** | Composition | `etp.kafka` v1.0.0, required flag |
| **B. Kafka usage** | Application design | Producer/consumer role, topic, group, serializers |
| **C. Environment** | Deployment | Bootstrap servers, SASL, TLS, secrets |
| **D. Business implementation** | Runtime code | Handlers, validation, rules, transformations |

No generic application capability configuration database model exists yet (Application Composition future scope).

### Spring Boot producer example

```properties
spring.kafka.bootstrap-servers=${ETP_KAFKA_BOOTSTRAP_SERVERS:localhost:59092}
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.acks=all
spring.kafka.producer.retries=3
spring.kafka.producer.properties.enable.idempotence=true
```

### Spring Boot consumer example

```properties
spring.kafka.bootstrap-servers=${ETP_KAFKA_BOOTSTRAP_SERVERS:localhost:59092}
spring.kafka.consumer.group-id=${MY_APP_CONSUMER_GROUP}
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.listener.ack-mode=record
```

Topic and `group-id` are **application-defined** — not hardcoded in ETP Kafka capability files.

---

## Health / readiness

| Layer | Approach |
|-------|----------|
| **Docker** | `kafka-broker-api-versions.sh` in compose healthcheck |
| **Application** | Spring Boot Actuator Kafka health when `spring-kafka` health contributor is configured; or metadata request via client |
| **ETP service** | No separate ETP Kafka health microservice |

---

## Security

### Required for v1.0.0 readiness (documented)

- PLAINTEXT local dev with documented limitation
- Secret references via env/secret manager — no registry plaintext
- Network isolation guidance for non-dev
- Topic/group naming isolation per application

### Future hardening

- SASL (SCRAM, OAuthBearer)
- TLS/mTLS for brokers and clients
- Kafka ACLs per producer/consumer principal
- Private-network-only endpoints
- MSK/Azure IAM integration patterns

### v1.0.0 local reality

| Feature | Local compose |
|---------|---------------|
| PLAINTEXT | **Yes** |
| SASL | **No** |
| TLS | **No** |
| ACLs | **No** |

---

## Deployment targets (not in repository)

| Environment | Target | Repository status |
|-------------|--------|-------------------|
| DEV | Docker Compose | **Implemented** |
| QA | Kafka on Kubernetes | Documented target only |
| PROD | AWS MSK | Documented target only |
| PROD | Azure Event Hubs for Kafka / managed Kafka | Documented target only |

Applications receive bootstrap servers, credentials, and TLS settings from the deployment environment — they should not hardcode infrastructure type.

### Future composition (not implemented)

```
Application Definition → Select etp.kafka v1.0.0
    → Deployment resolves bootstrap servers + secrets
    → Application runtime → Kafka client → Kafka
```

---

## UAM use cases (future — not implemented here)

**Consumer path:**

```
External Kafka Event → etp.kafka (infrastructure) → UAM Consumer
    → Event Processing → JSON Schema Validation → Business Rules
    → UAM Business Logic → Redis / PostgreSQL
```

**Producer path:**

```
UAM Business Logic → Event Processing → etp.kafka (infrastructure)
    → Kafka Producer → Kafka Topic → External System
```

`etp.kafka` provides broker connectivity only — not UAM business logic.

---

## Operational considerations

- Start Kafka before applications that require broker connectivity at startup.
- Single-node local broker is not HA — use clustered MSK/K8s for production.
- Monitor disk usage (`etp-kafka-data` volume) and retention policies.
- Consumer lag monitoring is application/ops responsibility.
- No numeric SLOs defined in repository for v1.0.0.

---

## Dependencies

Registry `dependencies`: `[]` — Kafka is infrastructure.

JSON Schema Validation, Redis, PostgreSQL, and IAM are separate capabilities consumed at the **application** layer; they are not registry dependencies of `etp.kafka`.

---

## Out of scope

- Registry registration (separate step)
- UAM topics, groups, handlers
- Kafka REST proxy or JVM broker service
- Schema Registry deployment
- MSK/Azure/Kubernetes automation in this repository
- Changes to other ETP capability packages
