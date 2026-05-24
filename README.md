# Document Version Update Events

> **[▶ Project walkthrough (Loom)](https://www.loom.com/share/2535f2b6bf924222927a7afac7428c4d)** — demo of the service (Docker Compose, competing consumers, reorder/pending, retries). Design rationale and requirements mapping: [`SOLUTION.md`](SOLUTION.md).

Spring Boot service that consumes legal-document revision events from a message broker, applies them in per-document sequence order, and exposes document search over PostgreSQL full-text search.

The vetting spec targets **at-least-once**, **non-FIFO** brokers (e.g. **Amazon SQS standard**): short-window reordering, duplicate `event_id`, multi-instance consumers, and hot-document bursts. **Local/docker** use **ActiveMQ + JMS**; the **`aws`** profile uses **RDS PostgreSQL** and an **existing SQS standard queue** — see **[Deploy on AWS](#deploy-on-aws)**.

## Prerequisites

- **Java 17+**
- **Maven 3.9+** (or use the included `./mvnw` wrapper)
- **Docker** and **Docker Compose** (for the multi-instance local stack)
- For local (non-Docker) development: **PostgreSQL 16** and **Apache ActiveMQ** reachable on your machine

## Configuration

Runtime configuration lives under `src/main/resources/` only (there is no root `application.properties`).

| File | Purpose |
|------|---------|
| [`application.properties`](src/main/resources/application.properties) | Default profile — local Postgres on port **5432**, ActiveMQ on **61616**, HTTP port **3030** |
| [`application-docker.properties`](src/main/resources/application-docker.properties) | `docker` profile — Postgres on host port **5433**, ActiveMQ on host port **61617** |
| [`application-aws.properties`](src/main/resources/application-aws.properties) | `aws` profile — **RDS** via env vars, **SQS** consumer, demo endpoints off by default |

Schema migrations run automatically on startup via **Flyway** (`lexis-nexis-events` schema).

### Environment variables (optional)

| Variable | Profile | Default | Description |
|----------|---------|---------|-------------|
| `DB_PASSWORD` | default | `postgres` | Local PostgreSQL password |
| `ACTIVEMQ_PASSWORD` | default | `admin` | Local ActiveMQ password |
| `DOCKER_DB_PASSWORD` | docker | `postgres` | Postgres password when using `docker` profile |
| `DOCKER_ACTIVEMQ_PASSWORD` | docker | `admin` | ActiveMQ password when using `docker` profile |
| `SERVER_PORT` | any | `3030` | HTTP port (set per instance in Compose) |

### Environment variables (`aws` profile)

| Variable | Required | Description |
|----------|----------|-------------|
| `DB_HOST` | yes | RDS endpoint hostname |
| `DB_USERNAME` | yes | Database user |
| `DB_PASSWORD` | yes | Database password |
| `AWS_REGION` | yes | AWS region (SQS + SDK) |
| `AWS_SQS_REVISION_EVENTS_QUEUE_URL` | yes | Standard queue URL |
| `DB_PORT` | no | Default `5432` |
| `DB_NAME` | no | Default `postgres` |
| `DB_SCHEMA` | no | Default `lexis-nexis-events` |
| `DB_JDBC_PARAMS` | no | Default `&sslmode=require` for RDS |
| `APP_DEMO_ENDPOINTS_ENABLED` | no | Default `false` on AWS |
| `SERVER_PORT` | no | Default `3030` |

Full ECS example: [`deploy/aws/env.example`](deploy/aws/env.example).

---

## Run with Docker Compose (recommended)

[`docker-compose.yml`](docker-compose.yml) starts **two application instances** that compete on the same JMS **queue** (multi-instance consumer pattern), plus shared infrastructure.

### Services and ports

| Service | Container name | Host port | Notes |
|---------|----------------|-----------|--------|
| PostgreSQL | `postgres` | **5433** → 5432 | Avoids conflict with a local Postgres on 5432 |
| ActiveMQ (OpenWire) | `activemq` | **61617** | JMS broker (container listens on 61616) |
| ActiveMQ (console) | `activemq` | **8162** | Web UI — login `admin` / `admin` |
| App instance 1 | `document-version-update-events-1` | **3030** | `SPRING_PROFILES_ACTIVE=docker` |
| App instance 2 | `document-version-update-events-2` | **3031** | Second consumer instance |

Inside the Compose network, apps connect to `postgres:5432` and `activemq:61616` (set via environment variables in the compose file). From your **host machine** (e.g. DBeaver or a locally run app with the `docker` profile), use **`localhost:5433`** for Postgres.

### Start the full stack

From the project root:

```bash
docker compose up --build
```

Run detached:

```bash
docker compose up --build -d
```

Follow logs:

```bash
docker compose logs -f document-version-update-events-1 document-version-update-events-2
```

Stop and remove containers (keep database volume):

```bash
docker compose down
```

Stop and remove containers **and** the Postgres volume:

```bash
docker compose down -v
```

### Verify the stack

**Search** (either instance):

```bash
curl "http://localhost:3030/document/search?q=revision&limit=10"
curl "http://localhost:3031/document/search?q=revision&limit=10"
```

**Demo** — clears DB tables, then **enqueues** sample revisions on `document.revision.events` so **all running instances** share consumption (typical `docker compose up` with two app services):

```bash
curl -X POST http://localhost:3030/demo/revision-events/start
```

### Compose environment overrides

Each app service sets:

```yaml
SPRING_PROFILES_ACTIVE: docker
APP_DOCKER_POSTGRES_HOST: postgres      # in-network hostname
APP_DOCKER_POSTGRES_PORT: 5432         # container port (not 5433)
APP_DOCKER_ACTIVEMQ_HOST: activemq
APP_DOCKER_ACTIVEMQ_PORT: 61616
```

You normally do not need to change these unless you rename services in `docker-compose.yml`.

### Run only infrastructure in Docker

Useful when debugging a single app from the IDE:

```bash
docker compose up postgres activemq
```

Then run the app on the host with the `docker` profile (connects to `localhost:5433` and `localhost:61617`):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=docker
```

On Windows:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=docker"
```

---

## Run locally (without Docker for the app)

1. Start PostgreSQL (schema `lexis-nexis-events` is created by Flyway) and ActiveMQ on the default ports.
2. Ensure credentials match `application.properties` or set `DB_PASSWORD` / `ACTIVEMQ_PASSWORD`.
3. Build and run:

```bash
./mvnw spring-boot:run
```

The API listens on **http://localhost:3030** (port from `server.port` in `pom.xml`).

Package and run the JAR:

```bash
./mvnw -DskipTests package
java -jar target/document-version-update-events-0.0.1-SNAPSHOT.jar
```

---

## Build and test

```bash
./mvnw test
./mvnw -DskipTests package
```

Integration tests use **Testcontainers** for PostgreSQL and require Docker to be running.

---

## HTTP API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/document/{documentId}` | Current document state by id (assignment section 7); **404** if unknown |
| `GET` | `/document/search?q={text}&limit={n}` | Full-text search over document title/body |
| `POST` | `/demo/revision-events/start` | Start demo event simulation (development only) |

Example:

```bash
curl "http://localhost:3030/document/doc-hot-001"
```

---

## JMS consumer (current implementation)

- Destination (queue): `document.revision.events`
- Broker: **Apache ActiveMQ** (OpenWire), Spring **`JmsListener`**
- Mode: point-to-point queue (`spring.jms.pub-sub-domain=false`) so multiple instances share load
- Wire format: JSON text messages (`MappingJackson2MessageConverter`; type id property `_type`). See [`RevisionEvent.java`](src/main/java/com/ryanwoolf/document_version_update_events/model/RevisionEvent.java).
- Message body is **assignment-shaped**: **snake_case** keys (`event_id`, `document_id`, `event_type`, `timestamp`, `payload`), with **`event_type`**: `CREATE` (first revision, `sequence` **1**) or `UPDATE`, and **`timestamp`** as ISO-8601 instant (e.g. ending in **`Z`**). [`JmsConfig`](src/main/java/com/ryanwoolf/document_version_update_events/config/JmsConfig.java) registers **`JavaTimeModule`** and disables numeric date timestamps so **`Instant`** serializes as an ISO string.

---

## Deploy on AWS

The **`aws`** Spring profile connects to **RDS PostgreSQL** and consumes an **existing SQS standard queue** (not FIFO). ActiveMQ is disabled; JMS beans load only when `aws` is **not** active.

| Piece | Service | Notes |
|-------|---------|--------|
| API + consumers | **ECS Fargate** (≥ 2 tasks) | Same JVM: HTTP + `@SqsListener` |
| Database | **RDS PostgreSQL** | Flyway on startup; schema `lexis-nexis-events` |
| Messaging | **SQS standard queue** | Set `AWS_SQS_REVISION_EVENTS_QUEUE_URL` |
| Image | **ECR** | Build with root [`Dockerfile`](Dockerfile) |
| Health | **Actuator** | `/actuator/health` for ALB / ECS |

**Code paths:** [`DocumentEventSqsConsumer`](src/main/java/com/ryanwoolf/document_version_update_events/consumer/DocumentEventSqsConsumer.java) (`aws`), [`DocumentEventConsumer`](src/main/java/com/ryanwoolf/document_version_update_events/consumer/DocumentEventConsumer.java) (`!aws`), shared [`RevisionEventHandler`](src/main/java/com/ryanwoolf/document_version_update_events/consumer/RevisionEventHandler.java).

**Steps:** see [`deploy/aws/README.md`](deploy/aws/README.md). Set `SPRING_PROFILES_ACTIVE=aws` and env vars from [`deploy/aws/env.example`](deploy/aws/env.example).

**Verify:**

```bash
aws sqs send-message --queue-url "$AWS_SQS_REVISION_EVENTS_QUEUE_URL" \
  --message-body '{"event_id":"evt-1","document_id":"doc-aws-001","sequence":1,"event_type":"CREATE","timestamp":"2026-05-19T12:00:00Z","payload":{"title":"AWS","body":"test"}}'
curl "https://YOUR-ALB/document/doc-aws-001"
```

Tune SQS **visibility timeout** and use a **DLQ** for poison messages. Demo HTTP is off by default (`APP_DEMO_ENDPOINTS_ENABLED=false`).

---

## Troubleshooting

| Issue | Suggestion |
|-------|------------|
| Port **5432** already in use | Compose maps Postgres to **5433** on the host; use `localhost:5433` with the `docker` profile |
| Port **3030** / **3031** in use | Change host mappings in `docker-compose.yml` and matching `SERVER_PORT` env vars |
| Port **61616** / **8161** in use (local ActiveMQ) | Compose maps broker to **61617** and console to **8162** on the host; stop the other broker or change mappings in `docker-compose.yml` |
| Flyway / schema errors on restart | `docker compose down -v` to reset the Postgres volume, then `up --build` again |
| ActiveMQ connection refused | Wait for `activemq` to finish starting, or check `docker compose logs activemq` |
| ShedLock / scheduler errors | Ensure Flyway migration `V2__shedlock.sql` has run in schema `lexis-nexis-events` |
| ECS task cannot reach RDS | Security group: allow **5432** from task SG to RDS; check `DB_HOST` and `DB_JDBC_PARAMS` (`sslmode=require` for RDS) |
| SQS messages not consumed | Task role SQS permissions; `AWS_SQS_REVISION_EVENTS_QUEUE_URL`; NAT or VPC endpoint for SQS from private subnets |
| `@SqsListener` + DevTools | Do not enable devtools on AWS images (known classloader issue with SQS listeners) |
| Docker build: `wget: bad address 'repo.maven.apache.org'` | The `Dockerfile` uses the official Maven image so the wrapper does not download Maven inside Alpine. If dependency download still fails, fix Docker DNS (e.g. Docker Desktop → Settings → Docker Engine: `"dns": ["8.8.8.8","8.8.4.4"]`) or check VPN/firewall |

---

## Further reading

- [Amazon SQS developer guide](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/welcome.html) — visibility timeout, FIFO, DLQ
- [Spring Cloud AWS – documentation](https://docs.awspring.io/spring-cloud-aws/docs/) (verify Spring Boot compatibility before adopting)
- [`SOLUTION.md`](SOLUTION.md) — design notes and requirements mapping (includes the [Loom walkthrough](https://www.loom.com/share/2535f2b6bf924222927a7afac7428c4d))
- [`RELEASE_REMINDER.md`](RELEASE_REMINDER.md) — deployment and migration reminders
