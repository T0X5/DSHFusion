package com.dshfusion.app.bridge.security;

import java.util.Objects;

/**
 * 把**两道门**合成一次裁决：来源门（{@link BridgePolicy} 的判据）+ 档位门（{@link CapabilityGate}）。
 *
 * <h2>为什么它在这里、而不是在 {@code BridgeRouter} 里</h2>
 *
 * 因为 {@code BridgeRouter} 依赖 {@code OriginVerifier}，而后者用了
 * {@code android.net.Uri} —— 于是一段纯逻辑的粘合代码会**因为一个 Android 类型**
 * 而只能在 instrumentation 里测，或者加 Robolectric。
 *
 * <p>这不划算：这段代码决定「能不能碰手机」。把它做成**零 Android 导入的纯 Java**，
 * 就能在本机两秒跑完（本机有 JDK 17、没有 kotlinc）。路由器那边只剩几行类型转换。
 *
 * <h2>两道门是 AND，不是 OR</h2>
 *
 * 来源门不检查档位，档位门不检查来源。任何一道拒绝就是拒绝 ——
 * 所以不存在「过了新的那道就绕过旧的那道」。
 *
 * <h2>未知能力一律拒绝</h2>
 *
 * 映射不到档位 = {@link CapabilityGate.DenyCode#UNKNOWN_CAPABILITY}，
 * **不是放行**。新增能力忘了映射时，结果必须是「用不了」，而不是「不受管」。
 */
public final class CapabilityAuthorizer {

    /** 一次裁决的结果。**不含任何可用于执行的参数。** */
    public static final class Outcome {
        private final boolean allowed;
        private final CapabilityGate.Tier tier;
        private final CapabilityGate.DenyCode code;

        private Outcome(boolean allowed, CapabilityGate.Tier tier, CapabilityGate.DenyCode code) {
            this.allowed = allowed;
            this.tier = tier;
            this.code = code;
        }

        public boolean allowed() {
            return allowed;
        }

        /** 放行时的档位；被拒时为 {@code null}。 */
        public CapabilityGate.Tier tier() {
            return tier;
        }

        /** 被拒时的码；放行时为 {@code null}。 */
        public CapabilityGate.DenyCode code() {
            return code;
        }

        @Override
        public String toString() {
            return allowed ? "Allowed(" + tier + ")" : "Denied(" + code + ")";
        }
    }

    private CapabilityAuthorizer() {}

    /**
     * 裁决一次能力调用。
     *
     * @param originTrusted 来源门是否通过（**由调用方判定**——判定它需要 {@code android.net.Uri}）
     * @param capabilityName 能力的枚举名；未知名字会被拒绝
     * @param grants 档位授权状态
     */
    public static Outcome authorize(boolean originTrusted, String capabilityName,
                                    CapabilityGate.Grants grants) {
        CapabilityGate.Tier tier = CapabilityTierMap.tierFor(capabilityName);
        if (tier == null) {
            // 未知能力**先于来源判定**就要拒绝？不 —— 顺序见下：
            // 来源不受信任时统一报 ORIGIN_NOT_TRUSTED，避免把「能力存在与否」泄露给不受信任的来源。
            if (!originTrusted) {
                return new Outcome(false, null, CapabilityGate.DenyCode.ORIGIN_NOT_TRUSTED);
            }
            return new Outcome(false, null, CapabilityGate.DenyCode.UNKNOWN_CAPABILITY);
        }
        return authorize(originTrusted, tier, false, grants);
    }

    /**
     * 按**档位**直接裁决 —— 供设备能力端点表使用（那些端点不是 {@code BridgeCapability}，
     * 硬塞进那张表会让「枚举对账」这条测试失去意义）。
     *
     * @param accessibilityServiceOn T2 专用：系统里的无障碍服务是否开着。
     *                               **调用方不告诉我们就按「没开」处理** ——
     *                               「没告知」不能当成「开着」。
     */
    public static Outcome authorize(boolean originTrusted, CapabilityGate.Tier tier,
                                    boolean accessibilityServiceOn,
                                    CapabilityGate.Grants grants) {
        Objects.requireNonNull(grants, "grants");
        Objects.requireNonNull(tier, "tier");

        if (!originTrusted) {
            return new Outcome(false, null, CapabilityGate.DenyCode.ORIGIN_NOT_TRUSTED);
        }

        // T3 需要显式通道，而这条路径没有通道概念 —— 传 null，闸门会拒绝。
        // **这是刻意的**：想跑命令必须先说清楚用哪条通道，不许「有一个通道就行」。
        CapabilityGate.Request request;
        switch (tier) {
            case T3_PRIVILEGED:
                request = CapabilityGate.Request.privileged(null);
                break;
            case T2_ACCESSIBILITY:
                request = CapabilityGate.Request.accessibility(accessibilityServiceOn);
                break;
            default:
                request = CapabilityGate.Request.of(tier);
                break;
        }

        CapabilityGate.Decision decision = new CapabilityGate(grants).decide(request);
        if (decision instanceof CapabilityGate.Permit) {
            return new Outcome(true, ((CapabilityGate.Permit) decision).tier(), null);
        }
        CapabilityGate.DenyCode code = CapabilityGate.DenyCode.AUTHORIZATION_UNAVAILABLE;
        if (decision instanceof CapabilityGate.Denial) {
            code = ((CapabilityGate.Denial) decision).code();
        }
        return new Outcome(false, null, code);
    }

}
