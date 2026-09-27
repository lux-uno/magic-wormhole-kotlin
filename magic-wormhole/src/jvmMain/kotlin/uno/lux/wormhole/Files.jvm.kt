package uno.lux.wormhole

import kotlinx.io.RawSource
import kotlinx.io.asSource
import kotlinx.io.files.Path
import java.io.FileInputStream

internal actual fun openFileAt(
    path: Path,
    offset: Long,
): RawSource {
    val stream = FileInputStream(path.toString())
    stream.channel.position(offset)
    return stream.asSource()
}
