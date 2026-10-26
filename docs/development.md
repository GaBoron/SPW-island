# 开发指南

本文面向希望了解、修改或贡献灵动词岛 for SPW 的开发者。

如果只是安装或使用插件，请阅读：

- [安装与更新](installation.md)
- [使用与设置](usage.md)
- [兼容性与限制](compatibility.md)
- [常见问题](troubleshooting.md)

本文主要回答三个问题：

1. 项目由哪些部分组成；
2. 修改某个功能时应该从哪里开始；
3. 代码应如何组织、验证和贡献。

具体实现始终以当前源码为准。如果新增、删除或移动了承担独立职责的源码文件，也请同步更新本文的代码地图。

---

## 1. 项目概览

灵动词岛 for SPW 是运行在 Salt Player for Windows（SPW）Workshop API 上的歌词显示插件。

项目没有让 UI 直接依赖 SPW 的播放对象，而是在宿主与界面之间维护一层自己的播放模型。

主要数据流：

```text
SPW
 ↓
host/
  播放与歌词回调
 ↓
core/
  Track / LyricLine / PlaybackTimeline / PlaybackSnapshot
 ↓
ui/
  布局、歌词绘制、窗口与交互
```

歌曲时长、封面与封面颜色通过另一条只读链路补充，优先查询宿主公开 API：

```text
Track
 ↓
TrackMetadataLoader
 ↓
WorkshopTrackMetadata
 ↓
LocalTrackMetadata
 ↓
本地音频标签 / 封面文件
 ↓
CoverColorExtractor
 ↓
TrackMetadata
 ↓
PlaybackTimeline
```

Windows 和 Linux 共用核心播放模型与大部分 Swing UI，但窗口运行方式不同。

Windows：

```text
SPW JVM
 ├─ host/
 ├─ core/
 ├─ ui/
 └─ platform/Windows...
```

Linux：

```text
SPW JVM
 │
 ├─ host/
 ├─ core/
 └─ LinuxIslandProcess
        │
        │ 匿名双向管道
        ↓
   独立界面 JVM
        ├─ RemotePlayback
        └─ IslandWindow
```

Linux 将界面放进独立 JVM，主要是为了隔离宿主的 Skiko/AWT 状态和 GTK/X11 桌面组件。

---

## 2. 模块边界

项目 Kotlin 代码分为五个主要包：

| 目录 | 职责 |
| --- | --- |
| `core/` | 与 SPW、Swing 和具体操作系统无关的数据模型、播放状态和配置模型 |
| `host/` | SPW / PF4J 生命周期、宿主回调、配置保存和宿主兼容 |
| `ui/` | 窗口、布局、歌词绘制、动画、菜单和交互 |
| `platform/` | Windows、X11、本地文件、系统主题、音频辅助进程等平台能力 |
| `remote/` | Linux 宿主与独立界面进程之间的通信 |

依赖方向应尽量保持：

```text
host ───────┐
platform ───┼──→ core
remote ─────┤
ui ─────────┘
```

`core/` 不应依赖：

- SPW Workshop API；
- PF4J；
- Swing / AWT；
- JNA；
- Windows / X11；
- 本地文件解析实现。

宿主相关问题应停留在 `host/`，操作系统差异应停留在 `platform/` 或 `remote/`。

---

## 3. 主要运行链路

### 3.1 SPW 播放回调

播放扩展由：

```text
META-INF/extensions.idx
 ↓
IslandPlaybackExtension
```

注册。

完整路径：

```text
SPW PlaybackExtensionPoint
 ↓
IslandPlaybackExtension
 ↓
PlaybackCallbackBridge
 ↓
IslandRuntime
 ↓
PlaybackTimeline
 ↓
PlaybackSnapshot
```

`IslandPlaybackExtension` 只负责把 SPW 的类型映射为项目自己的 `Track`、`LyricLine`、`Word` 和播放状态。

它不负责绘制，也不提供或替换 SPW 歌词。

`PlaybackCallbackBridge` 处理插件生命周期中的一个特殊情况：PF4J 创建播放扩展实例和插件运行实例的时机并不完全一致，因此运行实例尚未准备好时到达的播放回调会先暂存，随后交给 `IslandRuntime`。

`PlaybackTimeline` 是最终的播放状态汇合点。

### 3.2 播放时间

SPW 提供的播放位置通知不一定与词岛刷新率一致。

`PlaybackTimeline` 保存最近一次宿主位置和单调时钟锚点，在两个宿主位置回调之间补间当前位置：

```text
SPW position heartbeat
 ↓
PlaybackTimeline
 ↓
单调时钟补间
 ↓
PlaybackSnapshot.positionMs
 ↓
歌词动画 / 进度显示
```

seek、暂停、恢复、切歌和播放结束也在这里统一处理。

如果插件在歌曲已经播放后才启动，可能错过更早的播放状态回调。`PlaybackHeartbeatRecovery` 可以从连续向前的播放位置心跳恢复缺失的播放状态，而不是仅凭一次位置变化猜测正在播放。

### 3.3 完整歌词与当前歌词

正常情况下，SPW 会通过歌词行回调提供当前歌词。

项目同时维护：

```text
当前歌词回调
+
可选完整歌词轴
 ↓
PlaybackTimeline
```

`ActiveLyrics` 再根据播放时间选择真正应该显示的歌词。

普通模式通常显示当前一行。

实验性多行模式可以在完整歌词轴可用时保留仍未结束的重叠歌词，并在无歌词间奏期间保留上一条有效歌词。

`IslandPlaybackExtension.onLyricsLinesUpdated` 接收 SPW 的完整歌词快照。插件启用后由宿主异步重放；换曲和歌词重载的空列表会清除文档、已累积的单行和暂停画面中的旧歌词。相同内容的重复通知不会重复累积歌词，低性能模式同样接收完整时间轴。

`LyricsDocument` 缓存合并后的歌词轴和整曲逐字类型；相同歌词回调复用已有文档，帧快照不再扫描整首歌词。歌词内容更新时重新合并，暂停时保留当时的文档与分类。

### 3.4 本地歌曲元数据

SPW 的歌词加载回调提供基本曲目信息，新增字段的默认值不能作为完整曲库元数据使用。

词岛额外需要：

- 歌曲时长；
- 专辑封面；
- 封面主色。

`WorkshopTrackMetadata` 在后台查询 `getCurrentMediaItem()`，仅在路径、标题和艺术家匹配当前曲目时采用其时长与歌曲 ID。ID 仅用于本次 `getCoverById()` 查询，不持久化。权限未授权或查询失败时继续使用本地文件补充。

```text
trackChanged
 ↓
TrackMetadataLoader
 ↓ 后台
WorkshopTrackMetadata
 ├─ getCurrentMediaItem：曲目 / 时长
 └─ getCoverById：内嵌封面
 ↓ 缺失时补充
LocalTrackMetadata
 ├─ Jaudiotagger：时长 / 内嵌封面
 └─ cover.* / folder.*：同目录封面
 ↓
CoverColorExtractor
 ↓
TrackMetadata
```

读取过程不联网、不修改标签，也不解码完整音频。启动恢复保留已经到达的播放位置和歌词；查询期间切歌或进入 Idle / Ended 后，旧代次结果不会恢复旧曲目。

晚到的旧曲目元数据不会覆盖当前曲目。

### 3.5 Windows UI

Windows 由 `IslandRuntime` 创建 `IslandWindow`，通过 Skia / Direct3D 绘制词岛；Linux 独立界面进程使用 Skia / OpenGL。文字仍由 AWT 塑形，轮廓、渐变、辉光和图层合成交给 GPU。GPU 初始化或绘制失败时切回 Java2D，关于窗口的性能状态会说明回退。Compose 字体窗口由插件设置按需打开。

```text
PlaybackTimeline
 ↓ snapshot()
IslandWindow
 ↓
IslandPanel
 ├─ IslandLeadingContent
 ├─ IslandLyricsPainter
 ├─ IslandTrailingContent
 ├─ IslandBackgroundProgress
 └─ PlaybackProgress / 播放按钮
```

`IslandWindow` 负责窗口生命周期和整体状态，不负责具体歌词绘制。

Windows 实时频谱由 `ProcessSpectrum` 通过 `SpectrumSource` 接口提供。

### 3.6 Linux UI

Linux 上宿主不直接创建词岛窗口。

```text
IslandRuntime
 ↓
LinuxIslandProcess
 ↓ IslandState
匿名管道
 ↓
LinuxIslandMain
 ↓
RemotePlayback
 ↓
IslandWindow
```

界面的播放、seek、设置和位置操作通过反向 `IslandCommand` 传回宿主：

```text
IslandWindow
 ↓
IslandCommand
 ↓
LinuxIslandProcess
 ↓
SPW / HostSettings
```

`IslandWire` 定义两边共享的状态、频谱帧与命令模型。每个命令有递增 ID；宿主处理命令后记录已处理的命令 ID，并在读取播放 / 设置快照之前读取该 ID。界面的 `RemotePlayback` 和 `RemoteSettingsStore` 保留最新 seek、位置和菜单设置预览，收到对应确认后使用宿主值；命令失败时恢复宿主状态，切歌时取消旧 seek。频谱帧由后台线程接收，界面读取最近有效样本；350 ms 无新数据时归零。

Linux 实时频谱由 `LinuxProcessSpectrum` 提供。Python helper 完成 FFT，只将四个频段值送往宿主；`IslandSpectrum` 以约 30 Hz 独立传给界面，不增加歌词/封面状态频率。低性能模式、暂停、关闭词岛或不显示频谱时停止捕获。

### 3.7 设置

设置统一保存到 SPW 管理的 `island.json`。

```text
preference_config.json
 ↓
SPW ConfigManager
 ↓
HostSettings
 ↓
IslandSettings
 ↓
UI
```

