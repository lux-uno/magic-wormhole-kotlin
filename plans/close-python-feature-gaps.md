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

**Status: done, `internal`-only.** See [plans/dilation-implementation.md](dilation-implementation.md)
for the full task-by-task record of what landed (the crypto primitives, connector, records,
multiplexing, and reconnection) and two non-obvious concurrency bugs found along the way. No public
API exists yet (§11 of that plan) — nothing in `wormhole-rift` calls it yet, so exposing one was
deferred per AGENTS.md's "public API is small" principle rather than designed speculatively.

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
per-subchannel keys derived the same HKDF way as everything else here, and it does require a Noise
Protocol handshake (a cryptographic primitive this library does not have at all yet — see below).
The main risk is protocol fidelity (getting the message shapes and state machine right), *and* the
absence of X25519/ChaCha20-Poly1305/BLAKE2s/Noise in this codebase.

### Research spike findings (read `wormhole/_dilation/*.py` + `docs/dilation-protocol.rst` end to end)

The spike below replaces step 1 of the original phase breakdown; the formats and state machine here
are read directly from the reference implementation (not the older, slightly-aspirational protocol
doc — where the two disagree, the code is authoritative and is what's recorded here).

**Version negotiation (mailbox `version` phase, before any dilate-n phase exists).** Each side's
encrypted `version` message gains two keys: `"can-dilate": ["ged"]` (the *only* version string the
current reference implementation supports — not `"1"`, despite an older doc draft; must match
exactly for interop) and `"dilation-abilities": [{"type":"direct-tcp-v1"},{"type":"relay-v1"}]`.
Both sides intersect their `can-dilate` lists; empty intersection is a hard failure
(`OldPeerCannotDilateError`). `dilation_key = HKDF-SHA256(mainKey, info=b"dilation-v1", length=32)`
(no salt) — derived once from the main PAKE key and reused unchanged for the whole session,
including every reconnect attempt (only the ephemeral Noise keys change per attempt, not this PSK).

**Control channel over the mailbox.** Separate phases named `dilate-0`, `dilate-1`, `dilate-2`, ...
(a per-side monotonic counter), each an encrypted JSON dict with a `"type"` field, delivered to the
receiver strictly in phase-number order (buffer and reorder if the mailbox delivers out of order).
Message shapes:
- `{"type": "please", "side": "<8-byte-hex-string>", "use-version": "ged"}` — `side` here is a
  **separate** random value from the mailbox `side`, freshly generated per wormhole for Dilation
  specifically (8 random bytes, hex-encoded). Sent by both peers as soon as their own `dilate()` is
  called and their own version/dilation-key are known.
- `{"type": "connection-hints", "hints": [...]}` — see hint shapes below.
- `{"type": "reconnect"}` — Leader-only, signals "my connection died, starting a new generation."
- `{"type": "reconnecting"}` — Follower-only, sent in reply once the Follower has torn down its own
  side and is ready for the new generation.

**Leader/Follower.** On receiving the peer's `please`, compare the two 8-byte-hex `side` strings
lexicographically: the **higher** string is Leader, the lower is Follower (equal is a fatal
"reflection" error — should not happen since sides are independently random). Leader allocates
subchannel ids as odd numbers starting at 1 (`1, 3, 5, ...`); Follower as even numbers starting at 2
(`2, 4, 6, ...`); subchannel id `0` is reserved and is the always-open control channel, available
the moment the first L2 connection is selected (no OPEN record needed for it).

**Hints.** Two JSON shapes, sent inside `connection-hints`:
- Direct: `{"type": "direct-tcp-v1", "priority": <float>, "hostname": "<str>", "port": <int>}`.
- Relay: `{"type": "relay-v1", "hints": [<direct hint dicts of the relay's own address(es)>]}`.
(A `tor-tcp-v1` type exists conceptually in the Python code's data model but is never advertised by
default and is out of scope here.) These are a **different wire shape from `transit/Hints.kt`'s
`"hints-v1"`/`DirectHint`** — do not reuse that type, write a parallel one. Direct hints are dialled
immediately, all in parallel, no staggering. A configured relay is dialled too, but only after a
fixed `2.0s` delay *if* at least one direct hint exists and direct listening isn't disabled
(`RELAY_DELAY = 2.0`); this is the only fixed timing constant in connection establishment.

**Per-connection wire protocol (L2), in order, for every candidate TCP connection (dialled or
accepted):**
1. *(relay-dialled connections only)* send ASCII line `"please relay " + hex(HKDF(dilation_key, 32,
   info=b"transit_relay_token")) + " for side " + side + "\n"`; the relay must reply with exactly
   `"ok\n"` before continuing, otherwise disconnect.
2. **Prologue** (every connection, both directions): Leader sends
   `"Magic-Wormhole Dilation Handshake v1 Leader\n\n"`; Follower sends
   `"Magic-Wormhole Dilation Handshake v1 Follower\n\n"`. Each side expects to *read* the other
   role's exact prologue line back; any mismatch (including an unrelated TCP server accidentally
   reached via a bad hint) is an immediate disconnect, no error surfaced to the app.
