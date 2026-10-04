# 安装与更新

[← 文档首页](README.md)

## 下载

从 [Releases](https://github.com/GaBoron/Dynamic-Lyrics-Island-for-SPW/releases/latest) 下载与你的平台对应的插件包：

| 平台 | 文件名 | 状态 |
| --- | --- | --- |
| Windows x64 | `spw-island-*-windows-x64.zip` | 主要支持平台 |
| Linux x64 | `spw-island-*-linux-x64.zip` | 实验性支持 |

不要导入带 `-source` 后缀的源码包，也不要混用 Windows / Linux 包。

## Windows

需要 Windows 10 / 11 x64，以及兼容当前 Workshop API 的 SPW 版本。Java 由 SPW 提供，不需要另外安装。

正常安装：

1. 在 SPW 中打开创意工坊 / 插件管理；
2. 导入下载好的 `windows-x64` ZIP；
3. 启用 Dynamic Lyrics Island for SPW；
4. 播放一首已经有歌词的歌曲。

如果当前 SPW 没有插件导入入口，可以完全退出 SPW，把 ZIP 放到：

```text
%APPDATA%\Salt Player for Windows\workshop\plugins\
```

然后重新启动 SPW。

实时频谱还有额外要求：Windows build 20348 或更高、可用的 .NET Framework 4.x，以及共享模式音频输出。条件不满足时歌词和其他功能仍可继续使用，详见 [兼容性与限制](compatibility.md#实时频谱与音频输出)。

## Linux

Linux x64 目前仍是实验性支持，需要：

- 支持 Java / Swing 插件的 SPW Linux 宿主；
- `/usr/bin/python3`；
- GTK 3；
- X11 或 XWayland。

界面使用 SPW 自带的 JVM，不需要单独安装 Java；Python helper 只使用标准库，不需要 PyGObject。

可选的实时频谱还需要 PipeWire，以及 `pw-record` / `pw-dump`（发行版通常放在 `pipewire-bin` 或 `pipewire` 包中）。悬停隐藏支持 X11 与 KDE Wayland。GNOME Wayland 45–50 需点击插件配置的“安装 GNOME 悬停隐藏支持”，首次安装或更新后保存工作并重新登录。

安装方式与 Windows 类似：导入 `linux-x64` ZIP 并启用插件即可。Linux 的快捷设置入口在系统托盘，部分 Windows 专属功能不会出现，具体见 [兼容性与限制](compatibility.md#linux-实验性支持)。

## 更新

通过 SPW 安装的用户，下载新版对应平台 ZIP 后重新导入即可，原有设置通常不需要清空。

如果 Windows 一直使用手动安装目录，更新前先退出 SPW，再用新版插件包替换旧版本。

更新后如果只是词岛位置或显示状态异常，先试一下插件设置里的“找回词岛”，没必要一上来就删配置重装。

## 装好之后

第一次测试建议选一首 SPW 自己已经能正常显示歌词的歌曲。

- 词岛完全不出现：看 [故障排查 → 安装与显示](troubleshooting.md#安装与显示)
- 有歌名但没歌词：看 [故障排查 → 歌词与时序](troubleshooting.md#歌词与时序)
- 频谱不动：看 [故障排查 → 封面、进度与频谱](troubleshooting.md#封面进度与频谱)

本插件不负责联网搜索歌词。需要自动获取逐字歌词时，可以搭配 [SPW-Lyrics](https://github.com/GaBoron/SPW-Lyrics)。
