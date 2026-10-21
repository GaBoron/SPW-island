// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.host

import com.xuncorp.spw.workshop.api.WorkshopApi
import io.github.gaboron.spwisland.diagnostics.DiagnosticExport
import io.github.gaboron.spwisland.diagnostics.IslandLog
import java.util.concurrent.atomic.AtomicBoolean

/** Settings entry points remain usable after window/runtime creation has failed. */
internal object HostDiagnosticActions {
    private val exporting = AtomicBoolean()
    fun openLogs() = launch("open_directory") { DiagnosticExport.openDirectory() }
    fun export() {
        if (!exporting.compareAndSet(false, true)) return
        launch("export") {
            try {
                WorkshopApi.ui.toast("正在导出诊断包。异常堆栈可能含有隐私信息，分享前请先检查。",
                    WorkshopApi.Ui.ToastType.Warning)
                val archive = DiagnosticExport.create()
                WorkshopApi.ui.toast("诊断包已保存为 ${archive.fileName}。分享前请检查异常堆栈中可能包含的隐私信息。",
                    WorkshopApi.Ui.ToastType.Warning)
                IslandLog.attempt("diagnostics", "open_export_directory.failed") { DiagnosticExport.open(archive.parent) }
            } finally { exporting.set(false) }
        }
    }
    private fun launch(name: String, action: () -> Unit) {
        IslandLog.thread("SPW Island diagnostics $name") {
            IslandLog.event("diagnostics", "$name.requested")
            IslandLog.attempt("diagnostics", "$name.failed", action).onFailure { error ->
                IslandLog.attempt("diagnostics", "toast.failed") {
                    WorkshopApi.ui.toast("日志操作失败：${error.message}", WorkshopApi.Ui.ToastType.Error)
                }
            }
        }.start()
    }
}
