# 安装与更新

[← 文档首页](README.md)

## 下载与安装

在 [Releases](https://github.com/GaBoron/Dynamic-Lyrics-Island-for-SPW/releases/latest) 下载 `spw-island-<版本>.spmod`，或通过 [Steam 创意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3821553308) 订阅。Windows x64 和 Linux x64 使用同一个插件包；不要导入带 `-source` 后缀的源码包。

手动安装步骤：

1. 在 SPW 的创意工坊 / 插件管理中导入 `.spmod`。
2. 启用“灵动词岛 for SPW”。
3. 播放一首 SPW 已经加载歌词的歌曲。

需要 **SPW 1.19.0+（Workshop API dev23）** 和宿主提供的 **Java 25+**。可授予“曲库读取”和“快捷键”权限，以便恢复当前歌曲、读取封面 / 时长及使用快捷键。

## Linux

Linux x64 为实验性支持，需要 `/usr/bin/python3` 和 X11 / XWayland。功能差异见 [兼容性与限制](compatibility.md)。

需要使用以下功能时，另有要求：

- **实时频谱**：PipeWire，以及 `pw-record` / `pw-dump`。
- **GNOME Wayland 悬停隐藏（45–50）**：在插件设置中点击“安装 GNOME 悬停隐藏支持”，首次安装或更新扩展后重新登录。
- **GNOME 50 液态玻璃**：在插件设置的“背景与进度”中点击“安装 / 修复 GNOME 液态玻璃支持（Linux）”，首次安装后重新登录。

Linux 的快捷菜单位于系统托盘；看不到托盘时，仍可从 SPW 插件设置管理词岛。

## 更新

从创意工坊更新，或导入新版 `.spmod` 即可。Windows 若提示文件被占用，先完全退出 SPW；使用手动插件目录的用户应退出后替换原文件。

## 遇到问题

词岛不显示、歌词异常或频谱不动时，请查看 [故障排查](troubleshooting.md)。插件不负责在线搜索歌词，需要时可搭配 [SPW-Lyrics](https://github.com/GaBoron/SPW-Lyrics)。
