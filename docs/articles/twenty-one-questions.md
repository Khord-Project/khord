# Twenty-One Questions

*From proof of concept to beta — what it takes to go from "it works" to "people can use this."*

---

We shipped twenty-one alpha releases of Khord before changing the version number to beta. Same code. Different promise.

Each release was a question asked to the real world — to people holding phones on unpredictable networks, not to emulators running on our machines. The answers shaped everything, and surfaced bugs no emulator could have found.

## The Starting Point

Khord started as an idea: what if a messaging app was designed from the ground up to know as little as possible about its users? The architecture followed — two servers that share nothing, end-to-end encryption where neither can read your messages, and no phone number, no email, no account. Your identity is a cryptographic key derived from twelve words.

The architecture was sound. The code compiled. The tests passed. On the emulator, everything worked.

Then we gave it to testers.

## What "Works" Means

The first alphas worked. You could register, add a contact, send a message, and receive a reply. The encryption was real. The servers couldn't read anything. The threat model held.

The first alphas were also unusable.

Messages didn't appear in the chat until you left and came back. Notifications stayed in the shade after you tapped them. You couldn't copy a message. You couldn't scroll the settings screen. The back button crashed the app on Motorola. Xiaomi testers saw "registration state lost" every time they reopened it. The foreground service timed out on Android 14. URLs were just text.

None of these were in the test suite. All of them surfaced in the first hours and days of real-world testing.

## The Grind

What followed was the kind of work that doesn't make for exciting conference talks. Copy and paste support. Notification routing (each notification needs a unique PendingIntent — Android reuses them if you don't). A scrollable settings screen. A clock icon next to messages that haven't sent yet.

Alpha.3 shipped the bug reporting system — one tap from the tester, a structured diagnostic report lands as a GitHub issue. Entirely optional, because even in our own diagnostic tooling we don't collect anything without consent. But for testers who chose to use it, it was priceless. Instead of "it's broken" we got device model, Android version, startup diagnostic path, and stack traces. The questions we asked the real world started coming back with useful answers.

Alpha.7 shipped delete conversation and version display. Not because they were on the roadmap — because testers asked for them.

Alpha.9 shipped a two-line fix that resolved every "registration state lost" report. We'd spent five releases building Xiaomi Keystore mitigations, diagnostic ring buffers, and MIUI-specific warnings. The actual bug was a stale variable reference on line 170 of AppContainer.kt. The AI built the diagnostics. A human noticed that messages still decrypted while the app claimed the identity was gone. That contradiction broke the case open.

Alpha.14 shipped message editing. Alpha.18 shipped quote-reply, contact blocking, full-text search, and F-Droid metadata. Alpha.20 shipped image attachments with EXIF stripping and encrypted thumbnails. Alpha.21 shipped the offline message queue — send a message with no network, it waits and delivers when connectivity returns.

Twenty-one releases. Each one responding to what real devices on real networks did to our assumptions.

## The Features That Don't Make the Highlights

Nobody writes a blog post about notification routing. But if tapping a notification opens the wrong conversation, your tester stops trusting the app. The fix was understanding that Android's PendingIntent system collapses intents with the same shape — you need a unique data URI per contact, not just a different request code.

Nobody celebrates a scrollable settings screen. But if your testers can't reach the theme picker because the content overflows the viewport, they can't customize the app. The fix was one modifier: `Modifier.verticalScroll(rememberScrollState())`.

Nobody pitches "incognito keyboard mode" as a feature. But setting one flag — `privateImeOptions = "nm"` — asks the keyboard not to learn from what the user types. Most keyboards honor it. Your users' message content doesn't train Gboard's prediction model. One line of code, invisible to the user, meaningful for their privacy.

These are the features that make the difference between a demo and a product. They don't appear in architecture diagrams. They appear in the gap between "it compiles" and "I use this every day."

## The Question of Trust

Here's where it gets complicated. Khord is an encrypted messenger. People might trust it with sensitive conversations. How do you know it's safe?

The conventional answer is: get a professional security audit. A team of cryptography specialists examines your code, finds vulnerabilities, and signs off. The report says "we looked, here's what we found, here's what was fixed."

That answer costs fifty to two hundred thousand dollars. For a solo developer building in free time, that's not a budget question — it's an existential one. If a professional audit is the price of entry, most privacy tools built by individuals will never exist.

Khord takes a different position: transparency is the audit mechanism.

The code is open source. The protocol specification is published under CC-BY-SA-4.0. The architecture decisions are documented in thirty ADRs. The threat model is on the website — including an honest "what we don't protect against" section that most commercial apps would never publish. The cryptographic primitives are industry-standard libsodium, not custom implementations.

Anyone with the expertise can examine every line of code, every protocol decision, every key derivation path. The invitation is permanent and unconditional. A professional audit would add confidence, and we'd welcome sponsorship for one. But we won't pretend the code is unreviewed just because no one has been paid to review it. Open source means the review can happen every day, by everyone.

This doesn't make Khord audited. It makes Khord auditable. The difference matters, and we state it clearly.

## What "Ready" Means

So what does it mean to change the label from alpha to beta?

It doesn't mean finished. The roadmap has federation, typing indicators, read receipts, disappearing messages, an iOS client, a web interface. Months of work, at least.

It doesn't mean audited. We've been clear about that, and we'll keep being clear.

It means this: the core promise works reliably, across real devices, in real conditions. You can install Khord, create an identity from a seed phrase, add contacts, send messages, share images, create groups, search your history, and recover your identity if something goes wrong. Your messages are encrypted end-to-end. The servers can't read them. No phone number, no email, no metadata.

It means twenty-one rounds of breaking it and fixing it. Each round started with a question and ended with an answer. Some answers were two-line fixes. Some were architectural decisions that reshaped the protocol. All of them came from the only test environment that matters: real people, real phones, real networks.

## The Version Number

Alpha.21 and beta.1 are the same code. The version number is a promise, not a feature. It says: we've asked twenty-one questions, and we're confident enough in the answers to invite more people to ask their own.

The questions won't stop. They'll just get harder — more devices, more edge cases, more network conditions, more threat models. That's what beta means. Not "done." Not "safe." Not "audited."

Ready to be questioned.
