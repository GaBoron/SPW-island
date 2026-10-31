# 安装与更新

[← 文档首页](README.md)

## 下载与安装

通过 [Steam 创意工坊](https://steamcommunity.com/sharedfiles/filedetails/?id=3821553308) 订阅，或从 [Releases](https://github.com/GaBoron/SPW-island/releases/latest) 下载 `spw-island-<版本>.spmod`。

手动安装：

1. 在 SPW 插件管理中导入 `.spmod`，Windows x64 和 Linux x64 使用同一个包。
2. 启用“灵动词岛 for SPW”。
3. 播放一首 SPW 已加载歌词的歌曲。

需要 **SPW 1.19.0+（Workshop API dev23）** 和 **Java 25+**。授予“曲库读取”权限可读取当前歌曲、封面和时长；“快捷键”权限用于显示 / 隐藏词岛。

## Linux

需要 Linux x64、`/usr/bin/python3` 和 X11 / XWayland。

- **实时频谱**：需要 PipeWire 和 `pw-record` / `pw-dump`。
- **液态玻璃**：首次使用时，在“背景与进度”中点击“安装 / 修复 Linux 液态玻璃支持”。GNOME 安装后重新登录；KDE 按系统授权提示完成安装。[支持范围](compatibility.md#液态玻璃)。
- **GNOME 悬停隐藏**：点击“安装 GNOME 悬停隐藏支持”，安装或更新后重新登录。适用于 GNOME Wayland 45–50。

GNOME 配套支持安装会开启用户扩展，其他已启用扩展也可能恢复。Linux 快捷菜单位于系统托盘；没有托盘时可使用 SPW 插件设置。

## 更新

从创意工坊更新，或导入新版 `.spmod`。提示文件被占用时，完全退出 SPW 后再更新；手动替换插件文件也应先退出 SPW。

遇到问题请查看 [故障排查](troubleshooting.md)。
