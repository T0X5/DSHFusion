package com.dshfusion.app.bridge.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dshfusion.app.bridge.security.CapabilityGate.Channel;
import com.dshfusion.app.bridge.security.CapabilityGate.Decision;
import com.dshfusion.app.bridge.security.CapabilityGate.DenyCode;
import com.dshfusion.app.bridge.security.CapabilityGate.Grants;
import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 授权状态读写的断言 —— 纯 Java，本机可跑。
 *
 * <p>观察手段优先走 {@link CapabilityGate}（`load` 出来的东西拿去裁决），
 * 而不是直接读 {@code Grants} 的内部字段 —— 因为「读到了什么」不重要，
 * **「因此放行了什么」才重要**。
 */
public final class GrantStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static DenyCode codeOf(Decision decision) {
        assertFalse("期望拒绝", decision.allowed());
        return ((CapabilityGate.Denial) decision).code();
    }

    /** 文件不存在 = 全新安装 = 默认状态（T0/T1 开、T2 关）。 */
    @Test
    public void missingFileMeansFreshInstallNotUnavailable() {
        Path home = folder.getRoot().toPath();
        CapabilityGate gate = new CapabilityGate(GrantStore.load(home));

        assertTrue("全新安装时 T0 应当放行", gate.decide(
                CapabilityGate.Request.of(Tier.T0_UNCONDITIONAL)).allowed());
        assertEquals("T2 在全新安装时必须关着", DenyCode.TIER_DISABLED,
                codeOf(gate.decide(CapabilityGate.Request.accessibility(true))));
    }

    /** 存下去再读回来，结论要一致。 */
    @Test
    public void savedGrantsSurviveARoundTrip() throws IOException {
        Path home = folder.getRoot().toPath();
        Grants saved = Grants.of(
                EnumSet.of(Tier.T0_UNCONDITIONAL, Tier.T2_ACCESSIBILITY, Tier.T3_PRIVILEGED),
                EnumSet.of(Channel.SHIZUKU));

        assertTrue("保存应当成功", GrantStore.save(home, saved));
        CapabilityGate gate = new CapabilityGate(GrantStore.load(home));

        assertTrue("T2 已授权 + 系统服务已开 ⇒ 放行",
                gate.decide(CapabilityGate.Request.accessibility(true)).allowed());
        assertTrue("Shizuku 已授权 ⇒ 放行",
                gate.decide(CapabilityGate.Request.privileged(Channel.SHIZUKU)).allowed());
        assertEquals("没授权的通道仍必须拒绝", DenyCode.CHANNEL_NOT_GRANTED,
                codeOf(gate.decide(CapabilityGate.Request.privileged(Channel.ROOT))));
    }

    /**
     * 损坏的文件一律 fail-closed —— **连 T0 都不放行**。
     *
     * <p>逐条列出坏法，而不是只测一种：这套格式的每一种坏法都会走到解析器的
     * 不同分支，只测一种等于只测了一条分支。
     */
    @Test
    public void everyKindOfCorruptionFailsClosed() {
        String[] broken = {
                "",                                            // 空文件（保存失败留下的）
                "这是人话，不是键值对\n",                       // 完全没有结构
                "version=1\n",                                 // 缺 tiers
                "version=9\ntiers=T0_UNCONDITIONAL\n",         // 版本不认识
                "version=1\ntiers=T9_FUTURE_TIER\n",           // 档位名不认识
                "version=1\ntiers=SHIZUKU\n",                  // 档位位放了个通道名
                "version=1\ntiers=T4_BRIDGE_CREDENTIAL\n",     // T4 不可授予 ⇒ 文件是伪造的
                "version=1\ntiers=T0_UNCONDITIONAL\nchannels=ROOT,FAKE\n",
                "version=1\ntiers=T0_UNCONDITIONAL\ncolour=red\n",  // 不认识的键
                "version=1\ntiers=T0_UNCONDITIONAL\nthis line has no equals\n",
        };
        for (String text : broken) {
            CapabilityGate gate = new CapabilityGate(GrantStore.parse(text));
            assertEquals("坏输入必须 fail-closed：" + text.replace("\n", "\\n"),
                    DenyCode.AUTHORIZATION_UNAVAILABLE,
                    codeOf(gate.decide(CapabilityGate.Request.of(Tier.T0_UNCONDITIONAL))));
        }
    }

    /** 保存成功后**不许留下临时文件** —— 残留的 .tmp 会在下次保存时被误读或误删。 */
    @Test
    public void saveLeavesNoTemporaryFileBehind() throws IOException {
        Path home = folder.getRoot().toPath();
        assertTrue(GrantStore.save(home, Grants.defaults()));

        assertTrue("正式文件应当存在", Files.exists(home.resolve(GrantStore.FILE_NAME)));
        assertFalse("不许留下 .tmp",
                Files.exists(home.resolve(GrantStore.FILE_NAME + ".tmp")));
    }

    /** 保存失败必须回 {@code false}，且不许抛 —— 调用方要能把它如实告诉用户。 */
    @Test
    public void aFailedSaveReturnsFalseInsteadOfThrowing() throws IOException {
        // 让 home 指向一个**普通文件**：它下面的目录建不出来。
        Path notADirectory = folder.getRoot().toPath().resolve("a-file");
        Files.write(notADirectory, "x".getBytes(StandardCharsets.UTF_8));

        assertFalse("写不进去时必须回 false", GrantStore.save(notADirectory, Grants.defaults()));
    }

    /** 序列化出来的文本里**不许出现 T4** —— 它不可授予，也就不该被写进任何存档。 */
    @Test
    public void renderedTextNeverContainsTheCredentialTier() {
        String text = GrantStore.render(Grants.of(EnumSet.allOf(Tier.class),
                EnumSet.allOf(Channel.class)));
        assertFalse("T4 不可授予，不许出现在存档里", text.contains("T4_BRIDGE_CREDENTIAL"));
    }
}
