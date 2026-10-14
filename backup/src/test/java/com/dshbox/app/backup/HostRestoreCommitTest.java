// DSHFusion 自写（**不是**从 DSHA 移植）。见 backup/README.md「为什么这个类是 Java」。
package com.dshbox.app.backup;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.FileBackupSource;
import com.deepseekharness.app.backup.HostSnapshot;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.backup.NativeRestorePlan;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 阶段 2.4b-②：提交与回切。
 *
 * 这里最有价值的不是「成功路径能跑」，而是**故障注入真的注进去了**：
 * `FaultFs` 在第 N 次 `move` 上抛错，于是
 *
 * - 第 1 次失败 → 活动目录**必须一动不动**（回切不能把原件挪进 `failed/`）；
 * - 第 2 次失败 → 旧内容已在 aside、候选还没进来 → 回切必须把旧内容挪回来；
 * - 从第 2 次起全失败 → 回切也失败，日志**必须留着**，`recover()` 才救得回来。
 *
 * 第三条是「崩溃恢复」唯一诚实的测法：崩溃状态是**由真实代码路径产生**的，
 * 不是我在测试里手搭一个现场（手搭的现场会和源码各说各话 —— 事故表第 17 条）。
 */
public class HostRestoreCommitTest {

  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  private static final List<HostRestoreMapping.Member> MEMBERS =
      List.of(
          new HostRestoreMapping.Member("dsh", ".dsh", "application"),
          new HostRestoreMapping.Member("dsh-sessions", ".dsh/sessions", "sessions"),
          new HostRestoreMapping.Member("dsh-storages", ".dsh/storages", "sessions"),
          new HostRestoreMapping.Member("dsh-attachments", ".dsh/attachments", "sessions"),
          new HostRestoreMapping.Member("dsh-settings", ".dsh/settings.yaml", "settings"),
          new HostRestoreMapping.Member("dsh-profiles", ".dsh/profiles", "plugins"));

  /** 一次完整的「备份 → 预检 → 候选」，供各用例复用。 */
  private static final class Staged {
    File userData, restoreTask;
    NativeRestorePlan plan;
    List<String> groups;
  }

  private Staged stage(BackupFileSystem any) throws IOException {
    Staged staged = new Staged();
    staged.userData = temporary.newFolder();
    seed(staged.userData);
    File backupTask = temporary.newFolder();
    File archive = new File(backupTask, "archive");
    FileBackupSource sessions =
        new FileBackupSource(
            any, "dsh-sessions", "sessions", new File(staged.userData, ".dsh/sessions"), false, null);
    HostSnapshot.create(
        any, List.of(sessions), backupTask, archive, provenance(), false, new BackupControl(null));
    staged.restoreTask = temporary.newFolder();
    HostRestoreMapping mapping = new HostRestoreMapping(staged.userData, MEMBERS);
    staged.plan =
        NativeRestorePlan.inspect(
            any,
            staged.restoreTask,
            archive,
            mapping,
            Set.of("sessions"),
            new BackupControl(null));
    staged.groups = staged.plan.buildCandidates(true, new BackupControl(null));
    assertEquals(List.of("dsh-sessions"), staged.groups);
    return staged;
  }

  private File sessions(Staged staged) {
    return new File(staged.userData, ".dsh/sessions");
  }

  private File aside(Staged staged) {
    return new File(staged.restoreTask, "rollback/dsh-sessions");
  }

  // ---------- 成功路径 ----------

  @Test
  public void commitInstallsTheCandidateAndKeepsWhatWasThereWhenItWasBuilt() throws Exception {
    Staged staged = stage(fs);

    new HostRestoreCommit(fs, staged.restoreTask)
        .commit(staged.plan, staged.groups, new BackupControl(null));

    assertEquals("{\"session\":1}", read(new File(sessions(staged), "a.json")));
    // 合并是在 **buildCandidates 那一刻**发生的：当时就在的文件必须活着。
    assertTrue(new File(sessions(staged), "local-only").isDirectory());
    // 提交成功后日志必须消失，否则下一次 recover() 会拿它当现场。
    assertFalse(new File(staged.restoreTask, HostRestoreCommit.JOURNAL).exists());
    assertFalse(aside(staged).exists());
  }

