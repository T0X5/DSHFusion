package com.dshfusion.app.di

import android.content.Context
import android.os.PowerManager
import com.dshfusion.app.R
import com.dshfusion.app.bridge.BridgeRouter
import com.dshfusion.app.bridge.DeviceCapabilitySettings
import com.dshfusion.app.bridge.DeviceCapabilityWiring
import com.dshfusion.app.bridge.api.BridgeApi
import com.dshfusion.app.runtime.RuntimeUpdateManager
import com.dshfusion.app.sandbox.DefaultSandboxManager
import com.dshfusion.app.sandbox.SandboxConfig
import com.dshfusion.terminal.DshTerminalManager

/**
 * Creates the MVP graph. BridgeApi is stubbed in Phase 0/1; the first working
 * implementation should live in the sandbox-manager module and be wired here.
 */
object ServiceLocator {
    fun createAppContainer(context: Context): AppContainer {
        val sandboxConfig = SandboxConfig(
            appFilesDir = context.filesDir,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            // npm 下载缓存宿主目录（guest 侧 bind 为 /root/.npm），
            // 随「应用缓存」可一键清理，不再占 base/root/.npm 的红线区空间。
            appCacheDir = context.cacheDir,
        )
        // 设备交互状态（唤醒且亮屏）交给健康循环：休眠期间 guest 会被整体冻结，
        // 端口在听但 HTTP 不应答，此时的探测失败是暂时状态，不该判为 DSH 宕机。
        val powerManager = context.getSystemService(PowerManager::class.java)
        val sandboxManager = DefaultSandboxManager(
            sandboxConfig,
            isDeviceInteractive = { powerManager?.isInteractive ?: true },
        )
        val noopBridge = object : BridgeApi {
            override suspend fun getCurrentWorkspace(): String = "/root/projects"
            override suspend fun setCurrentWorkspace(path: String) = Unit
            override suspend fun listWorkspaces(): List<String> = emptyList<String>()
            override suspend fun createWorkspace(path: String) = Unit
            override suspend fun listDirectory(path: String): List<com.dshfusion.app.bridge.model.FileEntry> = emptyList()
            override suspend fun stat(path: String) =
                com.dshfusion.app.bridge.model.FileEntry(path, path, true, null, null)
            override suspend fun readText(path: String) =
                com.dshfusion.app.bridge.model.FileContent(path, "")
            override suspend fun writeText(path: String, content: String) = Unit
            override suspend fun createDirectory(path: String) = Unit
            override suspend fun delete(path: String) = Unit
            override suspend fun move(from: String, to: String) = Unit
            override suspend fun copy(from: String, to: String) = Unit
            override suspend fun execute(request: com.dshfusion.app.bridge.model.CommandRequest) =
                com.dshfusion.app.bridge.model.CommandResult(null, "", "bridge not implemented", timedOut = false)
            override suspend fun cancel(processId: Long) = Unit
            override suspend fun listProcesses(): List<Long> = emptyList<Long>()
            override suspend fun killProcess(processId: Long) = Unit
            override suspend fun showNotification(title: String, body: String) = Unit
            override suspend fun clipboardRead(): String = ""
            override suspend fun clipboardWrite(text: String) = Unit
        }
        val bridgeRouter = BridgeRouter(delegate = noopBridge, expectedDshToken = "")
        // 设备能力层：构造对象图，然后**按用户开关决定要不要起**。
        // 开关默认关（`DeviceCapabilityDefaults.ENABLED`），所以全新安装上
        // 这个服务一次都不会监听端口 —— 这是 3.4 边界 1 要的形态。
        val deviceCapability = DeviceCapabilityWiring.create(sandboxConfig.userDataDir)
        // **唯一的启动点。** 值来自用户开关（默认关），不是一个常量 ——
        // 套件 5z 节盯的就是这件事：主源码里只允许这一个调用点，且不许写死 `true`。
        //
        // 起不来（端口被占之类）不该让 App 起不来，但也**不许静默**：
        // 记一条 ASCII 标识的警告，因为中文日志会撞 i18n 门禁，而它判得对 ——
        // 日志里的标识符不该随语言变。
        try {
            deviceCapability?.bridge?.startIfEnabled(DeviceCapabilitySettings.isEnabled(context))
        } catch (error: java.io.IOException) {
            android.util.Log.w("DSHFusion", "DEVICE_CAPABILITY_START_FAILED", error)
        }
        val overlayAssets = listOf(
            "vim_9.1.1230-2_arm64.deb",
            "vim-common_9.1.1230-2_all.deb",
            "vim-runtime_9.1.1230-2_all.deb",
            "xxd_9.1.1230-2_arm64.deb",
            "htop_3.4.1-5_arm64.deb",
            "libgpm2_1.20.7-11+b2_arm64.deb",
            "libsodium23_1.0.18-1+deb13u1_arm64.deb",
            // 常用小工具（层里没有、也不该为此撑大 base 层）：JSON 处理、SQLite CLI、
            // 打补丁、二进制排障（strings/objdump/readelf）、简易编辑器。
            // 依赖闭包由 Debian trixie 索引解析得出（含 libjq1/libonig5/binutils 系列），
            // 逐个 sha256 校验后随 APK 分发。
            "jq_1.7.1-6+deb13u3_arm64.deb",
            "libjq1_1.7.1-6+deb13u3_arm64.deb",
            "libonig5_6.9.9-1+b1_arm64.deb",
            "libjansson4_2.14-2+b3_arm64.deb",
            "sqlite3_3.46.1-7+deb13u2_arm64.deb",
            "patch_2.8-2_arm64.deb",
            "nano_8.4-1+deb13u1_arm64.deb",
            "binutils_2.44-3_arm64.deb",
            "binutils-common_2.44-3_arm64.deb",
            "binutils-aarch64-linux-gnu_2.44-3_arm64.deb",
            "libbinutils_2.44-3_arm64.deb",
            "libgprofng0_2.44-3_arm64.deb",
            "libsframe1_2.44-3_arm64.deb",
            "libctf0_2.44-3_arm64.deb",
            "libctf-nobfd0_2.44-3_arm64.deb",
            // 包管理器侧：属主虚拟化（fakeroot 的 tcp 变体 + 其库）。
            // 伪造 root 下维护脚本的 chown/useradd 会被内核拒绝，导致带维护脚本的包必然半途而废；
            // 用 fakeroot 在虚拟层完成这些操作即可装完（sysv 变体依赖 SysV IPC，PRoot 下不可用，
            // 因此只用 tcp 变体）。解包不跑维护脚本，所以这两个 deb 直接落盘即可。
            "fakeroot_1.37.1.1-1_arm64.deb",
            "libfakeroot_1.37.1.1-1_arm64.deb",
        )
        val overlayInstaller = com.dshfusion.terminal.TerminalOverlayInstaller(
            assetBridge = { name, target ->
                try {
                    context.assets.open("terminal-packages/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                } catch (t: Throwable) {
                    android.util.Log.e("DshBoxAssets", "asset copy failed: $name", t)
                    false
                }
            },
            assetNames = overlayAssets,
        )
        val dshTerminalManager = DshTerminalManager(
            pathsProvider = {
                com.dshfusion.terminal.TerminalPathsResolver.resolve(
                    appFilesDir = sandboxConfig.appFilesDir,
                    nativeLibraryDir = sandboxConfig.nativeLibraryDir,
                )
            },
            overlayInstaller = overlayInstaller,
            // 会话标题由 app 层资源化生成（终端 1 / 受限 1 → Terminal 1 / Failsafe 1）。
            titleFormatter = { kind, order ->
                val res = when (kind) {
                    com.dshfusion.terminal.DshTerminalManager.Kind.SANDBOX -> R.string.terminal_session_sandbox
                    com.dshfusion.terminal.DshTerminalManager.Kind.FAILSAFE -> R.string.terminal_session_failsafe
                }
                context.getString(res, order)
            },
        )
        val runtimeUpdateManager = RuntimeUpdateManager(context, sandboxManager)
        val onlineRuntimeImportManager = com.dshfusion.app.runtime.OnlineRuntimeImportManager(
            context, sandboxConfig, sandboxManager,
        )
        return AppContainer(
            context,
            sandboxConfig,
            sandboxManager,
            bridgeRouter,
            dshTerminalManager,
            runtimeUpdateManager,
            onlineRuntimeImportManager,
            deviceCapability,
        )
    }
}
