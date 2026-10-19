// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Event-driven volume feedback; the existing frame clock owns timeout and reversible motion. */
internal class IslandVolumeFeedback(private val adjust: (Double, (Double?) -> Unit) -> Unit,
                                    private val setVolume: (Double, (Double?) -> Unit) -> Unit,
                                    private val nanoTime: () -> Long = System::nanoTime) {
    private val motion = IslandTransitionMotion(false, 0.0,
        timing = IslandTransitionMotion.Timing(open = .22, notchClose = .22, pillClose = .22))
    private var active = false
    private var generation = 0L
    private var request = 0L
    private var lastOperation = 0L
    private var pending = false
    private var showing = false
    private var hovered = false
    private var volume = 0.0
    private var confirmedVolume = 0.0
    private var displayedVolume = 0.0
    private var queuedVolume: Double? = null
    var dragging = false
        private set
    var mix = 0.0
        private set
    private val visible get() = showing || mix > 0.0
    private val held get() = dragging || visible && hovered
    val canDrag get() = active && visible
    val blocksSeek get() = pending || visible || dragging
    val animating get() = dragging || motion.animating || mix > 0.0 && abs(displayedVolume - volume) > .0001
    val label get() = "${(volume * 100).roundToInt()}%"

    fun scroll(rotation: Double): Boolean {
        if (!active || !rotation.isFinite() || rotation == 0.0) return false
        submit { reply -> adjust((-rotation * .02).coerceIn(-1.0, 1.0), reply) }
        return true
    }

    fun hover(inside: Boolean) {
        if (hovered == inside) return
        hovered = inside
        if (!inside && visible) lastOperation = nanoTime()
    }

    fun beginDrag(fraction: Double): Boolean {
        if (!canDrag || !fraction.isFinite()) return false
        dragging = true; showing = true
        dragTo(fraction)
        return true
    }

    fun dragTo(fraction: Double) {
        if (!dragging || !fraction.isFinite()) return
        lastOperation = nanoTime()
        val target = fraction.coerceIn(0.0, 1.0)
        if (target == volume) return
        // Preview immediately; the existing frame clock coalesces host writes, including in low mode.
        request++; pending = true; queuedVolume = target
        volume = target; displayedVolume = target
    }

    fun endDrag() {
        if (!dragging) return
        flushVolume()
        dragging = false; lastOperation = nanoTime()
    }

    fun cancelDrag() {
        if (!dragging) return
        queuedVolume = null; dragging = false; pending = false
        request++; lastOperation = nanoTime()
        volume = confirmedVolume; displayedVolume = volume
    }

    private fun flushVolume() {
        val target = queuedVolume ?: return
        queuedVolume = null
        submit { reply -> setVolume(target, reply) }
    }

    private fun submit(change: ((Double?) -> Unit) -> Unit) {
        lastOperation = nanoTime()
        pending = true
        val token = generation
        val currentRequest = ++request
        change { value ->
            if (token == generation && currentRequest == request && active &&
                (held || nanoTime() - lastOperation < TIMEOUT_NS)) {
                pending = false
                if (value != null && value.isFinite()) {
                    volume = value.coerceIn(0.0, 1.0)
                    confirmedVolume = volume
                    if (mix == 0.0 || dragging) displayedVolume = volume
                    showing = true
                } else {
                    volume = confirmedVolume
                    if (dragging) displayedVolume = volume
                }
            }
        }
    }

    fun update(interactive: Boolean, dt: Double, instant: Boolean) {
        if (!interactive) {
            if (active || blocksSeek) reset()
            return
        }
        active = true
        flushVolume()
        if (held) showing = true
        else if (nanoTime() - lastOperation >= TIMEOUT_NS) {
            showing = false; pending = false
        }
        mix = motion.update(showing, dt, instant, false).coerceIn(0.0, 1.0)
        displayedVolume = if (instant || dragging) volume else
            displayedVolume + (volume - displayedVolume) * (1.0 - exp(-dt * 22))
        if (abs(displayedVolume - volume) < .0001) displayedVolume = volume
    }

    fun fraction(playback: Double) = (playback + (displayedVolume - playback) * mix).coerceIn(0.0, 1.0)

    fun reset() {
        generation++
        active = false; pending = false; showing = false; hovered = false; dragging = false
        queuedVolume = null
        mix = motion.update(false, 0.0, true, false)
    }

    companion object { private const val TIMEOUT_NS = 2_000_000_000L }
}
