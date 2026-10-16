// DSHFusion 自写（**不是**从 DSHA 移植）。见 backup/README.md「为什么这个类是 Java」。
package com.dshfusion.app.backup;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.FileBackupSource;
import com.deepseekharness.app.backup.HostSnapshot;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.backup.NativeRestorePlan;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 阶段 2.4b 的落点：逻辑根 → 本机路径。
 *
 * 这里最重要的两条不是「字段对不对」，而是：
 *
 * 1. **端到端往返真的通** —— 用我们**自己的** `HostSnapshot` 打出一份归档，再用
 *    **我们自己的** mapping 预检并构建候选。这是 2.1 → 2.4a → 2.4b 第一次串起来跑。
 *    「两个模块各自的单测都过」证明不了它们能对接（事故表第 17 条的形态）。
 * 2. **活动目录在两次操作之间被改过时，恢复会拒绝** —— 这是「会毁数据的那条路径」
 *    最基本的自我保护，而且它是**注入出来的**，不是读出来的。
 */
public class HostRestoreMappingTest {

  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  /**
   * 与 `DshBackupScope.members` 一一对应。
   *
   * **这份表不是权威来源** —— 权威是 Kotlin 侧的 `DshBackupScope`，运行时由它传进来。
   * 这里复制一份是为了让本机（没有 kotlinc）能真跑。两边一旦不一致，
   * `tools/check-backup-contract.py` 会判失败（它有专门的断言盯着这张表）。
   */
  private static final List<HostRestoreMapping.Member> MEMBERS =
      List.of(
          new HostRestoreMapping.Member("dsh", ".dsh", "application"),
          new HostRestoreMapping.Member("dsh-sessions", ".dsh/sessions", "sessions"),
          new HostRestoreMapping.Member("dsh-storages", ".dsh/storages", "sessions"),
          new HostRestoreMapping.Member("dsh-attachments", ".dsh/attachments", "sessions"),
          new HostRestoreMapping.Member("dsh-settings", ".dsh/settings.yaml", "settings"),
          new HostRestoreMapping.Member("dsh-profiles", ".dsh/profiles", "plugins"));

  // ---------- 成员表本身 ----------

  @Test
  public void mergeSubdirIsDerivedFromPathNotGivenTwice() throws Exception {
    assertEquals("", MEMBERS.get(0).mergeSubdir());
    assertEquals("sessions", MEMBERS.get(1).mergeSubdir());
    assertEquals("attachments", MEMBERS.get(3).mergeSubdir());
    // 带扩展名的成员也一样：它就是「去掉 .dsh/ 前缀」。
    assertEquals("settings.yaml", MEMBERS.get(4).mergeSubdir());
  }

  @Test
  public void memberOutsideDshHomeIsRejectedAtConstruction() throws Exception {
    File userData = temporary.newFolder();
    IOException outside =
        assertThrows(
            IOException.class,
            () ->
                new HostRestoreMapping(
                    userData, List.of(new HostRestoreMapping.Member("x", "other/y", "sessions"))));
    assertEquals("MEMBER_OUTSIDE_DSH", outside.getMessage());
  }

  @Test
  public void memberPathWithDotDotIsRejectedBeforeItCanEscape() throws Exception {
    File userData = temporary.newFolder();
    // 成员表是编译期常量，但它是**数据**。一个手误的 `..` 会让恢复写到别人的目录里。
    assertThrows(
        IOException.class,
        () ->
            new HostRestoreMapping(
                userData, List.of(new HostRestoreMapping.Member("x", ".dsh/../../evil", "sessions"))));
  }

  @Test
  public void emptyMemberTableIsRejected() throws Exception {
    File userData = temporary.newFolder();
    assertThrows(
        IOException.class, () -> new HostRestoreMapping(userData, Collections.emptyList()));
  }

  @Test
  public void duplicateMemberIdIsRejected() throws Exception {
    File userData = temporary.newFolder();
    IOException duplicate =
        assertThrows(
            IOException.class,
            () ->
                new HostRestoreMapping(
                    userData,
                    List.of(
                        new HostRestoreMapping.Member("dsh", ".dsh", "application"),
                        new HostRestoreMapping.Member("dsh", ".dsh/sessions", "sessions"))));
    assertEquals("DUPLICATE_MEMBER", duplicate.getMessage());
  }

