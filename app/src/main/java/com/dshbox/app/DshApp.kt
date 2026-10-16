package com.dshbox.app

import android.app.Application
import com.dshbox.app.common.Constants
import com.dshbox.app.di.AppContainer
import com.dshbox.app.di.ServiceLocator
import com.dshbox.app.sandbox.OfficialPluginOverlay
import com.dshbox.app.service.DshboxLayoutMigration
import com.dshbox.app.service.SandboxService
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.service.AutomaticBackupJobService
import com.dshbox.app.service.AutomaticBackupSettingsStore
import com.dshbox.app.ui.theme.AppLocaleState
import com.dshbox.app.ui.theme.AppThemeState
import com.dshbox.app.util.FileOps
import com.dshbox.app.util.MoveEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DshApp : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * 进程级作用域，只用来观察沙箱状态。
     *
     * `SupervisorJob`：一条观察流挂掉不该影响别的（目前只有一条，但这条约定值钱）。
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = ServiceLocator.createAppContainer(this)
        AppThemeState.load(this)
        // 资产目录布局的一次性迁移（旧名 → 新名）。**必须早于任何往新路径写文件的代码**：
        // 紧随其后的那次 refresh 就会写新路径，那会让迁移误判"新文件已存在"而跳过，
        // 设备上就永久留下一份旧的 features/ 目录。
        val layout = DshboxLayoutMigration.run(
            File(container.sandboxConfig.userDataDir, Constants.DSHBOX_ASSETS_RELATIVE_DIR),
        )
        if (layout.changed) android.util.Log.i("DshApp", "dshbox layout migrated $layout")

        // 自动备份的心跳（阶段 2.6 第 ⑥ 段，DIV-0022）。
        //
        // **必须在这里重登记一次**：心跳用的是 `setPersisted(false)`（不跨重启保留），
        // 所以设备重启之后那条任务就没了。少了这一行，自动备份会在重启后
        // **静默停止** —— 用户看到开关还开着，而再也不会有新备份。
        // 这与本项目反复吃亏的那个形态同族：**一个不再发生的动作，看起来和
        // 「还没到点」完全一样。**
        //
        // 只在开启时才登记；关掉时不取消（`onStartJob` 会立刻返回）——
        // 见 `AutomaticBackupJobService.schedule` 的注释。
        if (AutomaticBackupSettingsStore.enabled(this)) {
            AutomaticBackupJobService.schedule(this)
        }

        // 「停止后」这一档的判据：沙箱**转入** STOPPED 的时刻。
        //
        // 为什么要观察状态而不是在 `stopSandbox()` 里记一笔：停止有**至少五条**路径
        // （界面上的停止按钮直接调 `sandboxManager.stopSandbox()`，**根本不经过**
        // `SandboxService`；通知栏走 `ACTION_STOP_SANDBOX`；还有运行时更新与开发用接收器）。
        // 在每一条里各记一笔是注定会漏的 —— 漏掉的那一条会**静默地**不触发备份，
        // 而用户看到的是「开关开着、什么都没发生」。
        //
        // `sandboxState` 是所有路径的**唯一汇聚点**（`DefaultSandboxManager` 改的就是它），
        // 所以观察它既覆盖全部路径，也不用改 `sandbox-manager` 一行。
        applicationScope.launch {
            container.sandboxManager.sandboxState
                .map { it == SandboxState.STOPPED }
                .distinctUntilChanged()
                .collect { stopped ->
                    if (stopped) {
                        AutomaticBackupSettingsStore.setStopped(this@DshApp, System.currentTimeMillis())
                    }
                }
        }

        // 功能开关预置层：用 dsh 官方的 `--patch` 入口打开上游"按 profile 默认关闭"
        // 的官方条目（当前为侧边栏「浏览器」）。各层互不覆盖。
        OfficialPluginOverlay.refresh(this)
        // 解析应用语言（delegate 优先、prefs 镜像兜底），供设置页与
        // 前台服务通知取用；早于 SandboxService.start 构建首条通知。
        AppLocaleState.refresh(this)
        // 启动时清理上一次移动崩溃/断电遗留的 .dsh-moving-* 中转残留。
        // 后台线程 + 延迟 30s 错峰（审查修正：不全量遍历 filesDir、避免与沙盒启动争 IO，
        // 扫描范围限定移动落点四层，见 MoveEngine.cleanupMovingResidualsInAppDirs）。
        Thread {
            runCatching {
                Thread.sleep(30_000)
                MoveEngine.cleanupMovingResidualsInAppDirs(filesDir)
            }
        }.start()
        // Start the foreground service as early as possible. On first run it
        // will bootstrap both the sandbox and DSH; afterwards the user controls
        // each independently.
        //
        // 这是**尽力而为**：进程也可能是被系统在后台拉起来的（绑定无障碍服务），此时不允许启动
        // 前台服务。`SandboxService.start` 已经把那种情况吞掉并返回 false；这里再兜一层，
        // 是因为 **`onCreate` 里任何异常都会让整个进程启动即崩**，而"沙盒晚几秒起"绝不能是崩溃的理由。
        runCatching { SandboxService.start(this) }
    }
}
