# ADR 011: Server Authentication — Challenge-Response and Bearer Tokens

**Status:** Accepted
**Date:** 2026-05-02

## Context

Servers must prevent abuse without knowing user identities. The authentication mechanism must maintain the split-trust separation: the Key Server should know identity keys but not mailboxes, and the Relay Server should know mailbox tokens but not identities.

## Decision

**Key Server:** Cryptographic challenge-response. The client proves it holds the private key corresponding to the public identity key it claims to own. No accounts, passwords, or emails. Authentication is "prove you are this key."

**Relay Server:** Opaque bearer tokens. When a mailbox is created, the server returns a random access token. The client presents the token to read or write. The token is unlinked to any identity — the Relay Server cannot determine which identity key a token corresponds to.

**Separation maintained:** Key Server knows identity keys but not mailboxes. Relay Server knows tokens but not identities. Neither can reconstruct the full picture alone.

## Consequences

- Token compromise grants mailbox access. Tokens must be stored securely on the client (Android Keystore alongside identity keys).
- Token rotation is a possible future enhancement — noted but deferred.
- No session cookies, no JWTs with user metadata — just raw cryptographic proof (Key Server) or opaque tokens (Relay Server).