菜单修改设置时也通过同一个 `SettingsStore`：

```text
IslandMenuCommands
 ↓
SettingsStore
 ↓
HostSettings
 ↓
island.json
```

托盘菜单提供显示、穿透、悬停隐藏和低性能模式开关，以及找回词岛、重置位置和关于窗口入口。悬停隐藏开启时，穿透项也显示勾选；关闭穿透时同时关闭悬停隐藏。

`IslandConfigFile` 使用 Gson 解析单次读取的 JSON 快照。空文件或无法解析的内容不会替换已接受设置；保存前检查文件是否已被其他操作修改，再通过同目录临时文件原子替换。显式设置操作最多重试 6 次，间隔 10 ms。位置由插件保存，并与 SPW 表单编辑合并；Windows 字体由字体窗口管理，Linux 字体字段直接接受表单修改。

`IslandConfigWatch` 监听配置文件创建、修改与替换，宿主通知也通过同一后台队列合并。读取不完整时在 2 秒窗口内每 250 ms 重试；正常静置时每 5 秒兜底检查，并重新注册失效的目录监听。

Windows 和 Linux 共用 `preference_config.json`，平台专属设置在名称中标明；对应运行组件按实际操作系统选择。

---

## 4. 完整代码地图

本节描述每个源码文件的稳定职责。

如果只是修改一个局部功能，通常只需要阅读对应的小组，不需要先阅读整个项目。

### 4.1 `core/`：核心模型与播放状态

| 文件 | 职责 |
| --- | --- |
| `ActiveLyrics.kt` | 根据当前播放快照选择实际显示的歌词；负责普通单行、实验性多行、重叠歌词和间奏保留 |
| `IslandAnchor.kt` | 九宫格窗口锚点模型，以及锚点的持久化名称转换 |
| `IslandSettings.kt` | 稳定的设置快照、两侧内容模式、背景进度模式，以及通用 `SettingsStore` 接口 |
| `GlassStatus.kt` | 桌面捕获状态、回退原因、更新率和原生处理耗时 |
| `LyricFontWeight.kt` | 歌词字重的稳定存储值与解析 |
| `LyricWordTiming.kt` | 优先使用原生逐字时间轴；按行时长估算普通 LRC 的字素进度并标记估算来源 |
| `Lyrics.kt` | `Track`、`Word`、`LyricLine`、`PlaybackSnapshot`、`TrackMetadata`、封面数据等核心模型 |
| `LyricsDocument.kt` | 缓存歌词行索引、合并结果和整曲逐字类型；二分查找当前行，内容未变时复用文档 |
| `PerformanceProfile.kt` | 标准模式和低性能模式的统一性能预算 |
| `PlaybackHeartbeatRecovery.kt` | 在启动时缺失播放状态回调的情况下，根据连续播放位置心跳恢复状态 |
| `PlaybackTimeline.kt` | 汇总宿主回调、补间播放位置、处理 seek；歌词源变化时合并歌词轴，帧快照复用合并结果 |

修改核心播放规则时，应首先确认逻辑能否留在这里，而不是依赖 SPW 或 UI。

### 运行诊断

`diagnostics/` 提供与宿主和绘制后端无关的进程内日志服务，插件创建时和 Linux 独立界面入口分别启动。日志使用 JSON Lines，包含运行编号、进程、线程、模块、级别、时间和显式上下文；宿主与子进程共享运行编号。正常播放位置、歌词正文及逐帧内容不写入日志。

| 文件 | 职责 |
| --- | --- |
| `diagnostics/IslandLog.kt` | 显式日志生命周期、统一事件接口、状态变化、异常尝试和插件自有线程工厂 |
| `diagnostics/DiagnosticWriter.kt` | 有界异步队列、最新观察状态缓存、异常汇总、落盘、轮换与丢失条数记录 |
| `diagnostics/DiagnosticWatchdog.kt` | 独立于文件写入的进展与超时采样，按性能配置调度 |
| `diagnostics/DiagnosticRetention.kt` | 合并历史清理请求，在独立工作线程使用系统回收站 |
| `diagnostics/DiagnosticStorage.kt` | 日志目录、10 天保留与 64 MiB 容量预算；历史文件仅移入回收站，失败时保留并记录 |
| `diagnostics/DiagnosticFileLease.kt` | 在打开日志前登记进程与启动时间，关闭后释放；清理时保护仍在使用的文件，无需心跳轮询 |
| `diagnostics/DiagnosticPrivacy.kt` | 显式上下文、异常堆栈的常见路径和凭据脱敏 |
| `diagnostics/DiagnosticOperation.kt` | 操作编号、开始与结果、超时及恢复 |
| `diagnostics/DiagnosticPulse.kt` | 不依赖 EDT 的进展停滞与恢复监测 |
| `diagnostics/DiagnosticExecutors.kt` | 收集插件自有 executor 和 scheduled future 中捕获的异常 |
| `diagnostics/DiagnosticProcesses.kt` | 启动辅助进程、传递运行编号、独立收集 stderr 和退出状态 |
| `diagnostics/DiagnosticExport.kt` | 导出日志、允许的运行环境信息与当前进程的最新观察状态，不复制设置、媒体或宿主日志 |
| `host/HostDiagnosticActions.kt` | SPW 设置页的日志目录与诊断包入口，不依赖词岛创建成功 |
| `host/SettingsDiagnostics.kt` | 只记录插件设置模型中实际变化的字段、来源和保存尝试次数 |
| `ui/WindowDiagnostics.kt` | 帧调度与绘制进展、显示策略及窗口状态采样 |
| `platform/NativeWindowDiagnostics.kt` | Windows 原生窗口存在、可见、最小化与置顶状态；系统合成结果保持未知 |
| `src/linux/resources/native/island-diagnostics.py` | Python 辅助进程内的异常、线程和回调诊断，重复异常汇总后交给 JVM 收集 |
| `native/glass/CaptureDiagnostics.h` | 每个桌面捕获实例的有界原生诊断队列，保留阶段、HRESULT、线程和原生栈地址 |

调用方只提供必要的结构化字段，不传递曲目对象、歌词、配置全文或命令行。已有异常回调仍负责界面反馈，日志不依赖这些界面；新模块沿用同一接口。插件自有的界面、定时器和原生回调用 `IslandLog.guard` 局部记录异常后保留原有传播方式。仅设置插件自有线程的异常处理器，不替换 SPW JVM 的默认处理器或全局输出。

窗口、帧进展和 IPC 监测通过 `PerformanceProfile` 取得采样与停滞阈值：标准模式每秒采样，5 秒无进展时记录；低性能模式每 5 秒采样，阈值为 15 秒。策略隐藏期间暂停绘制进展判断。窗口系统报告的状态、绘制完成和用户实际看到的合成结果须分别解释。

状态未变化时也刷新有界缓存中的观察时间和上下文，不重复写入事件。诊断包的 `latest-state.json` 包含导出进程最近观察的状态、状态标签最后变化时间及观察距导出的间隔；它不发起新的界面查询，也不等待 EDT。Linux 独立界面的观察记录仍在同一运行编号的 UI 日志中。

窗口采样包含隐藏策略、内容位置、缩放和生产帧数；Windows 另记原生矩形、透明样式及前台窗口类名与矩形，不记录窗口标题。渲染信息的周期读取遵循同一采样预算，另在图层创建时记录一次请求后端、实际后端和图层状态。找回与重置位置操作记录提交、完成或失败；设置操作完成不表示用户已看到词岛。导出刷新屏障在返回成功前写出待汇总的重复异常、溢出事件和丢失条数。

首次停滞和操作超时时仅抓取相关操作线程或最近推进该监测点的线程栈，不转储整个 JVM。异步工作开始时通过 `runningHere()` 更新操作所属线程，避免把调度线程误认为执行线程。

### 4.2 `host/`：SPW 接入

| 文件 | 职责 |
| --- | --- |
| `WorkshopTrackMetadata.kt` | 封装公开的当前曲目、时长、内嵌封面和读取权限查询，转换为核心模型 |
| `IslandShortcut.kt` | 在插件 start 线程注册 SPW 管理的显示快捷键，管理权限与注销句柄 |
| `HostVolumeControl.kt` | 按需绑定 SPW 内部音量读写接口，在宿主 EDT 同步播放器、界面和保存值并回传实际音量；不轮询 |
| `HostSettings.kt` | 适配 `SettingsStore`，负责配置同步、合并、保存重试和旧配置迁移 |
| `IslandConfigFile.kt` | 解析同一次读取的完整 JSON 快照，拒绝空/部分内容，检查新编辑并原子保存 |
| `IslandConfigWatch.kt` | 文件事件与宿主通知合并、短时读取重试和低频兜底检查 |
| `IslandPlaybackExtension.kt` | 接收 SPW `PlaybackExtensionPoint` 回调并转换为项目自己的模型 |
| `IslandPlugin.kt` | PF4J 插件生命周期；创建和释放唯一的 `IslandRuntime`，并提供配置页按钮入口 |
| `IslandRuntime.kt` | 宿主侧组合根；组装时间轴、设置、元数据、频谱、窗口、平台远程 UI 和播放操作 |
| `IslandUiLifecycle.kt` | 异步投递窗口创建、操作和销毁；取消停用后的待执行启动，避免持有宿主插件管理锁时等待 EDT |
| `PlaybackCallbackBridge.kt` | 暂存运行实例建立之前到达的播放回调，并在 runtime 就绪后转交 |
| `TrackMetadataLoader.kt` | 单个有界后台线程恢复当前曲目、查询宿主元数据并补充本地信息；旧结果通过代次检查丢弃 |

正常业务逻辑不应绕过这些边界直接访问 SPW 内部对象。

