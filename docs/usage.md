# Using magic-wormhole-kotlin

Task-based examples. For installation, see the [README](../README.md).

- [Basics](#basics)
- [Send text](#send-text)
- [Send a file](#send-a-file)
- [Send a folder or several files](#send-a-folder-or-several-files)
- [Send a file on Android](#send-a-file-on-android)
- [Receive on desktop](#receive-on-desktop)
- [Ask the user before accepting](#ask-the-user-before-accepting)
- [Receive on Android with a custom `FileSaver`](#receive-on-android-with-a-custom-filesaver)
- [Receive into a sink](#receive-into-a-sink)
- [Choose a `FolderUnpacker`](#choose-a-folderunpacker)
- [Use other servers](#use-other-servers)
- [Cancel a transfer](#cancel-a-transfer)
- [Handle errors](#handle-errors)

## Basics

```kotlin
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import uno.lux.wormhole.*

val wormhole = Wormhole() // the defaults work with the `wormhole` CLI
```

Every function returns a cold `Flow` of events. The transfer starts when you collect the flow
and stops when you stop collecting it. Failures are thrown from `collect` as
[`WormholeException`](#handle-errors) subclasses.

## Send text

```kotlin
wormhole.sendText("hello").collect { event ->
    when (event) {
        is SendEvent.CodeAllocated -> println("Give the receiver this code: ${event.code}")
        SendEvent.Completed -> println("Delivered")
    }
}
```

## Send a file

```kotlin
wormhole.sendFile(Path("photo.jpg")).collect { event ->
    when (event) {
        is SendEvent.CodeAllocated -> println("Code: ${event.code}")
        is SendEvent.Progress -> println("${event.sentBytes} / ${event.totalBytes} bytes")
        SendEvent.Completed -> println("The receiver has the file")
    }
}
```

To send it under another name: `sendFile(Path("IMG_0001.jpg"), name = "beach.jpg")`.

## Send a folder or several files

```kotlin
// A folder with all its files and subfolders. Empty folders are not sent.
wormhole.sendDirectory(Path("holiday")).collect { println(it) }
```

To send several files that are not in one folder, give each one a path inside the folder that the
receiver gets:

```kotlin
val files = listOf(
    OutgoingFile(Path("/photos/IMG_1.jpg"), name = "beach.jpg"),
    OutgoingFile(Path("/photos/IMG_2.jpg"), name = "day 2/sea.jpg"),
)

wormhole.sendDirectory("holiday", files).collect { println(it) }
```

A folder is sent as a zip, the same way the `wormhole` CLI sends one. Each file is read twice
(once for its checksum, once to send it), so `open` must return the same bytes each time.

## Send a file on Android

On Android, picked files are `content://` URIs, not paths. Wrap them in an `OutgoingFile`.

This helper is not part of the library; copy it into your project:

```kotlin
fun Context.outgoingFile(uri: Uri): OutgoingFile {
    val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    val (name, size) = contentResolver.query(uri, columns, null, null, null)!!
        .use { it.moveToFirst(); it.getString(0) to it.getLong(1) }
    
    return OutgoingFile(name, size) { contentResolver.openInputStream(uri)!!.asSource() }
}
```

Then use it like this:

```kotlin
wormhole.sendFile(context.outgoingFile(uri)).collect { println(it) }
```

`open` is called only when the transfer runs, and the library closes the stream.

## Receive data

```kotlin
wormhole.receive("7-guitarist-revenge").collect { event ->
    when (event) {
        is ReceiveEvent.TextReceived ->
            println(event.text)
        is ReceiveEvent.FileOffered ->
            event.acceptInto(Path(System.getProperty("user.home"), "Downloads"))
        is ReceiveEvent.Progress ->
            println("${event.receivedBytes} / ${event.totalBytes} bytes")
        ReceiveEvent.Unpacking ->
            println("Unpacking the folder")
        is ReceiveEvent.FileReceived ->
            println("Saved to ${event.saved?.location}")
    }
}
```

`acceptInto(folder)` saves a file as `folder/<name>` and unpacks a folder into `folder/<name>`.
It is safe with names from the sender:

- Only the last part of the offered name is used: `../../evil.txt` is saved as `evil.txt`.
- It never overwrites. When the name is taken, it saves `photo (1).jpg`.
- Data goes to a hidden `.part` file first, so a failed transfer leaves nothing behind.

## Ask the user before accepting

The flow waits at `FileOffered` until you call `acceptInto`, `accept` or `reject`. You can call
them later, for example from a button:

```kotlin
wormhole.receive(code).collect { event ->
    when (event) {
        is ReceiveEvent.FileOffered -> {
            // name, size, isDirectory, fileCount and unpackedSize describe the offer.
            uiState.value = UiState.Offered(event.name, event.size, event.isDirectory)
            pendingOffer = event // call pendingOffer.acceptInto(...) or reject() from the UI
        }
        // ...
    }
}
```

After `reject()`, the sender gets a `TransferRejectedException` and the flow completes.

## Receive on Android with a custom FileSaver

**On Android 8 and 9**, use:

```kotlin
FileSaver.folder(Path(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!.path))
```

**On Android 10+** apps save to the shared Downloads collection through MediaStore, which has no `Path`.
You'll have to implement a `FileSaver`.

The library still does the rest: safe names, unpacking folders, and calling `discard()` when a transfer fails or is cancelled.

```kotlin
/** Saves into Downloads through MediaStore (Android 10 and later). */
class DownloadsSaver(context: Context) : FileSaver {
    private val resolver = context.contentResolver

    override suspend fun createFile(name: String, size: Long): IncomingFile =
        withContext(Dispatchers.IO) {
            val uri = insert(name, Environment.DIRECTORY_DOWNLOADS)

            object : IncomingFile {
                override val sink = resolver.openOutputStream(uri)!!.asSink()

                override suspend fun commit(): SavedFile {
                    publish(uri)
                    return SavedFile(name, uri.toString()) // MediaStore may have renamed it; read DISPLAY_NAME to show the real name
                }

                override suspend fun discard() = delete(uri)
            }
        }

    override suspend fun createFolder(name: String): IncomingFolder {
        val uris = mutableListOf<Uri>()

        return object : IncomingFolder {
            // MediaStore has no empty folders; they are created with their files.
            override fun createDirectory(path: String) = Unit

            override fun createFile(path: String): RawSink {
                val folder = listOf(Environment.DIRECTORY_DOWNLOADS, name) + path.split('/').dropLast(1)
                val uri = insert(path.substringAfterLast('/'), folder.joinToString("/")).also { uris += it }
                return resolver.openOutputStream(uri)!!.asSink()
            }

            override suspend fun commit(): SavedFile {
                uris.forEach { publish(it) }
                return SavedFile(name, "${Environment.DIRECTORY_DOWNLOADS}/$name")
            }

            override suspend fun discard() = uris.forEach { delete(it) }
        }
    }

    /** A pending entry, hidden from other apps until [publish]. */
    private fun insert(name: String, relativePath: String): Uri =
        resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "$relativePath/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: throw IOException("Could not create $name in Downloads")

    private suspend fun publish(uri: Uri) =
        withContext(Dispatchers.IO) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }

    private suspend fun delete(uri: Uri) {
        withContext(Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) } }
    }
}

// In the receive flow:
is ReceiveEvent.FileOffered -> event.acceptInto(DownloadsSaver(context))
```

What the library promises a `FileSaver`:

- Names are safe: one path part, without `/`, `\`, `:` or control characters, and never `.` or `..`.
- Paths in `IncomingFolder.createFile` are relative, use `/`, and never contain `..`.
- The library closes every sink. Then it calls `commit()` if everything arrived and was
  checked, or `discard()` otherwise.
- Errors thrown by the saver reach you as `SaveFailedException`.

To keep a received folder as a zip instead of unpacking it: `event.acceptInto(saver, unpack = false)`.
It is then saved as the file `<name>.zip`.

## Receive into a sink

`accept(sink)` writes the received bytes to any `RawSink`, for example a `Buffer`. The library
closes the sink when the transfer ends. A folder then arrives as `<name>.zip`
(`event.isDirectory` is true). Unpack it safely with `unzip`, using the offer's numbers as limits:

```kotlin
unzip(
    zip = Path("holiday.zip"),
    folder = Path("Downloads", "holiday"),
    maxBytes = offer.unpackedSize ?: Long.MAX_VALUE,
    maxFiles = offer.fileCount ?: Int.MAX_VALUE,
)
```

`sendDirectory` writes an uncompressed (stored) zip whose size is known before sending, so no
temporary file is needed. `unzip` reads stored and deflated zips (including Zip64 and the
streamed zips of the `wormhole` CLI), rejects paths that leave the target folder, checks every
CRC, and stops when the zip holds more than the announced bytes or files.

## Choose a `FolderUnpacker`

A received folder is a zip. The `FolderUnpacker` in `WormholeConfig` decides how it is unpacked:

| Unpacker | How | Disk space | After the last byte |
|---|---|---|---|
| `FolderUnpacker.streaming()` (default) | Unpacks while the data arrives | The files only | Done |
| `FolderUnpacker.temporaryFile()` | Saves the zip, then unpacks it | The files plus the zip | `Unpacking` event, then done |

```kotlin
val wormhole = Wormhole(WormholeConfig(folderUnpacker = FolderUnpacker.temporaryFile()))

// On Android, keep the zip in the app's cache folder:
FolderUnpacker.temporaryFile(Path(context.cacheDir.path))
```

Both check paths, sizes, file counts and checksums the same way. Use `temporaryFile()` if a
sender's zip fails with `InvalidZipException` only when streaming. While a folder streams in, one
thread of `Dispatchers.IO` waits for its data.

You can also implement `FolderUnpacker` yourself: `start` gets the target folder and returns a
`ZipReceiver` that gets the zip's bytes in order, then `finish` or `discard`.

## Use other servers

```kotlin
val config =
    WormholeConfig(
        rendezvousUrl = "wss://my-mailbox.example.com/v1",
        transitRelay = "tcp:my-relay.example.com:4001", // or null for direct connections only
        codeLength = 3, // words after the number: 7-guitarist-revenge-sandwich
    )
val wormhole = Wormhole(config)
```

Both sides must use the same rendezvous server and `appId`.

## Cancel a transfer

Cancel the coroutine that collects the flow:

```kotlin
val job = scope.launch {
    wormhole.receive(code).collect { /* ... */ }
}
// later
job.cancel()
```

With `acceptInto`, partly received files and folders are discarded. With `accept(sink)`, the
library closes your sink.

## Handle errors

```kotlin
try {
    wormhole.receive(code).collect { /* ... */ }
} catch (e: WrongCodeException) {
    showError("The code is wrong. Check it and try again.")
} catch (e: SaveFailedException) {
    showError("Could not save the file: ${e.message}")
} catch (e: WormholeException) {
    showError("The transfer failed: ${e.message}")
}
```

| Exception | When |
|---|---|
| `InvalidCodeException` | The code is not `<number>-<word>-<word>...`. |
| `WrongCodeException` | The two sides typed different codes. |
| `TransferRejectedException` | The receiver rejected the file (thrown on the sender). |
| `ServerConnectionException` | The rendezvous server cannot be reached, or the connection was lost. |
| `WormholeServerException` | The server reported an error, for example `crowded`. |
| `TransitException` | The data connection (direct or relay) failed. |
| `PeerErrorException` | The other side stopped with an error. |
| `WormholeProtocolException` | The other side sent something the protocol does not allow. |
| `SaveFailedException` | A received file could not be stored, for example the disk is full. |
| `InvalidZipException` | A received folder is damaged, unsafe, or larger than announced. |

All of them extend `WormholeException`. Two errors come from kotlinx-io or the arguments instead:

- `sendFile(path)` throws `kotlinx.io.files.FileNotFoundException` from `collect` when there is
  no file at `path`. `sendDirectory(path)` throws `IllegalArgumentException` from `collect` when
  `path` is not a folder.
- `sendDirectory(name, files)` throws `IllegalArgumentException` right away for an invalid folder
  name, an unsafe path, or two files with the same path.
