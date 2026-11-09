package io.github.gaboron.spwisland.host

import java.lang.management.ManagementFactory
import java.net.HttpURLConnection
import java.net.URI
import javax.management.ObjectName
import javax.management.openmbean.CompositeData

/** JDK-only process-local contract, shared by independently loaded playback plugins. */
internal class ExternalPlaybackClient {
    private val server = ManagementFactory.getPlatformMBeanServer()

    fun snapshot(): ExternalPlayback? = runCatching {
        server.queryNames(SOURCES, null).mapNotNull(::snapshot)
            .maxWithOrNull(compareBy<ExternalPlayback> { it.activatedAtNanos }.thenBy { it.sourceName.canonicalName })
    }.getOrNull()

    private fun snapshot(name: ObjectName): ExternalPlayback? = runCatching {
        if (server.getAttribute(name, "ProtocolVersion") != 1) return null
        val value = server.getAttribute(name, "Snapshot") as? CompositeData ?: return null
        if (value["active"] != true) return null
        val updated = value.number("updatedAtNanos")
        if (updated != 0L && System.nanoTime() - updated !in 0L..15_000_000_000L) return null
        ExternalPlayback(
            sourceName = name, activatedAtNanos = value.number("activatedAtNanos"),
            provider = value.text("provider"), sessionId = value.text("sessionId"),
            revision = value.number("revision"), trackRevision = value.number("trackRevision"),
            uri = value.text("uri"), title = value.text("title"), artist = value.text("artist"),
            album = value.text("album"), coverUrl = value.text("coverUrl"),
            durationMs = value.number("durationMs").coerceAtLeast(0),
            positionMs = value.number("positionMs").coerceAtLeast(0),
            sampledAtNanos = value.number("sampledAtNanos"), playing = value["playing"] == true,
            volumePercent = value.number("volumePercent").coerceIn(0, 100).toInt(),
            playbackSpeed = (if (value.containsKey("playbackSpeed")) value["playbackSpeed"] as? Number else null)
                ?.toDouble()?.takeIf { it.isFinite() && it in .1..8.0 } ?: 1.0,
        ).takeIf { it.provider.isNotBlank() && it.sessionId.isNotBlank() && it.uri.isNotBlank() }
    }.getOrNull()

    fun lyrics(track: ExternalPlayback): ExternalLyrics? = runCatching {
        if (!current(track)) return null
        val value = server.getAttribute(track.sourceName, "Lyrics") as? CompositeData ?: return null
        if (value.text("sessionId") != track.sessionId || value.number("trackRevision") != track.trackRevision ||
            value.text("uri") != track.uri) return null
        val format = value.text("format")
        val content = value.text("content")
        val spl = value.text("spl")
        val offset = value.number("offsetMs")
        if (format !in setOf("ttml", "lrc", "empty") || content.length > 2_000_000 || spl.length > 2_000_000 ||
            offset !in -600_000L..600_000L) return null
        ExternalLyrics(format, content, spl, offset, value.number("revision"))
    }.getOrNull()

    fun control(track: ExternalPlayback, command: String, value: Long = 0): Boolean = runCatching {
        if (!current(track)) return false
        val operations = server.getMBeanInfo(track.sourceName).operations
        fun available(name: String, signature: Array<String>) = operations.any { operation ->
            operation.name == name && operation.signature.map { it.type } == signature.toList()
        }
        fun invoke(name: String, arguments: Array<Any>, signature: Array<String>): Boolean =
            available(name, signature) && server.invoke(track.sourceName, name, arguments, signature) != false
        val guarded = arrayOf("java.lang.String", "long", "java.lang.String", "java.lang.String", "long")
        if (available("control", guarded)) return server.invoke(track.sourceName, "control",
            arrayOf<Any>(track.sessionId, track.trackRevision, track.uri, command, value), guarded) == true
        when (command) {
            "seek" -> {
                val identity = arrayOf("java.lang.String", "long", "java.lang.String", "long")
                if (available("seekToIdentity", identity)) invoke("seekToIdentity",
                    arrayOf(track.sessionId, track.trackRevision, track.uri, value.coerceAtLeast(0)), identity)
                else invoke("seekTo", arrayOf(track.uri, value.coerceAtLeast(0)), arrayOf("java.lang.String", "long"))
            }
            "volume" -> invoke("setVolume", arrayOf(value.coerceIn(0, 100).toInt()), arrayOf("int"))
            "play", "pause", "previous", "next" -> invoke(command, emptyArray(), emptyArray())
            else -> false
        }
    }.getOrDefault(false)

    fun seek(track: ExternalPlayback, positionMs: Long): Boolean = control(track, "seek", positionMs.coerceAtLeast(0))

    fun volume(track: ExternalPlayback, percent: Int): Boolean = control(track, "volume", percent.coerceIn(0, 100).toLong())

    private fun current(track: ExternalPlayback): Boolean = snapshot()?.identity == track.identity

    private companion object {
        val SOURCES = ObjectName("dev.gaboron.spw:type=ExternalPlayback,version=1,*")
    }
}

internal data class ExternalPlayback(
    val sourceName: ObjectName, val activatedAtNanos: Long,
    val provider: String, val sessionId: String, val revision: Long, val trackRevision: Long,
    val uri: String, val title: String, val artist: String, val album: String, val coverUrl: String,
    val durationMs: Long, val positionMs: Long, val sampledAtNanos: Long, val playing: Boolean,
    val volumePercent: Int, val playbackSpeed: Double,
) {
    val identity: String get() = listOf(sourceName.canonicalName, provider, sessionId, trackRevision.toString(), uri)
        .joinToString("") { "${it.length}:$it" }

    fun currentPosition(): Long {
        val elapsed = if (playing) ((System.nanoTime() - sampledAtNanos) / 1_000_000L).coerceAtLeast(0) else 0
        val position = (positionMs.toDouble() + elapsed * playbackSpeed).toLong()
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }
}

internal data class ExternalLyrics(val format: String, val content: String, val spl: String, val offsetMs: Long, val revision: Long)

private fun CompositeData.text(key: String): String = if (containsKey(key)) get(key) as? String ?: "" else ""
private fun CompositeData.number(key: String): Long = if (containsKey(key)) (get(key) as? Number)?.toLong() ?: 0 else 0

/** HTTP artwork is bounded before decoding on the consumer's worker; local paths are not fetched. */
internal fun readExternalArtwork(url: String): ByteArray? = runCatching {
    val uri = URI(url)
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    val connection = uri.toURL().openConnection() as? HttpURLConnection ?: return null
    try {
        connection.connectTimeout = 3_000
        connection.readTimeout = 4_000
        connection.instanceFollowRedirects = false
        if (connection.responseCode != 200 || connection.contentLengthLong > 8_000_000) return null
        connection.inputStream.use { input -> input.readNBytes(8_000_001).takeIf { it.size <= 8_000_000 } }
    } finally { connection.disconnect() }
}.getOrNull()
