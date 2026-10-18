# 安装与更新

[← 文档首页](README.md)

## 下载

从 [Releases](https://github.com/GaBoron/Dynamic-Lyrics-Island-for-SPW/releases/latest) 下载 `spw-island-<版本>.spmod`。Windows x64 和 Linux x64 使用同一个插件包，名称统一为“灵动词岛 for SPW”。Windows 为主要支持平台，Linux 为实验性支持。

不要导入带 `-source` 后缀的源码包。

## Windows

需要 Windows 10 / 11 x64，以及支持 Workshop API dev23 的 SPW 1.19.0 或更高版本。Java 25 或更高版本由 SPW 提供，不需要另外安装。

正常安装：

1. 在 SPW 中打开创意工坊 / 插件管理；
2. 导入下载好的 `.spmod`；
3. 启用“灵动词岛 for SPW”；
4. 播放一首已经有歌词的歌曲。

安装时可授予“曲库读取”和“快捷键”权限。曲库读取用于恢复当前歌曲、查询时长和内嵌封面；快捷键用于注册显示 / 隐藏词岛动作。未授权时，歌词和播放控制仍可使用，曲目信息会在后续切歌回调到达后显示，封面与时长尝试从本地文件读取。更改权限后可停用并重新启用插件。

实时频谱还有额外要求：Windows build 20348 或更高、可用的 .NET Framework 4.x，以及共享模式音频输出。条件不满足时歌词和其他功能仍可继续使用，详见 [兼容性与限制](compatibility.md#实时频谱与音频输出)。

## Linux

Linux x64 目前仍是实验性支持，需要：

- 支持 Workshop API dev23、Java 25 和 Swing 插件的 SPW Linux 宿主；
- `/usr/bin/python3`；
- X11 或 XWayland。

界面使用 SPW 自带的 JVM，不需要单独安装 Java；Python helper 只使用标准库，不需要 PyGObject。

系统托盘优先使用 GTK 3，不可加载时尝试 GTK 2。GTK 必须在 SPW 的运行环境中可用；托盘不可用时仍可从 SPW 插件设置页管理词岛。

可选的实时频谱还需要 PipeWire，以及 `pw-record` / `pw-dump`（发行版通常放在 `pipewire-bin` 或 `pipewire` 包中）。悬停隐藏支持 X11 与 KDE Wayland。GNOME Wayland 45–50 需点击插件配置的“安装 GNOME 悬停隐藏支持”，首次安装或更新后保存工作并重新登录。

安装方式与 Windows 相同：导入 `.spmod` 并启用“灵动词岛 for SPW”。Linux 的快捷设置入口在系统托盘；设置页中标为 Windows 的功能仅在 Windows 生效，Linux 字体与 GNOME 支持使用对应的 Linux 设置项。具体差异见 [兼容性与限制](compatibility.md#linux-实验性支持)。

## 更新

通过 SPW 安装的用户，下载新版 `.spmod` 后重新导入即可，原有设置通常不需要清空。

Windows 更新后请重启 SPW，以加载插件内的原生库。

如果 Windows 一直使用手动安装目录，更新前先退出 SPW，再用新版插件包替换旧版本。

更新后如果只是词岛位置或显示状态异常，先试一下插件设置里的“找回词岛”，没必要一上来就删配置重装。

## 装好之后

第一次测试建议选一首 SPW 自己已经能正常显示歌词的歌曲。

- 词岛完全不出现：看 [故障排查 → 安装与显示](troubleshooting.md#安装与显示)
- 有歌名但没歌词：看 [故障排查 → 歌词与时序](troubleshooting.md#歌词与时序)
- 频谱不动：看 [故障排查 → 封面、进度与频谱](troubleshooting.md#封面进度与频谱)

本插件不负责联网搜索歌词。需要自动获取逐字歌词时，可以搭配 [SPW-Lyrics](https://github.com/GaBoron/SPW-Lyrics)。
