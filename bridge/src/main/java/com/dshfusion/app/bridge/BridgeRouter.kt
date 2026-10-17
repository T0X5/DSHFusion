package com.dshfusion.app.bridge

import com.dshfusion.app.bridge.api.BridgeApi
import com.dshfusion.app.bridge.security.BridgeCapability
import com.dshfusion.app.bridge.security.BridgePolicy
import com.dshfusion.app.bridge.security.CapabilityAuthorizer
import com.dshfusion.app.bridge.security.CapabilityGate
import com.dshfusion.app.bridge.security.OriginVerifier
import com.dshfusion.app.bridge.security.TrustLevel

/**
 * Entry point for WebView JS bridge calls. Every capability call must pass
 * through this router. The router does not trust localhost by itself.
 *
 * ## 两道门，都要过（阶段 3.4）
 *
 * 1. **来源门**（[BridgePolicy]，继承自骨干）：来源必须是受信任的 DSH WebUI。
 * 2. **档位门**（[CapabilityGate]，阶段 3.2/3.4）：这个能力的档位必须是开着的。
 *
 * 两道门是**相加**关系，不是替代关系 —— 来源门不检查档位，档位门不检查来源。
 * 任何一道拒绝就是拒绝；是 `AND` 而不是 `OR`，所以不存在「过了新的就绕过旧的」。
 *
 * ## 为什么返回值是这里自己的类型，而不是 [CapabilityGate.Decision]
 *
 * 因为路由器要表达**两个来源的拒绝**（来源门与档位门），而
 * [CapabilityGate.Denial] 是闸门私有的、刻意不给外部构造 —— 那正是它
 * 「拒绝时什么都给不出去」的保证。所以路由器用 [Authorization] 把两边的结论
 * **并起来**，而不是伪造一个 Denial 去冒充闸门的判断。
 */
class BridgeRouter(
    private val delegate: BridgeApi,
    private val expectedDshToken: String,
) {
    private val sessionCapabilities = mutableSetOf<BridgeCapability>()
    private var userAuthorizedHighRisk = false

    /** 档位门的授权状态。默认 = 全新安装（只开 T0/T1）。 */
    private var grants: CapabilityGate.Grants = CapabilityGate.Grants.defaults()

    fun classify(url: String): TrustLevel = OriginVerifier.classify(url)

    fun buildPolicy(url: String): BridgePolicy {
        val trust = classify(url)
        return BridgePolicy(
            trustLevel = trust,
            grantedCapabilities = sessionCapabilities.toSet(),
            userAuthorizedHighRisk = userAuthorizedHighRisk,
        )
    }

    fun grant(capabilities: Set<BridgeCapability>, userAuthorizedHighRisk: Boolean) {
        sessionCapabilities += capabilities
        this.userAuthorizedHighRisk = userAuthorizedHighRisk
    }

    fun revokeAll() {
        sessionCapabilities.clear()
        userAuthorizedHighRisk = false
    }

    /**
     * 换一份档位授权状态（由设置在用户操作后调用）。
     *
     * ⚠️ **不存在 `grantAll()` 之类的捷径** —— 阶段 3.2 判定过那是缺陷形态：
     * 用户为了「能写文件」打开的那一个开关，不该连「能跑任意命令」一起打开。
     */
    fun setGrants(grants: CapabilityGate.Grants) {
        this.grants = grants
    }

    /** 当前的档位授权状态（只读用）。 */
    fun grants(): CapabilityGate.Grants = grants

    /**
     * 一次能力调用的裁决。**这是唯一的入口** —— 调用方拿到 [Authorization.Allowed]
     * 之前不许动手；而那个分支里只有 `tier`，没有任何可用于执行的参数。
     */
    fun authorize(url: String, capability: BridgeCapability): Authorization {
        // ① 来源门 —— 这一句需要 android.net.Uri，所以它只能留在这里（无法本机单测）。
        val evaluated = buildPolicy(url).evaluate(capability)
        // ② 档位门与两道门的合成 —— 在 CapabilityAuthorizer 里，纯 Java、本机可测。
        val outcome = CapabilityAuthorizer.authorize(
            evaluated.allowed,
            capability.name,
            grants,
        )
        return if (outcome.allowed()) {
            Authorization.Allowed(outcome.tier())
        } else {
            Authorization.Denied(outcome.code(), evaluated.reason)
        }
    }

    val api: BridgeApi = delegate

    /** 路由器层面的裁决。**不含任何可用于执行的参数** —— 与闸门的保证一致。 */
    sealed interface Authorization {
        data class Allowed(val tier: CapabilityGate.Tier?) : Authorization

        data class Denied(
            val code: CapabilityGate.DenyCode,
            val detail: String?,
        ) : Authorization
    }
}
