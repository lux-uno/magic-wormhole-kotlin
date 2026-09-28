package uno.lux.wormhole.dilation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uno.lux.wormhole.DEFAULT_APP_ID
import uno.lux.wormhole.WormholeProtocolException
import uno.lux.wormhole.WormholeSession
import uno.lux.wormhole.rendezvous.FakeMailboxServer
import uno.lux.wormhole.rendezvous.RendezvousClient
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DilationManagerTest {
    private val appId = DEFAULT_APP_ID

    private suspend fun CoroutineScope.pair(server: FakeMailboxServer): Pair<RendezvousClient, RendezvousClient> {
        val a = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
        val b = RendezvousClient.connect(server.transport, "ws://fake", appId, this)
        a.bind()
        b.bind()
        val nameplate = a.allocate()
        a.open(a.claim(nameplate))
        b.open(b.claim(nameplate))
        return a to b
    }

    @Test
    fun bothSidesAdvertiseDilationSupportToEachOther() =
        runTest {
            val (a, b) = pair(FakeMailboxServer())
            val sa = WormholeSession(a, appId)
            val sb = WormholeSession(b, appId)
            val ka = async { sa.exchangeKeys("1-purple-sausages") }
            sb.exchangeKeys("1-purple-sausages")
            ka.await()

            assertEquals(true, sa.peerSupportsDilation())
            assertEquals(true, sb.peerSupportsDilation())
            a.shutdown()
            b.shutdown()
        }

    @Test
    fun theHigherSideBecomesLeaderAndTheLowerBecomesFollower() =
        runTest {
            val (a, b) = pair(FakeMailboxServer())
            val sa = WormholeSession(a, appId)
            val sb = WormholeSession(b, appId)
            val ka = async { sa.exchangeKeys("1-purple-sausages") }
            sb.exchangeKeys("1-purple-sausages")
            ka.await()

            val dilationA = DilationManager(sa, side = "ffffffffffffffff")
            val dilationB = DilationManager(sb, side = "0000000000000000")
            val startA = async { dilationA.start() }
            dilationB.start()
            startA.await()

            assertEquals(DilationRole.LEADER, dilationA.role)
            assertEquals(DilationRole.FOLLOWER, dilationB.role)
            assertEquals("0000000000000000", dilationA.peerSide)
            assertEquals("ffffffffffffffff", dilationB.peerSide)
            a.shutdown()
            b.shutdown()
        }

    @Test
    fun bothSidesDeriveTheSameDilationKey() =
        runTest {
            val (a, b) = pair(FakeMailboxServer())
            val sa = WormholeSession(a, appId)
            val sb = WormholeSession(b, appId)
            val ka = async { sa.exchangeKeys("1-purple-sausages") }
            sb.exchangeKeys("1-purple-sausages")
            ka.await()

            val dilationA = DilationManager(sa, side = "aaaaaaaaaaaaaaaa")
            val dilationB = DilationManager(sb, side = "bbbbbbbbbbbbbbbb")
            val startA = async { dilationA.start() }
            dilationB.start()
            startA.await()

            assertContentEquals(dilationA.dilationKey, dilationB.dilationKey)
            assertEquals(32, dilationA.dilationKey.size)
            a.shutdown()
            b.shutdown()
        }

    @Test
    fun defaultSidesAreRandomAndDistinctPerSession() =
        runTest {
            val (a, b) = pair(FakeMailboxServer())
            val sa = WormholeSession(a, appId)
            val sb = WormholeSession(b, appId)
            val ka = async { sa.exchangeKeys("1-purple-sausages") }
            sb.exchangeKeys("1-purple-sausages")
            ka.await()

            val dilationA = DilationManager(sa)
            val dilationB = DilationManager(sb)
            val startA = async { dilationA.start() }
            dilationB.start()
            startA.await()

            assertNotEquals(dilationA.peerSide, dilationB.peerSide)
            assertEquals(setOf(DilationRole.LEADER, DilationRole.FOLLOWER), setOf(dilationA.role, dilationB.role))
            a.shutdown()
            b.shutdown()
        }

    @Test
    fun rejectsAPeerAdvertisingAnUnsupportedDilationVersion() =
        runTest {
            val (a, b) = pair(FakeMailboxServer())
            val sa = WormholeSession(a, appId)
            val sb = WormholeSession(b, appId)
            val ka = async { sa.exchangeKeys("1-purple-sausages") }
            sb.exchangeKeys("1-purple-sausages")
            ka.await()

            val dilationA = DilationManager(sa, side = "aaaaaaaaaaaaaaaa")
            val startA = async { runCatching { dilationA.start() } }
            sb.sendDilation(
                buildJsonObject {
                    put("type", "please")
                    put("side", "bbbbbbbbbbbbbbbb")
                    put("use-version", "not-ged")
                },
            )

            assertFailsWith<WormholeProtocolException> { startA.await().getOrThrow() }
            a.shutdown()
            b.shutdown()
        }
}
