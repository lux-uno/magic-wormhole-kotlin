package uno.lux.wormhole

/** Base class for all errors reported by this library. */
public open class WormholeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The code does not have the form `<number>-<word>[-<word>...]`. */
public class InvalidCodeException(code: String) : WormholeException("Invalid wormhole code: \"$code\"")
