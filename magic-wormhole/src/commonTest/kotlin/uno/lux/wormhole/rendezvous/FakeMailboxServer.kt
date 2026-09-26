package uno.lux.wormhole.rendezvous

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory mailbox server that follows the Magic Wormhole server protocol closely enough
 * for client tests: nameplates, mailboxes, message echo, acks and errors.
 */
internal class FakeMailboxServer(
    private val welcomeError: String? = null,
    private val maxSidesPerNameplate: Int = 2,
) {
    private val mutex = Mutex()
    private var nextNameplate = 1
    private val nameplateToMailbox = mutableMapOf<String, String>()
    private val nameplateSides = mutableMapOf<String, MutableSet<String>>()
    private val mailboxes = mutableMapOf<String, MutableList<ServerMessage.Message>>()
    private val listeners = mutableMapOf<String, MutableList<Connection>>()

    /** Every client message the server received, in order. */
    val received = mutableListOf<ClientMessage>()
    val connections = mutableListOf<Connection>()

    val transport: RendezvousTransport = RendezvousTransport { connect() }

    private fun connect(): Connection =
        Connection().also { c ->
            connections += c
            c.push(ServerMessage.Welcome(motd = null, error = welcomeError))
        }

    /** Drops every open connection, like a network failure. */
    fun dropAllConnections() {
        connections.forEach { it.toClient.close() }
    }

    inner class Connection : RendezvousConnection {
        val toClient = Channel<String>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<String> get() = toClient
        var side: String? = null
        var closedByClient = false

        fun push(message: ServerMessage) {
            toClient.trySend(ServerMessage.toJson(message))
        }

        override suspend fun send(text: String) {
            val message = ClientMessage.parse(text) ?: return
            mutex.withLock {
                received += message
                push(ServerMessage.Ack)
                handle(message)
            }
        }

        private fun handle(message: ClientMessage) {
            when (message) {
                is ClientMessage.Bind -> {
                    side = message.side
                }

                ClientMessage.Allocate -> {
                    val nameplate = (nextNameplate++).toString()
                    nameplateToMailbox[nameplate] = "mailbox$nameplate"
                    push(ServerMessage.Allocated(nameplate))
                }

                is ClientMessage.Claim -> {
                    val sides = nameplateSides.getOrPut(message.nameplate) { mutableSetOf() }
                    sides += side!!
                    if (sides.size > maxSidesPerNameplate) {
                        push(ServerMessage.Error("crowded"))
                        return
                    }
                    val mailbox = nameplateToMailbox.getOrPut(message.nameplate) { "mailbox${message.nameplate}" }
                    push(ServerMessage.Claimed(mailbox))
                }

                is ClientMessage.Release -> {
                    push(ServerMessage.Released)
                }

                is ClientMessage.Open -> {
                    listeners.getOrPut(message.mailbox) { mutableListOf() } += this
                    mailboxes[message.mailbox]?.forEach(::push)
                }

                is ClientMessage.Add -> {
                    val mailbox = listeners.entries.first { this in it.value }.key
                    val m = ServerMessage.Message(side!!, message.phase, message.body)
                    mailboxes.getOrPut(mailbox) { mutableListOf() } += m
                    listeners[mailbox].orEmpty().forEach { it.push(m) }
                }

                is ClientMessage.Close -> {
                    listeners[message.mailbox]?.remove(this)
                    push(ServerMessage.Closed)
                }

                is ClientMessage.Ping -> {
                    push(ServerMessage.Pong(message.ping))
                }
            }
        }

        override suspend fun close() {
            closedByClient = true
            toClient.close()
        }
    }
}
