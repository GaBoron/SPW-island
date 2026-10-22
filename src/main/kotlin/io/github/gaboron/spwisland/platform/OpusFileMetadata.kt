// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.platform

import io.github.gaboron.spwisland.diagnostics.IslandLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.roundToLong

/** Reads the Opus identification and comment packets and the Ogg granule positions. */
internal object OpusFileMetadata {
    data class Result(val durationMs: Long, val artwork: ByteArray?)

    private const val MAX_TAG_PACKET = 24 * 1024 * 1024
    private const val MAX_ARTWORK = 16 * 1024 * 1024

    fun read(file: File): Result? = RandomAccessFile(file, "r").use { input ->
        var serial: Int? = null
        var preSkip = 0
        var lastGranule = -1L
        var packetNumber = 0
        var artwork: ByteArray? = null
        val packet = ByteArrayOutputStream()
        while (input.filePointer < input.length()) {
            if (input.length() - input.filePointer < 27) return null
            val header = ByteArray(27).also(input::readFully)
            if (!header.copyOfRange(0, 4).contentEquals("OggS".toByteArray()) || header[4].toInt() != 0) return null
            val segments = header[26].toInt() and 0xff
            val lacing = ByteArray(segments).also(input::readFully)
            val pageSize = lacing.sumOf { it.toInt() and 0xff }
            if (input.length() - input.filePointer < pageSize) return null
            val pageSerial = leInt(header, 14)
            if (serial == null) serial = pageSerial
            if (pageSerial != serial) {
                input.seek(input.filePointer + pageSize)
                continue
            }
            val granule = leLong(header, 6)
            if (granule >= 0) lastGranule = granule
            if (packetNumber >= 2) {
                input.seek(input.filePointer + pageSize)
                continue
            }
            for (segment in lacing) {
                val size = segment.toInt() and 0xff
                if (packet.size() + size > MAX_TAG_PACKET) return null
                packet.write(ByteArray(size).also(input::readFully))
                if (size == 255) continue
                val bytes = packet.toByteArray()
                packet.reset()
                when (packetNumber++) {
                    0 -> {
                        if (bytes.size < 19 || !bytes.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray())) return null
                        preSkip = (bytes[10].toInt() and 0xff) or ((bytes[11].toInt() and 0xff) shl 8)
                    }
                    1 -> artwork = readArtwork(bytes)
                }
            }
        }
        if (packetNumber == 0 || lastGranule < 0) return null
        Result(((lastGranule - preSkip).coerceAtLeast(0) / 48.0).roundToLong(), artwork)
    }

    private fun readArtwork(packet: ByteArray): ByteArray? {
        if (packet.size < 16 || !packet.copyOfRange(0, 8).contentEquals("OpusTags".toByteArray())) return null
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).apply { position(8) }
        val vendorLength = data.int
        if (vendorLength < 0 || vendorLength > data.remaining() - 4) return null
        data.position(data.position() + vendorLength)
        val count = data.int
        if (count < 0 || count > data.remaining() / 4) return null
        repeat(count) {
            if (data.remaining() < 4) return null
            val length = data.int
            if (length < 0 || length > data.remaining()) return null
            val start = data.position()
            val separator = (start until start + length).firstOrNull { packet[it] == '='.code.toByte() }
            if (separator != null) {
                val key = String(packet, start, separator - start, Charsets.US_ASCII)
                if (key.equals("METADATA_BLOCK_PICTURE", ignoreCase = true)) {
                    val encodedLength = start + length - separator - 1
                    if (encodedLength <= ((MAX_ARTWORK + 1024) * 4L / 3 + 4)) {
                        val encoded = packet.copyOfRange(separator + 1, start + length)
                        val picture = IslandLog.attempt("metadata", "opus.artwork_decode_failed") {
                            Base64.getDecoder().decode(encoded)
                        }.getOrNull()
                        val image = picture?.let(::pictureData)
                        if (image != null) return image
                    }
                }
            }
            data.position(start + length)
        }
        return null
    }

    /** FLAC picture block fields use network byte order. */
    private fun pictureData(picture: ByteArray): ByteArray? {
        val data = ByteBuffer.wrap(picture).order(ByteOrder.BIG_ENDIAN)
        if (data.remaining() < 32) return null
        data.int // picture type
        repeat(2) { index ->
            val length = data.int
            val reserved = if (index == 0) 24 else 20
            if (length < 0 || length > data.remaining() - reserved) return null
            data.position(data.position() + length)
        }
        if (data.remaining() < 20) return null
        data.position(data.position() + 16) // width, height, depth, palette size
        val length = data.int
        if (length <= 0 || length > MAX_ARTWORK || length > data.remaining()) return null
        return ByteArray(length).also(data::get)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN).int

    private fun leLong(bytes: ByteArray, offset: Int): Long = ByteBuffer.wrap(bytes, offset, 8)
        .order(ByteOrder.LITTLE_ENDIAN).long
}
