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
val wormhole = Wormhole(WormholeConfig()) // defaults work with the `wormhole` CLI

// Send text
wormhole.sendText("hello").collect { event ->
    when (event) {
        is SendEvent.CodeAllocated -> println("Code: ${event.code}")
        is SendEvent.Progress -> Unit
        SendEvent.Completed -> println("Sent")
    }
}

// Send a file (the library closes the source)
val path = Path("photo.jpg")
wormhole.sendFile("photo.jpg", SystemFileSystem.metadataOrNull(path)!!.size, SystemFileSystem.source(path))
    .collect { println(it) }

// Receive
wormhole.receive("7-guitarist-revenge").collect { event ->
    when (event) {
        is ReceiveEvent.TextReceived -> println(event.text)
        is ReceiveEvent.FileOffered -> event.accept(SystemFileSystem.sink(Path(event.name))) // or event.reject()
        is ReceiveEvent.Progress -> println("${event.receivedBytes} / ${event.totalBytes}")
        ReceiveEvent.FileReceived -> println("Done")
    }
}
```

What is supported: text, single files, directories and several files at once (sent as a zip), direct TCP connections and the transit relay.

### Directories and several files

```kotlin
// Send: entries are relative paths; `open` is called twice (checksum pass, then send).
wormhole.sendDirectory(
    "holiday",
    listOf(DirectoryEntry("beach.jpg", size) { SystemFileSystem.source(Path("beach.jpg")) }),
).collect { println(it) }

// Receive: a directory arrives as `<name>.zip` with isDirectory = true.
// Save it (for example to a temporary file), then unpack it safely:
unzip(
    size = zipSize,
    open = { offset -> SystemFileSystem.source(zipPath).buffered().apply { skip(offset) } },
    target = myTarget, // an UnzipTarget that creates folders and files
    maxBytes = offer.unpackedSize ?: Long.MAX_VALUE,
    maxFiles = offer.fileCount ?: Int.MAX_VALUE,
)
```

`sendDirectory` writes an uncompressed (stored) zip whose size is known before sending, so no
temporary file is needed. `unzip` reads stored and deflated zips (including Zip64 and the
streamed zips of the `wormhole` CLI), rejects paths that leave the target folder, checks every
CRC, and stops when the zip holds more than the announced bytes or files.

## Installation

Using Gradle:

```kotlin
dependencies {
    implementation("io.github.lux-uno:wormhole:<version>")
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
