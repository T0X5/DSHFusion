package com.dshfusion.app.sandbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections

/**
 * 健康检查的判据回归 —— 重点是 **DIV-0014：端口有人应答 ≠ 我的 DSH 起来了**。
 *
 * ## 这个文件曾经断言过 bug 本身
 *
 * 上游版本的判据是「任何 HTTP 状态码都算存活」，当时的注释写得很清楚：
 * 「DSH 新版 webserver 对无 token 请求返回 401/302——任何 HTTP 响应都说明 webserver 存活」。
 * 这个推理本身没错，但它答的是「这个端口上有没有 webserver」，不是「**我的** DSH 起来了没有」。
 * 两者只有在「这台设备上只有一个 DSH」时才等价，而 DSHFusion 的设计前提恰恰不是。
 *
 * 于是旧的用例（`HTTP 401 视为 webserver 存活`、`HTTP 500 视为存活`…）全部把
 * 「端口被别人占用」判成了健康。真机上的后果：界面显示「DSH 就绪」，
 * 而首页「启动」按钮的条件是 `sandboxRunning && !dshReady`，被灰掉，用户无法重试。
 *
 * 现在判据是两步：端口是本实例的，**且**应答者接受了本实例的 launchToken。
 * 实测 DSH 的行为：无 token → 401、错 token → 401、对 token → 303。
 * 所以「状态码不是 401」即「应答者认识我的 token」。
 */
class HttpHealthCheckerTest {

    /** 起一个只回固定状态码的单请求 HTTP 服务，并记录收到的请求行，跑完即关。 */
    private suspend fun withResponse(
        statusLine: String,
        token: String? = "test-token",
        block: suspend (HttpHealthChecker, List<String>) -> Unit,
    ) {
        val server = ServerSocket(0)
        val requestLines = Collections.synchronizedList(mutableListOf<String>())
        val thread = Thread {
            try {
                // 循环 accept：健康检查会先做一次端口探测连接（无请求、直接关闭），
                // 再发起真正的 HTTP 请求——每个连接都要被消费。
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    try {
                        socket.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            var gotRequest = false
                            while (true) {
                                val line = reader.readLine() ?: break
                                gotRequest = true
                                if (line.isEmpty()) break
                                if (line.startsWith("GET ")) requestLines.add(line)
                            }
                            if (gotRequest) {
                                s.getOutputStream().write(
                                    "$statusLine\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                                )
                            }
                        }
                    } catch (_: Exception) {
                        // per-connection failure: keep serving
                    }
                }
            } catch (_: Exception) {
                // server closed
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            val checker = HttpHealthChecker(
                port = server.localPort,
                tokenProvider = { token },
                connectTimeoutMs = 1_000,
                readTimeoutMs = 2_000,
            )
            block(checker, requestLines)
        } finally {
            server.close()
            thread.join(2_000)
        }
    }

    // ── 身份被接受（token 对上了）→ 就绪 ──────────────────────────────────────

    @Test
    fun `token 被接受时的 303 判为就绪（DSH 对合法 token 的真实响应）`() = runBlocking {
        withResponse("HTTP/1.1 303 See Other") { checker, _ ->
            val health = checker.check()
            assertTrue(health.webUiReady)
            assertTrue(health.instanceVerified)
        }
    }

    @Test
    fun `token 被接受时的 200 判为就绪`() = runBlocking {
        withResponse("HTTP/1.1 200 OK") { checker, _ ->
            assertTrue(checker.check().webUiReady)
        }
    }

    @Test
    fun `token 被接受时的 404 判为就绪（SPA 路由由前端处理）`() = runBlocking {
        withResponse("HTTP/1.1 404 Not Found") { checker, _ ->
            assertTrue(checker.check().webUiReady)
        }
    }

    @Test
    fun `token 被接受时的 500 判为就绪（服务在处理请求即算活着）`() = runBlocking {
        withResponse("HTTP/1.1 500 Internal Server Error") { checker, _ ->
            assertTrue(checker.check().webUiReady)
        }
    }

    // ── 身份未被接受 → 不就绪（DIV-0014 的核心）──────────────────────────────

    @Test
    fun `401 说明应答者不认识本实例的 token，判为不就绪`() = runBlocking {
        withResponse("HTTP/1.1 401 Unauthorized") { checker, _ ->
            val health = checker.check()
            assertFalse(
                "401 正是「这个 token 我不认识」——即端口上应答的是别的实例，绝不能判就绪",
                health.webUiReady,
            )
            assertFalse(health.instanceVerified)
            // 端口本身是通的：这个区分对排查很重要（「没人应答」vs「有人应答但不是我」）。
            assertTrue(health.portOpen)
        }
    }

    @Test
    fun `探测请求确实带上了本实例的 token`() = runBlocking {
        withResponse("HTTP/1.1 303 See Other", token = "abc123") { checker, requestLines ->
            checker.check()
            val get = requestLines.firstOrNull { it.startsWith("GET ") }
            assertTrue("没有发出 HTTP 请求：$requestLines", get != null)
            assertTrue("请求里必须带 token=abc123，实际：$get", get!!.contains("token=abc123"))
        }
    }

    @Test
    fun `token 未知时不判就绪（无法证明应答者是谁）`() = runBlocking {
        withResponse("HTTP/1.1 200 OK", token = null) { checker, _ ->
            val health = checker.check()
            assertFalse(
                "拿不到 token 就没有身份判据，宁可继续等，也不要再产生一次无法归因的假成功",
                health.webUiReady,
            )
            assertFalse(health.instanceVerified)
            assertTrue(health.portOpen)
        }
    }

    // ── 端口层面 ─────────────────────────────────────────────────────────────

    @Test
    fun `端口未监听视为不健康`() = runBlocking {
        val checker = HttpHealthChecker(
            port = 1, // 无服务监听的端口
            connectTimeoutMs = 500,
            readTimeoutMs = 500,
        )
        val health = checker.check()
        assertFalse(health.webUiReady)
        assertFalse(health.portOpen)
    }

    @Test
    fun `端口未知（0，即尚未从 DSH 输出里学到）视为不健康`() = runBlocking {
        val checker = HttpHealthChecker(
            port = 0,
            tokenProvider = { "abc123" },
            connectTimeoutMs = 500,
            readTimeoutMs = 500,
        )
        val health = checker.check()
        assertFalse(health.webUiReady)
        assertFalse(health.portOpen)
        assertFalse(health.instanceVerified)
    }

    @Test
    fun `端口由 provider 动态提供，探测用的是最新值`() = runBlocking {
        // 模拟「先未知、后学到」：checker 是长生命周期对象，端口在 DSH 启动之后才出现。
        // 用固定端口构造的旧写法在 --port 0 下必然探错地方，所以这里钉住 provider 语义。
        var port = 0
        val server = ServerSocket(0)
        try {
            val checker = HttpHealthChecker(
                portProvider = { port },
                tokenProvider = { "abc123" },
                connectTimeoutMs = 500,
                readTimeoutMs = 500,
            )
            assertFalse("端口还是 0 时不该探到任何东西", checker.check().portOpen)

            port = server.localPort
            assertTrue("provider 返回真实端口后应能探到", checker.check().portOpen)
        } finally {
            server.close()
        }
    }
}
