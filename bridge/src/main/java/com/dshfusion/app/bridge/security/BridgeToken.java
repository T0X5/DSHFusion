package com.dshfusion.app.bridge.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

/**
 * 设备能力桥**自己的**鉴权 token。
 *
 * <h2>为什么不是复用上游那个 token</h2>
 *
 * 因为**那是别人的凭据**。上游的 `.bridge_token` 属于上游那座桥；
 * 拿它当我们的桥的鉴权，等于两座桥共用一把钥匙 ——
 * 一处泄露两处失守，而且「谁授权的」变得说不清。
 *
 * <h2>三条规则</h2>
 *
 * <ol>
 *   <li><b>没有 token 就不许起服务。</b>{@link #loadOrCreate} 在无法持久化时**抛异常**，
 *       调用方必须让服务起不来 —— 而不是「鉴权关掉继续跑」。
 *       上游的 `/app/help` 里写过「凭据由宿主写入」；我们这边就是这一步。</li>
 *   <li><b>权限 0600。</b>只在 POSIX 文件系统上设置；设不上不算失败（有些卷不支持），
 *       但要**尽力**。</li>
 *   <li><b>永不写日志、永不进 URL。</b>本类不提供 {@code toString()}，
 *       也不返回任何「带 token 的地址」—— 调用方只能拿到裸字符串，自己决定放哪。</li>
 * </ol>
 *
 * <h2>为什么 32 个十六进制字符</h2>
 *
 * 与上游同形（32 字节的随机性折成 64 位不够，所以取 **16 字节 → 32 hex**，
 * 128 位）。这个长度足以抵抗穷举，又短到能放进请求头。
 */
public final class BridgeToken {

    /** token 文件在 DSH home 里的相对位置。**与上游同名**，但内容是我们自己的。 */
    public static final String FILE_NAME = ".dsh/.device-bridge-token";

    /** 16 字节 → 32 个十六进制字符。 */
    static final int TOKEN_LENGTH = 32;

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private BridgeToken() {}

    /** 生成一个新 token。**只产生字符串，不落盘、不记日志。** */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_LENGTH / 2];
        RANDOM.nextBytes(bytes);
        // 手写十六进制而**不用 `java.util.HexFormat`** —— 后者是 Java 17 的 API，
        // Android 没有它（事故第 49 条：`java.*` 并不都等于「JDK 与 Android 都有」）。
        StringBuilder builder = new StringBuilder(TOKEN_LENGTH);
        for (byte b : bytes) {
            builder.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return builder.toString();
    }

    /**
     * 读已有 token；没有就生成并**先写临时文件再改名**地落盘。
     *
     * @throws IOException 读不了、或写不进去。**调用方必须因此让服务起不来** ——
     *                     「没 token 也能跑」等于把鉴权关掉。
     */
    public static String loadOrCreate(Path home) throws IOException {
        Path file = home.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            String existing = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (isWellFormed(existing)) {
                return existing;
            }
            // 内容坏了：**轮换**而不是继续用。与上游 `ensureToken()` 同一个取舍，
            // 但这里不「沿用可疑值」—— 一个被截断的 token 会让鉴权随机地时通时不通。
        }
        String token = generate();
        write(home, token);
        return token;
    }

    /** 覆盖写入。先写 `.tmp` 再改名，避免半截文件被当成有效 token。 */
    static void write(Path home, String token) throws IOException {
        if (!isWellFormed(token)) {
            throw new IOException("BRIDGE_TOKEN_MALFORMED");
        }
        Path file = home.resolve(FILE_NAME);
        Path temporary = home.resolve(FILE_NAME + ".tmp");
        Files.createDirectories(file.getParent());
        Files.write(temporary, token.getBytes(StandardCharsets.UTF_8));
        restrictToOwner(temporary);
        try {
            Files.move(temporary, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictToOwner(file);
    }

    /** 形状校验：**只接受小写十六进制且长度正确**。大小写混用在比对时会出岔子。 */
    static boolean isWellFormed(String token) {
        if (token == null || token.length() != TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static void restrictToOwner(Path file) {
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, ownerOnly);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 有些卷不支持 POSIX 权限。**尽力而为**，不因此让调用失败 ——
            // 但这条「没设上」不构成「安全」的证据，所以不写进任何断言。
        }
    }
}
