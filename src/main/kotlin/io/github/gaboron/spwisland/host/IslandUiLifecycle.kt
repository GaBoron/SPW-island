// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import io.github.gaboron.spwisland.ui.IslandWindow
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** SPW holds its plugin manager monitor during lifecycle calls; never wait for its EDT. */
internal class IslandUiLifecycle(
    private val create: () -> IslandWindow,
    private val report: (Throwable) -> Unit,
    private val post: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) }
) : AutoCloseable {
    private val closed = AtomicBoolean()
    // Accessed only by posted EDT tasks.
    private var started = false
    private var window: IslandWindow? = null

    fun start() = post {
        if (!closed.get() && !started) {
            started = true
            try {
                val created = create()
                window = created
                if (closed.get()) dispose()
            } catch (error: Throwable) {
                report(IllegalStateException("词岛界面启动失败", error))
            }
        }
    }

    fun reload() = post { if (!closed.get()) window?.reload() }
    fun about() = post { if (!closed.get()) window?.about() }

    override fun close() {
        if (closed.compareAndSet(false, true)) post { dispose() }
    }

    private fun dispose() {
        val previous = window
        window = null
        try { previous?.close() } catch (error: Throwable) { report(error) }
    }
}
