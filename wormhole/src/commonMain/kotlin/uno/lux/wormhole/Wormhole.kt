package uno.lux.wormhole

/** Default Magic Wormhole rendezvous (mailbox) server. */
public const val DEFAULT_RENDEZVOUS_URL: String = "ws://relay.magic-wormhole.io:4000/v1"

/** Default Magic Wormhole transit relay. */
public const val DEFAULT_TRANSIT_RELAY: String = "tcp:transit.magic-wormhole.io:4001"

/** Application ID used by the `wormhole` CLI and wormhole-william for text and file transfers. */
public const val DEFAULT_APP_ID: String = "lothar.com/wormhole/text-or-file-xfer"
