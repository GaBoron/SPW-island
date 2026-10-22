// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import io.github.gaboron.spwisland.core.TrackMetadata
import io.github.gaboron.spwisland.core.CoverArtwork
import io.github.gaboron.spwisland.diagnostics.IslandLog
import org.jaudiotagger.audio.AudioFileIO
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Read-only local tags. No audio decoding, writes, remote artwork, or host-private APIs. */
object LocalTrackMetadata {
    fun read(path: String, durationMs: Long = 0, artwork: ByteArray? = null): TrackMetadata {
        val file = File(path)
        val needsTags = file.isFile && (durationMs <= 0 || artwork == null)
        val opus = if (needsTags && file.extension.equals("opus", ignoreCase = true))
            IslandLog.attempt("metadata", "opus.read_failed") { OpusFileMetadata.read(file) }.getOrNull() else null
        val audio = if (needsTags && opus == null)
            IslandLog.attempt("metadata", "tags.read_failed") { AudioFileIO.read(file) }.getOrNull() else null
        val duration = durationMs.takeIf { it > 0 } ?: opus?.durationMs ?: audio?.audioHeader?.preciseTrackLength
            ?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).roundToLong() } ?: 0
        val embedded = artwork ?: opus?.artwork ?:
            IslandLog.attempt("metadata", "artwork.read_failed") { audio?.tag?.firstArtwork?.binaryData }.getOrNull()
        val cover = embedded?.takeIf { it.size <= 16 * 1024 * 1024 }?.let { bytes ->
            IslandLog.attempt("metadata", "artwork.decode_failed") { ByteArrayInputStream(bytes).use { decode(it) } }.getOrNull()
        } ?: sequenceOf("cover.jpg", "cover.png", "folder.jpg", "folder.png").mapNotNull { name ->
            if (!file.isFile) return@mapNotNull null
            val image = File(file.parentFile, name)
            if (!image.isFile || image.length() > 16 * 1024 * 1024) null
            else IslandLog.attempt("metadata", "sidecar.decode_failed") { image.inputStream().use { decode(it) } }.getOrNull()
        }.firstOrNull()
        return TrackMetadata(duration, cover?.let(CoverColorExtractor::dominant), cover?.let(::artwork))
    }

    private fun artwork(image: BufferedImage) = CoverArtwork(image.width, image.height,
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width))

    private fun decode(input: java.io.InputStream): BufferedImage? = MemoryCacheImageInputStream(input).use { stream ->
        val readers = ImageIO.getImageReaders(stream)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = stream
            val width = reader.getWidth(0); val height = reader.getHeight(0)
            if (width <= 0 || height <= 0 || width.toLong() * height > 100_000_000) return null
            val step = ceil(maxOf(width, height) / 64.0).toInt().coerceAtLeast(1)
            val params = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
            reader.read(0, params)
        } finally { reader.dispose() }
    }
}
