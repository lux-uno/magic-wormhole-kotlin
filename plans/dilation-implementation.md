# Plan: implement Wormhole Dilation

Status: core protocol complete (tasks 1-11 below all done) and `internal`-only, matching the
Python reference implementation's wire format. What's *not* built: a public API (task 11 explains
why), and anything beyond what the Python implementation itself does (no resumable-file-transfer
layer on top — see `close-python-feature-gaps.md` §5 for that, separate and still not started).

Supersedes the phase list in
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
- [x] 7-8 (merged). `dilation/DilationHint.kt`, `dilation/DilationConnection.kt` (4-byte-length
      framer + KCM), `dilation/DilationConnector.kt`: hint dial/accept reusing
      `transit/Transit.kt`'s `TransitNetwork`/`TransitSocket`/`TransitListener` interfaces directly
      (no new network abstraction needed), prologue exchange, and the relay handshake line —
      reusing `transit.Handshakes.relay()`/`RELAY_OK` as-is, since Dilation's relay token uses the
      exact same `"transit_relay_token"` HKDF purpose string as Transit's (confirmed against
      `connector.py`/`_hints.py`). Per-candidate: Noise handshake (Leader = initiator, Follower =
      responder), then the KCM exchange exactly as `connection.py`'s `dataReceived` drives it
      (Follower sends a KCM unconditionally right after its handshake half completes; every
      candidate — either role — waits to receive an inbound KCM before it's considered viable;
      first-viable-wins; only the Leader additionally sends its own KCM, and only on the winning
      connection, which is what tells the Follower which one won). Reused `transit/FakeInternet.kt`
      directly for tests (no new fake network needed, since `TransitNetwork` is exactly the
      interface it already implements) — covers direct-hint success, relay-only success, a raced
      direct+relay double-success (exactly one winner), a prologue-mismatching impostor, and a
      wrong-PSK Noise failure. Folded 7 and 8 into one task since KCM/selection and the framing it
      rides on aren't meaningfully separable — you can't test "first viable wins" without the KCM
      exchange that defines viability.
- [x] 9. `dilation/DilationRecord.kt` (all 7 record types + big-endian codec, generalizing the
      KCM-only logic from task 7-8) and multiplexing support added directly to
      `DilationManager.kt`: `connect()` now also exchanges `connection-hints` (`dilate-1`), races the
      connection via `DilationConnector`, and launches a background reader that ACKs every inbound
      OPEN/DATA/CLOSE (even stale ones, matching `Manager.got_record`), drops already-delivered ones
      via a monotonic watermark, and retires outbound entries once ACKed. `openSubchannel`/
      `sendData`/`closeSubchannel` assign the next scid (odd for Leader starting at 1, even for
      Follower starting at 2) or seqnum and queue+send. Deliberately **not** built yet: a
      consumer-facing `DilationSubchannel` object with pre-attach buffering — there is no real
      consumer/public API to attach to until task 11 decides one, so `events: ReceiveChannel<...>`
      is the manager-level surface for now; build the richer subchannel object once something needs
      it. Tests reuse `transit/FakeInternet.kt` again; a same-instant reconnection swap (moving
      unretired queue entries to a new connection) is task 10's job, not this one.
