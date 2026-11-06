# 贡献指南

感谢愿意帮忙。这个仓库是 MuClip 套件的 Android 客户端，改动会直接影响真实用户的剪贴板与文件传输，提交前请看完下面几节。

跨仓库的通用规约（提交信息、分支、行为准则）见核心仓库的 [`CONTRIBUTING.md`](https://github.com/katomegumi1999/MuClip/blob/main/CONTRIBUTING.md)。本文只写 **Android 仓库特有**的部分。

---

## 开发环境

| 依赖 | 版本 | 说明 |
| :--- | :--- | :--- |
| JDK | 21 | 与 `jvmTarget` 一致，勿上调 |
| Android SDK | compileSdk 37 / Build-Tools 37.0.0 | 旧版 Studio 打不开工程 |
| Android NDK | 29.0.14206865 | `app/build.gradle.kts` 里已固定 |
| Gradle | 9.8.2 | wrapper 自带，勿手动升级 |
| Rust | 1.85+ | 仅编译 `libmuclip_core.so` 时需要 |

`local.properties` 不会被提交。命令行构建需设置 `ANDROID_HOME`。

国内网络可在**用户级** `~/.gradle/gradle.properties` 里加 `muclip.useCnMirrors=true` 走阿里云镜像（不要在仓库里改默认值，那是本地网络偏好）。

---

## 提交前的硬性要求

```bash
./gradlew.bat assembleDebug assembleRelease test      # Windows
./gradlew assembleDebug assembleRelease test          # Linux / macOS
```

三条都过，且**构建输出里不应出现新的 `w:` 警告**。CI 会跑同样的命令。

`assembleRelease` 不是可选项：它才会触发 R8 与资源压缩，很多问题（反射、JNI、ProGuard 规则）只在 release 构建暴露。

---

## 几条不能破坏的约束

### 1. 后台回调不得直接写系统剪贴板

Android 10+ 禁止后台应用读写剪贴板。正确链路是：

```
Rust 推送到达
  → ClipboardSyncService 落库 core.addHistory(content)
  → SyncEventManager 存入 pendingClipboardWrite
  → 用户回到前台，MainActivity 获焦时 checkAndApplyPendingClipboardWrite() 写入
```

在后台 service 回调里直接 `setPrimaryClip` 会静默失败（部分机型抛异常），且**不会有人发现**，因为此前落库已经成功。改接收路径时不要破坏这条不变量。

### 2. 改了 JNI 回调签名必须同步改两处

`Rust 侧 GetMethodID（字符串方法名）` ↔ `proguard-rules.pro 的 -keep`。

Rust 用 `env.call_method(&callback, "onClipboardReceived", ...)` 按**字符串名字**解析目标方法，而该调用的错误是被丢弃的。若 R8 把方法名混淆掉，Java 侧不崩，但剪贴板、配对、文件事件会**全部静默失效** —— 这类 bug 极难定位。

具体规则与原因写在 [`app/proguard-rules.pro`](app/proguard-rules.pro) 顶部，改动前先读。

### 3. 改了核心库要重编两端

```bash
cargo ndk -t arm64-v8a -o <本仓库>/app/src/main/jniLibs build --release
```

Windows 客户端用的是 `muclip_core.dll`。只重编一端，两端会跑在不同协议版本上，症状通常是「能发现设备但配对后收不到内容」。

协议不兼容的改动必须递增帧头 `Version`，并在 `SyncHeader::parse` 里保留明确的错误提示，不要静默降级。

### 4. `libmuclip_core.so` 不入库

`.gitignore` 已排除。PR 里不要提交二进制；需要它才能本地构建的，按 README「快速开始」自行编译或从 CI artifact 取。

### 5. 不要用 `path="."` 扩大 FileProvider 或备份范围

`res/xml/file_paths.xml` 当前只暴露 `Download/` 与应用私有目录。任何把它放宽到整个外部存储的改动都需要在 PR 里说明理由。

---

## 代码约定

- **Kotlin 官方风格**（`kotlin.code.style=official`），缩进 4 空格。
- 注释用中文可以，**变量名、函数名、类型名一律英文**。
- 不要为了消除弃用警告而引入 `@Suppress("DEPRECATION")` —— 要么按新 API 改，要么在注释里写清为什么暂时保留。
- UI 层保持 Compose 单一数据流；不要在 Composable 里直接做 I/O。
- 新增依赖前先确认：能否不引入？引入后会不会破坏 `assembleRelease` 的 R8 结果？（`material-icons-extended` 是全量图标库，误用会让 dex 膨胀数倍）

---

## 需要补测试的改动

| 改动范围 | 需要补的测试 |
| :--- | :--- |
| `core/InPlaceAead.kt` | AAD 构造字节序、错 key / 错 nonce / 篡改 tag 必须拒绝 |
| `core/DirectBufferPool.kt` | 租借归还配对、容量耗尽、跨线程归还 |
| `core/StreamingFileTransfer.kt` | 分块边界（1 字节 / 正好一块 / 一块+1）、截断、取消 |
| `data/LocalDbKey.kt` | 密钥格式（64 位 hex）、重复调用幂等、损坏密文时拒绝而不是降级 |
| 任何 JNI 边界改动 | 至少手工验证一次真实设备上的回调链路，并在 PR 里写明验证方式 |

单元测试跑在 host JVM 上，**没有 Android 运行时**：`android.util.Log`、`Context`、`MediaStore` 之类不可用，需要被测代码做依赖注入或纯化。

---

## PR 要求

1. 从 `main` 拉分支：`feat/xxx`、`fix/xxx`、`docs/xxx`
2. 描述里写清：**改了什么、为什么、怎么验证的**（有真机验证请注明机型与 Android 版本）
3. 涉及协议、加密、权限、`proguard-rules.pro` 的改动，单独说明**是否向后兼容**
4. 纯格式化改动单独成 commit
5. 提交前检查 diff 里没有：本机绝对路径、真实设备 ID、内网 IP、个人邮箱、明文密钥

---

## 文档改动同样欢迎

README 里跑不通的命令、过期的版本号、与代码不符的描述，都是有效贡献。**技术描述必须可验证** —— 写「支持 XXX MB/s」要给复现命令与测试环境，写「已加密」要能指出加密发生在哪个文件哪一行。含糊的营销式描述会被 review 打回。
