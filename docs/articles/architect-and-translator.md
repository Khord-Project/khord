# The Architect and the Translator: What We Talk About When We Talk About Vibecoding

*This article was co-authored by human and AI.*

---

During the building of our latest project — [Khord](https://github.com/khord/khord), a privacy-first encrypted messenger — we found ourselves in an unexpected conversation. Not about cryptography or server architecture, but about the process itself. Every commit carries a co-author tag: `Co-Authored-By: Claude`. That's the truth — an AI was involved in writing every line of code. But does that make it vibecoding?

It's a question worth taking seriously, because the answer matters beyond this one project. As AI becomes a standard part of how software gets built, the line between "AI-assisted engineering" and "AI-generated code with no oversight" is one the industry needs to draw clearly. We think the distinction is simpler than most people assume — and it has nothing to do with whether AI touched the code.

## What vibecoding actually means

To explain why we feel this is not vibecoding, we first need to explain what is.

"Build me a chat app." The AI generates something. It compiles. It sort of works. Nobody asked what the threat model is, or what happens when a user loses their phone. Nobody decided who can see what, or whether the quick solution today becomes tomorrow's security problem. The code exists, it runs, and nobody can tell you why it was built that way — because it wasn't. It just happened.

The problem isn't that AI wrote the code. The problem is that nobody designed the system. The same failure happens when human developers skip architecture and jump straight to implementation — it just takes longer to arrive at the same poorly-founded result. Vibecoding has a specific failure mode, and it's not "AI was involved." It's the absence of design.

## What the alternative looks like

If vibecoding is building without design, then the counter is straightforward: design first, build second. That sounds obvious. In practice, it's a discipline most projects skip — with or without AI.

Before any implementation began on Khord, architect and AI worked through twenty-six explicit architectural decisions. Each was documented in a formal decision record with context, options considered, the choice made, and its consequences.

An example of real collaboration was the threat model discussion. The AI proposed that protecting against a full compromise of any single operator was out of scope. The architect disagreed — the split-trust architecture was designed precisely for this. If the two servers are operated independently, no single point of compromise reveals the full picture, even if one side is fully breached. Through discussion, we separated what the architecture already solves (neither operator alone holds enough data to be dangerous) from what it doesn't yet address (an observer watching the network traffic itself). Neither the original "out of scope" nor a naive "we'll solve everything" survived — the answer came from working through the nuance together.

**PWA or native?** The initial agreement was to build a Progressive Web App as a quick proof of concept. Then the AI flagged a tension: in a browser, private keys are stored in IndexedDB or Web Crypto API — both vulnerable to cross-site scripting in ways that native secure storage isn't. For a project whose core promise is privacy, that felt like the wrong compromise. The architect's response wasn't to accept or reject — it was to reframe: "What if we go Android app through F-Droid? Can we set it up so iOS can be built later?" That led to Kotlin Multiplatform — a choice neither participant had proposed at the start, born from the tension between the AI's technical concern and the architect's practical instinct.

**The backend stack.** The AI recommended Kotlin for the server side too, arguing for a single-language stack that could share code between client and server. The architect pushed back — FastAPI had a larger ecosystem, a proven track record across production use cases, and broad compatibility with any API consumer, not just Kotlin clients. The shared-language benefit sounded good in theory, but the servers are deliberately simple blob routers. There's almost nothing to share between client and server because the servers intentionally don't understand what they're handling. The argument evaporated under scrutiny.

All of these foundational decisions were made before a single line of code was written. They could have been implemented in any language, on any framework. Well-designed systems are built independently of the code that expresses them.

## The investigate-first discipline

The architect established the workflow before the project began. It had a specific rule: **no code goes in without explicit design lock-in.** Investigate first, propose the approach, then implement. This applied to the AI just as it would apply to a human developer on the team.

In practice, every implementation phase started with an investigation. The implementing AI had to answer specific questions about its approach before writing any code. For the cryptographic module — the most security-critical piece — there were seven mandatory questions that had to be answered and reviewed before a single function was written.

When an investigation proposed a more complex approach to message encryption, the review process surfaced a simpler alternative in the same library that achieved the same result with fewer moving parts. It was adopted. This happened repeatedly — the investigation discipline created space to find better solutions before committing to code.

This discipline added time. But it caught problems when they were cheapest to fix — before they existed.

## What the architect caught that the AI didn't

Real-world testing was the architect's domain. Installing the app on a real phone immediately surfaced issues that neither AI could have found — a cryptographic library that wasn't loaded in the right order crashed the app on first launch, a data wipe button that froze because of database lock contention under real usage patterns, a seed phrase confirmation that always asked for the same three words (predictable enough to undermine its purpose). These aren't edge cases. They're the gap between software that works in a test environment and software that works in someone's hand.

But the most telling moment wasn't a bug. During a discussion about deploying the community servers, the AI recommended running both servers on a single machine for simplicity. The architect's immediate response: don't we break our own primary rule — that no single operator should hold the full picture? The AI had optimized for convenience and lost sight of the foundational design principle. The architect hadn't. That kind of oversight — holding the whole system in your head and noticing when a practical decision contradicts it — is something no test suite catches.

## What the AI contributed that the architect couldn't

The architect is direct about this: "I would never be able to build without it."

Not because the concepts are beyond them — the architect designed the split-trust architecture, the threat model, the privacy properties, the identity model, and the deployment philosophy. But translating those decisions into working Kotlin, Python, SQL, and Docker configurations requires a fluency in specific languages and frameworks that takes years of daily practice to develop. That's the translation barrier.

The AI bridges it. Given a clear design — what a component should do, what it should never do, how it should fail — the AI can produce the implementation, the tests, and the documentation. But "given a clear design" is doing all the heavy lifting in that sentence. Without the twenty-six decisions that preceded it, the AI would have produced something resembling a chat app. It would not have produced Khord.

## The translation barrier is the real story

Here's what the vibecoding discourse misses: a significant portion of what we call "software engineering skill" has always been translation work. Taking a concept — "messages should be encrypted so only the recipient can read them" — and expressing it as the correct sequence of function calls in a specific programming language.

That translation work is real and precision matters. A wrong parameter in a cryptographic function produces output that looks correct but has no security properties. Experience builds the pattern recognition to avoid these mistakes.

But translation is also, increasingly, automatable — and arguably it plays to AI's strengths more than ours. Pattern recognition across languages, frameworks, and libraries is exactly what AI is trained on. It has seen every common implementation pattern and every well-documented API. What it hasn't seen is your system, your threat model, and the specific set of trade-offs that make your design yours. That layer — systems thinking, security reasoning, holding a complex design in your head and noticing when a practical decision contradicts it — remains a human strength. The most productive approach isn't human or AI. It's both, playing to what each does best.

Before this year, having design skills without syntax fluency meant you could design systems but not ship them. You needed a team of developers to do the translation. Now you need AI. The skills that matter haven't changed. The barrier to expressing them has.

## The uncomfortable implication

A lot of what the industry valued as "senior engineering" was accumulated syntax fluency and framework familiarity. Knowing that Android's Compose framework wraps Activities in ContextWrappers. Knowing that SQLite's write lock blocks concurrent transactions. Knowing that libsodium's HMAC function is hardcoded to 32-byte keys.

This knowledge is real and it matters — the Khord project hit all three of those issues. But it's the kind of knowledge that AI acquires from training data, and the kind of knowledge that humans acquire from years of painful debugging. It's not the kind of knowledge that determines whether a system is well-designed.

The genuinely hard parts of software engineering — threat modeling, architectural integrity, knowing what not to build, maintaining design coherence across a complex system — were always rare. AI hasn't devalued engineering. It's revealed which parts were always translation work and which parts were always design work. The vibecoding criticism is correct when directed at undesigned AI output. It's misplaced when directed at AI-assisted implementation of a carefully designed system.

## Process, not provenance

Khord's proof of concept has 21 architectural decision records, a formal protocol specification, 123 tests, real-device validation, and a published threat model. It also has AI co-author tags on every commit.

The co-author tags stay. Not because we're proud of the tooling (though we think the workflow is worth studying) but because transparency about how software is produced is more important than managing perception. Especially for a project whose core value is privacy and trust.

The question worth asking about any piece of software isn't "who wrote it?" It's "was it designed?" The structured, iterative flow — decide, investigate, implement, test, refine — produces quality regardless of who or what handles each step. That process is the difference, not the provenance.

The for loops don't care who wrote them. The architecture does.

---

*The project discussed in this article is [Khord](https://github.com/khord/khord), a privacy-first encrypted messaging PoC. For a technical introduction to what Khord is and how it works, see [Khord: Privacy by Architecture](./privacy-by-architecture.md).*

*This article was co-authored by the architect and the strategic AI instance that participated in the project. The tactical AI (Claude Code) that implemented the code was a separate instance and is not a co-author of this text, though its work is discussed.*
