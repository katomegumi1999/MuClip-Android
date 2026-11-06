# ============================================================================
# MuClip Android — R8 / ProGuard 规则
#
# 本项目只有一个真正的反射边界：Rust 侧的 JNI 回调。
# muclip-core 的 src/ffi/jni_direct/sync.rs 用
#   env.call_method(&callback, "<方法名>", "<JNI 签名>", ...)
# 在运行时通过 GetMethodID 按**字符串方法名**解析调用目标。
# R8 若把方法名混淆成 a() / b()，Java 侧不会崩溃，但 GetMethodID 会返回 null，
# 而 Rust 侧写的是 `let _ = env.call_method(...)`（错误被丢弃），
# 结果是远端剪贴板、配对请求、文件传输事件全部**静默失效**。
# 因此下面这些方法名必须原样保留。
# ============================================================================

# ---------------------------------------------------------------------------
# 1. Rust → Kotlin 的同步事件回调（8 个方法，签名见 MuClipSyncCallback）
#    onClipboardReceived / onPairRequestReceived / onPairSuccess / onPeerUnpaired
#    onFileOffer / onFileProgress / onFileCompleted / onIncomingFileStream
# ---------------------------------------------------------------------------
-keep interface com.muclip.core.MuClipSyncCallback { *; }

# 匿名实现类（MuClipApplication.initSyncEngine() / ClipboardSyncService 中的
# `object : MuClipSyncCallback { ... }`）。类名可以混淆，
# 但覆写的方法名必须与接口保持一致，否则 GetMethodID 找不到。
-keepclassmembers class * implements com.muclip.core.MuClipSyncCallback {
    public <methods>;
}

# ---------------------------------------------------------------------------
# 2. JNI 导出符号
#    external fun 的 C 符号是 Java_<包>_<类>_<方法> 拼出来的
#    （例：Java_com_muclip_core_MuClipNative_nativeRegisterSyncCallback），
#    类名、包名、方法名任何一个被改，System.loadLibrary 后就会抛
#    UnsatisfiedLinkError。
# ---------------------------------------------------------------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# 3. 崩溃日志可读性
#    混淆后 CrashHandler 写出的堆栈只有 a.b.c(SourceFile)，
#    保留行号表才能定位到具体代码行。
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ---------------------------------------------------------------------------
# 4. 已知无害的告警
#    org.json:json 与 Android 平台内置的 org.json 重复，运行时始终用平台的，
#    所以忽略该依赖里的 JDK-only 类缺失。
# ---------------------------------------------------------------------------
-dontwarn org.json.**

# ---------------------------------------------------------------------------
# 5. Kyant Backdrop & Liquid Glass
# ---------------------------------------------------------------------------
-keep class com.kyant.backdrop.** { *; }
-dontwarn com.kyant.backdrop.**

# Kotlin 协程 / Compose 的 consumer rules 由各自的 AAR 自带，无需在此重复声明。
