# 回归检查

安装 JDK 25 后，在仓库根目录运行：

```powershell
.\gradlew.bat regressionTest --no-daemon
```

`test` 和 `check` 也会运行这些检查。首次运行需要下载构建依赖；检查本身不联网、不连接 SPW、不写真实配置。使用 Kotlin 标准库断言，无额外测试框架；标准 `test` 任务只委托 `regressionTest`，自身的 JUnit 发现阶段显示 `SKIPPED`。构建输出留在忽略的 `build/`。

覆盖播放速率估计、短间隔/长间隔回调、抖动平滑、前后跳转、暂停恢复、显式速率范围和重置。实际宿主回调、Linux IPC、播放控制和界面仍需在对应环境验收。
