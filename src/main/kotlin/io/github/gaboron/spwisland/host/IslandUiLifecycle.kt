// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import io.github.gaboron.spwisland.diagnostics.IslandLog

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

    fun start() {
        val operation = IslandLog.operation("window", "create")
        dispatch {
            operation.runningHere()
            if (!closed.get() && !started) {
                started = true
                try {
                    val created = create()
                    window = created
                    operation.close()
                    if (closed.get()) dispose()
                } catch (error: Throwable) {
                    operation.failed(error)
                    report(IllegalStateException("词岛界面启动失败", error))
                }
            } else {
                IslandLog.event("window", "create.cancelled")
                operation.cancel()
            }
        }
    }

    private fun dispatch(action: () -> Unit) = post {
        try { action() } catch (error: Throwable) {
            IslandLog.failure("window", "edt.operation_failed", error); report(error)
        }
    }
    fun reload() = dispatch { if (!closed.get()) window?.reload() }
    fun about() = dispatch { if (!closed.get()) window?.about() }

    override fun close() {
        if (closed.compareAndSet(false, true)) dispatch { dispose() }
    }

    private fun dispose() {
        val previous = window
        window = null
        val operation = IslandLog.operation("window", "dispose")
        try { previous?.close(); operation.close() } catch (error: Throwable) {
            operation.failed(error); report(error)
        }
    }
}
