# 开发指南

[← 文档首页](README.md)

本文供维护和修改灵动词岛的开发者使用。普通使用说明见 [安装与更新](installation.md)、[使用与设置](usage.md) 和 [兼容性与限制](compatibility.md)；提交代码前请阅读 [贡献指南](../CONTRIBUTING.md)。

## 架构概览

插件通过 SPW Workshop API 接收歌曲、歌词和播放状态，并在内部转换为稳定快照。UI 不直接持有宿主播放对象。

```text
SPW → host/ → core/ → ui/
            └─ platform/
Linux: host/ → remote/（双向 IPC）→ 独立 UI JVM
```

| 模块 | 责任 |
| --- | --- |
| `core/` | 播放、歌词和设置模型，不依赖宿主或图形平台 |
| `host/` | SPW 回调、权限、配置和插件生命周期 |
| `ui/` | 窗口、歌词布局、绘制、动画与交互 |
| `platform/` | 原生窗口、音频、桌面集成与平台辅助进程 |
| `remote/` | Linux 宿主与独立界面的状态和命令通信 |

Windows 的界面运行在 SPW JVM 中，使用 Skia / Direct3D；Linux 将 Swing UI 放在独立 JVM，通过 X11 / XWayland 与 Skia / OpenGL 绘制，GPU 不可用时使用兼容路径。

Linux 的播放与设置变更由 `LinuxIslandProcess` 和 `IslandWire` 在宿主与 UI 间传递；`RemotePlayback`、`RemoteSettingsStore` 接收确认后以宿主状态为准。平台窗口、托盘与指针操作不要直接进入 `core/`。

## 平台能力

- **窗口与交互**：`IslandWindow` 管理窗口生命周期；`IslandPanel` 组合歌词和控件。X11 输入区域由 `X11InputRegion` 处理；`IslandPointerPresence` 与 `LinuxGlobalPointer` 管理悬停和全局指针。
- **Linux 桌面**：X11 直接查询指针，KDE Wayland 使用临时 KWin 脚本，GNOME Wayland 使用配套扩展。指针失联时必须恢复词岛可见状态，不能留下无法操作的永久桌面设置。
- **液态玻璃**：Windows 由原生背景捕获与 Skia 着色器实现；GNOME 50 由 Shell 扩展、KDE 实验路径由 KWin 原生效果完成合成器内渲染，`LinuxCompositorGlass` 只同步动态轮廓和不透明度，不传输桌面像素。实现时不得干扰原有歌词布局、窗口输入和动画。
- **音频**：Windows `ProcessSpectrum` 和 Linux `LinuxProcessSpectrum` 分别管理辅助进程，只向 UI 提供四频段数据；无可用音频流时降级，不影响歌词。
- **设置**：`IslandSettings`、`HostSettings` 和 `preference_config.json` 共同定义用户设置。配置变更保持已发布键名的兼容性。

