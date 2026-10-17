package com.dshfusion.app.bridge.security

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CapabilityTierMap] 与**真实枚举**的对账。
 *
 * 这个测试只能写成 Kotlin —— 它要枚举 `BridgeCapability` 的真实取值，
 * 而 `CapabilityTierMap` 刻意按名字（String）匹配，以便在本机真跑（本机没有 kotlinc）。
 * 于是「**每一个真实能力都有映射**」这件事只能在 CI 里证。
 *
 * 它挡的是一个很具体的错：有人加了 `BridgeCapability.NEW_THING`，
 * 却没在映射表里加对应项 —— 那时闸门会对它返回 `null`，
 * 而调用方必须把 `null` 当成拒绝。**测试在这里红，比在真机上表现为
 * 「某个功能突然不能用了」要好得多。**
 */
class CapabilityTierMapContractTest {

    @Test
    fun everyBridgeCapabilityHasATier() {
        for (capability in BridgeCapability.values()) {
            assertNotNull(
                "BridgeCapability.${capability.name} 没有映射 —— " +
                    "新增能力必须同时映射档位，否则闸门会拒绝它（fail-closed），" +
                    "见 CapabilityTierMap 的类注释",
                CapabilityTierMap.tierFor(capability.name),
            )
        }
    }

    /**
     * 反向：映射表里的每一个名字，都必须是**真实存在**的枚举值。
     *
     * 这条挡的是「枚举改名后忘了同步映射表」：旧名字留在表里永远不会被匹配到，
     * 而那会让「表里有这一项」变成一句假话 —— 下一个读表的人会以为它管着什么。
     */
    @Test
    fun theTableHasNoStaleNames() {
        val real = BridgeCapability.values().map { it.name }.toSet()
        val stale = CapabilityTierMap.mappedNames().filter { it !in real }
        assertTrue("映射表里有不存在的枚举名：$stale —— 枚举改名后忘了同步", stale.isEmpty())
    }
}
