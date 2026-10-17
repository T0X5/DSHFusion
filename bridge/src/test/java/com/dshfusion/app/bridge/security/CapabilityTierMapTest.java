package com.dshfusion.app.bridge.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/**
 * {@link CapabilityTierMap} 的断言 —— 本机可跑（纯 Java，无需 kotlinc）。
 *
 * <p>与 {@link CapabilityTierMapContractTest}（Kotlin）分工：
 * 那个证「**每一个真实的枚举值**都有映射」；
 * 这个证「**映射到哪一档**是对的，且未知一律拒绝」。
 * 只做前者会允许「全映射到 T0」，只做后者会允许「新增了能力却没映射」。
 */
public final class CapabilityTierMapTest {

    @Test
    public void workspaceIsUnconditionalSection() {
        assertEquals(Tier.T0_UNCONDITIONAL, CapabilityTierMap.tierFor("WORKSPACE"));
    }

    /** Android 侧的几项都是应用层可达 —— 最坏后果是打扰用户。 */
    @Test
    public void androidCapabilitiesAreAppLevel() {
        for (String name : new String[] {
                "ANDROID_NOTIFICATION", "ANDROID_CLIPBOARD",
                "ANDROID_FILE_PICKER", "ANDROID_SHARE"}) {
            assertEquals(name, Tier.T1_APP_PERMISSION, CapabilityTierMap.tierFor(name));
        }
    }

    /**
     * {@code COMMAND} / {@code PROCESS} 必须是 T3 —— 于是它们在本模型里**默认被拒**。
     *
     * <p>这一条挡的是「把提权降成应用层」那类悄无声息的放宽。
     */
    @Test
    public void privilegedCapabilitiesAreTier3() {
        assertEquals(Tier.T3_PRIVILEGED, CapabilityTierMap.tierFor("COMMAND"));
        assertEquals(Tier.T3_PRIVILEGED, CapabilityTierMap.tierFor("PROCESS"));
    }

    /**
     * {@code FILESYSTEM_WRITE} 不许被降到 T0。
     *
     * <p>旧的 {@code BridgePolicy} 把它列为 HIGH_RISK；新映射若把它降到 T0，
     * 就等于**在旧门之上开了一个更大的口子**。它是不是 T1 可以讨论，
     * 「不许是 T0」不可以。
     */
    @Test
    public void fileSystemWriteIsNotUnconditional() {
        Tier tier = CapabilityTierMap.tierFor("FILESYSTEM_WRITE");
        assertNotNull("FILESYSTEM_WRITE 必须有映射", tier);
        assertTrue("FILESYSTEM_WRITE 不许是 T0（那等于无声放宽）",
                tier != Tier.T0_UNCONDITIONAL);
    }

    /** 未知能力返回 {@code null} —— 而 {@code null} 必须被当成拒绝。 */
    @Test
    public void unknownCapabilityHasNoTier() {
        assertNull(CapabilityTierMap.tierFor("SOMETHING_NEW"));
        assertNull(CapabilityTierMap.tierFor(null));
        assertNull(CapabilityTierMap.tierFor("WORKSPACE "));  // 尾随空格也不算
    }

    /**
     * 表里不许**悄悄少一项** —— 删掉一项会把它变成「未知 → 拒绝」，
     * 而那会表现成「昨晚还能用，今天突然不行了」，极难查。
     *
     * <p>注意这条断言钉的是**计数**，所以它必须与上面的逐项断言同时存在：
     * 只钉计数会允许「删一项、加一项到别处」。
     */
    @Test
    public void theTableDoesNotSilentlyShrink() {
        Set<String> expected = new LinkedHashSet<>(java.util.Arrays.asList(
                "WORKSPACE", "FILESYSTEM_READ", "FILESYSTEM_WRITE",
                "COMMAND", "PROCESS",
                "ANDROID_NOTIFICATION", "ANDROID_CLIPBOARD",
                "ANDROID_FILE_PICKER", "ANDROID_SHARE"));
        assertEquals(expected, CapabilityTierMap.mappedNames());
    }
}
