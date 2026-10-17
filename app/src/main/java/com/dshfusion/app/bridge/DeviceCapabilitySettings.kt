package com.dshfusion.app.bridge

import android.content.Context
import com.dshfusion.app.bridge.security.DeviceCapabilityDefaults

/**
 * 设备能力层的用户开关。
 *
 * **默认值来自 `DeviceCapabilityDefaults.ENABLED`（false），而不是在这里再写一个 `false`** ——
 * 那样「默认值」就有了两个真值来源，而它们迟早会分叉。
 *
 * 打开它必须是**用户的一次显式动作**（将来的设置页），不是代码里的一个常量。
 * 这也是套件 5z 节要盯的东西：调用点只有一个，且不许写死 `true`。
 */
object DeviceCapabilitySettings {

    private const val PREFERENCES = "device_capability"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DeviceCapabilityDefaults.ENABLED)

    /** 供将来的设置页调用。**返回上一次是否已启用**，方便界面回滚。 */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.getBoolean(KEY_ENABLED, DeviceCapabilityDefaults.ENABLED)
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        return previous
    }
}
