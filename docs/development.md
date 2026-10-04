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

本地歌曲的时长、封面与封面颜色通过另一条只读链路补充：

```text
Track
 ↓
TrackMetadataLoader
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

宿主公开 API 无法提供的完整歌词信息目前被隔离在 `HostPlaybackProbe` 中。它属于兼容层，不应成为其他模块的通用数据源。

`HostLyricsRecovery` 将完整歌词探测放在单个有界后台线程，宿主歌词回调只提交请求。已读取的歌词行通过索引复用整首歌词轴；探测期间的新请求合并为最新一行，失败后至少间隔 2 秒再由后续回调重试。异步结果由 `PlaybackTimeline` 按歌词代次验收：切歌以及 Idle / Ended 清空歌词时推进代次，避免切歌、A → B → A 或同曲重播后复用旧结果；文档规范化在时间轴同步区外完成。低性能模式禁用私有歌词探测，插件关闭时终止该工作线程。

### 3.4 本地歌曲元数据

SPW 的播放回调主要提供播放所需的曲目信息。

词岛额外需要：

- 歌曲时长；
- 专辑封面；
- 封面主色。

这部分不依赖 SPW 私有数据库。

```text
trackChanged
 ↓
TrackMetadataLoader
 ↓ 后台
LocalTrackMetadata
 ├─ Jaudiotagger：时长 / 内嵌封面
 └─ cover.* / folder.*：同目录封面
 ↓
CoverColorExtractor
 ↓
