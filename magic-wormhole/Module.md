# Module magic-wormhole-kotlin

A Kotlin Multiplatform implementation of the [Magic Wormhole](https://magic-wormhole.readthedocs.io/)
protocol: get things from one computer to another, safely, with a short human-pronounceable code.

For task-based examples (sending, receiving, welcome messages, transit hints), see
[docs/usage.md](https://github.com/lux-uno/magic-wormhole-kotlin/blob/main/docs/usage.md).

# Package uno.lux.wormhole

The public API: [uno.lux.wormhole.Wormhole] and the types used to configure it, send, and receive.

# Package uno.lux.wormhole.crypto

SHA-256, HMAC, HKDF, XSalsa20-Poly1305 secretbox, Ed25519, and SPAKE2. Internal.

# Package uno.lux.wormhole.code

The PGP word list and wormhole code generation. Internal.

# Package uno.lux.wormhole.rendezvous

Mailbox server messages and client. Internal.

# Package uno.lux.wormhole.transit

Direct and relay TCP connections, and encrypted records. Internal.

# Package uno.lux.wormhole.zip

Zip archive reading and writing for folder transfers. Internal.
