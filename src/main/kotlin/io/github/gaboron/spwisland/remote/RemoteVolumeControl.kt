// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.remote

/** UI-thread callbacks for on-demand host volume commands; only the newest feedback is needed. */
internal class RemoteVolumeControl(private val send: (String, Double) -> Long) {
    private var requestId = 0L
    private var completed: ((Double?) -> Unit)? = null

    fun adjust(delta: Double, reply: (Double?) -> Unit) = change("volume", delta, reply)
    fun set(value: Double, reply: (Double?) -> Unit) = change("set_volume", value, reply)

    private fun change(action: String, value: Double, reply: (Double?) -> Unit) {
        requestId = send(action, value)
        completed = reply
    }

    fun accept(result: IslandVolumeResult) {
        if (result.requestId != requestId) return
        val reply = completed
        completed = null
        reply?.invoke(result.value)
    }
}
