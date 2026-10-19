# 第三方许可与署名

## 灵动词岛

“灵动词岛 / Dynamic Lyrics Island”原创为 **Lyricify / WXRIW（XY Wang）**，首次创作于 2022-09-30。
[原始声明](https://github.com/WXRIW/Lyricify-App#lyricify-原创)将这项创作以 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) 许可。

本项目是面向 SPW 的独立改编：重新实现播放接入、文字塑形与绘制、逐字高亮、窗口伸缩、播放控制、设置和 Windows 悬浮窗口行为。视觉和交互改编继续采用 CC BY-SA 4.0；保留署名、原始来源、许可链接和改动说明，不暗示原作者认可或参与本项目。没有复制或分发 Lyricify 应用程序、标志、截图或未公开源码。

新增程序代码采用 [GPL-3.0-only](LICENSE)。Creative Commons 将 GPLv3 列为 [CC BY-SA 4.0 的单向兼容许可](https://creativecommons.org/compatible-licenses/)，因此代码贡献采用 GPLv3 不会替换原始创作的 CC BY-SA 许可。视觉设计及本文等文档按 CC BY-SA 4.0 提供。完整文本见 [CC-BY-SA-4.0.txt](licenses/CC-BY-SA-4.0.txt)。

## AMLL 歌词动画

[Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics)，AMLL contributors（包括 Steve-xmh），采用 [AGPL-3.0](licenses/AGPL-3.0.txt)。参考版本为 `58ccd3ffae7ec4e9a6d1cdb0dd88ac8c767f68a8`。

2026-09-04 将其 `packages/core/src/lyric-player/dom/lyric-line.ts` 中的逐字抬升、长音强调曲线、持续时间缩放和末词增强移植到 `AmllMotion.kt`，改用 Kotlin 按播放时间采样，并接入 Java2D 字形绘制与单行换行动画。未嵌入 AMLL 的 DOM、WebGL 或第三方弹簧实现。

`AmllMotion.kt` 保持 **AGPL-3.0-only**，其他原有程序文件保留 **GPL-3.0-only**。依据 GPLv3 与 AGPLv3 的第 13 条组合分发，同时保留两份许可和完整对应源码；这不改变 Lyricify 原创的 CC BY-SA 4.0 许可。详见 [NOTICE](NOTICE) 中的来源、改动日期及组合说明。

## 依赖与构建工具

[AndroidLiquidGlass / Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)，Copyright 2025 Kyant，采用 [Apache-2.0](licenses/Apache-2.0.txt)。参考提交 `65ab177e90e5c1d8c62e70cf7755841982da65f6`；2026-10-15 将 `internal/Shaders.kt` 的 Lens、七采样色散与 Default Highlight 移植到 `IslandGlassShaders.kt`，并在 Skia 中复现 `ColorFilter.kt`、`HighlightModifier.kt` 的 Vibrancy、滤镜顺序及独立 PLUS 高光合成。使用真实 Windows 桌面裁片；轮廓距离与法线适配原有动态 Path，限制色散并减弱背光侧，未引入 Compose 组件。原始声明与改动见 [NOTICE](NOTICE)。

| 项目 | 使用范围 | 许可与保留材料 |
| --- | --- | --- |
| [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api)，Moriafly | 编译使用 0.1.0-dev23；由 SPW 提供，不装入插件 | Apache-2.0；[完整文本](licenses/Apache-2.0.txt)，署名见 [NOTICE](NOTICE) |
| [SPW API example](https://github.com/Moriafly/spw-workshop-api/tree/0.1.0-dev20/example) | 改编 Gradle 插件 ZIP 布局与清单配置 | Apache-2.0；改动见 NOTICE |
| [JNA / JNA Platform](https://github.com/java-native-access/jna/tree/5.17.0)，JNA contributors | 编译参考 5.17.0，运行复用 SPW 自带版本，不重复打包；用于 Windows 窗口能力和 Linux X11 输入区域 | JNA 双许可中选择 Apache-2.0；保留 [JNA LICENSE](licenses/JNA-LICENSE.txt)、[第三方声明](licenses/JNA-THIRD-PARTY.txt) 与 [libffi 许可](licenses/JNA-libffi-LICENSE.txt) |
| [Gson](https://github.com/google/gson/tree/gson-parent-2.11.0)，Google | 随包提供 2.11.0；配置 JSON 的读取与序列化 | Apache-2.0；保留 JAR 内声明及 [完整文本](licenses/Apache-2.0.txt) |
| [Jaudiotagger](https://www.jthink.net/jaudiotagger/)，Paul Taylor 与 contributors | 随包提供未修改的 3.0.1，仅读取本地音频时长与内嵌封面；独立 JAR 可替换 | LGPL-2.1-or-later；附带 [许可全文](licenses/LGPL-2.1.txt) 与 `source/jaudiotagger-3.0.1-sources.jar` |
| [MiSans](https://hyperos.mi.com/font)，Xiaomi | Windows/Linux 默认歌词、插件界面、Windows 右键菜单与字体窗口预览使用内置 MiSans VF | MiSans 字体许可；附带 [许可文件](licenses/MiSans-License.pdf) |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)，JetBrains / Android Open Source Project | Windows 字体窗口使用 Compose Desktop 1.12.1；随统一包提供运行库 | Apache-2.0；保留 JAR 内声明与 [完整文本](licenses/Apache-2.0.txt) |
| [Skiko](https://github.com/JetBrains/skiko/tree/v0.150.1)，JetBrains | 两平台词岛使用 0.150.1，Windows 字体窗口共用；随包提供 Windows x64 与 Linux x64 原生库 | Apache-2.0；[完整文本](licenses/Apache-2.0.txt) 与 [Skiko NOTICE](licenses/Skiko-NOTICE.txt) |
| [Skia](https://skia.org/)，Google 与 contributors | Skiko 原生库中的绘制引擎 | BSD-3-Clause；附带 [许可全文](licenses/Skia-LICENSE.txt) |
| [Kotlin / kotlinx.coroutines](https://github.com/JetBrains/kotlin)，JetBrains | 编译工具、标准库与绘制调度运行库；随包提供，Linux 独立 JVM 使用插件自带版本 | Apache-2.0；[完整文本](licenses/Apache-2.0.txt) |
| [PF4J](https://github.com/pf4j/pf4j)，Decebal Suiu | 编译使用 3.12.0，由宿主提供 | Apache-2.0 |
| [Gradle](https://github.com/gradle/gradle)，Gradle contributors | Wrapper 来自 SPW API 0.1.0-dev20，构建使用 9.8.0 | Apache-2.0；Wrapper 自带声明保留，发行包不内嵌 Gradle 发行版 |

运行时所用 Windows 和 Java 由其原供应方提供，遵循各自条款。插件不附带音乐、封面或歌词内容，也不改变这些内容的权利归属。

## 分发

插件 `.spmod` 内含 LICENSE、NOTICE、本文件、licenses 目录及 source 目录中的对应完整源码压缩包（含构建脚本与 Wrapper）。转发二进制时保留这些材料；修改后重新构建也应提供对应源码，保留署名并说明改动。不得对 CC BY-SA 内容追加限制该许可权利的条款或技术措施。
