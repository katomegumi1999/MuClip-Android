# 安全策略

MuClip Android 会读取剪贴板、接收文件、并（可选地）开启无障碍服务。这些能力本身就是攻击面，请认真对待。

协议层与加密实现的安全边界见核心仓库的 [`SECURITY.md`](https://github.com/katomegumi1999/MuClip/blob/main/SECURITY.md)。本文只覆盖 **Android 客户端特有**的部分。

---

## 报告漏洞

**不要用公开 issue 报告安全问题。**

请使用 GitHub 的 [Private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)（仓库 **Settings → Security → Advisories**）——全程私密，不需要暴露邮箱。

若该功能不可用，可在公开 issue 里只写「有安全问题需私下沟通」，维护者会主动联系。**不要在 issue 正文里写漏洞细节。**

报告中请包含：受影响版本 / commit、复现步骤、影响范围（能否读取剪贴板、能否解密文件、是否需要同网段、是否需要已配对或已开启无障碍）、你的严重等级评估。

**响应承诺**：48 小时内确认收到；7 天内给出初步评估；修复发布后在 advisory 中致谢（除非要求匿名）。

---

## Android 侧攻击面

| 攻击面 | 当前防护 | 已知局限 |
| :--- | :--- | :--- |
| **无障碍服务**（可选，默认关闭） | 需要用户手动在系统设置里开启；只监听 `typeViewClicked` / `typeViewTextSelectionChanged` / `typeWindowStateChanged` / `typeWindowContentChanged` | 一旦开启，本服务在系统层面可读取所有窗口内容。代码只从中提取剪贴板动作，但**权限本身远超该用途**。这是 Android 平台的结构性风险，无法在应用内收敛 |
| **后台剪贴板落地** | 后台收到推送只写 SQLite 并缓存到 `SyncEventManager.pendingClipboardWrite`（进程内存），回到前台才写系统剪贴板 | 待写内容在写入系统剪贴板前一直驻留进程内存；同进程被注入或 root 环境可直接读取 |
| **接收文件落盘** | 写入公共「下载/MuClip」，通过 `MediaScannerConnection` 刷新媒体库 | 公共目录下的文件对拥有存储权限的应用可读。加密保护只在**传输链路**，落盘即明文 |
| **FileProvider** | `exported="false"`，仅通过 `FLAG_GRANT_READ_URI_PERMISSION` 按次授权；路径限定为 `Download/` 与应用私有目录 | 分享出去的文件在目标应用持有的 Uri 生命周期内可被其读取 |
| **APK 安装引导**（`REQUEST_INSTALL_PACKAGES`） | 只在用户点击「打开」一个**接收到的** .apk 时生效；未授予「安装未知应用」时只跳设置页，不做任何静默动作 | 该权限让本应用具备引导安装任意 APK 的能力。若对端设备已被控制，攻击者可以推一个恶意 APK 过来等你点。系统安装界面会列出所请求的权限并要求确认，但人往往直接点「安装」。**不要把本应用用在你不信任的对端上** |
| **系统备份** | `allowBackup="false"` | 用户无法通过系统备份迁移数据，需走应用内的 `.mubc` 加密备份 |
| **本地数据库** | SQLCipher（可选 feature），32 字节 raw key 由 `LocalDbKey` 生成并存入 AndroidKeyStore（TEE 保护） | 明文版构建（plain feature）下历史记录以明文 SQLite 落盘，设置页会标注「明文」；root 环境可读 TEE 之外的密钥材料 |
| **配对 PIN** | 走 SPAKE2，PIN 不上线；key 由配对派生 | 6 位 PIN 在无速率限制的场景下可离线枚举（同核心仓库的协议局限） |
| **剪贴板历史暴露面** | 数据存在应用私有目录 | 若开启无障碍且设备已被 root，历史内容与实时剪贴板同等级暴露 |

---

## 已知边界（请理解后再使用）

### 1. 明文版构建是真实存在的

打包进去的 `libmuclip_core.so` 若不支持 SQLCipher，`nativeIsEncrypted()` 探针会抛 `UnsatisfiedLinkError`，应用**如实退回明文打开**并在设置页显示「明文」。它不会假装加密成功，但也不会阻止你继续使用。

自行编译时请显式加 `--no-default-features --features sqlcipher`。

### 2. 设备发现与心跳是明文

未配对设备需要能看见彼此才能发起配对，因此 UDP 发现广播与 TCP 心跳不加密。业务载荷（剪贴板内容、文件、配对握手）全程 AEAD 加密。

后果：同网段的人能观测到「有两台设备在用 MuClip」，还能看到设备标识与在线状态，但看不到内容。

### 3. 无障碍服务是过度授权

系统不允许应用在后台读取剪贴板，绕过它只能靠无障碍捷径。这是平台限制导致的妥协，不是设计上的优雅选择。**如果你不接受这个权限换来的便利，就不要开启它** —— 不开启时应用功能完整，只是需要回到前台才同步剪贴板。

### 4. 本应用不适合处理高敏感数据

出于上述边界，不建议用它同步密码、私钥、身份证件等材料。它解决的是「在信任的局域网里、自己的设备之间传普通文本与文件」。

---

## 不在范围内

- 已 root 的设备上的本地数据提取
- 恶意输入法、恶意无障碍服务等系统级威胁
- 物理接触设备后的攻击
- 用户主动把 `.mubc` 备份与口令放在同一位置导致的泄露
- 第三方应用在获得 `FLAG_GRANT_READ_URI_PERMISSION` 后滥用该权限
