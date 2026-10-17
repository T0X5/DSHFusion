package com.dshfusion.app.bridge.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@code BridgeCapability} 映射到 {@link CapabilityGate.Tier}。
 *
 * <h2>为什么按名字（String）而不是按枚举</h2>
 *
 * {@code BridgeCapability} 是 Kotlin 枚举。若本类直接引用它，就编不成纯 Java，
 * 也就**没法在本机跑**（本机没有 kotlinc，见 docs/INCIDENTS.md 第 21 条）。
 * 而这恰好是「允许 agent 能不能跑命令」的那条判定 —— 让它只能等 CI 太贵。
 *
 * <p>代价是丢掉了编译期类型安全。补偿方式有两条，都在测试里：
 * <ol>
 *   <li>{@code CapabilityTierMapContractTest}（Kotlin）枚举**真实的**枚举值，
 *       断言每一个都有映射 —— 将来有人加了新能力却忘了映射，这条会红；</li>
 *   <li>本类对**未知名字返回 {@code null}**，而调用方必须把 {@code null} 当成拒绝。</li>
 * </ol>
 *
 * <h2>未知一律拒绝，这条是刻意的</h2>
 *
 * 「忘了映射」的默认结果必须是**拒绝**，不能是「放行」或「抛异常被吞掉」。
 * 这与阶段 3.4 §三 的 fail-closed 是同一条规矩。
 *
 * <h2>COMMAND 与 PROCESS 映射到 T3 —— 于是它们在本模型里**默认被拒**</h2>
 *
 * T3 要求**显式通道**（root / Shizuku / ADB），而 {@code BridgeCapability} 没有通道概念。
 * 所以经这条路径的 {@code COMMAND} / {@code PROCESS} 会拿到
 * {@link CapabilityGate.DenyCode#CHANNEL_NOT_GRANTED}。
 *
 * <p>**这是有意的、也是更严的。** `BridgePolicy` 原来用一个
 * {@code userAuthorizedHighRisk} 布尔同时罩住 COMMAND / FILESYSTEM_WRITE / PROCESS ——
 * 那正是阶段 3.2 判定为缺陷的形态。这里不提供那个「一键全开」的捷径：
 * 想跑命令，就必须说清楚**用哪条通道**。
 */
public final class CapabilityTierMap {

    /** 能力名 → 档位。**只增不删**；删掉一项等于悄悄放宽一条。 */
    private static final Map<String, CapabilityGate.Tier> TIERS;

    static {
        Map<String, CapabilityGate.Tier> map = new LinkedHashMap<>();

        // 工作区：只影响它自己，不碰设备。
        map.put("WORKSPACE", CapabilityGate.Tier.T0_UNCONDITIONAL);

        // 文件读写：沙箱内。注意 FILESYSTEM_WRITE 在本层是 T1，
        // 但旧的 BridgePolicy 仍把它列为 HIGH_RISK —— **两道门都要过**，没有放宽。
        map.put("FILESYSTEM_READ", CapabilityGate.Tier.T1_APP_PERMISSION);
        map.put("FILESYSTEM_WRITE", CapabilityGate.Tier.T1_APP_PERMISSION);

        // 提权：必须有显式通道，否则闸门不给过。
        map.put("COMMAND", CapabilityGate.Tier.T3_PRIVILEGED);
        map.put("PROCESS", CapabilityGate.Tier.T3_PRIVILEGED);

        // Android 侧、应用层可达。
        map.put("ANDROID_NOTIFICATION", CapabilityGate.Tier.T1_APP_PERMISSION);
        map.put("ANDROID_CLIPBOARD", CapabilityGate.Tier.T1_APP_PERMISSION);
        map.put("ANDROID_FILE_PICKER", CapabilityGate.Tier.T1_APP_PERMISSION);
        map.put("ANDROID_SHARE", CapabilityGate.Tier.T1_APP_PERMISSION);

        TIERS = Collections.unmodifiableMap(map);
    }

    private CapabilityTierMap() {}

    /**
     * 返回该能力的档位；**未知能力返回 {@code null}**。
     *
     * <p>调用方必须把 {@code null} 当成**拒绝**。这里不抛异常，是因为抛异常容易被
     * 一个宽泛的 {@code catch} 吞掉，而返回 {@code null} 会被用在类型上 ——
     * 忘了处理就拿不到 {@link CapabilityGate.Tier}，也就发不出请求。
     */
    public static CapabilityGate.Tier tierFor(String capabilityName) {
        if (capabilityName == null) {
            return null;
        }
        return TIERS.get(capabilityName);
    }

    /** 已映射的能力名（只读）。给测试与诊断用。 */
    public static java.util.Set<String> mappedNames() {
        return TIERS.keySet();
    }
}
