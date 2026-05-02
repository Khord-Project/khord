# ADR 001: Threat Model Tiers

**Status:** Accepted
**Date:** 2026-05-02

## Context

"Absolute privacy" must be decomposed into concrete, testable properties. Different threat levels require different technical mechanisms, have different performance costs, and different feasibility profiles. We need to explicitly define what Khord protects against and what it does not.

## Decision

Khord's threat model is organized into four levels:

**Level 1 — E2E Encryption (Hard requirement)**
The server operator cannot read message contents. All messages are end-to-end encrypted using the Signal Protocol (X3DH + Double Ratchet). Servers only handle opaque ciphertext.

**Level 2 — Metadata Minimization (Hard requirement)**
The server operator cannot determine who talks to whom. Achieved through the split-trust two-server architecture: the Key Server knows identities but not messaging patterns, the Relay Server routes messages but doesn't know identities.

**Level 3 — Traffic Analysis Resistance (Design for, do not implement in PoC)**
An observer cannot determine communication patterns from traffic analysis. Mitigations include padding, decoy traffic, key pre-fetching, and artificial delays. The architecture must not prevent these from being added later.

**Level 4a — Jurisdictional Resistance (Addressed architecturally)**
A state-level adversary using legal compulsion cannot obtain the full picture. Addressed by the split-trust model with independent operators in different legal jurisdictions. No single legal authority can compel both operators.

**Level 4b — Passive Network Observation (Acknowledged, architecture must not prevent future mitigation)**
A state-level adversary passively observing network traffic cannot correlate users. This requires Tor integration, mixnets, or similar. Not in scope for PoC, but the architecture must not preclude these mitigations.

## Consequences

- All server-side design must assume adversarial operators — servers must be unable to derive protected information even if they try.
- Level 3 mitigations will add latency and bandwidth overhead when implemented. Performance-sensitive design decisions should account for this future cost.
- Level 4a requires that the two servers share zero state and have no communication channel between them.
- The PoC is honest about what it does and does not protect against. Documentation must clearly state current protection levels.
