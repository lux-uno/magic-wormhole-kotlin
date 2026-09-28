# Note: Tor and Dilation are not symmetric

Tor support in general is a separate, larger effort and out of scope for the current Dilation
work (see [close-python-feature-gaps.md](close-python-feature-gaps.md) §"Goal" and
[dilation-implementation.md](dilation-implementation.md)). This file records one caveat about how
Tor interacts with Dilation specifically, for whenever Tor support is picked up.

## The caveat

There is no protocol requirement that both peers use Tor symmetrically during Dilation.

In the Python reference implementation (`wormhole/_dilation/connector.py`), Tor is a per-side,
per-connection configuration detail, not something negotiated between peers:

- If a side is configured with a `tor=` object, its `Connector` skips opening a local listening
  TCP port for direct hints (`if not self._no_listen and not self._tor: ...`) and dials all
  outbound hints — direct and relay alike — through the Tor SOCKS proxy.
- If a side is *not* configured with Tor, it simply skips any `tor-tcp-v1` hints the peer
  advertises (`if isinstance(h, TorTCPV1Hint) and not self._tor: continue`) and falls through to
  whatever direct/relay hints are mutually usable.

So one peer can dial over Tor while the other dials directly (or via the Transit relay) with no
protocol-level negotiation of "both sides must match." Each side just makes its own connection
attempts under its own configuration, and the usual L2 candidate-selection process picks whichever
one actually connects. This is consistent with `dilation-abilities` in the wormhole `version`
message ([dilation-protocol.rst](../magic-wormhole/docs/dilation-protocol.rst) — the reference
docs checked out at `magic-wormhole/`): it lists hint *types* (`direct-tcp-v1`, `relay-v1`), not a
"my peer must also use Tor" flag. Tor-ness is a property of how a side makes its own outbound
connections, not a mutually agreed protocol mode.

## Implication for a future Kotlin implementation

Don't design a Tor toggle as a Dilation-level negotiated setting (e.g. something exchanged in the
`please`/`dilate-N` handshake). It's local configuration on each side, same as it is in Python:
whether *this* wormhole instance is told to route through Tor determines whether *this* side
listens for direct hints, advertises `tor-tcp-v1` hints of its own, and dials outbound connections
(including to the Transit relay) via a SOCKS proxy — independent of what the peer decides.

As `close-python-feature-gaps.md` §"Hints" already notes, a `tor-tcp-v1` hint type exists
conceptually in Python's data model but isn't advertised by default there either, so there's no
working default-Tor-hint wire behavior to port yet — just the asymmetry rule above to keep in mind
once that work starts.
