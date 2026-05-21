# The Bug That Wasn't There

*How eleven alpha releases, an AI-built diagnostic pipeline, and three wrong theories led to a two-line fix — and what that says about building software with AI.*

---

We shipped eleven alpha releases in a single day. Group messaging. Seed phrase recovery. Real-time push notifications. A CI/CD pipeline that built signed APKs automatically. An in-app bug reporting system that filed GitHub issues with one tap. And, threading through all of it, a bug that haunted every release — reported by testers, diagnosed by Claude, fixed five times over, and never actually what we thought it was.

This is the story of how a human architect and an AI built a product together — and how the architect saw something the AI couldn't.

## The First Reports

Khord is an encrypted messenger built on a split-trust architecture. Two servers, each holding half the puzzle, neither able to reconstruct the whole. The crypto is real — Signal Protocol, X3DH key exchange, Double Ratchet. The servers are live. The Android app works. We have testers.

The first tester was on a Xiaomi Redmi Note 10. Within hours of installing, the bug reports started arriving — not emails or screenshots, but structured diagnostics auto-submitted from the app to our GitHub issue tracker with a single tap. The reporting system Claude Code had built that same day worked exactly as designed:

```
App version: 0.1.0-alpha.3
Android version: 13 (API 33)
Device: Xiaomi M2101K6G
Error: state_loss: bootstrap returned no identity
       despite leftover state (db=true, prefs=true)
```

Three identical reports. Same device, same MIUI build, same pattern. The database file existed. The Keystore preferences existed. But the app couldn't find the user's identity. Everything was there and nothing worked.

## The Obvious Theory

Claude diagnosed it immediately: Xiaomi's MIUI was invalidating Android Keystore keys.

This is a real thing. The Android Keystore stores cryptographic keys inside a hardware security chip — the Trusted Execution Environment. It's excellent security. Keys never leave the chip. But some manufacturers' firmware has bugs. Xiaomi is a known offender. MIUI has been documented invalidating Keystore keys during system updates, lock screen changes, or aggressive battery optimization sweeps.

The theory was clean and it fit the evidence: MIUI invalidates the Keystore key. The app tries to decrypt the database passphrase. Decryption fails. The defense-in-depth code generates a new passphrase. The new passphrase can't open the old database. The identity is lost.

Based on this data, we built a fix. We built several.

## Building the Wrong Solution, Thoroughly

What followed was a series of sophisticated engineering aimed at a problem that didn't exist. While we shipped real features — group messaging, WebSocket push notifications, theme customization, runtime server selection — the bug reports kept arriving, and we kept responding.

First: orphan database cleanup. When the Keystore regenerated and an existing database couldn't be opened, Claude Code would detect it, log it, delete the orphaned file, and let the user start fresh. Clean, defensive, well-tested.

Then: a diagnostic ring buffer. Android restricts apps from reading their own logcat on some OEM ROMs. Our original diagnostic capture returned "not available" on every Xiaomi report. So Claude Code built an in-memory ring buffer — last 100 log entries, captured at every decision point in the startup path, dumped into the bug report payload. No logcat dependency, works on every device.

Then: MIUI-specific Keystore mitigation. We researched SharedPreferences passphrase backup, discussed threat models and hardware security trade-offs. We drafted architecture decision records. We debated whether the database passphrase should be derived from the seed phrase itself.

Then: a Xiaomi onboarding warning. A blocking dialog explaining battery saver settings, autostart permissions, and the lock-in-recents trick. We checked `Build.MANUFACTURER` for "xiaomi" and "redmi" and "poco."

Each fix was well-reasoned, cleanly implemented with comprehensive tests and thoughtful documentation. The diagnostic pipeline was genuinely good engineering. Meanwhile, the app was growing — seed phrase recovery, delete conversation, version display, a proper launcher icon, signed releases through an automated pipeline.

The bug fixes were wrong. Everything else was real.

## The Reports Keep Coming

We shipped five alpha releases targeting the Xiaomi issue. The reports continued. Same pattern, now from two different Xiaomi devices. Never from the Pixel emulators. Never from my OnePlus. Only Xiaomi. That pattern strengthened the theory — this had to be MIUI.

Claude proposed more sophisticated theories. Maybe MIUI was clearing the entire app data directory, not just the Keystore. Maybe we needed to store the passphrase backup outside the app sandbox entirely.

We asked the testers directly: are you using any cleaner apps? No. Have you manually cleared the app data? No. Are you uninstalling and reinstalling? No.

We were deep in the weeds of hardware security — and we were wrong.

## A Different Phone

Then I picked up a Motorola.

Fresh install. Register. Send a message. Open the contact list. Press back. "Registration state lost."

Three times in a row.

