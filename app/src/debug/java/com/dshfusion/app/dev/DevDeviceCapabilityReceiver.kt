package com.dshfusion.app.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dshfusion.app.DshApp
import com.dshfusion.app.bridge.DeviceCapabilitySettings
import java.io.IOException

/**
 * **DEBUG 构建专用**的开关翻板，给 3.5 真机验收用。
 *
 * ## 为什么要有它
 *
 * 阶段 3.4 里「默认关」是靠三样东西守住的：默认常量 false、值来自用户设置、
 * 主源码只有一个启动点。但**设置页开关还没做**，所以真机上没有合法路径把它打开 ——
 * 而 3.5 要验的正是「关着时做不了 / 打开后能做 / 再关掉又做不了」。
 *
 * 于是按 `DevInstallReceiver` 的先例，做一个**只存在于 debug 变体**的翻板：
 * release 的清单里根本没有这个接收器，**不是靠运行时判断关掉的**。
 *
 * ## 它不做鉴权以外的任何事
 *
 * 只翻一个布尔 + 起停服务，不接受端口、路径或命令参数 ——
 * 一个「测试入口」不该长得像一把瑞士军刀。
 *
 * ## 用法
 *
 * ```
 * adb shell am broadcast -n com.dshfusion.app/.dev.DevDeviceCapabilityReceiver \
 *   -a com.dshfusion.app.dev.action.DEVICE_CAPABILITY --ez enabled true
 * ```
 *
 * 结果同时写进 `setResultData`（`am broadcast` 会打印）与 logcat，
 * 因为**广播结果会超时**，而超时不等于没发生（与 DevInstallReceiver 同一个坑）。
 */
class DevDeviceCapabilityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEVICE_CAPABILITY) return

        val enable = intent.getBooleanExtra(EXTRA_ENABLED, false)
        val previous = DeviceCapabilitySettings.setEnabled(context, enable)

        val app = context.applicationContext as? DshApp
        val wired = app?.container?.deviceCapability

        val outcome: String
        if (wired == null) {
            // 没有这个对象 = 桥的 token 拿不到。**如实说，不假装关掉了**。
            outcome = "UNAVAILABLE"
        } else {
            outcome = try {
                if (enable) {
                    // 这里写死 true 是**刻意的**：它只存在于 debug 变体，
                    // 而主源码里那条「恰好一个启动点、且不许写死」的检查
                    // 扫的是 app/src/main，所以不会与它冲突 —— 那正是作用域的意义。
                    val port = wired.bridge.startIfEnabled(true)
                    "ENABLED port=$port"
                } else {
                    wired.bridge.stop()
                    "DISABLED"
                }
            } catch (error: IOException) {
                "START_FAILED ${error.javaClass.simpleName}"
            }
        }

        Log.i(TAG, "device capability: previous=$previous requested=$enable outcome=$outcome")
        setResultData("previous=$previous outcome=$outcome")
        setResultCode(if (outcome.startsWith("UNAVAILABLE") || outcome.startsWith("START_FAILED")) 1 else 0)
    }

    companion object {
        private const val TAG = "DevDeviceCapability"
        const val ACTION_DEVICE_CAPABILITY = "com.dshfusion.app.dev.action.DEVICE_CAPABILITY"
        const val EXTRA_ENABLED = "enabled"
    }
}
