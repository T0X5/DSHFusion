package com.dshfusion.app.bridge.security;

import com.dshfusion.app.bridge.security.CapabilityGate.Channel;
import com.dshfusion.app.bridge.security.CapabilityGate.Grants;
import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 授权状态的持久化 —— 阶段 3.2 未决③的落地。
 *
 * <h2>三条规则，每条都能被测试证死</h2>
 *
 * <ol>
 *   <li><b>文件不存在 ⇒ 全新安装 ⇒ {@link Grants#defaults()}</b>。
 *       这是唯一一种「按宽松处理」的情况，因为「从没授权过」本来就是默认状态。</li>
 *   <li><b>其它任何异常 ⇒ {@link Grants#unavailable()}</b>（fail-closed）：
 *       空文件、内容坏掉、版本不认识、出现不认识的档位/通道名、文件里有 T4。</li>
 *   <li><b>写入是「先写临时文件再改名」</b>。直接覆盖会在崩溃时留下半截文件，
 *       而那半截可能**解析成功但少了几项** —— 表现为「用户昨天开的权限今天不见了」，
 *       或者更糟：少了几项**看起来像正常**。</li>
 * </ol>
 *
 * <h2>为什么不认识的档位名要整份拒绝，而不是跳过那一行</h2>
 *
 * 「跳过不认识的」听起来宽容，但它会让**降级**变得危险：新版写入了 T5，
 * 旧版读到后跳过 —— 于是用户在 T5 上的授权**静默消失**，而没人会收到任何提示。
 * 整份拒绝至少会表现为「全都不能用」，那会促使你去查。
 *
 * <p>这条与阶段 3.4 §三「授权状态读不到 / 损坏 ⇒ 拒绝」是同一条规矩。
 *
 * <h2>这个文件必须**不进备份归档**</h2>
 *
 * 授权是**这台设备对这台设备**的信任。跟着归档搬到新设备上，
 * 等于「换台设备恢复」时把旧的信任一起搬过去 —— 那不是恢复数据，那是伪造授权。
 * 具体位置见 {@code DshBackupExclusions}。
 */
public final class GrantStore {

    /** 存档格式版本。读到别的版本一律 fail-closed。 */
    public static final String CURRENT_VERSION = "1";

    /**
     * 授权文件在 DSH home 里的相对位置。
     *
     * <p>放在 `.dsh/` 下而不是别处，是为了让它与桥凭据一样**受同一套排除规则管**：
     * 它们都是「这台机器的本地状态」，不是用户数据。
     */
    public static final String FILE_NAME = ".dsh/.capability-grants";

    private static final String KEY_VERSION = "version";
    private static final String KEY_TIERS = "tiers";
    private static final String KEY_CHANNELS = "channels";

    private GrantStore() {}

    /**
     * 读授权状态。
     *
     * <p>**不抛异常** —— 读盘与解析的失败都必须变成 {@link Grants#unavailable()}，
     * 因为调用方（闸门）只有「可用 / 不可用」两种输入，而把它变成异常会让
     * 某个宽泛的 {@code catch} 有机会把「拒绝」变成「放行」。
     */
    public static Grants load(Path home) {
        Path file = home.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return Grants.defaults();   // 唯一按宽松处理的分支，见类注释。
        }
        try {
            return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            return Grants.unavailable();
        }
    }

    /**
     * 解析文本。**包级可见是为了让测试能直接喂字符串**，不必造文件。
     *
     * <p>任何不合规的输入都返回 {@link Grants#unavailable()}，绝不返回 {@code null}。
     */
    static Grants parse(String text) {
        if (text == null || text.isEmpty()) {
            return Grants.unavailable();
        }
        String version = null;
        String tiers = null;
        String channels = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                return Grants.unavailable();   // 不认识的行 = 文件坏了
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            switch (key) {
                case KEY_VERSION:
                    version = value;
                    break;
                case KEY_TIERS:
                    tiers = value;
                    break;
                case KEY_CHANNELS:
                    channels = value;
                    break;
                default:
                    return Grants.unavailable();   // 不认识的键 = 版本不一致
            }
        }
        if (!CURRENT_VERSION.equals(version) || tiers == null) {
            return Grants.unavailable();
        }

        Set<Tier> tierSet = EnumSet.noneOf(Tier.class);
        for (String name : splitList(tiers)) {
            Tier tier = enumOrNull(Tier.class, name);
            if (tier == null) {
                return Grants.unavailable();      // 不认识的档位名 ⇒ 整份拒绝
            }
            if (tier == Tier.T4_BRIDGE_CREDENTIAL) {
                // T4 不可授予（阶段 3.2 §二）。文件里出现它 ⇒ 这份文件是伪造或损坏的。
                return Grants.unavailable();
            }
            tierSet.add(tier);
        }

        Set<Channel> channelSet = EnumSet.noneOf(Channel.class);
        for (String name : splitList(channels)) {
            Channel channel = enumOrNull(Channel.class, name);
            if (channel == null) {
                return Grants.unavailable();
            }
            channelSet.add(channel);
        }
        return Grants.of(tierSet, channelSet);
    }

    /**
     * 写授权状态。**先写临时文件再改名**（见类注释第 3 条）。
     *
     * @return 写成功为 {@code true}；任何失败为 {@code false}，
     *         且**不留下半截文件**。调用方应当把 {@code false} 当成
     *         「这次授权没保存」告诉用户，而不是当成成功。
     */
    public static boolean save(Path home, Grants grants) {
        if (grants == null) {
            return false;
        }
        Path file = home.resolve(FILE_NAME);
        Path temporary = home.resolve(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.write(temporary, render(grants).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 删不掉临时文件不该让「保存失败」变成异常。
            }
            return false;
        }
    }

    /** 序列化。**只导出档位与通道** —— 其它任何东西都不该进这个文件。 */
    static String render(Grants grants) {
        StringBuilder builder = new StringBuilder();
        builder.append("# DSHFusion 设备能力授权状态 —— 本机本地，不进备份，勿手工编辑\n");
        builder.append(KEY_VERSION).append('=').append(CURRENT_VERSION).append('\n');
        builder.append(KEY_TIERS).append('=').append(joinTiers(grants)).append('\n');
        builder.append(KEY_CHANNELS).append('=').append(joinChannels(grants)).append('\n');
        return builder.toString();
    }

    private static String joinTiers(Grants grants) {
        List<String> names = new ArrayList<>();
        for (Tier tier : Tier.values()) {
            if (tier != Tier.T4_BRIDGE_CREDENTIAL && grants.isOpen(tier)) {
                names.add(tier.name());
            }
        }
        return String.join(",", names);
    }

    private static String joinChannels(Grants grants) {
        List<String> names = new ArrayList<>();
        for (Channel channel : Channel.values()) {
            if (grants.hasChannel(channel)) {
                names.add(channel.name());
            }
        }
        return String.join(",", names);
    }

    private static List<String> splitList(String value) {
        List<String> names = new ArrayList<>();
        if (value == null) {
            return names;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
