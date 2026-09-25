package uno.lux.wormhole.transit

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import uno.lux.wormhole.TransitException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransitTest {
    private val key = TransitVectors.KEY

    @Test
    fun handshakesMatchMagicWormhole() {
        assertEquals(TransitVectors.SENDER_HANDSHAKE, Handshakes.sender(key).decodeToString())
        assertEquals(TransitVectors.RECEIVER_HANDSHAKE, Handshakes.receiver(key).decodeToString())
        assertEquals(TransitVectors.RELAY_HANDSHAKE, Handshakes.relay(key, TransitVectors.RELAY_SIDE).decodeToString())
        assertEquals(TransitVectors.SENDER_RECORD_KEY, Handshakes.senderRecordKey(key).toHexString())
        assertEquals(TransitVectors.RECEIVER_RECORD_KEY, Handshakes.receiverRecordKey(key).toHexString())
    }

    @Test
    fun recordsAreFramedAndEncryptedLikeMagicWormhole() =
        runTest {
            val wire = ByteChannel(autoFlush = true)
            val back = ByteChannel(autoFlush = true)
            val sender =
                RecordPipe(
                    FakeInternet.FakeSocket(back, wire),
                    Handshakes.senderRecordKey(key),
                    Handshakes.receiverRecordKey(key),
                )
            sender.send("hello".encodeToByteArray())
            sender.send("world".encodeToByteArray())
            val expected = TransitVectors.SENDER_RECORDS.hexToByteArray()
            val bytes = wire.readByteArray(expected.size)
            assertContentEquals(expected, bytes)
        }

    @Test
    fun receiverDecryptsRecordsInOrder() =
        runTest {
            val wire = ByteChannel(autoFlush = true)
            wire.writeFullyAndFlush(TransitVectors.SENDER_RECORDS.hexToByteArray())
            val receiver =
                RecordPipe(
                    FakeInternet.FakeSocket(wire, ByteChannel()),
                    Handshakes.receiverRecordKey(key),
                    Handshakes.senderRecordKey(key),
                )
            assertEquals("hello", receiver.receive().decodeToString())
            assertEquals("world", receiver.receive().decodeToString())
        }

    @Test
    fun receiverRejectsOutOfOrderRecords() =
        runTest {
            val records = TransitVectors.SENDER_RECORDS.hexToByteArray()
            val firstLength = 4 + 24 + 16 + 5
            val wire = ByteChannel(autoFlush = true)
            wire.writeFullyAndFlush(records.copyOfRange(firstLength, records.size)) // only the nonce-1 record
            val receiver =
                RecordPipe(
                    FakeInternet.FakeSocket(wire, ByteChannel()),
                    Handshakes.receiverRecordKey(key),
                    Handshakes.senderRecordKey(key),
                )
            assertFailsWith<TransitException> { receiver.receive() }
        }

    @Test
    fun transitMessageHasAbilitiesAndHints() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val transit = Transit(Transit.Role.SENDER, key, internet.network(listOf("10.0.0.1")), FakeInternet.RELAY)
            val message = transit.start()
            val expected =
                Json
                    .parseToJsonElement(
                        """{"abilities-v1":[{"type":"direct-tcp-v1"},{"type":"relay-v1"}],
               "hints-v1":[{"type":"direct-tcp-v1","priority":0.0,"hostname":"10.0.0.1","port":40000},
                           {"type":"relay-v1","hints":[{"type":"direct-tcp-v1","priority":0.0,"hostname":"relay.test","port":4001}]}]}""",
                    ).jsonObject
            assertEquals(expected, message)
            transit.close()
        }

    @Test
    fun parsesHintsFromOtherClients() {
        val hints =
            TransitHints.parse(
                Json
                    .parseToJsonElement(
                        """{"abilities-v1":[{"type":"direct-tcp-v1"},{"type":"relay-v1"}],
                   "hints-v1":[{"type":"direct-tcp-v1","priority":0,"hostname":"192.168.1.5","port":1234},
                               {"type":"tor-tcp-v1","hostname":"x.onion","port":80},
                               {"type":"relay-v1","hints":[{"type":"direct-tcp-v1","hostname":"relay.example","port":4001}]}]}""",
                    ).jsonObject,
            )
        assertEquals(listOf(DirectHint("192.168.1.5", 1234)), hints.direct)
        assertEquals(listOf(DirectHint("relay.example", 4001)), hints.relays)
    }

    @Test
    fun parsesRelayUrls() {
        assertEquals(
            DirectHint("transit.magic-wormhole.io", 4001),
            DirectHint.parseRelayUrl("tcp:transit.magic-wormhole.io:4001"),
        )
        assertFailsWith<IllegalArgumentException> { DirectHint.parseRelayUrl("transit.magic-wormhole.io") }
    }

    private suspend fun connectPair(
        internet: FakeInternet,
        senderCanListen: Boolean,
        receiverCanListen: Boolean,
    ): Pair<RecordPipe, RecordPipe> {
        val sender =
            Transit(Transit.Role.SENDER, key, internet.network(listOf("10.0.0.1"), senderCanListen), FakeInternet.RELAY)
        val receiver =
            Transit(
                Transit.Role.RECEIVER,
                key,
                internet.network(listOf("10.0.0.2"), receiverCanListen),
                FakeInternet.RELAY,
            )
        val senderHints = TransitHints.parse(sender.start())
        val receiverHints = TransitHints.parse(receiver.start())
        return kotlinx.coroutines.coroutineScope {
            val s = async { sender.connect(receiverHints) }
            val r = async { receiver.connect(senderHints) }
            s.await() to r.await()
        }
    }

    @Test
    fun directConnectionCarriesRecordsBothWays() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val (s, r) = connectPair(internet, senderCanListen = true, receiverCanListen = true)
            s.send("file data".encodeToByteArray())
            assertEquals("file data", r.receive().decodeToString())
            r.send("ack".encodeToByteArray())
            assertEquals("ack", s.receive().decodeToString())
            assertTrue(internet.connectAttempts.none { it.startsWith(FakeInternet.RELAY_HOST) })
            s.close()
            r.close()
        }

    @Test
    fun relayIsUsedWhenNobodyCanListen() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val (s, r) = connectPair(internet, senderCanListen = false, receiverCanListen = false)
            s.send("via relay".encodeToByteArray())
            assertEquals("via relay", r.receive().decodeToString())
            assertTrue(internet.connectAttempts.all { it.startsWith(FakeInternet.RELAY_HOST) })
            s.close()
            r.close()
        }

    @Test
    fun failsWhenNoConnectionIsPossible() =
        runTest {
            val internet = FakeInternet(backgroundScope)
            val sender =
                Transit(Transit.Role.SENDER, key, internet.network(emptyList(), canListen = false), relay = null)
            sender.start()
            assertFailsWith<TransitException> {
                sender.connect(TransitHints(direct = listOf(DirectHint("10.9.9.9", 1)), relays = emptyList()))
            }
        }
}

private suspend fun ByteChannel.writeFullyAndFlush(bytes: ByteArray) {
    writeFully(bytes)
    flush()
}
