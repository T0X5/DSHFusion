package com.dshfusion.app.bridge.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dshfusion.app.bridge.security.CapabilityGate.Channel;
import com.dshfusion.app.bridge.security.CapabilityGate.Grants;
import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
 * **线上真的会发生什么**（状态码、正文、以及设备有没有被碰）。
 * 阶段 3.4 §四 的验收句子是「关着的时候**真的做不了**」——
 * 「真的」那两个字要靠真请求来兑现。
 *
 * <h2>为什么用 {@link HttpURLConnection} 而不是 {@code java.net.http}</h2>
 *
 * 因为 {@code java.net.http}（JDK 11 的 HTTP 客户端）**在 Android 上不存在**，
 * 而 {@code :bridge} 的单测是按 Android 的 bootclasspath 编的 ——
 * 用它会直接 `compileDebugUnitTestJavaWithJavac` 失败（真机上就是这样红的）。
 * {@code HttpURLConnection} 两边都有。
 *
 * <p>同一个坑在这一轮里踩了**两次**：主源码用 {@code com.sun.net.httpserver}
 * 也是一样的原因。规律是 **`java.*` 并不都等于「JDK 与 Android 都有」**。
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

    /** 一个极小的响应载体 —— 为了不依赖 {@code java.net.http.HttpResponse}。 */
    private static final class Reply {
        final int status;
        final String body;

        Reply(int status, String body) {
            this.status = status;
            this.body = body;
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

    private Reply request(String method, String path, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setUseCaches(false);
            if (token != null) {
                connection.setRequestProperty("X-Token", token);
            }
            if (!"GET".equals(method)) {
                connection.setDoOutput(true);
                connection.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Reply(status, readAll(stream));
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int got;
            while ((got = in.read(buffer)) > 0) {
                collected.write(buffer, 0, got);
            }
            return new String(collected.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 没有 token 时，连「端点存不存在」都不该被探出来。 */
    @Test
    public void missingTokenIsRejectedBeforeRouting() throws Exception {
        assertEquals(401, request("GET", "/app/version", null).status);
        assertEquals("UNAUTHORIZED", request("GET", "/app/version", "wrong").body);
        assertEquals("鉴权失败时不许碰设备", 0, client.versionCalls + client.dumpCalls);
    }

    @Test
    public void versionIsServedOverRealHttp() throws Exception {
        Reply reply = request("GET", "/app/version", "test-token");
        assertEquals(200, reply.status);
        assertEquals("BRIDGE_PROTOCOL=3", reply.body);
        assertEquals(1, client.versionCalls);
    }

    /**
     * **头条用例（真 HTTP 版）**：读屏默认关 ⇒ 403 + 稳定码，且设备一次没被碰。
     *
     * <p>注意此时连无障碍服务都还没开 —— 双重拒绝，理由以档位为先。
     */
    @Test
    public void screenDumpIsDeniedOverRealHttpWithNoDeviceAccess() throws Exception {
        Reply reply = request("GET", "/app/ui/dump", "test-token");
        assertEquals(403, reply.status);
        assertEquals("TIER_DISABLED", reply.body);
        assertEquals("关着时**一次都不许**碰设备", 0, client.dumpCalls);
    }

    /** 打开 T2 与系统服务后，同一个请求变成 200 —— 证明上面那条拒绝是可撤销的。 */
    @Test
    public void screenDumpBecomesAvailableOnlyAfterBothGatesOpen() throws Exception {
        grants.set(Grants.of(EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T2_ACCESSIBILITY),
                EnumSet.noneOf(Channel.class)));
        accessibilityOn.set(true);

        Reply reply = request("GET", "/app/ui/dump", "test-token");
        assertEquals(200, reply.status);
        assertTrue(reply.body.contains("设置"));
        assertEquals(1, client.dumpCalls);
    }

    /** 非 GET 方法被拒绝 —— 协议里没有定义，拒绝比「当成 GET」安全。 */
    @Test
    public void nonGetMethodsAreRejected() throws Exception {
        assertEquals(405, request("POST", "/app/version", "test-token").status);
        assertEquals(0, client.versionCalls);
    }
}