  /**
   * 提交时的 `before` 复核是一条**运行约束**，不只是防御性代码。
   *
   * 合并（把活动目录拷进候选）发生在 `buildCandidates` 那一刻。所以在那之后写进
   * 活动目录的东西**不在候选里**，提交会把它盖掉。拒绝是对的 ——
   * 但代价是：**恢复期间目标必须静止**（DSH 得停，或至少该成员不能被写）。
   * 这条约束必须由调用方保证；这个类只能做到「不静默地违反它」。
   */
  @Test
  public void commitRefusesWhenTheActiveTreeWasModifiedAfterCandidatesWereBuilt() throws Exception {
    Staged staged = stage(fs);
    write(new File(sessions(staged), "a.json"), "user edited this while waiting");

    IOException changed =
        assertThrows(
            IOException.class,
            () ->
                new HostRestoreCommit(fs, staged.restoreTask)
                    .commit(staged.plan, staged.groups, new BackupControl(null)));
    assertEquals("TARGET_CHANGED", changed.getMessage());
    assertEquals("user edited this while waiting", read(new File(sessions(staged), "a.json")));
  }

  @Test
  public void commitRefusesWhenANewFileAppearedAfterCandidatesWereBuilt() throws Exception {
    Staged staged = stage(fs);
    // 「新出现」比「被改过」更危险：它在候选里根本不存在，提交等于把它删了。
    write(new File(sessions(staged), "appeared-while-waiting.txt"), "not in the candidate");

    IOException changed =
        assertThrows(
            IOException.class,
            () ->
                new HostRestoreCommit(fs, staged.restoreTask)
                    .commit(staged.plan, staged.groups, new BackupControl(null)));
    assertEquals("TARGET_CHANGED", changed.getMessage());
    assertEquals(
        "not in the candidate", read(new File(sessions(staged), "appeared-while-waiting.txt")));
  }

  // ---------- 故障注入：提交内回切 ----------

  @Test
  public void faultBeforeTheFirstMoveLeavesTheActiveTreeUntouched() throws Exception {
    Staged staged = stage(fs);
    String before = read(new File(sessions(staged), "a.json"));
    FaultFs fault = new FaultFs(fs, sessions(staged), aside(staged), 1, Integer.MAX_VALUE);

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                new HostRestoreCommit(fault, staged.restoreTask)
                    .commit(staged.plan, staged.groups, new BackupControl(null)));

