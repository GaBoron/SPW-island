// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import com.sun.jna.Platform
import io.github.gaboron.spwisland.core.*

enum class IslandStatusGroup(val title: String) { PLAYBACK("播放与显示"), RENDERING("渲染与材质"), INTERACTION("音频与交互") }

data class IslandStatusEntry(val title: String, val summary: String, val detail: String,
                           val warning: Boolean = false, val group: IslandStatusGroup = IslandStatusGroup.INTERACTION)

/** Explain the actual display policy and data source, including disabled and waiting states. */
internal object IslandRuntimeStatus {
    val environment: String = if (Platform.isLinux()) {
        val desktops = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().split(':')
        val desktop = when {
            desktops.any { it.equals("GNOME", true) } -> "GNOME"
            desktops.any { it.equals("KDE", true) } -> "KDE Plasma"
            else -> "Linux"
        }
        val session = if (System.getenv("XDG_SESSION_TYPE") == "wayland" ||
            !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()) "Wayland" else "X11"
        "$desktop · $session"
    } else if (Platform.isWindows()) "Windows" else System.getProperty("os.name")

    fun describe(settings: IslandSettings, snapshot: PlaybackSnapshot, visible: Boolean,
                 hoverHidden: Boolean, fullscreen: Boolean, inputAvailable: Boolean,
                 pointerReady: Boolean, pointerStatus: String, fallback: Boolean,
                 spectrumStatus: String, gpuActive: Boolean = false,
                 gpuFallback: Boolean = false, glassStatus: GlassStatus = GlassStatus("等待桌面捕获")): List<IslandStatusEntry> = buildList {
        add(display(settings, snapshot, visible, hoverHidden, fullscreen))
        add(lyrics(settings, snapshot))
        add(IslandStatusEntry("性能模式", if (settings.lowPerformance) "低性能模式" else "标准模式",
            if (settings.lowPerformance) "降低刷新率与动效，使用模拟频谱。" else "完整动效，支持实时频谱。",
            group = IslandStatusGroup.RENDERING))
        add(rendering(settings, gpuActive, gpuFallback))
        addAll(material(settings, gpuFallback, glassStatus))
        add(spectrum(settings, snapshot, fallback, spectrumStatus))
        add(interaction(settings, visible, inputAvailable, pointerReady, pointerStatus))
    }

    private fun lyrics(settings: IslandSettings, snapshot: PlaybackSnapshot): IslandStatusEntry {
        val line = snapshot.line
        val timing = LyricWordTiming.resolve(line, snapshot.usesWordTiming, settings.forceKaraoke)
        val (summary, detail) = when {
            !settings.karaoke -> "逐行显示" to "逐字高亮已关闭。"
            line == null -> "等待歌词" to "读取歌词后更新显示方式。"
            timing.estimated -> "强制逐字" to "按行时长估算进度，不应用长音发光。"
            timing.words.isNotEmpty() -> "原生逐字" to "使用歌词自带的时间轴。"
            else -> "逐行显示" to "当前歌词没有可用的逐字时间轴。"
        }
        return IslandStatusEntry("歌词高亮", summary, detail, group = IslandStatusGroup.PLAYBACK)
    }

    private fun rendering(settings: IslandSettings, active: Boolean, fallback: Boolean): IslandStatusEntry {
        val (summary, detail) = when {
            !settings.enabled -> "待启用" to "显示词岛后启用绘制。"
            fallback -> "兼容绘制" to "GPU 不可用，重启插件后重试。"
            active -> "GPU 绘制" to ""
            else -> "初始化中" to "等待窗口初始化。"
        }
        return IslandStatusEntry("词岛绘制", summary, detail, settings.enabled && fallback, IslandStatusGroup.RENDERING)
    }

    private fun material(settings: IslandSettings, fallback: Boolean, glass: GlassStatus): List<IslandStatusEntry> {
        val (summary, detail) = when {
            settings.backgroundStyle != IslandBackgroundStyle.LIQUID_GLASS -> "经典背景" to ""
            !Platform.isWindows() -> "经典背景" to "当前平台不支持液态玻璃。"
            settings.lowPerformance -> "经典背景" to "低性能模式停用液态玻璃。"
            !settings.enabled -> "液态玻璃 · 待启用" to "显示词岛后开始桌面捕获。"
            fallback -> "经典背景" to "词岛液态玻璃需要 GPU 绘制。"
            else -> "液态玻璃 · ${glass.summary}" to glass.detail
        }
        val capturing = settings.backgroundStyle == IslandBackgroundStyle.LIQUID_GLASS && Platform.isWindows() &&
            !settings.lowPerformance && settings.enabled && !fallback
        return buildList {
            add(IslandStatusEntry("背景材质", summary, detail,
                capturing && glass.warning, IslandStatusGroup.RENDERING))
            if (capturing && glass.framesPerSecond != null) add(IslandStatusEntry("桌面捕获",
                "${glass.framesPerSecond} fps · 原生处理 ${glass.nativeTimeMs} ms",
                "处理耗时不含界面等待与绘制。玻璃窗口从系统录屏中排除。",
                group = IslandStatusGroup.RENDERING))
        }
    }

