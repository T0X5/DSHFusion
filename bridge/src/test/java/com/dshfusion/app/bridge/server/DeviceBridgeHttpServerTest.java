package com.dshfusion.app.bridge.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dshfusion.app.bridge.security.CapabilityGate.Channel;
import com.dshfusion.app.bridge.security.CapabilityGate.Grants;
import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 用**真的 HTTP 请求**验门禁 —— 不是直接调函数。
 *
 * <p>为什么值得多一层：分发器测的是「函数返回了什么」，而这里测的是
 * **线上真的会发生什么**（状态码、头、正文、以及设备有没有被碰）。
 * 阶段 3.4 §四 的验收句子是「关着的时候**真的做不了**」——
 * 「真的」那两个字要靠真请求来兑现。
 */
public final class DeviceBridgeHttpServerTest {

    private static final class ProbeClient implements DeviceClient {
        int versionCalls;
        int dumpCalls;

        @Override public String version() {
            versionCalls++;
            return "BRIDGE_PROTOCOL=3";
        }

        @Override public String uiDump() {
            dumpCalls++;
            return "[1] \"设置\" 可点击";
        }
    }

    private ProbeClient client;
    private DeviceBridgeHttpServer server;
    private String base;

    private final AtomicBoolean originTrusted = new AtomicBoolean(true);
    private final AtomicBoolean accessibilityOn = new AtomicBoolean(false);
    private final AtomicReference<Grants> grants = new AtomicReference<>(Grants.defaults());

    @Before
    public void startServer() throws Exception {
        client = new ProbeClient();
        server = new DeviceBridgeHttpServer(
                new DeviceEndpointDispatcher(client),
                new DeviceBridgeHttpServer.Environment() {
                    @Override public boolean originTrusted() {
                        return originTrusted.get();
                    }

                    @Override public boolean accessibilityServiceOn() {
                        return accessibilityOn.get();
                    }

                    @Override public Grants grants() {
                        return grants.get();
                    }

                    @Override public String expectedToken() {
                        return "test-token";
                    }
                });
        int port = server.start(0);
        base = "http://127.0.0.1:" + port;
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (token != null) {
            builder.header("X-Token", token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 没有 token 时，连「端点存不存在」都不该被探出来。 */
    @Test
    public void missingTokenIsRejectedBeforeRouting() throws Exception {
        HttpResponse<String> noToken = get("/app/version", null);
        assertEquals(401, noToken.statusCode());
        assertEquals("UNAUTHORIZED", noToken.body());

        HttpResponse<String> badToken = get("/app/version", "wrong");
        assertEquals(401, badToken.statusCode());

        assertEquals("鉴权失败时不许碰设备", 0, client.versionCalls + client.dumpCalls);
    }

    @Test
    public void versionIsServedOverRealHttp() throws Exception {
        HttpResponse<String> response = get("/app/version", "test-token");
        assertEquals(200, response.statusCode());
        assertEquals("BRIDGE_PROTOCOL=3", response.body());
        assertEquals(1, client.versionCalls);
    }

    /**
     * **头条用例（真 HTTP 版）**：读屏默认关 ⇒ 403 + 稳定码，且设备一次没被碰。
     *
     * <p>注意此时连无障碍服务都还没开 —— 双重拒绝，理由以档位为先。
     */
    @Test
    public void screenDumpIsDeniedOverRealHttpWithNoDeviceAccess() throws Exception {
        HttpResponse<String> response = get("/app/ui/dump", "test-token");
        assertEquals(403, response.statusCode());
        assertEquals("TIER_DISABLED", response.body());
        assertEquals("关着时**一次都不许**碰设备", 0, client.dumpCalls);
    }

    /** 打开 T2 与系统服务后，同一个请求变成 200 —— 证明上面那条拒绝是可撤销的。 */
    @Test
    public void screenDumpBecomesAvailableOnlyAfterBothGatesOpen() throws Exception {
        grants.set(Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T2_ACCESSIBILITY),
                EnumSet.noneOf(Channel.class)));
        accessibilityOn.set(true);

        HttpResponse<String> response = get("/app/ui/dump", "test-token");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("设置"));
        assertEquals(1, client.dumpCalls);
    }

    /** 非 GET 方法被拒绝 —— 协议里没有定义，拒绝比「当成 GET」安全。 */
    @Test
    public void nonGetMethodsAreRejected() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/app/version"))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-Token", "test-token")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertEquals(0, client.versionCalls);
    }
}
