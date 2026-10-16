package com.dshfusion.app.di

import android.content.Context
import com.dshfusion.app.bridge.BridgeRouter
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
)
