# URL Shortener

A high-throughput URL shortener built as a Gradle multi-module Java project, designed to handle 5 000+ redirects/second with p99 latency under 50 ms on the hot redirect path.

## Deployment Layout

Deployment work is being organized into three layers:

- local development
- shared single-VM deployment
- provider-specific provisioning for GCP, Hetzner, Oracle, and AWS

See:
- [deployment plan](/Users/hemantkgupta/offline/url-shortener/docs/deployment-plan.md)
- [deploy index](/Users/hemantkgupta/offline/url-shortener/deploy/README.md)

---

## Architecture

```
 Browser / Client
       |
       |  GET /{key}               POST /v1/urls
       v                                 |
+----------------+           +---------------------+
| redirect-      |           |  write-service      |
| service :8080  |           |  :8082              |
| (virtual thds) |           |                     |
+-------+--------+           +----------+----------+
        |                               |
        |  cache hit? (L1 Caffeine)     | reserve key batch
        |  cache hit? (L2 Redis)        v
        |  ScyllaDB lookup      +----------------+
        |                       | key-generation |
        +----+------------------| -service :8081 |
             |                  | (etcd counter) |
             |                  +----------------+
             v
    +-----------------+
    |   ScyllaDB      |  <-- primary store (URL mappings)
    |   :9042         |
    +-----------------+
             |
             | Kafka events (visit / create / delete)
             v
    +-----------------+      +-----------------+
    | analytics-      |      |   ClickHouse    |
    | service :8083   |----->|   :8123         |
    | (Flink + API)   |      +-----------------+
    +-----------------+

Observability: all services -> OpenTelemetry Collector :4317/4318 -> SigNoz :3301
```

---

## Tech Stack

| Layer          | Technology                                                      |
|----------------|-----------------------------------------------------------------|
| Backend        | Java 21, Spring Boot 3, Gradle 8, virtual threads               |
| Frontend       | React 18, TypeScript, Vite, port 5173                           |
| Data           | ScyllaDB (primary), Redis (L2 cache), ClickHouse (analytics)    |
| Infrastructure | Docker Compose, etcd (KGS counter), Apache Kafka, Gatling       |
| Monitoring     | SigNoz, OpenTelemetry Java Agent, custom metrics                |

---

## Prerequisites

| Tool          | Version  | Install                                      |
|---------------|----------|----------------------------------------------|
| Docker Desktop| >= 4.x   | https://www.docker.com/products/docker-desktop |
| Java          | 21       | `sdk install java 21-tem` (SDKMAN)           |
| Node.js       | 20       | `nvm install 20 && nvm use 20`               |
| Make          | any      | ships with macOS Xcode CLI tools             |

Install SDKMAN: `curl -s "https://get.sdkman.io" | bash`
Install nvm: `curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash`

---

## Quick Start

```bash
# 1. Clone and enter the project
git clone https://github.com/your-org/url-shortener.git
cd url-shortener

# 2. Optional: review deploy/local/env.example and update ~/.url-shortener-local-dev

# 3. Start the full local stack
bash ./deploy/local/run.sh --npm-install
```

Compatibility wrapper:

```bash
bash ./run-local.sh --npm-install
```

---

## Service URLs

| Service             | URL                        | Notes                          |
|---------------------|----------------------------|--------------------------------|
| redirect-service    | http://localhost:18080     | GET /{key} hot redirect path   |
| write-service       | http://localhost:18082     | POST/DELETE URL management     |
| key-generation-service | http://localhost:18081  | Internal KGS (etcd-backed)     |
| analytics-service   | http://localhost:18083     | Analytics REST API             |
| Frontend            | http://localhost:13000     | React + Vite dev server        |
| SigNoz              | http://localhost:3301      | Manual full-infra profile only |
| Kafka UI            | http://localhost:9080      | Browse topics and messages     |
| RedisInsight        | http://localhost:8001      | Redis browser and profiler     |

---

## Module Descriptions

