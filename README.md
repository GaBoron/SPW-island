<p align="center">
  <img src="docs/assets/README-hero.png" width="320" alt="灵动词岛预览">
</p>

<h1 align="center">🎵 灵动词岛 for SPW</h1>

<p align="center">让 Salt Player 的歌词成为桌面的一部分。<br>逐字高亮、流畅动效、液态玻璃与轻巧的播放交互。</p>

<p align="center">
  <a href="https://github.com/GaBoron/SPW-island/releases/latest"><img alt="最新版本" src="https://img.shields.io/github/v/release/GaBoron/SPW-island?style=flat-square&logo=github&label=Release"></a>
  <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=3821553308"><img alt="Steam 创意工坊" src="https://img.shields.io/badge/Steam-创意工坊-1B2838?style=flat-square&logo=steam&logoColor=white"></a>
  <a href="docs/compatibility.md"><img alt="Windows 10 / 11" src="https://img.shields.io/badge/Windows-10%20%2F%2011-0078D4?style=flat-square&logo=windows&logoColor=white"></a>
  <a href="docs/compatibility.md"><img alt="Linux 实验性支持" src="https://img.shields.io/badge/Linux-实验性支持-FCC624?style=flat-square&logo=linux&logoColor=black"></a>
  <a href="docs/installation.md"><img alt="SPW 1.19.0+" src="https://img.shields.io/badge/SPW-1.19.0%2B-6750A4?style=flat-square"></a>
  <a href="LICENSE"><img alt="GPL-3.0 与 AGPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0%20%2B%20AGPL--3.0-blue?style=flat-square"></a>
</p>

<p align="center">
  <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=3821553308">🎮 创意工坊</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/GaBoron/SPW-island/releases/latest">📦 下载插件</a>
  &nbsp;·&nbsp;
  <a href="docs/README.md">📖 使用文档</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/GaBoron/SPW-island/issues">💬 问题反馈</a>
</p>

## ✨ 功能亮点

| | |
| --- | --- |
| 🎤 **逐字歌词**<br>逐字高亮、翻译、长音强调与 AMLL 风格动效，普通 LRC 也可估算逐字进度。 | 🫧 **液态玻璃**<br>实时模糊、折射、色散与高光；实验性支持 Windows 11 SDR 和 GNOME 50。 |
| 🎨 **自由外观**<br>胶囊 / 顶部刘海、圆角、字体、封面取色和背景播放进度。 | ⏯️ **播放控制**<br>悬停展开上一首、播放 / 暂停、下一首、进度条与音量控制。 |
| 🎶 **实时频谱**<br>四频段音乐可视化，支持频谱取色；Windows 与 Linux 均可使用。 | 🖱️ **桌面交互**<br>九宫格吸附、鼠标穿透、悬停自动隐藏和低性能模式。 |

<sub>功能会因操作系统和桌面环境有所不同，详见 [兼容性与限制](docs/compatibility.md)。</sub>

## 🧩 平台支持

| 平台 | 支持情况 | 特别说明 |
| --- | --- | --- |
| 🪟 **Windows 10 / 11 x64** | ✅ 主要支持 | Windows 11 SDR 可使用液态玻璃 |
| 🐧 **Linux x64** | 🧪 实验性支持 | GNOME 50 可使用液态玻璃；X11、KDE 与 GNOME Wayland 提供对应的悬停隐藏支持 |
| 🍎 **macOS** | ➖ 暂不支持 | 暂无适配计划 |

## 📦 快速安装

| 安装方式 | 操作 |
| --- | --- |
| <img src="https://cdn.simpleicons.org/steam/66C0F4" width="16" alt=""> **Steam 创意工坊** | [订阅灵动词岛](https://steamcommunity.com/sharedfiles/filedetails/?id=3821553308) |
| 📥 **手动安装** | 从 [Releases](https://github.com/GaBoron/SPW-island/releases/latest) 下载 `spw-island-<版本>.spmod`，在 SPW 插件管理中导入并启用 |

需要支持 Workshop API dev23 的 **SPW 1.19.0+**，以及宿主提供的 **Java 25+**。Windows 和 Linux 使用同一个插件包。

首次使用建议播放一首 SPW 已加载歌词的歌曲。更多步骤见 [安装与更新](docs/installation.md)。

## 📚 文档导航

| | |
| --- | --- |
| 📥 [安装与更新](docs/installation.md) | ⚙️ [使用与设置](docs/usage.md) |
| 🧩 [兼容性与限制](docs/compatibility.md) | 🛠️ [故障排查](docs/troubleshooting.md) |
| 💻 [开发指南](docs/development.md) | 🤝 [贡献指南](CONTRIBUTING.md) |

## 🔗 相关项目与交流

- **[SPW-Lyrics](https://github.com/GaBoron/SPW-Lyrics)**：为 SPW 获取和管理歌词，可与灵动词岛搭配使用。灵动词岛自身不会联网搜索歌词。
- **QQ 交流群**：`1054809039`
- **反馈与建议**：欢迎通过 [Issues](https://github.com/GaBoron/SPW-island/issues) 提出；参与开发可查看 [贡献指南](CONTRIBUTING.md)。

## 📜 许可与致谢

灵动词岛的原创视觉概念来自 [Lyricify / WXRIW（XY Wang）](https://github.com/WXRIW/Lyricify-App#lyricify-原创)，本项目依据 CC BY-SA 4.0 独立实现，并非 Lyricify 官方产品。

AMLL 动画移植模块采用 AGPL-3.0-only，其他程序采用 GPL-3.0-only；视觉、交互改编及文档采用 CC BY-SA 4.0。详见 [NOTICE](NOTICE)、[第三方许可说明](THIRD_PARTY_NOTICES.md) 和 [LICENSE](LICENSE)。
