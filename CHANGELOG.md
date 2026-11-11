# 更新日志

版本号格式 `主版本.次版本.修订号`，对应 `app/build.gradle.kts` 里的 `versionName` / `versionCode`。
每次交付 APK 必须同时递增两者 —— 否则装机后无法从系统设置里确认到底跑的是哪一版。

## [0.2.1] - 2026-11-11

`versionCode` 10。

### 修复

- **接收到的 APK 无法打开。** 根因是 `AndroidManifest.xml` 没有声明 `REQUEST_INSTALL_PACKAGES`。
  Android 8.0 起，调用方缺这个声明时系统安装器会**直接拒绝启动**，既不抛异常也没有
  明显的日志痕迹，表现出来就是「点了没反应」。
  同时把 APK 从 `createChooser` 流程里分离出来，直接走 `ACTION_VIEW` —— 套一层
  chooser 会被系统当成「选一个应用打开」的普通流程，部分 ROM 上根本列不出安装器。
  另外补了 `PackageManager.canRequestPackageInstalls()` 检测：用户没为本应用打开
  「安装未知应用」时，直接引导到对应设置页，而不是静默失败。

## [0.2.0] - 2026-11-06

首个开源版本。`versionCode` 9。

### 新增

- 明暗主题跟随系统 `uiMode`，`values-night` 提供对应启动主题，避免浅色环境下启动闪黑帧。
- 液态玻璃风格底栏（`ui/components/LiquidGlassNavBar.kt`）：半透明玻璃体 + 上下双色描边 + 内部折射渐变，选中态为滑动玻璃胶囊。
- 列表内容可延伸到玻璃底栏之下（`LocalMuBottomInset`）。
- `SECURITY.md`：Android 侧攻击面清单与已知边界。
- `CONTRIBUTING.md`：本仓库特有的硬性约束（JNI 回调、后台剪贴板不变量、FileProvider 范围）。
- 单元测试 `AadLayoutTest`：文件分块 AAD 的 57 字节布局、大端编码、fileId 零填充与截断、nonce 唯一性。

### 修复

- **FileProvider 暴露整个外部存储**：`file_paths.xml` 从 `path="."` 收窄为 `Download/` 与应用私有目录。
- **打开文件时可能崩溃**：`FileTransferViewModel.openFile` 在 `getUriForFile` 失败时回退到 `Uri.fromFile`，而 targetSdk 24+ 把它交给 `startActivity` 会抛 `FileUriExposedException`。改为提示用户文件位置。
- **系统备份产生打不开的数据库**：`allowBackup` 由 `true` 改为 `false`。SQLCipher 的 raw key 存在 AndroidKeyStore，不参与系统备份，恢复后会得到「有库无密钥」的损坏状态。
- **剪贴板 API 弃用**：`DeviceDialogs.kt` 两处 `LocalClipboardManager` 改为原生 `ClipboardManager`，构建警告归零。
- **`gradlew` 缺可执行位**：git 索引里为 `100644`，Linux / macOS / CI 上 clone 后执行会 `Permission denied`。已修正为 `100755`。

### 变更

- **Release 签名不再使用 debug keystore**：改为从 `keystore.properties` 读取（已 gitignore），文件缺失时回退 debug 以便任何人本地构建。原先所有人共享同一私钥，且用户无法用正式密钥覆盖安装。
- **恢复 `AarMetadata` 依赖校验**：移除全局 `enabled = false` 的绕过。`compileSdk 37` 已能满足全部依赖，该绕过无必要，留着只会让兼容性问题在更晚、更难排查的位置爆出来。
- **仓库源默认走官方**：`google()` / `mavenCentral()` 优先，阿里云镜像降为回退。国内加速改由用户级 `~/.gradle/gradle.properties` 里的 `muclip.useCnMirrors=true` 控制，不再强加给所有贡献者。
- **移除 `foojay-resolver-convention` 插件**：项目从未声明 Java toolchain，该插件始终未被触发，却让每次构建都必须能访问 `plugins.gradle.org`。
- **测试数量** 7 → 18（新增 `AadLayoutTest` 11 项）。

### 移除

- 早期 uniffi 生成物目录 `generated/`（已被手写桥接层取代，零引用）。
- `.class` 反编译临时目录 `tmp_jar/`、`tmp_shapes/`。
- 预编译动态库不再入库，`libmuclip_core.so` 由核心仓库的 CI artifact 或本地 `cargo ndk` 构建提供。

---

## [0.1.7] - 2026-10-28

`versionCode` 8。

### 变更

- Kotlin `jvmTarget` 由 26 降回 21。项目 0 个 `.java` 文件，`compileOptions` 实际不参与编译；`jvmTarget` 只决定 class 文件版本号，D8 打包后 ART 完全看不到。Java 21 是 LTS，也是当前 Android 生态的安全上界。

