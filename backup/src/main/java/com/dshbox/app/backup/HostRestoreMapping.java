// DSHFusion 自写（**不是**从 DSHA 移植）。见 backup/README.md「为什么这个类是 Java」。
package com.dshbox.app.backup;

import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.BackupLimits;
import com.deepseekharness.app.backup.NativeRestorePlan;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把归档里的**逻辑根**映射到本机的真实落点。
 *
 * ## 为什么必须自己写（阶段 2.4b 的只读审计结论）
 *
 * DSHA 的对应实现是 `NativeRestoreTargets`，它**不可复用**，理由有三条，最强的那条
 * 与 `android.*` 无关：
 *
 * 1. **逻辑根词汇根本不同。** 归档里的根名 = {@code BackupSource.id()}
 *    （`HostSnapshot` 的 `record()` 与 `:43` 都写 `source.id()`），而
 *    {@code BackupSource.description()} 只给 `id`/`scope`/`external` 三个键。
 *    DSHA 的 `target()` 第一句是 {@code BackupJson.string(root, "logicalKind")} ——
 *    **我们的根没有这个键，它会直接抛**。DSHA 认的根是 `dsh-home`/`dsh-child`/…，
 *    其中 `dsh-child` 是「`.dsh` 的子条目逐个成根」；我们是「一个 `DshBackupMember`
 *    一个根」。**两套词汇，不是同一套的两个实现。**
 * 2. 目的地路径不同（DSHA 写 `runtime-current/base`、`user-data-v5/dsh`、
 *    `plugin-imports/<task>`；我们的是 `user-data/.dsh`）。
 * 3. 设置钩子不同（DSHA 走 `ConfigStore` = SharedPreferences + Keystore；骨干是 DataStore）。
 *
 * ## 落点从哪来（单一事实来源）
 *
 * guest 的 `/root/projects` 与宿主 `filesDir/user-data` 是 PRoot 的 bind 关系
 * （`SandboxFiles.PathMapper`），而 DSH 的 HOME 是 `/root/projects/.dsh`
 * （`SandboxService.kt:1017`）。所以**宿主侧**的 DSH 家目录就是
 * `filesDir/user-data/.dsh`，成员路径（`.dsh`、`.dsh/sessions`、…）可以直接当
 * **相对 `user-data` 的宿主路径**用 —— 不需要第二套映射表。
 *
 * ## 一个成员一个 group（这是与 DSHA 的一处刻意不同）
 *
 * `NativeRestorePlan` 的 `Target` 支持「多个根共享同一个活动目标」（用 `group` + `prefix`
 * 区分）。DSHA 用它把 `.dsh` 的子条目并进一个 group。**我们不这么做**，因为那要求
 * `read` 指向 group 根，于是**只恢复 sessions 也会把整个 `.dsh` 拷进候选**
 * （包括凭据文件、插件实体、会话库）—— 慢，而且把与本次恢复无关的东西也卷进事务。
 *
 * 改成「一个成员一个 group」之后：`write = read = 成员自己的路径`、`prefix` 为空，
 * 于是
 *
 * - `before` / `readBefore` 是**逐个成员**的摘要 → 只有被动过的那个成员会报
 *   `TARGET_CHANGED`，变更检测更准；
 * - 候选树是 `candidate/&lt;成员 id&gt;/…` → 提交时**每个成员各换各的**，
 *   失败半径从「整个家目录」缩到「一个成员」；
 * - 不再需要 `memberRead`（它是为 group/prefix 那套准备的）。
 */
public final class HostRestoreMapping implements NativeRestorePlan.Mapping {

  /** 归档成员 → 本机落点。 */
  public static final class Member {
    public final String id, path, v5Scope;

    /**
     * @param id 归档里的逻辑根名（= `BackupSource.id()`）
     * @param path 相对 **`user-data`** 的宿主路径（`.dsh`、`.dsh/sessions`、…）
     * @param v5Scope 归档里这些记录应当携带的范围
     */
    public Member(String id, String path, String v5Scope) {
      this.id = id;
      this.path = path;
      this.v5Scope = v5Scope;
    }

    /**
     * 合并子目录 —— **由 [path] 推导，不由调用方重复给一份**。
     *
     * 「同一个事实写两遍」正是本仓库事故表第 17/20 条的成因（测试手抄魔数、
     * 夹具与源码各说各话）。这里干脆让它没有第二个来源：`.dsh` → 空串，
     * `.dsh/x/y` → `x/y`。若将来真需要「恢复到别处」，那是一个新概念，
     * 应当显式加字段，而不是让两处悄悄漂移。
     */
    public String mergeSubdir() {
      return path.equals(GROUP_NAME) ? "" : path.substring(GROUP_NAME.length() + 1);
    }
  }

  /** DSH 家目录相对 `user-data` 的名字。也是成员 path 的公共前缀。 */
  private static final String GROUP_NAME = ".dsh";

