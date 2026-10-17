package com.dshfusion.app.bridge.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 统一设备能力层的**档位闸门**（阶段 3.2 的档位 + 3.4 的边界）。
 *
 * <h2>为什么是 Java 而不是 Kotlin</h2>
 *
 * 本机有 JDK 17、没有 kotlinc（见 docs/INCIDENTS.md 第 21 条）。这条代码决定
 * **能不能碰手机**——只让 CI 编译它太贵。与阶段 2.4b-① 的 `HostRestoreMapping`
 * 同一个取舍：**会碰设备/会毁数据的路径要能本机真跑。** 本类零 Android 导入。
 *
 * <h2>为什么不是扩展 {@link BridgePolicy}</h2>
 *
 * 那个类管的是**网页来源的信任等级**，能力模型是**扁平枚举 + 一个
 * {@code userAuthorizedHighRisk} 布尔**，而那个布尔同时罩着
 * {@code COMMAND} / {@code FILESYSTEM_WRITE} / {@code PROCESS}。
 *
 * <p>阶段 3.2 已判定那是个缺陷：**把最弱那个的风险灌到最强那个上**。
 * 用户为了「让 agent 能写文件」而打开的那一个开关，会连「能跑任意命令」一起打开。
 * 所以这里另建一个**按最坏后果分级**的模型，而不是把那个布尔拆成三个布尔
 * （拆三个布尔仍无档位，无法回答「T2 关着时是什么样」）。
 *
 * <h2>设计上唯一重要的一件事：拒绝时**什么都给不出去**</h2>
 *
 * {@link #decide(Request)} 只返回 {@link Decision}。被拒时得到的是
 * {@link Denial}——**它不携带任何可转交给下游的东西**；放行时得到的是
 * {@link Permit}，而 {@link Permit} **不能被构造**（构造器私有），只能由本类产生。
 *
 * <p>于是「关着时拿不到数据」不是一条需要记得去写的判断，而是**类型上做不到**：
 * 没有 {@code Permit} 的代码路径根本拿不到参数，也就无从发请求。
 * 这正是阶段 3.4 边界 1 要的形态——说「不可能」，不说「不允许」。
 *
 * <h2>拒绝码是契约，不是消息</h2>
 *
 * 每一个 {@link DenyCode} 都是**稳定标识符**，会被写进 3.3 的契约表
 * （「关着时返回什么」那一列）。改动它们等于改接口，需要同步契约表。
 * **人类可读的说明另外给，且随时可以改——两者不许混。**
 */
public final class CapabilityGate {

    /** 档位，按**最坏能做什么**排（阶段 3.2 §二）。 */
    public enum Tier {
        /** 只影响它自己：不读设备、不写设备、不发起外部请求。 */
        T0_UNCONDITIONAL,
        /** 需要一个 Android 运行时权限（通知 / 悬浮窗 / 闹钟）。最坏是打扰用户。 */
        T1_APP_PERMISSION,
        /**
         * 无障碍。最坏是**看见屏幕上的任何内容并代替用户操作**。
         *
         * <p>注意它排在 T3 之前：root 通道仍受设备侧的策略拦截，
         * 而无障碍**没有那样的边界**——它能读到密码框，也能代替用户点「允许」。
         * 它不是「更大的权限」，它是「一个新的你」。
         */
        T2_ACCESSIBILITY,
        /** 提权：Shizuku / root / ADB。最坏是**以 shell 身份做任何事**。 */
        T3_PRIVILEGED,
        /**
         * 桥凭据本身。最坏是**完全接管整座桥**。
         *
         * <p>这一档**没有授权入口**：凭据是进程身份，不是用户偏好。
         */
        T4_BRIDGE_CREDENTIAL,
    }

    /** T3 的三条通道。**必须分别授权**——三档的后果不同。 */
    public enum Channel {
        /** 只在被授予的范围内。 */
        SHIZUKU,
        /** 全机。 */
        ROOT,
        /** 要连着线。 */
        ADB,
    }

    /** 稳定的拒绝码。**改它等于改接口。** */
    public enum DenyCode {
        /** 该档默认关闭，用户从未开启。 */
        TIER_DISABLED,
        /** 用户开过这一档，但没给这一项。 */
        CAPABILITY_NOT_GRANTED,
        /** T2 专用：系统的无障碍服务没有开（应用侧开了也不算）。 */
        ACCESSIBILITY_SERVICE_OFF,
        /** T3 专用：请求的通道没有被授权。**不许降级到更弱的通道。** */
        CHANNEL_NOT_GRANTED,
        /** 以宿主自身为目标——权限自举回路，见阶段 3.4 边界 2。 */
        SELF_TARGET_FORBIDDEN,
        /** T4 专用：桥凭据永不交给调用方。 */
        CREDENTIAL_NEVER_EXPOSED,
        /** 授权状态读不到或损坏。**按拒绝处理，不按放行处理。** */
        AUTHORIZATION_UNAVAILABLE,
    }

    /** 一次请求。{@code selfTarget} 只允许在 {@code bringHostToFront} 这一种形状上为真。 */
    public static final class Request {
        private final Tier tier;
        private final Channel channel;      // 只有 T3 用；其余传 null
        private final boolean selfTarget;
        private final boolean bringHostToFront;
        private final boolean accessibilityServiceOn;

        private Request(Tier tier, Channel channel, boolean selfTarget,
                        boolean bringHostToFront, boolean accessibilityServiceOn) {
            this.tier = Objects.requireNonNull(tier, "tier");
            this.channel = channel;
            this.selfTarget = selfTarget;
            this.bringHostToFront = bringHostToFront;
            this.accessibilityServiceOn = accessibilityServiceOn;
        }

        /** T0 / T1 / T2 / T4 的请求形状。 */
        public static Request of(Tier tier) {
            return new Request(tier, null, false, false, false);
        }

        /** T2 的请求形状：必须同时告诉闸门「系统里的无障碍服务开着吗」。 */
        public static Request accessibility(boolean serviceOn) {
            return new Request(Tier.T2_ACCESSIBILITY, null, false, false, serviceOn);
        }

        /** T3 的请求形状：**通道必须显式给出**，没有默认值。 */
        public static Request privileged(Channel channel) {
            return new Request(Tier.T3_PRIVILEGED, channel, false, false, false);
        }

        /** 以宿主自身为目标——唯一合法的形状是「把宿主叫回前台」。 */
        public static Request selfTarget(Tier tier, Channel channel, boolean bringHostToFront) {
            return new Request(tier, channel, true, bringHostToFront, tier == Tier.T2_ACCESSIBILITY);
        }
    }

    /** 判定结果。**只有两种，没有「允许但带警告」那一种。** */
    public interface Decision {
        boolean allowed();
    }

    /** 放行凭据。**构造器私有一一只能由 {@code CapabilityGate} 产生。** */
    public static final class Permit implements Decision {
        private final Tier tier;

        private Permit(Tier tier) {
            this.tier = tier;
        }

        @Override public boolean allowed() {
            return true;
        }

        public Tier tier() {
            return tier;
        }
    }

    /** 拒绝。**不携带任何可转交给下游的东西。** */
    public static final class Denial implements Decision {
        private final DenyCode code;

        private Denial(DenyCode code) {
            this.code = code;
        }

        @Override public boolean allowed() {
            return false;
        }

        public DenyCode code() {
            return code;
        }

        @Override public String toString() {
            return "Denial(" + code + ")";
        }
    }

    /** 用户的授权状态。**只读快照，不可变。** */
    public static final class Grants {
        private final Set<Tier> openTiers;
        private final Set<Channel> channels;
        private final boolean unavailable;

        private Grants(Set<Tier> openTiers, Set<Channel> channels, boolean unavailable) {
            this.openTiers = openTiers;
            this.channels = channels;
            this.unavailable = unavailable;
        }

        /**
         * 全新安装：**只有 T0 是开的**。
         *
         * <p>T1 也放进来，因为它的默认是「开但首次使用时问一次」——
         * 而「问一次」属于呈现层，不属于闸门；闸门只管「要不要拦」。
         */
        public static Grants defaults() {
            return new Grants(EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T1_APP_PERMISSION),
                    Collections.<Channel>emptySet(), false);
        }

        /** 授权状态读不到。**闸门必须 fail-closed。** */
        public static Grants unavailable() {
            return new Grants(Collections.<Tier>emptySet(),
                    Collections.<Channel>emptySet(), true);
        }

        public static Grants of(Set<Tier> tiers, Set<Channel> channels) {
            return new Grants(EnumSet.copyOf(tiers),
                    channels.isEmpty() ? Collections.<Channel>emptySet() : EnumSet.copyOf(channels),
                    false);
        }

        boolean isOpen(Tier tier) {
            return openTiers.contains(tier);
        }

        boolean hasChannel(Channel channel) {
            return channel != null && channels.contains(channel);
        }
    }

    private final Grants grants;

    public CapabilityGate(Grants grants) {
        this.grants = Objects.requireNonNull(grants, "grants");
    }

    /**
     * 判定一次请求。
     *
     * <p>顺序是刻意的：**先判「状态可不可信」，再判档位，最后判形状。**
     * 若把「状态不可用」放在后面，一次读盘失败就可能走到「默认放行」那一步。
     */
    public Decision decide(Request request) {
        Objects.requireNonNull(request, "request");

        if (grants.unavailable) {
            return new Denial(DenyCode.AUTHORIZATION_UNAVAILABLE);
        }

        // T4 没有授权入口——它排在所有档位判断之前，因为它**永远**是拒绝。
        if (request.tier == Tier.T4_BRIDGE_CREDENTIAL) {
            return new Denial(DenyCode.CREDENTIAL_NEVER_EXPOSED);
        }

        if (!grants.isOpen(request.tier)) {
            return new Denial(DenyCode.TIER_DISABLED);
        }

        if (request.tier == Tier.T2_ACCESSIBILITY && !request.accessibilityServiceOn) {
            return new Denial(DenyCode.ACCESSIBILITY_SERVICE_OFF);
        }

        if (request.tier == Tier.T3_PRIVILEGED) {
            if (request.channel == null) {
                // 没有默认通道。**「不知道用哪个」不等于「随便挑一个」**。
                return new Denial(DenyCode.CHANNEL_NOT_GRANTED);
            }
            if (!grants.hasChannel(request.channel)) {
                return new Denial(DenyCode.CHANNEL_NOT_GRANTED);
            }
        }

        // 以宿主自身为目标：唯一合法形状是「把宿主叫回前台」。
        // 其余一律拒——否则 agent 可以拿宿主应用当目标驱动它自己（权限自举回路）。
        if (request.selfTarget && !request.bringHostToFront) {
            return new Denial(DenyCode.SELF_TARGET_FORBIDDEN);
        }

        return new Permit(request.tier);
    }
}
