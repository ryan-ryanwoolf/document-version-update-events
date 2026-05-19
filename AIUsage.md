# AI usage disclosure — document-version-update-events

This document summarizes how AI (Cursor agent / chat) was used while working on this repository during the assessment period. It is intended to give reviewers clarity on what was AI-assisted versus human-directed design and review.

## Tool and mode

- **Tool:** Cursor IDE with an AI coding agent (chat + file edits, terminal commands, tests).
- **Interaction style:** I asked questions, requested implementations, and reviewed or corrected explanations. The agent read the codebase, proposed changes, and ran `mvn test` where relevant.

## Topics covered in this session (by theme)

### 1. Message retry and redelivery (JMS / ActiveMQ)

- **Problem I raised:** Demo retry verification looked like it was not retrying; logs were unclear; redelivery felt too fast (~1s) with no obvious backoff.
- **AI-assisted work:**
  - Removed **Spring Retry** (`@Retryable`) on `RevisionEventHandler` so failures roll back the transacted JMS session and the **broker** redelivers (avoiding a second in-process attempt that acks the message).
  - Adjusted **demo retry verification** to count deliveries across broker redeliveries (`failures-before-success`, default **3** in local `application.properties`).
  - Added logging in `DocumentEventConsumer` (`jms_redelivered`, `JMSXDeliveryCount`) and clearer handler messages.
  - Added **`ActiveMqRedeliveryConfiguration`** and `app.jms.redelivery.*` properties for client `RedeliveryPolicy` (e.g. 5s initial delay, exponential backoff).
- **My role:** I decided broker-only retry was the right model and asked for either removing in-process retries or throwing after exhaustion; I validated behavior from logs.

### 2. Docker and local operations

- **Questions:** Duplicate-looking lines in Docker Desktop logs; how to run Compose without streaming all container logs.
- **AI-assisted work:** Explained log **wrapping/UI artifacts** vs duplicate application logs; documented **`docker compose up -d`** plus **`docker compose logs -f`** for app services only.
- **My role:** Operational preference; no application code change required for the logging view issue.

### 3. Concurrency model (`DocumentProcessingStripes`)

- **Questions:** Implications of more than 64 stripes; whether allocation is random; whether 64 caps concurrent documents; how `ReentrantLock` works; whether interleaving splits the queue.
- **AI-assisted work:** Explanations aligned with `DocumentProcessingStripes.java` and `DocumentEventConsumer` (per-`document_id` serialization in one JVM, hash-based stripe buckets, listener thread pool as the real parallelism cap, queue not split).
- **My role:** I refined my own mental model and wording for interviews/docs; no major code changes driven solely by this Q&A.

### 4. Idempotency and replay (`DocumentRevisionService`)

- **Question:** Whether the `last_event_id` short-circuit works if replaying events from two or three versions ago.
- **AI-assisted work:** Traced flow: duplicate `event_id` discard vs **`sequence <= latestSequence`** stale discard; clarified that older revisions are no-ops by design, not re-applied.
- **My role:** Understanding for assessment defense; informed trust in existing guards.

### 5. Pending reconciliation scheduler

- **Question:** Whether `PendingRevisionReconciliationScheduler` is still needed if the consumer drains on apply.
- **AI-assisted work:** Listed use cases (manual/ops DB fixes, defense in depth); I asked to emphasize **manual processing backstop** in `SOLUTION.md` — text updated accordingly.
- **My role:** I kept the scheduler and wanted the solution document to state why.

### 6. Demo producer (`DemoRevisionEventProducer`)

- **Question:** Purpose of `interleaveDocuments`.
- **AI-assisted work:** Explained round-robin publish order to mimic a shared queue with multiple documents (stripes, competing consumers, pending/drain under mixed load).
- **My role:** Clarification only.

### 7. Refactoring for readability

- **Request:** Delegate responsibility in `processRevisionEvent` (lines ~37–81) into smaller methods.
- **AI-assisted work:** Extracted `shouldDiscardRevisionEvent`, `isAlreadyAppliedDuplicate`, `isStaleOrDuplicateSequence`, `isAlreadyBufferedAsPending`, `tryApplyNextInSequence`; ran tests.
- **My role:** I requested the refactor; I should review diffs for style and correctness.

## What I did not delegate to AI (in this session)

- Final sign-off on architecture trade-offs (e.g. keeping ShedLock scheduler, broker-only retry).
- Running the full demo stack and interpreting production-like behavior in my environment.
- Writing this disclosure itself — I requested it; content reflects this chat.

## Verification I performed (or should perform)

- **`mvn test`** was run by the agent after several changes (reported success).
- Manual checks I used or should use: `POST /demo/revision-events/retry-verification` with `app.demo.retry-verification.enabled=true`, `docker compose up -d` + targeted `logs -f`, demo `start` with interleaved documents.

## How reviewers can interpret this

- **Substantive code changes** in this chat cluster around **JMS redelivery**, **demo retry verification**, **ActiveMQ redelivery tuning**, **`SOLUTION.md` reconciliation wording**, and **`DocumentRevisionService` structure**.
- **Most other items** were **explanations** and **documentation** to support understanding and assessment narrative.
- I treat AI output as **draft**: I read diffs, ask follow-ups, and align explanations with the actual code before relying on them in submission or interview.

## File produced for transparency

- **This file:** `AIUsage.md` (repository root), created at my request to document the above.
