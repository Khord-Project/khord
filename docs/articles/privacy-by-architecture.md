# Khord: Privacy by Architecture

---

Encrypted messaging has come a long way. Apps like Signal and Threema have made end-to-end encryption the baseline — your messages genuinely are private, even from the service operator. That's a real achievement. But encryption protects the content, not the context. The metadata — who you talk to, when, how often — still flows through a single server infrastructure. Not because these apps are careless, but because their architecture wasn't designed to separate it. The messages are unreadable. The patterns are not.

Khord takes a different approach.

## The split-trust architecture

Instead of one server handling everything, Khord splits the work between two independent servers that each see only half the picture:

**The Key Server** stores your public encryption keys. It knows your cryptographic identity — but it has no concept of messages, conversations, or who you communicate with. Think of it as a public phone book that lists your number but doesn't record your calls.

**The Relay Server** delivers your encrypted messages. It routes sealed envelopes between mailboxes — but it doesn't know who owns those mailboxes or what's inside the envelopes. It's a postal service that handles only opaque, sealed packages with no return address.

Neither server alone can answer the question: **"What did Alice say to Bob, and when?"**

The Key Server could tell you Alice exists. The Relay Server could tell you that mailbox X received a message at a certain time. But the Key Server doesn't know about mailbox X, and the Relay Server doesn't know that mailbox X belongs to Alice. Connecting those dots requires access to both servers simultaneously — and the architecture is designed so that doesn't happen.

## Building on Signal and Threema

Khord doesn't reinvent the cryptography. It uses the same proven protocols as Signal — [X3DH](https://signal.org/docs/specifications/x3dh/) for initial key agreement and the [Double Ratchet](https://signal.org/docs/specifications/doubleratchet/) for ongoing message encryption. Every message gets its own unique encryption key. Past messages can't be decrypted even if current keys are compromised (forward secrecy). Security automatically recovers after a compromise (post-compromise recovery). These protocols are peer-reviewed, battle-tested, and trusted by security researchers worldwide. Khord adopts them wholesale.

What Khord tries to push further is the server architecture. Signal and Threema both solved the hard problem of making message content unreadable to the server. Khord asks: can we do the same for the metadata? Can the server infrastructure be designed so that no single operator sees both who you are and who you talk to?

That's what the split-trust model attempts. Rather than trusting one server operator's policy not to examine metadata, the architecture splits the metadata across two independent servers so that neither one holds enough to reconstruct the picture. It's not a criticism of Signal's approach — it's an attempt to extend the same philosophy of structural privacy guarantees from message content to message metadata.

## No phone number. No email. No account.

There's no sign-up form. No phone number, no email address, no username. When you install Khord, the app generates a cryptographic key pair on your device — that key IS your identity. It's derived from a seed phrase (twelve words) that you write down and keep safe. The servers don't have a concept of "accounts." The Key Server stores your public key so others can encrypt messages to you. That's the extent of what any server knows about you.

You add contacts by scanning QR codes — in person, or by sharing a contact link through a channel you trust. When you scan someone's code, you receive their actual public key directly. Later, when Khord fetches their encryption material from the Key Server to start a conversation, it compares what the server provides against the key you received from the QR code. If they don't match — whether through server error, compromise, or an attempt to substitute a malicious key — the app rejects the connection. The QR code is the source of truth, not the server.

For remote contact exchange, Khord generates a shareable contact link that you can send through any channel — Signal, email, even written on paper. The link contains only public information, so sharing it widely isn't a security risk. If you receive a link rather than scanning a code in person, it's worth confirming it actually came from who you think it did. The link tells Khord how to reach someone. It doesn't prove who that someone is.

## Per-contact mailboxes

Most messaging systems give each user a single inbox. All your messages arrive at one place. The server can see exactly how many conversations you have and how active each one is.

Khord creates a separate, unlinkable mailbox for each contact relationship. Your conversation with Alice uses one mailbox. Your conversation with Bob uses a completely different one. The Relay Server has no way to determine that both mailboxes belong to the same person — they're random identifiers with no shared metadata.

Messages are deleted from the server immediately upon delivery confirmation. No persistent storage, no message history on any server. Your chat history lives exclusively on your device, encrypted at rest.

## The panic button

One tap. Everything gone. Your identity, contacts, messages, encryption keys — wiped from the device in milliseconds. The encryption key that protected the local database is destroyed in Android's secure hardware, making the database file unreadable even if physically recovered from the device's storage. On modern devices with hardware-backed key storage, this destruction is permanent — no forensic process can reconstruct the key.

For the privacy-conscious: this is peace of mind. For the security-critical: this is a necessary feature. Most people will never tap it. It's there for the ones who might need to.

## Self-hosting: your servers, your rules

Khord is designed for self-hosting. The entire server stack — Key Server, Relay Server, and their databases — runs in Docker containers with automatic TLS via Caddy or your existing reverse proxy.

**For organizations:** run both servers on your own infrastructure. Your communication metadata never leaves your network. Your IT team controls the encryption key storage, the message retention policy, and the access logs.

**For individuals:** use the community servers at `keys.khord.org` and `relay.khord.org` to get started. Want more control? Run your own Relay Server — a single Docker Compose command — and use the community Key Server for key distribution. The Key Server only holds public keys, so this gives you full control over your communication metadata while keeping the convenience of a shared key directory.

**For the security-conscious middle ground:** run your own Relay Server, use the community Key Server. The Khord project can see your public key exists. It cannot see who you talk to, when, or how often — because that data flows through your server, not ours.

**The bigger picture:** the community servers are a starting point, not the destination. The architecture is designed so that anyone — a non-profit, a university, a local government, a community organization — can run their own servers. No federation protocol needed. No permission from us. If you can run a Docker container, you can run a Khord server. The more independent operators exist, the more resilient and trustworthy the network becomes.

## Where Khord stands today

Khord is in its early stages. The core is working — two real devices exchanging end-to-end encrypted messages through the split-trust architecture — and the foundation is solid: proven cryptographic protocols, a widely-audited encryption library, and test coverage validated against published reference standards. But there's a roadmap ahead: group messaging, media sharing, iOS support, background notifications, and a professional security audit are all planned milestones. This is the beginning of the project, not the finished product.

**Khord is open source.** The server code is [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.en.html), which means anyone running a modified version must publish their changes. You can verify that the server code you audit is the code actually running. The protocol specification is [CC-BY-SA-4.0](https://creativecommons.org/licenses/by-sa/4.0/), so anyone can build a compatible client or server without adopting the AGPL.

**Khord works without Google Play Services.** The app uses no proprietary Google components — QR scanning uses the open-source ZXing library, push notifications are planned via the open UnifiedPush standard. This means it can be distributed through [F-Droid](https://f-droid.org/) and sideloaded on degoogled devices. Distribution through Google Play is possible in the future but not a dependency.

**Khord is not yet feature-complete.** Group messaging, media attachments, voice calls, and multi-device sync are on the roadmap but not implemented. The current release supports one-to-one text messaging with the full encryption and privacy architecture in place.

## Try it

**As a user:** install the APK, choose "Use Khord Community Servers" during setup, and exchange QR codes with someone you trust.

**As a self-hoster:** clone the repository, follow the five-step deployment guide in `deploy/README.md`, and share your server URLs with your community.

**As a contributor:** the protocol specification, architectural decision records, and deferred decisions document are all in the repository. We know what's been decided, what's been deferred, and why.

The code lives at [github.com/khord/khord](https://github.com/khord/khord).

---

*Khord is built on the principle that privacy should be structural, not just promised. The architecture enforces it. The source code proves it.*
