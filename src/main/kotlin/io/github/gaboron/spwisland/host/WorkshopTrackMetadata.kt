// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.spwisland.core.Track
import io.github.gaboron.spwisland.core.TrackMetadata
import io.github.gaboron.spwisland.platform.LocalTrackMetadata

/** Public host queries run only on the metadata worker; IDs are never persisted. */
internal object WorkshopTrackMetadata {
    fun current(): PlaybackExtensionPoint.MediaItem? =
        if (WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ))
            WorkshopApi.playback.getCurrentMediaItem() else null

    fun read(track: Track, item: PlaybackExtensionPoint.MediaItem? = runCatching(::current).getOrNull()): TrackMetadata {
        val matched = item?.takeIf { it.toTrack() == track }
        val artwork = matched?.id?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching {
                if (WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ))
                    WorkshopApi.library.getCoverById(id).toCompletableFuture().get() else null
            }.getOrNull()
        }
        return LocalTrackMetadata.read(track.path, matched?.duration?.coerceAtLeast(0) ?: 0, artwork)
    }
}

internal fun PlaybackExtensionPoint.MediaItem.toTrack() = Track(title, artist, path)
