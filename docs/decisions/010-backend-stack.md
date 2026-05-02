# ADR 010: Backend Stack — FastAPI + PostgreSQL

**Status:** Accepted
**Date:** 2026-05-02

## Context

Both servers (Key Server and Relay Server) need a web framework and a database. The servers are deliberately simple — they route opaque blobs and serve key bundles. The framework choice matters less than for a complex application, but operational maturity matters.

## Decision

**FastAPI** for both Key Server and Relay Server. **PostgreSQL** for both, in separate database instances.

### FastAPI rationale

- Mature async framework with native WebSocket support (needed for Relay Server)
- Excellent ecosystem for monitoring, deployment, API documentation
- The servers are thin REST/WebSocket services — FastAPI is more than sufficient
- Team familiarity reduces ramp-up time

### PostgreSQL rationale

- The per-contact mailbox model generates a large number of records (mailboxes scale linearly with contacts × users)
- SQLite's write concurrency limitations would become a bottleneck at scale
- Spinning up PostgreSQL is trivial and avoids a future migration from SQLite
- Both servers use separate PostgreSQL instances — zero shared state

### Why not a shared-language stack (Ktor/Kotlin)

Considered for code sharing between client and server. Rejected because the servers handle opaque blobs — there is almost nothing to share at the code level between client and server. The servers deliberately don't understand what they're handling.

## Consequences

- Two FastAPI services, two PostgreSQL instances, clear separation.
- The database schemas for Key Server and Relay Server share no concepts — different tables, different fields, different concerns.
- asyncpg or SQLAlchemy (async) for database access.
- Docker Compose deployment packages both services with their databases (see ADR 019).
