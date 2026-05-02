# ADR 019: Deployment — Docker Compose for PoC

**Status:** Accepted
**Date:** 2026-05-02

## Context

The PoC needs a simple deployment model that mirrors the production separation at the container level.

## Decision

Docker Compose with four containers:
1. Key Server (FastAPI)
2. Key Server database (PostgreSQL)
3. Relay Server (FastAPI)
4. Relay Server database (PostgreSQL)

No shared networks between the Key Server pair and the Relay Server pair. This mirrors the production separation (different infrastructure, different operators) at the container level.

For production: the Key Server and Relay Server move to separate infrastructure under separate control. Because they share zero state and have no communication channel, this is a deployment configuration change, not an architectural change.

## Consequences

- Development and testing can run on a single machine.
- The Docker Compose configuration serves as documentation of the separation requirements.
- Network isolation between container pairs can be enforced via Docker network configuration.
- Key Server governance (non-profit operation, audit obligations) is an operational decision deferred to production planning.
