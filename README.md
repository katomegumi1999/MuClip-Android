# MuClip Android

局域网剪贴板同步与文件互传的 Android 客户端 —— Kotlin + Jetpack Compose (Material 3) + Rust 核心（JNI 直连）。

不经过任何云服务器。设备在同一个局域网内互相发现、`SPAKE2` 配对、`XChaCha20-Poly1305` 端到端加密传输，剪贴板和文件走同一条加密通道。

> 这是 **MuClip 套件的 Android 客户端**，本身只包含 UI 与 Android 平台能力。
> 网络发现、配对握手、加解密、协议编解码全部在 Rust 核心库 [`muclip-core`](https://github.com/katomegumi1999/MuClip) 里，以 `libmuclip_core.so` 的形式通过 JNI 调用。

---

## 套件关系

| 仓库 | 角色 | 技术栈 | 与本仓库的关系 |
| :--- | :--- | :--- | :--- |
| [`MuClip`](https://github.com/katomegumi1999/MuClip) | 核心引擎（**必需**） | Rust | 提供 `libmuclip_core.so`，协议与加密的唯一实现 |
| `MuClip-Android` | Android 客户端（本仓库） | Kotlin + Compose | JNI 直连 `src/ffi/jni_direct/` |
| [`ShawMuOCR`](https://github.com/katomegumi1999/ShawMuOCR) | Windows 客户端 | C# + Avalonia | P/Invoke C ABI，剪贴板与 OCR 结果互同步 |

三者跑同一套线路协议，可以互相配对。**协议不兼容的改动必须递增帧头 `Version` 字段。**

---

## 功能

- **剪贴板双向同步**：文本内容跨设备实时推送，历史记录落地加密数据库，支持搜索与分类。
- **后台静默接收**：电脑推送的内容在应用退到后台时照常入库，回到前台再写入系统剪贴板（绕过 Android 10+ 的后台剪贴板限制，见下方「设计要点」）。
- **文件互传**：128 KiB 分块的零分配流式加密发送，接收端直接解密写入 MediaStore，不落明文中间文件。
- **常用语**：固定片段一键推送。
- **本地数据加密**：SQLCipher 加密数据库（密钥存 TEE），支持口令保护的 `.mubc` 加密备份导出/导入。
- **手动节点**：除局域网自动发现外，可填 `host:port` 走端口映射或 FRP 跨网段连接。

---

## 环境要求

### 运行

| 项 | 要求 |
| :--- | :--- |
| Android | 8.0（API 26）及以上 |
| 架构 | **仅 arm64-v8a**（x86_64 模拟器无法运行，需真机） |
| 网络 | 与对端处于同一局域网，或已配置可达的端口映射节点 |

### 构建

| 依赖 | 版本 |
| :--- | :--- |
| JDK | 21（`jvmTarget` 上限，勿上调） |
| Android SDK | compileSdk / targetSdk 37，Build-Tools 37.0.0 |
| Android NDK | 29.0.14206865 |
| Gradle | 9.8.2（wrapper 自带） |
| AGP | 9.4.0 |
| Rust | 1.85+ / edition 2024（仅编译核心库时需要） |

> 版本较新，请使用近期版本的 Android Studio。旧版 Studio 会因为 compileSdk 37 与 AGP 9.4 无法同步工程。

---

## 快速开始

### 1. 获取 `libmuclip_core.so`（必需）

预编译动态库**不入库**（避免二进制与 Rust 源码版本漂移，也避免每次核心变更都产生不可 diff 的大提交）。三选一：

**方式 A：自行编译（推荐，版本最可控）**

```bash
git clone https://github.com/katomegumi1999/MuClip.git
cd MuClip
cargo install cargo-ndk          # 首次
cargo ndk -t arm64-v8a -o <本仓库路径>/app/src/main/jniLibs build --release
```

产物落在 `app/src/main/jniLibs/arm64-v8a/libmuclip_core.so`（`cargo ndk` 会自动建目录）。

**方式 B：从 CI 构建产物下载**

`MuClip` 仓库的 Actions 中，名为 **Android arm64 (SQLCipher)** 的 workflow 每次构建都会上传 `libmuclip_core-so-sqlcipher` artifact，下载后放进同一路径即可。

**方式 C：从 Releases 下载**

若 `MuClip` 的 Releases 页面提供了对应版本的 Android 产物，取其中的 `libmuclip_core.so` 放入同一路径。

### 2. 启用数据库加密（可选，但建议）

`muclip-core` 的 `plain` 与 `sqlcipher` 两个 feature 互斥，加密版必须显式关掉默认特性：

```bash
cargo ndk -t arm64-v8a -o <本仓库路径>/app/src/main/jniLibs \
  build --release --no-default-features --features sqlcipher
```

> `sqlcipher` 会现场编译 vendored OpenSSL，交叉编译到 Android 需要 **perl** 与 NDK 工具链，首次耗时明显变长，so 体积从约 3 MB 涨到 7 MB 左右。

**两种构建都能跑，客户端会如实反映状态**：如果放入的是明文版 so，`nativeIsEncrypted()` 探针会抛 `UnsatisfiedLinkError`，App 退回明文打开并在设置页显示「明文」，不会假装加密成功。

### 3. 编译 APK

```bash
./gradlew.bat assembleDebug assembleRelease      # Windows
./gradlew assembleDebug assembleRelease          # Linux / macOS

# 产物
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

`local.properties` 不会被提交（含本机 SDK 路径），Android Studio 首次同步会自动生成；命令行构建请确保 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 已设置。

**关于 Release 签名**：仓库不含任何签名密钥。未配置 `keystore.properties` 时，release 构建会回退到本机 debug keystore —— 仅用于本地验证，**不要用它分发**。

---

## 项目结构

```text
app/src/main/
├── AndroidManifest.xml                  # 权限、ClipboardSyncService、无障碍服务
├── jniLibs/arm64-v8a/libmuclip_core.so  # ← 需自行放入（见「快速开始」）
└── kotlin/com/muclip/
    ├── core/                            # Rust JNI 桥接与零拷贝加密封装
    │   ├── MuClipNative.kt              # external 函数声明与动态库加载
    │   ├── MuClipCore.kt                # SQLite 历史记录、常用语、安全配置 KV
    │   ├── MuClipSync.kt                # 发现、配对、剪贴板广播、文件传输
    │   ├── MuClipSyncCallback.kt        # Rust 异步回调接口
    │   ├── DirectBufferPool.kt          # 堆外 DirectByteBuffer 零拷贝内存池
    │   ├── InPlaceAead.kt               # 原位 XChaCha20-Poly1305 与 57B AAD 构造
    │   └── StreamingFileTransfer.kt     # 发送端 128KiB 零分配流式加密
    └── app/
        ├── MuClipApplication.kt         # 单例、数据库初始化、SyncEngine 启动
        ├── activity/                    # MainActivity、ClipboardActionActivity
        ├── service/
        │   ├── ClipboardSyncService.kt  # 常驻前台服务、锁保活、落库与通知
        │   └── MuClipAccessibilityService.kt  # 可选，后台复制自动检测
        ├── data/                        # 配对持久化、KeyStore、事件总线、SAF 工具
        ├── viewmodel/                   # Sync / Clipboard / FileTransfer
        └── ui/screens/                  # Compose M3 界面
```

协议细节、模块职责与「改哪里」的完整索引在核心仓库：

- [架构索引 `ARCHITECTURE_INDEX.md`](https://github.com/katomegumi1999/MuClip/blob/main/docs/ARCHITECTURE_INDEX.md)
- [Android 集成指南 `KOTLIN_ANDROID_GUIDE.md`](https://github.com/katomegumi1999/MuClip/blob/main/docs/KOTLIN_ANDROID_GUIDE.md)

---

## 设计要点

### 后台不直接写系统剪贴板

Android 10+ 禁止后台应用读写剪贴板。因此**后台回调里绝不调用** `ClipboardManager.setPrimaryClip`：

```
Rust 推送到达
  → ClipboardSyncService 落库 core.addHistory(content)
  → SyncEventManager 缓存到 pendingClipboardWrite
  → 用户回到前台，MainActivity 获焦时 checkAndApplyPendingClipboardWrite() 写入
```

这条不变量是客户端能稳定工作的前提，修改接收路径时不要破坏它。

### 前台服务类型选 `connectedDevice|specialUse`

**刻意不声明 `dataSync`**。Android 15+ 对 `dataSync` 有每 24 小时累计 6 小时的硬性额度，超时会调用 `Service.onTimeout()` 并强杀应用。局域网 P2P 同步属于「与外部设备交互」，用 `connectedDevice | specialUse` 不受该限制。

### R8 必须保留 JNI 回调方法名

Rust 侧通过 `GetMethodID` 按**字符串方法名**解析回调目标，而 `env.call_method(...)` 的错误是被丢弃的。一旦 R8 把 `onClipboardReceived` 之类的方法名混淆成 `a()`，Java 侧不会崩溃，但远端剪贴板、配对请求、文件传输事件会**全部静默失效**。相关 `-keep` 规则与原因写在 [`proguard-rules.pro`](app/proguard-rules.pro) 里。

### 本地数据保护分层

| 层 | 保护方式 | 说明 |
| :--- | :--- | :--- |
| 传输 | XChaCha20-Poly1305 | 端到端加密，密钥由 SPAKE2 配对派生，PIN 不上线 |
| 本地库 | SQLCipher（可选 feature） | raw key 跳过 PBKDF2，32 字节密钥本体在 TEE |
| 备份 `.mubc` | deflate + XChaCha20-Poly1305 + PBKDF2 10 万轮 | 口令与本地库密钥完全独立，文件拷走没口令打不开 |

---

## 常见修改位置

1. **后台收到电脑推送后的行为（通知 / 落库 / 写剪贴板策略）**
   [`ClipboardSyncService.kt`](app/src/main/kotlin/com/muclip/app/service/ClipboardSyncService.kt) → `registerNativeSyncCallbacks()` 的 `onClipboardReceived`
2. **常驻通知栏按钮、文案、划掉自动恢复**
   同上 → `buildPersistentNotification()`、`onStartCommand()`、`startBackgroundMonitorLoop()`
3. **接收文件的保存目录**
   同上 → `onCreate()` 中的 Download/MuClip 目录配置，`onFileCompleted` 的 `MediaScannerConnection.scanFile`
4. **已配对设备的持久化与信任恢复**
   [`PairingPreferences.kt`](app/src/main/kotlin/com/muclip/app/data/PairingPreferences.kt) → `getPairedDevices()` / `setDevicePairedInfo()`
5. **本地库加密策略与 `.mubc` 备份**
   密钥 [`LocalDbKey.kt`](app/src/main/kotlin/com/muclip/app/data/LocalDbKey.kt) → `getOrCreate()`；界面 [`SettingsScreen.kt`](app/src/main/kotlin/com/muclip/app/ui/screens/SettingsScreen.kt) 的「加密备份」卡片

---

## 隐私与权限

这个应用会读取你的剪贴板，这是它存在的意义。以下是它实际需要的东西：

| 权限 | 用途 |
| :--- | :--- |
| `INTERNET` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` | 局域网设备发现（UDP 组播）与 P2P 传输 |
| `FOREGROUND_SERVICE` 系列 | 常驻同步服务 |
| `POST_NOTIFICATIONS` | 同步结果与配对请求通知 |
| `WAKE_LOCK` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 防止休眠时断连 |
| `REQUEST_INSTALL_PACKAGES` | 安装接收到的 APK。Android 8.0+ 起，没有这个声明时系统安装器会直接拒绝启动（表现是「点了打不开」）。用户仍需在系统设置里为本应用单独打开「安装未知应用」 |
| 无障碍服务（**可选，默认关闭**） | 在后台自动检测复制动作，免去每次手动打开应用 |

- **没有账号，没有遥测，没有云服务器。** 数据只在你的设备之间点对点传输。
- 无障碍服务是可选项，不开启时应用功能完整，只是需要回到前台才能同步剪贴板。
- `WRITE_EXTERNAL_STORAGE` 仅对 API ≤ 28 声明；API 29+ 走 MediaStore。

> ⚠️ **本项目不面向 Google Play 分发**。后台读取剪贴板与无障碍服务的使用方式不符合 Play 的部分政策条款（`READ_CLIPBOARD_IN_BACKGROUND`、`BIND_ACCESSIBILITY_SERVICE` 的用途限制）。请自行编译安装，或使用 Releases 提供的 APK。

---

## 已知限制

- 仅支持 arm64-v8a，无 armeabi-v7a / x86_64。
- 设备发现广播与心跳为明文（未配对设备需要能看到彼此才能发起配对）；业务载荷全程加密。
- 未开启无障碍服务时，后台无法自动捕获复制动作。
- 明文版 so 构建下，剪贴板历史以明文 SQLite 落盘（设置页会标注「明文」）。

---

## 贡献

本仓库特有的硬性约束（JNI 回调、后台剪贴板不变量、FileProvider 范围、需要补测试的改动）见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

跨仓库的通用规约（提交信息、分支、代码约定）见核心仓库的 [`CONTRIBUTING.md`](https://github.com/katomegumi1999/MuClip/blob/main/CONTRIBUTING.md)。

```bash
./gradlew.bat assembleDebug assembleRelease test      # Windows
./gradlew assembleDebug assembleRelease test          # Linux / macOS
```

三条都要过，且构建输出里不应出现新的 `w:` 警告。**`assembleRelease` 不是可选项** —— 只有它才触发 R8，JNI 反射边界的问题在那里暴露。

改了 `core/` 下的 JNI 桥接或 Rust 核心后，**两端都要重新编译**（Windows `muclip_core.dll` + Android `libmuclip_core.so`），否则客户端之间会跑在不同协议版本上。

改动 `MuClipSyncCallback` 的方法名或签名时，必须同步更新 `proguard-rules.pro` 与 Rust 侧的 `GetMethodID` 调用，否则回调会静默失效。

## 安全

**不要用公开 issue 报告安全问题。** 请使用 GitHub 的 Private vulnerability reporting（仓库 Settings → Security → Advisories），或在该仓库的 issue 里只写「有安全问题需私下沟通」，不要贴细节。

Android 侧的攻击面与已知边界（无障碍服务、后台剪贴板落地、FileProvider、明文版构建）见 [`SECURITY.md`](SECURITY.md)；协议与加密实现的安全边界见核心仓库的 [`SECURITY.md`](https://github.com/katomegumi1999/MuClip/blob/main/SECURITY.md)。

## 版本

版本变更记录见 [`CHANGELOG.md`](CHANGELOG.md)。

## 许可证

[MIT](LICENSE) © 2026 MuClip Contributors
