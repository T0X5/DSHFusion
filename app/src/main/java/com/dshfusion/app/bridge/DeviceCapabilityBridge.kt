package com.dshfusion.app.bridge

import com.dshfusion.app.bridge.server.DeviceBridgeHttpServer
import com.dshfusion.app.bridge.server.DeviceClient

import java.io.IOException
import com.dshfusion.app.bridge.server.DeviceEndpointDispatcher

/**
 * 设备能力端点的**生命周期**。
 *
 * ## 默认关，而且是在结构上关着
 *
 * 「默认关」在这里不是一句注释：**没有任何代码路径会构造本类**。
 * `ServiceLocator` 里没有它、启动流程里没有它 —— 所以这个服务在装好之后
 * **一次都不会监听端口**，直到有人在设置里显式打开、并有代码去 `startIfEnabled(true)`。
 *
 * 这与阶段 3.4 边界 1 的要求一致：**「关着」应当是「没有那条路径」，而不是「有路径但会拒绝」。**
 *
 * ## 为什么用 `startIfEnabled(Boolean)` 而不是 `start()`
 *
 * 因为它把「要不要起」这个判断**放在唯一的地方**，且签名上就带着理由。
 * 若提供不带参数的 `start()`，某个调用点早晚会忘了判断而直接调它 ——
 * 那时「默认关」就只剩一句注释了。
 */
class DeviceCapabilityBridge(
    private val client: DeviceClient,
    private val environment: DeviceBridgeHttpServer.Environment,
    /** 0 = 让系统挑端口；真机上固定端口才有意义，测试用 0。 */
    private val preferredPort: Int = 0,
) {

    // `@Volatile` 而不是给 getter 加 `@Synchronized` —— 后者在 Kotlin 里要写成
    // `@get:Synchronized`，而那个写法容易在改注解目标时悄悄失效。这里只要求可见性。
    @Volatile
    private var server: DeviceBridgeHttpServer? = null

    @Volatile
    private var boundPort: Int = -1

    val isRunning: Boolean get() = server != null

    /** 当前监听端口；没起时为 -1。 */
    val port: Int get() = boundPort

    /**
     * 只有在 `enabled` 为真时才起服务。
     *
     * @return 起的端口；未启用或已在运行则返回 {@code null}。
     *         ⚠️ 起失败会抛 {@link IOException}（端口被占用之类）——
     *         **不要吞掉**：那会让「以为开着其实没开」变成静默事实。
     */
    @Synchronized
    @Throws(IOException::class)
    fun startIfEnabled(enabled: Boolean): Int? {
        if (!enabled) return null
        if (server != null) return boundPort

        val dispatcher = DeviceEndpointDispatcher(client)
        val started = DeviceBridgeHttpServer(dispatcher, environment)
        val actual = started.start(preferredPort)
        server = started
        boundPort = actual
        return actual
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        boundPort = -1
    }
}