TrackMetadata
```

读取过程只读本地文件，不联网、不修改标签，也不解码完整音频。

晚到的旧曲目元数据不会覆盖当前曲目。

### 3.5 Windows UI

Windows 由 `IslandRuntime` 创建 `IslandWindow`，使用 Java2D 绘制词岛与歌词动画。Compose 字体窗口由插件设置按需打开。

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

实时频谱通过 `SpectrumSource` 提供：Windows 使用 `ProcessSpectrum`，Linux 使用 `LinuxProcessSpectrum`。Linux PCM 在 Python helper 中完成 FFT，只将四个频段值送往宿主；`IslandSpectrum` 以约 30 Hz 独立传给界面，不增加歌词/封面状态频率。低性能模式、暂停或不显示频谱时停止 Linux 捕获。

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

`IslandWire` 定义两边共享的状态、频谱帧与命令模型。每个命令有递增 ID；宿主在操作完成后记录确认水位，并在读取播放 / 设置快照之前读取水位。界面的 `RemotePlayback` 和 `RemoteSettingsStore` 保留最新 seek、位置和菜单设置预览，忽略旧状态，确认后交接到宿主值；失败则随确认状态恢复，切歌会取消旧 seek。这样松手后不会先跳回旧位置。音频帧不进入 EDT，界面读取最近有效样本；350 ms 无新数据时归零。

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

托盘菜单只保留四个常用开关和三个操作，状态详情集中在“关于与状态”窗口。该窗口打开时读取状态，并在显示期间每秒更新；关闭后停止定时器。显示、性能、频谱、鼠标交互各自列出当前状态与说明，区分配置停用、等待播放、连接中和异常回退；GNOME 指针扩展未连接时提供安装与重新登录提示。源码入口与简短版权卡片保留在窗口内，完整署名和许可随 ZIP 提供。菜单中的穿透勾选包含悬停隐藏自动启用的穿透，关闭穿透时也关闭悬停隐藏。

因此托盘菜单不是第二套配置系统。`IslandConfigFile` 使用 Gson 解析一次读取到的字节，而不再次读取宿主共享的可变 `ConfigHelper`；空文件、部分 JSON 和并发修改不会覆盖已接受设置。写入先检查读取后的新编辑，再通过同目录临时文件原子替换。SPW 表单短暂截断文件时，显式设置操作最多重试 6 次，间隔 10 ms。Linux 表单拥有字体字段，不再用旧字体强行恢复；插件拥有的位置仍与表单编辑合并。

Windows 和 Linux 使用不同的 `preference_config.json`，用于隐藏对应平台没有可靠实现的设置。

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
| `LyricFontWeight.kt` | 歌词字重的稳定存储值与解析 |
| `Lyrics.kt` | `Track`、`Word`、`LyricLine`、`PlaybackSnapshot`、`TrackMetadata`、封面数据等核心模型 |
| `PerformanceProfile.kt` | 标准模式和低性能模式的统一性能预算 |
| `PlaybackHeartbeatRecovery.kt` | 在启动时缺失播放状态回调的情况下，根据连续播放位置心跳恢复状态 |
| `PlaybackTimeline.kt` | 汇总宿主回调、补间播放位置、处理 seek；歌词源变化时合并歌词轴，帧快照复用合并结果 |

修改核心播放规则时，应首先确认逻辑能否留在这里，而不是依赖 SPW 或 UI。

### 4.2 `host/`：SPW 接入

| 文件 | 职责 |
| --- | --- |
| `CurrentTrackRecovery.kt` | 插件启动错过曲目回调时进行短时当前曲目恢复 |
| `HostPlaybackProbe.kt` | 只读探测当前 SPW 内部播放对象和完整歌词轴；属于隔离的兼容代码 |
| `HostLyricsRecovery.kt` | 有界后台完整歌词探测、行索引复用和请求合并；按歌词代次发布，失败请求延后重试 |
| `HostSettings.kt` | 适配 `SettingsStore`，负责配置同步、合并、保存重试和旧配置迁移 |
| `IslandConfigFile.kt` | 解析同一次读取的完整 JSON 快照，拒绝空/部分内容，检查新编辑并原子保存 |
| `IslandPlaybackExtension.kt` | 接收 SPW `PlaybackExtensionPoint` 回调并转换为项目自己的模型 |
| `IslandPlugin.kt` | PF4J 插件生命周期；创建和释放唯一的 `IslandRuntime`，并提供配置页按钮入口 |
| `IslandRuntime.kt` | 宿主侧组合根；组装时间轴、设置、元数据、频谱、窗口、平台远程 UI 和播放操作 |
| `PlaybackCallbackBridge.kt` | 暂存运行实例建立之前到达的播放回调，并在 runtime 就绪后转交 |
| `TrackMetadataLoader.kt` | 单后台工作线程读取当前曲目的本地元数据；新曲目请求优先，旧结果通过代次检查丢弃 |

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
| `SystemTheme.kt` | 读取 Windows 应用深浅色主题 |
| `DotNetFrameworkRuntime.kt` | 在启动实时频谱 helper 前静默检查所需 .NET Framework CLR |
| `ProcessSpectrum.kt` | 管理 Windows 进程音频频谱 helper 的生命周期、数据读取和模拟频谱降级 |

#### 菜单与 X11

| 文件 | 职责 |
| --- | --- |
| `GlobalMenuDismisser.kt` | 弹出菜单显示期间监听菜单外点击；Windows 使用低级鼠标钩子，其他平台使用适当 fallback |
| `X11MenuDismisser.kt` | `GlobalMenuDismisser` 的 XToolkit/X11 外部点击 fallback；当前 Linux 主菜单路径主要使用 GTK 托盘 |
| `X11InputRegion.kt` | 根据词岛真实轮廓设置 X11 ShapeInput，使透明区域不截获鼠标 |

#### Linux 启动

| 文件 | 职责 |
| --- | --- |
| `LinuxHelper.kt` | 提取并启动 Linux Python helper，并为独立界面 JVM 构造 classpath |

平台功能不要直接加入 `core/`。

### 4.4 `remote/`：独立进程通信

| 文件 | 职责 |
| --- | --- |
| `RemoteSettingsStore.kt` | 即时应用 Linux 菜单与位置操作，宿主确认后移除待确认编辑 |
| `IslandWire.kt` | 定义宿主与子 JVM 之间的 `IslandState`、`IslandCommand`，以及子进程使用的 `RemotePlayback` |
| `LinuxIslandProcess.kt` | 宿主侧独立界面进程管理；发送播放状态和设置，接收播放/seek/设置等命令 |
| `LinuxIslandMain.kt` | Linux 子 JVM 入口；接收状态、创建 `IslandWindow` 并把用户操作传回宿主 |

控制通道使用父子进程匿名管道，不提供网络接口。

### 4.5 `ui/`：窗口与几何

| 文件 | 职责 |
| --- | --- |
| `IslandWindow.kt` | 顶层窗口生命周期、帧更新、拖动、hover、可见性、窗口定位和平台窗口状态 |
| `IslandPanel.kt` | 词岛主体组件；组合背景、左右内容、歌词、播放按钮和展开内容 |
| `IslandSurface.kt` | 稳定透明画布、持续更新悬停隐藏时的离屏内容并呈现动画帧，以及输入区域计算 |
| `BufferedIslandWindow.kt` | Linux 上先离屏完成透明帧，再一次提交到窗口，降低透明窗口闪烁 |
| `IslandPlacement.kt` | 工作区计算、九宫格锚点、刘海顶部约束、拖动磁吸和稳定画布位置 |
| `IslandGeometry.kt` | 静止胶囊/刘海轮廓、内容安全内缩、进度细线内缩和轮廓缓存 |
| `IslandNotchGeometry.kt` | 静止与形变刘海共用的顶部粘连、主体横向拉伸与高度收缩几何 |
| `ContinuousCornerPath.kt` | 连续圆角 / 超椭圆圆角路径生成 |
| `IslandAlphaMask.kt` | 常驻设备像素内容层，持续离屏绘制；最终合成时应用内容淡出、位移和抗锯齿轮廓蒙版 |
| `IslandHoverVisibility.kt` | 鼠标穿透模式下的进入区域锁定与隐藏目标判定 |
| `IslandTransitionMotion.kt` | 隐藏、控件和进度淡入淡出共用的有限时长 Hermite 运动；可配置时长、两端过冲和单次回弹，反向时接续速度 |
| `IslandHoverMotion.kt` | 悬停隐藏运动参数及完整显示 / 动画中的状态；恢复时单次回弹 |
| `IslandExpandedMotion.kt` | 与隐藏动画共用速率的控件打开 / 收起运动，两端各有一次过冲回弹 |
| `IslandHoverGeometry.kt` | 将可见状态映射为刘海形变或胶囊收圆，并计算内容淡出、位移和画布留边 |
| `IslandExpandedContentTransition.kt` | 常驻展开控件条的设备像素缓冲，最终合成时统一应用非线性淡入、位移和打开过冲 |

`IslandWindow` 是窗口状态协调者，不应继续承担具体歌词、形状或菜单绘制。

悬停隐藏沿 `IslandHoverVisibility → IslandHoverMotion → IslandHoverGeometry → IslandSurface / IslandPanel` 传递。进入时锁定正常轮廓，动画和歌词尺寸变化不影响本次离开判定。刘海仅沿顶部拖动；隐藏时顶部粘连区和主体同时向左右拉伸、压低高度，恢复时越过正常尺寸后回到终点。胶囊先横向收圆，再等比缩小，恢复时先长成正常高度的圆，再向两侧拉长并回弹。两种轮廓都与内容绘制分开，内容层按设备像素密度绘制后统一淡出和裁切。低性能模式沿用 `PerformanceProfile.animateLayout`，直接切换到隐藏或显示终点。

画布和像素缓冲提前覆盖横向拉伸、回弹及完整控制区高度，并依据已预备的后续歌词高度扩容。内容层和展开控件条始终使用常驻缓冲，悬停完全隐藏时仍绘制最新内容；隐藏仅改变最终轮廓和合成透明度。控件持续挂载，不通过 `isVisible` 切换动画状态；恢复与正常显示使用同一合成路径。缓冲按 64 像素对齐，仅增长并复用。

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
| `LyricTypography.kt` | 使用 `TextLayout` 统一文本塑形、测量、字体 fallback 和字形轮廓缓存 |
| `AmllWordPainter.kt` | 对已塑形的 grapheme 应用逐字高亮、位移、缩放和辉光 |
| `AmllMotion.kt` | AMLL 来源的逐字浮动、长音强调和歌词行切换运动曲线；只接收规则给出的长音结果 |
| `longtone/` | 准备 Unicode 文本信息、选择语言规则，并保守判断当前 timing cell 是否为长音 |
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

### 4.7 `ui/`：侧边内容、颜色与播放控制

| 文件 | 职责 |
| --- | --- |
| `IslandLeadingContent.kt` | 绘制左侧专辑封面或频谱，并缓存封面图像 |
| `IslandTrailingContent.kt` | 绘制右侧频谱或播放状态 |
| `IslandPalette.kt` | 根据默认配色、设置和封面主色生成歌词、背景和频谱颜色 |
| `IslandBackgroundProgress.kt` | 在词岛背景中绘制整曲播放进度，包括注水和边缘细线；细线位于胶囊顶部或刘海底部 |
| `IslandProgressTransition.kt` | 背景进度的非线性淡入淡出和退出样式保留；独立状态由窗口帧更新，绘制不推进动画 |
| `PlaybackProgress.kt` | 展开状态下的播放进度条和 seek 手势；松手时一次提交，并防止切歌后误 seek |
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
| `GtkTray.kt` | Linux 原生 GTK 托盘及其菜单同步 |
| `AboutDialog.kt` | 四项实时状态和简短版权卡片；按文本实际宽度计算高度，窄屏或长说明可滚动 |
| `BufferedAboutWindow.kt` | 独立关于窗口；Linux 子控件重绘先完成整帧透明缓冲，再一次提交 |
| `IslandRuntimeStatus.kt` | 将设置、播放、频谱和指针状态转换为可读说明及异常处理提示 |
| `DialogButton.kt` | 关于窗口统一按钮；绘制与命中使用同一圆角区域，关闭图标始终居中 |
| `ApplicationIdentity.kt` | 窗口/托盘共享的应用名称和图标，以及 Linux 托盘临时图标导出 |
| `ProjectLinks.kt` | 从构建资源读取项目地址并调用系统浏览器打开 |

完整设置仍由 SPW 原生插件配置页提供。托盘菜单只提供高频操作，不应逐渐演变成第二套完整设置界面。

关于窗口在 EDT 上更新已有状态行，状态变化不重建窗口；相同内容不重复替换文本。所有文字统一用内置 MiSans；说明文本不可选中或获取焦点。顶部关闭按钮用固定尺寸容器避免布局拉伸；按钮固定使用 BasicButtonUI 提供事件处理，自行绘制悬停、按下与键盘焦点，并将所有绘制裁剪到圆角轮廓，避免 GTK/Synth 改写字体或绘制默认按钮动画。Linux 使用整帧透明缓冲，避免子控件重绘时先清空矩形背景。

关于窗口不再属于词岛窗口。显示期间抑制歌词控件展开，打开、关闭时清空悬停记录；其他窗口收到鼠标进入或移动事件时也清除旧记录，处理合成器漏发离开事件的情况。关闭关于窗口后，只有新的词岛鼠标事件才触发展开。建议用临时用例检查按钮命中、主题切换、MiSans、禁止选中、窗口独立性、悬停清理及状态映射；可用隐藏窗口渲染检查 GTK、深浅主题、长说明和窄屏换行。

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

加载 GTK 3，并优先使用 AppIndicator 创建持久托盘；不可用时回退到 GTK StatusIcon。

菜单数据来自 `GtkTray`。

GTK 与 Swing/AWT 不在同一个 JVM 中初始化，以减少线程和桌面工具包冲突。

另有 `island-spectrum.py`（PipeWire 目标流监控与四频段 FFT）和 `island-pointer.py`（GIO/D-Bus 临时 KWin 脚本或 GNOME 配套扩展客户端）。都只使用 Python 标准库。普通 Linux 悬停使用 `IslandPointerPresence` 聚合自身窗口事件；120 ms 离开延迟避免子控件切换导致收起。穿透通过 `X11InputRegion` 设置空 ShapeInput；隐藏区域保持进入时的轮廓，KWin 坐标超过 750 ms 失效时恢复显示。

#### 方案选择

[Issue #30](https://github.com/GaBoron/SPW-island/issues/30) 的直接问题是：XWayland 窗口失去指针焦点后，全局指针查询可能保留旧坐标。普通悬停展开可以依靠自身窗口的进入/离开事件解决。原生 Wayland 窗口迁移还涉及渲染、输入区域、置顶、定位、GTK/JVM 生命周期与桌面兼容性，不能单靠换窗口后端解决全部功能。

本次采用三个独立改动，保留现有离屏 Swing 渲染及 Linux 独立界面进程：

| 任务 | 实现 | 预期效果 |
| --- | --- | --- |
| 普通悬停展开/收起 | 聚合词岛及子控件的进入、移动、离开事件；120 ms 离开延迟 | 移到原生 Wayland 窗口后收起，不被旧的全局坐标重新展开；按钮间移动保持展开，拖动期间不收起 |
| 穿透时悬停隐藏 | X11 空 ShapeInput；X11 指针查询、KDE Wayland 临时 KWin 桥接或 GNOME Shell 配套扩展 | 点击交给下层窗口；保持进入时的退出边界，避免隐藏动画触发反复显隐 |
| Linux 实时频谱 | 严格匹配宿主/子进程的 PipeWire 播放节点，原始 PCM → FFT → 四个频段 | 不混入其他应用与麦克风；复用现有频谱动画和封面配色 |

GNOME Wayland 使用配套的 `SPW Island Pointer` 扩展（GNOME 45–50），以只读、按需 D-Bus 方法返回 `global.get_pointer()`；不在 Shell 中运行定时器或修改窗口。其他未提供桥接的 Wayland 桌面仍可使用普通悬停及穿透，自动隐藏保持显示。完整 Wayland 窗口后端作为单独迁移处理。

#### 实现约束

- KWin 桥接只在请求隐藏时启动，临时脚本通过会话 D-Bus 加载，不写入永久设置。指针每 50 ms 更新，超过 750 ms 未收到有效样本时恢复显示。桥接会重连；EOF、退出、过期及辅助进程被终止时清理脚本。脚本也会检测桥接进程的 D-Bus 名称消失并自行卸载。
- 开启“悬停自动隐藏”时自动使用空输入区域，无需再单独开启穿透。关闭隐藏后按独立穿透开关恢复；找回词岛同时关闭两个开关。GNOME 扩展通过插件配置按钮安装，保留其他扩展设置；首次安装或更新需要重新登录。GNOME 50 的本地扩展发现发生在会话启动时，[`ReloadExtension` 已不支持](https://gitlab.gnome.org/GNOME/gnome-shell/-/blob/50.1/js/ui/shellDBus.js#L449)，[本地扩展在会话启动时被发现](https://gitlab.gnome.org/GNOME/gnome-shell/-/blob/50.1/js/ui/extensionSystem.js#L630)，因此首次安装需重新登录。
- PipeWire 的服务端客户端 PID 优先于应用自报 PID。只允许 `Stream/Output/Audio`，使用 `object.serial` 指定目标，禁止自动回退和自动连接到其他节点。多个 SPW 流同时存在时优先选择最新的活动流。
- 捕获使用 `pw-record` 的 48 kHz、双声道 float32 数据，不保存 PCM。2048 点 Hann FFT，双声道分别计算功率再合并，避免反相抵消；频段为 40–250、250–1000、1000–4000、4000–16000 Hz。
- 一个渐变参考电平保留频段间相对能量；UI 继续使用原有攻击/释放平滑。四个浮点值与来源状态经 `IslandSpectrum` 单独传递，最多约 30 Hz；歌词/封面仍约 10 Hz。350 ms 无新频谱数据时归零。
- 暂停、关闭显示、不显示频谱及低性能模式都会停止 Linux 捕获。缺少工具或目标流时标明模拟来源；目标流重建后重新发现并连接。
- 插件设置和托盘中的“找回词岛”始终可恢复操作。

#### 建议验证方法

可先构建 Linux 插件包：

```bash
./gradlew pluginLinux
```

测试脚本和测试依赖仅用于临时验证，完成后删除；项目及源码包不保留测试集。以下方法可用于设计临时用例。

音频集成验证建议创建临时空输出，不更改默认音频设备，不产生可听声音。可用两个进程向同一空输出分别播放 100 Hz 与 8 kHz，只监控目标进程；随后断开并重建目标流，检查是否只重连目标进程。结束时应删除临时输出。

D-Bus 集成验证建议运行在私有会话，模拟 KWin 的脚本加载、运行、指针更新与卸载接口，检查 GIO 调用、发送方限制、数据停止后的退出和临时文件清理。KDE 桌面交互应另在真实 Plasma 环境中检查。

JVM 临时用例建议覆盖窗口事件、原生 X11 输入区域、隐藏状态、频谱 IPC、松手后的进度绘制、连续 seek / 位置提交与设置确认，以及空配置、部分写入、字体修改和并发保存。GNOME 安装验证可使用临时配置与 keyfile 后端，检查安装、更新备份及其他扩展设置是否保留。GNOME 扩展验证可在私有 D-Bus 和隔离数据目录中启动 headless GNOME Shell，加载扩展后检查查询、禁用、重新启用与父进程 EOF 清理。打包检查应确认指针扩展 ZIP、音频辅助程序与设置项齐全，且没有 Python 缓存文件。

桌面验收建议分别选择 X11、GNOME Wayland 和 KDE Plasma 5/6，并包含混合缩放及多显示器场景。GNOME 首次安装或更新扩展后，应重新登录再检查连接。可按以下步骤检查：

1. 普通模式进入词岛、移入播放按钮/进度条、移到原生窗口；确认展开、保持、收起。
2. 拖动词岛或进度条越过边界，松开后恢复正常悬停判断。
3. 开启穿透及自动隐藏，在词岛区域进入、静止、移开；确认点击下层窗口与一次隐藏/恢复。
4. 隐藏中关闭桥接或禁用 GNOME 扩展 / 重启 KWin；确认自动显示与重新连接。
5. 在不同缩放的屏幕间移动词岛，检查指针与隐藏轮廓对齐。
6. 切歌、输出设备变化及音频服务重启；检查重新连接、来源标注和进程清理。

`native/gnome-pointer/extension.js` 和 `metadata.json` 提供只读 GNOME Shell 扩展；`install-gnome-pointer.py` 安装配套 ZIP，保留其他扩展设置，并提醒重新登录。Gradle 的 `gnomePointer` 任务生成独立扩展包，同时装入 Linux 插件资源。

### 4.11 资源与构建入口

| 路径 | 职责 |
| --- | --- |
| `src/main/resources/preference_config.json` | Windows/SPW 插件配置页声明 |
| `src/linux/resources/preference_config.json` | Linux 配置页声明，移除平台未支持项目 |
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

如果涉及当前公开 API 无法提供的内容，再查看：

```text
HostPlaybackProbe
HostLyricsRecovery
CurrentTrackRecovery
```

新版公开 API 能够替代某项反射探测时，优先删除或缩小兼容代码，而不是继续叠加新探测。

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
HostPlaybackProbe
HostLyricsRecovery
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

Linux 输入轮廓：

```text
IslandWindow
IslandSurface
X11InputRegion
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
ProcessSpectrum
PerformanceProfile
```

Windows 捕获/算法：

```text
native/AudioInterop.cs
native/ProcessLoopback.cs
native/Spectrum.cs
native/SpectrumLevels.cs
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

