# wormhole-kotlin

A Kotlin Multiplatform implementation of the [Magic Wormhole](https://magic-wormhole.readthedocs.io/)
protocol. Send text and files between devices using a short code like `7-guitarist-revenge`.

Compatible with the Python `wormhole` CLI and [wormhole-william](https://github.com/psanford/wormhole-william).

- Pure Kotlin: no native libraries, no Go.
- Targets: JVM (also Android), iOS (`iosArm64`, `iosSimulatorArm64`).
- Coroutines-first API.

> Status: under development. Not yet published.

## Usage

```kotlin
val wormhole = Wormhole(WormholeConfig())

// Sender
wormhole.sendText("hello").collect { event ->
    when (event) {
        is SendEvent.CodeAllocated -> println("Code: ${event.code}")
        is SendEvent.Progress -> Unit
        SendEvent.Completed -> println("Sent")
    }
}

// Receiver
when (val incoming = wormhole.receive("7-guitarist-revenge")) {
    is IncomingTransfer.Text -> println(incoming.text)
    is IncomingTransfer.FileOffer -> incoming.accept(sink) { received, total -> }
}
```

## Installation

After the first release:

```kotlin
dependencies {
    implementation("uno.lux.wormhole:wormhole:<version>")
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
