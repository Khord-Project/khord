# F-Droid submission notes

Working notes for the F-Droid listing. Not consumed by any build —
reference material for whoever files the metadata PR against
fdroiddata.

## Metadata layout

Standard Fastlane structure under
`client/android/fastlane/metadata/android/en-US/`:

- `title.txt` — "Khord"
- `short_description.txt` — ≤ 80 chars
- `full_description.txt`
- `changelogs/<versionCode>.txt` — one per release; F-Droid shows
  the file matching the built versionCode (currently `17.txt` for
  v0.1.0-alpha.17). Add a new file each release.
- `images/icon.png` — copied from `assets/icon-512.png`

## Anti-features

**None to declare.**

- No tracking, no analytics, no ads.
- No proprietary dependencies — the crypto is libsodium
  (`ionspin/kotlin-multiplatform-libsodium`); storage is SQLCipher;
  QR is ZXing. All FOSS.
- **Not `NonFreeNet`.** The app talks to default community servers
  (keys.khord.org / relay.khord.org), but those servers are
  open source (AGPL-3.0, in this same repo under `servers/`) and
  self-hostable, and the user can point the app at their own
  instance from in-app settings. By F-Droid's definition,
  NonFreeNet applies to apps that *promote or depend on* a network
  service that is not FOSS — neither holds here.
- No Google Play Services / FCM dependency: push is a direct
  WebSocket to the relay (ADR 022). Nothing to flag as
  `NonFreeDep` or `Tracking`.

## Build notes

- Reproducible builds are NOT yet set up (tracked separately as
  issue #41). Until they are, the F-Droid build will be from
  source on F-Droid's infrastructure — fine, just not yet
  reproducible-verified against our published APKs.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }`
  is set in `client/android/build.gradle.kts` so the APK doesn't
  embed the Play-style signed dependency metadata block (F-Droid
  prefers builds without it).

## Build flavor

F-Droid should build the **prod** flavor (`assembleProdRelease` /
the prod variant), which points at the community servers by
default. The `dev` flavor targets `10.0.2.2` and is emulator-only.