    // **注入自证**：第 1 次换出被打了，而且打的确实是换出（不是日志自己的 move）。
    // 少了这一句，用例可能在「注入根本没生效」的情况下照样绿 —— 事故表第 16 条。
    assertEquals("PROCESS_INTERRUPTED", failure.getMessage());
    assertEquals("应当正好尝试过 1 次换出", 1, fault.swaps);
    // 关键：回切**不能**把原件挪进 failed/。第一次 move 都没发生，
    // 活动目录必须原封不动，否则用户看到的就是数据没了。
    assertEquals(before, read(new File(sessions(staged), "a.json")));
    assertTrue(new File(sessions(staged), "local-only").isDirectory());
    assertFalse("aside 不该被创建", new File(staged.restoreTask, "rollback/dsh-sessions").exists());
    // 回切干净 → 日志必须已删。
    assertFalse(new File(staged.restoreTask, HostRestoreCommit.JOURNAL).exists());
  }

  @Test
  public void faultBetweenTheTwoMovesIsRolledBack() throws Exception {
    Staged staged = stage(fs);
    String before = read(new File(sessions(staged), "a.json"));
    // 第 1 次换出成功（旧内容挪到 aside），第 2 次换入失败（候选进不来）。
    FaultFs fault = new FaultFs(fs, sessions(staged), aside(staged), 2, Integer.MAX_VALUE);

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                new HostRestoreCommit(fault, staged.restoreTask)
                    .commit(staged.plan, staged.groups, new BackupControl(null)));

    // **注入自证**：3 次换入换出 = 换出成功(1) → 换入失败(2) → 回切挪回(3)。
    // 如果这个数不对，说明失败打在了别的地方，下面的断言就不再证明它名字说的事。
    assertEquals("PROCESS_INTERRUPTED", failure.getMessage());
    assertEquals(3, fault.swaps);
    assertEquals("回切必须成功，所以不该有 suppressed", 0, failure.getSuppressed().length);
    assertEquals("旧内容必须被挪回来", before, read(new File(sessions(staged), "a.json")));
    assertTrue(new File(sessions(staged), "local-only").isDirectory());
    assertFalse(new File(staged.restoreTask, "rollback/dsh-sessions").exists());
    assertFalse(new File(staged.restoreTask, HostRestoreCommit.JOURNAL).exists());
  }

  // ---------- 崩溃恢复：现场由真实代码路径产生 ----------

  /** 制造「死在两次 rename 之间」：从第 2 次 move 起全失败，于是回切也失败、日志留下。 */
  private Staged crashedMidSwap() throws Exception {
    Staged staged = stage(fs);
    FaultFs fault = new FaultFs(fs, sessions(staged), aside(staged), 2, 2);
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                new HostRestoreCommit(fault, staged.restoreTask)
                    .commit(staged.plan, staged.groups, new BackupControl(null)));
    // **注入自证**：换出(1) → 换入失败(2) → 回切挪回也被拒(3)。
    // 这正是「崩溃现场」：旧内容停在 aside，活动目录缺失，日志留下。
    assertEquals("PROCESS_INTERRUPTED", failure.getMessage());
    assertEquals(3, fault.swaps);
    assertTrue("回切自身失败必须挂在原始异常上", failure.getSuppressed().length > 0);
    assertFalse("活动目录此时应当缺失（旧内容在 aside 里）", sessions(staged).exists());
    assertTrue(new File(staged.restoreTask, HostRestoreCommit.JOURNAL).exists());
    return staged;
  }

  @Test
  public void recoverRestoresTheOriginalFromTheCrashWindow() throws Exception {
    Staged staged = crashedMidSwap();
    String original = "{\"session\":1}";

    List<String> handled =
        new HostRestoreCommit(fs, staged.restoreTask).recover(new BackupControl(null));

    assertEquals(List.of("dsh-sessions"), handled);
    assertEquals(original, read(new File(sessions(staged), "a.json")));
    assertTrue(new File(sessions(staged), "local-only").isDirectory());
    assertFalse(new File(staged.restoreTask, HostRestoreCommit.JOURNAL).exists());
  }

  @Test
  public void recoverIsIdempotent() throws Exception {
    Staged staged = crashedMidSwap();
    HostRestoreCommit commit = new HostRestoreCommit(fs, staged.restoreTask);
    assertEquals(List.of("dsh-sessions"), commit.recover(new BackupControl(null)));
    // 没有日志时它必须什么都不做，而不是再动一次活动目录。
    assertTrue(commit.recover(new BackupControl(null)).isEmpty());
    assertEquals("{\"session\":1}", read(new File(sessions(staged), "a.json")));
  }

  @Test
  public void recoverRefusesWhenActiveMatchesNeitherTheOriginalNorTheCandidate() throws Exception {
    Staged staged = crashedMidSwap();
    // 注入：把活动目录换成一份**既不是原件也不是候选**的内容。
    // 现实中这对应「另一个进程在崩溃之后写了东西」。
    fs.directory(sessions(staged));
    write(new File(sessions(staged), "someone-else.txt"), "not part of this transaction");

    IOException ambiguous =
        assertThrows(
            IOException.class,
            () ->
                new HostRestoreCommit(fs, staged.restoreTask).recover(new BackupControl(null)));
    assertEquals("RECOVERY_AMBIGUOUS", ambiguous.getMessage());
    // 拒绝时**不能**顺手把现场删掉 —— 那正是唯一能人工恢复的线索。
    assertTrue(new File(staged.restoreTask, HostRestoreCommit.JOURNAL).exists());
    assertEquals("not part of this transaction", read(new File(sessions(staged), "someone-else.txt")));
  }

  // ---------- 前提断言 ----------

  @Test
  public void crossDeviceMoveIsRefusedBeforeAnythingIsTouched() throws Exception {
    Staged staged = stage(fs);
    // 注入：让活动目录的父目录看起来在另一个设备上。
    // 跨设备时 move 不报错，它只是退化成拷贝 —— 原子性会**静默消失**，
    // 所以这条必须在动手之前就判失败。
    FaultFs other =
        new FaultFs(fs, sessions(staged), aside(staged), Integer.MAX_VALUE, Integer.MAX_VALUE) {
      @Override
      public Node stat(File file) throws IOException {
        Node node = super.stat(file);
        File parent = sessions(staged).getParentFile();
        if (file.equals(parent))
          return new Node(node.type, node.key, node.size, node.modified, node.device + 1, node.mode);
        return node;
      }
    };
    IOException cross =
        assertThrows(
            IOException.class,
            () ->
                new HostRestoreCommit(other, staged.restoreTask)
                    .commit(staged.plan, staged.groups, new BackupControl(null)));
    assertEquals("CROSS_DEVICE", cross.getMessage());
    assertEquals("{\"session\":1}", read(new File(sessions(staged), "a.json")));
  }

  // ---------- 夹具 ----------

  private static void seed(File userData) throws IOException {
    File home = new File(userData, ".dsh");
    write(new File(home, "sessions/a.json"), "{\"session\":1}");
    write(new File(home, "sessions/nested/b.txt"), "deep");
    new File(home, "sessions/local-only").mkdirs();
    write(new File(home, "settings.yaml"), "model: test\n");
  }

  private static void write(File file, String text) throws IOException {
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  private static String read(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

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

  /**
   * 故障注入：在**换入/换出动作**上按次序失败。
   *
   * ⚠️ 第一版数的是「第 N 次 `move`」，而那个计数是**错的** ——
   * `fs.atomic()` 自己内部也会 `move`（发布日志、轮转 `.previous`），
   * 于是 `failAt=2` 实际打在 `move(active, aside)` 上，而我的用例以为它打在
   * 「候选进不来」那一步。三个用例当时都「通过」了，却一个都没测到它名字说的东西。
   * 这正是事故表第 16 条的形态：**注入没生效，给出的是完全虚假的安全感。**
   *
   * 现在按**语义**判定：一次 move 只要有一端是活动目录或 aside 目录，才算
   * 「换入/换出」。日志自己的 move 因此被排除在外。
   */
  private static class FaultFs implements BackupFileSystem {
    final BackupFileSystem delegate;
    final File active, aside;
    final int failSwapAt, failSwapFrom;
    int swaps;

    FaultFs(BackupFileSystem delegate, File active, File aside, int failSwapAt, int failSwapFrom) {
      this.delegate = delegate;
      this.active = active;
      this.aside = aside;
      this.failSwapAt = failSwapAt;
      this.failSwapFrom = failSwapFrom;
    }

    private boolean isSwap(File source, File target) {
      return source.equals(active) || target.equals(active) || target.equals(aside);
    }

    public Node stat(File f) throws IOException {
      return delegate.stat(f);
    }

    public String readLink(File f) throws IOException {
      return delegate.readLink(f);
    }

    public List<String> list(File f) throws IOException {
      return delegate.list(f);
    }

    public InputStream read(File f, Node n) throws IOException {
      return delegate.read(f, n);
    }

    public OutputStream create(File f) throws IOException {
      return delegate.create(f);
    }

    public void directory(File f) throws IOException {
      delegate.directory(f);
    }

    public void move(File a, File b) throws IOException {
      if (isSwap(a, b)) {
        int index = ++swaps;
        if (index == failSwapAt || index >= failSwapFrom)
          throw new IOException("PROCESS_INTERRUPTED");
      }
      delegate.move(a, b);
    }

    public void delete(File f) throws IOException {
      delegate.delete(f);
    }

    public void syncDirectory(File f) throws IOException {
      delegate.syncDirectory(f);
    }

    public void mode(File f, int m) throws IOException {
      delegate.mode(f, m);
    }

    public void symlink(String t, File l) throws IOException {
      delegate.symlink(t, l);
    }
  }
}
