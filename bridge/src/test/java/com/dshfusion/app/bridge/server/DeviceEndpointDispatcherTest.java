package com.dshfusion.app.bridge.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dshfusion.app.bridge.security.CapabilityGate.Channel;
import com.dshfusion.app.bridge.security.CapabilityGate.DenyCode;
import com.dshfusion.app.bridge.security.CapabilityGate.Grants;
import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.util.EnumSet;

import org.junit.Test;

/**
 * 分发器的门禁行为 —— 纯 Java，本机可跑。
 *
 * <h2>这个测试真正在证的东西</h2>
 *
 * 不是「返回了 403」，而是 **设备一次都没被碰**。
 * 所以用 {@link ProbeClient} 计数，并且在拒绝的用例里断言计数是 0。
 *
 * <p>而那 0 必须有意义 —— 如果客户端从来不计数、或者放行路径也从不调用它，
 * 「0 次」就会**因为错的原因**通过。所以每个「被拒」用例旁边都配一条放行用例，
 * 断言计数变成 1。**只证一半等于没证**（事故第 28/30 条的家族）。
 */
public final class DeviceEndpointDispatcherTest {

    /** 探针客户端：数调用次数。 */
    private static final class ProbeClient implements DeviceClient {
        int versionCalls;
        int dumpCalls;

        @Override public String version() {
            versionCalls++;
            return "BRIDGE_PROTOCOL=3";
        }

        @Override public String uiDump() {
            dumpCalls++;
            return "[1] \"设置\" 可点击 中心=(72,216)";
        }
    }

    private static Grants onlyT0() {
        return Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL), EnumSet.noneOf(Channel.class));
    }

    private static Grants t0AndT2() {
        return Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T2_ACCESSIBILITY),
                EnumSet.noneOf(Channel.class));
    }

    @Test
    public void versionIsTier0SoItIsAllowedByDefault() {
        ProbeClient client = new ProbeClient();
        DeviceEndpointDispatcher.Response response = new DeviceEndpointDispatcher(client)
                .dispatch(true, "/app/version", false, Grants.defaults());

        assertEquals(200, response.status());
        assertEquals("BRIDGE_PROTOCOL=3", response.body());
        assertNull(response.code());
        assertEquals("放行时应当恰好调用一次设备", 1, client.versionCalls);
    }

    /**
     * **头条用例**：读屏是 T2，默认关 ⇒ 403，而且 {@code uiDump} 一次都没被调用。
     *
     * <p>「没被调用」比「返回了 403」强得多：它证明的是**做不到**，而不是**做了但没给**。
     */
    @Test
    public void screenDumpIsDeniedByDefaultAndNeverTouchesTheDevice() {
        ProbeClient client = new ProbeClient();
        DeviceEndpointDispatcher.Response response = new DeviceEndpointDispatcher(client)
                .dispatch(true, "/app/ui/dump", true, Grants.defaults());

        assertEquals(403, response.status());
        assertEquals(DenyCode.TIER_DISABLED.name(), response.body());
        assertEquals(DenyCode.TIER_DISABLED, response.code());
        assertEquals("被拒时**一次都不许**碰设备", 0, client.dumpCalls);
    }

    /** 开了 T2 但系统无障碍没开 —— 仍然不许碰设备（应用侧开了不算）。 */
    @Test
    public void screenDumpStillDeniedWhenTheSystemServiceIsOff() {
        ProbeClient client = new ProbeClient();
        DeviceEndpointDispatcher.Response response = new DeviceEndpointDispatcher(client)
                .dispatch(true, "/app/ui/dump", false, t0AndT2());

        assertEquals(403, response.status());
        assertEquals(DenyCode.ACCESSIBILITY_SERVICE_OFF, response.code());
        assertEquals(0, client.dumpCalls);
    }

    /** 两边都开了才放行 —— 与上面两条配对，证明那个 0 不是「永远不调用」。 */
    @Test
    public void screenDumpIsAllowedWhenTierAndSystemServiceAreBothOn() {
        ProbeClient client = new ProbeClient();
        DeviceEndpointDispatcher.Response response = new DeviceEndpointDispatcher(client)
                .dispatch(true, "/app/ui/dump", true, t0AndT2());

        assertEquals(200, response.status());
        assertTrue(response.body().contains("设置"));
        assertEquals(1, client.dumpCalls);
    }

    /** 未知端点：拒绝，且**不碰设备**。 */
    @Test
    public void unknownEndpointIsDeniedAndNeverTouchesTheDevice() {
        ProbeClient client = new ProbeClient();
        DeviceEndpointDispatcher.Response response = new DeviceEndpointDispatcher(client)
                .dispatch(true, "/app/exec?cmd=id", true,
                        Grants.of(EnumSet.allOf(Tier.class), EnumSet.allOf(Channel.class)));

        assertEquals(403, response.status());
        assertEquals(DenyCode.UNKNOWN_CAPABILITY, response.code());
        assertEquals(0, client.versionCalls + client.dumpCalls);
    }

    /** 来源不受信任：拒绝。而且对未知端点**也报来源问题**，免得把端点表泄露出去。 */
    @Test
    public void untrustedOriginIsDeniedAndHidesWhetherTheEndpointExists() {
        ProbeClient client = new ProbeClient();
        DeviceEndpointDispatcher dispatcher = new DeviceEndpointDispatcher(client);

        assertEquals(DenyCode.ORIGIN_NOT_TRUSTED,
                dispatcher.dispatch(false, "/app/version", true, Grants.defaults()).code());
        assertEquals(DenyCode.ORIGIN_NOT_TRUSTED,
                dispatcher.dispatch(false, "/app/does-not-exist", true, Grants.defaults()).code());
        assertEquals(0, client.versionCalls + client.dumpCalls);
    }

    /** 授权状态不可用：fail-closed，连 T0 都不放行。 */
    @Test
    public void unavailableGrantsDenyEvenTier0() {
        ProbeClient client = new ProbeClient();
        DeviceEndpointDispatcher.Response response = new DeviceEndpointDispatcher(client)
                .dispatch(true, "/app/version", true, Grants.unavailable());

        assertEquals(403, response.status());
        assertEquals(DenyCode.AUTHORIZATION_UNAVAILABLE, response.code());
        assertEquals(0, client.versionCalls);
    }

    /** 查询串不影响档位判定；而设备侧失败给出稳定的码，不透出异常文本。 */
    @Test
    public void queryStringIsIgnoredAndDeviceFailureHasAStableCode() {
        ProbeClient ok = new ProbeClient();
        assertEquals(200, new DeviceEndpointDispatcher(ok)
                .dispatch(true, "/app/version?x=1", true, Grants.defaults()).status());

        DeviceClient broken = new DeviceClient() {
            @Override public String version() {
                throw new IllegalStateException("原始路径 /data/user/0/… 就在这里");
            }

            @Override public String uiDump() {
                throw new IllegalStateException("同上");
            }
        };
        DeviceEndpointDispatcher.Response response = new DeviceEndpointDispatcher(broken)
                .dispatch(true, "/app/version", true, Grants.defaults());

        assertEquals(503, response.status());
        assertEquals("DEVICE_UNAVAILABLE", response.body());
        assertTrue("不许把上游异常文本原样透出去（它可能带本机路径）",
                !response.body().contains("原始路径"));
    }
}
