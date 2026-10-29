# 第三方许可与署名

本文件汇总第三方作品、依赖和相关分发要求。详细版权与改动记录见 [NOTICE](NOTICE)，许可全文见 [licenses/](licenses/)。

## 改编来源

### 灵动词岛（Lyricify）

“灵动词岛 / Dynamic Lyrics Island”由 **Lyricify / WXRIW（XY Wang）** 于 2022-09-30 首次创作，原始声明采用 [CC BY-SA 4.0](licenses/CC-BY-SA-4.0.txt)：[原始来源与署名](https://github.com/WXRIW/Lyricify-App#lyricify-原创)。

本项目独立实现了面向 SPW 的播放接入、歌词绘制、动画、窗口交互与设置。视觉及交互改编继续采用 CC BY-SA 4.0；新增程序代码采用 [GPL-3.0-only](LICENSE)，许可兼容性见 [Creative Commons 官方列表](https://creativecommons.org/compatible-licenses/)。保留原始署名、来源和改动声明；本项目未经原作者认可或赞助，也未分发 Lyricify 程序、标志、截图或非公开源码。

### AMLL 歌词动画

[Apple Music-like Lyrics](https://github.com/amll-dev/applemusic-like-lyrics)，AMLL contributors（包括 Steve-xmh），来源提交 `58ccd3ffae7ec4e9a6d1cdb0dd88ac8c767f68a8`。

2026-09-04 从 `packages/core/src/lyric-player/dom/lyric-line.ts` 移植逐字抬升、长音强调等算法至 Kotlin `AmllMotion.kt`，并适配词岛绘制与时间轴，未嵌入 AMLL DOM、WebGL 或弹簧库。

`AmllMotion.kt` 采用 **AGPL-3.0-only**（[许可全文](licenses/AGPL-3.0.txt)），其他原有程序文件保留 **GPL-3.0-only**。依据 GPLv3 和 AGPLv3 第 13 条组合分发，随包提供两份许可与对应源码；不改变 Lyricify 原创的 CC BY-SA 4.0 许可。

### AndroidLiquidGlass / Backdrop

[AndroidLiquidGlass / Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)，Copyright 2025 Kyant，来源提交 `65ab177e90e5c1d8c62e70cf7755841982da65f6`，采用 [Apache-2.0](licenses/Apache-2.0.txt)。

2026-10-15 参考 Lens、色散、Vibrancy 与高光合成实现 Windows `IslandGlassShaders.kt` 等组件；2026-10-29 将相关光学效果适配到 GNOME `gnome-glass/glass.glsl`。未引入原项目的 Compose 组件，具体修改见 [NOTICE](NOTICE)。GNOME 扩展包同样附带相应 NOTICE 与 Apache-2.0 许可。

## 依赖与构建工具

| 项目 | 用途及分发 | 许可证与保留材料 |
| --- | --- | --- |
| [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api)，Moriafly | 宿主 API（0.1.0-dev23），由 SPW 提供 | Apache-2.0；[许可文本](licenses/Apache-2.0.txt)、[NOTICE](NOTICE) |
| [SPW API example](https://github.com/Moriafly/spw-workshop-api/tree/0.1.0-dev20/example) | 改编构建布局及清单 | Apache-2.0；改动见 [NOTICE](NOTICE) |
| [JNA / JNA Platform](https://github.com/java-native-access/jna/tree/5.17.0)，JNA contributors | 原生窗口与 Linux X11 接入，运行时使用 SPW 版本 | 双许可中选 Apache-2.0；[LICENSE](licenses/JNA-LICENSE.txt)、[第三方声明](licenses/JNA-THIRD-PARTY.txt)、[libffi 许可](licenses/JNA-libffi-LICENSE.txt) |
| [Gson](https://github.com/google/gson/tree/gson-parent-2.11.0)，Google | JSON 配置；随包提供 | Apache-2.0；[许可文本](licenses/Apache-2.0.txt) |
| [Jaudiotagger](https://www.jthink.net/jaudiotagger/)，Paul Taylor 与 contributors | 本地音频元数据；随包提供未修改的 3.0.1 JAR | LGPL-2.1-or-later；[许可全文](licenses/LGPL-2.1.txt)、`source/jaudiotagger-3.0.1-sources.jar` |
| [MiSans](https://hyperos.mi.com/font)，Xiaomi | 内置歌词及界面字体 | [MiSans 字体许可](licenses/MiSans-License.pdf) |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)，JetBrains / Android Open Source Project | Windows 字体窗口；随包提供 | Apache-2.0；[许可文本](licenses/Apache-2.0.txt) |
| [Skiko](https://github.com/JetBrains/skiko/tree/v0.150.1)，JetBrains | 跨平台绘制与原生库；随包提供 | Apache-2.0；[许可文本](licenses/Apache-2.0.txt)、[Skiko NOTICE](licenses/Skiko-NOTICE.txt) |
| [Skia](https://skia.org/)，Google 与 contributors | Skiko 使用的绘制引擎 | BSD-3-Clause；[许可全文](licenses/Skia-LICENSE.txt) |
| [Kotlin / kotlinx.coroutines](https://github.com/JetBrains/kotlin)，JetBrains | 编译和运行库；随包提供 | Apache-2.0；[许可文本](licenses/Apache-2.0.txt) |
| [PF4J](https://github.com/pf4j/pf4j)，Decebal Suiu | 插件框架，由宿主提供 | Apache-2.0 |
| [Gradle](https://github.com/gradle/gradle)，Gradle contributors | 构建及 Wrapper，插件不附带 Gradle 发行版 | Apache-2.0；Wrapper 声明与改动见 [NOTICE](NOTICE) |

Windows、Java 等运行环境由其供应方提供，遵循各自条款。插件不附带音乐、封面或歌词内容。

## 分发

插件 `.spmod` 包含 `LICENSE`、`NOTICE`、本文件、`licenses/` 及 `source/` 中的对应源码和构建材料。重新分发或修改时，应保留适用的署名、许可证和修改说明，并按许可提供对应源码；不得对 CC BY-SA 内容增加限制其许可权利的条款或技术措施。
