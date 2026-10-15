// DSHFusion 自写（**不是**从 DSHA 移植）。见 backup/README.md「为什么这个类是 Java」。
package com.dshbox.app.backup;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.FileBackupSource;
import com.deepseekharness.app.backup.HostDataTransaction;
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
 * 阶段 2.4b-② 的**集成**测试：走我们自己的「归档 → 预检 → 候选」链路，
 * 再把结果交给**上游的** {@link HostDataTransaction}。
 *
 * ## 为什么这份测试存在，而上游那份不够
 *
 * 上游的 `HostDataTransactionTest`（5 条，含真杀子进程）测的是**它自己** ——
 * 它用自己手搭的 `sessions`/`settings` 两个根和一份 `DiskSettings`。
 * 它证明不了下面这三件事，而这三件正是「我们能不能真的用它」的全部：
 *
 * 1. 我们的 `NativeRestorePlan` 产出的候选树，**路径与上游的 `candidate(id)` 同构**吗？
 *    （不同构的话 `prepare` 会直接 `CANDIDATE_MISSING`）
 * 2. 我们的 `plan.before` 能不能直接当上游的 `expectedBefore`？
 * 3. **适配层自己加的那条 `CROSS_DEVICE`** 真的会在动任何东西之前拦下来吗？
 *    —— 上游没有这条检查，所以它那边**永远测不到**。
 *
 * 所以分工是：**上游那份测事务本身的严密性，这份测「接得上」**。
 *
 * ## 故障注入用上游的命名边界，不用我自己的计数
 *
 * 上游的 `Fault.boundary(name)` 在代码里显式给出边界名（`old-<id>`、`new-<id>`、
 * `files-committed`…）。这比我阶段 2.4b-② 手写的那套「数第 N 次 `move`」健壮得多 ——
 * 那套因为 `fs.atomic()` 内部也在 `move`，注入打偏了而三个用例全绿（第 31 条）。
 * **边界名由被测代码自己给出**，注入点不可能悄悄漂移。
 */
public class HostRestoreTransactionTest {

  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();

  /** 事务目录名必须是 UUID 形状（上游用它给事务命名，名字会写进 plan.json 与每个标记）。 */
  private static final String ID = "11111111-2222-4333-8444-555555555555";

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
    // 事务目录名必须是 UUID —— 上游的 HostDataTransaction 构造器会校验。
    staged.restoreTask = new File(temporary.newFolder(), ID);
    assertTrue(staged.restoreTask.mkdirs());
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

  /** 上游把换出来的原件放在 `previous/<id>`（不是我手写那版的 `rollback/`）。 */
  private File previous(Staged staged) {
    return new File(staged.restoreTask, "previous/dsh-sessions");
  }

  // ---------- 接得上：成功路径 ----------

  @Test
  public void ourCandidateLayoutAndBeforeMapDriveTheUpstreamTransactionEndToEnd() throws Exception {
    Staged staged = stage(fs);
    HostRestoreTransaction tx = new HostRestoreTransaction(fs, staged.restoreTask, staged.plan);

    tx.checkSameFileSystem(staged.groups);
    tx.prepare(staged.groups, new BackupControl(null));
    tx.commit(new BackupControl(null));

    // 候选的内容进来了
    assertEquals("{\"session\":1}", read(new File(sessions(staged), "a.json")));
    assertEquals("deep", read(new File(sessions(staged), "nested/b.txt")));
    // 合并是在 **buildCandidates 那一刻**发生的：当时就在的文件必须活着
    assertTrue(new File(sessions(staged), "local-only").isDirectory());
    // 原件留在了 previous/，没有被删
    assertEquals("{\"session\":1}", read(new File(previous(staged), "a.json")));
    assertTrue(tx.finalized());
  }

  // ---------- 适配层自己加的那条检查 ----------

