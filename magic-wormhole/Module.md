# Module magic-wormhole-kotlin

A Kotlin Multiplatform implementation of the [Magic Wormhole](https://magic-wormhole.readthedocs.io/)
protocol: get things from one computer to another, safely, with a short human-pronounceable code.

For task-based examples (sending, receiving, welcome messages, transit hints), see
[docs/usage.md](https://github.com/lux-uno/magic-wormhole-kotlin/blob/main/docs/usage.md).

# Package uno.lux.wormhole

The public API: [uno.lux.wormhole.Wormhole] and the types used to configure it, send, and receive.

# Package uno.lux.wormhole.crypto

SHA-256, HMAC, HKDF, XSalsa20-Poly1305 secretbox, Ed25519, SPAKE2, X25519, ChaCha20-Poly1305,
BLAKE2s, and a Noise Protocol handshake scoped to `Noise_NNpsk0_25519_ChaChaPoly_BLAKE2s`
(Dilation's L2 handshake). Internal.

# Package uno.lux.wormhole.code

The PGP word list and wormhole code generation. Internal.

# Package uno.lux.wormhole.rendezvous

Mailbox server messages and client. Internal.

# Package uno.lux.wormhole.transit

Direct and relay TCP connections, and encrypted records. Internal.

# Package uno.lux.wormhole.zip

Zip archive reading and writing for folder transfers. Internal.

# Package uno.lux.wormhole.dilation

Dilation: mailbox-level version/role negotiation, connection hints, racing/selecting one L2 TCP
connection (direct or relayed, Noise-handshaked), and multiplexed subchannels on top of it
(OPEN/DATA/CLOSE with ACKs, dedup, and an outbound retry queue). Reconnection after a dropped
connection is not yet implemented. Internal.