Not Xiaomi. Not MIUI. A Motorola, running stock Android, with none of the aggressive optimization that was supposed to be causing the problem. The same bug, instantly reproducible, on a completely different device.

## The Question the Machine Didn't Ask

I remembered something a tester had mentioned earlier that day, almost in passing. He'd reported the state-loss screen. But then he added something I hadn't fully processed: "I still get notifications for new messages. I can even open them and reply."

Claude had seen this data. The diagnostic pipeline had captured it. Neither Claude nor I had stopped to ask the obvious question:

*If the identity is gone, how are messages still decrypting?*

End-to-end encryption means the recipient's private key decrypts each message. The private key lives in the identity. If the identity is gone, decryption is impossible. Messages shouldn't arrive. The WebSocket shouldn't authenticate. The push service shouldn't function. And yet — notifications appeared, messages decrypted, replies encrypted and sent.

The identity wasn't gone. The app was lying.

## Line 170

I pointed Claude at the codebase with a new hypothesis: the data is intact, the state-loss screen is wrong, find out why.

Five minutes later, Claude Code had the answer.

```kotlin
suspend fun bootstrap(...): Boolean {
    bootstrap?.let { return it.messaging != null }  // ← line 170
    ...
}
```

When the app first launches with no identity, this function creates a `bootstrap` object with `messaging = null` and saves it. After the user registers, the live `messaging` instance is stored in a separate field — `AppContainer.messaging`. But the `bootstrap` object is never updated. Its `messaging` reference stays null forever.

Any time `bootstrap()` is called again — which happens when Compose Navigation revives the splash screen after a back press on some devices — it checks the frozen reference, sees null, and reports "no identity found."

Meanwhile, the push service reads from `AppContainer.messaging` — the live reference — and keeps working. Messages arrive. Decryption succeeds. The app functions. But the UI says the identity is lost.

The fix:

```kotlin
bootstrap?.let { return messaging != null || it.messaging != null }
```

One boolean check. Plus a `BackHandler` on the contact list screen to prevent the back press from reaching the splash screen in the first place. Fifty lines total across two files.

## The Diagnostic System That Caught Nothing and Built Everything

Here's the uncomfortable part: the diagnostic pipeline we built to find this bug didn't find it. The ring buffer, the structured reports, the GitHub Issues integration — none of it contained the signal that mattered. The signal was a tester saying "but I can still reply to messages," and a human recognizing that was impossible.

But here's the other part: every piece of that infrastructure is permanent. The ring buffer captures startup decisions on every device. The bug reporter lets any user submit structured data with one tap. The GitHub integration creates trackable issues automatically. The Xiaomi battery warning helps real users with real optimization problems.

We built the right infrastructure for the wrong reason. And alongside the bug hunt, we built real features — group messaging with zero server awareness, seed phrase recovery with session reset, a CI/CD pipeline that ships signed APKs on every tag. The phantom bug consumed our diagnostic energy. It didn't consume the day.

## Eleven Alphas

Eleven alpha releases. Each one a question asked to the real world, built by Claude Code, tested by CI, signed and published automatically, delivered to testers who gave us answers within hours.

Alpha.3 shipped the bug reporting system. Alpha.5 shipped the diagnostic ring buffer. Alpha.7 shipped delete conversation and version display. Alpha.8 shipped seed phrase recovery and the session reset protocol — a genuine protocol extension that lets contacts reconnect without re-scanning QR codes. Alpha.9 shipped the two-line fix that resolved every state-loss report.

Most of those features had nothing to do with the phantom bug. They were built in the same session, by the same collaboration, while the bug hunt ran in parallel.

The speed mattered. Not because shipping fast is inherently good, but because each release closed a feedback loop with real devices in real hands. The Motorola reproduction couldn't have happened if we hadn't shipped fast enough for me to still be testing that day.

## What This Means for Building With AI

There's a narrative that AI will replace developers. There's a counter-narrative that AI is just autocomplete with better marketing. Both miss what actually happened here.

Claude built things I couldn't build as fast. I saw things Claude couldn't see at all. Claude was wrong about the diagnosis — and so was I, for most of the day. But when a tester said something that contradicted the theory, I noticed. Claude had the same information and didn't.

The collaboration isn't division of labor. It's not even "human designs, AI implements." It's two different ways of seeing the same problem. Claude sees the code — all of it, instantly, in full detail. The architect sees the user — the contradiction in their report, the impossibility of their experience, the gap between what the app claims and what actually happens.

The bug was found at the intersection. Not by Claude alone. Not by me alone. By me saying "that can't be right" and Claude finding exactly why it wasn't, in five minutes flat.

Line 170. One boolean check. Eleven alphas to get there. And the real fix was a question only a human thought to ask.
