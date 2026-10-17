/**
 * {@code CapabilityGate} 的**行为探针** —— 真编译、真运行，不是 grep。
 *
 * <p>为什么用这个形状而不是 JUnit：本机有 JDK 17、没有 Gradle / Android SDK，
 * 而 JUnit 需要一整套依赖。这个文件只要 {@code javac} 与 {@code java}，
 * 于是它能在**本机**跑（见 tools/README.md 里 {@code check-credential-projection.java}
 * 的先例）。CI 里另有 JUnit 版本负责在 Android 工具链下跑一遍。
 *
 * <p>用法：{@code tools/check-capability-gate.java}（脚本形式，可直接执行）
 * 或 {@code java tools/check-capability-gate.java}（JDK 11+ 单文件模式）。
 *
 * <p>⚠️ **注入约定**：加一条断言就要能证明它会红。本文件的断言都可以通过
 * 改 {@code CapabilityGate.java} 里对应的判断来打红（tools/README.md 有记录）。
 */

import com.dshfusion.app.bridge.security.CapabilityGate;
import com.dshfusion.app.bridge.security.CapabilityGate.Channel;
import com.dshfusion.app.bridge.security.CapabilityGate.Decision;
import com.dshfusion.app.bridge.security.CapabilityGate.DenyCode;
import com.dshfusion.app.bridge.security.CapabilityGate.Grants;
import com.dshfusion.app.bridge.security.CapabilityGate.Permit;
import com.dshfusion.app.bridge.security.CapabilityGate.Request;
import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.Set;

final class CheckCapabilityGate {   // 非 public：文件名是 kebab-case（仓库约定）

    private static int failures = 0;
    private static int checked = 0;

    public static void main(String[] args) {
        defaultsCloseTheRightTiers();
        accessibilityNeedsTheSystemService();
        privilegedNeedsTheExactChannel();
        selfTargetOnlyForBringingHostToFront();
        unavailableStateFailsClosed();
        bridgeCredentialIsNeverGrantable();
        permitCannotBeForged();

        System.out.println();
        System.out.println("检查了 " + checked + " 条，失败 " + failures + " 条。");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---------- 断言原语 ----------

    private static void ok(String what) {
        checked++;
        System.out.println("  [PASS] " + what);
    }

    private static void bad(String what, Object actual) {
        checked++;
        failures++;
        System.out.println("  [FAIL] " + what + " —— 实际是 " + actual);
    }

    private static void expectAllowed(String what, Decision decision) {
        if (decision.allowed()) {
            ok(what);
        } else {
            bad(what + "（期望放行）", decision);
        }
    }

    private static void expectDenied(String what, Decision decision, DenyCode expected) {
        if (decision.allowed()) {
            bad(what + "（期望拒绝 " + expected + "，却放行了）", decision);
            return;
        }
        CapabilityGate.Denial denial = (CapabilityGate.Denial) decision;
        if (denial.code() == expected) {
            ok(what + " → " + expected);
        } else {
            bad(what + "（期望 " + expected + "）", denial.code());
        }
    }

    // ---------- 五组边界 ----------

    /** 3.2 的默认值：只开 T0/T1；T2/T3 关；T4 永不。 */
    private static void defaultsCloseTheRightTiers() {
        System.out.println("== 默认值（全新安装） ==");
        CapabilityGate gate = new CapabilityGate(Grants.defaults());

        expectAllowed("T0 默认可放行", gate.decide(Request.of(Tier.T0_UNCONDITIONAL)));
        expectAllowed("T1 默认可放行（「首次问一次」属于呈现层）",
                gate.decide(Request.of(Tier.T1_APP_PERMISSION)));
        expectDenied("T2 默认关", gate.decide(Request.accessibility(true)),
                DenyCode.TIER_DISABLED);
        expectDenied("T3 默认关", gate.decide(Request.privileged(Channel.SHIZUKU)),
                DenyCode.TIER_DISABLED);
        expectDenied("T4 永不", gate.decide(Request.of(Tier.T4_BRIDGE_CREDENTIAL)),
                DenyCode.CREDENTIAL_NEVER_EXPOSED);
    }

    /** 3.4 边界 1：开了 T2 但系统里的无障碍服务没开，仍然不能做。 */
    private static void accessibilityNeedsTheSystemService() {
        System.out.println("== T2：应用侧开了不算，系统服务也要开 ==");
        CapabilityGate gate = new CapabilityGate(
                Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T2_ACCESSIBILITY),
                        EnumSet.noneOf(Channel.class)));

