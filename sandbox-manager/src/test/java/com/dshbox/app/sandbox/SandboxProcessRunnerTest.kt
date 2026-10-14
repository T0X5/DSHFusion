package com.dshbox.app.sandbox

import com.dshbox.app.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SandboxProcessRunnerTest {
    private val config = SandboxConfig(appFilesDir = File("/tmp/dshapp-test"))

    @Test
    fun prootDshCommandUsesAbsolutePathsAndDshStartScript() {
        val runner = SandboxProcessRunner(config)
        val command = runner.buildProotDshCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
        )
        assertEquals("/data/data/com.dshbox.app/files/runtime/runtime-current/proot", command[0])
        assertTrue(command.any { it.startsWith("--rootfs=") })
        assertTrue(command.any { it.endsWith(":/root/projects") })
        assertTrue(command.any { it.contains(Constants.DSH_START_SCRIPT) })
    }

    @Test
    fun prootSandboxCommandUsesKeepaliveMarker() {
        val runner = SandboxProcessRunner(config)
        val command = runner.buildProotSandboxCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
        )
        assertEquals("/data/data/com.dshbox.app/files/runtime/runtime-current/proot", command[0])
        assertTrue(command.any { it.startsWith("--rootfs=") })
        assertTrue(command.any { it.contains(Constants.SANDBOX_KEEPALIVE_MARKER) })
    }

    /**
     * 垫片注入：传入 [shimHostDir] 时必须同时出现 bind 与 `--import`，且 `--import`
     * 必须排在 DSH 入口脚本**之前**（否则 node 先执行入口，垫片来不及生效）。
     *
     * 这是「不修改 DSH 源码」这条约束的落地点——硬链接兼容全靠这个预加载。
     */
    @Test
    fun dshCommandInjectsLinkShimWhenProvided() {
        val runner = SandboxProcessRunner(config)
        val shimDir = "/data/data/com.dshbox.app/files/dshbox"
        val command = runner.buildProotDshCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
            shimHostDir = shimDir,
        )
        assertTrue(
            "应把垫片目录 bind 到 guest ${Constants.DSH_LINK_SHIM_GUEST_DIR}",
            command.any { it == "--bind=$shimDir:${Constants.DSH_LINK_SHIM_GUEST_DIR}" },
        )
        val shell = command.last()
        assertTrue(
            "应以 --import 预加载垫片",
            shell.contains("--import ${Constants.DSH_LINK_SHIM_GUEST_PATH}"),
        )
        assertTrue(
            "--import 必须排在 DSH 入口脚本之前",
            shell.indexOf("--import") < shell.indexOf(Constants.DSH_START_SCRIPT),
        )
    }

    /** 未提供垫片目录时不得注入：`--import` 指向不存在的文件会让 node 直接退出。 */
    @Test
    fun dshCommandOmitsShimWhenAbsent() {
        val runner = SandboxProcessRunner(config)
        val command = runner.buildProotDshCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
        )
        assertTrue("不应出现垫片绑定", command.none { it.contains(Constants.DSH_LINK_SHIM_GUEST_DIR) })
        assertTrue("不应出现 --import", !command.last().contains("--import"))
        // DSH 入口标记必须保持——stop() 靠它定位进程树
        assertTrue(command.any { it.contains(Constants.DSH_START_SCRIPT) })
    }

    /**
     * `--patch` 必须排在 `--host` / `--port` **之前**（DIV-0014）。
     *
     * dsh 启动器只解析它自己的旗标，**遇到第一个不认识的旗标就把剩下的原样转交给
     * 被启动的应用**；`--host` / `--port` 属于 web 应用，所以 `--patch` 一旦排在
     * 它们后面，就会被当成 web 应用自己的旗标 —— web 应用不认识它，打印
     * `error: unknown option '--patch'` 并退出。
     *
     * 2026-10-14 真机上 DSH 每次启动都死在这里（`process-dsh.log` 里连续 6 次）。
     *
     * 这条断言钉的是**位置**而不是**存在性**：上游原样导入的代码里 `--patch`
     * 一直都在，只是被后续改动挤到了后面。所以「命令里有没有 `--patch`」
     * 这种查法是抓不到这个回归的 —— 必须比下标。
     */
    @Test
    fun dshPatchOverlaysMustPrecedeAppFlags() {
        val runner = SandboxProcessRunner(config)
        val command = runner.buildProotDshCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
            dshPatchGuestPaths = listOf(
                "/root/projects/.dsh/official-plugin.patch.yml",
                "/root/projects/.dsh/safe-mode.patch.yml",
            ),
        )
        val shell = command.last()
        assertTrue(
            "两个层都要拼出来（--patch 可重复）",
            shell.contains("--patch /root/projects/.dsh/official-plugin.patch.yml") &&
                shell.contains("--patch /root/projects/.dsh/safe-mode.patch.yml"),
        )
        val patch = shell.indexOf("--patch")
        assertTrue("--patch 必须排在 --profile 之后（它是启动器的旗标）", shell.indexOf("--profile") < patch)
        assertTrue(
            "--patch 必须排在 --host 之前，否则会被转交给 web 应用并报 unknown option",
            patch < shell.indexOf("--host"),
        )
        assertTrue("--patch 必须排在 --port 之前", patch < shell.indexOf("--port"))
    }

    /** 没有层文件时不得拼出 `--patch` —— 传一个不存在的 patch 会让 dsh 启动直接失败。 */
    @Test
    fun dshCommandOmitsPatchWhenNoOverlays() {
        val runner = SandboxProcessRunner(config)
        val command = runner.buildProotDshCommand(
            prootBinary = "/data/data/com.dshbox.app/files/runtime/runtime-current/proot",
            rootfsDir = "/data/data/com.dshbox.app/files/runtime/runtime-current/debian",
            workspaceBind = "/data/data/com.dshbox.app/files/user-data",
        )
        assertTrue("无层时不应出现 --patch", !command.last().contains("--patch"))
    }
}
