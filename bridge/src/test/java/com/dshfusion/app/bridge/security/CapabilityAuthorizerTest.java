package com.dshfusion.app.bridge.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dshfusion.app.bridge.security.CapabilityAuthorizer.Outcome;
import com.dshfusion.app.bridge.security.CapabilityGate.Channel;
import com.dshfusion.app.bridge.security.CapabilityGate.DenyCode;
import com.dshfusion.app.bridge.security.CapabilityGate.Grants;
import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.util.EnumSet;

import org.junit.Test;

/**
 * 两道门合成的裁决 —— 本机可跑（纯 Java）。
 *
 * <p>覆盖的是「**来源门与档位门必须 AND**」以及「未知能力默认拒绝」。
 * 这两条都挡过真实的错法：前者挡「加了新门就以为旧门可以不要」，
 * 后者挡「新增能力忘了映射，于是没人管它」。
 */
public final class CapabilityAuthorizerTest {

    @Test
    public void untrustedOriginDeniesEvenATier0Capability() {
        Outcome outcome = CapabilityAuthorizer.authorize(
                false, "WORKSPACE", Grants.of(EnumSet.allOf(Tier.class), EnumSet.allOf(Channel.class)));
        assertFalse("来源不受信任时，连 T0 也不许过", outcome.allowed());
        assertEquals(DenyCode.ORIGIN_NOT_TRUSTED, outcome.code());
        assertNull(outcome.tier());
    }

    @Test
    public void trustedOriginStillNeedsTheTierOpen() {
        // 只开 T0：T1 的能力必须被档位门拦下 —— 证明两道门不是同一件事。
        Grants onlyT0 = Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL), EnumSet.noneOf(Channel.class));

        assertTrue("T0 能力在默认状态下应当放行",
                CapabilityAuthorizer.authorize(true, "WORKSPACE", onlyT0).allowed());
        Outcome denied = CapabilityAuthorizer.authorize(true, "ANDROID_NOTIFICATION", onlyT0);
        assertFalse("T1 能力在只开 T0 时必须被拒", denied.allowed());
        assertEquals(DenyCode.TIER_DISABLED, denied.code());
    }

    @Test
    public void unknownCapabilityIsDeniedNotIgnored() {
        Outcome outcome = CapabilityAuthorizer.authorize(
                true, "A_CAPABILITY_NOBODY_MAPPED", Grants.of(EnumSet.allOf(Tier.class), EnumSet.allOf(Channel.class)));
        assertFalse("映射不到档位必须拒绝", outcome.allowed());
        assertEquals(DenyCode.UNKNOWN_CAPABILITY, outcome.code());
    }

    /**
     * COMMAND 在**全部档位都开着**的情况下仍然被拒 —— 因为它需要显式通道，
     * 而这条路径没有通道概念。
     *
     * <p>这是 Phase 3.2 判定「不许有一个通道就行」的直接后果：
     * 想跑命令，必须说清楚用哪条通道。
     */
    @Test
    public void commandIsDeniedWithoutAnExplicitChannelEvenWhenEverythingIsOpen() {
        Outcome outcome = CapabilityAuthorizer.authorize(
                true, "COMMAND", Grants.of(EnumSet.allOf(Tier.class), EnumSet.allOf(Channel.class)));
        assertFalse("没有显式通道时 COMMAND 必须被拒", outcome.allowed());
        assertEquals(DenyCode.CHANNEL_NOT_GRANTED, outcome.code());
    }

    /** T4 不可授予：即使全部档位都开着，凭据也不可能被放出去。 */
    @Test
    public void bridgeCredentialNeverComesBackAllowed() {
        Outcome outcome = CapabilityAuthorizer.authorize(
                true, "T4_BRIDGE_CREDENTIAL", Grants.of(EnumSet.allOf(Tier.class), EnumSet.allOf(Channel.class)));
        // 注意：T4 这个名字**不在**映射表里，所以这里拿到的是 UNKNOWN_CAPABILITY。
        // 两条路都通向「拒绝」，但理由不同 —— 断言理由，否则这条会因为错的原因通过。
        assertFalse(outcome.allowed());
        assertEquals(DenyCode.UNKNOWN_CAPABILITY, outcome.code());
    }

    /** 授权状态不可用时 fail-closed —— 即使来源受信任、能力已映射。 */
    @Test
    public void unavailableGrantsFailClosed() {
        Outcome outcome = CapabilityAuthorizer.authorize(true, "WORKSPACE", Grants.unavailable());
        assertFalse(outcome.allowed());
        assertEquals(DenyCode.AUTHORIZATION_UNAVAILABLE, outcome.code());
    }
}