  // ---------- target() 的三条判定 ----------

  @Test
  public void unknownRootIsSkippedNotGuessed() throws Exception {
    HostRestoreMapping mapping = new HostRestoreMapping(temporary.newFolder(), MEMBERS);
    Map<String, Object> root = new HashMap<>();
    root.put("id", "dsh-something-newer");
    root.put("scope", "sessions");
    // 认不出**不是**错误：归档可能来自更新的版本。绝不能猜一个落点。
    assertNull(mapping.target(root));
    assertEquals("ROOT_NOT_IN_MEMBER_TABLE", mapping.skippedReason(root));
  }

  @Test
  public void knownRootWithWrongScopeIsAnErrorNotASkip() throws Exception {
    HostRestoreMapping mapping = new HostRestoreMapping(temporary.newFolder(), MEMBERS);
    Map<String, Object> root = new HashMap<>();
    root.put("id", "dsh-sessions");
    root.put("scope", "settings");
    IOException wrong =
        assertThrows(IOException.class, () -> mapping.target(root));
    assertEquals("ROOT_SCOPE", wrong.getMessage());
  }

  @Test
  public void targetIsTheMemberItselfInItsOwnGroup() throws Exception {
    File userData = temporary.newFolder();
    HostRestoreMapping mapping = new HostRestoreMapping(userData, MEMBERS);
    Map<String, Object> root = new HashMap<>();
    root.put("id", "dsh-sessions");
    root.put("scope", "sessions");
    NativeRestorePlan.Target target = mapping.target(root);
    File expected = new File(userData.getCanonicalFile(), ".dsh/sessions");
    assertEquals(expected, target.write);
    assertEquals(expected, target.read);
    assertTrue(target.merge);
    // 一个成员一个 group：这样 before/readBefore 是**成员级**摘要，提交半径也小。
    assertEquals("dsh-sessions", target.group);
    assertEquals("", target.prefix);
    assertNull(target.memberRead);
  }

  // ---------- 端到端往返（2.1 → 2.4a → 2.4b 第一次串起来） ----------

  @Test
  public void roundTripPlansAndBuildsCandidateTreeForRepositorySessionData() throws Exception {
    File userData = temporary.newFolder();
    seed(userData);
    File task = temporary.newFolder(), archive = new File(task, "archive");

    // ① 用我们自己的写入侧打归档（模拟「只备份会话」）。
    FileBackupSource sessions =
        new FileBackupSource(
            fs, "dsh-sessions", "sessions", new File(userData, ".dsh/sessions"), false, null);
    Map<String, Object> manifest =
        HostSnapshot.create(
            fs, List.of(sessions), task, archive, provenance(), false, new BackupControl(null));
    assertEquals("QUIESCENT", manifest.get("integrity"));

    // ② 用我们自己的 mapping 预检并构建候选。
    HostRestoreMapping mapping = new HostRestoreMapping(userData, MEMBERS);
    File restore = temporary.newFolder();
    NativeRestorePlan plan =
        NativeRestorePlan.inspect(
            fs, restore, archive, mapping, Set.of("sessions"), new BackupControl(null));

    // 只有被选中的那个成员有落点；settings 不在范围里，必须不在计划里。
    assertTrue(plan.targets.containsKey("dsh-sessions"));
    assertFalse(plan.targets.containsKey("dsh-settings"));

    List<String> groups = plan.buildCandidates(true, new BackupControl(null));
    assertEquals(List.of("dsh-sessions"), groups);

    // ③ 候选树里的字节必须与源一致 —— 而且位置是 candidate/<成员 id>/<相对路径>。
    File candidate = new File(restore, "candidate/dsh-sessions");
    assertEquals(
        "{\"session\":1}",
        new String(
            Files.readAllBytes(new File(candidate, "a.json").toPath()), StandardCharsets.UTF_8));
    assertEquals(
        "deep",
        new String(
            Files.readAllBytes(new File(candidate, "nested/b.txt").toPath()),
            StandardCharsets.UTF_8));
  }

