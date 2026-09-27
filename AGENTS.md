# AGENTS.md

Guidance for AI agents and humans working on magic-wormhole-kotlin.

## Project

A standalone, MIT-licensed Kotlin Multiplatform library for the Magic Wormhole protocol. It must
stay usable by anyone: no app code, no Compose, no dependency on the Wormhole Rift app.
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

## Documentation

- `README.md`: overview and the shortest examples.
- `docs/usage.md`: task-based examples. Update it when the public API changes.
- `plans/`: plans for larger work, for example `plans/dokka-api-docs.md`.

Public API changes need KDoc; check with `./gradlew :magic-wormhole:dokkaGeneratePublicationHtml`
(warns about undocumented public declarations). The generated site is published to
[lux-uno.github.io/magic-wormhole-kotlin](https://lux-uno.github.io/magic-wormhole-kotlin/) on
each release tag; see "Releases" below. Keep `magic-wormhole/Module.md` up to date too: update its
package list when a package is added, removed, or renamed.

## Code style

- ktlint (`ktlint_official`) via the `org.jlleitschuh.gradle.ktlint` plugin; rules in `.editorconfig`.
- Run `./gradlew ktlintFormat` before committing; `./gradlew ktlintCheck` must pass.
- Generated test-vector files may suppress `ktlint:standard:max-line-length`.

## Ported code

Mention the origin at the top of a ported file (for example "Ported from TweetNaCl (public
domain)"). Add new sources to `THIRD_PARTY_NOTICES.md`.

## Releases

Every release gets a new version number and a git tag. Never publish without both.

1. **Pick the version.** Follow [Semantic Versioning](https://semver.org/). Before 1.0, a breaking
   change to the public API raises the minor version (0.1.0 → 0.2.0); anything else raises the
   patch version (0.1.0 → 0.1.1).
2. **Set it.** In `gradle.properties`, set `VERSION_NAME` to the release version, without
   `-SNAPSHOT`. Commit: `Release 0.2.0`.
3. **Tag that commit, before publishing:** `git tag -a v0.2.0 -m "Release 0.2.0"`. The tag is `v`
   followed by `VERSION_NAME`, exactly.
4. **Publish** the artifact from the tagged commit.
5. **Push the commit and the tag:** `git push origin main v0.2.0`. Once the docs workflow from
   `plans/dokka-api-docs.md` exists, the pushed tag publishes the API docs; it fails when the tag
   does not match `VERSION_NAME`.
6. **Start the next version.** Set `VERSION_NAME` to the next snapshot, for example
   `0.2.1-SNAPSHOT`. Commit: `Start 0.2.1 development`.

Never move or delete a pushed tag. If a release is wrong, release a new version.

## Commands

| Task | Command |
|------|---------|
| JVM tests | `./gradlew jvmTest` |
| Interop tests (needs `wormhole` CLI + internet) | `WORMHOLE_INTEROP=1 ./gradlew jvmTest` |
| iOS simulator tests (macOS only) | `./gradlew iosSimulatorArm64Test` |
| Lint (check / auto-fix) | `./gradlew ktlintCheck` / `./gradlew ktlintFormat` |
| Publish to local Maven | `./gradlew publishToMavenLocal` |
