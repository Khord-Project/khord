# ADR 016: Logging Policy — Allow-List Only

**Status:** Accepted
**Date:** 2026-05-02

## Context

Servers need operational observability (crash detection, load monitoring, storage tracking) but every log line is a potential metadata leak.

## Decision

**Allow-list logging only.** Servers may only log fields from an explicit allow-list. Anything not on the list is forbidden.

**Allowed:**
- Server health metrics (CPU, memory, disk usage)
- Aggregate request counts (total requests per endpoint per time window, not per-mailbox)
- Error rates and error types (without request-specific context)
- Uptime and restart events

**Never logged:**
- IP addresses
- Mailbox IDs
- Bearer tokens
- Request timing correlated to specific mailboxes
- Any content from payloads
- Key fingerprints beyond aggregate counts

**Enforcement:** Code review must verify that no log line references request-specific data. The allow-list approach is safer than a deny-list — "you may only log these things" rather than "don't log these things."

## Consequences

- Debugging production issues is harder without request-specific logs. This is an accepted trade-off.
- Any log line referencing a mailbox ID, token, or request-specific data is a bug, not a feature.
- Structured logging frameworks should be configured to strip sensitive fields automatically.
