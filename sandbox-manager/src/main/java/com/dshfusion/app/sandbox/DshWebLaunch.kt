package com.dshfusion.app.sandbox

/**
 * DSH 实例的启动信息，**由 DSH 自己打印的那行输出解析而来**。
 *
 * 为什么端口要从输出里学，而不是由 app 指定：见 [DshWebLaunchParser] 的说明。
 *
 * @param port DSH webserver 实际绑定的端口（内核分配的真实端口，不是请求值）。
 * @param token 进程级 launchToken，仅存在于进程内存、不落盘；`null` 表示该行没带 token。
 */
data class DshWebLaunch(
    val port: Int,
    val token: String?,
)

/**
 * 解析 `dsh web` 启动时打印的那一行：
 *
 * ```
 * dsh web: http://127.0.0.1:42797/?token=8OMeQzE98nRyjCPBhqSu4_sVBhKFQdbq8mCP-9qf5u4
 * ```
 *
 * ## 为什么这件事要独立成一个纯函数
 *
 * 它是「app 怎么知道自己的 DSH 在哪个端口」的唯一入口。这条链路上出过错 ——
 * 端口被写死成 `3080` 之后，只要手机上还有另一个 DSH 应用，就会同时出现两个后果：
 * 新实例 `EADDRINUSE` 起不来，而健康检查去探 3080 时**探到的是别人**，
 * 于是界面显示「DSH 就绪」。把它抽成纯函数，是为了能用单测钉住
 * 「端口是从这行里读出来的」，而不是某个常量。
 *
 * ## 解析规则（宽松但不猜）
 *
 * - 认 `dsh web: http://` 这个标记（上游 `dsh-web-app` 打印 URL 时用的就是它）。
 * - 主机名里不允许出现 `:`，端口必须是 1..65535 的十进制数，否则判为解析失败
 *   —— **不返回「默认端口」**：宁可让上层继续等，也不要拿一个猜出来的端口去探测，
 *   那正是这条 bug 的成因。
 * - `token=` 取到 `&` 或行尾为止；缺失时 `token = null`，但端口仍然有效。
 * - 容忍行尾的 ` (LAN: http://…)` 后缀（DSH 在有局域网地址时会追加）。
 */
object DshWebLaunchParser {

    private const val MARKER = "dsh web: http"

    /** `dsh web: http://<host>:<port>/<path>`，host 内不含 `:` 与 `/`。 */
    private val URL_RE = Regex("""dsh web: http://([^:/\s]+):(\d+)(/\S*)?""")

    fun parse(line: String): DshWebLaunch? {
        if (!line.contains(MARKER)) return null
        val match = URL_RE.find(line) ?: return null
        val port = match.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val path = match.groupValues.getOrNull(3).orEmpty()
        return DshWebLaunch(port = port, token = tokenFrom(path))
    }

    /** 从 `/…?token=xxx` 里取 token；取不到返回 null。 */
    private fun tokenFrom(path: String): String? {
        val q = path.indexOf('?')
        if (q < 0) return null
        return path.substring(q + 1)
            .split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.removePrefix("token=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
