package com.dshfusion.app.bridge.server;

import com.dshfusion.app.bridge.security.CapabilityGate.Grants;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 把 {@link DeviceEndpointDispatcher} 接到真 HTTP 上。
 *
 * <h2>为什么手写一个极小的 HTTP 循环，而不是用现成的</h2>
 *
 * {@code com.sun.net.httpserver} **在 Android 上不存在** —— 它是 JDK 的东西。
 * 而 {@code :bridge} 是 Android 库，主源码集必须能编过，所以那样写会在 CI 直接红。
 *
 * <p>{@code java.net.ServerSocket} 两边都有，所以这里只实现**协议里用到的那一点**：
 * 只认 {@code GET}、按 {@code Content-Length} 回、{@code Connection: close}。
 * 够用，且**不需要任何依赖** —— 于是门禁行为可以用真 HTTP 请求在本机验。
 *
 * <h2>只绑回环地址</h2>
 *
 * 绑 {@code 127.0.0.1}。理由不是「别人一般连不上」，而是：
 * **设备能力层永远不该被局域网访问，所以它连监听都不该出现在可路由的地址上。**
 *
 * <h2>鉴权在路由之前</h2>
 *
 * 没有 token（或 token 不对）返回 {@code 401}，**在解析路径之前** ——
 * 否则「哪些端点存在」会被未鉴权的调用方探出来。
 *
 * <h2>它**不是**生产用的 HTTP 服务器</h2>
 *
 * 没有 keep-alive、没有分块、没有并发调优、没有 TLS。它的用途是
 * **让门禁行为可被真请求验证**，以及在真机上做最小可用面。
 * 将来若要更高要求，换实现而不换 {@link DeviceEndpointDispatcher} —— 门禁逻辑不在这一层。
 */
public final class DeviceBridgeHttpServer {

    /** 服务器要从外部读到的四件东西 —— 全部由调用方给，服务器自己不猜。 */
    public interface Environment {
        /** 来源门是否通过（判定它需要 {@code android.net.Uri}）。 */
        boolean originTrusted();

        /** 系统里的无障碍服务是否开着（T2 专用）。 */
        boolean accessibilityServiceOn();

        /** 档位授权状态。 */
        Grants grants();

        /** 期望的 token；空串表示**不鉴权**（仅限测试与离线调试）。 */
        String expectedToken();
    }

    private static final byte[] TERMINATOR = {'\r', '\n', '\r', '\n'};
    private static final int MAX_REQUEST_HEAD = 16 * 1024;

    private final DeviceEndpointDispatcher dispatcher;
    private final Environment environment;

    private ServerSocket server;
    private Thread loop;
    private volatile boolean running;

    public DeviceBridgeHttpServer(DeviceEndpointDispatcher dispatcher, Environment environment) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** 在回环地址上起服务；{@code port == 0} 表示让系统挑一个。返回实际端口。 */
    public int start(int port) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        running = true;
        loop = new Thread(this::acceptLoop, "device-bridge-accept");
        loop.setDaemon(true);
        loop.start();
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 关不掉也不该抛 —— 调用方正在退出。
            }
            server = null;
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException error) {
                return;   // close() 会让 accept 抛；正常退出走这里。
            }
            try {
                socket.setSoTimeout(10_000);
                serve(socket);
            } catch (IOException | RuntimeException ignored) {
                // 单个连接出问题不影响下一个。
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 同上。
                }
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        String head = readHead(in);
        if (head == null) {
            return;
        }
        String[] lines = head.split("\r\n");
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            write(socket.getOutputStream(), 400, "BAD_REQUEST");
            return;
        }
        String method = requestLine[0].toUpperCase(Locale.ROOT);
        String path = requestLine[1];

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }

        // ① 鉴权**先于**路由。
        String expected = environment.expectedToken();
        if (expected != null && !expected.isEmpty()
                && !expected.equals(headers.get("x-token"))) {
            write(socket.getOutputStream(), 401, "UNAUTHORIZED");
            return;
        }

        // ② 只认 GET。
        if (!"GET".equals(method)) {
            write(socket.getOutputStream(), 405, "METHOD_NOT_ALLOWED");
            return;
        }

        // ③ 门禁 + 分发。
        DeviceEndpointDispatcher.Response response = dispatcher.dispatch(
                environment.originTrusted(),
                path,
                environment.accessibilityServiceOn(),
                environment.grants());
        write(socket.getOutputStream(), response.status(), response.body());
    }

    /** 读到空行为止；返回整个头（不含分隔空行）。超上限或对端关闭返回 {@code null}。 */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        int matched = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            collected.write(b);
            if (collected.size() > MAX_REQUEST_HEAD) {
                return null;
            }
            if (b == '\r' || b == '\n') {
                matched = advance(matched, (byte) b);
            } else {
                matched = 0;
            }
            if (matched == TERMINATOR.length) {
                String head = new String(collected.toByteArray(), StandardCharsets.UTF_8);
                return head.substring(0, head.length() - TERMINATOR.length);
            }
        }
    }

    private static int advance(int matched, byte b) {
        if (b == TERMINATOR[matched]) {
            return matched + 1;
        }
        return b == TERMINATOR[0] ? 1 : 0;
    }

    private static void write(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default:  return "Status";
        }
    }
}