### 4.3 `platform/`：平台与本地能力

#### 本地歌曲

| 文件 | 职责 |
| --- | --- |
| `LocalTrackMetadata.kt` | 只读获取歌曲时长、内嵌封面和同目录封面 |
| `CoverColorExtractor.kt` | 从封面中选择有代表性的彩色主色，并尽量避开近黑和近白像素 |

#### Windows

| 文件 | 职责 |
| --- | --- |
| `WindowsOverlay.kt` | Windows 覆盖窗口能力：鼠标穿透、置顶强化和前台全屏判断 |
| `WindowsNativeResources.kt` | 按插件类加载器提取并复用原生 DLL、辅助程序和配套数据的临时副本，避免占用插件安装目录 |
| `WindowsTrayExecutable.kt` | 在用户本地应用数据目录部署并复用固定路径的托盘程序，按内容更新且串行安装 |
| `WindowsApplicationData.kt` | 查询本地应用数据的实际重定向目标，使 MSIX 宿主部署的程序可由 CLR 直接访问 |
| `SystemTheme.kt` | 读取 Windows 应用深浅色主题 |
| `DotNetFrameworkRuntime.kt` | 在启动实时频谱 helper 前静默检查所需 .NET Framework CLR |

#### 频谱

| 文件 | 职责 |
| --- | --- |
| `SpectrumSource.kt` | 两平台共用的频谱来源接口，提供启停、四频段值、来源状态和模拟回退标记 |
| `ProcessSpectrum.kt` | 管理 Windows 进程音频频谱 helper 的生命周期、数据读取和模拟频谱降级 |
| `LinuxProcessSpectrum.kt` | 管理 PipeWire 频谱 helper、最新频段值、来源状态和模拟回退 |

#### 菜单与 X11

| 文件 | 职责 |
| --- | --- |
| `GlobalMenuDismisser.kt` | 弹出菜单显示期间监听菜单外点击；Windows 使用低级鼠标钩子，其他平台使用适当 fallback |
| `X11MenuDismisser.kt` | `GlobalMenuDismisser` 的 XToolkit/X11 外部点击 fallback；当前 Linux 主菜单路径主要使用 GTK 托盘 |
| `X11InputRegion.kt` | 设置 X11 ShapeInput；普通模式使用词岛轮廓，穿透模式使用空输入区域 |

#### Linux 辅助进程

| 文件 | 职责 |
| --- | --- |
| `LinuxHelper.kt` | 提取并启动 Linux Python helper，并为独立界面 JVM 构造 classpath |
| `src/linux/resources/native/island-dbus-tray.py` | GTK 缺失时通过 GIO 提供 StatusNotifierItem / DBusMenu；按管道和总线事件更新，图标以 ARGB 像素传输 |
| `LinuxGlobalPointer.kt` | 提供 X11 指针查询，管理 KWin / GNOME 指针桥接及样本有效期 |

平台功能不要直接加入 `core/`。

### 4.4 `remote/`：独立进程通信

| 文件 | 职责 |
| --- | --- |
| `RemoteSettingsStore.kt` | 即时应用 Linux 菜单与位置操作，宿主确认后移除待确认编辑 |
| `IslandWire.kt` | 定义状态、命令、频谱与音量结果消息，以及子进程使用的 `RemotePlayback` 与 `RemoteSpectrum` |
| `RemoteVolumeControl.kt` | 发送滚轮增量与拖动绝对音量命令，按请求 ID 将最新宿主结果交回界面 |
| `LinuxIslandProcess.kt` | 宿主侧独立界面进程管理；发送播放、设置和频谱状态，接收并确认播放/seek/设置等命令 |
| `LinuxIslandMain.kt` | Linux 子 JVM 入口；接收状态、创建 `IslandWindow` 并把用户操作传回宿主 |

控制通道使用父子进程匿名管道，不提供网络接口。

### 4.5 `ui/`：窗口与几何

| 文件 | 职责 |
| --- | --- |
| `IslandWindow.kt` | 顶层窗口生命周期、帧更新、拖动、hover、可见性、窗口定位和平台窗口状态 |
| `IslandFrameClock.kt` | 查询词岛所在显示器的刷新率，按性能预算计算帧间隔并扣除本帧处理时间 |
| `IslandPanel.kt` | 词岛主体组件；组合背景、左右内容、歌词、播放按钮和展开内容 |
| `IslandSurface.kt` | 稳定透明画布、GPU 与兼容绘制入口，以及输入区域计算 |
| `BufferedIslandWindow.kt` | Linux 兼容绘制时先完成透明缓冲，再一次提交；GPU 绘制不使用该缓冲 |
| `gpu/IslandGpuRenderer.kt` | 管理 Skia 原生层、后端选择、故障回退和资源释放 |
| `gpu/IslandGpuScene.kt` | 直接用 Skia Canvas 组合背景、左右内容、歌词和控件；不调用 Swing 绘制 |
| `gpu/IslandGpuFrame.kt` | 面板提供的帧数据，包含布局、动画和交互预览状态 |
| `gpu/IslandGpuLyrics.kt` | 原生字形路径、逐字渐变、长音辉光和低性能高亮 |
| `gpu/IslandLyricContrast.kt` | 在 GPU 上比较最终玻璃与当前歌词颜色，低对比位置补充中性描边 |
| `gpu/IslandGpuBackground.kt` | 原生背景、注水和边缘进度 |
| `gpu/IslandLiquidGlass.kt` | Windows 实验性玻璃光学：真实后景的边缘折射、轻微色散与方向高光 |
| `gpu/IslandGlassContour.kt` | 从原始 Shape 展平线段生成最短距离场，另保留原 Path 的覆盖 mask，按几何和 DPI 缓存 |
| `gpu/IslandGlassImage.kt` | GPU 裁片或兼容像素帧的 Vibrancy 与中心/边缘模糊缓存，仅在背景帧或 DPI 改变时更新 |
| `gpu/IslandGlassShaders.kt` | 移植的 Lens、七采样色散与 Default Highlight Shader，以及轮廓距离适配程序 |
| `gpu/IslandGlassHighlight.kt` | 原轮廓内侧的模糊细边，在内容之后独立使用 PLUS 合成 |
| `gpu/SwingGlassMaterial.kt` | 菜单与关于页的 Direct3D 图层、桌面裁片、显示时钟、首帧准备和资源释放 |
| `gpu/SwingGlassOptics.kt` | 辅助窗口圆角矩形的解析距离场、共享折射与高光，以及深浅主题基底 |
| `gpu/SwingGlassContent.kt` | 仅在控件内容变化时更新 Swing 内容图像，与桌面背景刷新分开 |
| `gpu/SwingGlassInput.kt` | 将原生输入窗口的鼠标事件转给原有按钮、拖动与滚动逻辑，观察键盘和焦点变化 |
| `gpu/IslandGpuSides.kt` | 原生封面、频谱和播放状态 |
| `gpu/IslandGpuControls.kt` | 原生控制图标、歌曲信息、可拖动进度和音量条过渡的呈现 |
| `gpu/IslandGpuResources.kt` | 有界原生路径与封面缓存、复用画笔和透明图层资源 |
| `IslandLyricPresentation.kt` | GPU 与兼容绘制共用的行位置、换行透明度和缩放 |
| `IslandContentGeometry.kt` | GPU 与兼容绘制共用的内容坐标，匹配窗口整数锚点并保留连续动画尺寸 |
| `LyricTextPlacement.kt` | 共用的逐字边界、双向 caret、居中与滚动计算 |
| `gpu/IslandGpuInput.kt` | 将原生输入转给已有按钮、进度、拖动和滚轮逻辑，保留拖动捕获 |
| `platform/WindowsGpuInput.kt` | 独立的非聚焦输入窗口；轮廓外穿透，不裁切 GPU 画面 |
| `platform/WindowsDesktopBackdrop.kt` | JNA 接口、共享纹理或 Skia Data 帧快照、新帧重绘通知、状态与异步释放 |
| `platform/WindowsGpuTextureImport.kt` | 获取实际 Skiko Direct3D 上下文及绘制锁，将共享 D3D12 资源快照为 GPU Image |
| `native/glass/DesktopCapture.cpp` / `tools/build-glass.ps1` | C++/WinRT 桌面采样及 MSVC 构建；MTA 线程负责捕获、裁切、窗口排除与恢复 |
| `native/glass/DesktopReadback.h` | 三槽 GPU 裁切队列，fence 完成通知及 staging 读回兼容路径；仅发布最新已完成裁片 |
| `native/glass/DesktopSharedTexture.h` | 匹配渲染适配器，创建只写一次的 D3D11/D3D12 共享裁片 |
| `native/glass/DesktopViewport.h` | 屏幕坐标固定的裁片范围与换块滞回，拖动时复用既有像素 |
| `platform/SkikoRuntime.kt` | 加载插件自己的 Skiko 原生库，供词岛与字体窗口共用 |
| `IslandPlacement.kt` | 工作区计算、九宫格锚点、刘海顶部约束、拖动磁吸和稳定画布位置 |
| `IslandGeometry.kt` | 静止胶囊/刘海轮廓、内容安全内缩、进度细线内缩和轮廓缓存 |
| `IslandNotchGeometry.kt` | 静止与形变刘海共用的顶部粘连、主体横向拉伸与高度收缩几何 |
| `ContinuousCornerPath.kt` | 连续圆角 / 超椭圆圆角路径生成 |
| `IslandAlphaMask.kt` | Java2D 兼容路径的设备像素内容层与抗锯齿轮廓蒙版 |
| `IslandHoverVisibility.kt` | 鼠标穿透模式下的进入区域锁定与隐藏目标判定 |
| `IslandPointerPresence.kt` | 聚合 Linux 词岛与子控件的鼠标事件，以离开延迟保持控件间的悬停连续性 |
| `IslandTransitionMotion.kt` | 隐藏、控件和进度淡入淡出共用的有限时长 Hermite 运动；可配置时长、两端过冲和单次回弹，反向时接续速度 |
| `IslandHoverMotion.kt` | 悬停隐藏运动参数及完整显示 / 动画中的状态；恢复时单次回弹 |
| `IslandExpandedMotion.kt` | 与隐藏动画共用速率的控件打开 / 收起运动，两端各有一次过冲回弹 |
| `IslandHoverGeometry.kt` | 将可见状态映射为刘海形变或胶囊收圆，并计算内容淡出、位移和画布留边 |
| `IslandExpandedContentTransition.kt` | 展开控件条共用的透明度曲线，以及兼容路径的像素缓冲 |

