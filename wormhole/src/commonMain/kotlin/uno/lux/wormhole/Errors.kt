package uno.lux.wormhole

/** Base class for all errors reported by this library. */
public open class WormholeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The code does not have the form `<number>-<word>[-<word>...]`. */
public class InvalidCodeException(code: String) : WormholeException("Invalid wormhole code: \"$code\"")

/** The mailbox server reported an error, for example `crowded` or a welcome error. */
public class WormholeServerException(public val serverError: String) :
    WormholeException("Wormhole server error: $serverError")

/** The two sides used different codes, so the keys do not match. */
public class WrongCodeException : WormholeException("The wormhole code is wrong (the keys do not match)")

/** The receiver declined the transfer. */
public class TransferRejectedException : WormholeException("The receiver rejected the transfer")

/** The other side stopped with an error message. */
public class PeerErrorException(public val peerMessage: String) :
    WormholeException("The other side reported an error: $peerMessage")

/** The other side sent something that does not follow the protocol. */
public class WormholeProtocolException(message: String, cause: Throwable? = null) :
    WormholeException(message, cause)

/** The data connection (direct or through the transit relay) failed. */
public class TransitException(message: String, cause: Throwable? = null) : WormholeException(message, cause)

/** The mailbox server could not be reached, or the connection was lost. */
public class ServerConnectionException(message: String, cause: Throwable? = null) :
    WormholeException(message, cause)
