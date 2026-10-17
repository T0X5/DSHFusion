package com.dshfusion.app.bridge

import com.dshfusion.app.bridge.security.CapabilityGate
import com.dshfusion.app.bridge.server.DeviceBridgeHttpServer
import com.dshfusion.app.bridge.server.DeviceClient

import java.net.HttpURLConnection
import java.net.URL

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生命周期与端到端接线 —— JVM 单测（不碰 Android API，所以能在 `:app` 的单元测试里跑）。
 *
 * <p>为什么值得放在 `:app` 而不是 `:bridge`：它要证明的是「**App 这一层怎么接**」——
 * 服务真的起得来、真的能应答、真的能在关掉之后不再应答。
 * 门禁逻辑本身已经在 `:bridge` 里有 40 条断言，这里不重复测它。
 */
class DeviceCapabilityBridgeTest {

    private class FakeClient : DeviceClient {
        override fun version(): String = "BRIDGE_PROTOCOL=3"
        override fun uiDump(): String = "should not be reached in these tests"
    }

    private class FakeEnvironment(
        private val token: String = "test-token",
    ) : DeviceBridgeHttpServer.Environment {
        override fun originTrusted(): Boolean = true
        override fun accessibilityServiceOn(): Boolean = false
        override fun grants(): CapabilityGate.Grants = CapabilityGate.Grants.defaults()
        override fun expectedToken(): String = token
    }

    private fun reply(url: String, token: String?): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (token != null) connection.setRequestProperty("X-Token", token)
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return status to body
        } finally {
            connection.disconnect()
        }
    }

    /**
     * **头条断言：默认关 = 结构上关着。**
     *
     * <p>不是「起了一个会拒绝的服务」，而是**什么都没起**：端口是 -1、`isRunning` 为假。
     * 与阶段 3.4 边界 1 同一条要求 —— 说「不可能」，别说「不允许」。
     */
    @Test
    fun disabledNeverStartsAnything() {
        val bridge = DeviceCapabilityBridge(FakeClient(), FakeEnvironment())

        assertNull("未启用时不该返回端口", bridge.startIfEnabled(false))
        assertFalse(bridge.isRunning)
        assertEquals("未启用时端口必须是 -1", -1, bridge.port)
    }

    /** 启用之后：起得来、能应答、能停掉，而且停掉后真的不再应答。 */
    @Test
    fun enabledStartsServesAndStops() {
        val bridge = DeviceCapabilityBridge(FakeClient(), FakeEnvironment())
        val port = bridge.startIfEnabled(true)

        assertNotNull("启用时应当返回端口", port)
        assertTrue(bridge.isRunning)
        assertTrue("端口应当被真正占用", (port ?: -1) > 0)

        val (status, body) = reply("http://127.0.0.1:$port/app/version", "test-token")
        assertEquals(200, status)
        assertEquals("BRIDGE_PROTOCOL=3", body)

        bridge.stop()
        assertFalse("停掉之后不许再是运行状态", bridge.isRunning)
        assertEquals(-1, bridge.port)
    }

    /**
     * 重复调用 `startIfEnabled(true)` **不许**起第二个服务。
     *
     * <p>没有这条，某个生命周期回调被调两次就会留下一个**没人能停掉**的监听 ——
     * 而那在真机上表现为「端口被占用」，排查起来非常像端口冲突。
     */
    @Test
    fun startingTwiceReusesTheSameServer() {
        val bridge = DeviceCapabilityBridge(FakeClient(), FakeEnvironment())
        val first = bridge.startIfEnabled(true)
        val second = bridge.startIfEnabled(true)

        assertEquals("第二次应当返回同一个端口", first, second)
        bridge.stop()
    }

    /** 鉴权是接线的一部分：没有 token 时必须 401，而不是拿到数据。 */
    @Test
    fun theWiredServerStillRequiresTheToken() {
        val bridge = DeviceCapabilityBridge(FakeClient(), FakeEnvironment())
        val port = bridge.startIfEnabled(true)
        try {
            val (status, body) = reply("http://127.0.0.1:$port/app/version", null)
            assertEquals(401, status)
            assertEquals("UNAUTHORIZED", body)
        } finally {
            bridge.stop()
        }
    }
}
