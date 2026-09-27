package uno.lux.wormhole

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal actual fun openFileAt(
    path: Path,
    offset: Long,
): RawSource = SystemFileSystem.source(path).buffered().apply { skip(offset) }
