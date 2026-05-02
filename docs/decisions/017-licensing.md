# ADR 017: Licensing — AGPL Code, CC-BY-SA Protocol Spec

**Status:** Accepted
**Date:** 2026-05-02

## Context

Licensing affects who can use, modify, and deploy Khord, and what transparency obligations they have.

## Decision

**All code (servers + client): AGPL-3.0.** Anyone who modifies the code and runs it as a service must publish their modifications. This prevents someone forking the server, adding hidden logging, and offering it as a "privacy" service without disclosing the changes.

**Protocol specification: CC-BY-SA-4.0.** The protocol spec is openly licensed so anyone can build a compatible client or server without AGPL obligations. This encourages ecosystem diversity — users don't have to trust a single client implementation.

**Reproducible builds (production requirement, not PoC).** Anyone can take the published source, build it themselves, and verify they get an identical binary. Combined with AGPL source disclosure, this allows independent verification that the running code matches the source. Documented as a production requirement in the README.

## Consequences

- F-Droid compatibility is ensured — AGPL and all dependencies must be FOSS.
- Third-party clients can be built against the CC-BY-SA protocol spec without adopting AGPL.
- The anti-tampering stack is: AGPL forces transparency, reproducible builds enable verification, split-trust ensures neither operator alone can compromise users.
