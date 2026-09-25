# wormhole-kotlin

A Kotlin Multiplatform implementation of the [Magic Wormhole](https://magic-wormhole.readthedocs.io/)
protocol. Send text and files between devices using a short code like `7-guitarist-revenge`.

Compatible with the Python `wormhole` CLI and [wormhole-william](https://github.com/psanford/wormhole-william).

- Pure Kotlin: no native libraries, no Go.
- Targets: JVM (also Android), iOS (`iosArm64`, `iosSimulatorArm64`).
- Coroutines-first API.

> Status: under development. Not yet published.

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

What is supported: text, single files, receiving directories (as a `.zip`), direct TCP
connections and the transit relay. Not yet: sending directories, Dilation, Tor.
On iOS the library does not listen for direct connections yet; it connects out directly or via the
relay.

## Installation

After the first release:

```kotlin
dependencies {
    implementation("io.github.lux-uno:wormhole:<version>")
}
```

For local development, include this repository as a composite build:

```kotlin
// settings.gradle.kts of your app
includeBuild("../wormhole-kotlin")
```

## Building and testing

```bash
./gradlew jvmTest
```

Interop tests against the real `wormhole` CLI and the public relay are skipped by default. Run them
with `WORMHOLE_INTEROP=1 ./gradlew jvmTest` (needs `wormhole` on `PATH` and internet access).

## License

MIT. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