`IslandWindow` 是窗口状态协调者，不应继续承担具体歌词、形状或菜单绘制。

悬停隐藏沿 `IslandHoverVisibility → IslandHoverMotion → IslandHoverGeometry → IslandSurface / IslandPanel` 传递。进入时锁定正常轮廓，动画和歌词尺寸变化不影响本次离开判定。刘海仅沿顶部拖动；隐藏时顶部粘连区和主体同时向左右拉伸、压低高度，恢复时越过正常尺寸后回到终点。胶囊先横向收圆，再等比缩小，恢复时先长成正常高度的圆，再向两侧拉长并回弹。两种轮廓都与内容绘制分开，内容层按设备像素密度绘制后统一淡出和裁切。低性能模式沿用 `PerformanceProfile.animateLayout`，直接切换到隐藏或显示终点。

画布提前覆盖横向拉伸、回弹及完整控制区高度，并依据已预备的后续歌词高度扩容。GPU 路径直接调用 Skia Canvas；字体塑形和回退仍共用 `LyricTypography`，字形轮廓首次使用时转成缓存的原生路径。原生画笔复用，裁剪按内容区域设置，完整不透明时不创建额外透明图层，完全透明的控制区不录制指令。GPU 路径不生成逐帧整块 CPU 位图；封面仅在缓存未命中时转换。悬停隐藏仍更新歌词，只改变最终轮廓和透明度；控件持续挂载并负责输入。关闭“显示词岛”时释放原生层、输入窗口及绘制缓存，临时隐藏保留资源。Java2D 兼容路径继续复用按 64 像素对齐的缓冲，只清空当前与上一帧内容范围的并集。

`background_style` 默认为 `classic`；Windows 的 `liquid_glass` 使用 WGC 实时桌面裁片。捕获线程保留最新帧，按当前轮廓加光学留边裁切；屏幕坐标缓存和换块滞回复用拖动中的裁片。三槽队列通过 GPU fence 发布最新已完成帧，通知线程合并 EDT 重绘请求，呈现由 Skiko/VSync 调度。

背景优先使用同适配器的 D3D11/D3D12 共享纹理。适配器遵循 Skiko 的 GPU 优先级、DXGI 枚举顺序和 D3D12 支持条件，同名设备按这一顺序选择。裁片只写一次，复制完成后交给 Skia GPU Image；共享不可用时使用 staging 读回。`WindowsGpuTextureImport` 绑定 Skiko 0.150.1，通过反射访问实际 `DirectContext`、`drawLock` 与 GPU 优先级；上下文操作、录制和释放共用绘制锁。共享路径保留 GPU 裁切和快照复制，不回读像素；资源创建和 Present 等待仍有开销。

材质顺序为 Backdrop Vibrancy → Blur → Lens → 中性基底与进度 → 原内容 → 独立 Highlight。中心模糊 sigma 为 6 逻辑像素，折射边缘使用 sigma 2 的较清晰采样，两者平滑混合；折射集中在内侧 14 逻辑像素，最大位移 36。七采样色散的最大单向偏移为 0.2 逻辑像素。词岛、菜单和关于页共用这些光学参数。高光使用轮廓内侧的模糊细边，独立 PLUS 合成；光源固定左上，背光侧强度为 15%。词岛基准遮罩为 50% 黑色，由背景不透明度缩放；辅助窗口使用随主题变化的 82% 中性基底保证小字对比度。

距离场和覆盖 mask 来自同一动态 Shape，法线由距离差分计算，最终轮廓使用原 Path 裁剪。Shader 复用；几何按轮廓与 DPI 缓存，背景滤镜按捕获版本缓存。歌词对比度在 GPU 上以 5×3 个位置采样最终材质，再归约到 1×1 纹理；低于 4.5:1 时整行使用经典黑色描边，字形动画共用判断结果。

辅助窗口的固定圆角矩形使用解析距离场，词岛保留原动态 Shape。`SwingGlassMaterial` 与词岛共用 Direct3D、桌面共享纹理和显示时钟；背景直接由 GPU 合成，不逐帧回读成 Swing 位图。控件内容单独缓存，只有文字、焦点或交互状态变化时更新。

