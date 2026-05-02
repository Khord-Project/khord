# ADR 013: Push Notifications — UnifiedPush

**Status:** Accepted
**Date:** 2026-05-02

## Context

When the app is closed and a message arrives, the user needs to be notified. Standard Android push (FCM) involves Google infrastructure, which conflicts with privacy goals and F-Droid distribution.

## Decision

**UnifiedPush** for the PoC. An open standard for push notifications on Android where the user chooses their own push provider (self-hosted, or trusted third-party like ntfy.sh).

Push payloads are always empty — just a wake-up signal. The client then fetches actual messages over its own authenticated connection to the Relay Server. The push provider never sees message content, sender, or mailbox information.

**Abstracted push interface:** The client implements a push listener interface that triggers a mailbox poll. UnifiedPush is the first implementation. FCM can be added as a second implementation later (for eventual Play Store distribution) using the same interface — no refactoring required.

**Relay Server side:** When a blob arrives at a mailbox and no WebSocket is connected, the server dispatches a wake signal through the configured notification provider. This is abstracted behind a notification dispatcher — first implementation calls UnifiedPush, FCM is a future second backend.

## Consequences

- No Google Play Services dependency. Fully compatible with F-Droid.
- Users must configure a UnifiedPush provider — additional onboarding step, but privacy-conscious users expect this.
- Adding FCM later is a configuration change, not an architectural change.
- The push payload is always empty. This is a hard rule — never include content, sender, or mailbox information in the push signal.