- [x] 10. Reconnection: `DilationManager` now runs a persistent `lifecycleLoop` (launched once from
       `connect()`) that establishes a connection, runs it until lost, handles the loss per
       `close-python-feature-gaps.md §4`'s state table (Leader always FLUSHING→send `reconnect`→wait
       `reconnecting`; Follower LONELY→wait `reconnect`→send `reconnecting` if its own connection died
       first, or ABANDONING→close its still-good connection→send `reconnecting` if the Leader's
       `reconnect` arrives while still connected), then reconnects — forever. A single-consumer
       `dilateReaderLoop` feeds every `dilate-N` message into a channel so the "waiting for a specific
       control message" logic never races with `WormholeSession`'s per-phase counter. The Leader-only
       ping/pong liveness timer (`pingInterval`, default 30s) sends a `PING` each idle interval and
       force-closes the connection if a second interval passes with no traffic. `resendUnretiredQueue`
       replays not-yet-acked OPEN/DATA/CLOSE on the new connection, in order, before new writes.

       Two real bugs found and fixed while getting this to pass against `FakeInternet`, both worth
       remembering:
       1. `kotlinx.coroutines.selects.select` combining a `Deferred.onAwait` with a
          `Channel.onReceive` clause silently never fired the channel clause in this scenario, even
          with a value already sitting in the channel and no other consumer — root cause not fully
          identified. Replaced with an explicit `CompletableDeferred` + two `launch`ed racer
          coroutines (mirroring `DilationConnector.ConnectionRace`'s style), which works reliably.
          Avoid `select` here again without a minimal reproduction confirming it's fixed upstream.
       2. `readLoop`'s try/catch originally wrapped only `connection.receiveRecord()`, not the record
          handling that follows. A record can be read successfully and then fail to *ACK* (the peer's
          read half died but the write half hadn't yet, or vice versa) — that failure was propagating
          uncaught out of the `launch`, silently cancelling the whole `coroutineScope` (including the
          Follower's reconnect-signal watcher) with no readable error at the call site. Fixed by
          guarding the entire per-record iteration, not just the read.

       Tests in `DilationManagerReconnectionTest.kt`: a plain drop-and-reconnect from either role,
       subchannel ids surviving a reconnect, and an unacked DATA record correctly resending after
       reconnect.
- [x] 11. Checked `wormhole-rift` (the sibling app repo) for any reference to Dilation — there is
       none. No concrete caller exists yet, so per AGENTS.md's "public API is small" principle,
       Dilation stays fully `internal` for now (no public `Wormhole` method exposes it). Revisit this
       once a real feature (e.g. a persistent/dilated transfer mode, or a non-file-transfer use case)
       needs it — at that point design the public surface around that caller's actual shape rather
       than speculatively now.

## Remaining work

The 11 tasks above cover the protocol core. What they don't cover, roughly in priority order:

1. **No public API.** Everything in `uno.lux.wormhole.dilation` is `internal` (task 11's deliberate
   deferral). Nothing outside this module can use Dilation yet — this is the main blocker to it
   being useful, not just complete.
2. **Large-record chunking is missing — a real protocol-fidelity bug, not a nice-to-have.** Python
   splits any Noise message payload over `NOISE_MAX_PAYLOAD` (65,519 bytes) into multiple
   concatenated ciphertext chunks within one frame (`_noise.py`'s `NOISE_MAX_PAYLOAD`/
   `NOISE_MAX_CIPHERTEXT`, applied in `connection.py`'s `send_record`/`decrypt_message`).
   `DilationConnection.sendRecord`/`receiveRecord` currently encrypt/decrypt the whole record in one
   Noise call with no size limit — fine for control records, but a DATA payload over ~64KB would
   produce a frame a real Python peer's `decrypt_message` wouldn't parse correctly. Needs fixing
   before this could interop with anything beyond another instance of this library.
3. **No interop test against a real Python `wormhole` peer.** Every test here runs against
   `transit/FakeInternet.kt` (in-memory), and the crypto layer is cross-checked against independent
   Python reimplementations, not against `python-noise`/a live peer. That's solid for
   correctness-in-isolation but the only way to be confident the wire format is truly byte-compatible
   is to actually exchange bytes with the genuine `magic-wormhole` CLI/library once a Python
   Dilation-using tool is reachable (see `close-python-feature-gaps.md` §4's original phase 6).
4. **No consumer-facing subchannel object.** The surface today is
   `events: ReceiveChannel<DilationInboundEvent>` plus `openSubchannel`/`sendData`/`closeSubchannel`
   by raw scid — there's no stream-like "get a `Source`/`Sink` for subchannel N" abstraction (task
   9's deliberate deferral, for the same reason as #1: no caller to design it around yet).
5. **Tor support** — explicitly out of scope, tracked separately in `plans/tor.md`.
6. **Status/observer callbacks** (Python's `DilationStatus`/`WormholeStatus`) — a UI-facing
   nice-to-have surfacing connecting/reconnecting/connected state and timestamps; not implemented.
7. **Concurrent writes during a reconnect gap aren't fully guarded.** An app calling
   `sendData`/`openSubchannel` while `establishConnection()` is mid-swap of the `connection` field
   could hit a stale/closed reference. Not exercised by any test since nothing calls it concurrently
   yet; worth a real look once #1/#4 give this a real caller with real concurrency patterns.

Of these, #2 is worth fixing regardless of what else happens — it's a correctness bug, not a scope
decision. Everything else depends on deciding what the public API (#1) should look like, which in
turn depends on what a real caller in `wormhole-rift` (or elsewhere) actually needs.

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
