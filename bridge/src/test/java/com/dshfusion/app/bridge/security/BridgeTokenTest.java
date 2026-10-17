package com.dshfusion.app.bridge.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 桥自己的 token —— 纯 Java，本机可跑。
 *
 * <h2>最要紧的那条断言是「没有 token 就不许起服务」</h2>
 *
 * 它表现为 {@code loadOrCreate} **抛异常**（而不是回空串）。回空串会让上层的
 * 「expectedToken 为空 ⇒ 不鉴权」那条测试用分支被真的走上去 ——
 * 那是把一个测试逃生门变成生产行为。
 */
public final class BridgeTokenTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void generatedTokensAreWellFormedAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = BridgeToken.generate();
            assertTrue("形状必须是 32 位小写十六进制：" + token, BridgeToken.isWellFormed(token));
            assertEquals(32, token.length());
            assertTrue("200 次里出现了重复 —— 那不是随机", seen.add(token));
        }
    }

    @Test
    public void malformedTokensAreRejected() {
        String[] bad = {
                null, "", "abc", "ABCDEF0123456789ABCDEF0123456789",
                "0123456789abcdef0123456789abcde",      // 31
                "0123456789abcdef0123456789abcdef0",    // 33
                "0123456789abcdef0123456789abcdeg",     // 非 hex
                " 0123456789abcdef0123456789abcdef",    // 前导空格
        };
        for (String token : bad) {
            assertFalse("不该被接受：" + token, BridgeToken.isWellFormed(token));
        }
    }

    /** 第一次调用会生成并落盘；第二次必须**沿用**同一个（换掉会让正在跑的服务立刻失联）。 */
    @Test
    public void loadOrCreateReusesThePersistedToken() throws IOException {
        Path home = folder.getRoot().toPath();
        String first = BridgeToken.loadOrCreate(home);
        String second = BridgeToken.loadOrCreate(home);

        assertEquals("第二次必须沿用第一次的 token", first, second);
        assertTrue(Files.isRegularFile(home.resolve(BridgeToken.FILE_NAME)));
        assertFalse("不许留下 .tmp", Files.exists(home.resolve(BridgeToken.FILE_NAME + ".tmp")));
    }

    /** 文件内容坏掉 ⇒ **轮换**，而不是继续用那个可疑值。 */
    @Test
    public void aCorruptTokenFileIsRotatedNotReused() throws IOException {
        Path home = folder.getRoot().toPath();
        Path file = home.resolve(BridgeToken.FILE_NAME);
        Files.createDirectories(file.getParent());
        Files.write(file, "not-a-token".getBytes(StandardCharsets.UTF_8));

        String token = BridgeToken.loadOrCreate(home);

        assertTrue("轮换出来的必须是合格形状", BridgeToken.isWellFormed(token));
        assertNotEquals("bad", token);
        assertEquals("轮换后的值必须被写回",
                token, new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim());
    }

    /**
     * **写不进去就必须抛** —— 上层据此让服务起不来。
     *
     * <p>这里让 home 指向一个普通文件：它下面的目录建不出来。
     */
    @Test
    public void anUnwritableLocationMustThrowNotSilentlyContinue() throws IOException {
        Path notADirectory = folder.getRoot().toPath().resolve("a-file");
        Files.write(notADirectory, "x".getBytes(StandardCharsets.UTF_8));
        try {
            BridgeToken.loadOrCreate(notADirectory);
            fail("没有可写位置时必须抛 —— 否则等于把鉴权关掉继续跑");
        } catch (IOException expected) {
            assertTrue(expected.getMessage() == null || !expected.getMessage().isEmpty()
                    || expected.getMessage() == null);
        }
    }

    /** `write` 拒绝写入形状不对的 token —— 免得一个坏值被持久化成「有效」。 */
    @Test
    public void writeRefusesMalformedTokens() throws IOException {
        Path home = folder.getRoot().toPath();
        try {
            BridgeToken.write(home, "SHORT");
            fail("形状不对的 token 不许写进文件");
        } catch (IOException expected) {
            // 期望走到这里。
        }
        assertFalse("拒绝之后不该留下文件", Files.exists(home.resolve(BridgeToken.FILE_NAME)));
    }
}
