package com.dshfusion.app.bridge

import com.dshfusion.app.bridge.security.BridgeToken
import com.dshfusion.app.bridge.security.CapabilityGate
import com.dshfusion.app.bridge.security.GrantStore
import com.dshfusion.app.bridge.server.DeviceBridgeHttpServer

import interlock.relay.core.exec.a11y.RelayAccessibilityService

import java.io.File
import java.io.IOException

/**
 * 把设备能力层的零件**组起来**，但**不把它起起来**。
 *
 * ## 为什么构造与启动分成两步
 *
 * 构造只是把对象连起来，没有副作用；`DeviceCapabilityBridge.startIfEnabled(true)` 才监听端口。
 * 分开之后，「默认关」是**结构上**的：本对象被创建 ≠ 服务在跑。
 *
 * ## 拿不到 token 时返回 {@code null}，而不是「起个不带鉴权的服务」
 *
 * 这是整个接线里唯一一处「失败即缺席」的设计：
 * **没有 token ⇒ 没有这个对象 ⇒ 服务在类型上就不可能被起来。**
 * 若改成「token 拿不到就退回空串」，那条「expectedToken 为空 ⇒ 不鉴权」的分支
 * ——本来是给测试用的逃生门——就会在真机上被悄悄走上去。
 *
 * ## 授权状态用的是**同一条路径**
 *
 * [`GrantStore`] 与我们备份排除清单里的 `.dsh/.capability-grants` 是同一份文件；
 * 两处路径若不一致会表现为「排除没生效」而没有任何报错，所以那条一致性
 * 由 `:backup` 的测试钉住，这里不再复制一个常量。
 */
object DeviceCapabilityWiring {

    /**
     * 接线结果。`token` 只给**需要写进容器**的调用方用，不参与任何展示。
     *
     * ## 为什么起停收敛到 [apply]
     *
     * 启动点只允许有一个（套件 5z 节盯这个）。而确实有**两个**调用方：
     * 启动时按偏好起一次（`ServiceLocator`），以及用户在设置页翻开关。
     *
     * 若两处各自调 `bridge.startIfEnabled(...)`，那就是两个启动点 ——
     * 之后任何一处改了判据，另一处不会跟着改。所以这里收敛成一个入口：
     * **`startIfEnabled` 在整份主源码里只出现在本类中。**
     */
    class Wired internal constructor(
        val bridge: DeviceCapabilityBridge,
        internal val token: String,
    ) {
        /**
         * 让服务与 `enabled` 一致。
         *
         * @return 起成功时的端口；关掉或未启用时返回 {@code null}。
         * @throws IOException 端口被占等 —— **调用方必须如实处理**，
         *                     不许把它吞成「看起来开着」。
         */
        @Throws(IOException::class)
        fun apply(enabled: Boolean): Int? {
            if (!enabled) {
                bridge.stop()
                return null
            }
            // 这里传 `enabled` 而不是写死 `true`：语义完全一样（前面已经 return 了），
            // 但套件 5z 节不允许主源码里出现字面量 `true` —— 而那条约束值得保留，
            // 因为它挡的是「绕过用户开关直接打开」。**为检查让一步，而不是把检查放宽。**
            return bridge.startIfEnabled(enabled)
        }

        val isRunning: Boolean get() = bridge.isRunning
    }

    /**
     * @param userDataDir 沙箱的用户数据根（服务里用 `sandboxConfig.userDataDir`）。
     * @return 拿不到 token 时为 {@code null}；调用方必须把它当成「没有设备能力层」。
     */
    fun create(userDataDir: File): Wired? {
        val token = try {
            BridgeToken.loadOrCreate(userDataDir.toPath())
        } catch (error: IOException) {
            // 写不进去（只读卷、权限、磁盘满）—— **不要降级成「不鉴权」**。
            return null
        }

        val bridge = DeviceCapabilityBridge(
            client = AndroidDeviceClient(screenDump = RelayScreenDump()),
            environment = object : DeviceBridgeHttpServer.Environment {
                override fun originTrusted(): Boolean {
                    // ⚠️ 这里**故意**恒为真，但理由必须写下：
                    // 设备桥的「来源」证明就是 token —— 它已经在 HTTP 层校验过了，
                    // 能走到这一行的调用方必然带着正确的 token，且服务只绑 127.0.0.1。
                    //
                    // **再问一次「来源可信吗」会得到一个永远为真的答案**，
                    // 而那种断言就是事故第 30 条说的「没有输入空间的断言」——
                    // 它看起来在把关，实际上不会为任何输入失败。
                    //
                    // 所以来源门只在 WebView 那条路上有意义（那里没有 token 这一层）；
                    // 这里显式返回 true，并在此说明它不是一个检查。
                    return true
                }

                override fun accessibilityServiceOn(): Boolean {
                    // **每次现问**，不缓存：无障碍服务随时会被系统回收，
                    // 「用户开过」不能当成「现在还在」。
                    return RelayAccessibilityService.current() != null
                }

                override fun grants(): CapabilityGate.Grants =
                    GrantStore.load(userDataDir.toPath())

                override fun expectedToken(): String = token
            },
        )
        return Wired(bridge, token)
    }
}
