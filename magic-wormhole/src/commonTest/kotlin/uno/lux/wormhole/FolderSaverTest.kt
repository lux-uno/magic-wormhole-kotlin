package uno.lux.wormhole

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderSaverTest {
    private val dir = Path(SystemTemporaryDirectory, "received-${Random.nextLong().toULong()}")
    private val saver = FileSaver.folder(dir)

    private suspend fun receive(
        name: String,
        content: String,
    ): SavedFile {
        val file = saver.createFile(name, content.length.toLong())
        val buffer = Buffer().apply { write(content.encodeToByteArray()) }
        file.sink.write(buffer, buffer.size)
        file.sink.close()
        return file.commit()
    }

    private fun read(path: String) = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

    @Test
    fun committedFileHasItsNameAndContent() =
        runTest {
            val saved = receive("notes.txt", "hello")
            assertEquals("notes.txt", saved.name)
            assertEquals(Path(dir, "notes.txt").toString(), saved.location)
            assertEquals("hello", read(saved.location))
        }

    @Test
    fun existingFilesAreNotOverwritten() =
        runTest {
            receive("notes.txt", "first")
            val second = receive("notes.txt", "second")
            val third = receive("notes.txt", "third")
            assertEquals("notes (1).txt", second.name)
            assertEquals("notes (2).txt", third.name)
            assertEquals("first", read(Path(dir, "notes.txt").toString()))
        }

    @Test
    fun namesWithoutExtensionGetANumberAtTheEnd() =
        runTest {
            receive("README", "a")
            assertEquals("README (1)", receive("README", "b").name)
        }

    @Test
    fun discardedFileLeavesNothingBehind() =
        runTest {
            val file = saver.createFile("big.bin", 10)
            file.sink.close()
            file.discard()
            assertFalse(SystemFileSystem.list(dir).any())
        }

    private fun IncomingFolder.write(
        path: String,
        content: String,
    ) = createFile(
        path,
    ).use { it.write(Buffer().apply { write(content.encodeToByteArray()) }, content.length.toLong()) }

    @Test
    fun committedFolderHasItsFilesAndAUniqueName() =
        runTest {
            val first = saver.createFolder("holiday")
            first.write("a.txt", "A")
            first.write("day 2/b.txt", "B")
            first.createDirectory("empty/inner")
            assertEquals(Path(dir, "holiday").toString(), first.commit().location)
            assertEquals("A", read(Path(dir, "holiday", "a.txt").toString()))
            assertEquals("B", read(Path(dir, "holiday", "day 2", "b.txt").toString()))
            assertTrue(SystemFileSystem.metadataOrNull(Path(dir, "holiday", "empty", "inner"))!!.isDirectory)

            val second = saver.createFolder("holiday")
            second.write("a.txt", "A2")
            val saved = second.commit()
            assertEquals("holiday (1)", saved.name)
            assertEquals("A2", read(Path(saved.location, "a.txt").toString()))
        }

    @Test
    fun discardedFolderLeavesNothingBehind() =
        runTest {
            val folder = saver.createFolder("holiday")
            folder.write("a/b/c.txt", "x")
            folder.discard()
            assertEquals(emptyList(), SystemFileSystem.list(dir))
        }

    @Test
    fun partsOfFolderPathsAreMadeSafe() =
        runTest {
            val folder = saver.createFolder("what_")
            folder.write("in*valid/.hidden", "x")
            val saved = folder.commit()
            assertEquals("x", read(Path(saved.location, "in_valid", ".hidden").toString()))
        }

    @Test
    fun offeredNamesAreMadeSafe() {
        assertEquals("evil.txt", safeFileName("../../evil.txt"))
        assertEquals("evil.txt", safeFileName("..\\..\\evil.txt"))
        assertEquals("what_", safeFileName("what?"))
        assertEquals("a_b", safeFileName("a:b"))
        assertEquals("hidden", safeFileName(".hidden"))
        for (empty in listOf("", ".", "..", "dir/")) assertEquals("received-file", safeFileName(empty), empty)
    }
}
