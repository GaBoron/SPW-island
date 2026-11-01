# 开发指南

[← 文档首页](README.md)

用户操作见 [使用与设置](usage.md)，提交要求见 [贡献指南](../CONTRIBUTING.md)。

## 架构

插件通过 SPW Workshop API 接收播放和歌词状态，转换为稳定快照后交给界面。

```text
SPW → host/ → core/ → ui/
            └─ platform/
Linux: host/ → remote/（双向 IPC）→ 独立 UI JVM
```

| 模块 | 责任 |
| --- | --- |
| `core/` | 播放、歌词和设置模型，不依赖宿主或图形平台 |
| `host/` | SPW 接口、权限、配置和插件生命周期 |
| `ui/` | 布局、绘制、动画和交互 |
| `platform/` | 原生窗口、音频和桌面集成 |
| `remote/` | Linux 宿主与独立界面的状态和命令通信 |
| `diagnostics/` | 日志、后台任务异常和诊断导出 |

Windows 界面运行在 SPW JVM 中，使用 Skia / Direct3D。Linux 界面运行在独立 JVM，通过 X11 / XWayland 与 Skia / OpenGL 绘制，GPU 不可用时回退到兼容绘制。

## 代码地图

| 修改范围 | 主要入口 |
| --- | --- |
| 插件生命周期与 SPW 回调 | `IslandPlugin`、`IslandRuntime`、`IslandPlaybackExtension` |
| 播放时间与歌词数据 | `PlaybackTimeline`、`PlaybackHeartbeatRecovery`、`TrackMetadataLoader` |
| 歌词布局与逐字动效 | `ActiveLyrics`、`IslandLyricsLayout`、`LyricPainter`、`AmllMotion`、`longtone/` |
| 窗口、轮廓与拖动 | `IslandWindow`、`IslandPlacement`、`IslandGeometry`、`IslandHoverGeometry` |
| GPU 绘制 | `ui/gpu/IslandGpuRenderer`、`IslandGpuScene`、`IslandGpuResources` |
| Windows 玻璃 | `IslandGlassShaders`、`LiquidGlassSurface`、`native/glass/` |
| Linux 玻璃与指针 | `LinuxCompositorGlass`、`LinuxGlobalPointer`、`src/linux/resources/native/` |
| 播放控制与音量 | `PlaybackProgress`、`IslandVolumeFeedback`、`HostVolumeControl` |
| 封面、频谱与配色 | `LocalTrackMetadata`、`SpectrumSource`、`ProcessSpectrum`、`LinuxProcessSpectrum`、`IslandPalette` |
| 设置与字体 | `IslandSettings`、`HostSettings`、`IslandConfigFile`、`ComposeFontPickerWindow` |
| Linux 进程通信 | `LinuxIslandProcess`、`LinuxIslandMain`、`IslandWire` |
| 菜单与状态 | `IslandMenu`、`IslandRuntimeStatus`、`diagnostics/` |

## 状态、线程与资源

宿主 API 限于 `host/` 边界。SPW 回调应尽快返回；文件、音频和字形准备在有界后台任务中完成，Swing 操作回到所属 EDT。

`PlaybackTimeline` 提供稳定播放快照。Linux 菜单和位置操作可即时预览，但最终状态以宿主确认结果为准；相关消息由 `IslandWire` 定义。

`PerformanceProfile` 集中管理标准和低性能预算。渲染、采集和辅助进程须随隐藏、停用及关闭释放资源。诊断经 `IslandLog` 统一记录；日志清理保护活动文件，诊断导出不复制媒体、用户设置或宿主日志。

新增设置同时检查 `IslandSettings`、`IslandSettingsDefaults`、`HostSettings` 和 `src/main/resources/preference_config.json`，保持已有配置兼容。

## 性能采集

在 SPW 插件设置中导出诊断包，查看 JSONL 中 `performance` 模块。启动记录区分插件初始化、运行时构造、设置读取、快捷键注册、后台提交、EDT 排队、窗口构造和首帧；线程名用于区分宿主调用与界面线程。插件计时不包含宿主加载插件类之前的工作，首帧记录只表示绘制命令已完成，不证明合成器已显示。

