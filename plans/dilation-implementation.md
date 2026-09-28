# Plan: implement Wormhole Dilation

Status: in progress. Supersedes the phase list in
[close-python-feature-gaps.md §4](close-python-feature-gaps.md), which now holds the research
findings (message formats, wire framing, state machine) this plan implements. Read that section
first if you need the "why" behind any wire format decision here.

## Goal

Port Dilation from the Python reference implementation (`wormhole/_dilation/*.py`) into
`uno.lux.wormhole.dilation`, matching its wire format exactly (see
`close-python-feature-gaps.md §4` for the recorded formats), so a Kotlin wormhole can dilate with a
real Python peer.

## Why crypto comes first

Dilation's L2 handshake requires `Noise_NNpsk0_25519_ChaChaPoly_BLAKE2s`. None of X25519,
ChaCha20-Poly1305, BLAKE2s, or a Noise handshake state machine exist in this library yet
(`Ed25519.kt`/`SecretBox.kt` are unrelated primitives for SPAKE2/Transit). Per AGENTS.md, crypto
must be built from a reference source and checked against independently-published test vectors
before anything depends on it — so each primitive is its own task/commit, vector-tested, before the
Noise handshake, and the Noise handshake is its own vector-tested task before any protocol code uses
it.

## Tasks (one task = one commit)

- [x] 1. Record findings in `close-python-feature-gaps.md §4` (done alongside this plan).
- [x] 2. `crypto/Curve25519.kt` — X25519 (RFC 7748 Montgomery ladder), built on the existing `Field`
      GF(2^255-19) arithmetic in `Ed25519.kt`. Vectors in `Curve25519Vectors.kt` are independently
      generated with Python's `cryptography` library rather than hand-transcribed from the RFC (to
      avoid transcription errors); the 1-iteration and 1000-iteration `k=u=9` results match the
      published RFC 7748 §5.2 values exactly. Skipped the 1,000,000-iteration vector (slow, and the
      1000-iteration one already exercises the same code path).
- [x] 3. `crypto/ChaCha20Poly1305.kt` — ChaCha20 stream cipher + AEAD (RFC 8439), reusing the
      existing `Poly1305.mac` primitive from `SecretBox.kt` rather than reimplementing it. Vectors
      in `ChaCha20Poly1305Vectors.kt` are independently generated with Python's `cryptography`
      library (same rationale as the X25519 vectors: avoids hand-transcription errors from the RFC
      text), covering empty/short/AAD-only/unaligned/multi-block cases plus tamper and wrong-AAD
      rejection tests.
- [x] 4. `crypto/Blake2s.kt` — unkeyed BLAKE2s with the fixed 32-byte digest Noise needs (`blake2s`),
      plus `hmacBlake2s` (RFC 2104 HMAC over it, 64-byte block size) for Noise's `HMAC-HASH`. Scoped
      to unkeyed hashing only — Noise never uses BLAKE2s's native key parameter, so that path was
      left out rather than built and left untested. Vectors in `Blake2sVectors.kt` come from Python's
      `hashlib.blake2s`/`hmac`; the `"abc"` digest matches the well-known published BLAKE2s-256
      test value as a cross-check.
- [x] 5. `crypto/Noise.kt` — handshake state for exactly `Noise_NNpsk0_25519_ChaChaPoly_BLAKE2s`
      (`NoiseHandshake`, `NoiseCipherState`), built directly on `Curve25519`/`ChaCha20Poly1305`/
      `Blake2s` rather than a general token-driven Noise engine, since no other pattern is needed.
      Checked noise-c's and "cacophony"'s published vector sets first; neither covers `NNpsk0`
      specifically (only plain `NN` or the differently-modified `NoisePSK_*` patterns), so
      `NoiseHandshakeVectors.kt` cross-checks against a from-scratch Python reimplementation of the
      Noise spec (built on already-vector-tested Python primitives, not on `python-noise`, which
      isn't installable in this environment) — documented as such in the file header. Confirmed via
      the Python source (`_dilation/connector.py`) that Dilation sets no Noise prologue (only the
      separate plaintext `PROLOGUE_LEADER`/`PROLOGUE_FOLLOWER` line, which is not fed into Noise).
      `NoiseHandshake` takes an injectable ephemeral-key generator so tests can pin the vectors'
      fixed keys while real usage defaults to `randomBytes`.
- [x] 6. Dilation version negotiation: `WormholeSession.exchangeVersions()` now sends
      `can-dilate`/`dilation-abilities` and records the peer's `can-dilate`
      (`peerSupportsDilation()`); `sendDilation`/`receiveDilation` add a separate `dilate-N` phase
      sequence alongside the existing numbered one, sharing the same encrypt/decrypt machinery
      (confirmed against `_key.py`/`_send.py` that Python derives the `dilate-N` phase key exactly
      like any other phase, keyed by the phase string and the *mailbox* side — not the
      dilation-specific side). `dilation.DilationManager` sends/receives the `please` exchange and
      decides Leader/Follower by comparing the 8-byte-hex dilation `side` values (own side
      injectable for deterministic tests, defaults to random). No TCP yet. Tested against
      `FakeMailboxServer` in `DilationManagerTest.kt`.
- [ ] 7. `dilation/DilationHints.kt` + `dilation/DilationConnector.kt`: hint dial/accept on
      `transit/TcpTransitNetwork`'s `TransitNetwork`/`TransitSocket`/`TransitListener`, prologue and
      relay-handshake lines, Noise handshake per candidate, KCM exchange, first-viable-wins
      selection. Test against a new `FakeDilationNetwork` (in-memory, styled like
      `transit/FakeInternet.kt`).
- [ ] 8. `dilation/DilationRecord.kt`: record tag encode/decode + 4-byte length framer. Get one
      control-channel connection fully established end to end (Leader ↔ Follower, real bytes).
- [ ] 9. `dilation/DilationSubchannel.kt` + subchannel support in `DilationManager.kt`: OPEN/DATA/CLOSE,
      scid allocation by role, buffering for data arriving before a consumer attaches, ACK watermark,
      outbound retry queue.
- [ ] 10. Reconnection: full `DilationManager` state machine (table in
       `close-python-feature-gaps.md §4`), ping/pong liveness timer, `reconnect`/`reconnecting`
       handshake, resend of the unretired queue on the new connection. Test: drop the fake connection
       mid-session, assert unacked records resend in order.
- [ ] 11. Confirm whether a public API is warranted yet (lean internal-only per AGENTS.md's "small
       public API" until a real caller exists — re-check this against whatever `wormhole-rift` needs
       at the time).

## Conventions to follow (already established elsewhere in this repo)

- Manual `JsonObject` construction for wire messages, no `@Serializable` model classes — match
  `rendezvous/Messages.kt` / `transit/Hints.kt`.
- New types `internal` by default (dilation has no public API yet per task 11).
- Ported/adapted code gets an origin comment header and a `THIRD_PARTY_NOTICES.md` entry.
- `magic-wormhole/Module.md`'s package list gets a `uno.lux.wormhole.dilation` entry once that
  package exists.
- `./gradlew :magic-wormhole:jvmTest` and `./gradlew ktlintCheck` after every task.

## Check

Per task, see the "test first" note above. End-to-end check once tasks 7-10 land: an in-memory
two-wormhole test (styled like `transit/TransitTest.kt`) that dilates, opens a subchannel, sends
bytes, drops the connection, and confirms the transfer resumes without duplicating or losing bytes.