---

## [0.1.6] - 2026-10-27

`versionCode` 7。

### 性能

- `AndroidKeyStoreCrypto` 缓存 `SecretKey` 句柄：原来每次加解密都要 `KeyStore.load` + `containsAlias` + `getEntry`，约 3 次 keystore2 binder 往返。缓存的是句柄（内部只有别名），密钥材料不离 TEE。
- `PairingPreferences` 缓存对端密钥：后台监控循环每 10 秒遍历在线 peer，仅为比较指纹字符串就要走 TEE 解密。稳态下省掉每小时数百次 TEE 调用。
- 全文件 8 处 `commit()` 改为 `apply()`。

### 变更

- AGP 9.3.3 → 9.4.0，Gradle 9.8.0 → 9.8.2（9.8.1 起修了 3 个高危 CVE）。
- 移除 `android.suppressUnsupportedCompileSdk=37,37.2`：AGP 9.4.0 原生支持 API 37.2。

---

## [0.1.5] - 2026-10-27

`versionCode` 6。

### 变更

- 8 个 JNI 回调的两份重复实现收敛为 `data/SyncCallbackActions.kt`，两个适配器只保留各自差异（通知文案、socket 直传）。保留「直接 implements 接口」而不用基类，因为 R8 的 `-keepclassmembers` 对间接实现匹配不确定。
- 拆分巨型文件：`ClipboardNotifier.kt`（通知渠道与提醒）、`IncomingFileReceiver.kt`（socket 直传）、`DeviceDialogs.kt` / `SettingsDialogs.kt`（参数驱动、不持有 ViewModel）。
  `ClipboardSyncService` 893→620、`DevicesScreen` 894→703、`SettingsScreen` 889→758 行。

### 移除

- `StreamingFileTransfer` 三个零引用方法（`sendFileStream` / `receiveFromSocketFd` / `receiveFileStream`），778 → 312 行。
  顺带发现：原有单测测的正是其中被删的 staging 版本，生产路径 `receiveToStream` 零覆盖。已把纯数据搬运抽成 `receiveChunksInto` 并为它补测。

---

## [0.1.4] - 2026-10-27

`versionCode` 5。

### 修复

- **前台服务被系统强杀（Android 15+）**：`dataSync` 类型有每 24 小时累计 6 小时的硬额度，超时调用 `onTimeout()`。改为 `connectedDevice|specialUse`，两者都无时间上限。
- **每次剪贴板变化崩一次**：`MuClipApplication.core` 是 `lateinit`，加密库打开失败后任何解引用都抛 `UninitializedPropertyAccessException`。新增 `coreOrNull()`，全项目 24 处解引用改为安全访问。
- **API 26~28 收文件落盘失败**：`WRITE_EXTERNAL_STORAGE` 补 `maxSdkVersion="28"` 并在 `MainActivity` 补运行时申请链。
- **MulticastLock 永久持有**：原来在 `onCreate()` 获取、`onTerminate()` 释放，而真机永不调用 `onTerminate()`。改为按 Activity 生命周期计数获取/释放。
- **主线程 KeyStore 阻塞**：`PairingPreferences.longTermKey` 每次读取都走 TEE AES-GCM（5~20ms），且在 `Application.onCreate()` 主线程上调用。加进程内缓存。

### 性能

- 开启 R8 与资源压缩：APK 12.0MB → 2.78MB。其中删除 23,143 个类，11,403 个（47%）是 `material-icons-extended` 里没用到的图标。
- `WifiLock` 由 `WIFI_MODE_FULL_LOW_LATENCY` 降为 `FULL_HIGH_PERF`：前者向固件申请低延迟通道，对低频小包流量是纯浪费。

### 新增

- `app/proguard-rules.pro`。此前该文件根本不存在（因为混淆一直关着）。JNI 回调靠 `GetMethodID` 按字符串方法名解析，且 Rust 侧丢弃了错误，方法名一旦被混淆，剪贴板/配对/文件事件会全部静默失效且 Java 侧不崩溃。

---

## [0.1.3] - 2026-10-27

`versionCode` 4。修复自适应图标在国产 ROM 上变形、通知栏图标缺底色的问题；撤销诊断用的洋红底色。

## [0.1.2] - 2026-10-27

`versionCode` 3。诊断版本：通知正文追加 `v$versionName`，用于一次性切开「资源没生效 / 装的是旧包」两个变量。

## [0.1.1] - 2026-10-27

`versionCode` 2。修复与 0.1.0 版本号相同导致无法确认装机版本的问题。

## [0.1.0]

`versionCode` 1。初版：局域网设备发现、SPAKE2 配对、剪贴板同步、文件互传。
