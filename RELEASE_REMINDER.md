# Release reminder

## Database migrations (Flyway)

Schema changes are applied automatically on startup via Flyway (`src/main/resources/db/migration/`).

Current migrations:

- `V1__document_revision_tables.sql` — `document_state`, `pending_revision_event`, and indexes
- `V2__shedlock.sql` — ShedLock table for distributed scheduler locking

Flyway creates the `lexis-nexis-events` schema automatically (`spring.flyway.create-schemas=true`).

`spring.jpa.hibernate.ddl-auto` is set to `validate`; Hibernate will not create or alter tables.

Migrations use `IF NOT EXISTS` / conditional `DO` blocks so they are safe to re-run manually (e.g. after a partial failure). Flyway still records each version once in `flyway_schema_history`.

For new environments, ensure PostgreSQL is reachable and the application datasource credentials are configured before first startup.

If the database already has tables created by Hibernate `ddl-auto=update`, baseline Flyway before deploying:

```bash
mvn flyway:baseline -Dflyway.schemas=lexis-nexis-events
```

Or drop and recreate the schema in non-production environments.

## JMS queue

Revision events are consumed from an ActiveMQ **queue** named `document.revision.events` (`app.jms.revision-events-destination`). The queue is created on first use. Do not switch to a topic for multi-instance deployments unless every instance is intended to receive every message.
