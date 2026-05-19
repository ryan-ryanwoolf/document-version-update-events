# Solution — Document revision events service

This document explains how the implementation meets the vetting assignment, what was deliberately simplified, and how the same design transfers to production on a cloud queue (AWS used as the primary example; the same ideas apply to GCP Pub/Sub or Azure Service Bus in non-session mode)

## Walkthrough video

A short demo of the service (local / Docker setup, demo endpoints, and key behaviors) is available here:

**[Project walkthrough (Loom)](https://www.loom.com/share/2535f2b6bf924222927a7afac7428c4d)**

---

## Architecture (what the code actually does)

- **Inbound path:** `DocumentEventConsumer` listens on a JMS **queue** (`document.revision.events`). Messages are JSON mapped to `RevisionEvent` (Jackson + `JavaTimeModule`, snake_case via `@JsonProperty`). Each message is processed under a **per-document stripe** (`DocumentProcessingStripes`) so only one thread **in this JVM** handles a given `document_id` at a time, while other documents use other stripes. **Stripes do not span instances**—each running container has its own in-memory locks.
- **Core logic:** `DocumentRevisionService.processRevisionEvent` loads `document_state`, enforces **CREATE** vs **UPDATE** rules, applies the next in-sequence revision directly, or **buffers** future sequences in `pending_revision_event`. It then **drains** consecutive pending rows when the gap is filled. **Pessimistic locks** on document and pending rows coordinate with a second instance that might process the same document.
- **Reconciliation:** `PendingRevisionReconciliationScheduler` (ShedLock) periodically finds documents that still have pending rows and calls `reconcilePendingForDocument` (same drain as after an in-order apply). **Happy path:** when the missing sequence arrives, the consumer applies it and drains pending inline—no scheduler required for correctness of reordering. The sweep matters as a **backstop**: e.g. **manual or operational DB fixes** (adjusted `document_state`, repaired `pending_revision_event`, migrations) where no new broker message is published for that document but pending is already drainable; also light **defense in depth** if a future path ever skipped drain.
- **Read path:** `GET /document/{documentId}` returns current applied state from `document_state`. `GET /document/search` runs PostgreSQL **full-text search** over title/body (materialised `tsvector` on `document_state`).
- **Persistence:** PostgreSQL, schema `**lexis-nexis-events`**, Flyway migrations `V1`–`V3` (document + pending tables, ShedLock, `event_type` on pending).
- **Local multi-instance:** Docker Compose runs **two app containers** sharing Postgres and ActiveMQ so both compete on the same queue (see `README.md`).

The vetting spec’s “at-least-once, no strict ordering” broker is modeled with **ActiveMQ + JMS** and a **point-to-point queue** (`spring.jms.pub-sub-domain=false`). Production would swap the listener for SQS / Pub/Sub / etc.; the revision processor and database design stay the same.

**Demo-only HTTP (local / vetting):** `POST /demo/revision-events/start` clears DB tables and enqueues a large interleaved scenario (reordering, duplicates, hot doc, gap reorder, and a **permanently missing sequence** doc). `POST /demo/revision-events` with a `RevisionEvent` JSON body enqueues **one** message (e.g. replay a missing sequence). Disable or protect these in production.

---

## Technology choices (non-trivial)


| Choice                            | Why                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **PostgreSQL**                    | Durable state, transactional processing with JMS, row-level locking for cross-instance safety, native full-text search for “queryable” content without a second store.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| **Flyway**                        | Repeatable schema for `lexis-nexis-events`, matches how RDS (or Cloud SQL) would be managed in production.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **JMS + ActiveMQ (dev only)**     | Matches competing consumers and at-least-once semantics; no FIFO dependency. Queue prefetch tuned via broker URL; listener concurrency and stripes documented in `application.properties` / `application-docker.properties`. **Broker backoff** after a rolled-back transacted receive is governed by the ActiveMQ client `RedeliveryPolicy` (`app.jms.redelivery.*`)—defaults were ~1s, which is easy to mistake for an in-thread retry.                                                                                                                                                                  |
| **ShedLock**                      | Ensures only one instance runs the pending-reconciliation sweep at a time, avoiding duplicate reconcile work while keeping consumers aggressive.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| **In-process stripes + DB locks** | **Stripes are per JVM:** they serialize work for a given `document_id` among listener threads *inside one instance* so those threads do not interleave updates for the same document. With **multiple instances**, two containers can still each process a message for the same `document_id` at the same time—**PostgreSQL pessimistic locks** on `document_state` / `pending_revision_event` are what make that safe: one transaction waits until the other commits. Stripes are therefore an in-process optimization (clarity + less lock contention in the common case); **correctness across instances is entirely database-backed**, not stripe-backed. |
| **No in-process handler retries** | Failures from `RevisionEventHandler` bubble out of the JMS listener so the **transacted session rolls back** and the **broker** redelivers (ActiveMQ redelivery policy, SQS visibility timeout, etc.). Avoids fast in-thread retries that can overload the database while the message is still considered “in flight.”                                                                                                                                                                                                                                          |


**To do before** going to production: Repeated failures should land messages in a **DLQ** and trigger alerts; here the handler throws on failure and relies on broker redelivery (no separate DLQ wiring or storage of failures).

---

## Wire format

Events match the assignment example style: **snake_case** keys, `**event_type`**: `CREATE` | `UPDATE`, `**timestamp`** as ISO-8601 instant (e.g. ending in `Z`). **CREATE** is required for **sequence 1**; **UPDATE** cannot be the first revision; **CREATE** cannot be applied as the next revision once a document exists.

---

## Assignment requirements (traceability)


| #   | Requirement                                       | Implementation                                                                                                                                                                    |
| --- | ------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Consume revision events from the broker           | `DocumentEventConsumer` + `JmsConfig` JSON converter, queue `document.revision.events`.                                                                                           |
| 2   | Current state reflects every **applied** revision | `document_state` updated only when a revision is applied in order; title/body/`latest_sequence`/`last_event_id` reflect head.                                                     |
| 3   | Apply in **sequence order** regardless of arrival | Immediate apply when `sequence == latest + 1`; else insert `pending_revision_event`; drain consecutive pending after apply or via scheduler.                                      |
| 4   | Same `event_id` twice is idempotent               | Discard if `last_event_id` matches; ignore stale `sequence`; skip duplicate pending insert (unique constraint + catch); skip if pending already holds `event_id`.                 |
| 5   | Activity on one document does not block others    | Stripe pool + multiple listener threads + separate DB rows; prefetch limited so one consumer does not hoard the whole queue during bursts.                                        |
| 6   | Responsive under load and hot-document bursts     | Tuning: listener concurrency range, ActiveMQ `queuePrefetch=1`, bounded executor for reconcile tasks; **not** validated here with a formal load benchmark—see “Deliberately cut”. |
| 7   | Fetch document state by id                        | `GET /document/{documentId}` via `DocumentQueryService` (404 if unknown).                                                                                                         |
| 8   | Develop locally, deploy and operate in production | Compose stack + `README.md` runbook; deployment outline below and in `README.md` (SQS-style).                                                                                     |


---

## Correctness and failure modes

- **Short-window reorder:** Pending table + eventual drain handles `1, 3, 2, 4`-style arrival for one document.
- **Duplicates / recovery:** Idempotency on `event_id` and sequence guards prevent double-apply.
- **Multi-instance:** Competing consumers + transaction boundaries + `SELECT … FOR UPDATE` style access on document and pending sets.
- **Permanent sequence gap (beyond spec’s “seconds” reorder):** If sequence `N` never arrives, state stalls at `N-1` and pending holds `N+1`, … Alerting would use `**received_at`** staleness on pending rows; recovery is operational (replay / gap-fill). The demo document `doc-stuck-missing-revision-001` simulates this; `**POST /demo/revision-events`** can enqueue a single missing event for manual unblock.

---

## Deliberately cut or simplified (scope for a time-boxed exercise)

- **Broker:** Production would use **SQS standard** (or equivalent), IAM, DLQ, visibility tuning—not implemented as a second code path; ActiveMQ stands in locally.
- **Security:** No auth on HTTP or broker; `/demo/`* would be disabled or protected in production.
- **Observability:** Structured logs exist; no OpenTelemetry/CW dashboards wired.
- **Load testing:** No committed JMeter/Gatling suite or quoted SLOs for requirement 6—design supports isolation; verify in a follow-on perf pass.
- **Revision history:** Only **current** state is stored; no `document_revision_history` table (not required by spec).
- **Admin API:** Aside from demo replay (`POST /demo/revision-events` with JSON body), no full audit UI or ticket integration.

---

## Deployment plan (production-oriented, AWS-first)

**Runtime:** Container image (same `Dockerfile` as today) on **ECS Fargate** or EKS with **desired count ≥ 2** behind an ALB for `GET /document/...` and search.

**Data:** **RDS PostgreSQL** in private subnets; same Flyway migrations; credentials from **Secrets Manager**. JDBC URL includes schema or `search_path` so application code and migrations stay aligned with `lexis-nexis-events`.

**Messaging:** Use an **existing SQS standard queue** (team-provisioned). Task role: `ReceiveMessage`, `DeleteMessage`, `ChangeMessageVisibility`, `GetQueueAttributes`; optional **DLQ** + max receive count. Replace `JmsListener` with long-polling SQS worker (`@SqsListener` or SDK loop); **do not** rely on FIFO or `MessageGroupId` for correctness—ordering stays in the app.

**Configuration:** Queue URL/region from SSM or environment; profile `**aws`** in Spring (outline only in repo today).

**Operations:** CloudWatch alarms on queue age/depth, DLQ depth, RDS CPU/lag; roll deployments one task at a time; runbook for stuck pending (replay, synthetic gap-fill with audit).

**Local parity:** Keep **Compose + ActiveMQ** for developer velocity; optional later: LocalStack SQS for integration tests.

---

## Relation to `README.md`

Build, run, ports, demo curls, and a longer **SQS migration checklist** live in `README.md`. This file is the **design defense**; the README is the **operator quickstart**.