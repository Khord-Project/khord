# ADR 018: API Versioning — /v1/ Prefix From Day One

**Status:** Accepted
**Date:** 2026-05-02

## Context

If the pre-key bundle format, message envelope structure, or server API endpoints change, clients on older versions need to either still work or fail gracefully.

## Decision

All endpoints on both servers use a `/v1/` prefix from day one. When a breaking change is needed, `/v2/` endpoints go live alongside `/v1/`. Old clients keep working until a version is actively sunset.

## Consequences

- Cheap to implement now, prevents "force everyone to update simultaneously" later.
- Version sunset policy is a future operational decision, not an architectural one.
- Client must send its supported API version in requests so the server can respond appropriately.
