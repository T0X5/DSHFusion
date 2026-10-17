package com.dshfusion.app.bridge.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dshfusion.app.bridge.security.CapabilityGate.Channel;
import com.dshfusion.app.bridge.security.CapabilityGate.Decision;
import com.dshfusion.app.bridge.security.CapabilityGate.DenyCode;
import com.dshfusion.app.bridge.security.CapabilityGate.Grants;
import com.dshfusion.app.bridge.security.CapabilityGate.Permit;
import com.dshfusion.app.bridge.security.CapabilityGate.Request;
import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/**
 * {@link CapabilityGate} 的行为断言 —— 这条路径决定「能不能碰手机」，
 * 所以每一条都必须能在 CI 里真跑（本机另有一份 {@code tools/check-capability-gate.java}）。
 *
 * <h2>为什么是 Java 而不是 Kotlin</h2>
 *
 * 与 {@code HostRestoreMapping}（阶段 2.4b-①）同一个取舍：本机有 JDK 17、没有 kotlinc，
 * 写成 Kotlin 就只能等 CI。**会碰设备的判定逻辑应当能在本机两秒跑完。**
 *
 * <h2>一个关于「因为正确的理由通过」的注意</h2>
 *
 * {@link #bridgeCredentialIsDeniedEvenWhenListedAsOpen()} 是 T4 那条**唯一有决定性**的用例：
 * 若只断言「默认配置下 T4 被拒」，那个断言会**因为错的原因**通过 ——
 * 默认配置里 T4 本来就不在已开启集合里，于是它返回的是 {@code TIER_DISABLED}
 * 而不是 {@code CREDENTIAL_NEVER_EXPOSED}。**这个区别是实测出来的**
 * （把 T4 的提前拒绝删掉后，前一条断言仍然「通过」，后一条才红）。
 */
public final class CapabilityGateTest {

    @Test
    public void defaultsOnlyOpenT0AndT1() {
        CapabilityGate gate = new CapabilityGate(Grants.defaults());

        assertTrue(gate.decide(Request.of(Tier.T0_UNCONDITIONAL)).allowed());
        assertTrue(gate.decide(Request.of(Tier.T1_APP_PERMISSION)).allowed());
        assertCode(DenyCode.TIER_DISABLED, gate.decide(Request.accessibility(true)));
        assertCode(DenyCode.TIER_DISABLED, gate.decide(Request.privileged(Channel.SHIZUKU)));
    }