        expectDenied("开了 T2 但无障碍服务没开", gate.decide(Request.accessibility(false)),
                DenyCode.ACCESSIBILITY_SERVICE_OFF);
        expectAllowed("两者都开才放行", gate.decide(Request.accessibility(true)));
    }

    /** 3.4 边界 3：T3 按通道分级，**不许降级到更弱的通道**。 */
    private static void privilegedNeedsTheExactChannel() {
        System.out.println("== T3：按通道分级，不降级 ==");
        CapabilityGate gate = new CapabilityGate(
                Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T3_PRIVILEGED),
                        EnumSet.of(Channel.SHIZUKU)));

        expectAllowed("只授了 Shizuku，请求 Shizuku → 放行",
                gate.decide(Request.privileged(Channel.SHIZUKU)));
        expectDenied("只授了 Shizuku，请求 ROOT → 拒绝（不降级、不静默换通道）",
                gate.decide(Request.privileged(Channel.ROOT)), DenyCode.CHANNEL_NOT_GRANTED);
        expectDenied("只授了 Shizuku，请求 ADB → 拒绝",
                gate.decide(Request.privileged(Channel.ADB)), DenyCode.CHANNEL_NOT_GRANTED);

        // 没有默认通道：连「让闸门自己挑一个」都不允许。
        expectDenied("T3 请求不带通道 → 拒绝（「不知道用哪个」不等于「随便挑一个」）",
                gate.decide(Request.privileged(null)), DenyCode.CHANNEL_NOT_GRANTED);
    }

    /** 3.4 边界 2：禁止以宿主自身为目标，唯一例外是「叫回前台」。 */
    private static void selfTargetOnlyForBringingHostToFront() {
        System.out.println("== 禁止自目标（权限自举回路） ==");
        CapabilityGate gate = new CapabilityGate(
                Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T1_APP_PERMISSION,
                        Tier.T2_ACCESSIBILITY, Tier.T3_PRIVILEGED), EnumSet.allOf(Channel.class)));

        expectDenied("T1 以宿主为目标（不是叫回前台）",
                gate.decide(Request.selfTarget(Tier.T1_APP_PERMISSION, null, false)),
                DenyCode.SELF_TARGET_FORBIDDEN);
        expectAllowed("T1 以宿主为目标但形状是「叫回前台」",
                gate.decide(Request.selfTarget(Tier.T1_APP_PERMISSION, null, true)));
        expectDenied("T3 以宿主为目标（不是叫回前台）",
                gate.decide(Request.selfTarget(Tier.T3_PRIVILEGED, Channel.ROOT, false)),
                DenyCode.SELF_TARGET_FORBIDDEN);
    }

    /** 3.4 §三：授权状态读不到时 **fail-closed**，连 T0 都不放行。 */
    private static void unavailableStateFailsClosed() {
        System.out.println("== 授权状态不可用 → 全部拒绝 ==");
        CapabilityGate gate = new CapabilityGate(Grants.unavailable());

        expectDenied("状态不可用时 T0 也被拒（不许「读不到就当开着」）",
                gate.decide(Request.of(Tier.T0_UNCONDITIONAL)),
                DenyCode.AUTHORIZATION_UNAVAILABLE);
        expectDenied("状态不可用时 T3 也被拒",
                gate.decide(Request.privileged(Channel.ROOT)),
                DenyCode.AUTHORIZATION_UNAVAILABLE);
    }

    /**
     * T4 **不可授予**：即使有人把 T4 塞进「已开启档位」这个集合，也仍然拒绝。
     *
     * <p>这一条是防御性的——它挡的是「将来有人给 T4 加了授权入口」。
     */
    private static void bridgeCredentialIsNeverGrantable() {
        System.out.println("== T4 即使被误加进已开启集合，也仍然拒绝 ==");
        CapabilityGate gate = new CapabilityGate(
                Grants.of(EnumSet.allOf(Tier.class), EnumSet.allOf(Channel.class)));

        expectDenied("T4 被列入已开启档位时仍然拒绝",
                gate.decide(Request.of(Tier.T4_BRIDGE_CREDENTIAL)),
                DenyCode.CREDENTIAL_NEVER_EXPOSED);
    }

    /**
     * **结构性断言**：{@link Permit} 的构造器不是 public —— 于是闸门之外造不出「放行凭据」。
     *
     * <p>这条比任何布尔判断都强：它证明「拒绝时什么都给不出去」不是约定，是类型上做不到。
     */
    private static void permitCannotBeForged() {
        System.out.println("== 结构性：Permit 无法被构造 ==");
        try {
            Constructor<?>[] ctors = Permit.class.getDeclaredConstructors();
            boolean anyPublic = false;
            for (Constructor<?> ctor : ctors) {
                if (Modifier.isPublic(ctor.getModifiers())) {
                    anyPublic = true;
                }
            }
            if (anyPublic) {
                bad("Permit 的构造器不该是 public", "public");
            } else {
                ok("Permit 的构造器全部非 public（" + ctors.length + " 个）—— 闸门之外造不出来");
            }
        } catch (RuntimeException error) {
            bad("反射读取 Permit 构造器失败", error.getClass().getSimpleName());
        }

        // Denial 不携带任何「下游可用的东西」：它只 public 一个 `code()`。
        Set<String> publicMethods = new java.util.TreeSet<>();
        for (java.lang.reflect.Method method : CapabilityGate.Denial.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                publicMethods.add(method.getName());
            }
        }
        Set<String> allowed = new java.util.TreeSet<>(
                java.util.Arrays.asList("allowed", "code", "toString"));
        if (publicMethods.equals(allowed)) {
            ok("Denial 只暴露 " + allowed + " —— 没有任何可转交给下游的字段");
        } else {
            bad("Denial 的 public 方法集合变了", publicMethods);
        }
    }
}
