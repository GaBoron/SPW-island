# 第三方许可与署名

## 灵动词岛

“灵动词岛 / Dynamic Lyrics Island”原创为 **Lyricify / WXRIW（XY Wang）**，首次创作于 2022-09-30。
[原始声明](https://github.com/WXRIW/Lyricify-App#lyricify-原创)将这项创作以 [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) 许可。

本项目是面向 SPW 的独立改编：重新实现播放接入、Java2D 文字绘制、逐字高亮、窗口伸缩、播放控制、设置和 Windows 悬浮窗口行为。视觉和交互改编继续采用 CC BY-SA 4.0；保留署名、原始来源、许可链接和改动说明，不暗示原作者认可或参与本项目。没有复制或分发 Lyricify 应用程序、标志、截图或未公开源码。

新增程序代码采用 [GPL-3.0-only](LICENSE)。Creative Commons 将 GPLv3 列为 [CC BY-SA 4.0 的单向兼容许可](https://creativecommons.org/compatible-licenses/)，因此代码贡献采用 GPLv3 不会替换原始创作的 CC BY-SA 许可。视觉设计及本文等文档按 CC BY-SA 4.0 提供。完整文本见 [CC-BY-SA-4.0.txt](licenses/CC-BY-SA-4.0.txt)。

## AMLL 歌词动画

[Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics)，AMLL contributors（包括 Steve-xmh），采用 [AGPL-3.0](licenses/AGPL-3.0.txt)。参考版本为 `58ccd3ffae7ec4e9a6d1cdb0dd88ac8c767f68a8`。

2026-09-04 将其 `packages/core/src/lyric-player/dom/lyric-line.ts` 中的逐字抬升、长音强调曲线、持续时间缩放和末词增强移植到 `AmllMotion.kt`，改用 Kotlin 按播放时间采样，并接入 Java2D 字形绘制与单行换行动画。未嵌入 AMLL 的 DOM、WebGL 或第三方弹簧实现。

`AmllMotion.kt` 保持 **AGPL-3.0-only**，其他原有程序文件保留 **GPL-3.0-only**。依据 GPLv3 与 AGPLv3 的第 13 条组合分发，同时保留两份许可和完整对应源码；这不改变 Lyricify 原创的 CC BY-SA 4.0 许可。详见 [NOTICE](NOTICE) 中的来源、改动日期及组合说明。

## 依赖与构建工具

| 项目 | 使用范围 | 许可与保留材料 |
| --- | --- | --- |
| [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api)，Moriafly | 编译使用 0.1.0-dev20；由 SPW 提供，不装入插件 | Apache-2.0；[完整文本](licenses/Apache-2.0.txt)，署名见 [NOTICE](NOTICE) |
| [SPW API example](https://github.com/Moriafly/spw-workshop-api/tree/0.1.0-dev20/example) | 改编 Gradle 插件 ZIP 布局与清单配置 | Apache-2.0；改动见 NOTICE |
| [JNA / JNA Platform](https://github.com/java-native-access/jna/tree/5.17.0)，JNA contributors | 随包提供 5.17.0；Windows 鼠标穿透与全屏判断 | JNA 双许可中选择 Apache-2.0；保留 [JNA LICENSE](licenses/JNA-LICENSE.txt)、[第三方声明](licenses/JNA-THIRD-PARTY.txt)、[libffi 许可](licenses/JNA-libffi-LICENSE.txt) 与 JAR 内原始许可 |
| [Gson](https://github.com/google/gson/tree/gson-parent-2.11.0)，Google | 随包提供 2.11.0；独立解析配置文件及原子保存 | Apache-2.0；保留 JAR 内声明及 [完整文本](licenses/Apache-2.0.txt) |
| [Jaudiotagger](https://www.jthink.net/jaudiotagger/)，Paul Taylor 与 contributors | 随包提供未修改的 3.0.1，仅读取本地音频时长与内嵌封面；独立 JAR 可替换 | LGPL-2.1-or-later；附带 [许可全文](licenses/LGPL-2.1.txt) 与 `source/jaudiotagger-3.0.1-sources.jar` |
| [MiSans](https://hyperos.mi.com/font)，Xiaomi | Windows/Linux 默认歌词、插件界面、Windows 右键菜单与字体窗口预览使用内置 MiSans VF | MiSans 字体许可；附带 [许可文件](licenses/MiSans-License.pdf) |
| [Kotlin](https://github.com/JetBrains/kotlin)，JetBrains | 编译工具和宿主标准库，不装入插件 | Apache-2.0 |
| [PF4J](https://github.com/pf4j/pf4j)，Decebal Suiu | 编译使用 3.12.0，由宿主提供 | Apache-2.0 |
| [Gradle](https://github.com/gradle/gradle)，Gradle contributors | Wrapper 来自 SPW API 0.1.0-dev20，构建使用 9.2.1 | Apache-2.0；Wrapper 自带声明保留，发行包不内嵌 Gradle 发行版 |

运行时所用 Windows 和 Java 由其原供应方提供，遵循各自条款。插件不附带音乐、封面或歌词内容，也不改变这些内容的权利归属。

## 分发

插件 ZIP 内含 LICENSE、NOTICE、本文件、licenses 目录及 source 目录中的对应完整源码压缩包（含构建脚本与 Wrapper）。转发二进制时保留这些材料；修改后重新构建也应提供对应源码，保留署名并说明改动。不得对 CC BY-SA 内容追加限制该许可权利的条款或技术措施。
