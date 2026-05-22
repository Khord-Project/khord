# Khord Versioning

Khord follows [Semantic Versioning](https://semver.org/) with pre-release tags for development builds.

Format: `MAJOR.MINOR.PATCH-prerelease`

---

## What each number means

**MAJOR (0 → 1):** Protocol is stable, security audit completed, safe to recommend publicly. Breaking changes to the wire format or server API require a major bump after 1.0.

**MINOR (0.1 → 0.2):** New functionality. Feature milestones that meaningfully change what the app can do.

**PATCH (0.1.0 → 0.1.1):** Bug fixes, performance improvements, polish. No new features.

**Pre-release (alpha, beta):** Development builds. Alpha means active feature development with known rough edges. Beta means feature-complete for that minor version, stabilizing.

---

## Current phase

We're at `0.1.0-alpha.X` — the proof-of-concept phase. Features are landing fast, bugs are found and fixed in the same day, the protocol may change without notice. Alpha testers should expect rough edges.

---

## Planned milestones

### 0.1.0-beta
The alpha tag drops when:
- No critical bugs reported for one quiet week
- Core messaging flow is reliable across tested devices
- Seed phrase recovery works
- Contact acceptance gate is in place

Beta testers can use the app daily. Known limitations are documented. Protocol may still change.

### 0.1.0 (first stable release)
The pre-release tag drops entirely when:
- Beta period passes without critical issues
- F-Droid listing is live
- Self-hosting documentation is polished
- Data processing commitment is published

This is the "tell your friends" release. Still not audited, still 0.x, but reliable for daily use.

### 0.2.0
A significant feature milestone. Candidates:
- Web interface
- Media attachments
- Multi-device support

The specific trigger depends on what ships next. Each minor bump represents a meaningful expansion of what Khord can do.

### 0.3.0, 0.4.0, ...
Subsequent feature milestones. No fixed mapping — version numbers follow the work, not a predetermined roadmap.

### 1.0.0
The "we're serious" release:
- Professional security audit completed
- Protocol specification frozen (changes require a new major version)
- Wire format and server API are stable — old clients will work with new servers
- Reproducible builds verified
- At least two independent client implementations (Android + web, or Android + iOS)

This is the version that gets submitted to privacy community directories and recommended for sensitive use cases.

---

## Pre-release numbering

During alpha/beta, the pre-release number increments with every release:

```
0.1.0-alpha.1   first tester build
0.1.0-alpha.2   bug fix
0.1.0-alpha.12  current
0.1.0-alpha.13  next
...
0.1.0-beta.1    stabilization begins
0.1.0-beta.2    beta bug fix
0.1.0           stable
```

There's no limit on alpha/beta numbers. Ship as many as needed.

---

## When to bump what

| I'm shipping... | Bump |
|---|---|
| A bug fix for testers | alpha.X → alpha.X+1 |
| The last alpha, starting stabilization | alpha → beta.1 |
| A bug fix during beta | beta.X → beta.X+1 |
| Beta is stable, ready for wider use | drop pre-release tag (0.1.0) |
| A new major feature set | minor (0.1.0 → 0.2.0) |
| A bug fix on a stable release | patch (0.2.0 → 0.2.1) |
| Protocol frozen, audited, production-grade | major (0.x → 1.0.0) |

---

## What we don't do

- We don't tie version numbers to calendar dates
- We don't skip numbers for marketing reasons
- We don't promise features for specific versions (the roadmap is separate)
- We don't bump major before the security audit

---

*This document lives at `docs/VERSIONING.md` and is referenced from the README.*
