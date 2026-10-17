package com.dshfusion.app.bridge.server;

import com.dshfusion.app.bridge.security.CapabilityAuthorizer;
import com.dshfusion.app.bridge.security.CapabilityGate;
import com.dshfusion.app.bridge.security.CapabilityGate.DenyCode;
import com.dshfusion.app.bridge.security.CapabilityGate.Grants;
import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.util.Objects;

/**
 * 设备能力端点的**分发器**：路由 → 门禁 → 调用设备 / 或拒绝。
 *
 * <h2>它是纯 Java 的理由</h2>
 *
 * 这一段决定「关着的时候会不会碰到设备」。它一行 Android 都不用，
 * 所以能在本机**用真 HTTP 请求**验（见 {@code DeviceBridgeHttpServer}），
 * 不必等真机。本机有 JDK 17、没有 kotlinc（docs/INCIDENTS.md 第 21 条）。
 *
 * <h2>核心不变量：被拒时**设备一次都不会被碰**</h2>
 *
 * {@link #dispatch} 的所有拒绝分支都在**调用 {@link DeviceClient} 之前**返回。
 * 这条不靠注释保证 —— 测试里用的实现**一被调用就抛异常**，
 * 所以「被拒时碰了设备」会以异常的形式炸出来，而不是悄悄多读了一次屏。
 *
 * <h2>拒绝码是契约</h2>
 *
 * 每个 {@link DenyCode} 是稳定标识符，会被写进 3.3 的契约表
 * （「关着时返回什么」那一列）。**人类可读的说明另给，且随时可以改。**
 */
public final class DeviceEndpointDispatcher {

    /** 一次请求的结论。**被拒时 {@code body} 里不含任何设备数据。** */
    public static final class Response {
        private final int status;
        private final String body;
        private final DenyCode code;

        private Response(int status, String body, DenyCode code) {
            this.status = status;
            this.body = body;
            this.code = code;
        }

        public int status() {
            return status;
        }

        /** 响应正文。拒绝时是**稳定的错误标识**，不是给人看的散文。 */
        public String body() {
            return body;
        }

        /** 被拒时的码；放行时为 {@code null}。 */
        public DenyCode code() {
            return code;
        }

        @Override
        public String toString() {
            return status + (code == null ? " ok" : " " + code);
        }
    }

    private final DeviceClient client;

    public DeviceEndpointDispatcher(DeviceClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * 处理一次请求。
     *
     * @param originTrusted         来源门（判定它需要 {@code android.net.Uri}，所以由调用方给结论）
     * @param path                  原始路径，**可带查询串**
     * @param accessibilityServiceOn T2 专用：系统里的无障碍服务是否开着
     * @param grants                档位授权状态
     */
    public Response dispatch(boolean originTrusted, String path,
                             boolean accessibilityServiceOn, Grants grants) {
        // ① 未知端点：先拒绝，且**不泄露「哪些端点是存在的」**给不受信任的来源。
        Tier tier = DeviceEndpointTable.tierFor(path);
        if (tier == null) {
            DenyCode code = originTrusted ? DenyCode.UNKNOWN_CAPABILITY : DenyCode.ORIGIN_NOT_TRUSTED;
            return denied(code);
        }

        // ② 门禁。**这一段之后才允许碰设备。**
        CapabilityAuthorizer.Outcome outcome =
                CapabilityAuthorizer.authorize(originTrusted, tier, accessibilityServiceOn, grants);
        if (!outcome.allowed()) {
            return denied(outcome.code());
        }

        // ③ 到这里才碰设备。
        String bare = barePath(path);
        String payload;
        try {
            payload = "/app/version".equals(bare) ? client.version() : client.uiDump();
        } catch (RuntimeException error) {
            // 设备侧失败也要有稳定的码，且**不把上游的异常文本原样透出去**
            // （它可能带有路径、包名等本机信息）。
            return new Response(503, "DEVICE_UNAVAILABLE", DenyCode.AUTHORIZATION_UNAVAILABLE);
        }
        if (payload == null) {
            return new Response(503, "DEVICE_UNAVAILABLE", DenyCode.AUTHORIZATION_UNAVAILABLE);
        }
        return new Response(200, payload, null);
    }

    private static Response denied(DenyCode code) {
        // 403 而不是 404：**端点存在，但这次不给。** 用 404 会让「关着」与「没有这个端点」
        // 长得一模一样，而这两件事对排查很不一样。
        return new Response(403, code.name(), code);
    }

    private static String barePath(String path) {
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
    }
}
