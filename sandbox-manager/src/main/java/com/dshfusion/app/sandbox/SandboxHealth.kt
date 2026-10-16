package com.dshfusion.app.sandbox

/**
 * DSH 运行时健康快照。沙箱状态由 [SandboxManager.sandboxState] 单独暴露，
 * 此处仅描述 DSH 进程/端口/WebUI 状态。
 */
data class SandboxHealth(
    val dshProcessRunning: Boolean,
    val portOpen: Boolean,
    val webUiReady: Boolean,
    val lastError: String? = null,
    /**
     * 是否**证明了**应答者是本实例的 DSH（launchToken 被接受），而不是「某个 webserver 在应答」。
     *
     * 与 [webUiReady] 分开是为了让日志与诊断页能区分两种「没起来」：端口没人应答
     * （进程没起 / 端口没绑），与端口有人应答但不是我（**端口被别人占用**）——
     * 后者正是 DIV-0014 那个假「DSH 就绪」的成因，也是最容易被忽略的一种。
     */
    val instanceVerified: Boolean = false,
)

data class DshRuntimeStatus(
    val dshVersion: String?,
    val pluginApiVersion: String?,
    val baseUrl: String,
    val ready: Boolean,
)
