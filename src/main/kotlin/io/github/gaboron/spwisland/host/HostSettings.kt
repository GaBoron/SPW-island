// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.config.ConfigManager
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.sun.jna.Platform
import io.github.gaboron.spwisland.core.*
import java.util.function.Consumer
import java.nio.file.Files
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HostSettings(private val manager: ConfigManager, private val changed: () -> Unit) : SettingsStore, AutoCloseable {
    private val lock = Any()
    private val config = manager.getConfig("island.json")
    private var closed = false
    private var saveInProgress = false
    private var accepted: IslandSettings? = null
    private var fingerprint: ByteArray? = null
    private val poller = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "SPW Island settings sync").apply { isDaemon = true }
    }
    // Notifications are hints only. Keep our helper instead of adopting the settings form's
    // mutable cache, which can still contain the position from before the latest drag.
    private val listener = Consumer<ConfigHelper> { refresh() }
    init {
        migrateSettings()
        accepted = decode()
        manager.addConfigChangeListener("island.json", listener)
        // SPW can fail to register its watcher when a plugin's data directory does not yet exist.
        // Re-read the actual file independently; notifications remain a fast path only.
        poller.scheduleWithFixedDelay({ runCatching { refresh() }.onFailure {
            System.err.println("[SPW Island] Settings sync: ${it.message}")
        } }, 250, 250, TimeUnit.MILLISECONDS)
    }

    override fun read(): IslandSettings = synchronized(lock) { accepted ?: decode() }
    private fun migrateSettings() {
        // Native sliders use floating-point values. Persist whole-number settings as integers
        // so reopening SPW does not inherit noisy fractional values from the slider thumb.
        if (!Files.exists(config.getConfigPath()) || !config.reload()) return
        val migrated = migrateCornerRoundnessForV0100() or normalizeIntegerSettings() or
            normalizeBackgroundProgressSetting()
        if (migrated) check(saveConfig()) { "词岛旧设置迁移失败，请检查 SPW 配置目录权限。" }
    }
    private fun decode(): IslandSettings = IslandSettings(
        // An incomplete settings write must not change an accepted visibility policy.
        enabled = config.get("enabled", accepted?.enabled ?: true), translation = config.get("translation", true),
        karaoke = config.get("karaoke", true),
        experimentalMultiLine = config.get("experimental_multi_line", false),
        hidePaused = config.get("hide_paused", accepted?.hidePaused ?: false),
        hideFullscreen = Platform.isWindows() && config.get("hide_fullscreen", accepted?.hideFullscreen ?: true),
        clickThrough = Platform.isWindows() && config.get("click_through", accepted?.clickThrough ?: false),
        autoHideOnHover = Platform.isWindows() && config.get("auto_hide_on_hover", accepted?.autoHideOnHover ?: false),
        // Keep the original key so existing users retain their enabled setting after the rename.
        lowPerformance = config.get("reduced_motion", false), notch = config.get("shape", "pill") == "notch",
        cornerRoundness = number("corner_roundness", 95, 0, 100),
        lyricCoverColor = config.get("lyric_cover_color", false),
        backgroundCoverColor = config.get("background_cover_color", false),
        backgroundProgress = backgroundProgressMode(),
        spectrumCoverColor = config.get("spectrum_cover_color", false),
        fixedWidth = config.get("fixed_width", false),
        sideContent = if (!Platform.isWindows()) SideContent.COVER else when (config.get("leading_content", "cover_spectrum")) {
            "spectrum" -> SideContent.SPECTRUM
            "cover" -> SideContent.COVER
            "none" -> SideContent.NONE
            else -> SideContent.COVER_SPECTRUM
        },
        fontFamily = config.get("font_family", "").take(100).trim(),
        fontWeight = LyricFontWeight.fromStorage(
            config.get<Any>("font_weight", "400").toString().toDoubleOrNull()?.toInt()?.toString() ?: "400"
        ),
        fontSize = number("font_size", 22, 12, 72), maxWidth = number("max_width", 640, 280, 1200),
        opacity = number("opacity", 96, 35, 100), offsetMs = number("offset_ms", 0, -2000, 2000),
        screen = config.get("screen", ""),
        positionX = optionalNumber("position_x"),
        positionY = optionalNumber("position_y"),
        positionAnchor = IslandAnchor.fromStorage(config.get("position_anchor", "")),
        legacyCenterX = optionalNumber("center_x"),
        legacyTop = optionalNumber("top")
    )
    internal fun refresh() {
        val notify = synchronized(lock) {
            if (closed || saveInProgress || !Files.exists(config.getConfigPath())) return
            val bytes = Files.readAllBytes(config.getConfigPath())
            if (bytes.isEmpty() || fingerprint?.contentEquals(bytes) == true) return
            // Failed/partial writes must not replace the last usable snapshot with defaults.
            val retainedPosition = accepted?.positionState()
            val retainedFont = accepted?.fontState()
            if (!config.reload()) return
            val value = decodePreservingPluginSettings(retainedPosition, retainedFont)
            val positionRestored = restorePosition(retainedPosition)
            val fontRestored = restoreFont(retainedFont)
            val normalized = normalizeIntegerSettings() or normalizeBackgroundProgressSetting()
            val needsSave = positionRestored || fontRestored || normalized
            if (needsSave && !saveConfig()) return
            // A later host write can race with save(). Recheck the file on the next poll,
            // and never decode the mutable helper again to replace our owned state.
            fingerprint = if (needsSave) null else bytes
            (value != accepted).also { accepted = value }
        }
        if (notify) changed()
    }

    private fun decodePreservingPluginSettings(position: PositionState?, font: FontState?): IslandSettings {
        val decoded = decode()
        val positioned = position?.applyTo(decoded) ?: decoded
        return font?.applyTo(positioned) ?: positioned
    }

    private fun saveConfig(): Boolean {
        // All writes, including stale-state restoration, can notify listeners synchronously.
        saveInProgress = true
        return try { config.save() } finally { saveInProgress = false }
    }

    /** Position is plugin-owned state; SPW's settings form can write back a stale config snapshot. */
    private fun restorePosition(retained: PositionState?): Boolean {
        if (retained == null || decode().positionState() == retained) return false
        config.set("screen", retained.screen)
        config.set("position_x", retained.x ?: Int.MIN_VALUE)
        config.set("position_y", retained.y ?: Int.MIN_VALUE)
        config.set("position_anchor", retained.anchor?.storageName ?: "")
        config.set("center_x", retained.legacyCenterX ?: Int.MIN_VALUE)
        config.set("top", retained.legacyTop ?: Int.MIN_VALUE)
        return true
    }

    private fun IslandSettings.positionState() = PositionState(
        screen, positionX, positionY, positionAnchor, legacyCenterX, legacyTop
    )

    /** The font picker owns these keys; SPW's settings form can save an older snapshot. */
    private fun restoreFont(retained: FontState?): Boolean {
        if (retained == null || decode().fontState() == retained) return false
        config.set("font_family", retained.family)
        config.set("font_weight", retained.weight.storageName)
        return true
    }

    private fun IslandSettings.fontState() = FontState(fontFamily, fontWeight)

    private fun number(key: String, default: Int, min: Int, max: Int): Int {
        // Integer text fields coexist with numeric values saved by the original sliders.
        val text = config.get<Any>(key, "") as? String
        val value = text?.trim()?.toDoubleOrNull() ?: (config.get<Any>(key, default) as? Number)?.toDouble()
        return value?.takeIf { it.isFinite() }?.coerceIn(min.toDouble(), max.toDouble())?.roundToInt() ?: default
    }

    private fun optionalNumber(key: String): Int? =
        number(key, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE).takeUnless { it == Int.MIN_VALUE }

    private fun backgroundProgressMode(): BackgroundProgressMode =
        when (val value = config.get<Any>("background_progress", "off")) {
            true -> BackgroundProgressMode.FILL
            is String -> when (value) {
                "fill" -> BackgroundProgressMode.FILL
                "top_line" -> BackgroundProgressMode.TOP_LINE
                else -> BackgroundProgressMode.OFF
            }
            else -> BackgroundProgressMode.OFF
        }

    private fun normalizeBackgroundProgressSetting(): Boolean {
        val value = config.get<Any>("background_progress", "off")
        if (value !is Boolean) return false
        config.set("background_progress", if (value) "fill" else "off")
        return true
    }

    private fun migrateCornerRoundnessForV0100(): Boolean {
        if (config.get(CORNER_ROUNDNESS_V0100_MIGRATED, false)) return false
        config.set("corner_roundness", 95)
        config.set(CORNER_ROUNDNESS_V0100_MIGRATED, true)
        return true
    }

    private fun normalizeIntegerSettings(): Boolean {
        var changed = false
        for ((key, limits) in INTEGER_SETTINGS) {
            val raw = config.get<Any>(key, "")
            val value = (raw as? Number)?.toDouble() ?: (raw as? String)?.trim()?.toDoubleOrNull() ?: continue
            val rounded = value.takeIf { it.isFinite() }
                ?.coerceIn(limits.first.toDouble(), limits.last.toDouble())?.roundToInt() ?: limits.default
            if (raw !is Number || raw.toDouble() != rounded.toDouble()) {
                config.set(key, rounded)
                changed = true
            }
        }
        return changed
    }
    override fun set(key: String, value: Any) = update { it.set(key, value) }
    override fun setFont(family: String, weight: LyricFontWeight) = update(replacesFont = true) {
        it.set("font_family", family.take(100).trim())
        it.set("font_weight", weight.storageName)
    }
    override fun savePosition(screen: String, x: Int, y: Int, anchor: IslandAnchor) = update(replacesPosition = true) {
        it.set("screen", screen); it.set("position_x", x); it.set("position_y", y)
        it.set("position_anchor", anchor.storageName)
        // Clear legacy center/top storage after the first drag on the automatic anchor model.
        it.set("center_x", Int.MIN_VALUE); it.set("top", Int.MIN_VALUE); it.set("vertical_anchor", "free")
    }
    override fun resetPosition() = update(replacesPosition = true) {
        it.set("screen", ""); it.set("position_x", Int.MIN_VALUE); it.set("position_y", Int.MIN_VALUE)
        it.set("position_anchor", ""); it.set("center_x", Int.MIN_VALUE); it.set("top", Int.MIN_VALUE)
        it.set("vertical_anchor", "free")
    }
    override fun resetAll() = update(replacesPosition = true, replacesFont = true) { helper ->
        IslandSettingsDefaults.values.forEach { (key, value) -> helper.set(key, value) }
    }
    private fun update(replacesPosition: Boolean = false, replacesFont: Boolean = false,
                       change: (ConfigHelper) -> Unit) {
        synchronized(lock) {
            if (closed) return
            val retainedPosition = if (replacesPosition) null else accepted?.positionState()
            val retainedFont = if (replacesFont) null else accepted?.fontState()
            // Preserve settings written by the host even if its notification has not arrived yet.
            if (Files.exists(config.getConfigPath())) check(config.reload()) { "词岛设置读取失败，未覆盖已有设置。" }
            restorePosition(retainedPosition)
            restoreFont(retainedFont)
            change(config)
            val next = decodePreservingPluginSettings(retainedPosition, retainedFont)
            check(saveConfig()) { "词岛设置保存失败，请检查 SPW 配置目录权限。" }
            // Keep the requested snapshot even if the helper's cache changes during save.
            accepted = next
            fingerprint = null
        }
        changed()
    }
    override fun close() {
        synchronized(lock) { closed = true }
        poller.shutdownNow()
        manager.removeConfigChangeListener(listener)
    }

    private data class IntegerLimits(val default: Int, val first: Int, val last: Int)
    private data class PositionState(
        val screen: String,
        val x: Int?,
        val y: Int?,
        val anchor: IslandAnchor?,
        val legacyCenterX: Int?,
        val legacyTop: Int?
    ) {
        fun applyTo(settings: IslandSettings) = settings.copy(
            screen = screen, positionX = x, positionY = y, positionAnchor = anchor,
            legacyCenterX = legacyCenterX, legacyTop = legacyTop
        )
    }
    private data class FontState(val family: String, val weight: LyricFontWeight) {
        fun applyTo(settings: IslandSettings) = settings.copy(fontFamily = family, fontWeight = weight)
    }

    private companion object {
        val INTEGER_SETTINGS = mapOf(
            "corner_roundness" to IntegerLimits(95, 0, 100),
            "font_size" to IntegerLimits(22, 12, 72),
            "max_width" to IntegerLimits(640, 280, 1200),
            "opacity" to IntegerLimits(96, 35, 100),
            "offset_ms" to IntegerLimits(0, -2000, 2000)
        )
        const val CORNER_ROUNDNESS_V0100_MIGRATED = "corner_roundness_migrated_v0_10_0"
    }
}