    private fun display(s: IslandSettings, p: PlaybackSnapshot, visible: Boolean, hoverHidden: Boolean,
                        fullscreen: Boolean): IslandStatusEntry {
        val (summary, detail) = when {
            !s.enabled -> "已关闭" to "从托盘开启“显示词岛”。"
            s.hidePaused && !p.playing -> "暂停时隐藏" to "恢复播放后显示。"
            s.hideFullscreen && fullscreen -> "全屏时隐藏" to "退出全屏后显示。"
            s.autoHideOnHover && hoverHidden -> "悬停时隐藏" to "鼠标移开后显示。"
            p.track == null -> "等待歌曲" to "播放歌曲后更新。"
            !visible -> "准备显示" to "正在初始化窗口。"
            else -> "显示中" to "${if (p.playing) "正在播放" else "已暂停"}：${p.track.title}" +
                p.track.artist.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        }
        return IslandStatusEntry("词岛显示", summary, detail, group = IslandStatusGroup.PLAYBACK)
    }

    private fun spectrum(s: IslandSettings, p: PlaybackSnapshot, fallback: Boolean, source: String): IslandStatusEntry = when {
        !s.sideContent.showsSpectrum -> IslandStatusEntry("音频频谱", "未显示", "未选择频谱，音频采集已停用。")
        !s.enabled -> IslandStatusEntry("音频频谱", "已停用", "词岛关闭，音频采集已停用。")
        !p.playing -> IslandStatusEntry("音频频谱", "等待播放", "暂停时归零，播放后恢复。")
        s.lowPerformance -> IslandStatusEntry("音频频谱", "模拟频谱", "按播放位置生成动画。")
        fallback -> IslandStatusEntry("音频频谱", "暂用模拟频谱",
            source.removeSuffix("（使用模拟频谱）").removeSuffix("（暂用模拟频谱）").ifBlank { "实时音频暂不可用。" } +
                when {
                    source.contains("缺少") && listOf("pw-record", "pw-dump", "PipeWire").any(source::contains) ->
                        "。安装 PipeWire 工具后重启插件。"
                    source.contains(".NET Framework") -> "。修复 .NET Framework 4.8 后重启 SPW。"
                    else -> "。音频恢复后重连。"
                }, true)
        source.contains("不可用") || source.contains("缺少") ->
            IslandStatusEntry("音频频谱", "实时频谱异常", source + "。检查音频输出或重启插件。", true)
        source.isBlank() || source.contains("正在") || source.contains("等待") || source.contains("未启用") ->
            IslandStatusEntry("音频频谱", "连接中", "等待 SPW 音频。")
        else -> IslandStatusEntry("音频频谱", "SPW 实时频谱", "SPW 音频 · 四频段能量")
    }

    private fun interaction(s: IslandSettings, visible: Boolean, inputAvailable: Boolean,
                            pointerReady: Boolean, pointerStatus: String): IslandStatusEntry = when {
        (s.clickThrough || s.autoHideOnHover) && !inputAvailable -> IslandStatusEntry("鼠标交互", "穿透暂不可用",
            "当前窗口仍接收鼠标操作。", true)
        s.autoHideOnHover && !visible -> IslandStatusEntry("鼠标交互", "悬停隐藏已开启", "显示后启用检测与穿透。")
        s.autoHideOnHover && !pointerReady -> IslandStatusEntry("鼠标交互", "悬停隐藏待连接",
            if (pointerStatus.contains("GNOME") && pointerStatus.contains("扩展"))
                "在插件设置安装 GNOME 支持，首次安装后重新登录。"
            else pointerStatus.takeUnless { it.startsWith("悬停隐藏：") }
                .orEmpty().ifBlank { "指针不可用，等待重连。" }, true)
        s.autoHideOnHover -> IslandStatusEntry("鼠标交互", "穿透与悬停隐藏", "经过时隐藏，点击交给下方窗口。")
        s.clickThrough -> IslandStatusEntry("鼠标交互", "鼠标穿透", "点击交给下方窗口。")
        else -> IslandStatusEntry("鼠标交互", "正常交互", "悬停展开，可拖动位置与进度。")
    }
}