GNOME 合成器实现可参考 [Mutter 50](https://github.com/GNOME/mutter/tree/50.0) 和 [GNOME Liquid Glass](https://github.com/ryohsuke1231/liquid-glass)；KWin 的参考项目为 [kwin-effects-glass](https://github.com/4v3ngR/kwin-effects-glass)。KWin 实验接入面向 Arch Linux、KWin 6.6–6.8 Wayland，仍需目标环境的原生构建与视觉验收。

## 修改入口

| 修改范围 | 优先查看 |
| --- | --- |
| SPW 回调、插件生命周期 | `IslandPlaybackExtension`、`PlaybackCallbackBridge`、`IslandRuntime` |
| 歌曲位置、seek、暂停与切歌 | `PlaybackTimeline`、`PlaybackHeartbeatRecovery` |
| 歌词来源及本地元数据 | `TrackMetadataLoader`、`WorkshopTrackMetadata`、`LocalTrackMetadata` |
| 单行 / 多行歌词 | `ActiveLyrics`、`IslandLyricsLayout`、`IslandLyricsPainter` |
| 逐字动画、长音与文字几何 | `AmllMotion`、`WordGeometry`、`longtone/` |
| 词岛尺寸、轮廓与拖动 | `IslandWindow`、`IslandSurface`、`IslandHoverGeometry` |
| Windows 玻璃、菜单及关于页 | `IslandGlassShaders`、`LiquidGlassSurface`、`IslandMenu` |
| Linux 玻璃、悬停隐藏 | `LinuxCompositorGlass`、`LinuxGlobalPointer`、`src/linux/resources/native/` |
| 音频频谱 | `SpectrumSource`、`ProcessSpectrum`、`LinuxProcessSpectrum` |
| 设置与持久化 | `IslandSettings`、`HostSettings`、`IslandConfigFile` |
| Linux 独立界面与进程通信 | `LinuxIslandProcess`、`LinuxIslandMain`、`IslandWire` |
| 状态与诊断 | `IslandRuntimeStatus`、`diagnostics/` |

## 代码地图

下表用于定位职责，不替代源码中的实现细节。增删或移动独立职责文件时同步更新本表。

### `core/`：核心模型与播放状态

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

### 运行诊断

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
| `platform/AwtGraphicsDiagnostics.kt` | 窗口与 Skiko Canvas 的 AWT 配置、颜色模型和通道掩码；不推断 GLX 缓冲区格式或最终合成结果 |
| `src/linux/resources/native/island-diagnostics.py` | Python 辅助进程内的异常、线程和回调诊断，重复异常汇总后交给 JVM 收集 |
| `native/glass/CaptureDiagnostics.h` | 每个桌面捕获实例的有界原生诊断队列，保留阶段、HRESULT、线程和原生栈地址 |

### `host/`：SPW 接入

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

### `platform/`：平台与本地能力
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
| `LinuxCompositorGlass.kt` | 合并发送现有动态轮廓，管理 GNOME / KWin 材质桥接、首帧确认、失联回退和低性能停用 |
| `src/linux/resources/native/island-glass.py` | 复用指针桥的 GIO 连接，将几何消息转发给当前合成器；不传输桌面像素 |
| `src/linux/resources/native/kwin-glass-support.py` | Arch 原生组件安装、宿主用户服务构建、接口版本校验与普通着色器热更新 |
| `native/kwin-glass/IslandGlassEffect.*` | KWin 几何服务、调用进程与 XWayland 窗口绑定、租约和候选材质切换 |
| `native/kwin-glass/GlassRenderer.*` | GPU 后景与客户端 alpha、连续模糊、轮廓缓存及光学合成 |
| `native/kwin-glass/shaders/` | KWin 轮廓距离场、中心模糊、边缘折射、色散与高光 |
| `src/linux/resources/native/install-gnome-support.py` | 共用的指针 / 玻璃扩展安装器，校验内置包、备份替换、合并启用设置；失败暂存目录移入回收站 |
| `src/linux/resources/native/gnome-glass/extension.js` | GNOME 50 配套服务、词岛窗口绑定、调用进程校验、租约回收及渲染模块加载 |
| `src/linux/resources/native/gnome-glass/surface.js` | 稳定的 Clutter Actor、渲染模块首帧切换及失败保留 |
| `src/linux/resources/native/gnome-glass/renderer.js` | Cogl 后景复制、轮廓距离缓存、分离模糊及光学合成；不接管输入 |
| `src/linux/resources/native/gnome-glass/*.glsl` | 轮廓距离与法线、中心模糊、边缘折射、色散和高光 |

### `remote/`：独立进程通信

| 文件 | 职责 |
| --- | --- |
| `RemoteSettingsStore.kt` | 即时应用 Linux 菜单与位置操作，宿主确认后移除待确认编辑 |
| `IslandWire.kt` | 定义状态、命令、频谱与音量结果消息，以及子进程使用的 `RemotePlayback` 与 `RemoteSpectrum` |
| `RemoteVolumeControl.kt` | 发送滚轮增量与拖动绝对音量命令，按请求 ID 将最新宿主结果交回界面 |
| `LinuxIslandProcess.kt` | 宿主侧独立界面进程管理；发送播放、设置和频谱状态，接收并确认播放/seek/设置等命令 |
| `LinuxIslandMain.kt` | Linux 子 JVM 入口；接收状态、创建 `IslandWindow` 并把用户操作传回宿主 |

### `ui/`：窗口与几何

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

### `ui/`：歌词布局与逐字绘制

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

### `ui/`：侧边内容、颜色与播放控制

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

### `ui/`：菜单、托盘、关于与项目身份

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

### Windows 原生频谱 helper

| 文件 | 职责 |
| --- | --- |
| `AudioInterop.cs` | WASAPI / COM 所需结构、接口和 `ActivateAudioInterfaceAsync` 声明 |
| `ProcessLoopback.cs` | helper 程序入口、目标进程树回环捕获、PCM 数据读取和频谱输出 |
| `Spectrum.cs` | Hann 窗、FFT、立体声功率合并和四个实际频段的 RMS 计算 |
| `SpectrumLevels.cs` | 将频段 RMS 映射成稳定、适合音乐可视化的 0～1 柱高 |

### Linux Python bridge

- `island-linux.py`：启动 Linux 界面 JVM 或托盘 helper。
- `island-dbus-tray.py`：系统托盘备用实现。
- `island-pointer.py`、`gnome-pointer/`：Wayland 指针桥接与配套扩展。
- `island-spectrum.py`：PipeWire 播放流的频谱数据处理。
- `install-gnome-support.py`、`gnome-glass/`：GNOME 组件安装与液态玻璃扩展。
- `kwin-glass-support.py`、`native/kwin-glass/`：KWin 原生组件与着色器，`kwinGlass` 任务打包源代码后随插件分发。首次安装通过宿主的 systemd 用户服务匹配系统 KWin SDK，在普通用户身份下构建；只有补充编译工具和部署独立原生库时请求 PolicyKit 授权，不以管理员身份编译。普通更新只写入用户数据目录并热加载着色器，不弹系统授权。

KWin 后景通过当前绘制目标的 GPU blit 获取，另在 GPU 上绘制当前词岛的 alpha 以约束最终轮廓，随后保留原有窗口绘制。重绘区域未完整覆盖采样范围时保留上一张完整材质并请求重绘；候选模块出首帧后才替换当前模块。低性能模式、隐藏或关闭窗口时结束租约，释放各视图纹理且停止续约定时器。原生接口更新需要通过同一安装入口重新构建；不加载不匹配的 KWin SDK 组件，不修改全局模糊、窗口规则或输入区域。

### 资源与构建入口

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

## 构建与分发

构建工具、依赖版本、原生组件及分发清单以 `build.gradle.kts`、`gradle.properties` 和对应源码为准。

在配置了项目原生构建工具的 Windows x64 环境运行：

```powershell
.\gradlew.bat plugin --no-daemon
```

产物位于 `build/libs/spw-island-<版本>.spmod`。统一包包含 Windows / Linux 平台组件；修改平台能力时仍需在对应桌面环境验证。源码归档和第三方许可材料必须随分发保留。

## 维护约束

- 宿主 API 只在 `host/` 边界使用，优先调用公开接口；非公开宿主接口需隔离并明确兼容范围。
- SPW 回调应尽快返回；文件读取、音频处理、字形准备和耗时工作应在有界后台任务中完成。Swing 组件只在所属 EDT 操作。
- `PlaybackTimeline` 向界面提供稳定快照；跨 Linux 进程的命令由宿主确认，不以 UI 本地预览覆盖最终状态。
- `PerformanceProfile` 统一标准 / 低性能预算；新持续性绘制或采集功能需要遵守既有预算和生命周期。
- 新增设置时同时检查配置声明、读写及默认值；已有用户配置应继续可用。
- 平台问题要在真实 SPW 和目标操作系统中确认；不要从单一系统推断全部 Linux 桌面行为。
- 变更应保持明确职责和最小必要复杂度，避免为了假设中的边界堆叠长期回退逻辑。

贡献流程见 [CONTRIBUTING.md](../CONTRIBUTING.md)。第三方来源与授权以 [NOTICE](../NOTICE)、[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 和 [licenses/](../licenses/) 为准。
