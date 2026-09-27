# Plan: close feature gaps with Python `magic-wormhole`

Status: not started.

## Goal

Add four features the Python implementation has and this library does not (Tor support is a
separate, larger effort and not covered here):

1. **Dilation** — the multiplexed, reconnectable transit protocol.
2. **Verifier** — a human-checkable confirmation string for the derived key.
3. **Zero mode** — codeless ("wormhole send -0") transfers with a fixed nameplate/password.
4. **Multi-file offers** — sending several files in one session without zipping them.

Each section below is independent and can be its own set of commits (see "Commit after each
finished task" in [AGENTS.md](../AGENTS.md)). Do 2 and 3 first: they are small and self-contained.
4 is medium-sized and touches the public API. 1 is a large, separate effort; do it last, and treat
its steps as a starting breakdown rather than a final one.

---

## 1. Verifier (about half a day)

### What Python does

`wormhole._boss` derives a `verifier` from the SPAKE2 session key with HKDF and a fixed purpose
string (`"wormhole:verifier"` in the Rust/Python implementations' shared constants), and the CLI
prints it as hex so both sides can read it aloud and compare before trusting the connection. It is
available as soon as the key is derived — before any offer is sent — so an app can gate the
transfer on the user confirming it matches.

### Where this fits

[WormholeSession.deriveKey](../magic-wormhole/src/commonMain/kotlin/uno/lux/wormhole/WormholeSession.kt#L74)
already does exactly this kind of derivation for the transit key
(see [FileTransfer.TRANSIT_KEY_PURPOSE](../magic-wormhole/src/commonMain/kotlin/uno/lux/wormhole/FileTransfer.kt#L23)).
Adding a verifier is one more `deriveKey` call with the matching purpose string, exposed once the
key exists.

### Steps

1. **Find the exact purpose string and length.** Check the `spake2` line in `docs/api.md` /
   `_boss.py` in the Python `magic-wormhole` source (or the `spake2` Python package used for the
   crypto test vectors, per AGENTS.md's crypto rule) for the literal purpose string and output
   length Python's `derive_key(key, b"wormhole:verifier")` uses, and add it as a cross-checked test
   vector the way `Spake2Symmetric` and the other crypto files do. Do not invent the string —
   AGENTS.md requires published or independently generated test vectors for crypto code.
2. **Write the failing test first.** In `WormholeSessionTest` (or a new test file next to it), add
   a case asserting `session.verifier()` (or similar) matches the vector from step 1 for a known
   session key.
3. **Add the method.** A small `WormholeSession.verifier(): ByteArray` using `deriveKey`, hex-encoded
   at the call site.
4. **Expose it publicly.** Both `sendText`/`sendFile`/`sendDirectory` and `receive` need it before
   the transfer proceeds. The natural spot is a new event:
   - `SendEvent.VerifierAvailable(val verifier: String)` emitted right after `CodeAllocated` (the
     key exists once the peer's PAKE message arrives, which is already awaited before the mailbox
     is used for anything else).
   - `ReceiveEvent` gets the equivalent, emitted before `FileOffered`/`TextReceived`.
   Since neither side currently blocks on the other confirming the verifier (Python's CLI shows it
   informationally, `--verify` just prints it), keep this non-blocking too: emit the event, do not
   add a decision gate. Note in KDoc that an app *can* build a confirmation gate on top by not
   proceeding until the user acts, but the library does not force one.
5. **Update `docs/usage.md`** with a short example showing both sides printing the verifier.

### Check

Both a send and a receive `commonTest` (they already exist end-to-end, using the in-memory
`RendezvousTransport`) see matching verifier strings for the same session, and different strings
for two independent sessions.

---

## 2. Zero mode (about half a day)

### What Python does

`wormhole send -0` (`--zeromode`) uses the fixed nameplate `0` and an empty password, skipping code
entry entirely: both sides just add `-0` to CLI invocation without exchanging any code out of band.
It is meant for same-machine or already-authenticated-channel use (e.g. piping a code through an
existing SSH session), not as a generally weaker password — the security comes from nameplate `0`
being useless without also controlling both ends' timing (server matches whichever two clients meet
there first).

### Where this fits

[Codes.build](../magic-wormhole/src/commonMain/kotlin/uno/lux/wormhole/code/Codes.kt#L22) requires
`wordCount >= 1` and always generates random words; [WormholeConfig.codeLength](../magic-wormhole/src/commonMain/kotlin/uno/lux/wormhole/Wormhole.kt#L55)
requires `>= 1`. Zero mode needs a path that produces code `"0-"` (nameplate `0`, zero words) on
the sending side, and a receive path that accepts it.

### Steps

1. **Loosen `Codes`.** Failing test: `Codes.generateWords(0, ...)` returns `""`, and
   `Codes.build("0", 0, ...)` returns `"0-"`. Change `require(count >= 1)` to `require(count >= 0)`
   and handle the empty-join case (already correct: `joinToString` on an empty range is `""`).
2. **Allow `codeLength = 0`** in `WormholeConfig` (`require(codeLength >= 0)`).
3. **Add a way to request nameplate `0` specifically.** Sending currently always calls
   `RendezvousClient`'s allocate, which the server assigns freely; zero mode needs to *claim*
   nameplate `0` rather than allocate one. Check whether `RendezvousClient` already has a claim-by-
   name path (used when a receiver has a code) that the sender side can reuse for this one fixed
   case; if not, add one. This is the part of the step most likely to need reading
   `RendezvousClient.kt` and `rendezvous/Messages.kt` closely — the sender and receiver currently
   take different code paths (allocate-then-claim vs. claim-only) and zero mode needs the sender to
   use the receiver's path.
4. **Add the public entry point**, for example `Wormhole.sendFile(file, zeroMode = true)` (and the
   equivalents for `sendText`/`sendDirectory`), or a dedicated `WormholeConfig` flag if that reads
   more cleanly given the constructor shape. Document the same caveat Python's docs carry: this is
   for pre-authenticated channels, not a substitute for a real code.
5. **Test end-to-end**: a send with `zeroMode = true` and a `receive("0-")` complete a transfer over
   the in-memory transport.

### Check

`./gradlew jvmTest` passes; a manual `docs/usage.md` example shows the CLI-equivalent call shape.

---

## 3. Multi-file offers without zipping (1–2 days)

### What Python does

Nothing exactly like this, in fairness — Python's `wormhole send` takes one path (file or
directory) per invocation and zips directories. The gap here is narrower than "Python has this and
we don't": it's that `sendDirectory` is the only way to send more than one `OutgoingFile` in this
library, and it always zips, forcing the receiver to unpack (or keep a zip) even when they wanted
the individual files. Add a way to send a list of files as **separate sequential offers in one
wormhole session**, so the receiver decides per file and gets them unzipped, without needing a new
code per file.

### Where this fits

- [Wormhole.sendDirectory](../magic-wormhole/src/commonMain/kotlin/uno/lux/wormhole/Wormhole.kt#L291)
  already takes `List<OutgoingFile>` — reuse `checkDirectory`'s validation (minus the zip step) for
  the new method.
- [FileTransfer.send/receive](../magic-wormhole/src/commonMain/kotlin/uno/lux/wormhole/FileTransfer.kt#L27)
  each open one transit connection per call; sending N files means N transit connections (direct
  connection setup is cheap once a relay/hint exchange has happened once, but confirm whether the
  transit key or hints need to be re-established per file — check `Transit.start`/`connect` for
  whether they are safe to call more than once per `WormholeSession`, or whether each file needs its
  own `Transit` instance as today).
- `withSenderMailbox` (in `Wormhole.kt`, used by all three send methods) currently wraps exactly one
  offer/answer exchange before the mailbox closes; it needs to stay open across N offers.

### Steps

1. **Design the wire format first.** Two options, in order of preference:
   - Send N independent `{"offer": {"file": ...}}` messages in sequence, each followed by its own
     transit connection and its own `{"answer": {"file_ack": "ok"}}`/reject, reusing one
     `WormholeSession` (one mailbox) for all of them. This matches the existing single-file offer
     format exactly, just repeated — no new message type.
   - A batched `{"offer": {"files": [...]}}` — more efficient but is a new, non-Python wire
     message, so a Python `wormhole` peer could not receive it. **Prefer the first option**: it
     stays wire-compatible with Python peers, who will just see N separate offers (Python's
     receiver already loops on incoming offers on the same mailbox — verify this against
     `cmd_receive.py`/the protocol docs before relying on it).
2. **Write the failing test first**: an in-memory `commonTest` where a sender offers 3 files over
   one code and a receiver accepts all 3, ending with distinct files on disk (not a zip).
3. **Sending side.** Add `Wormhole.sendFiles(files: List<OutgoingFile>): Flow<SendFileEvent>` (naming
   TBD — keep it distinct from `sendDirectory`, which keeps its zip behavior for existing callers;
   this is additive, not a replacement). Loop `sendOverTransit` per file inside one
   `withSenderMailbox` block, emitting `SendEvent.Progress` per file (need a new event field or a
   file index — check whether `Progress(sentBytes, totalBytes)` should gain a `fileIndex`/`fileName`
   or whether a wrapping event is cleaner) and one `Completed` at the end.
4. **Receiving side.** `receive()`'s current flow ends after the first `FileOffered`/`TextReceived`.
   Change it to keep pulling offers from the same mailbox until the sender signals it is done —
   this needs an explicit "no more files" signal, since Python has no such thing for this new
   sequential case: add a trailing message (e.g. `{"offer": {"done": true}}` sent after the last
   real file, only used by this library's own sender/receiver pair) or simply rely on mailbox close
   as the end-of-stream signal and confirm `session.receive()` surfaces closure cleanly as
   `Flow` completion rather than an exception.
5. **Decide accept/reject granularity.** Presumably the receiver can accept some files and reject
   others in the same session — confirm `FileOffered.reject()` closes only that file's transit
   connection, not the mailbox, before relying on it for this.
6. **Update `docs/usage.md`** with a "sending several files without zipping" example, contrasting it
   with the existing zipped-directory example.

### Check

New `commonTest` covering: all accepted, one rejected mid-sequence, and a peer disconnecting after
file 2 of 3 (existing error paths should still surface correctly per-file).

---

## 4. Dilation (large; treat this breakdown as a starting point, not a final plan)

### What Python does

Dilation (`wormhole._dilation`) replaces the one-shot Transit connection with a persistent,
reconnectable, multiplexed channel opened once per wormhole session. It adds:

- A **dilation version negotiation** message exchanged over the existing mailbox, separate from
  the transit offer/answer used today.
- **Leader/follower roles** (decided by comparing sides' random tie-breaker values) that determine
  who drives reconnection.
- Its own **connection hints and relay protocol** (similar in shape to `transit/Hints.kt` and
  `transit/Transit.kt`'s relay handshake, but a different wire format — do not assume it reuses
  the existing `DirectHint`/relay-token handshake without checking).
- A **framing layer over the raw connection** supporting multiple logical subchannels
  (multiplexing), sequence numbers, and resumption after a connection drop, so a dropped Wi-Fi
  connection does not kill the whole session the way it would with today's one-shot `Transit`.
- Used today mainly by `wormhole-transit-relay`-independent tools built on top of `wormhole` (e.g.
  `wormhole ssh`-style tools), not by `wormhole send/receive` themselves, which still use classic
  Transit. **Confirm this is still true in the current Python release before starting** — if
  `send`/`receive` have since moved to Dilation, the interop test in AGENTS.md
  (`WORMHOLE_INTEROP=1 ./gradlew jvmTest`) is the fastest way to notice.

### Why this is different from the other three

There is no equivalent of the `spake2`/`nacl` test-vector story for Dilation — it is a framing and
session-management protocol, not primarily a cryptographic primitive, though it does layer new
per-subchannel keys derived the same HKDF way as everything else here. The main risk is protocol
fidelity (getting the message shapes and state machine right against a moving, under-documented
target), not crypto correctness.

### Suggested phases

1. **Spike: read the protocol, don't write library code yet (2–3 days).** Read
   `wormhole/_dilation/*.py` in the Python source end to end, and any protocol doc that ships with
   it (`docs/dilation-protocol.md` if present in the version being targeted). Write up, as a
   revision of this plan section, the actual message formats, the leader/follower decision rule,
   and the subchannel framing — this plan cannot specify them correctly without that reading, and
   guessing would violate the "never invent crypto/protocol" spirit of AGENTS.md's crypto rule even
   though Dilation is framing rather than a cipher.
2. **Land the mailbox-level version negotiation** as its own small, testable piece: exchange the
   dilation-versions message, agree on a version, and expose which side is leader — with no actual
   dilated connection yet. This is testable against the existing in-memory `RendezvousTransport`
   the same way the current PAKE exchange is tested.
3. **Land a bare single-subchannel dilated connection** (open, send bytes, close) reusing as much
   of `transit/TcpTransitNetwork.kt`'s platform connection code as fits, before adding
   multiplexing.
4. **Add multiplexing and reconnection** once (3) is solid — reconnection is the part most likely to
   need careful state-machine tests (drop the connection mid-transfer in a test, assert the
   transfer resumes).
5. **Expose a public API.** Decide then whether this becomes a new `Wormhole` method (e.g. a raw
   bidirectional stream API distinct from `sendFile`/`receive`) or stays internal until a concrete
   use case (this library has none yet — no app in this repo's scope needs `wormhole ssh`-style
   sessions) asks for one. Given AGENTS.md's "public API is small," lean toward not exposing
   anything publicly until a real caller exists.
6. **Interop-test against the real `wormhole` CLI** (`WORMHOLE_INTEROP=1 ./gradlew jvmTest`) once a
   tool on the Python side actually uses Dilation to test against — confirm one exists and is
   reachable before committing to this step.

### Estimate

Weeks, not days — plan for this to be its own multi-PR effort with its own commits per AGENTS.md's
"one task, one commit," not a single pass through this repo.

---

## 5. Resumable file transfer (depends on §1 Dilation; medium, once Dilation lands)

### What this is

Dilation (§1) makes the underlying connection survive a drop and reconnect, but a dropped
connection today still restarts the whole file: nothing tracks which bytes the receiver already
has. This section is the missing app-level layer on top of a dilated stream: detect a resumed
connection mid-file and continue from the last acknowledged byte instead of byte 0. Python's
`wormhole` has no equivalent of this either — this would be new protocol design, not a port, so
treat message formats here as this library's own and confirm they don't collide with anything a
real `wormhole` peer might send on the same stream.

### Why it belongs here and not in `wormhole-rift`

It's the "which chunks did you get" bookkeeping, not UI or app state — exactly the kind of protocol
logic AGENTS.md scopes to the library ("must stay usable by anyone"). `wormhole-rift` should only
need to consume whatever the library exposes (e.g. persisting a resume token/offset across app
restarts) and build retry UI on top; it should not need to reimplement chunk tracking itself.

### Steps

1. **Depends on §1 landing first** — specifically phase 4 (multiplexing and reconnection). Nothing
   here is buildable before a dilated connection can actually drop and resume at the framing layer.
2. **Design the resume handshake.** On reconnect, the receiver needs to tell the sender how many
   bytes of the current file it already wrote (a running SHA-256 or byte count checkpointed
   periodically, not just at the end the way `FileTransfer.receive` does today — see
   [FileTransfer.kt:107-127](../magic-wormhole/src/commonMain/kotlin/uno/lux/wormhole/FileTransfer.kt#L107-L127)
   for the current all-at-the-end hashing). The sender then seeks its source to that offset and
   continues. Write this as a small explicit message on the dilated stream (e.g.
   `{"resume": {"received": <bytes>}}`) sent once per reconnect, before resuming the raw byte
   stream.
3. **Sender-side seek.** `OutgoingFile.open()` returns a fresh `RawSource` from byte 0
   ([Wormhole.kt:169](../magic-wormhole/src/commonMain/kotlin/uno/lux/wormhole/Wormhole.kt#L169));
   resuming needs the ability to open from an offset. Check whether `RawSource`/`kotlinx-io` has a
   cheap seek, or whether this needs `OutgoingFile` to gain an offset-aware open variant.
4. **Persisted resume across process restarts (not just in-session reconnects).** A dropped Wi-Fi
   connection is one thing; the app being killed and relaunched is another — the wormhole code
   itself would be gone by then unless the app kept it. Decide the scope: this plan's minimum bar
   is resuming within one still-open `Wormhole` session (surviving reconnects Dilation itself
   handles); resuming across a fresh `receive(code)`/`sendFile(...)` call after the process
   restarted is a stretch goal, since it needs the same code to still be valid, which Python's
   nameplate/mailbox lifetime does not guarantee.
5. **Write the failing test first**: an in-memory test that interrupts the transit stream partway
   (reusing whatever fake-drop hook §1's reconnection tests add), then asserts the transfer
   completes without re-sending already-acknowledged bytes (assert on bytes sent, not just the
   final checksum, or a bug that resends everything would still pass).
6. **Expose it minimally.** Likely no new public API at all if resume is automatic underneath
   `sendFile`/`receive` once §1 lands — the event stream (`SendEvent.Progress`, etc.) already
   reports bytes sent, which is enough for an app to show "resumed at 4.2 MB" without a dedicated
   event. Add one only if testing shows callers need to distinguish "resumed" from "progressed
   normally."

### Check

A test that drops the dilated connection at a known byte offset mid-file and asserts: (a) the
transfer still completes, (b) the sender did not re-read/re-send bytes before the acknowledged
offset, (c) the final SHA-256 still matches.

---

## Order and dependencies

None of §1-4 depend on each other. §5 depends on §1. Suggested order: **2 (verifier) → 3 (zero
mode) → 4 (multi-file) → 1 (Dilation) → 5 (resumable transfer)** — smallest and most self-contained
first, and 5 only becomes buildable once 1 is done.