  @Test
  public void mergeKeepsUnarchivedMembersAliveInTheCandidate() throws Exception {
    File userData = temporary.newFolder();
    seed(userData);
    File task = temporary.newFolder(), archive = new File(task, "archive");
    FileBackupSource sessions =
        new FileBackupSource(
            fs, "dsh-sessions", "sessions", new File(userData, ".dsh/sessions"), false, null);
    HostSnapshot.create(fs, List.of(sessions), task, archive, provenance(), false, new BackupControl(null));

    HostRestoreMapping mapping = new HostRestoreMapping(userData, MEMBERS);
    File restore = temporary.newFolder();
    NativeRestorePlan plan =
        NativeRestorePlan.inspect(
            fs, restore, archive, mapping, Set.of("sessions"), new BackupControl(null));
    plan.buildCandidates(true, new BackupControl(null));

    File candidate = new File(restore, "candidate/dsh-sessions");
    // 这条是「恢复后东西没回来」的反面：源里存在、且**不在**归档里的文件（例如
    // 本地产生但没进备份的目录）在合并语义下必须仍然在候选树里。
    assertTrue(new File(candidate, "local-only").isDirectory());
    // .dsh 里的其它成员（settings.yaml / keep.txt）**不能**被卷进这次候选 ——
    // 这正是「一个成员一个 group」相对「整个 .dsh 一个 group」的好处。
    assertEquals(
        Set.of("dsh-sessions"), Set.copyOf(plan.groups.keySet()));
  }

  @Test
  public void targetChangedBetweenInspectionAndBuildIsRefused() throws Exception {
    File userData = temporary.newFolder();
    seed(userData);
    File task = temporary.newFolder(), archive = new File(task, "archive");
    FileBackupSource sessions =
        new FileBackupSource(
            fs, "dsh-sessions", "sessions", new File(userData, ".dsh/sessions"), false, null);
    HostSnapshot.create(fs, List.of(sessions), task, archive, provenance(), false, new BackupControl(null));

    HostRestoreMapping mapping = new HostRestoreMapping(userData, MEMBERS);
    File restore = temporary.newFolder();
    NativeRestorePlan plan =
        NativeRestorePlan.inspect(
            fs, restore, archive, mapping, Set.of("sessions"), new BackupControl(null));

    // 注入：预检之后、构建候选之前，活动目录被改动了。
    Files.write(
        new File(userData, ".dsh/sessions/appeared-later.json").toPath(),
        "{}".getBytes(StandardCharsets.UTF_8));

    IOException changed =
        assertThrows(IOException.class, () -> plan.buildCandidates(true, new BackupControl(null)));
    // 关键：它拒绝了，而且是在**写任何东西之前**拒绝的。
    assertEquals("TARGET_CHANGED", changed.getMessage());
    assertFalse("候选树不该在拒绝后被留下", new File(restore, "candidate/dsh-sessions/a.json").exists());
  }

  // ---------- 夹具 ----------

  private static void seed(File userData) throws IOException {
    File home = new File(userData, ".dsh");
    write(new File(home, "sessions/a.json"), "{\"session\":1}");
    write(new File(home, "sessions/nested/b.txt"), "deep");
    // 本地产生、且**不在**归档里的目录：合并语义下它必须活着。
    new File(home, "sessions/local-only").mkdirs();
    write(new File(home, "settings.yaml"), "model: test\n");
    write(new File(home, "keep.txt"), "not part of sessions\n");
  }

  private static void write(File file, String text) throws IOException {
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 归档来源信息。这里**自己写一份**而不是复用 `BackupArchiveTest.summary()`：
   * 那份是包内可见，而且「测试夹具复用别的测试的夹具」会让两边的失败缠在一起。
   * 键名错了 `BackupArchive` 会自己判失败 —— 夹具不需要靠人来保证对。
   */
  private static Map<String, Object> provenance() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("operation", "EXPORT");
    value.put("integrity", "QUIESCENT");
    value.put("createdAt", 1L);
    value.put("appVersion", "test");
    value.put("runtime", "test");
    value.put("dataFormat", "dsh-v3");
    value.put("sensitivePolicy", "ENCRYPTED");
    value.put("plugins", Collections.emptyMap());
    return value;
  }
}
