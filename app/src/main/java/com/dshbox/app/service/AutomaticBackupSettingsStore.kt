package com.dshbox.app.service

import android.content.Context
import com.dshbox.app.backup.DshAutomaticBackupRunner

/**
 * 自动备份设置的持久化。
 *
 * ## 为什么七个字段各自读写，而不是序列化一整个对象
 *
 * 因为**读写发生在不同的时刻**：`last` 由任务自己写（每次成功之后），
 * 其余由界面写。整存整取会让「任务写 last」与「界面改模式」互相覆盖 ——
 * 而那种覆盖是**静默的**：用户改了模式，任务是几秒前读到的旧快照，
 * 写回时把模式又盖回去了。
 *
 * ## 为什么不放在 `:backup` 模块里
 *
 * 它需要 `Context`，而 `:backup` 目前一行 Android 依赖都没有（除了那个
 * 我们特意导入的 `AndroidBackupFileSystem`）。设置属于**应用**，
 * 不属于备份格式。`DshAutomaticBackupRunner.Settings` 是纯数据，
 * 由 [read] 在这里装配 —— 那条边界保持不变。
 */
object AutomaticBackupSettingsStore {

    /** 单独一个偏好文件：与界面语言/主题那些互不干扰。 */
    private const val PREFS = "automatic_backup"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODE = "mode"
    private const val KEY_INTERVAL_HOURS = "interval_hours"
    private const val KEY_MINUTE_OF_DAY = "minute_of_day"
    private const val KEY_ANCHOR = "anchor"
    private const val KEY_LAST = "last"
    private const val KEY_STOPPED = "stopped"

    /** 三种模式。**字符串值一旦发布不可更改** —— 它们被写进偏好文件。 */
    const val MODE_INTERVAL = "interval"
    const val MODE_DAILY = "daily"
    const val MODE_STOP = "stop"

    /** 默认间隔。与 `AutomaticBackupPolicy` 允许的 1–168 一致。 */
    const val DEFAULT_INTERVAL_HOURS = 24

    /** 默认时刻：凌晨 3 点。 */
    const val DEFAULT_MINUTE_OF_DAY = 3 * 60

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        val editor = prefs(context).edit().putBoolean(KEY_ENABLED, value)
        // 首次打开时记下 anchor —— 策略类用它算「第一次该在什么时候」。
        // 不记的话 `daily` 模式会立刻到期（anchor=0 意味着「很久以前」）。
        if (value && prefs(context).getLong(KEY_ANCHOR, 0L) == 0L) {
            editor.putLong(KEY_ANCHOR, System.currentTimeMillis())
        }
        editor.apply()
    }

    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_DAILY) ?: MODE_DAILY

    fun setMode(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MODE, value).apply()
    }

    fun intervalHours(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)

    fun setIntervalHours(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_HOURS, value).apply()
    }

    fun minuteOfDay(context: Context): Int =
        prefs(context).getInt(KEY_MINUTE_OF_DAY, DEFAULT_MINUTE_OF_DAY)

    fun setMinuteOfDay(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MINUTE_OF_DAY, value).apply()
    }

    /** 上一次成功的自动备份时刻；0 = 从没成功过。**只有任务自己写这个字段。** */
    fun last(context: Context): Long = prefs(context).getLong(KEY_LAST, 0L)

    fun setLast(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_LAST, value).apply()
    }

    /**
     * 沙箱上一次停止的时刻。
     *
     * ⚠️ **诚实边界：目前只有界面上的「停止」会记它。**
     * 通知栏的停止动作走的是 `SandboxService` 内部那条路径，**没有记** ——
     * 而那条路径我们还没碰（它是上游文件，改动要单独登记）。
     * 所以 `stop` 模式的到期判定在「从通知栏停止」之后不会触发。
     * 这是**已知缺口**，不是「大概能用」。
     */
    fun stopped(context: Context): Long = prefs(context).getLong(KEY_STOPPED, 0L)

    fun setStopped(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_STOPPED, value).apply()
    }

    /** 装配成纯数据，交给 `:backup` 里的编排。**这是两个世界之间唯一的桥。** */
    fun read(context: Context): DshAutomaticBackupRunner.Settings =
        DshAutomaticBackupRunner.Settings(
            mode(context),
            intervalHours(context),
            minuteOfDay(context),
            prefs(context).getLong(KEY_ANCHOR, 0L),
            last(context),
            stopped(context),
        )
}
