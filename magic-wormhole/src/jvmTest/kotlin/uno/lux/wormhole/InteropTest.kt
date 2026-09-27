package uno.lux.wormhole

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import org.junit.Assume.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Talks to the real `wormhole` CLI (magic-wormhole) through the public relay.
 * Runs only with WORMHOLE_INTEROP=1.
 */
class InteropTest {
    @BeforeTest
    fun onlyWhenRequested() {
        assumeTrue("Set WORMHOLE_INTEROP=1 to run interop tests", System.getenv("WORMHOLE_INTEROP") == "1")
    }

    private fun cli(vararg args: String): Process =
        ProcessBuilder(listOf("wormhole") + args).redirectErrorStream(false).start()

    @Test
    fun kotlinSendsTextToTheCli() =
        runBlocking {
            withTimeout(60.seconds) {
                val code = CompletableDeferred<String>()
                val sender =
                    async {
                        Wormhole().sendText("hello from kotlin").collect {
                            if (it is SendEvent.CodeAllocated) code.complete(it.code)
                        }
                    }
                val output =
                    withContext(Dispatchers.IO) {
                        val p = cli("receive", "--hide-progress", code.await())
                        val out = p.inputStream.bufferedReader().readText()
                        val err = p.errorStream.bufferedReader().readText()
                        p.waitFor(30, TimeUnit.SECONDS)
                        assertEquals(0, p.exitValue(), err)
                        out
                    }
                sender.await()
                assertEquals("hello from kotlin", output.trim())
            }
        }

    @Test
    fun cliSendsTextToKotlin() =
        runBlocking {
            withTimeout(60.seconds) {
                val p = withContext(Dispatchers.IO) { cli("send", "--hide-progress", "--text", "hello from python") }
                val code =
                    withContext(Dispatchers.IO) {
                        val err = p.errorStream.bufferedReader()
                        generateSequence { err.readLine() }
                            .first { it.startsWith("Wormhole code is:") }
                            .substringAfter(":")
                            .trim()
                    }
                val events = Wormhole().receive(code).toList()
                assertEquals(listOf<ReceiveEvent>(ReceiveEvent.TextReceived("hello from python")), events)
                withContext(Dispatchers.IO) { p.waitFor(30, TimeUnit.SECONDS) }
                assertEquals(0, p.exitValue())
            }
        }

    private fun randomFile(size: Int): java.io.File {
        val file =
            kotlin.io.path
                .createTempFile("wormhole-interop", ".bin")
                .toFile()
        file.writeBytes(ByteArray(size).also { java.util.Random(42).nextBytes(it) })
        file.deleteOnExit()
        return file
    }

    @Test
    fun kotlinSendsFileToTheCli() =
        runBlocking {
            withTimeout(2.minutes) {
                val file = randomFile(3_000_000)
                val target =
                    kotlin.io.path
                        .createTempDirectory("wormhole-out")
                        .toFile()
                val out = java.io.File(target, "received.bin")
                val code = CompletableDeferred<String>()
                val sender =
                    async {
                        Wormhole()
                            .sendFile(
                                "data.bin",
                                file.length(),
                                kotlinx.io.files.SystemFileSystem
                                    .source(kotlinx.io.files.Path(file.path)),
                            ).collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
                    }
                withContext(Dispatchers.IO) {
                    val p = cli("receive", "--hide-progress", "--accept-file", "--output-file", out.path, code.await())
                    val err = p.errorStream.bufferedReader().readText()
                    p.waitFor(90, TimeUnit.SECONDS)
                    assertEquals(0, p.exitValue(), err)
                }
                sender.await()
                kotlin.test.assertContentEquals(file.readBytes(), out.readBytes())
            }
        }

    @Test
    fun cliSendsFileToKotlin() =
        runBlocking {
            withTimeout(2.minutes) {
                val file = randomFile(2_000_000)
                val p = withContext(Dispatchers.IO) { cli("send", "--hide-progress", file.path) }
                val code =
                    withContext(Dispatchers.IO) {
                        val err = p.errorStream.bufferedReader()
                        generateSequence { err.readLine() }
                            .first { it.startsWith("Wormhole code is:") }
                            .substringAfter(":")
                            .trim()
                    }
                val sink = kotlinx.io.Buffer()
                val events = mutableListOf<ReceiveEvent>()
                Wormhole().receive(code).collect { e ->
                    events += e
                    if (e is ReceiveEvent.FileOffered) e.accept(sink)
                }
                val offer = events.first() as ReceiveEvent.FileOffered
                assertEquals(file.name, offer.name)
                assertEquals(ReceiveEvent.FileReceived(path = null), events.last())
                kotlin.test.assertContentEquals(file.readBytes(), sink.readByteArray())
                withContext(Dispatchers.IO) { p.waitFor(30, TimeUnit.SECONDS) }
                assertEquals(0, p.exitValue())
            }
        }

