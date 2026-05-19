# Document Version Update Events

> **[▶ Project walkthrough (Loom)](https://www.loom.com/share/2535f2b6bf924222927a7afac7428c4d)** — demo of the service (Docker Compose, competing consumers, reorder/pending, retries). Design rationale and requirements mapping: [`SOLUTION.md`](SOLUTION.md).

Spring Boot service that consumes legal-document revision events from a message broker, applies them in per-document sequence order, and exposes document search over PostgreSQL full-text search.

The vetting spec targets **at-least-once**, **non-FIFO** brokers (e.g. **Amazon SQS standard**): short-window reordering, duplicate `event_id`, multi-instance consumers, and hot-document bursts. This prototype uses **ActiveMQ + JMS** locally to mimic that; the section **Deploy on AWS with Amazon SQS** below outlines wiring to a **pre-existing SQS standard queue**.

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

Schema migrations run automatically on startup via **Flyway** (`lexis-nexis-events` schema).

### Environment variables (optional)

| Variable | Profile | Default | Description |
|----------|---------|---------|-------------|
| `DB_PASSWORD` | default | `postgres` | Local PostgreSQL password |
| `ACTIVEMQ_PASSWORD` | default | `admin` | Local ActiveMQ password |
| `DOCKER_DB_PASSWORD` | docker | `postgres` | Postgres password when using `docker` profile |
| `DOCKER_ACTIVEMQ_PASSWORD` | docker | `admin` | ActiveMQ password when using `docker` profile |
| `SERVER_PORT` | any | `3030` | HTTP port (set per instance in Compose) |

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

## Deploy on AWS with Amazon SQS (outline)

This repo is **not** wired for SQS today—it uses **ActiveMQ + JMS**. The **technical vetting spec** describes production-like delivery: **at-least-once**, **no strict ordering** (think **Amazon SQS standard queue** or Pub/Sub in default mode), short-window **reordering per document**, duplicates on recovery, and **hot-document bursts**. The implementation here (pending buffer, sequence application order, `event_id` idempotency, per-document stripes, competing consumers) is aimed at that model.

For AWS, assume the **standard queue already exists** (platform team, Terraform, etc.). This service only needs **IAM**, configuration (**queue URL** or identifier + region), and the code changes below—**not** FIFO queues: ordering stays **application-defined**, not broker-defined.

### Message shape (spec vs this repo)

[`RevisionEvent`](src/main/java/com/ryanwoolf/document_version_update_events/model/RevisionEvent.java) uses **`@JsonProperty`** so JMS/SQS JSON matches the spec’s **snake_case** wire names; **`event_type`** is **`CREATE`** | **`UPDATE`**, and **`DocumentRevisionService`** rejects mismatches (e.g. **UPDATE** as the first revision). For SQS, use the same Jackson setup when deserializing the message body.

### AWS architecture (example)

| Piece | AWS service (example) | Role |
|-------|------------------------|------|
| API + queue workers | **ECS on Fargate** (desired count ≥ 2) or **App Runner** with steady capacity | HTTP + **long-polling** SQS workers in the same JVM (mirrors multi-instance ActiveMQ consumers) |
| PostgreSQL | **RDS for PostgreSQL** (private subnets) | Same Flyway migrations and schema as today |
| Revision queue | **Existing Amazon SQS standard queue** | Competing consumers; **no FIFO**, no reliance on `MessageGroupId` for correctness |
| Secrets | **Secrets Manager** or **SSM Parameter Store** | DB password; queue URL if treated as secret |
| Container image | **ECR** | Same `Dockerfile` after SQS code path is added |
| Networking | **VPC** + private tasks/RDS; **NAT** or **VPC endpoints** for SQS/API | Security group: tasks → RDS **5432**; HTTPS to SQS |

**IAM (task role)** — on the **existing queue ARN**: `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:GetQueueAttributes`, `sqs:ChangeMessageVisibility`; plus `kms:Decrypt` if the queue uses SSE-KMS. Publishers (upstream) use `sqs:SendMessage`.

### SQS standard queue vs current JMS

| Topic | ActiveMQ (today) | SQS standard (target) |
|-------|------------------|-------------------------|
| Delivery | At-least-once typical | **At-least-once** — duplicates and retries match spec; **`event_id`** idempotency must hold |
| Ordering | Competing consumers; app buffers gaps | **Best-effort / arbitrary interleaving** — same as spec; **no FIFO queue** |
| Ack | JMS transacted session | **DeleteMessage** after successful handling; failures leave message invisible until visibility timeout; **DLQ** + max receive count for poison pills |
| Long poll | Broker push | Queue attribute **`ReceiveMessageWaitTimeSeconds`** (e.g. 20s) |

Tune **visibility timeout** above worst-case handling for one full listener attempt (no extra in-process retries after `RevisionEventHandler` throws).

### Application code changes (checklist)

1. **Dependencies (Maven)**  
   - Remove **`spring-boot-starter-activemq`** from the `aws` profile (or gated dependency), or keep it only for **`docker`** / local dev.  
   - Add **Spring Cloud AWS** **`spring-cloud-aws-starter-sqs`** (or AWS SDK v2 + a small listener wrapper), with a BOM version **verified** against Spring Boot **4.x**.

2. **Configuration**  
   - Add profile **`aws`** (or `prod`): **`spring.cloud.aws.sqs`**, queue name or URL supplied by env/SSM (value from **already-provisioned** queue).  
   - Keep **`docker`** + ActiveMQ for Compose until you adopt **LocalStack** SQS locally.

3. **Consumer**  
   - Replace [`DocumentEventConsumer`](src/main/java/com/ryanwoolf/document_version_update_events/consumer/DocumentEventConsumer.java): **`@JmsListener`** → **`@SqsListener`**.  
   - Deserialize body to **`RevisionEvent`** with **snake_case**-compatible Jackson; branch **CREATE** vs **UPDATE** in [`DocumentRevisionService`](src/main/java/com/ryanwoolf/document_version_update_events/service/DocumentRevisionService.java) per spec.  
   - Keep **[`DocumentProcessingStripes`](src/main/java/com/ryanwoolf/document_version_update_events/config/DocumentProcessingStripes.java)** + [`RevisionEventHandler`](src/main/java/com/ryanwoolf/document_version_update_events/consumer/RevisionEventHandler.java).  
   - On failure, do **not** ack valid poison messages indefinitely — rely on **redrive** to DLQ after **maxReceiveCount**.

4. **Producer (demo / tests only)**  
   - Replace **`JmsTemplate`** in [`DemoRevisionEventProducer`](src/main/java/com/ryanwoolf/document_version_update_events/demo/DemoRevisionEventProducer.java) with **`SqsTemplate`** / **`SqsAsyncClient`**: **standard** `SendMessage`, body = spec-shaped JSON (**no** `MessageGroupId` / **no** FIFO name).

5. **Remove ActiveMQ-only beans** from the `aws` profile — [`JmsConfig`](src/main/java/com/ryanwoolf/document_version_update_events/config/JmsConfig.java) (`MappingJackson2MessageConverter`, `DefaultJmsListenerContainerFactory`).

6. **Retries / visibility** — Align **visibility timeout** and **SQS receive attempts** so repeated failures eventually land in **DLQ** for inspection (handler does not swallow exceptions).

7. **Local dev** — Optional **LocalStack** SQS + `aws` profile; or keep **ActiveMQ** + `docker` profile for parity with current Compose.

8. **Tests** — Mock SQS or **LocalStack** Testcontainers so CI stays offline.

9. **Observability** — CloudWatch metrics (queue depth, age of oldest message), structured logs with **`document_id`** / **`event_id`**.

### AWS setup (queue assumed to exist)

1. **VPC + RDS** — Same as any ECS/RDS pattern; DB in private subnets, credentials in Secrets Manager.  
2. **SQS** — Use the **provided standard queue URL** (and optional **DLQ** + redrive policy configured by platform). Do **not** require FIFO for this spec.  
3. **ECR + ECS Fargate** — Task role grants SQS permissions on that queue; pass **`QUEUE_URL`**, **`AWS_REGION`**, JDBC settings, **`SPRING_PROFILES_ACTIVE=aws`**.  
4. **ALB** — Target HTTP port for `GET /document/...` and search.  
5. **Production** — Lock down **`POST /demo/...`**.

### Verification

- Enqueue spec-shaped messages (AWS CLI `send-message` or upstream system) and confirm **multiple ECS tasks** consume and **`GET`**/search reflect applied state.  
- Validate **idempotency** (duplicate `event_id`) and **out-of-order** sequences under load.  
- Monitor **ApproximateAgeOfOldestMessage** and DLQ depth.

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
| Docker build: `wget: bad address 'repo.maven.apache.org'` | The `Dockerfile` uses the official Maven image so the wrapper does not download Maven inside Alpine. If dependency download still fails, fix Docker DNS (e.g. Docker Desktop → Settings → Docker Engine: `"dns": ["8.8.8.8","8.8.4.4"]`) or check VPN/firewall |

---

## Further reading

- [Amazon SQS developer guide](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/welcome.html) — visibility timeout, FIFO, DLQ
- [Spring Cloud AWS – documentation](https://docs.awspring.io/spring-cloud-aws/docs/) (verify Spring Boot compatibility before adopting)
- [`SOLUTION.md`](SOLUTION.md) — design notes and requirements mapping (includes the [Loom walkthrough](https://www.loom.com/share/2535f2b6bf924222927a7afac7428c4d))
- [`RELEASE_REMINDER.md`](RELEASE_REMINDER.md) — deployment and migration reminders
