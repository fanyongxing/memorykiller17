# MemoryKiller 17（内存杀手 · Android 17 适配版 `v0.1`）

专为 **Android 17 (API 37)** 适配与升级的内存清理工具。通过 **Shizuku** 临时解除 Android 17 单应用内存上限（Memory Limiter），内置 **三种原生吃内存清场引擎**（原版 `libhello-jni.so` 引擎、独立 `mmap` 高熵防 ZRAM 压缩引擎、以及两者叠加的双引擎极限清场），并附带系统级 `am kill-all` 备用清理与退出自动恢复限制、自动拉回桌面守护。

## 为什么旧版内存杀手在 Android 17 难以生效？

1. **Android 17 Memory Limiter（cgroup v2 单应用硬上限）**：从 Android 17 开始，系统对所有普通应用（`UID >= 10000`，无视 `targetSdkVersion`）按包级 UID 施加 `memory.max` 与 `memory.high` 限制，并启用 Local PSI 隔离，导致普通应用无法再单靠吃内存触发全局 LMKD 清理其他后台进程（详见 [AOSP Memory Limiter 文档](https://source.android.com/docs/core/perf/memory-limiter) 与 [Android 17 行为变更](https://developer.android.com/about/versions/17/behavior-changes-all)）。
2. **大容量 ZRAM / RAM Plus 压缩**：在三星 One UI 9（RAM Plus）等大 ZRAM 机型上，传统全零分配容易被 ZRAM 高倍压缩抵消压力。
3. **16 KB 内存页对齐要求**：移除旧包中 4 KB 对齐的冗余 `.so`，保留 64 KB 对齐的原版 `libhello-jni.so`，并以 `-Wl,-z,max-page-size=16384` 编译全新的 `libeatmem.so`，完整兼容 [Android 16 KB 页大小规范](https://developer.android.com/guide/practices/page-sizes)。

## 三种吃内存清场模式 + 备用清理

所有吃内存模式在运行前均通过 [Shizuku API 13.1.5](https://github.com/RikkaApps/Shizuku-API) 仅对本应用 UID 执行 `am memory-limiter ignore <UID>`；本应用被系统清场终止后 0.25 秒内，后台看门狗会自动执行 `am memory-limiter ignore none` 恢复系统限制并拉回桌面（避免任务栈中旧应用弹出）：

1. **原版引擎清场（100% 原版 `libhello-jni.so`）**：原封不动调用 `as.mke.eatmem.MainActivity.native_eatmf()`（5 线程循环 `calloc` 分配），还原早期安卓内存杀手逻辑。
2. **独立 `mmap` 高熵引擎清场（`libeatmem.so`）**：6 线程通过 `mmap(MAP_PRIVATE | MAP_ANONYMOUS)` 分配 8 MB 块并填充 `xorshift64*` 高熵伪随机数据，有效对抗 ZRAM（LZ4/ZSTD）高倍压缩。
3. **原版 + `mmap` 双引擎极限清场（最猛）**：同时运行原版 `libhello-jni.so` 与 `libeatmem.so`，以最快速度拉满物理内存与 ZRAM 压力，触发彻底清场（含桌面重启）。
4. **备用：系统级 `am kill-all`**：不吃内存，直接由系统安全回收后台缓存进程。

## 使用方法

1. 前往右侧 **Releases** 页面下载 `MemoryKiller17_v0.1.apk` 并安装。
2. 在手机上安装并打开 [Shizuku](https://github.com/RikkaApps/Shizuku)，通过“无线调试”启动 Shizuku 服务（无需 Root）。
3. 打开 **内存杀手 17**，点击 **“授权 / 连接 Shizuku”** 并允许授权。
4. 按需点击对应清场按钮即可。

## 本地编译指南

- **环境要求**：Android Studio Meerkat (2024.3.1)+、JDK 17、Android 17 SDK (`platforms;android-37.0`)、Build-Tools `37.0.0`、NDK `28.2.13676358`、CMake `3.31.6`（参考 [设置 Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk?hl=zh-cn)）。
- **构建命令**：
  ```bash
  gradle assembleRelease --no-daemon
  ```
- **签名**：使用 `zipalign -p -f 4` 与 `apksigner sign` 完成 APK Signature Scheme v2/v3 签名。

## 开源协议与说明

- 本项目 Kotlin/Java/C++ 源码部分基于 [MIT License](LICENSE) 开源；`app/src/main/jniLibs/*/libhello-jni.so` 为原版二进制库，仅用于个人学习与兼容测试。
- 极限清场模式会主动触发系统低内存回收，使用前请确认已保存重要后台数据。