    @Test
    fun kotlinSendsDirectoryToTheCli() =
        runBlocking {
            withTimeout(2.minutes) {
                val files =
                    mapOf(
                        "a.txt" to "hello".encodeToByteArray(),
                        "sub/b.bin" to ByteArray(500_000).also { java.util.Random(3).nextBytes(it) },
                        "sub/deeper/empty" to ByteArray(0),
                    )
                val target =
                    kotlin.io.path
                        .createTempDirectory("wormhole-out")
                        .toFile()
                val out = java.io.File(target, "received")
                val code = CompletableDeferred<String>()
                val sender =
                    async {
                        Wormhole()
                            .sendDirectory(
                                "holiday",
                                files.map { (path, data) ->
                                    DirectoryEntry(
                                        path,
                                        data.size.toLong(),
                                    ) { kotlinx.io.Buffer().apply { write(data) } }
                                },
                            ).collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
                    }
                withContext(Dispatchers.IO) {
                    val p = cli("receive", "--hide-progress", "--accept-file", "--output-file", out.path, code.await())
                    val err = p.errorStream.bufferedReader().readText()
                    p.waitFor(90, TimeUnit.SECONDS)
                    assertEquals(0, p.exitValue(), err)
                }
                sender.await()
                files.forEach { (path, data) ->
                    kotlin.test.assertContentEquals(data, java.io.File(out, path).readBytes(), path)
                }
            }
        }

    @Test
    fun cliSendsDirectoryToKotlin() =
        runBlocking {
            withTimeout(2.minutes) {
                val dir =
                    kotlin.io.path
                        .createTempDirectory("wormhole-dir")
                        .toFile()
                val files =
                    mapOf(
                        "a.txt" to "hello ".repeat(1000).encodeToByteArray(),
                        "sub/b.bin" to ByteArray(700_000).also { java.util.Random(5).nextBytes(it) },
                    )
                files.forEach { (path, data) ->
                    java.io
                        .File(dir, path)
                        .apply { parentFile.mkdirs() }
                        .writeBytes(data)
                }
                val p = withContext(Dispatchers.IO) { cli("send", "--hide-progress", dir.path) }
                val code =
                    withContext(Dispatchers.IO) {
                        val err = p.errorStream.bufferedReader()
                        generateSequence { err.readLine() }
                            .first { it.startsWith("Wormhole code is:") }
                            .substringAfter(":")
                            .trim()
                    }
                val sink = kotlinx.io.Buffer()
                var offer: ReceiveEvent.FileOffered? = null
                Wormhole().receive(code).collect { e ->
                    if (e is ReceiveEvent.FileOffered) {
                        offer = e
                        e.accept(sink)
                    }
                }
                val o = offer!!
                assertEquals(true, o.isDirectory)
                assertEquals(2, o.fileCount)
                val unpacked = mutableMapOf<String, kotlinx.io.Buffer>()
                val zip = sink.readByteArray()
                unzip(
                    zip.size.toLong(),
                    { offset -> kotlinx.io.Buffer().apply { write(zip, offset.toInt(), zip.size) } },
                    object : UnzipTarget {
                        override fun createDirectory(path: String) = Unit

                        override fun createFile(path: String) = kotlinx.io.Buffer().also { unpacked[path] = it }
                    },
                    maxBytes = o.unpackedSize!!,
                    maxFiles = o.fileCount!!,
                )
                assertEquals(files.keys, unpacked.keys)
                files.forEach { (path, data) ->
                    kotlin.test.assertContentEquals(data, unpacked.getValue(path).readByteArray())
                }
                withContext(Dispatchers.IO) { p.waitFor(30, TimeUnit.SECONDS) }
                assertEquals(0, p.exitValue())
            }
        }

    /** A real TCP network that neither listens nor connects directly, so only the relay works. */
    private fun relayOnlyNetwork(): uno.lux.wormhole.transit.TransitNetwork {
        val tcp =
            uno.lux.wormhole.transit
                .TcpTransitNetwork()
        return object : uno.lux.wormhole.transit.TransitNetwork by tcp {
            override suspend fun listen(): uno.lux.wormhole.transit.TransitListener? = null

            override suspend fun connect(
                host: String,
                port: Int,
            ): uno.lux.wormhole.transit.TransitSocket {
                require(host == "transit.magic-wormhole.io") { "direct connections disabled" }
                return tcp.connect(host, port)
            }
        }
    }

    @Test
    fun fileThroughThePublicTransitRelay() =
        runBlocking {
            withTimeout(2.minutes) {
                val data = ByteArray(1_000_000).also { java.util.Random(7).nextBytes(it) }
                val code = CompletableDeferred<String>()
                val sender =
                    async {
                        Wormhole(WormholeConfig(), uno.lux.wormhole.rendezvous.WebSocketTransport, ::relayOnlyNetwork)
                            .sendFile("r.bin", data.size.toLong(), kotlinx.io.Buffer().apply { write(data) })
                            .collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
                    }
                val sink = kotlinx.io.Buffer()
                Wormhole(WormholeConfig(), uno.lux.wormhole.rendezvous.WebSocketTransport, ::relayOnlyNetwork)
                    .receive(code.await())
                    .collect { if (it is ReceiveEvent.FileOffered) it.accept(sink) }
                sender.await()
                kotlin.test.assertContentEquals(data, sink.readByteArray())
            }
        }

    @Test
    fun kotlinToKotlinOverThePublicRelay() =
        runBlocking {
            withTimeout(60.seconds) {
                val code = CompletableDeferred<String>()
                val sender =
                    async {
                        Wormhole()
                            .sendText(
                                "round trip",
                            ).collect { if (it is SendEvent.CodeAllocated) code.complete(it.code) }
                    }
                val events = Wormhole().receive(code.await()).toList()
                sender.await()
                assertEquals(listOf<ReceiveEvent>(ReceiveEvent.TextReceived("round trip")), events)
            }
        }
}
