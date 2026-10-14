// DSHFusion 自写（**不是**从 DSHA 移植）。见 backup/README.md「为什么这个类是 Java」。
package com.dshbox.app.backup;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.BackupLimits;
import com.deepseekharness.app.backup.BackupTree;
import com.deepseekharness.app.backup.NativeRestorePlan;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 2.4b-②：把 {@link NativeRestorePlan} 建好的候选树**提交**到活动目录，失败能回切。
 *
 * ## 为什么这一步必须存在
 *
 * 读完 `NativeRestorePlan` 全部 405 行确认：`buildCandidates()` **只写
 * `task/candidate/`，一次都不碰活动目录**。所以 2.4a 是安全的，而**提交这一步
 * 在上游和我们这里都不存在** —— 上游 DSHA 把它放在 `HostDataTransaction` 里，
 * 那个类依赖它自己的 `ConfigStore`/`KeyVault`，我们没搬。这个类补的就是它。
 *
 * ## 原子性到底靠什么（先把话说明白）
 *
 * 候选树是在活动目录**之外**完整建好、逐文件校验过 sha256 的（2.4a 做的）。
 * 提交就是两次同文件系统内的 `move`（rename）：
 *
 * ```
 * move(active, aside)     // 旧内容挪到 task/rollback/<group>
 * move(candidate, active) // 候选挪进来
 * ```
 *
 * **rename 是原子的**，所以活动树在任何瞬间要么是旧的整棵、要么是新的整棵 ——
 * 不存在「半新半旧」。真正要处理的是另外两件事：
 *
 * | 失败形态 | 处理 |
 * |---|---|
 * | 两次 rename 之间进程被杀 → **目标不存在** | 提交前用 {@code fs.atomic()} 写日志；恢复时把 aside 移回来 |
 * | 提交成功但 aside 没来得及删 | 恢复时按摘要判定，再删 |
 *
 * ⚠️ **这个「原子」有一个前提，而且它必须被断言而不是被假设**：`task` 与活动目录
 * 必须在**同一个文件系统**上（`device` 相等）。跨设备时 `move` 会退化成拷贝，
 * 原子性假设当场失效 —— 那时上面整张表都不成立。{@link #checkPreconditions} 做这件事。
 *
 * ## 恢复判定看**摘要**，不看日志里的相位
 *
 * 日志里也记了相位（`PREPARED`/`COMMITTED`），但**它不是判据**：进程可能死在
 * 「相位写下去之前」或「写下去之后」，相位本身可能是过期的。判据只能是
 * 「活动目录现在的内容是哪一份」，而这要用摘要回答。
 *
 * | `active` | 摘要 | 判定 |
 * |---|---|---|
 * | 缺失 | — | aside 在 → 移回来（死在两次 rename 之间）；否则无事 |
 * | 在 | == `candidateDigest` | 换进去了 → 收尾（删 aside / candidate） |
 * | 在 | == `before` | 还没换 → aside 是上一轮的残留，删掉；candidate 也没用上，删掉 |
 * | 在 | **都不是** | **拒绝并报错** —— 不猜，不覆盖 |
 */
public final class HostRestoreCommit {

  /** 日志文件名。`fs.atomic()` 会自己维护 `…previous` 与 `…tmp-<uuid>` 两个辅助名。 */
  public static final String JOURNAL = "commit-journal.json";

  private static final String PHASE_PREPARED = "PREPARED";
  private static final String PHASE_COMMITTED = "COMMITTED";

  /** 一次提交里一个成员的四个位置与两个摘要。 */
  public static final class Entry {
    public final String group;
    public final File active, candidate, aside;
    public final String before, candidateDigest;

    /**
     * 两个「这一步到底做没做」的标记。回切必须知道它们 —— 只靠「aside 在不在」
     * 推不出来：`aside` 缺失既可能是「还没挪走」，也可能是「这个成员原本就不存在」。
     * 分不清的后果很具体：把原件挪进 `failed/`，而用户看到的是数据没了。
     */
    boolean asidePlaced, installed;

    Entry(
        String group,
        File active,
        File candidate,
        File aside,
        String before,
        String candidateDigest) {
      this.group = group;
      this.active = active;
      this.candidate = candidate;
      this.aside = aside;
      this.before = before;
      this.candidateDigest = candidateDigest;
    }
  }

  private final BackupFileSystem fs;
  private final File task, candidateRoot, rollbackRoot, failedRoot;

  /**
   * @param fs 文件系统边界
   * @param task 本次恢复的任务目录（`NativeRestorePlan.inspect` 用的同一个）
   */
  public HostRestoreCommit(BackupFileSystem fs, File task) throws IOException {
    this.fs = fs;
    this.task = task;
    this.candidateRoot = new File(task, "candidate");
    this.rollbackRoot = new File(task, "rollback");
    this.failedRoot = new File(task, "failed");
  }

  private File candidate(String group) {
    return new File(candidateRoot, group);
  }

  private File aside(String group) {
    return new File(rollbackRoot, group);
  }

  private File failed(String group) {
    return new File(failedRoot, group);
  }

  /**
   * 目录**缺了才建**。
   *
   * 不能直接用 `fs.directory()` —— 它在目录已存在时抛错（后端用的是
   * `Files.createDirectory`，不是 `createDirectories`）。任务目录、rollback、failed
   * 三个都常常已经存在，直接用会把「提交」变成「建目录失败」。
   */
  private void ensureDirectory(File directory) throws IOException {
    if (fs.stat(directory).type.equals("MISSING")) fs.directory(directory);
  }

  /**
   * 删一棵树。`fs.delete()` 只删空目录（后端是 `Files.delete`），
   * 而 aside / candidate 都是有内容的目录 —— 直接 delete 会抛
   * `DirectoryNotEmptyException`，那是「清理失败」，不是「清理完成」。
   *
   * 递归删用接口自带的 `removeOwned`：它自己带 `prepareOwnedRemoval` 与
   * `REFUSE_ROOT_DELETE`，比在这里另写一遍遍历安全。
   */
  private void deleteTree(File target) throws IOException {
    BackupFileSystem.Node node = fs.stat(target);
    if (node.type.equals("MISSING")) return;
    if (!node.type.equals("DIRECTORY")) {
      fs.delete(target);
      return;
    }
    File parent = target.getParentFile();
    if (parent == null) throw new IOException("REFUSE_ROOT_DELETE");
    fs.removeOwned(parent, target.getName());
  }

  /**
   * 提交前必须成立的前提。**不成立就抛，绝不「试试看」** ——
   * 跨文件系统的 `move` 不报错，它只是退化成拷贝，于是原子性静默消失。
   */
  public void checkPreconditions(NativeRestorePlan plan, List<String> groups, BackupControl control)
      throws IOException {
    if (groups == null || groups.isEmpty()) throw new IOException("NO_GROUPS");
    long taskDevice = fs.stat(task).device;
    if (taskDevice == 0) throw new IOException("DEVICE_UNKNOWN");
    for (String group : groups) {
      NativeRestorePlan.Target target = plan.groups.get(group);
      if (target == null) throw new IOException("UNKNOWN_GROUP");
      File candidate = candidate(group);
      if (!fs.stat(candidate).type.equals("DIRECTORY"))
        throw new IOException("CANDIDATE_MISSING");
      // 候选在 task 下，aside 也在 task 下；active 的父目录必须同设备，
      // 否则 move(active, aside) 与 move(candidate, active) 都不是 rename。
      File parent = target.write.getParentFile();
      if (parent == null || !fs.stat(parent).type.equals("DIRECTORY"))
        throw new IOException("TARGET_PARENT_MISSING");
      if (fs.stat(parent).device != taskDevice) throw new IOException("CROSS_DEVICE");
      // **重新核验 before，而不是只信 buildCandidates 当时算的那一次。**
      // 候选建好到真正提交之间隔着用户操作与界面确认，那段时间里活动目录完全可能
      // 被改过（DSH 正在跑，会话文件一直在写）。`buildCandidates` 只在它自己开始时
      // 核验过一次；少了这一句，提交会**静默覆盖**用户在等待期间写进去的东西。
      String before = plan.before.get(group);
      if (before != null && !before.equals(BackupTree.digest(fs, target.write, control)))
        throw new IOException("TARGET_CHANGED");
    }
  }

  /**
   * 提交。返回真正提交成功的 group 列表（顺序与传入一致）。
   *
   * 任何一步失败都会**先把已换的换回去**再抛 —— 抛出的异常是原始的失败原因，
   * 回切自身的失败会作为 suppressed 挂在它上面（回切失败比提交失败严重得多，
   * 不能让它把原始原因吞掉）。
   */
  public List<String> commit(
      NativeRestorePlan plan, List<String> groups, BackupControl control) throws IOException {
    checkPreconditions(plan, groups, control);
    ensureDirectory(rollbackRoot);
    List<Entry> entries = new ArrayList<>();
    for (String group : groups) {
      NativeRestorePlan.Target target = plan.groups.get(group);
      File candidate = candidate(group);
      entries.add(
          new Entry(
              group,
              target.write,
              candidate,
              aside(group),
              plan.before.get(group),
              BackupTree.digest(fs, candidate, control)));
    }
    writeJournal(PHASE_PREPARED, entries, control);

    List<Entry> swapped = new ArrayList<>();
    try {
      for (Entry entry : entries) {
        control.check();
        // **先登记，再动作。** 第一版把 add 放在两个 move 都成功之后，
        // 于是「旧内容已挪到 aside、候选还没挪进来」这个窗口里回切根本不知道
        // 要管它 —— 活动目录会永远缺失。这个窗口正是崩溃恢复要处理的那一个，
        // 而提交内的回切路径也必须覆盖它，不能只靠日志恢复。
        swapped.add(entry);
        if (!fs.stat(entry.active).type.equals("MISSING")) {
          fs.move(entry.active, entry.aside);
          entry.asidePlaced = true;
        }
        fs.move(entry.candidate, entry.active);
        entry.installed = true;
      }
      fs.syncDirectory(task);
    } catch (IOException failure) {
      rollback(swapped, failure);
      // 回切干净了才删日志。回切本身有失败（suppressed）时必须**留着**日志 ——
      // 那正是 recover() 存在的原因，删掉等于把现场抹了。
      if (failure.getSuppressed().length == 0) deleteJournal();
      throw failure;
    }

    try {
      for (Entry entry : entries) {
        control.check();
        String actual = BackupTree.digest(fs, entry.active, control);
        if (!actual.equals(entry.candidateDigest)) throw new IOException("COMMIT_VERIFY_FAILED");
      }
    } catch (IOException failure) {
      rollback(swapped, failure);
      if (failure.getSuppressed().length == 0) deleteJournal();
      throw failure;
    }

    writeJournal(PHASE_COMMITTED, entries, control);
    for (Entry entry : entries) {
      deleteTree(entry.aside);
      deleteTree(entry.candidate);
    }
    deleteJournal();
    return new ArrayList<>(groups);
  }

  /**
   * 崩溃恢复。**幂等**：没有日志时返回空列表，不做事。
   *
   * 判定看摘要（见类注释的表），相位只写进日志供人看。遇到「摘要既不是候选也不是
   * 原始」的组会**拒绝并抛错** —— 那种情况下任何自动动作都可能覆盖用户刚写进去的东西。
   */
  public List<String> recover(BackupControl control) throws IOException {
    Map<String, Object> journal = readJournal();
    if (journal == null) return new ArrayList<>();
    List<Map<String, Object>> rows = rows(journal);
    List<String> handled = new ArrayList<>();
    // 反向处理：后换进去的先收，与提交顺序镜像。
    for (int i = rows.size() - 1; i >= 0; i--) {
      Map<String, Object> row = rows.get(i);
      String group = BackupJson.string(row, "group");
      File active = new File(BackupJson.string(row, "active"));
      File candidate = new File(BackupJson.string(row, "candidate"));
      File aside = new File(BackupJson.string(row, "aside"));
      String before = BackupJson.string(row, "before");
      String want = BackupJson.string(row, "candidateDigest");
      String at = BackupTree.digest(fs, active, control);
      boolean asideThere = !fs.stat(aside).type.equals("MISSING");

      if (fs.stat(active).type.equals("MISSING")) {
        // 死在两次 rename 之间：旧内容在 aside 里，候选还在原地。
        if (asideThere) {
          fs.move(aside, active);
          handled.add(group);
        }
      } else if (at.equals(want)) {
        // 换进去了，只是没来得及收尾。
        deleteTree(aside);
        deleteTree(candidate);
        handled.add(group);
      } else if (at.equals(before)) {
        // 还没换。aside 只可能是上一轮的残留。
        deleteTree(aside);
        deleteTree(candidate);
        handled.add(group);
      } else {
        throw new IOException("RECOVERY_AMBIGUOUS");
      }
    }
    fs.syncDirectory(task);
    deleteJournal();
    return handled;
  }

  // ---------- 回切 ----------

  /**
   * 把已换的组换回去。反向遍历（后换的先退）。
   *
   * 旧内容不存在时（该成员原本就没有）**不假装恢复**，而是把新装进去的挪到
   * `failed/` 留证据 —— 直接删掉会让人以为「什么都没发生」。
   */
  private void rollback(List<Entry> swapped, IOException original) {
    try {
      ensureDirectory(failedRoot);
    } catch (IOException ignored) {
      // 建不出目录不阻断回切：下面的 move 会各自失败并被记成 suppressed。
    }
    for (int i = swapped.size() - 1; i >= 0; i--) {
      Entry entry = swapped.get(i);
      try {
        // 只有真装进去过才需要把新内容挪走；否则「挪到 failed」会连原件一起挪走。
        if (entry.installed && !fs.stat(entry.active).type.equals("MISSING"))
          fs.move(entry.active, failed(entry.group));
        // 只有真挪走过旧内容才需要挪回来。
        if (entry.asidePlaced) fs.move(entry.aside, entry.active);
      } catch (IOException rollbackFailure) {
        original.addSuppressed(rollbackFailure);
      }
    }
    try {
      fs.syncDirectory(task);
    } catch (IOException syncFailure) {
      original.addSuppressed(syncFailure);
    }
  }

  // ---------- 日志 ----------

  private void writeJournal(String phase, List<Entry> entries, BackupControl control)
      throws IOException {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("version", 1L);
    value.put("phase", phase);
    List<Object> rows = new ArrayList<>();
    for (Entry entry : entries) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("group", entry.group);
      row.put("active", entry.active.getAbsolutePath());
      row.put("candidate", entry.candidate.getAbsolutePath());
      row.put("aside", entry.aside.getAbsolutePath());
      row.put("before", entry.before == null ? "" : entry.before);
      row.put("candidateDigest", entry.candidateDigest);
      rows.add(row);
    }
    value.put("entries", rows);
    ensureDirectory(task);
    fs.atomic(task, JOURNAL, BackupJson.write(value, BackupLimits.RECORD));
  }

  private Map<String, Object> readJournal() throws IOException {
    File journal = new File(task, JOURNAL);
    if (fs.stat(journal).type.equals("MISSING")) return null;
    return BackupJson.read(fs.small(journal, BackupLimits.RECORD), BackupLimits.RECORD);
  }

  private void deleteJournal() throws IOException {
    File journal = new File(task, JOURNAL);
    if (!fs.stat(journal).type.equals("MISSING")) fs.delete(journal);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> journal) throws IOException {
    Object value = journal.get("entries");
    if (!(value instanceof List)) throw new IOException("JOURNAL_ENTRIES");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object item : (List<Object>) value) {
      if (!(item instanceof Map)) throw new IOException("JOURNAL_ENTRY");
      rows.add((Map<String, Object>) item);
    }
    if (rows.isEmpty()) throw new IOException("JOURNAL_ENTRIES");
    return Collections.unmodifiableList(rows);
  }
}