3. **Framing from here on**: `4-byte big-endian length` + payload, one Noise message per frame.
4. **Noise handshake**, pattern **`Noise_NNpsk0_25519_ChaChaPoly_BLAKE2s`**: `NN` = no static keys
   exchanged, `psk0` = the PSK (`dilation_key`) is mixed into the *first* handshake message,
   `25519` = X25519 DH, `ChaChaPoly` = ChaCha20-Poly1305 AEAD, `BLAKE2s` = hash/HKDF function. Leader
   is Noise initiator (sends message 1: `-> psk, e`); Follower is responder (reads it, replies with
   message 2: `<- e, ee`). No handshake payloads are used (empty payload both messages).
5. **KCM (Key Confirmation Message)** = an empty Noise transport message, wire tag `0x00`
   (see record tags below). Immediately after completing its side of the handshake, the **Follower**
   sends a KCM. The **Leader does not send a KCM yet** — for each candidate connection it waits to
   *receive* a KCM (proof the peer had the right PSK, i.e. the right wormhole code) before treating
   that connection as viable. Current reference-implementation selection policy is **first viable
   connection wins** (no scoring despite the older protocol doc describing one) — once the Leader
   accepts a winner, it sends its *own* KCM on that connection only, and drops every other
   candidate (dialled-but-not-yet-viable, or viable-but-not-chosen). The Follower learns which
   connection won purely by which one receives the Leader's KCM; it drops all others.

**Record tags** (1 byte, prefixing Noise plaintext, exact field layout is the ground truth to
replicate byte-for-byte):
```
KCM   = 0x00                                            (no body)
PING  = 0x01   ping_id:4B
PONG  = 0x02   ping_id:4B                                (echo of the PING's id)
OPEN  = 0x03   scid:4B-BE  seqnum:4B-BE  subprotocol:UTF-8 (rest of payload)
DATA  = 0x04   scid:4B-BE  seqnum:4B-BE  payload:bytes   (rest of payload)
CLOSE = 0x05   scid:4B-BE  seqnum:4B-BE
ACK   = 0x06   resp_seqnum:4B-BE
```
Noise per-message plaintext limit is `65535 - 16 = 65519` bytes (16-byte Poly1305 tag overhead,
ciphertext capped at `65535`); a record whose payload exceeds this is split into multiple
consecutive Noise ciphertexts concatenated inside one outer length-prefixed frame. There is no KCP
or other multiplexing transport underneath — plain TCP with this custom framing on top.

**Reliability / ACK / resend.** `seqnum` is per-direction, monotonically increasing, assigned only
to OPEN/DATA/CLOSE (not ACK/PING/PONG/KCM), starting at 0. Every inbound OPEN/DATA/CLOSE is ACKed
immediately regardless of whether it turns out to be a duplicate. The receiver tracks a single
watermark (`highest_inbound_acked`, updated via `max()`); any inbound record with
`seqnum <= watermark` is dropped (after re-ACKing) — this is what gives in-order, at-most-once
delivery without needing per-subchannel sequence tracking. The sender keeps every OPEN/DATA/CLOSE it
has sent in an outbound queue until it is retired by a matching ACK (`resp_seqnum >= seqnum`,
pop-from-front since acks are cumulative in practice via the max-based watermark on the far side).
When a new L2 connection is selected (initial or after reconnect), the *entire* not-yet-acked queue
is resent, in original order, before any newly-queued write is allowed out — this queue-and-resend
behavior, not anything generation-specific, is what makes the channel durable across reconnects.
PING/PONG/ACK/KCM are fire-and-forget, never queued, silently dropped if no connection is currently
up.

