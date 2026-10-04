// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import com.sun.jna.Platform
import io.github.gaboron.spwisland.core.*
import io.github.gaboron.spwisland.platform.WindowsOverlay
import io.github.gaboron.spwisland.platform.X11InputRegion
import io.github.gaboron.spwisland.platform.LinuxGlobalPointer
import java.awt.*
import java.awt.event.*
import javax.swing.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

private fun overlayGraphicsConfiguration(): GraphicsConfiguration {
    val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
    return if (Platform.isLinux()) {
        device.configurations.firstOrNull(GraphicsConfiguration::isTranslucencyCapable)
            ?: device.defaultConfiguration
    } else device.defaultConfiguration
}

/** Owns only window lifecycle, placement and presentation animation. Must live on the EDT. */
class IslandWindow(private val timeline: PlaybackSource, private val store: SettingsStore,
                   actions: PlaybackActions, private val report: (Throwable) -> Unit,
                   private val spectrum: () -> FloatArray = { FloatArray(4) },
                   private val spectrumFallback: () -> Boolean = { false },
                   private val spectrumStatus: () -> String = { "" }) : AutoCloseable {
    companion object {
        private const val DRAG_FRAME_DELAY_MS = 8
        private const val HOVER_MARGIN = 18
    }
    // A persistent overlay is a frame, not an AWT popup (SunAwtWindow).
    // Give it a distinct native role from tray menus and tooltips.
    private val window = BufferedIslandWindow(overlayGraphicsConfiguration()).apply {
        isUndecorated = true
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        name = ApplicationIdentity.NAME
        title = ApplicationIdentity.NAME
        iconImage = ApplicationIdentity.icon
        // GNOME/Mutter can throttle an application's utility surfaces together with its
        // obscured main window. Keep the Linux overlay an independent normal top-level.
        type = if (Platform.isLinux()) Window.Type.NORMAL else Window.Type.UTILITY
        isAlwaysOnTop = true
        focusableWindowState = false; isAutoRequestFocus = false
        background = Color(0, 0, 0, 0)
        rootPane.isOpaque = false
        rootPane.isDoubleBuffered = false
        layeredPane.isOpaque = false
    }
    private val panel = IslandPanel(actions, report)
    private val surface = IslandSurface(panel)
    private val hoverVisibility = IslandHoverVisibility()
    private val expandedMotion = IslandExpandedMotion()
    private val native = WindowsOverlay()
    private val inputRegion = if (Platform.isLinux() && Toolkit.getDefaultToolkit().javaClass.name.contains("XToolkit"))
        runCatching { X11InputRegion() }.onFailure(report).getOrNull() else null
    private val pointerPresence = if (Platform.isLinux()) IslandPointerPresence(window) else null
    private val globalPointer = if (Platform.isLinux()) LinuxGlobalPointer() else null
    private val stableTranslucentCanvas = Platform.isWindows() || window.graphicsConfiguration.isTranslucencyCapable
    private val menu = IslandMenu(store, report, window) {
        IslandRuntimeStatus.describe(store.read(), timeline.snapshot(), window.isVisible,
            surface.hoverPose.reveal < .01, fullscreen, inputRegion != null || native.supportsClickThrough && nativeAvailable,
            if (globalPointer != null) globalPointer.location() != null else
                runCatching { MouseInfo.getPointerInfo() != null }.getOrDefault(false),
            globalPointer?.status.orEmpty(), spectrumFallback(), spectrumStatus())
    }
    private var settings = store.read()
    private var nativeAvailable = true
    private var clickThroughApplied: Boolean? = null
    @Volatile private var closed = false
    private var fullscreen = false
    private var nextScreenCheck = 0L
    private var nextTopmostCheck = 0L
    private var topmostAvailable = true
    private var lastFrame = System.nanoTime()
    private var lastLines: List<LyricLine> = emptyList()
    private var previousSnapshot: PlaybackSnapshot? = null
    private var width = 280.0
    private var height = 58.0
    private var lyricWidth = 280.0
    private var lyricHeight = 58.0
    private var controlsWidth = 0.0
    private var hoverActive = false

    private var canvasWidth = 0
    private var canvasHeight = 0
    private var dragTopLeft: Point? = null
    private var dragAnchor: IslandAnchor? = null
    private var press: Point? = null
    private var dragOrigin: Point? = null
    private var dragging = false
    private var placementAnchor = settings.positionAnchor ?: IslandAnchor.TOP_CENTER
    @Volatile private var frameDelayMs = 16
    private val frameScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "SPW Island frame scheduler").apply { isDaemon = true }
    }

    init {
        check(SwingUtilities.isEventDispatchThread())
        window.contentPane = surface
        window.setSize(width.toInt(), height.toInt())
        panel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!Platform.isLinux() && e.isPopupTrigger) menu.popup(panel, e.x, e.y)
                if (SwingUtilities.isLeftMouseButton(e) && !settings.clickThrough && !settings.autoHideOnHover) {
                    press = e.locationOnScreen
                    dragOrigin = Point(window.x + panel.x, window.y + panel.y)
                    dragAnchor = placementAnchor
                    frameDelayMs = DRAG_FRAME_DELAY_MS
                }
            }
            override fun mouseReleased(e: MouseEvent) {
                if (!Platform.isLinux() && e.isPopupTrigger) menu.popup(panel, e.x, e.y)
                if (dragging) {
                    val topLeft = dragTopLeft ?: Point(window.x + panel.x, window.y + panel.y)
                    val current = Rectangle(topLeft.x, topLeft.y, panel.width, panel.height)
                    val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                    val device = devices.find { it.defaultConfiguration.bounds.contains(current.centerPoint()) }
                        ?: window.graphicsConfiguration.device
                    val workArea = IslandPlacement.workArea(device.defaultConfiguration)
                    if (settings.notch) current.y = workArea.y
                    val automatic = IslandPlacement.automaticAnchor(workArea, current, settings.notch)
                    val point = IslandPlacement.anchorPoint(current, automatic)
                    try {
                        store.savePosition(device.iDstring, point.x, point.y, automatic)
                        settings = store.read()
                    }
                    catch (error: Exception) { report(error) }
                }
                dragging = false; press = null; dragOrigin = null; dragTopLeft = null; dragAnchor = null
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) actions.toggle()
            }
        })
        panel.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val start = press ?: return
                val origin = dragOrigin ?: return
                val current = e.locationOnScreen
                if (start.distance(current) < 4 && !dragging) return
                dragging = true
                val candidate = Rectangle(origin.x + current.x - start.x, origin.y + current.y - start.y,
                    panel.width, panel.height)
                val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                val device = devices.find { it.defaultConfiguration.bounds.contains(current) }
                    ?: window.graphicsConfiguration.device
                val snapped = IslandPlacement.snapDrag(
                    IslandPlacement.workArea(device.defaultConfiguration), candidate, notch = settings.notch)
                dragTopLeft = snapped.topLeft
                dragAnchor = snapped.anchor
            }
        })
        // Allocate a native peer while hidden, so full-screen checks also work before first show.
        window.addNotify()
        try { menu.installTray() } catch (error: Exception) { report(error) }
        tick(); scheduleNextFrame()
    }
    fun reload() {
        if (closed) return
        settings = store.read()
        // Settings notifications may arrive repeatedly while the mouse button is held.
        // Keep the in-progress gesture until release commits its position.
        if (!dragging) { dragTopLeft = null; dragAnchor = null }
        nextScreenCheck = 0
        tick()
    }
    fun about() = menu.about()
    internal val presentedFrames: Long get() = window.presentedFrames

    private fun tick() {
        if (closed) return
        // Local optimistic edits must take effect even before the next host notification.
        settings = store.read()
        val now = System.nanoTime()
        val controlsBlocked = menu.aboutVisible
        pointerPresence?.setSuppressed(controlsBlocked)
        val dt = ((now - lastFrame) / 1_000_000_000.0).coerceIn(0.0, .1)
        lastFrame = now
        val performance = settings.performance
        val clickThrough = (settings.clickThrough || settings.autoHideOnHover) &&
            (native.supportsClickThrough || inputRegion != null)
        val snap = timeline.snapshot()
        if (nativeAvailable && now >= nextScreenCheck) {
            try { fullscreen = settings.hideFullscreen && native.foregroundIsFullscreen(window) }
            catch (error: Exception) { nativeAvailable = false; fullscreen = false; report(error) }
            nextScreenCheck = now + performance.screenCheckIntervalNs
        }
        val visible = settings.enabled && (!settings.hidePaused || snap.playing) && (!settings.hideFullscreen || !fullscreen)
        globalPointer?.setEnabled(clickThrough && settings.autoHideOnHover && visible)
        if (!visible) {
            // Avoid changing the native bounds or painting while the transparent peer is hidden.
            if (window.isVisible) window.isVisible = false
            surface.hoverPose = IslandHoverMotion.Pose.HIDDEN
            hoverVisibility.update(false, null, Rectangle(), dt, true, settings.notch)
            hoverActive = false
            pointerPresence?.reset()
            nextTopmostCheck = 0
            frameDelayMs = 200
            return
        }
        panel.settings = settings; panel.snapshot = snap
        val levels = if (!snap.playing || !settings.sideContent.showsSpectrum) FloatArray(4)
            else when (performance.spectrumMode) {
                SpectrumMode.LIVE -> if (spectrumFallback()) SyntheticSpectrum.levels(snap.positionMs) else spectrum()
                SpectrumMode.SYNTHETIC -> SyntheticSpectrum.levels(snap.positionMs)
            }
        panel.updateSpectrum(levels, dt)

        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val draggedScreen = dragTopLeft?.let { point ->
            val center = Point(point.x + width.roundToInt() / 2, point.y + height.roundToInt() / 2)
            devices.find { it.defaultConfiguration.bounds.contains(center) }
        }
        val device = draggedScreen ?: devices.find { it.iDstring == settings.screen } ?:
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val screen = IslandPlacement.workArea(device.defaultConfiguration)
        val mouse = if (globalPointer != null) {
            if (clickThrough && settings.autoHideOnHover) globalPointer.location() else null
        }
            else runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        val overIsland = mouse != null && IslandGeometry.silhouette(panel.width, panel.height, settings.notch,
            settings.cornerRoundness)
            .contains((mouse.x - window.x - panel.x).toDouble(), (mouse.y - window.y - panel.y).toDouble())
        val hoverRetention = mouse != null && Rectangle(
            window.x + panel.x - HOVER_MARGIN, window.y + panel.y - HOVER_MARGIN,
            panel.width + HOVER_MARGIN * 2, panel.height + HOVER_MARGIN * 2).contains(mouse)
        hoverActive = when {
            controlsBlocked || !window.isVisible || clickThrough -> { pointerPresence?.reset(); false }
            pointerPresence != null -> pointerPresence.active(now)
            hoverActive -> hoverRetention
            else -> overIsland
        }
        panel.expanded = !controlsBlocked && !clickThrough && (dragging || panel.progress.dragging || hoverActive)
        val visibleLines = ActiveLyrics.select(snap, settings.experimentalMultiLine)
        if (visibleLines != lastLines || snap.track != previousSnapshot?.track) {
            panel.outgoing = previousSnapshot?.takeIf { it.track == snap.track }
            panel.transition = 0.0; lastLines = visibleLines
        }
        previousSnapshot = snap
        panel.transition = if (performance.animateLayout) {
            (panel.transition + dt / .65).coerceAtMost(1.0)
        } else 1.0
        panel.prepareLyrics(visibleLines)
        val collapsed = panel.desiredSize(screen.width, false)
        val opened = panel.desiredSize(screen.width, true)
        val desired = if (panel.expanded) opened else collapsed
        val factor = if (performance.animateLayout) 1 - kotlin.math.exp(-dt * 15) else 1.0
        val expansion = expandedMotion.update(panel.expanded, dt, !performance.animateLayout, settings.notch)
        panel.expansion = expansion
        panel.controlsAnimating = expandedMotion.animating
        lyricWidth += (collapsed.width - lyricWidth) * factor
        lyricHeight += (collapsed.height - lyricHeight) * factor
        val controlsWidthTarget = (opened.width - collapsed.width).toDouble()
        controlsWidth += (controlsWidthTarget - controlsWidth) * factor
        if (abs(lyricWidth - collapsed.width) < .01) lyricWidth = collapsed.width.toDouble()
        if (abs(lyricHeight - collapsed.height) < .01) lyricHeight = collapsed.height.toDouble()
        if (abs(controlsWidth - controlsWidthTarget) < .01) controlsWidth = controlsWidthTarget
        width = lyricWidth + controlsWidth * expansion
        height = lyricHeight + IslandTextBlock.EXPANDED_HEIGHT * expansion
        panel.animatedWidth = width
        panel.animatedHeight = height
        val currentWidth = width.roundToInt()
        val currentHeight = height.roundToInt()
        val position = anchoredPosition(screen, device.iDstring, currentWidth, currentHeight)
        placementAnchor = position.second
        panel.anchor = placementAnchor
        val islandBounds = IslandPlacement.bounds(screen, position.first,
            currentWidth, currentHeight, placementAnchor)
        val hoverPadding = IslandHoverGeometry.padding(currentWidth, currentHeight, settings.notch)
        val presentationBounds = Rectangle(islandBounds.x - hoverPadding.left, islandBounds.y - hoverPadding.top,
            islandBounds.width + hoverPadding.left + hoverPadding.right,
            islandBounds.height + hoverPadding.top + hoverPadding.bottom).intersection(screen)
        val targetCanvasHeight = maxOf(currentHeight, desired.height, panel.reservedContentHeight,
            settings.fontSize * 4 + IslandTextBlock.EXPANDED_HEIGHT + 60)
        val canvasPadding = IslandHoverGeometry.padding(maxOf(settings.maxWidth, currentWidth), targetCanvasHeight, settings.notch)
        canvasWidth = maxOf(canvasWidth, settings.maxWidth + canvasPadding.left + canvasPadding.right, presentationBounds.width)
        canvasHeight = maxOf(canvasHeight, targetCanvasHeight + canvasPadding.top + canvasPadding.bottom, presentationBounds.height)
        surface.renderCapacity.setSize(canvasWidth, canvasHeight)
        val preferredCanvas = IslandPlacement.bounds(screen, IslandPlacement.anchorPoint(presentationBounds, placementAnchor),
            canvasWidth, canvasHeight, placementAnchor)
        val bounds = if (stableTranslucentCanvas) {
            IslandPlacement.stableCanvasBounds(screen, presentationBounds, preferredCanvas, window.bounds)
        } else presentationBounds
        val resized = window.width != bounds.width || window.height != bounds.height
        if (window.bounds != bounds) window.bounds = bounds
        if (resized) window.validate()
        surface.setSize(bounds.width, bounds.height)
        panel.setBounds(islandBounds.x - bounds.x, islandBounds.y - bounds.y, islandBounds.width, islandBounds.height)
        panel.prepareBuffers(window.graphicsConfiguration.defaultTransform)
        inputRegion?.let { input ->
            val scale = window.graphicsConfiguration.defaultTransform
            val shape = if (clickThrough) Rectangle() else scale.createTransformedShape(surface.inputRegion())
            input.update(window, shape, listOf(panel.bounds, settings.notch, settings.cornerRoundness,
                placementAnchor, scale.scaleX, scale.scaleY, clickThrough))
        }
        val hoverRegion = java.awt.geom.AffineTransform.getTranslateInstance(
            islandBounds.x.toDouble(), islandBounds.y.toDouble()
        ).createTransformedShape(IslandGeometry.silhouette(panel.width, panel.height, settings.notch,
            settings.cornerRoundness))
        surface.hoverPose = hoverVisibility.update(
            clickThrough && settings.autoHideOnHover, mouse, hoverRegion, dt,
            !performance.animateLayout, settings.notch)
        panel.hoverSettled = surface.hoverPose.fullyShown
        panel.updateProgressTransition(dt)
        panel.doLayout()
        if (!window.isVisible) {
            window.isVisible = true
            nextTopmostCheck = 0
        }
        if (topmostAvailable && now >= nextTopmostCheck) {
            try { native.reinforceTopmost(window) }
            catch (error: Exception) { topmostAvailable = false; report(error) }
            nextTopmostCheck = now + performance.topmostCheckIntervalNs
        }
        if (nativeAvailable && clickThroughApplied != clickThrough) {
            try { native.clickThrough(window, clickThrough); clickThroughApplied = clickThrough }
            catch (error: Exception) { nativeAvailable = false; report(error) }
        }
        if (Platform.isLinux()) {
            // The isolated Linux JVM uses XRender, and publishes one complete buffered frame.
            // Never clear the on-screen drawable or reshape it during layout animation.
            window.graphics?.let { graphics ->
                try { window.paint(graphics) } finally { graphics.dispose() }
            }
        } else surface.repaint()
        frameDelayMs = when {
            press != null -> DRAG_FRAME_DELAY_MS
            !performance.animateLayout -> performance.frameDelayMs
            hoverVisibility.animating || expandedMotion.animating || panel.progressAnimating -> performance.frameDelayMs
            !snap.playing && panel.transition >= 1 && width == desired.width.toDouble() && height == desired.height.toDouble() -> 50
            else -> performance.frameDelayMs
        }
    }

    /** Schedule from the completed EDT frame, with at most one outstanding frame. */
    private fun scheduleNextFrame() {
        if (closed) return
        frameScheduler.schedule({
            if (closed) return@schedule
            SwingUtilities.invokeLater {
                if (closed) return@invokeLater
                tick()
                scheduleNextFrame()
            }
        }, frameDelayMs.toLong(), TimeUnit.MILLISECONDS)
    }

    private fun anchoredPosition(screen: Rectangle, deviceId: String, width: Int, height: Int): Pair<Point, IslandAnchor> {
        val savedAnchor = settings.positionAnchor
        val savedX = settings.positionX
        val savedY = settings.positionY
        val topLeft = dragTopLeft
        val position = when {
            topLeft != null -> {
                val bounds = Rectangle(topLeft.x, topLeft.y, width, height)
                val locked = dragAnchor ?: placementAnchor
                IslandPlacement.anchorPoint(bounds, locked) to locked
            }
            settings.screen == deviceId && savedAnchor != null && savedX != null && savedY != null ->
                Point(savedX, savedY) to savedAnchor
            else -> {
                val centerX = settings.legacyCenterX?.takeIf { settings.screen == deviceId }
                    ?: (screen.x + screen.width / 2)
                val top = settings.legacyTop?.takeIf { settings.screen == deviceId } ?: screen.y
                val legacyBounds = Rectangle(centerX - width / 2, top, width, height)
                val automatic = IslandPlacement.automaticAnchor(screen, legacyBounds)
                IslandPlacement.anchorPoint(legacyBounds, automatic) to automatic
            }
        }
        return if (settings.notch) IslandPlacement.attachToTop(screen, position.first, position.second) else position
    }

    private fun Rectangle.centerPoint() = Point(x + width / 2, y + height / 2)

    override fun close() {
        if (closed) return
        closed = true; frameScheduler.shutdownNow(); panel.close(); menu.close()
        pointerPresence?.close(); globalPointer?.close(); inputRegion?.close(); window.dispose()
    }
}