  private final File userData, home;
  private final Map<String, Member> byId = new LinkedHashMap<>();

  /**
   * @param userData 宿主 `filesDir/user-data`（guest 的 `/root/projects`）
   * @param members 成员表。**权威来源是 {@code DshBackupScope.members}**，
   *     由 Kotlin 侧在运行时传进来；本类不复制那份表。
   *     `tools/check-backup-contract.py` 核验两边一致。
   */
  public HostRestoreMapping(File userData, List<Member> members) throws IOException {
    if (userData == null) throw new IOException("NO_USER_DATA");
    if (members == null || members.isEmpty()) throw new IOException("NO_MEMBERS");
    this.userData = userData.getCanonicalFile();
    this.home = new File(this.userData, GROUP_NAME);
    for (Member member : members) {
      BackupLimits.root(member.id);
      BackupLimits.path(member.path);
      if (!member.path.equals(GROUP_NAME) && !member.path.startsWith(GROUP_NAME + "/"))
        throw new IOException("MEMBER_OUTSIDE_DSH");
      if (member.v5Scope == null || member.v5Scope.isEmpty()) throw new IOException("MEMBER_SCOPE");
      File resolved = resolve(member.path);
      // 落点必须在 user-data 之内。成员表是编译期常量，但它是**数据**：
      // 一个手误的 `../` 会让恢复写到别人的目录里，这种错必须在构造时就炸，
      // 而不是等它把文件写出去。
      if (!within(this.userData, resolved)) throw new IOException("TARGET_OUTSIDE_APP");
      if (byId.put(member.id, member) != null) throw new IOException("DUPLICATE_MEMBER");
    }
  }

  /** 本机 DSH 家目录（`user-data/.dsh`）。 */
  public File home() {
    return home;
  }

  /** 某个成员的本机路径；不在表里返回 null。 */
  public File member(String id) {
    Member member = byId.get(id);
    return member == null ? null : resolve(member.path);
  }

  private File resolve(String path) {
    return new File(userData, path);
  }

  @Override
  public NativeRestorePlan.Target target(Map<String, Object> root) throws IOException {
    String id = BackupLimits.root(BackupJson.string(root, "id"));
    String scope = BackupJson.string(root, "scope");
    Member member = byId.get(id);
    // 认不出的根**不是**错误：归档可能来自更新的版本，或来自别的范围。
    // 返回 null 让调用方按「跳过并记原因」处理，绝不猜一个落点。
    if (member == null) return null;
    // 但认得出根、范围却对不上就是**错误**：那说明这份归档要么不是这个范围打的，
    // 要么被人改过。两种情况都不该继续恢复。
    if (!member.v5Scope.equals(scope)) throw new IOException("ROOT_SCOPE");
    File path = resolve(member.path);
    if (!within(userData, path)) throw new IOException("TARGET_OUTSIDE_APP");
    // 🔴 **`merge` 不能写死。**
    //
    // 它原来是硬编码的 `true`，而 `NativeRestorePlan` 里 `merge=true` 的语义是：
    //
    //     if (target.merge && target.read != null && !fs.stat(target.read).type.equals("MISSING"))
    //       copyTree(target.read, fs.child(candidate, root.getKey()), control, 0, copied);
    //
    // 也就是**先把现有的整棵树拷进候选，再把归档覆盖上去** ——
    // 于是「归档里没有的文件」原样保留，**全量恢复永远删不掉任何东西**。
    //
    // 真机上就是这么暴露的：用户建了会话 C → 导出（C 不在归档里）→ 恢复 →
    // 界面报「已恢复：1」，而 C **还在**。用户以为恢复没生效，其实恢复生效了，
    // 只是它做的是「合并」而不是「替换」。
    //
    // 规则：**根就是整个 home（`.dsh`）→ 替换；根是 home 里的某棵子树 → 合并。**
    // 前者是「把家恢复成那份归档的样子」，后者是「只换掉这一部分，别动其它」。
    boolean wholeHome = member.path.equals(GROUP_NAME);
    return new NativeRestorePlan.Target(path, path, !wholeHome, member.id, "", null);
  }

  @Override
  public String skippedReason(Map<String, Object> root) {
    // 给一个**具体**的原因，而不是默认的 ROOT_REQUIRES_DESTINATION ——
    // 「这个根不在我的成员表里」与「这个根需要用户先选目的地」是两件事，
    // 混成一个码会让界面说错话。
    return "ROOT_NOT_IN_MEMBER_TABLE";
  }

  /** 词法包含判断（不解析链接）。DSHA 的那份是包内可见，跨包用不了，所以自带一份。 */
  private static boolean within(File base, File file) {
    String root = base.getAbsolutePath(), path = file.getAbsolutePath();
    return path.equals(root) || path.startsWith(root + File.separator);
  }
}
