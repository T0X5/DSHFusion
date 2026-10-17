package com.dshfusion.app.di

import android.content.Context
import com.dshfusion.app.bridge.BridgeRouter
import com.dshfusion.app.bridge.DeviceCapabilityWiring
import com.dshfusion.app.runtime.OnlineRuntimeImportManager
import com.dshfusion.app.runtime.RuntimeUpdateManager
import com.dshfusion.app.sandbox.SandboxConfig
import com.dshfusion.app.sandbox.SandboxManager
import com.dshfusion.terminal.DshTerminalManager

/**
 * Minimal manual DI container. Replaced by Hilt only if the project grows.
 */
class AppContainer(
    val appContext: Context,
    val sandboxConfig: SandboxConfig,
    val sandboxManager: SandboxManager,
    val bridgeRouter: BridgeRouter,
    val dshTerminalManager: DshTerminalManager,
    val runtimeUpdateManager: RuntimeUpdateManager,
    val onlineRuntimeImportManager: OnlineRuntimeImportManager,
    /**
     * 设备能力层的接线结果。**为 {@code null} 表示这套能力在这台设备上就没有装起来**
     * （典型原因：桥的 token 写不进去）。
     *
     * 它是可空的，而这个可空**本身**就是设计：拿不到 token 时我们宁可没有这个对象，
     * 也不愿意有一个「不带鉴权的服务」在那儿等人打开开关。
     */
    val deviceCapability: DeviceCapabilityWiring.Wired?,
)