### 修改设置

通常同时查看：

```text
preference_config.json
IslandSettings
HostSettings
实际使用该设置的组件
```

如果设置属于菜单高频操作：

```text
IslandMenuCommands
```

Linux 平台还要确认：

```text
src/linux/resources/preference_config.json
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
native/Tray.cs
```

Linux：

```text
GtkTray
LinuxHelper
island-linux.py
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
```

### 修改关于窗口、项目名称或许可证展示

查看：

```text
AboutDialog
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

## 7. 宿主 API 与兼容代码

优先使用 SPW Workshop API 的公开接口。

只有公开 API 无法完成已经需要的功能时，才考虑宿主内部兼容。

目前 `HostPlaybackProbe` 就是这类边界。

它的目标不是建立另一套 SPW API，而是：

```text
缺少公开能力
 ↓
最小范围只读探测
 ↓
转换成项目自己的 Track / LyricLine
 ↓
兼容层结束
```

其他代码不应了解：

- SPW 内部类名；
- 私有字段；
- 私有 service；
- 内部歌词 document；
- 反射搜索路径。

如果将来的公开 API 已经覆盖对应能力，应优先迁移公开 API，并删除不再需要的探测。

兼容代码还应满足：

- 不修改宿主持有的对象；
- 不在 Swing 绘制循环执行复杂反射；
- 失败后不阻断基本歌词显示；
- 不把内部类型传播到 `core/`；
- 不把一次版本兼容 hack 扩散成整个项目依赖。

---

## 8. 线程与生命周期

项目包含多个线程和进程边界，修改时应知道代码运行在哪里。

### SPW 回调

`IslandPlaybackExtension` 接收宿主回调。

回调路径应尽量快速，只转换数据并更新内部状态，不在这里进行文件读取或复杂 UI 操作。

私有完整歌词探测由 `HostLyricsRecovery` 的后台线程执行，不持有 `PlaybackCallbackBridge` 的回调锁扫描宿主文档。Windows 词岛与 SPW 共用 JVM；排查两边同时卡顿时，应采集复现阶段的线程栈或性能采样，区分宿主回调、EDT 绘制和 JVM 停顿。

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

不要在绘制函数中执行：

- 文件读取；
- 网络请求；
- 宿主反射扫描；
- 阻塞等待；
- 长时间计算。

### 曲目元数据

`TrackMetadataLoader` 在单独后台线程读取本地文件。

不要把 Jaudiotagger 或图片解码移回 EDT。

### 逐字几何

`IslandLyricsPreparation` 提前预备后续歌词的测量结果，按需请求轮廓和逐字几何。布局缓存和 `LyricTypography` 的缓存锁只用于读取与发布结果，冷塑形在锁外执行，后台任务不会因持有缓存锁阻塞绘制线程。

`WordGeometry` 在有界后台线程准备昂贵的字形轮廓。

动画线程可以在几何尚未准备完成时使用轻量 fallback，不应等待后台任务。

### 实时频谱

`ProcessSpectrum` 管理单独的 Windows helper 进程和后台读取线程。

UI 只读取最新四个频段值。

### Linux

Linux 至少存在：

```text
SPW JVM
Linux IPC 后台线程
独立界面 JVM
独立界面 Swing EDT
GTK helper 进程
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

