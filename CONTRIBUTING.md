# Contributing to Khord

Khord is an open-source project. Contributions are welcome.

## How to contribute

1. Check the [roadmap](https://github.com/orgs/Khord-Project/projects/1) for open items
2. Open an issue or comment on an existing one to signal your interest
3. Fork the repo and create a feature branch from `main`
4. Make your changes
5. Open a pull request — CI must pass before merge

## Development setup

### Prerequisites
- JDK 17
- Android SDK (compileSdk 36; minSdk 26)
- Python 3.13 (for the servers)
- Docker + Docker Compose (for the local server stack)

### Build the Android app
```bash
cd client
./gradlew :android:assembleDevDebug
```

### Run tests
```bash
cd client
./gradlew :shared:jvmTest               # shared (KMP) module tests
./gradlew :android:testDevDebugUnitTest # Android unit tests
```

The full CI gate is:
```bash
cd client
./gradlew :shared:jvmTest :android:testDevDebugUnitTest \
          :android:assembleDevDebug :android:assembleProdDebug
```
plus the server pytest suite.

### Run the local server stack
The development Docker Compose file lives at the repo root and brings up
both servers (Key + Relay) with their own PostgreSQL instances:
```bash
docker compose up -d
```
This exposes the Key Server on `localhost:8001` and the Relay Server on
`localhost:8002`.

The dev flavor (`assembleDevDebug`) points at `10.0.2.2:8001` / `:8002`
— the Android emulator's alias for the host machine's localhost. For
physical-device testing, use **"Use custom servers"** in the app and
enter your machine's LAN IP.

## Branch protection

- `main` is protected — all changes go through pull requests
- CI (tests + build) must pass before merge
- The project owner merges PRs

## Code style

- Kotlin: follow existing patterns in the codebase
- Python: follow existing patterns in the codebase
- No auto-formatters enforced yet — match the style of surrounding code

## Versioning

See [docs/VERSIONING.md](docs/VERSIONING.md) for the versioning strategy.
Don't bump versions in PRs — the project owner handles version bumps and
release tags.

## Architecture

Before making changes, read the relevant architecture decision records in
[docs/decisions/](docs/decisions/). If your change affects the
architecture, propose an ADR as part of your PR. The protocol itself is
documented in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Security

If you find a security vulnerability, please report it responsibly. Open a
GitHub issue tagged `security`, or contact the project owner directly. Do
not include exploit details in public issues.
