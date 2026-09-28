package uno.lux.wormhole.dilation

import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import uno.lux.wormhole.TransitException
import uno.lux.wormhole.crypto.randomBytes
import uno.lux.wormhole.transit.DirectHint
import uno.lux.wormhole.transit.FakeInternet
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

class DilationConnectorTest {
    private val dilationKey = randomBytes(32)

    private fun connector(
        role: DilationRole,
        internet: FakeInternet,
        side: String,
        addresses: List<String> = emptyList(),
        canListen: Boolean = true,
        relay: DilationHint.Direct? = null,
        relayDelay: Duration = 2.seconds,
    ) = DilationConnector(role, dilationKey, side, internet.network(addresses, canListen), relay, relayDelay)

    private suspend fun connectPair(
        leader: DilationConnector,
        follower: DilationConnector,
    ): Pair<DilationConnection, DilationConnection> {
        val leaderHints = leader.start()
        val followerHints = follower.start()
        return coroutineScope {
            val l = async { leader.connect(followerHints) }
            val f = async { follower.connect(leaderHints) }
            l.await() to f.await()
        }
    }

    @Test
    fun directHintsCompleteTheHandshakeOnBothSides() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val leader = connector(DilationRole.LEADER, internet, "aaaaaaaaaaaaaaaa", addresses = listOf("10.0.0.1"))
            val follower =
                connector(DilationRole.FOLLOWER, internet, "bbbbbbbbbbbbbbbb", addresses = listOf("10.0.0.2"))
            val (l, f) = connectPair(leader, follower)
            l.close()
            f.close()
            assertTrue(internet.connectAttempts.none { it.startsWith(FakeInternet.RELAY_HOST) })
        }

    @Test
    fun relayIsUsedWhenNeitherSideCanListen() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val leader =
                connector(
                    DilationRole.LEADER,
                    internet,
                    "aaaaaaaaaaaaaaaa",
                    canListen = false,
                    relay = FakeInternet.RELAY.toDilationHint(),
                )
            val follower =
                connector(
                    DilationRole.FOLLOWER,
                    internet,
                    "bbbbbbbbbbbbbbbb",
                    canListen = false,
                    relay = FakeInternet.RELAY.toDilationHint(),
                )
            val (l, f) = connectPair(leader, follower)
            l.close()
            f.close()
            assertTrue(internet.connectAttempts.all { it.startsWith(FakeInternet.RELAY_HOST) })
        }

    @Test
    fun theFirstViableConnectionWinsWhenDirectAndRelayBothSucceed() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val leader =
                connector(
                    DilationRole.LEADER,
                    internet,
                    "aaaaaaaaaaaaaaaa",
                    addresses = listOf("10.0.0.1"),
                    relay = FakeInternet.RELAY.toDilationHint(),
                    relayDelay = ZERO,
                )
            val follower =
                connector(
                    DilationRole.FOLLOWER,
                    internet,
                    "bbbbbbbbbbbbbbbb",
                    addresses = listOf("10.0.0.2"),
                    relay = FakeInternet.RELAY.toDilationHint(),
                    relayDelay = ZERO,
                )
            val (l, f) = connectPair(leader, follower)
            l.close()
            f.close()
            // Both direct and relay attempts were raced; exactly one connection won on each side
            // (connectPair already asserts that implicitly by returning a single winner per side).
            assertTrue(internet.connectAttempts.any { !it.startsWith(FakeInternet.RELAY_HOST) })
            assertTrue(internet.connectAttempts.any { it.startsWith(FakeInternet.RELAY_HOST) })
        }

    @Test
    fun anImpostorWithABadPrologueDoesNotWin() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val follower =
                connector(DilationRole.FOLLOWER, internet, "bbbbbbbbbbbbbbbb", addresses = listOf("10.0.0.2"))
            val hints = follower.start()
            val port = (hints.single() as DilationHint.Direct).port

            val connectResult =
                async {
                    runCatching { follower.connect(emptyList()) }
                }
            val impostor = internet.network(listOf("10.9.9.9")).connect("10.0.0.2", port)
            impostor.output.writeFully(ByteArray(100)) // garbage instead of the real Leader prologue
            impostor.output.flush()

            assertFailsWith<TransitException> { connectResult.await().getOrThrow() }
            follower.close()
        }

    @Test
    fun wrongDilationKeyFailsTheNoiseHandshake() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val leaderKey = dilationKey
            val followerKey = randomBytes(32)
            val leader =
                DilationConnector(
                    DilationRole.LEADER,
                    leaderKey,
                    "aaaaaaaaaaaaaaaa",
                    internet.network(listOf("10.0.0.1")),
                    relay = null,
                )
            val follower =
                DilationConnector(
                    DilationRole.FOLLOWER,
                    followerKey,
                    "bbbbbbbbbbbbbbbb",
                    internet.network(listOf("10.0.0.2")),
                    relay = null,
                )
            val leaderHints = leader.start()
            val followerHints = follower.start()
            val leaderResult = async { runCatching { leader.connect(followerHints) } }
            val followerResult = async { runCatching { follower.connect(leaderHints) } }

            assertTrue(leaderResult.await().isFailure)
            assertTrue(followerResult.await().isFailure)
        }
}

private fun DirectHint.toDilationHint() = DilationHint.Direct(hostname, port)