帧调度、EDT 排队、绘制、帧间隔、播放回调及 Windows 桌面裁片传输按固定容量直方图汇总，包含样本数、均值、P50/P95/P99 桶上界、最大值和超出当帧预算的次数。标准模式约每 10 秒输出，低性能模式约每 30 秒输出；没有逐帧日志。JVM CPU（单核满载为 100%）、堆、GC 和线程数由现有诊断后台线程采样；Windows 宿主 JVM 包含播放器与其他插件，Linux 需分别查看宿主和界面进程。GPU 执行耗时、显存、原生内存及 Linux 合成器开销需另用系统性能分析器，不能由绘制回调耗时推断。

对照时固定机器、壁纸、歌曲、窗口位置、缩放、刷新率和动画设置，保留改动前后同场景的诊断包、外部性能记录和录屏：

1. 分别重复冷启动、再次启用、启用后立即停用和连续启停，检查快捷键、托盘、音频辅助进程及窗口是否正确释放。
2. 每种场景至少记录 60 秒：常规歌词、密集逐字、快速切歌、长短句交替、连续悬停反向、经典背景和液态玻璃；低性能设备使用相同场景，并单独记录低性能模式。
3. 比较尾延迟和最大耗时，同时核对歌词及翻译同步、频谱响应、宽度连续变化、动画反向接续、玻璃折射、光场、模糊与材质形变。只有性能数据和视觉结果都支持时，才接受优化。

## 平台集成

### 液态玻璃

Windows 的 `WindowsDesktopBackdrop` 对接原生桌面采样，`WindowsGpuTextureImport` 将共享纹理交给 Skia；原生代码位于 `native/glass/`。辅助窗口复用 `LiquidGlassSurface` 和 `SwingGlassMaterial`。

GNOME 和 KDE 在合成器内处理后景，`LinuxCompositorGlass` 只传轮廓和不透明度。GPU 后景、客户端 alpha 和动画轮廓分别处理，保留原窗口绘制与输入。候选材质完成首帧后再切换，失联或停用时释放租约。

GNOME 源码位于 `src/linux/resources/native/gnome-glass/`；KWin 源码位于 `native/kwin-glass/`，由 `kwinGlass` 任务随插件分发。KWin 安装器通过宿主 systemd 用户服务构建，校验运行版本、软件包和 SDK 一致性。首次注册固定链接，此后在用户目录构建并切换唯一文件名的模块，避免复用已加载的旧库。

参考实现：[Mutter 50](https://github.com/GNOME/mutter/tree/50.0)、[GNOME Liquid Glass](https://github.com/ryohsuke1231/liquid-glass)、[kwin-effects-glass](https://github.com/4v3ngR/kwin-effects-glass)。来源和许可修订见 [NOTICE](../NOTICE)。

### 指针、托盘与音频

Linux X11 直接查询指针；KDE Wayland 使用临时 KWin 脚本，GNOME Wayland 使用配套扩展。指针失联时恢复显示。X11 输入区域由 `X11InputRegion` 管理。

Windows 托盘使用 `native/Tray.cs`，Linux 托盘由 `GtkTray` 启动 Python helper。频谱 helper 仅返回四频段数据；不可用时使用模拟频谱。

## 构建与分发

统一插件需在 Windows x64 构建，使用 JDK 25、MSVC x64 工具及 CRT、含 C++/WinRT 的 Windows SDK 和 .NET Framework 4.x 编译器。

```powershell
.\gradlew.bat plugin --no-daemon
```

产物为 `build/libs/spw-island-<版本>.spmod`，包含 Windows / Linux 组件、源码归档和许可材料。依赖、资源和打包规则见 `build.gradle.kts`；版本和项目地址见 `gradle.properties`。平台改动需在对应 SPW 和桌面环境验证。

第三方来源与授权见 [NOTICE](../NOTICE)、[第三方许可说明](../THIRD_PARTY_NOTICES.md) 和 [licenses/](../licenses/)。