**Reconnection triggers and roles.** Only the Leader runs a liveness timer: on the winning
connection, sends a `PING` (4 random bytes) every `30.0s` of otherwise-idle time and expects a
`PONG` echo; missing two consecutive intervals with no traffic (ping or otherwise) is "connection
dead," at which point the Leader disconnects and sends `{"type":"reconnect"}` over the mailbox
control channel, entering a `FLUSHING`-equivalent wait for the Follower's `{"type":"reconnecting"}`
before starting a fresh connection-establishment round (a new "generation" — no generation number is
ever transmitted; ordering is implicit in the dilate-n phase sequence and the reconnect/reconnecting
handshake). The Follower never initiates reconnection on its own: if it notices its connection died
first, it just waits (does not send anything) until the Leader's `reconnect` arrives; if the Leader
sends `reconnect` while the Follower's own connection still looks fine, the Follower forcibly tears
its own connection down first, *then* replies `reconnecting`. Every new connection reuses the same
`dilation_key` PSK; hint exchange (a fresh `connection-hints` message) happens again per generation.

**Manager state machine** (mirror this as a Kotlin sealed-state machine):

| From | Event | To | Notes |
|---|---|---|---|
| WAITING | start (app calls dilate) | WANTING | send `please` |
| WANTING | received peer's `please` | CONNECTING | decide leader/follower, start connecting |
| CONNECTING | a connection is selected | CONNECTED | — |
| CONNECTED | (Leader) connection lost | FLUSHING | send `reconnect` |
| FLUSHING | received `reconnecting` | CONNECTING | start new generation |
| CONNECTED | (Follower) connection lost | LONELY | wait passively |
| LONELY | received `reconnect` | CONNECTING | send `reconnecting`, start new generation |
| CONNECTED | (Follower) received `reconnect` while still connected | ABANDONING | disconnect own connection first |
| ABANDONING | own connection now lost | CONNECTING | send `reconnecting`, start new generation |
| CONNECTING | received `reconnect` (rare/self-loop) | CONNECTING | stop current attempt, restart generation |
| any of WAITING/WANTING/CONNECTING/CONNECTED/FLUSHING/LONELY/ABANDONING | stop (app/session tears down) | STOPPED (via STOPPING while a connection is being torn down) | — |
| WANTING/CONNECTING (both roles) | `connection-hints` received | (same state) | too-early hints ignored in WANTING, applied (raced) in CONNECTING; ignored everywhere later (stale) |

None of this exists in the Kotlin library yet, and neither does any of the crypto it depends on:
there is no X25519, no ChaCha20-Poly1305, no BLAKE2s, and no Noise handshake state machine anywhere
in `uno.lux.wormhole.crypto` today (`Ed25519.kt`/`SecretBox.kt` are unrelated: Edwards signing for
SPAKE2 and NaCl XSalsa20-Poly1305 for Transit). Per AGENTS.md's crypto rule, each of these needs a
from-scratch implementation checked against independently-published test vectors (RFC 7748 for
X25519, RFC 8439 for ChaCha20-Poly1305, RFC 7693 for BLAKE2s) before the Noise handshake — and
everything above it — can be trusted; see `plans/dilation-implementation.md` for how this is broken
into commits.

### Suggested phases (superseded by `plans/dilation-implementation.md`, kept here for history)

1. ~~Spike: read the protocol, don't write library code yet~~ — done, findings recorded above.
2. Land the mailbox-level version negotiation, leader/follower decision, no TCP yet.
3. Land a bare single-subchannel dilated connection reusing `transit/TcpTransitNetwork.kt`.
4. Add multiplexing and reconnection.
5. Decide on public API exposure (lean toward staying internal until a real caller exists).
6. Interop-test against the real `wormhole` CLI once Dilation-using Python tooling is reachable.

### Estimate

Weeks, not days — plan for this to be its own multi-PR effort with its own commits per AGENTS.md's
"one task, one commit," not a single pass through this repo. See `plans/dilation-implementation.md`
for the active, step-by-step breakdown (crypto primitives first, then protocol layers).

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
