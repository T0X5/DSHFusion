package com.dshfusion.app.bridge

import com.dshfusion.app.BuildConfig
import com.dshfusion.app.bridge.server.DeviceClient

/**
 * 设备能力的 **Android 实现**。整个能力层里只有这一层该碰 Android。
 *
 * ## 为什么 `version()` 能真给，而 `uiDump()` 现在还不能
 *
 * `version()` 只需要应用自己的版本号 —— 它不读设备状态，也不需要任何权限。
 * `uiDump()` 要读**当前屏幕上的内容**，那需要无障碍服务（T2），
 * 而把 `interlock-relay-core` 的 `A11yBackend` 接进来是**下一个独立的一刀**（见下）。
 *
 * ## `uiDump()` 未实现时**抛异常**，而不是返回空串
 *
 * 这不是偷懒，是刻意的：抛出去会被 [com.dshfusion.app.bridge.server.DeviceEndpointDispatcher]
 * 接住并变成 **503 `DEVICE_UNAVAILABLE`**。返回空串则会让调用方以为
 * 「屏幕上什么都没有」—— **那是把「做不到」伪装成「看到了」**，
 * 比明确失败危险得多（与阶段 3.4 §三 的 fail-closed 同一条规矩）。
 *
 * ## 下一刀的落点（写在代码里，免得只活在文档）
 *
 * 1. 让本类接收一个 `ScreenDump`，由 `:app` 用一个适配器接到
 *    `interlock-relay-core` 的 `RelayAccessibilityService.current()`；
 * 2. 该适配器必须**先看服务在不在**（`current()` 为 null ⇒ 仍抛 ⇒ 仍 503），
 *    不能假设「用户开过就一直在」——无障碍服务会被系统随时回收。
 */
class AndroidDeviceClient(
    private val screenDump: ScreenDump? = null,
) : DeviceClient {

    /** 读屏。**故意做成接口**：真实现属于下一个独立的一刀，而这一刀不碰无障碍。 */
    fun interface ScreenDump {
        fun dump(): String
    }

    override fun version(): String = listOf(
        // 与上游桥同名的三项，方便将来做特性检测时用同一套判据。
        "BRIDGE_PROTOCOL=3",
        "APP_VERSION=${BuildConfig.VERSION_NAME}",
        "APP_CODE=${BuildConfig.VERSION_CODE}",
    ).joinToString("\n")

    override fun uiDump(): String {
        // 消息只用**稳定标识符**，不用散文 —— 两个理由：
        // ① 它可能被写进日志或返回给调用方，而那是协议面，散文会变、标识符不该变；
        // ② 源码里的中文字面量会被 i18n 门禁判成「硬编码文案」（它判得对：
        //    这句话不是给人读的 UI 文案，就不该长得像 UI 文案）。
        val dump = screenDump
            ?: throw UnsupportedOperationException("UI_DUMP_NOT_WIRED")
        return dump.dump()
    }
}