| Module                    | Description                                                                 |
|---------------------------|-----------------------------------------------------------------------------|
| `core`                    | Shared Java library: domain models, Base62 encoder, common utilities        |
| `key-generation-service`  | Generates unique short keys using a distributed counter stored in etcd      |
| `write-service`           | Handles URL creation, validation, and deletion; publishes Kafka events      |
| `redirect-service`        | Hot redirect path with 3-tier cache (Caffeine → Redis → ScyllaDB) and virtual threads |
| `analytics-service`       | Flink consumer ingests Kafka events into ClickHouse; exposes analytics API  |
| `frontend`                | React + TypeScript SPA for creating, managing, and viewing URL analytics    |

---

## Testing

### Unit Tests
```bash
./gradlew test
```

### Integration Tests
Requires Docker (uses Testcontainers to spin up ScyllaDB, Redis, etcd).
```bash
./gradlew integrationTest
```

### Performance Tests
Target: redirect-service p99 < 50 ms at 5 000 RPS sustained for 5 minutes.
```bash
# Ensure the full stack is running first
make dev
make perf-test
```
Gatling HTML reports are written to `infrastructure/performance-tests/results/`.

---

## Monitoring

SigNoz is available at **http://localhost:3301** after `make infra-up`.

All Spring Boot services are auto-instrumented via the OpenTelemetry Java Agent (`opentelemetry-javaagent.jar`). The agent is attached at startup by `infrastructure/scripts/start-services.sh` and exports traces and metrics to the OTel Collector at `localhost:4317`.

Custom metrics exposed per service:

| Metric                          | Service           | Description                          |
|---------------------------------|-------------------|--------------------------------------|
| `urls.created.total`            | write-service     | Counter of URLs created              |
| `urls.redirected.total`         | redirect-service  | Counter of successful redirects      |
| `cache.hit.ratio`               | redirect-service  | L1/L2 cache hit rate                 |
| `kgs.keys.available`            | key-generation    | Remaining pre-generated key pool     |
| `analytics.events.processed`    | analytics-service | Kafka events consumed by Flink       |

---

## API Reference

| Method | Path                        | Service           | Description                         |
|--------|-----------------------------|-------------------|-------------------------------------|
| POST   | `/v1/urls`                  | write-service     | Create a short URL                  |
| GET    | `/{key}`                    | redirect-service  | Redirect to original URL            |
| DELETE | `/v1/urls/{key}`            | write-service     | Delete a short URL                  |
| GET    | `/v1/urls/{key}/analytics`  | analytics-service | Fetch click analytics for a URL     |

**POST /v1/urls** request body:
```json
{
  "originalUrl": "https://example.com/very/long/path",
  "customAlias": "my-link",   // optional
  "expiresAt": "2026-12-31T00:00:00Z"  // optional
}
```

---

## Key Design Decisions

| Decision                | Rationale                                                                                      |
|-------------------------|------------------------------------------------------------------------------------------------|
| 302 (not 301) redirect  | Prevents browsers from caching redirects permanently, keeping analytics accurate               |
| Counter-based KGS       | A single etcd atomic counter converted to Base62 gives collision-free, lexicographically sortable keys without coordination overhead |
| 3-tier cache            | Caffeine (in-process, ~1 µs) → Redis (shared, ~1 ms) → ScyllaDB (durable, ~5 ms) minimises DB load on the hot path |
| ScyllaDB                | Wide-column store with predictable low latency and linear horizontal scalability               |
| Bloom filter gate       | A Bloom filter in redirect-service fast-rejects lookups for non-existent keys before hitting any cache tier |
| Virtual threads         | Java 21 virtual threads on redirect-service allow high concurrency without thread-pool tuning  |

---

## Development Tips

### Reset everything and start fresh
```bash
make clean       # ./gradlew clean + docker compose down -v (removes volumes)
make infra-up
make init-db
```

### View live logs from infrastructure
```bash
make infra-logs
# or a specific container
docker compose -f infrastructure/compose/docker-compose.dev.yml logs -f scylladb
```

### View live logs from a Spring Boot service
```bash
tail -f logs/redirect-service.log
```

### Rebuild a single Gradle module
```bash
./gradlew :redirect-service:build
```

### Open useful UIs quickly
```bash
make signoz      # prints http://localhost:3301
make kafka-ui    # prints http://localhost:9080
make redis-ui    # prints http://localhost:8001
```