如果 Windows 和 Linux 都支持，还要同步两套 `preference_config.json`。

如果某个平台不能可靠提供对应功能，就不要为了界面一致而暴露一个实际无效的开关。

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

低性能模式可以：

- 降低刷新率；
- 关闭复杂逐字几何；
- 使用简化逐字边界；
- 关闭复杂布局动画；
- 使用模拟频谱；
- 关闭非必要宿主探测；
- 降低平台状态检查频率。

不要在不同组件里各自重新判断一套“低性能模式是什么意思”。

---

## 11. 验证策略

项目不以测试覆盖率作为目标。测试脚本、用例和测试依赖只用于临时验证，完成后删除，不留在项目或源码包中。

所有文档涉及测试时，只提供方法建议、环境准备和判定条件；不记录执行状态、通过数量、覆盖统计或实际观测。

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

对于易被后续修改破坏的纯逻辑，建议使用少量临时自动化用例检查边界、状态切换和异常输入；验证后删除用例及新增测试依赖。

### UI 和动画

优先：

1. 编译；
2. 在真实 SPW 中运行；
3. 验证受影响的视觉和交互；
4. 检查相关旧行为。

大量 mock Swing 或模拟窗口管理器通常不能替代真实环境验证。

### 宿主 API

涉及 SPW 回调、插件启停或兼容探测时，应在真实 SPW 中验证。

