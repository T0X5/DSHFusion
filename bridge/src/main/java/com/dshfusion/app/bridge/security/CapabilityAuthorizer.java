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
        Objects.requireNonNull(grants, "grants");

        if (!originTrusted) {
            return new Outcome(false, null, CapabilityGate.DenyCode.ORIGIN_NOT_TRUSTED);
        }

        CapabilityGate.Tier tier = CapabilityTierMap.tierFor(capabilityName);
        if (tier == null) {
            return new Outcome(false, null, CapabilityGate.DenyCode.UNKNOWN_CAPABILITY);
        }

        // T3 需要显式通道，而这条路径没有通道概念 —— 传 null，闸门会拒绝。
        // **这是刻意的**：想跑命令必须先说清楚用哪条通道，不许「有一个通道就行」。
        CapabilityGate.Request request;
        switch (tier) {
            case T3_PRIVILEGED:
                request = CapabilityGate.Request.privileged(null);
                break;
            case T2_ACCESSIBILITY:
                // 无障碍的「系统服务是否开着」由调用方另行提供；这条路径默认按**没开**处理，
                // 因为「没告诉我」不能当成「开着」。
                request = CapabilityGate.Request.accessibility(false);
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
