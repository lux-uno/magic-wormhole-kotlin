# AGENTS.md

Guidance for AI agents and humans working on wormhole-kotlin.

## Project

A standalone, MIT-licensed Kotlin Multiplatform library for the Magic Wormhole protocol. It must
stay usable by anyone: no app code, no Compose, no dependency on the Super Massive Wormhole app.
The public API is small and documented; everything else is `internal` (`explicitApi()` is on).

## Development workflow: tests first

1. Write one small failing test, then just enough code to make it pass, then refactor.
2. Tests are the primary consumer of the code. If code is hard to test, change the design.
3. Test all critical components: crypto, protocol state machines, message formats, transit.
   Coverage does not need to be 100%.
4. Crypto code must be checked against published or independently generated test vectors
   (the `spake2` and `nacl` Python packages, RFCs). Never invent crypto.
5. Commit after each finished task. One task, one commit.

## Structure

- `crypto/`: SHA-256 / HMAC / HKDF wrappers, XSalsa20-Poly1305 secretbox, Ed25519 group, SPAKE2.
- `code/`: PGP word list, code generation.
- `rendezvous/`: mailbox server messages and client.
- `transit/`: direct and relay TCP connections, encrypted records.
- `Wormhole.kt`: public API.

Network access goes through small interfaces (`RendezvousTransport`, transit connection factory),
so `commonTest` can run full transfers in memory.

## Code style

- ktlint (`ktlint_official`) via the `org.jlleitschuh.gradle.ktlint` plugin; rules in `.editorconfig`.
- Run `./gradlew ktlintFormat` before committing; `./gradlew ktlintCheck` must pass.
- Generated test-vector files may suppress `ktlint:standard:max-line-length`.

## Ported code

Mention the origin at the top of a ported file (for example "Ported from TweetNaCl (public
domain)"). Add new sources to `THIRD_PARTY_NOTICES.md`.

## Commands

| Task | Command |
|------|---------|
| JVM tests | `./gradlew jvmTest` |
| Interop tests (needs `wormhole` CLI + internet) | `WORMHOLE_INTEROP=1 ./gradlew jvmTest` |
| iOS simulator tests (macOS only) | `./gradlew iosSimulatorArm64Test` |
| Lint (check / auto-fix) | `./gradlew ktlintCheck` / `./gradlew ktlintFormat` |
| Publish to local Maven | `./gradlew publishToMavenLocal` |