单纯构建成功不能证明宿主回调顺序符合假设。

### 平台能力

Windows 原生能力需要真实 Windows 环境验证。

Linux 窗口、托盘、GTK 和 X11/XWayland 行为需要真实桌面环境验证。

不要根据一个操作系统的运行结果推断另一个平台也正确。

### 临时验证

为了确认一次修改而创建的：

- 临时代码；
- 临时日志；
- 临时脚本；
- 临时测试入口；

应在验证结束后删除；测试集和测试依赖不得随项目或源码包保留。

不要为了覆盖理论边界而给生产代码增加大量仅用于测试的接口。

---

## 12. 构建

需要 JDK 21 和网络连接。

具体 Kotlin、Gradle 和依赖版本以当前构建文件为准。

### Windows x64

```powershell
.\gradlew.bat pluginWindows --no-daemon
```

Windows 构建还需要系统 .NET Framework 4.x 自带的 C# 编译器，用于从：

```text
native/*.cs
```

构建实时频谱 helper。

### Linux x64

```bash
./gradlew pluginLinux --no-daemon
```

### 当前平台

也可以使用：

```text
gradlew plugin
```

由构建脚本选择当前平台对应的插件任务。

输出位于：

```text
build/distributions/
```

### 依赖边界

SPW 提供：

- Workshop API；
- Kotlin 标准库；
- PF4J。

插件自身分发：

- JNA；
- JNA Platform；
- Jaudiotagger；
- Gson（配置快照解析）；
- Windows 字体窗口使用的 Compose 运行库；
- 插件资源；
- 平台需要的 native/helper 文件。

不要重复打包由宿主提供的依赖。

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
- [ ] 文档中的测试内容仅包含方法建议、环境准备和判定条件；
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
