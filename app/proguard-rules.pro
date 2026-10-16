# DSH WebUI is served over local HTTP; keep WebView and JS bridge classes.
-keep class com.dshfusion.app.bridge.** { *; }
-keepclassmembers class com.dshfusion.app.bridge.** { *; }

# Termux terminal-emulator: native methods are bound by name to libtermux.so.
# (Also shipped as consumer rules in the :terminal-emulator module.)
-keepclasseswithmembernames class com.termux.terminal.JNI {
    native <methods>;
}

# zstd-jni: native methods + the private long srcPos/dstPos bookkeeping fields of
# ZstdInputStream(NoFinalizer) are read by name from libzstd-jni-*.so via JNI
# Get/SetLongField. R8 renaming or removing these fields under release minify
# produces NoSuchFieldError: no "J" field "srcPos". Keep the whole zstd-jni tree
# intact (classes, methods, fields, signatures) so the native binding stays valid.
-keep class com.github.luben.zstd.** { *; }
-keepclasseswithmembernames class com.github.luben.zstd.** {
    native <methods>;
}


# ---------------------------------------------------------------------------
# BouncyCastle（bcprov/bcpg）：**算法实现必须整体保留，否则 release 包必挂**
#
# BC 的 provider 用「config 字符串 → 反射实例化 SPI」注册算法表，R8 的静态分析看不到这些
# 引用，会把 org.bouncycastle.jcajce.provider.** 整片剥掉；被剥掉后运行期报：
#   cannot create signature: no such algorithm: SHA256withRSA for provider BC
# 后果：在线导入 Linux（精简 Debian）层时 `Release.gpg` 验签必失败 —— 该功能在 release 包上
# **从来不可用**（App 只会显示"镜像源都不可用"）。
#
# ⚠️ 这类缺陷 debug 包测不出来（debug 不做 R8 处理），发布前必须用 release 包做一次在线导入冒烟。
#    排查手法：直接扫 APK 里的 dex 有没有 `org/bouncycastle/jcajce/provider` 与 `SHA256withRSA`。
#
# 注：sandbox-manager 的 consumer-rules.pro 里也写了同样的规则，但**没有进入合并后的
#    R8 配置**（configuration.txt 里查不到），故在 app 这边再落一遍（app 的规则确定生效，
#    同文件的 zstd / termux 规则即由这里生效）。
# ---------------------------------------------------------------------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ---------------------------------------------------------------------------
# SnakeYAML（org.yaml:snakeyaml:2.4，Apache-2.0）—— 阶段 2.3 起由 :backup 的
# CredentialProjection 使用（按 DSH 自己的 `.credentials.yaml` 布局做凭据投影）。
#
# **这条规则是保险，不是修复。** 我没有复现出「不加它就坏」的实例：我们只走
# SafeConstructor + 节点级 compose/serialize，不碰 JavaBean 反射那条路。
# 但三点事实让「不加」不值得赌：
#   1. 已解包核对 snakeyaml-2.4.jar —— META-INF 下只有 MANIFEST 与 maven 元数据，
#      **没有任何 consumer proguard 规则**，R8 可以自由改名与裁剪；
#   2. 本仓库的 release **开了混淆**（app 的 isMinifyEnabled = true）；
#   3. 上游 DSHA 的 release 是 **minifyEnabled false** —— 所以「DSHA 装了它能跑」
#      **不构成**「它在 R8 下能用」的证据。
#
# ⚠️ 诚实边界：**这条路径没有被验证过。** CI 只构建 debug（assembleDebug），
#    release 混淆后的行为要等有人真的构建 release 并运行凭据投影才能说「可用」。
# ---------------------------------------------------------------------------
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