    /**
     * 阶段 3.4 边界 1：应用侧开了 T2 **不算**，系统里的无障碍服务也要开。
     *
     * <p>这一条挡的是「界面上打了个勾就以为能读屏」那类误报。
     */
    @Test
    public void accessibilityNeedsTheSystemServiceNotJustTheGrant() {
        CapabilityGate gate = new CapabilityGate(
                Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T2_ACCESSIBILITY),
                        EnumSet.noneOf(Channel.class)));

        assertCode(DenyCode.ACCESSIBILITY_SERVICE_OFF, gate.decide(Request.accessibility(false)));
        assertTrue(gate.decide(Request.accessibility(true)).allowed());
    }

    /**
     * 阶段 3.4 边界 3：T3 按通道分级，**不许降级到更弱的通道**。
     *
     * <p>只授了 Shizuku 时请求 root —— 既不能放行，也不能「用 Shizuku 跑一遍假装成功」。
     */
    @Test
    public void privilegedRequiresTheExactGrantedChannel() {
        CapabilityGate gate = new CapabilityGate(
                Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T3_PRIVILEGED),
                        EnumSet.of(Channel.SHIZUKU)));

        assertTrue(gate.decide(Request.privileged(Channel.SHIZUKU)).allowed());
        assertCode(DenyCode.CHANNEL_NOT_GRANTED, gate.decide(Request.privileged(Channel.ROOT)));
        assertCode(DenyCode.CHANNEL_NOT_GRANTED, gate.decide(Request.privileged(Channel.ADB)));
    }

    /** 没有默认通道：「不知道用哪个」不等于「随便挑一个」。 */
    @Test
    public void privilegedWithoutAChannelIsDeniedNotGuessed() {
        CapabilityGate gate = new CapabilityGate(
                Grants.of(EnumSet.of(Tier.T3_PRIVILEGED), EnumSet.allOf(Channel.class)));

        assertCode(DenyCode.CHANNEL_NOT_GRANTED, gate.decide(Request.privileged(null)));
    }

    /**
     * 阶段 3.4 边界 2：禁止以宿主自身为目标 —— 否则 agent 能拿宿主应用驱动它自己
     * （权限自举回路）。唯一合法形状是「把宿主叫回前台」。
     */
    @Test
    public void selfTargetIsDeniedUnlessBringingHostToFront() {
        CapabilityGate gate = new CapabilityGate(
                Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T1_APP_PERMISSION,
                        Tier.T2_ACCESSIBILITY, Tier.T3_PRIVILEGED), EnumSet.allOf(Channel.class)));

        assertCode(DenyCode.SELF_TARGET_FORBIDDEN,
                gate.decide(Request.selfTarget(Tier.T1_APP_PERMISSION, null, false)));
        assertTrue(gate.decide(Request.selfTarget(Tier.T1_APP_PERMISSION, null, true)).allowed());
        assertCode(DenyCode.SELF_TARGET_FORBIDDEN,
                gate.decide(Request.selfTarget(Tier.T3_PRIVILEGED, Channel.ROOT, false)));
    }

    /**
     * 阶段 3.4 §三：授权状态读不到时 **fail-closed** —— 连 T0 都不放行。
     *
     * <p>若把「状态不可用」的判断挪到档位判断之后，这两条会变成 {@code TIER_DISABLED}
     * 「通过」—— 而这正是最危险的错法：**表现像拒绝，理由却是错的**。
     */
    @Test
    public void unavailableGrantsDenyEverythingIncludingT0() {
        CapabilityGate gate = new CapabilityGate(Grants.unavailable());

        assertCode(DenyCode.AUTHORIZATION_UNAVAILABLE,
                gate.decide(Request.of(Tier.T0_UNCONDITIONAL)));
        assertCode(DenyCode.AUTHORIZATION_UNAVAILABLE,
                gate.decide(Request.privileged(Channel.ROOT)));
    }

    /**
     * T4 **不可授予**：即使有人把 T4 塞进「已开启档位」，也仍然拒绝。
     *
     * <p>这一条是 T4 的唯一决定性断言，理由见类注释。
     */
    @Test
    public void bridgeCredentialIsDeniedEvenWhenListedAsOpen() {
        CapabilityGate gate = new CapabilityGate(
                Grants.of(EnumSet.allOf(Tier.class), EnumSet.allOf(Channel.class)));

        assertCode(DenyCode.CREDENTIAL_NEVER_EXPOSED,
                gate.decide(Request.of(Tier.T4_BRIDGE_CREDENTIAL)));
    }

    /**
     * 结构性断言：{@link Permit} 只能由闸门产生 —— 于是「被拒时什么都给不出去」
     * 不是一个要记得去写的判断，而是**类型上做不到**。
     */
    @Test
    public void permitCannotBeConstructedOutsideTheGate() {
        for (Constructor<?> ctor : Permit.class.getDeclaredConstructors()) {
            assertFalse("Permit 的构造器不该是 public",
                    Modifier.isPublic(ctor.getModifiers()));
        }
    }

    /** {@link CapabilityGate.Denial} 只暴露它的码 —— 没有可转交给下游的字段。 */
    @Test
    public void denialExposesNothingButItsCode() {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : CapabilityGate.Denial.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                names.add(method.getName());
            }
        }
        assertEquals(new LinkedHashSet<>(java.util.Arrays.asList("allowed", "code", "toString")),
                names);
    }

    private static void assertCode(DenyCode expected, Decision decision) {
        assertFalse("期望拒绝 " + expected + "，却放行了", decision.allowed());
        assertEquals(expected, ((CapabilityGate.Denial) decision).code());
    }
}