平台条件、录屏排除和回退行为见[兼容性与限制](compatibility.md#液态玻璃)。关于窗口中的背景更新率和原生处理耗时仅反映接收与发布阶段，不代表最终屏显帧率或端到端延迟。原生捕获构建需要 MSVC x64 C++ 工具、CRT/STL 和带 C++/WinRT 的 Windows SDK。

Windows 的 DirectComposition 画面不进入 AWT 的逐像素输入映射，因此使用仅承担命中的独立输入窗口。它采用最低非零的整体透明度和正常轮廓，不获取键盘焦点；鼠标穿透、悬停隐藏及窗口临时隐藏时撤下该输入窗口。输入区域变化不影响可见动画的裁剪。

控件展开与歌词换行尺寸分开驱动：歌词基础尺寸保持原有连续平滑，控制区宽高直接使用 `IslandExpandedMotion` 的进度，允许打开时超过 1、收起时低于 0。打开采用 0.44 秒主运动与 0.16 秒回弹；收起采用刘海 0.38 秒 / 胶囊 0.40 秒主运动与 0.16 秒回弹。可拖动进度条持续绘入控件条，与播放按钮共用淡入淡出和位移，仅交互根据呈现状态启用。背景注水和边缘细线在进入隐藏或控件动画时淡出；悬停完整显示、控件完全收起且两套运动均结束后淡入。`IslandProgressTransition` 使用无过冲 Hermite 曲线，淡入 0.18 秒、淡出 0.12 秒，中途反向接续当前速度；关闭背景进度设置时保留原样式直到淡出完成。注水通过颜色过渡保持整体背景透明度不变，细线通过透明度过渡，低性能模式直接切换终点。

### 4.6 `ui/`：歌词布局与逐字绘制

歌词绘制的主要路径：

```text
PlaybackSnapshot
 ↓
ActiveLyrics
 ↓
IslandLyricsLayout
 ↓
IslandTextBlock
 ↓
LyricTypography
 ↓
IslandLyricsPainter
 ↓
LyricPainter
 ├─ 普通歌词
 ├─ TimedKaraokeBoundary
 └─ AmllWordPainter
      ├─ WordGeometry
      ├─ AmllMotion
      └─ LyricGlow
```

对应文件：

| 文件 | 职责 |
| --- | --- |
| `IslandLyricsLayout.kt` | 测量一个或多个活动歌词行及整体尺寸；每个面板持有有界布局缓存供测量、绘制和预备线程共用 |
| `IslandLyricsPreparation.kt` | 有界后台预备曲目信息和后续歌词布局、轮廓及逐字几何，提供后续内容所需的缓冲高度 |
| `IslandTextBlock.kt` | 决定主歌词/翻译文本、字体、字形布局、逐字单元和单行尺寸 |
| `IslandLyricsPainter.kt` | 绘制当前歌词行，并处理歌词切换和多行位置过渡 |
| `LyricPainter.kt` | 单行歌词的实际绘制入口；负责居中、长歌词滚动、逐字高亮和低性能绘制路径 |
| `LyricTypography.kt` | 使用 `TextLayout` 统一文本塑形、测量、字体 fallback 和整行轮廓缓存 |
| `LyricGlyphOutline.kt` | 筛选当前 grapheme 附近的轮廓，在复杂度预算内生成裁剪字形 |
| `AmllWordPainter.kt` | 对已塑形的 grapheme 应用逐字高亮、位移、缩放和辉光 |
| `AmllMotion.kt` | AMLL 来源的逐字浮动、长音强调和歌词行切换运动曲线；只接收规则给出的长音结果 |
| `longtone/` | 准备 Unicode 文本信息、选择语言规则，并保守判断当前 timing cell 是否为长音 |
| `longtone/LongToneAnalysis.kt` | 按逐字单元列表缓存长音判定，供后台预备与绘制共用；不依赖字体 |
| `WordGeometry.kt` | 在有界后台线程中准备单词和 grapheme 的字形几何，避免动画线程做昂贵轮廓运算 |
| `TimedKaraokeBoundary.kt` | 低性能模式下只计算当前逐字单元的高亮边界 |
| `LyricGlow.kt` | 长音强调的辉光绘制 |
| `SystemUiFont.kt` | 加载两平台共用的内置 MiSans、自定义歌词字体及缺字回退 |
| `ComposeFontPickerWindow.kt` | 打开 Compose 字体窗口并回写选择结果 |
| `FontPickerCatalog.kt` | 读取 Windows 已安装字体和可用字形，并提供搜索分类 |
| `FontPickerContent.kt` | 字体窗口的搜索、字形选择与操作界面 |
| `FontPickerPreviewPanel.kt` | 使用词岛实际绘制逻辑预览歌词 |
| `FontPickerSelection.kt` | 字体窗口提交的字体与字重选择 |
| `IslandContentLayout.kt` | 统一歌词、左侧内容、右侧状态和展开信息的水平内缩与最小尺寸 |

文本的测量和实际绘制应使用同一套塑形结果，避免窗口尺寸与最终字形不一致。

`IslandPanel` 在同一帧内复用测量布局，`IslandLyricsPainter` 将其中的主歌词和翻译塑形结果直接交给 `LyricPainter`，完成换行过渡后不再准备旧行。`IslandLyricsPreparation` 只保留一个待执行请求，曲目、歌词、设置变化或明显播放位置跳变时更新请求，旧代次停止继续预备。标准模式预备后续三行；低性能模式只预备一行并跳过详细逐字几何。

完整逐字歌词到达后，预备线程分析整首歌词的长音标记；字形与轮廓仍只预备当前及随后几行。绘制复用长音结果、同一词的渐变和本帧逐字几何查询。切歌清空长音分析缓存，字体变化只影响字形准备；低性能模式跳过长音预分析。

`force_karaoke` 默认为 `false`。`LyricWordTiming` 在没有可用原生逐字时间轴时，按非空白字素均分行时长，空白单元不占时长。估算只用于呈现；`IslandTextBlock` 将来源标记传给 GPU 与 Java2D 绘制路径，两条路径均禁止估算歌词触发长音效果。

### 4.7 `ui/`：侧边内容、颜色与播放控制

| 文件 | 职责 |
| --- | --- |
| `IslandLeadingContent.kt` | 绘制左侧专辑封面或频谱，并缓存封面图像 |
| `IslandTrailingContent.kt` | 绘制右侧频谱或播放状态 |
| `IslandPalette.kt` | 根据默认配色、设置和封面主色生成歌词、背景和频谱颜色 |
| `IslandBackgroundProgress.kt` | 在词岛背景中绘制整曲播放进度，包括注水和边缘细线；细线位于胶囊顶部或刘海底部 |
| `IslandProgressTransition.kt` | 背景进度的非线性淡入淡出和退出样式保留；独立状态由窗口帧更新，绘制不推进动画 |
| `PlaybackProgress.kt` | 共用的进度 / 音量呈现、悬停命中与鼠标手势；音量反馈期间点击 / 拖动调音量，播放进度仍在松手时一次提交并防止切歌后误 seek |
| `IslandVolumeFeedback.kt` | 音量反馈的悬停保持、拖动预览与请求合并、2 秒超时和连续过渡；收起时停止交互并平滑退出，按代次拒绝迟到结果 |
| `PlaybackIcon.kt` | 与字体无关的上一首、播放、暂停和下一首矢量图标 |
| `SyntheticSpectrum.kt` | 无需音频捕获的低成本模拟频谱 |

### 4.8 `ui/`：菜单、托盘、关于与项目身份

共享菜单模型：

```text
IslandMenuCommands
 ↓
PopupMenuEntry
 ├─ LightweightPopupMenu
 ├─ WindowsTray → spw-island-tray.exe
 └─ GtkTray
```

对应文件：

| 文件 | 职责 |
| --- | --- |
| `IslandMenu.kt` | 统一管理 Windows 托盘/弹出菜单、Linux 托盘和关于窗口 |
| `IslandMenuCommands.kt` | 创建共享菜单模型，并将菜单操作转换为设置或插件操作 |
| `PopupMenuEntry.kt` | 菜单标题、说明、分隔线、开关和操作项的通用数据模型 |
| `LightweightPopupMenu.kt` | 非 Linux 平台使用的进程内自绘弹出菜单 |
| `WindowsTray.kt` / `native/Tray.cs` | Windows 独立托盘程序的生命周期与图标事件转发；菜单仍在插件进程内 |
| `native/NotificationIcon.cs` | 固定 GUID 的 Shell 图标注册、消息转发与资源管理器重启恢复 |
| `GtkTray.kt` | Linux 托盘进程及菜单同步；GTK 缺失时使用 GIO / D-Bus，托盘失败不阻断词岛 |
| `AboutDialog.kt` | 组合分组状态与版权卡片；按文本实际宽度计算高度，窄屏或长说明可滚动 |
| `AboutStatusPanel.kt` | 分组排版、主状态层级、自动换行与异常原因展示 |
| `AboutText.kt` | 关于页共用的宽度测量接口和不可编辑换行文本 |
| `BufferedAboutWindow.kt` | 独立关于窗口；兼容绘制使用整帧透明缓冲，GPU 材质直接呈现 |
| `LiquidGlassSurface.kt` | 菜单与关于页共用的背景选择、首次显示准备和可见性生命周期 |
| `WindowAlphaFrame.kt` | 按设备像素密度替换完整透明帧，用抗锯齿蒙版保留圆角 |
| `IslandRuntimeStatus.kt` | 按播放、渲染与交互组织状态，分离摘要、诊断与捕获指标 |
| `DialogButton.kt` | 关于窗口统一按钮；绘制与命中使用同一圆角区域，关闭图标始终居中 |
| `ApplicationIdentity.kt` | 窗口/托盘共享的应用名称和图标，以及 Linux 托盘临时图标导出 |
| `ProjectLinks.kt` | 从构建资源读取项目地址并调用系统浏览器打开 |

完整设置仍由 SPW 原生插件配置页提供。托盘菜单只提供高频操作，不应逐渐演变成第二套完整设置界面。

关于窗口在 EDT 上更新已有状态行，状态变化不重建窗口；相同内容不重复替换文本。所有文字统一用内置 MiSans；说明文本不可选中或获取焦点。顶部关闭按钮用固定尺寸容器避免布局拉伸；按钮固定使用 BasicButtonUI 提供事件处理，自行绘制悬停、按下与键盘焦点，并将所有绘制裁剪到圆角轮廓，避免 GTK/Synth 改写字体或绘制默认按钮动画。Linux 使用整帧透明缓冲，避免子控件重绘时先清空矩形背景。

关于窗口独立于词岛窗口。显示期间抑制歌词控件展开，打开、关闭时清空悬停记录；其他窗口收到鼠标进入或移动事件时也清除旧记录。关闭关于窗口后，新的词岛鼠标事件可触发展开。

状态由 `IslandRuntimeStatus` 提供，窗口显示期间每秒更新，关闭后停止定时器。`AboutStatusPanel` 按“播放与显示”“渲染与材质”“音频与交互”排版，捕获指标与回退原因分开呈现；GNOME 指针扩展未连接时提供安装与重新登录提示。

`LiquidGlassSurface` 在玻璃首帧准备完成后显示窗口，初始化超时或失败时使用经典背景。隐藏事件分发结束后才释放 GPU 图层，避免 Skiko 的后续回调访问已脱离窗口的组件；窗口已重新打开或仍在准备显示时跳过旧隐藏事件的释放。

### 4.9 Windows 原生频谱 helper

Windows 实时频谱由单独的 C# helper 提供。

```text
SPW JVM
 ↓ ProcessSpectrum
spw-spectrum.exe
 ↓
目标 SPW 进程及其子进程 WASAPI loopback
 ↓
FFT
 ↓
4 个归一化频段
 ↓ stdout
ProcessSpectrum
 ↓
IslandLeadingContent / IslandTrailingContent
```

`native/`：

| 文件 | 职责 |
| --- | --- |
| `AudioInterop.cs` | WASAPI / COM 所需结构、接口和 `ActivateAudioInterfaceAsync` 声明 |
| `ProcessLoopback.cs` | helper 程序入口、目标进程树回环捕获、PCM 数据读取和频谱输出 |
| `Spectrum.cs` | Hann 窗、FFT、立体声功率合并和四个实际频段的 RMS 计算 |
| `SpectrumLevels.cs` | 将频段 RMS 映射成稳定、适合音乐可视化的 0～1 柱高 |

该 helper：

- 只捕获目标 SPW 进程及其子进程；
- 不读取麦克风；
- 不读取完整系统混音；
- 不保存 PCM；
- 只向插件输出四个频段值。

实时频谱不可用时由 `ProcessSpectrum` 切换到 `SyntheticSpectrum`，而不是影响词岛基本功能。

### 4.10 Linux Python bridge

`src/linux/resources/native/island-linux.py` 是 Linux 桌面辅助桥。

它只使用 Python 标准库，通过 `ctypes` 调用系统共享库。

具有两种运行模式：

#### `jvm`

通过标准 JNI Invocation API 加载宿主自带的 `libjvm.so`，启动独立的 `LinuxIslandMain`。

这允许插件在 jpackage 运行环境没有独立 `bin/java` 时仍使用宿主 JVM。

#### `tray`

优先加载 GTK 3；库名查找失败时尝试常见 Linux x64 系统库目录，GTK 3 不可加载时尝试 GTK 2。优先使用匹配 GTK 版本的 AppIndicator 创建持久托盘，不可用时回退到 GTK StatusIcon。查找限于进程可见的文件系统，不从 `/run/host` 混入其他运行环境的库。

两版 GTK 均不可加载时，交给 `island-dbus-tray.py`，通过 `libgio-2.0.so.0` 注册 `org.kde.StatusNotifierItem` 与 `com.canonical.dbusmenu`。桌面负责绘制菜单；图标使用网络字节序的 ARGB 像素，避免依赖桌面能否读取容器中的临时路径。桌面托盘服务重启后重新注册，管道关闭后退出；GTK 与 D-Bus 均失败时保留两边的错误。

菜单数据来自 `GtkTray`。D-Bus 路径按管道和总线事件更新，不新增轮询；标准和低性能模式共用此路径并保留菜单操作。

GTK 与 Swing/AWT 不在同一个 JVM 中初始化，以减少线程和桌面工具包冲突。

#### 指针与悬停

`IslandPointerPresence` 聚合词岛与子控件的鼠标事件，使用 120 ms 离开延迟。普通悬停不读取全局坐标；拖动词岛或进度条时保持展开，松手后恢复悬停判断。

鼠标穿透由 `X11InputRegion` 设置空 ShapeInput。悬停隐藏使用 `LinuxGlobalPointer`：X11 直接查询指针；KDE Wayland 和 GNOME Wayland 通过 `island-pointer.py` 获取坐标。进入时锁定隐藏区域，动画不改变退出边界。750 ms 未收到有效指针样本时恢复显示。

KWin 桥接通过会话 D-Bus 加载临时脚本，每 50 ms 更新指针。关闭功能、退出或连接失效时卸载脚本，桥接进程消失时脚本也会自行卸载；连接断开后尝试重连。

GNOME Wayland 使用 `SPW Island Pointer` 扩展，支持 GNOME 45–50。扩展按需调用 `global.get_pointer()`，通过只读 D-Bus 方法返回坐标。`install-gnome-pointer.py` 安装扩展，备份已有版本并保留其他扩展设置；首次安装或更新后需重新登录。扩展源码位于 `src/linux/resources/native/gnome-pointer/`，由 `gnomePointer` 任务生成 ZIP 并装入 Linux 插件资源。

#### PipeWire 频谱

`LinuxProcessSpectrum` 启动 `island-spectrum.py`，只匹配 SPW 及其子进程拥有的 `Stream/Output/Audio` 节点。服务端客户端 PID 优先于应用自报 PID；多个播放流同时存在时选择最新的活动流。`pw-record` 以 `object.serial` 指定目标，不连接其他应用或默认音源。

音频格式为 48 kHz、双声道 float32。helper 使用 2048 点 Hann FFT，分别计算双声道功率后合并；四个频段为 40–250、250–1000、1000–4000 和 4000–16000 Hz。频段值共用渐变参考电平，保留相对能量；界面负责攻击/释放平滑。PCM 不保存到文件。

缺少工具或目标流时使用模拟频谱，并提供来源状态；目标流重建后重新发现并连接。暂停、关闭词岛、不显示频谱及低性能模式都会停止捕获。

### 4.11 资源与构建入口

| 路径 | 职责 |
| --- | --- |
| `src/main/resources/preference_config.json` | 两平台共用的插件配置页声明，标明平台专属设置 |
| `src/linux/resources/native/` | Linux 独立界面、托盘、指针及频谱 helper 和 GNOME 扩展源码 |
| `src/main/resources/META-INF/extensions.idx` | 显式注册 `IslandPlaybackExtension` |
| `src/main/resources/project.properties` | 构建时写入项目源代码地址 |
| `native/shared-fonts/MiSansVF.ttf` | 两平台共用的 MiSans，可在构建时加入歌词、菜单和字体窗口资源 |
| `gradle.properties` | 项目版本与项目地址的统一来源 |
| `build.gradle.kts` | 依赖、平台资源、频谱 helper、插件包和源码包构建 |
| `settings.gradle.kts` | Gradle 项目名称和依赖仓库 |
| `NOTICE` | 重要来源和组合许可说明 |
| `THIRD_PARTY_NOTICES.md` | 第三方组件与许可证说明 |
| `licenses/` | 分发所需许可证全文 |

不要在代码中重新维护第二份版本号或项目地址。

---

## 5. 常见修改应该从哪里开始

### 修改 SPW 播放回调或适配新版 Workshop API

首先查看：

```text
IslandPlaybackExtension
PlaybackCallbackBridge
IslandRuntime
PlaybackTimeline
```

当前曲目和快捷键接入：

```text
WorkshopTrackMetadata
TrackMetadataLoader
IslandShortcut
```

宿主数据通过公开 API 进入 `host/`，再转换为核心模型。

### 修改切歌、播放、暂停、seek 或播放位置

查看：

```text
PlaybackTimeline
PlaybackHeartbeatRecovery
IslandRuntime
```

如果问题发生在 SPW 回调进入项目之前，再查看：

```text
IslandPlaybackExtension
PlaybackCallbackBridge
```

### 修改实验性多行歌词

查看：

```text
ActiveLyrics
PlaybackTimeline
IslandLyricsLayout
IslandLyricsPainter
```

完整歌词轴来源相关问题：

```text
IslandPlaybackExtension
PlaybackCallbackBridge
IslandRuntime
```

### 修改逐字歌词效果

查看：

```text
IslandLyricsPainter
LyricPainter
AmllWordPainter
AmllMotion
WordGeometry
LyricGlow
```

低性能逐字模式：

```text
TimedKaraokeBoundary
PerformanceProfile
```

### 长音规则

`longtone/` 将歌词 timing cell 的长音判断与动效分开。`LongToneRuleContext` 提供规范化文本、持续时间、字素数量与 Unicode Script 等通用信息；`LongToneRuleRegistry` 按优先级选择规则。没有匹配规则，或最高优先级存在冲突时，均不触发长音。

各语言规则位于 `longtone/rules/`。规则实现 `LongToneRule`，由 `matches()` 声明适用范围，再由 `isLongTone()` 判断当前 cell；无法可靠判断时返回 `false`。通用文本处理放在 Context 中，语言特有的词形、音节或例外留在对应规则内。规则不控制辉光、缩放、位移和缓动，这些效果由 `AmllMotion` 统一处理。

内置规则可参考 `HanRule`、`JapaneseRule`、`KoreanRule` 和 `EnglishRule`。语言规则在 `LongToneRuleRegistry` 中注册；优先级越高，匹配条件应越专用、越可靠。涉及优先级调整的 PR 应说明覆盖关系，并附带有持续时间的真实歌词样例，分别展示触发与不触发的情况。

### 修改字体、缺字 fallback 或文字测量

查看：

```text
SystemUiFont
LyricTypography
IslandTextBlock
LyricPainter
```

不要让测量使用一套字体逻辑而绘制使用另一套。

### 修改词岛尺寸或歌词布局

查看：

```text
IslandLyricsLayout
IslandTextBlock
IslandContentLayout
IslandPanel
IslandGeometry
```

### 修改胶囊、刘海或圆角

查看：

```text
IslandGeometry
IslandNotchGeometry
IslandHoverGeometry
ContinuousCornerPath
IslandAlphaMask
IslandSurface
```

窗口位置一般不应在这些文件里修改。

### 修改拖动、磁吸或屏幕位置

查看：

```text
IslandWindow
IslandPlacement
IslandAnchor
HostSettings
```

### 修改鼠标穿透、全屏隐藏或悬停隐藏

Windows：

```text
IslandWindow
IslandHoverVisibility
IslandHoverMotion
IslandTransitionMotion
IslandHoverGeometry
IslandNotchGeometry
IslandSurface
WindowsOverlay
```

Linux 输入与指针：

```text
IslandWindow
IslandSurface
X11InputRegion
IslandPointerPresence
LinuxGlobalPointer
island-pointer.py
gnome-pointer/extension.js
```

### 修改封面、歌曲时长或封面取色

查看：

```text
TrackMetadataLoader
LocalTrackMetadata
CoverColorExtractor
IslandPalette
IslandLeadingContent
```

需要整曲时长的功能还包括：

```text
PlaybackProgress
IslandBackgroundProgress
```

### 修改实时频谱

宿主管理：

```text
SpectrumSource
IslandRuntime
LinuxIslandProcess
PerformanceProfile
```

Windows 捕获/算法：

```text
ProcessSpectrum
native/AudioInterop.cs
native/ProcessLoopback.cs
native/Spectrum.cs
native/SpectrumLevels.cs
```

Linux 捕获/算法：

```text
LinuxProcessSpectrum
src/linux/resources/native/island-spectrum.py
IslandWire / RemoteSpectrum
```

界面：

```text
IslandLeadingContent
IslandTrailingContent
SyntheticSpectrum
```

### 修改展开播放控件

查看：

```text
IslandPanel
IslandExpandedMotion
IslandTransitionMotion
IslandExpandedContentTransition
PlaybackProgress
PlaybackIcon
IslandWindow
```

播放命令最终由 `PlaybackActions` 回到 `IslandRuntime` 或 Linux IPC。

`scroll_volume` 默认为 `false`。`IslandVolumeFeedback` 仅在正常交互且控件展开时接收滚轮，按每格 2% 发送增量。音量条未完全消失时，悬停或拖动会保持显示，移开后重新计时 2 秒。点击 / 拖动使用绝对音量，立即预览并按窗口帧时钟合并宿主请求，松手时提交最终值；拖动捕获由现有 GPU / Swing 输入路径保留，拖动期间保持词岛展开。低性能模式沿用 `PerformanceProfile` 的低帧率降低请求频率，并通过 `animateLayout` 直接切换样式。

进度填充、时间 / 音量标签在 GPU 与 Java2D 路径共用呈现数据；收起时立即取消音量手势并拒绝该次展开中尚未返回的结果，保留当前呈现状态，平滑过渡回播放进度。窗口完全隐藏时复位音量反馈。Linux 使用 `volume` / `set_volume` 命令分别传递增量 / 绝对值，通过 `IslandVolumeResult` 返回实际音量；复用现有发送线程，不增加后台轮询。

### 修改设置

通常同时查看：

```text
preference_config.json
IslandSettings
HostSettings
IslandConfigFile
实际使用该设置的组件
```

如果设置属于菜单高频操作：

```text
IslandMenuCommands
```

Linux 设置同步还要确认：

```text
RemoteSettingsStore
```

### 修改托盘或右键菜单

共享内容：

```text
IslandMenu
IslandMenuCommands
PopupMenuEntry
```

Windows / 非 Linux 自绘弹窗：

```text
LightweightPopupMenu
GlobalMenuDismisser
SystemTheme
```

Windows 托盘身份与 SPW 分离：

```text
WindowsTray
WindowsTrayExecutable
native/Tray.cs
native/NotificationIcon.cs
```

Linux：

```text
GtkTray
LinuxHelper
island-linux.py
island-dbus-tray.py（GTK 缺失时）
```

### 修改 Linux 独立界面进程

查看：

```text
LinuxIslandProcess
IslandWire
LinuxIslandMain
LinuxHelper
island-linux.py
```

涉及透明窗口或鼠标输入：

```text
BufferedIslandWindow
IslandSurface
X11InputRegion
IslandPointerPresence
LinuxGlobalPointer
```

### 修改关于窗口、项目名称或许可证展示

查看：

```text
AboutDialog
BufferedAboutWindow
DialogButton
IslandRuntimeStatus
ApplicationIdentity
ProjectLinks
NOTICE
THIRD_PARTY_NOTICES.md
licenses/
```

---

## 6. 代码组织原则

### 6.1 按职责拆分，而不是按行数拆分

允许在现有文件中加入属于同一职责的新逻辑。

例如：

- `IslandPlacement` 增加新的位置计算规则；
- `LyricPainter` 增加一种单行歌词绘制行为；
- `HostSettings` 增加一个现有配置格式的解析。

不需要因为文件多了几十行就创建新类。

当一个文件开始同时承担明显不同的职责，或者继续扩展会显著增加理解和修改成本时，再按照实际职责拆分。

好的拆分：

```text
窗口生命周期
→ IslandWindow

位置策略
→ IslandPlacement

轮廓计算
→ IslandGeometry

歌词绘制
→ IslandLyricsPainter
```

不推荐：

```text
FeatureManager
FeatureHelper
FeatureUtils
FeatureService
```

如果它们只是在不同文件之间转发同一件简单的事情。

### 6.2 文件应当可以说明自己的存在理由

新文件通常至少满足一项：

- 有独立职责；
- 有独立生命周期或状态；
- 可以被多个调用者复用；
- 隔离平台或宿主实现；
- 将明显复杂的一块逻辑从上层协调者中移出；
- 能够独立理解和修改。

不要仅仅为了缩短原文件而拆分。

### 6.3 不要继续扩大复杂度中心

目前以下文件天然承担较多协调工作：

```text
IslandWindow
HostSettings
IslandRuntime
```

向这些文件增加逻辑前，先判断新功能究竟属于：

- 窗口生命周期；
- 设置存储；
- 运行时组装；

还是已经可以形成独立职责。

尤其不要因为某个功能最终“显示在词岛上”，就默认把它加入 `IslandWindow`。

### 6.4 优先最小改动

一次修改围绕一个明确目标。

修复一个问题时，不要顺手：

- 重写无关模块；
- 全局格式化；
- 重命名大量无关变量；
- 更换完全无关的实现方式；
- 添加与当前需求无关的未来框架。

如果当前需求确实暴露了职责问题，可以整理与本次修改直接相关的代码。

### 6.5 不提前解决不存在的问题

容错和 fallback 应对应：

- 已知宿主行为；
- 实际发生过的错误；
- 明确的平台差异；
- 合理且成本低的失败路径。

不要仅因为“理论上可能”就增加：

- 多层 fallback；
- 多套备用实现；
- 额外线程；
- 额外缓存；
- 额外反射；
- 大量异常吞噬。

一个失败即安全停用的可选功能，通常比五层互相兜底更容易维护。

---

## 7. 宿主 API 与权限

优先使用 SPW Workshop API 的公开接口。

`spmod.PluginPermissions` 声明 `LIBRARY_READ` 和 `KEY_BINDINGS`，实际授权由 SPW 管理。查询前使用 `isPermissionGranted()` 检查，并处理检查后撤销权限造成的拒绝。

曲库读取未授权时，启动查询不恢复当前歌曲，后续曲目与歌词回调仍可工作；本地文件读取继续补充封面和时长。快捷键未授权时跳过注册，托盘和插件设置不受影响。权限变更后可停用并重新启用插件。

快捷键必须在插件 `start()` 的调用线程注册，默认 `Ctrl+Shift+D`，允许用户在 SPW 中配置全局绑定。停用时关闭注册句柄，保留宿主管理的用户键位。

宿主对象仅在 `host/` 内使用，不传播到 `core/`。

dev23 尚无公开音量 API。`HostVolumeControl` 使用宿主类加载器按确切签名绑定 SPW 1.19 的 `AppConfig.getVolume()` / `updateVolume(float)`、`PlaybackController.changeVolume(float)` 与 `Spkv.encode(String, float)`，在宿主 EDT 同步界面、播放器和保存值。音量反馈由词岛自身呈现。绑定仅在首次音量操作发生，读写由滚轮和点击 / 拖动触发；到达音量边界时不重复写入。不扫描类、不修改方法；绑定或调用失败时返回空结果，每个插件生命周期最多提示一次。宿主版本更新后应重新确认此内部接入路径。

---

## 8. 线程与生命周期

项目包含多个线程和进程边界，修改时应知道代码运行在哪里。

### SPW 回调

`IslandPlaybackExtension` 接收宿主回调。

回调路径应尽量快速，只转换数据并更新内部状态，不在这里进行文件读取或复杂 UI 操作。

当前曲目和封面查询由 `TrackMetadataLoader` 的后台线程执行。Windows 词岛与 SPW 共用 JVM；排查两边同时卡顿时，应采集复现阶段的线程栈或性能采样，区分宿主回调、EDT 绘制和 JVM 停顿。

### `PlaybackTimeline`

时间轴内部状态通过同步访问保护。

UI 通过 `snapshot()` 获取稳定快照，而不是直接读取宿主对象。

### Swing EDT

Windows 的：

- `IslandWindow`
- `IslandPanel`
- Swing 菜单；
- 关于窗口；

都应在 EDT 上操作。

宿主调用插件生命周期方法时可能持有插件管理锁。`IslandUiLifecycle` 将 Windows 窗口创建和销毁异步投递到 EDT，生命周期线程不使用 `invokeAndWait`；停用先取消待执行的启动，已开始创建的窗口完成后立即释放。字体窗口也拒绝停用后的待执行打开操作。后台资源逐项关闭，一项失败仍继续释放其余资源。

Windows 原生 DLL、音频辅助程序和 ICU 数据由 `WindowsNativeResources` 按需复制到临时运行目录；同一插件类加载器复用副本，新类加载器使用独立目录。已加载的 DLL 可以存活到 JVM 退出，因此不直接从可替换的插件安装目录加载。

托盘程序由 `WindowsTrayExecutable` 部署到本地应用数据实际目录下的 `spw-island\tray\spw-island-tray.exe`，启动时核对内容，仅在需要时更新。`WindowsApplicationData` 使用 `SHGetKnownFolderPath` 的 `KF_FLAG_RETURN_FILTER_REDIRECTION_TARGET` 查询实际路径，兼容 Store / MSIX 宿主的应用数据虚拟化。`NotificationIcon` 以固定 GUID 注册图标，新增、版本设置、注销及资源管理器重启后的恢复使用相同身份。辅助程序在消息循环就绪后发送 `READY`，保证随后立即到达的 `STOP` 可以退出该循环。诊断日志区分辅助程序就绪、退出、启动失败及使用宿主身份的 AWT 降级。两种性能模式共用上述按需部署路径，不增加轮询或持续后台任务。

不要在绘制函数中执行：

- 文件读取；
- 网络请求；
- 宿主反射扫描；
- 阻塞等待；
- 长时间计算。

### 曲目元数据

`TrackMetadataLoader` 在单独的有界后台线程查询宿主并读取本地文件。关闭后不再发布结果；切歌通过代次检查拒绝旧结果。

不要把 Jaudiotagger 或图片解码移回 EDT。

### 逐字几何

`IslandLyricsPreparation` 提前预备后续歌词的测量结果，按需请求轮廓和逐字几何。布局缓存和 `LyricTypography` 的缓存锁只用于读取与发布结果，冷塑形在锁外执行，后台任务不会因持有缓存锁阻塞绘制线程。

`WordGeometry` 在有界后台线程准备逐字几何。`LyricGlyphOutline` 仅对当前 grapheme 附近的轮廓做相交运算，避免为每个字重新解析整行。字符、轮廓段数和累计运算量均有上限；超出预算时使用已有的逐字高亮，省略逐字浮动和辉光。逐字几何仅由有界缓存持有，整行塑形缓存不再重复保留裁剪字形。低性能模式继续跳过逐字几何准备。

动画线程可以在几何尚未准备完成时使用轻量 fallback，不应等待后台任务。

### 实时频谱

`ProcessSpectrum` 管理 Windows helper 进程，`LinuxProcessSpectrum` 管理 Linux PipeWire helper 进程；两者均通过后台线程读取频段值。

UI 只读取最新四个频段值。

### Linux

Linux 至少存在：

```text
SPW JVM
Linux IPC 后台线程
独立界面 JVM
独立界面 Swing EDT
托盘 helper 进程（GTK 或 GIO / D-Bus）
PipeWire 频谱 helper 进程（捕获启用时）
KWin / GNOME 指针 helper 进程（Wayland 悬停隐藏启用时）
```

不要假定宿主线程、Linux UI EDT 和 GTK 主循环属于同一个运行环境。

---

## 9. 设置开发规则

增加一个设置时，先确认它属于什么范围。

通常需要同步：

```text
IslandSettings
HostSettings
preference_config.json
实际消费者
```

两平台共用同一份 `preference_config.json`。平台专属项标明适用系统，运行时仍通过平台能力约束处理，保持已有 `island.json` 键名和默认值兼容。

### 配置兼容

已有用户的 `island.json` 应尽量继续工作。

必须修改持久化格式时：

1. 读取已存在值；
2. 转换为新表示；
3. 保存成功后继续使用新格式；
4. 保留合理默认值。

迁移应针对真实存在的历史格式，不需要为从未发布过的理论状态建立长期迁移层。

---

## 10. 性能原则

词岛会在整个播放期间持续运行，因此高频路径比一次性操作更重要。

需要特别谨慎的代码包括：

- 每帧绘制；
- 每帧布局；
- 定时轮询；
- 文件访问；
- 字体和 glyph 运算；
- 反射；
- 图片处理；
- 进程通信；
- 音频处理。

不要为了极小收益提前建立复杂优化，但一个操作如果会每秒执行几十次，就应该避免明显的重复高成本工作。

### 低性能模式

持续性视觉功能新增后，应考虑低性能模式行为。

`PerformanceProfile` 是统一入口。

普通模式跟随词岛所在显示器的刷新率，上限 120 FPS；低性能模式上限 15 FPS。刷新率未知时按 60 Hz 计算，跨屏时重新查询，同一屏幕每 2 秒检查显示模式。调度扣除本帧已花的处理时间，始终只保留一个待执行帧；这些是调度目标，实际呈现帧率取决于绘制和桌面环境。

低性能模式可以：

- 降低刷新率；
- 关闭复杂逐字几何；
- 使用简化逐字边界；
- 关闭复杂布局动画；
- 使用模拟频谱；
- 降低平台状态检查频率。

不要在不同组件里各自重新判断一套“低性能模式是什么意思”。

---

## 11. 验证策略

项目不以测试覆盖率作为目标，也不保留永久测试集。验证使用最小临时用例和实际宿主操作，结束后清理临时代码、脚本及新增测试依赖。

不同改动更适合不同验证方式。

### 纯逻辑

例如：

```text
PlaybackTimeline
ActiveLyrics
IslandPlacement
TimedKaraokeBoundary
```

可以使用最小输入输出验证。

临时用例应覆盖受影响的边界、状态切换和异常输入。

### UI 和动画

优先：

1. 编译；
2. 在真实 SPW 中运行；
3. 验证受影响的视觉和交互；
4. 检查相关旧行为。

大量 mock Swing 或模拟窗口管理器通常不能替代真实环境验证。

### 宿主 API

涉及 SPW 回调、插件启停或兼容探测时，应在真实 SPW 中验证。

应检查回调顺序、异步结果、配置同步和停止后的资源释放。

### 平台能力

Windows 原生能力需要真实 Windows 环境验证。

Linux 窗口、托盘、GTK 和 X11/XWayland 行为需要真实桌面环境验证。

Linux 交互应分别检查 X11、GNOME Wayland 和 KDE Wayland，重点包括：

- 移入子控件、移向其他窗口和拖动结束后的展开状态；
- 穿透后的下层点击、悬停隐藏及指针断开后的恢复；
- GNOME 扩展安装、重新登录、禁用和重连；
- 多显示器与混合缩放下的指针、轮廓和位置对齐；
- 连续拖动、seek 和设置修改后的宿主确认；
- 目标音频流选择、切换设备后的重连，以及暂停和低性能模式下的捕获停止。

不要根据一个操作系统的运行结果推断另一个平台也正确。

### 临时验证

为了确认一次修改而创建的：

- 临时代码；
- 临时日志；
- 临时脚本；
- 临时测试入口；

应在验证结束后清理。

不要为了覆盖理论边界而给生产代码增加大量仅用于测试的接口。

---

## 12. 构建

需要 JDK 25 和网络连接。Workshop API 与打包插件使用 dev23，Kotlin / Compose compiler 使用 2.4.20，Compose Multiplatform 使用 1.12.1，Gradle Wrapper 使用 9.8.0。

具体 Kotlin、Gradle 和依赖版本以当前构建文件为准。

在 Windows x64 上构建统一插件包：

```powershell
.\gradlew.bat plugin --no-daemon
```

原生组件需要系统 .NET Framework 4.x 的 C# 编译器、MSVC x64 C++ 工具、CRT/STL，以及带 C++/WinRT 的 Windows SDK。`plugin` 同时打包 Windows helper、Linux helper、GNOME 扩展和两平台 Skiko 原生库；运行时按实际系统加载，Linux 的桌面行为仍需在 Linux 宿主验证。

输出位于：

```text
build/libs/spw-island-<版本>.spmod
```

### 依赖边界

SPW 提供：

- Workshop API；
- Kotlin 标准库；
- PF4J。

JNA 与 JNA Platform 也复用 SPW 自带版本，不重复装入插件的 `lib/`。Linux 独立界面的 classpath 会加入 `Native` 和 `X11` 所在的宿主归档；当前 SPW 1.19 提供的 JNA 5.19.1 可运行插件使用的 5.17.0 API。

插件自身分发：

- Jaudiotagger；
- Gson（配置快照解析）；
- Windows 字体窗口使用的 Compose 运行库；
- 两平台词岛使用的 Skiko，以及 Windows x64 和 Linux x64 原生库；
- Kotlin 标准库与协程运行库（Linux 子 JVM 优先使用插件自带版本）；
- 插件资源；
- 平台需要的 native/helper 文件。

Workshop Gradle 插件统一生成 Manifest、权限声明和 `.spmod`。运行包不包含 Workshop API 或 PF4J；Linux 子 JVM 使用的 Kotlin 和协程运行库随插件分发。

### 源码分发

插件构建同时生成源码归档，并随对应插件包分发所需源代码和许可证材料。

修改构建结构时，不要只确认运行包能启动，还应确认：

- 对应源码仍被打包；
- 第三方许可证仍被包含；
- 平台专用源码没有遗漏。

---

## 13. 贡献方式

Bug 报告、功能建议、兼容性反馈、设计想法和代码贡献都欢迎。

对于：

- 新功能；
- 较大的 UI 行为变化；
- 新的平台能力；
- Workshop API 适配；
- 会影响现有职责划分的修改；

建议先创建 Issue 讨论目标和范围，再开始完整实现。

这样可以先确认：

```text
这个功能是否需要
↓
行为应该是什么
↓
应该放在哪个职责里
↓
最后再决定实现
```

通常比完成一整套实现后再重新调整方向更有效。

### 可以直接提交 PR 的情况

例如：

- 明确的小型 bug；
- 拼写和文档修正；
- 范围很小的兼容修复；
- 已经在 Issue 中确认过实现方向的功能。

### 较大的直接 PR

未经讨论直接提交的较大 PR 也会被查看。

但项目在合并前可能：

- 调整实现方式；
- 拆分或合并职责；
- 缩小功能范围；
- 删除不需要的 fallback；
- 将部分实现替换为与现有架构更一致的方案。

这不代表其中的思路或工作没有价值，而是最终代码需要继续与整个项目使用同一套职责边界和维护方式。

对于复杂功能，提前讨论通常可以减少双方重复工作。

---

## 14. PR 与修改范围

提交代码时，请尽量让一次 PR 表达一个清楚的目的。

避免：

```text
实现功能 A
+
顺便重构 B
+
顺便清理 C
+
顺便重新格式化 D
```

无关变化越少，越容易判断真正修改了什么。

如果 review 后改变了实现方向，可以直接更新现有 PR，不需要为了保留最初方案而继续维护已经不采用的代码。

---

## 15. 第三方代码与许可证

项目包含或适配了来自多个项目的代码、设计与依赖。

主要包括：

- SPW Workshop API；
- Lyricify 灵动词岛视觉概念；
- Apple Music-like Lyrics（AMLL）歌词动画；
- JNA；
- Jaudiotagger；
- Gson；
- MiSans。

第三方来源和许可要求以：

```text
NOTICE
THIRD_PARTY_NOTICES.md
licenses/
```

为准。

新增：

- 第三方代码；
- 算法移植；
- 图片或字体；
- native 实现；
- 具有署名要求的视觉设计；

都需要同时检查对应许可证。

不要删除已有来源说明。

`AmllMotion.kt` 属于单独标记的 AMLL 动画移植代码，修改或重新使用时尤其应保留其来源和许可证边界。

---

## 16. 提交前检查

提交前至少确认：

- [ ] 改动解决的是本次实际目标；
- [ ] 没有顺便修改无关代码；
- [ ] 新逻辑位于正确的职责模块；
- [ ] `core/` 没有新增 SPW、Swing、JNA 或平台依赖；
- [ ] 没有为了理论情况增加明显过度的 fallback；
- [ ] 没有把文件机械拆成只有转发作用的小层；
- [ ] 也没有继续把独立职责堆进已经复杂的协调类；
- [ ] 高成本工作没有进入 Swing 绘制或宿主回调热路径；
- [ ] 受影响的平台能够完成对应构建；
- [ ] 需要真实 SPW 或桌面环境验证的功能已经实际检查；
- [ ] 一次性调试代码和临时验证文件已经清理；
- [ ] 新增设置时检查了配置 schema、`IslandSettings` 和 `HostSettings`；
- [ ] 新增或删除独立源码文件时更新了本文代码地图；
- [ ] 用户可见行为改变时检查了用户文档；
- [ ] 第三方代码或资源变化时检查了许可证和源码分发。

---

## 17. 维护原则

项目不追求为了“完整”而实现尽可能多的功能。

通常优先考虑：

```text
行为正确
 ↓
真实环境可用
 ↓
职责清楚
 ↓
容易继续修改
 ↓
性能合理
 ↓
再考虑额外抽象和扩展
```

代码不需要预先解决所有未来情况。

一个好的实现应该让后来的人能够比较快地回答：

```text
这个功能由谁负责？
数据从哪里来？
状态在哪里保存？
平台差异在哪里结束？
我要修改它需要读哪些文件？
```

如果这些问题开始很难回答，通常意味着职责边界需要重新检查。
