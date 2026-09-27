# magic-wormhole-kotlin

A Kotlin Multiplatform implementation of the [Magic Wormhole](https://magic-wormhole.readthedocs.io/)
protocol. Send text and files between devices using a short code like `7-guitarist-revenge`.

## Features

- Pure Kotlin: no native libraries, coroutines-first API.
- Targets: Windows, macOS, Linux, Android, iOS
- Compatible with the Python `wormhole` CLI.

**What is supported:**
- Send text, multiple files, or a directory
- Direct TCP connections and the transit relay
- Zipped folders unpack while they download, using half the disk space compared to other libraries, and no wait for unpacking at the end

**What is missing:**
- Tor support
- Dilation (reconnectable transit protocol)
- Multiple simultaneous file offers in one session (we zip them instead)

## Usage

**Read the full documentation at [docs/usage.md](docs/usage.md), or the
[API reference](https://lux-uno.github.io/magic-wormhole-kotlin/).**

```kotlin
val wormhole = Wormhole()

// Send a file or a whole folder or text
wormhole.sendFile(Path("photo.jpg")).collect { println(it) }
wormhole.sendDirectory(Path("holiday")).collect { println(it) }
wormhole.sendText("Hello, World!").collect { println(it) }

// Receive
wormhole.receive("7-guitarist-revenge").collect { event ->
    when (event) {
        is ReceiveEvent.TextReceived -> println(event.text)
        is ReceiveEvent.FileOffered -> {
            // Saves as `Downloads/photo.jpg`, or unpacks a folder into `Downloads/holiday`
            event.acceptInto(Path("Downloads")) // Or call `event.reject()`
        }
        is ReceiveEvent.Progress -> println("${event.receivedBytes} / ${event.totalBytes}")
        ReceiveEvent.Unpacking -> println("Unpacking the folder")
        is ReceiveEvent.FileReceived -> println("Saved to ${event.saved?.location}")
    }
}
```

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