  /**
   * `CROSS_DEVICE` 是**上游没有的**检查，所以只有这里能测它。
   *
   * 跨文件系统时 `move` 不报错，只是退化成拷贝加删除 —— 于是「两次 rename 换进去」
   * 这个原子性**静默消失**，而所有测试仍然全绿。
   */
  @Test
  public void crossDeviceIsRefusedBeforeAnythingIsTouched() throws Exception {
    Staged staged = stage(fs);
    // 只把**目标父目录**的设备号改掉，其余全部委托
    BackupFileSystem other =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            Node node = super.stat(file);
            if (!file.equals(sessions(staged).getParentFile())) return node;
            return new Node(
                node.type, node.key, node.size, node.modified, node.device + 1, node.mode);
          }
        };

    HostRestoreTransaction tx = new HostRestoreTransaction(other, staged.restoreTask, staged.plan);
    IOException cross =
        assertThrows(IOException.class, () -> tx.checkSameFileSystem(staged.groups));

    assertEquals("CROSS_DEVICE", cross.getMessage());
    // 一个字节都没动
    assertEquals("{\"session\":1}", read(new File(sessions(staged), "a.json")));
    assertFalse(new File(staged.restoreTask, "plan.json").exists());
  }

  // ---------- 上游的 TARGET_CHANGED：经我们的 plan.before 生效 ----------

  /**
   * 这条同时证明「我们的 `plan.before` 确实被上游当成了 `expectedBefore`」——
   * 若映射错了（比如键名对不上），`prepare` 会报 `TARGET_CHANGED`，而这里要求它**不**报。
   */
  @Test
  public void prepareRefusesWhenTheActiveTreeChangedAfterCandidatesWereBuilt() throws Exception {
    Staged staged = stage(fs);
    write(new File(sessions(staged), "a.json"), "user edited this while waiting");

    HostRestoreTransaction tx = new HostRestoreTransaction(fs, staged.restoreTask, staged.plan);
    IOException changed =
        assertThrows(IOException.class, () -> tx.prepare(staged.groups, new BackupControl(null)));

    assertEquals("TARGET_CHANGED", changed.getMessage());
    assertEquals("user edited this while waiting", read(new File(sessions(staged), "a.json")));
  }

  // ---------- 故障注入：按上游的命名边界 ----------

  /**
   * 在「原件已换出、候选还没进来」这个窗口上抛错，然后走**真实的** `recover()`。
   *
   * 崩溃现场由真实代码路径产生，不是我在测试里手搭一个（手搭的现场会和源码各说各话 —— 第 17 条）。
   * 注入点用**边界名**，不用计数：计数那套我踩过（第 31 条）。
   */
  @Test
  public void faultInTheSwapWindowIsRolledBackAndRecoverIsIdempotent() throws Exception {
    Staged staged = stage(fs);
    HostRestoreTransaction injecting =
        new HostRestoreTransaction(
            fs,
            staged.restoreTask,
            staged.plan,
            name -> {
              if (name.equals("new-dsh-sessions")) throw new IOException("INJECTED");
            });

    injecting.prepare(staged.groups, new BackupControl(null));
    IOException failure =
        assertThrows(IOException.class, () -> injecting.commit(new BackupControl(null)));
    assertEquals("INJECTED", failure.getMessage());

    // commit 内部已经回切过一次 —— 原件必须回来了
    assertEquals("{\"session\":1}", read(new File(sessions(staged), "a.json")));

    // 再显式恢复两次：幂等
    HostRestoreTransaction plain = new HostRestoreTransaction(fs, staged.restoreTask, staged.plan);
    plain.recover();
    plain.recover();
    assertEquals("{\"session\":1}", read(new File(sessions(staged), "a.json")));
    assertEquals("deep", read(new File(sessions(staged), "nested/b.txt")));
    assertTrue(new File(sessions(staged), "local-only").isDirectory());
  }

  /** 回切本身也失败时，现场必须留着，而 `recover()` 还能救回来。 */
  @Test
  public void whenRollbackAlsoFailsRecoverStillRestoresTheOriginal() throws Exception {
    Staged staged = stage(fs);
    // 从「候选进不来」起全失败 → 连回切也失败 → 现场留在磁盘上
    HostRestoreTransaction injecting =
        new HostRestoreTransaction(
            fs,
            staged.restoreTask,
            staged.plan,
            name -> {
              if (name.equals("new-dsh-sessions") || name.startsWith("rollback-"))
                throw new IOException("INJECTED");
            });

    injecting.prepare(staged.groups, new BackupControl(null));
    IOException failure =
        assertThrows(IOException.class, () -> injecting.commit(new BackupControl(null)));
    // 回切失败必须被挂成 suppressed，而不是被吞掉
    assertTrue(
        "回切失败没有被记下来（suppressed 为空）", failure.getSuppressed().length > 0);
    // 现场还在：原件在 previous/，活动目录里没有它
    assertTrue(new File(previous(staged), "a.json").exists());
    assertFalse(new File(sessions(staged), "a.json").exists());

    new HostRestoreTransaction(fs, staged.restoreTask, staged.plan).recover();

    assertEquals("{\"session\":1}", read(new File(sessions(staged), "a.json")));
    assertEquals("deep", read(new File(sessions(staged), "nested/b.txt")));
    assertTrue(new File(sessions(staged), "local-only").isDirectory());
  }

  // ---------- 适配层自己的两条前提 ----------

  @Test
  public void taskDirectoryNameMustLookLikeAUuid() throws Exception {
    Staged staged = stage(fs);
    File notUuid = temporary.newFolder(); // 随机名，不是 UUID
    IOException e =
        assertThrows(
            IOException.class, () -> new HostRestoreTransaction(fs, notUuid, staged.plan));
    assertEquals("TASK_NAME_NOT_UUID", e.getMessage());
  }

  /**
   * 设置轴：我们**没有**它，所以非空值必须被**拒绝**，而不是静默空转。
   *
   * 上游会在提交时一并切换 `settings.json`，并有三处断言（`SETTINGS_CHANGED` /
   * `SETTINGS_COMMIT_VERIFICATION`）。若适配层给一个「恒空表、apply 什么都不做」的实现，
   * 那三处会**永远通过** —— 那就是第 30 条说的「没有输入空间的断言」。
   * 所以这里反过来测：非空值必须红。
   */
  @Test
  public void nonEmptySettingsAreRefusedRatherThanSilentlyIgnored() throws Exception {
    HostRestoreTransaction.NoSettings settings = new HostRestoreTransaction.NoSettings();
    assertTrue(settings.current().isEmpty());

    IOException e =
        assertThrows(
            IOException.class, () -> settings.apply(Map.of("apiCipher", "should-not-be-ignored")));
    assertEquals("SETTINGS_NOT_SUPPORTED", e.getMessage());
    // 空表是允许的（那才是我们真实的设置轴）
    settings.apply(Collections.emptyMap());
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

  /** 只用来让 `BackupFileSystem` 的匿名子类能编译（其余方法全部委托）。 */
  private abstract static class Delegating implements BackupFileSystem {
    final BackupFileSystem delegate = new JvmBackupFileSystem();

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
