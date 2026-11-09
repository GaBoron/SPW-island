// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import io.github.gaboron.spwisland.diagnostics.IslandLog

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.spwisland.core.Track
import io.github.gaboron.spwisland.core.TrackMetadata
import io.github.gaboron.spwisland.platform.LocalTrackMetadata
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Public host queries run only on the metadata worker; IDs are never persisted. */
internal object WorkshopTrackMetadata {
    fun current(): PlaybackExtensionPoint.MediaItem? {
        val granted = WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)
        IslandLog.state("permissions", "library_read", granted.toString())
        return if (granted) WorkshopApi.playback.getCurrentMediaItem() else null
    }

    fun read(track: Track, item: PlaybackExtensionPoint.MediaItem? = IslandLog.attempt("WorkshopTrackMetadata", "read.attempt1", ::current).getOrNull()): TrackMetadata {
        val matched = item?.takeIf { it.toTrack() == track }
        val artwork = matched?.id?.takeIf { it.isNotBlank() }?.let { id ->
            IslandLog.attempt("WorkshopTrackMetadata", "read.attempt2") {
                if (WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ))
                    WorkshopApi.library.getCoverById(id).toCompletableFuture().get(4, TimeUnit.SECONDS) else null
            }.getOrNull()
        }
        val duration = matched?.duration?.coerceAtLeast(0) ?: 0
        val localPath = localMediaPath(track.path)
        return if (localPath == null) LocalTrackMetadata.fromArtwork(duration, artwork)
            else LocalTrackMetadata.read(localPath, duration, artwork)
    }
}

internal fun PlaybackExtensionPoint.MediaItem.toTrack() = Track(title, artist, path,
    if (localMediaPath(path) == null) id.ifBlank { path } else "")

/** Provider URIs are identities, while file URIs and native paths remain local metadata inputs. */
internal fun localMediaPath(path: String): String? {
    if (path.isBlank()) return null
    if (path.matches(Regex("^[A-Za-z]:[\\\\/].*")) || path.startsWith("\\\\")) return path
    val uri = runCatching { URI(path) }.getOrNull()
        ?: return path.takeUnless { it.contains(Regex("^[A-Za-z][A-Za-z0-9+.-]*:")) }
    return when (uri.scheme?.lowercase()) {
        null -> path
        "file" -> runCatching { Path.of(uri).toString() }.getOrNull()
        else -> null
    }
}
