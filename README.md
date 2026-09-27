# magic-wormhole-kotlin

A Kotlin Multiplatform implementation of the [Magic Wormhole](https://magic-wormhole.readthedocs.io/)
protocol. Send text and files between devices using a short code like `7-guitarist-revenge`.

- Pure Kotlin: no native libraries.
- Targets: Windows, macOS, Linux, Android, iOS
- Coroutines-first API.
- Compatible with the Python `wormhole` CLI.

## Usage

Every operation returns a cold `Flow`. The transfer runs while you collect it; cancel the
collecting coroutine to cancel the transfer. Errors are `WormholeException` subclasses
(`WrongCodeException`, `TransferRejectedException`, `ServerConnectionException`, ...).

```kotlin
val wormhole = Wormhole() // the defaults work with the `wormhole` CLI

// Send text
wormhole.sendText("hello").collect { event ->
    when (event) {
        is SendEvent.CodeAllocated -> println("Code: ${event.code}")
        is SendEvent.Progress -> Unit
        SendEvent.Completed -> println("Sent")
    }
}

// Send a file or a whole folder
wormhole.sendFile(Path("photo.jpg")).collect { println(it) }
wormhole.sendDirectory(Path("holiday")).collect { println(it) }

// Receive
wormhole.receive("7-guitarist-revenge").collect { event ->
    when (event) {
        is ReceiveEvent.TextReceived -> println(event.text)
        // Saves Downloads/photo.jpg, or unpacks a folder into Downloads/holiday. Or call event.reject().
        is ReceiveEvent.FileOffered -> event.acceptInto(Path("Downloads"))
        is ReceiveEvent.Progress -> println("${event.receivedBytes} / ${event.totalBytes}")
        is ReceiveEvent.FileReceived -> println("Saved to ${event.path}")
    }
}
```

What is supported: text, single files, directories and several files at once (sent as a zip), direct TCP connections and the transit relay.

`acceptInto` keeps only the last part of the offered name, so a sender cannot write outside the
folder, and it never overwrites: if the file or folder exists, the transfer is rejected.

### Data that is not a file

When the data does not come from a `Path` (for example an Android `content://` URI), use the
stream versions:

```kotlin
// Send `size` bytes from a source. The library closes it.
wormhole.sendFile("photo.jpg", size, source)

// Send several files as a folder. `open` is called twice (checksum pass, then send).
wormhole.sendDirectory("holiday", listOf(DirectoryEntry("beach.jpg", size) { openBeach() }))

// Receive into any sink. A directory then arrives as `<name>.zip` with isDirectory = true.
event.accept(sink)

// Unpack a received zip safely, from a file or through your own UnzipTarget.
unzip(zipPath, Path("Downloads", "holiday"), maxBytes = offer.unpackedSize!!, maxFiles = offer.fileCount!!)
```

`sendDirectory` writes an uncompressed (stored) zip whose size is known before sending, so no
temporary file is needed. `unzip` reads stored and deflated zips (including Zip64 and the
streamed zips of the `wormhole` CLI), rejects paths that leave the target folder, checks every
CRC, and stops when the zip holds more than the announced bytes or files.

## Installation

Using Gradle:

```kotlin
dependencies {
    implementation("io.github.lux-uno:magic-wormhole:<version>")
}
```

For local development, include this repository as a composite build:

```kotlin
// settings.gradle.kts of your app
includeBuild("../magic-wormhole-kotlin")
```

## Building and testing

```bash
./gradlew jvmTest
```

Interop tests against the real `wormhole` CLI and the public relay are skipped by default. Run them
with `WORMHOLE_INTEROP=1 ./gradlew jvmTest` (needs `wormhole` on `PATH` and internet access).

## License

MIT. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
