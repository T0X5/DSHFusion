package com.dshfusion.app.sandbox

import com.dshfusion.app.common.AppError
import com.dshfusion.app.common.AppResult
import com.dshfusion.app.common.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * HTTP health checker for the local DSH WebUI.
 *
 * ## 判据：必须能证明「应答的是我自己的 DSH」（DIV-0014）
 *
 * 上游的判据是「端口能连上 + `GET /` 返回任意 HTTP 状态码」，注释里写得很清楚：
 * 「任何 HTTP 响应（含 401/302/404/5xx）都说明 webserver 存活」。**这个推理本身没错，
 * 但它答的是「3080 上有没有 webserver」，不是「我的 DSH 起来了没有」** ——
 * 两者只有在「这台设备上只有一个 DSH」时才等价。
 *
 * 真机上不等价：DSHFusion 与另一个 DSH 应用并存安装时，两个都想绑 3080，
 * 后启动的 `EADDRINUSE` 退出，而健康检查探到的是**另一个应用的 DSH**，
 * 于是界面显示「DSH 就绪」，用户拿到一个假成功、连「启动」按钮都被灰掉。
 *
 * 现在的判据分两步，两步都必须过：
 *
 * 1. **端口**：探的是本实例学到的端口（内核分配的），不是固定值。
 * 2. **身份**：带**本实例的 launchToken** 去请求。
 *    DSH 对未知/缺失 token 一律回 `401`；token 被接受时回 `303`（重定向到应用路由）
 *    —— 实测如此（无 token → 401、错 token → 401、对 token → 303）。
 *    所以「状态码不是 401」就等于「应答者认识我的 token」，即它是我自己的 DSH。
 *
 * 拿不到 token 时**判为未就绪**，而不是退回「任何响应都算活」：宁可让上层继续等
 * （启动有 120s 窗口），也不要再产生一次无法归因的假成功。
 *
 * ## ⚠️ [path] 必须是 DSH 打印的那个路径（实测约束，不是随便写的）
 *
 * 判据「状态码 ≠ 401」**只在探测的是 DSH 打印出来的那个 URL 时成立**。实测
 * （DSH 0.2.0-rc.2，本机）：
 *
 * ```
 * GET /?token=<正确>            → 303   ← 判为「是我的」
 * GET /?token=<错误>            → 401
 * GET /                            → 401
 * GET /api/health?token=<正确>  → 401   ← ⚠️ 换路径就变 401
 * GET /api/health               → 401
 * ```
 *
 * 也就是说：**换个看起来更"正规"的健康检查路径，会让这个判据永久失败**，
 * 而那会退化成上游踩过的另一个坑 —— 一直判未就绪 → 120s 超时 → 销毁进程
 * （上游最初的写法只认 200..299，正是被 DSH 的 401 打中才放宽的）。
 * 上游放宽是对的，只是放宽成了「任何响应」，于是又丢了身份判据；
 * 现在的做法是**把 token 带上**，而不是再放宽一次。
 *
 * `SandboxConfig.healthPath` 的默认值因此必须是 `"/"`。这条约束由
 * `tools/verify-phase0.sh` 的 5g 节钉住（改掉会被拦下，而不是等到真机上超时）。
 * DSH 若改了 URL 形态，`DshWebLaunchParserTest` 与这条断言会先报出来。
 */
class HttpHealthChecker(
    private val host: String = Constants.DSH_DEFAULT_HOST,
    /**
     * 当前实例的端口。用 provider 而不是构造期取值：端口在 DSH 打印 URL 之后才存在，
     * 而 checker 是长生命周期对象。
     */
    private val portProvider: () -> Int,
    /** 当前实例的 launchToken；未知时返回 null（此时无法核验身份，判未就绪）。 */
    private val tokenProvider: () -> String? = { null },
    private val path: String = "/",
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 3_000,
) : SandboxHealthChecker {

    /** 固定端口的便捷构造（宿主侧测试 / 特殊部署）。 */
    constructor(
        port: Int,
        host: String = Constants.DSH_DEFAULT_HOST,
        tokenProvider: () -> String? = { null },
        path: String = "/",
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 3_000,
    ) : this(host, { port }, tokenProvider, path, connectTimeoutMs, readTimeoutMs)

    override suspend fun check(): SandboxHealth = withContext(Dispatchers.IO) {
        val port = portProvider()
        // 端口未知（DSH 还没打印 URL）或为 0：没有可探的目标，如实报未就绪。
        if (port <= 0) {
            return@withContext SandboxHealth(
                dshProcessRunning = false,
                portOpen = false,
                webUiReady = false,
                instanceVerified = false,
            )
        }
        val portOpen = isPortOpen(host, port, connectTimeoutMs)
        if (!portOpen) {
            return@withContext SandboxHealth(
                dshProcessRunning = false,
                portOpen = false,
                webUiReady = false,
                instanceVerified = false,
            )
        }
        val token = tokenProvider()
        if (token.isNullOrEmpty()) {
            // 端口通、但无法证明是谁在应答 —— 不判就绪。
            return@withContext SandboxHealth(
                dshProcessRunning = true,
                portOpen = true,
                webUiReady = false,
                instanceVerified = false,
                lastError = "launchToken not known yet; cannot confirm the responder is this instance",
            )
        }
        val code = httpProbe(port, token)
        val verified = code != null && code != HTTP_UNAUTHORIZED
        SandboxHealth(
            dshProcessRunning = true,
            portOpen = true,
            webUiReady = verified,
            instanceVerified = verified,
            lastError = if (verified) {
                null
            } else {
                "responder on port $port rejected this instance's token (HTTP ${code ?: "no response"})"
            },
        )
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }

    /** 返回 HTTP 状态码；连不上/超时返回 null。 */
    private fun httpProbe(port: Int, token: String): Int? =
        try {
            val connection = URL("http://$host:$port$path?token=$token").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            connection.disconnect()
            // 不再「任何状态码都算活」：401 是 DSH 明确表达的「这个 token 我不认识」，
            // 也就是「应答者不是我」——必须判不健康。其余状态码（200/303/404/5xx…）
            // 都发生在 token 已被接受之后，说明是本实例在处理请求。
            code
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}

fun SandboxHealth.toAppResult(): AppResult<SandboxHealth> =
    if (webUiReady) AppResult.Success(this)
    else AppResult.Failure(AppError("DSH_NOT_READY", "DSH WebUI is not ready", recoverable = true))
