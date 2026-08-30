# ETP Kafka Capability

**Capability code:** `etp.kafka`  
**Target version:** `1.0.0`  
**Implementation code:** `kafka`

| Registry status | Not registered yet — artifacts prepared for review |

ETP Kafka is an **infrastructure capability**: Apache Kafka 3.8.1 event streaming with **producer** and **consumer** connectivity. It is **not** a Spring Boot service, **not** in the message path as a proxy, and **not** a Kafka REST API.

## Purpose

- Standard ETP event streaming infrastructure (Apache Kafka 3.8.1, KRaft)
- Direct producer and consumer client connectivity
- Topic, partition, consumer group, and offset infrastructure conventions
- Deployment, security, and configuration documentation

## Producer support

```
Application
    ↓
Kafka Producer Client (KafkaTemplate / KafkaProducer)
    ↓
Kafka Broker
    ↓
Topic → Partition
```

## Consumer support

```
Kafka Broker
    ↓
Topic → Partition
    ↓
Consumer Group
    ↓
Kafka Consumer Client (@KafkaListener / KafkaConsumer)
    ↓
Application
```

## Capability model

```
etp.kafka
   ├── Producer — Application → Kafka
   └── Consumer — Kafka → Application
```

Single capability — not split into separate registry entries.

## Runtime architecture

**etp.kafka is NOT a proxy.** Applications use Kafka clients directly.

Future UAM flows (application-owned logic):

**Consumer:** External event → Kafka → UAM consumer → Event processing → JSON Schema validation → Business rules → Redis/PostgreSQL

**Producer:** UAM business logic → Event processing → Kafka producer → Topic → External system

## Deployment model

| Environment | Target (documented) | In repository |
|-------------|---------------------|---------------|
| DEV | Docker Compose (KRaft, no Zookeeper) | `capabilities/kafka/docker-compose.yml` |
| QA | Kubernetes | Future / deployment-specific |
| PROD | AWS MSK | Future / deployment-specific |
| PROD | Azure Kafka-compatible / Event Hubs | Future / deployment-specific |

## Current implementation

| Item | Detail |
|------|--------|
| Software | Apache Kafka **3.8.1** (`apache/kafka:3.8.1`) |
| Mode | KRaft (no Zookeeper) |
| Local dev | `capabilities/kafka/docker-compose.yml` |
| Default bootstrap | `localhost:59092` |
| Security (local) | PLAINTEXT only |
| Schema Registry | **Not included** — future optional |
| Consumers | None yet in repository |

## Application responsibilities

- Topic names, consumer groups, event semantics
- Serializers/deserializers and payload format
- Producer acks, retries, idempotence policy
- Consumer offset strategy and acknowledgement mode
- Event processing, validation, and business logic

## Out of scope (v1.0.0)

- ETP Kafka REST proxy or JVM broker service
- UAM topics, consumer groups, handlers
- Confluent Schema Registry in repository
- Automatic Kafka provisioning via Application Composition (future)
- Changes to PostgreSQL, IAM, or Redis capabilities

## Artifacts

| File | Purpose |
|------|---------|
| `kafka-capability-contract.json` | Inline `contract.document` for registry |
| `kafka-capability-registration.json` | `CreateCapabilityRequest` payload (review before POST) |
| `ETP_KAFKA_CAPABILITY_DEPLOYMENT.md` | Deployment and consumption guide |
| `docker-compose.yml` | Local Kafka broker for development |
| `.env.example` | Local environment template |

## Related

- [ETP Redis capability](../redis/) — cache infrastructure (`etp.redis`)
- [ETP PostgreSQL capability](../postgresql/) — datastore (`etp.postgresql`)
- [ETP IAM capability](../etp-iam/) — reference onboarding (`etp.iam` ACTIVE)
- [Capability Registry](../../etp-platform-service/docs/CAPABILITY_REGISTRY.md)
