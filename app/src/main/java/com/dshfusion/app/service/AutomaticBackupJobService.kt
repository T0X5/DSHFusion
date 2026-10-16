package com.dshfusion.app.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.dshfusion.app.DshApp
import com.dshfusion.app.backup.DshAutomaticBackupRetention
import com.dshfusion.app.backup.DshAutomaticBackupRunner
import com.dshfusion.app.backup.DshBackupExportBridge
import com.dshfusion.app.backup.DshBackupScope
import com.deepseekharness.app.backup.AndroidBackupFileSystem
import com.deepseekharness.app.backup.BackupControl
import java.io.File
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * 自动备份的调度**胶水**。
 *
 * ## 它只做三件事
 *
 * ① 每 15 分钟被系统拉起来一次；② 读出设置、调 [DshAutomaticBackupRunner]；
 * ③ 成功就记下 `last`。
 *
 * **「该不该备份」「保留几份」一条都不在这里** —— 前者是逐字节导入的
 * `AutomaticBackupPolicy`，后者是 `DshAutomaticBackupRetention`，
 * 两者的顺序由 `DshAutomaticBackupRunner` 编排。这一层薄到几乎没有可错的余地，
 * 这是有意的：它跑在设备上，而本机验不了它（Kotlin + Android API）。
 *
 * ## 为什么是「每 15 分钟检查一次」而不是「按用户设的间隔设周期」
 *
 * 因为用户的三种模式（`interval` / `daily` / `stop`）不是三种周期：
 * `daily` 要的是**某个本地时刻**，`stop` 要的是**某个事件之后**。
 * `JobInfo.setPeriodic` 表达不了后者，而表达前者需要自己算下一次在几点 ——
 * 那么算错了就是**永远不备份**，且没有任何东西会报错。
 *
 * 所以周期固定成一个**检查心跳**，真正的判定交给那个有 4 条测试的纯逻辑类。
 * 代价是最多有 15 分钟的偏差 —— 对「每天备份一次」这件事可以忽略。
 *
 * ## 用平台自带的 JobScheduler，不引新依赖
 *
 * 与 `pilot/interlock-relay-core` 的 `RelayA11yWatchdog` 同一条约定。
 */
class AutomaticBackupJobService : JobService() {

    /** 单线程：两次备份同时跑会让「保留 N 份」的计数与删除互相打架。 */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters?): Boolean {
        val application = applicationContext as? DshApp ?: return false
        val context = applicationContext
        if (!AutomaticBackupSettingsStore.enabled(context)) return false

        worker.execute {
            try {
                val outcome = runOnceFor(context, application.container.sandboxConfig.userDataDir)
                if (outcome.ran && outcome.reason.isEmpty()) {
                    // **只有成功才推进 `last`。** 失败也推进的话，
                    // 一次磁盘满就会把「上次备份」推到未来，之后再也不重试。
                    AutomaticBackupSettingsStore.setLast(context, System.currentTimeMillis())
                }
                Log.i(TAG, outcome.describe())
            } catch (error: Exception) {
                // 不吞：Logcat 里会留下这一行，而下一个周期还会再试。
                // 日志用英文：它不是界面文案，而 i18n 门禁会（正确地）把 app/src/main 里
                // 引号内的中文判为违规。日志混进六语翻译没有意义。
                Log.w(TAG, "automatic backup failed", error)
            }
            // needsReschedule = false：这一轮的活已经干完（成功或失败都算干完），
            // 下一个周期由 `setPeriodic` 自己带来。
            // 返回 true 会让它**立刻**重跑一遍 —— 失败时那是磁盘满时的忙循环。
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // 返回 true = 排回去重试。备份是幂等的（导出一份新归档 + 清理旧的），
        // 重试不会造成损失；不重试才会造成「今天没备份」而没人知道。
        return true
    }

    companion object {
        private const val TAG = "AutomaticBackup"
        private const val JOB_ID = 0x5042

        /** 检查心跳：15 分钟是平台对周期任务的下限，写更小的值只会被向上取整。 */
        private const val HEARTBEAT_MS = 15L * 60L * 1000L

        /** 导出用的任务根。**必须在 `user-data` 之外**（编排自己会建 UUID 子目录）。 */
        private const val TASK_ROOT = "backup-task"

        /** 归档存放目录（应用私有，**不在 `user-data` 之下**）。 */
        fun archivesDir(context: Context): File = File(context.filesDir, "backups")

        /**
         * 跑一次。**界面上的「立即备份一次」与心跳走同一条路径** ——
         * 两条路各自实现一遍的话，「手动能跑、自动不能跑」这类差异不会有任何东西报出来。
         */
        fun runOnceFor(context: Context, userDataDir: File): DshAutomaticBackupRunner.Outcome {
            val archives = archivesDir(context)
            if (!archives.isDirectory() && !archives.mkdirs()) {
                return DshAutomaticBackupRunner.failed("ARCHIVE_DIRECTORY")
            }
            val taskRoot = File(context.cacheDir, TASK_ROOT)
            if (!taskRoot.isDirectory() && !taskRoot.mkdirs()) {
                return DshAutomaticBackupRunner.failed("TASK_DIRECTORY")
            }
            // 自动备份永远用全量范围：用户在导出界面选的范围是**那边**的事，
            // 不该被这里继承 —— 否则「我上次随手选了只备份设置」会让自动备份也只备份设置。
            val scope = DshBackupScope.FULL
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            return DshAutomaticBackupRunner.run(
                AndroidBackupFileSystem(),
                userDataDir,
                archives,
                taskRoot,
                DshBackupExportBridge.archiveName(scope, stampFor(now, zone)),
                DshBackupExportBridge.members(scope),
                DshBackupExportBridge.exclusions(),
                AutomaticBackupSettingsStore.read(context),
                now,
                zone,
                DshAutomaticBackupRetention.KEEP,
                BackupControl(null),
            )
        }

        private fun stampFor(now: Long, zone: ZoneId): String =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(zone)
                .format(java.time.Instant.ofEpochMilli(now))

        /**
         * 登记心跳任务。重复登记即替换，按幂等看待。
         *
         * 关掉自动备份时**不取消**它：任务仍在，只是 `onStartJob` 立刻返回。
         * 这样「关掉再打开」不需要额外的登记逻辑，而一条空转的心跳每 15 分钟醒一次的
         * 代价可以忽略（`onStartJob` 的第一件事就是读一个布尔值然后返回）。
         */
        fun schedule(context: Context) {
            val job =
                JobInfo.Builder(JOB_ID, ComponentName(context, AutomaticBackupJobService::class.java))
                    // 周期任务：不设周期的话系统会把它当成一次立即执行的任务，跑完就再无下文。
                    .setPeriodic(HEARTBEAT_MS)
                    // 不跨重启保留：开机后设置可能已被改过，持久化还要另加开机广播权限。
                    .setPersisted(false)
                    .build()
            val result =
                runCatching {
                        context.getSystemService(JobScheduler::class.java)?.schedule(job)
                            ?: JobScheduler.RESULT_FAILURE
                    }
                    .getOrDefault(JobScheduler.RESULT_FAILURE)
            if (result != JobScheduler.RESULT_SUCCESS) {
                Log.w(TAG, "failed to schedule the automatic backup heartbeat: $result")
            }
        }
    }
}
