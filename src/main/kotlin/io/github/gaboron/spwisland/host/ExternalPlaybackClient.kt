package io.github.gaboron.spwisland.host

import java.lang.management.ManagementFactory
import java.net.URI
import javax.management.ObjectName
import javax.management.openmbean.CompositeData

/** JDK-only process-local contract, shared by independently loaded playback plugins. */
internal class ExternalPlaybackClient {
    private val server = ManagementFactory.getPlatformMBeanServer()

    fun snapshot(): ExternalPlayback? = runCatching {
        if (!server.isRegistered(NAME) || server.getAttribute(NAME, "ProtocolVersion") != 1) return null
        val value = server.getAttribute(NAME, "Snapshot") as? CompositeData ?: return null
        if (value["active"] != true) return null
        ExternalPlayback(
            provider = value.text("provider"), sessionId = value.text("sessionId"),
            revision = value.number("revision"), trackRevision = value.number("trackRevision"),
            uri = value.text("uri"), title = value.text("title"), artist = value.text("artist"),
            album = value.text("album"), coverUrl = value.text("coverUrl"),
            durationMs = value.number("durationMs").coerceAtLeast(0),
            positionMs = value.number("positionMs").coerceAtLeast(0),
            sampledAtNanos = value.number("sampledAtNanos"), playing = value["playing"] == true,
            volumePercent = value.number("volumePercent").toInt().coerceIn(0, 100),
            playbackSpeed = (if (value.containsKey("playbackSpeed")) value["playbackSpeed"] as? Number else null)
                ?.toDouble()?.takeIf { it.isFinite() && it in .1..8.0 } ?: 1.0,
        ).takeIf { it.provider.isNotBlank() && it.sessionId.isNotBlank() && it.uri.isNotBlank() }
    }.getOrNull()

    fun lyrics(track: ExternalPlayback): ExternalLyrics? = runCatching {
        val value = server.getAttribute(NAME, "Lyrics") as? CompositeData ?: return null
        if (value.text("sessionId") != track.sessionId || value.number("trackRevision") != track.trackRevision ||
            value.text("uri") != track.uri) return null
        ExternalLyrics(value.text("format"), value.text("content"), value.text("spl"),
            value.number("offsetMs"), value.number("revision"))
    }.getOrNull()

    fun control(command: String): Boolean = runCatching {
        server.invoke(NAME, command, emptyArray(), emptyArray()) != false
    }.getOrDefault(false)

    fun seek(track: ExternalPlayback, positionMs: Long): Boolean = runCatching {
        if (server.getMBeanInfo(NAME).operations.any { it.name == "seekToIdentity" })
            server.invoke(NAME, "seekToIdentity", arrayOf<Any>(track.sessionId, track.trackRevision, track.uri, positionMs.coerceAtLeast(0)),
                arrayOf("java.lang.String", "long", "java.lang.String", "long")) == true
        else server.invoke(NAME, "seekTo", arrayOf<Any>(track.uri, positionMs.coerceAtLeast(0)),
                arrayOf("java.lang.String", "long")) == true
    }.getOrDefault(false)

    fun volume(percent: Int): Boolean = runCatching {
        server.invoke(NAME, "setVolume", arrayOf<Any>(percent.coerceIn(0, 100)), arrayOf("int")) == true
    }.getOrDefault(false)

    private companion object {
        val NAME = ObjectName("dev.gaboron.spw:type=ExternalPlayback,version=1")
    }
}

internal data class ExternalPlayback(
    val provider: String, val sessionId: String, val revision: Long, val trackRevision: Long,
    val uri: String, val title: String, val artist: String, val album: String, val coverUrl: String,
    val durationMs: Long, val positionMs: Long, val sampledAtNanos: Long, val playing: Boolean,
    val volumePercent: Int, val playbackSpeed: Double,
) {
    val identity: String get() = "$sessionId|$trackRevision|$uri"

    fun currentPosition(): Long {
        val elapsed = if (playing) ((System.nanoTime() - sampledAtNanos) / 1_000_000L).coerceAtLeast(0) else 0
        val position = positionMs + (elapsed * playbackSpeed).toLong()
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }
}

internal data class ExternalLyrics(val format: String, val content: String, val spl: String, val offsetMs: Long, val revision: Long)

private fun CompositeData.text(key: String): String = get(key) as? String ?: ""
private fun CompositeData.number(key: String): Long = if (containsKey(key)) (get(key) as? Number)?.toLong() ?: 0 else 0

/** Artwork IO is restricted to HTTPS and bounded before decoding on the consumer's worker. */
internal fun readExternalArtwork(url: String): ByteArray? = runCatching {
    val uri = URI(url)
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
    val connection = uri.toURL().openConnection().apply { connectTimeout = 3_000; readTimeout = 4_000 }
    if (connection.contentLengthLong > 8_000_000) return null
    connection.getInputStream().use { input -> input.readNBytes(8_000_001).takeIf { it.size <= 8_000_000 } }
}.getOrNull()
