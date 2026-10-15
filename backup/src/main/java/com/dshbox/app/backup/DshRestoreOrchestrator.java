// DSHFusion 自写。登记在 governance/dsha-imports.json 的 original 段。
//
// 为什么是 Java：这是本模块**会毁数据**的那条路径（它决定往哪里写、什么时候换进去），
// 只让 CI 编译它太贵（本机有 JDK 17、没有 kotlinc，见 docs/INCIDENTS.md 第 21 条）。
// 与 HostRestoreMapping / DshBackupExporter 同一条约定。
package com.dshbox.app.backup;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.NativeRestorePlan;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 恢复编排：把「选一份归档 → 预检 → 确认 → 提交」接成一条链。
 *
 * <h2>它做什么，不做什么</h2>
 *
 * 它只做**顺序**，不做**逻辑**：落点推导在 {@link HostRestoreMapping}，
 * 平行候选与校验在 {@link NativeRestorePlan}，五相位标记与回切在
 * {@link HostRestoreTransaction}。这里一行都没有重写 —— 每一段都有它自己的测试。
 *
 * <h2>为什么必须两步（预检与提交分开）</h2>
 *
 * 因为**用户必须在两步之间看到会发生什么，然后才说「确定」**。
 * 一口气做完的「恢复」在界面上只有一个按钮，那是把确认的责任推给了运气：
 * 上游的 {@code prepare} 会在提交前重验目标（`TARGET_CHANGED`），
 * 但「目标没变」不等于「用户知道目标里有什么、即将被换掉什么」。
 *
 * <h2>状态机</h2>
 *
 * ```
 * FRESH --inspect--> INSPECTED(候选已就位) --prepare--> PREPARED --commit--> COMMITTED
 *   ^                    |                                   |
 *   +-------recover------+-----------------------------------+
 * ```
 *
 * **`inspect` 是只读的，`prepare` 也是只读的**，只有 `commit` 会写现有数据。
 * 这个划分不是审美：界面据此可以把「预检」做成随时可取消，
 * 而「提交」做成一次性的、必须点确认的。
 *
 * `commit` 之后再调任何方法都抛 `ALREADY_FINALIZED`。
 * 复用一个已完成的运行对象是界面状态管理错误的症状 —— 早失败比静默重来安全。
 */
public final class DshRestoreOrchestrator {

  /** 恢复能走到的状态。 */
  public enum Phase {
    FRESH,
    /** 预检完成，平行候选已就位。**现有数据一个字节都没动。** */
    INSPECTED,
    /** 事务已准备（目标重验通过）。**现有数据仍然没动。** */
    PREPARED,
    /** 已换入。 */
    COMMITTED,
  }

  /** 一个被选根的摘要。 */
  public static final class RootSummary {
    public final String id;
    /** 将写入的位置（相对 user-data，形如 `.dsh/sessions`）。 */
    public final String destination;

    RootSummary(String id, String destination) {
      this.id = id;
      this.destination = destination;
    }
  }

  /** 预检结果，给界面展示。**全部是只读信息。** */
  public static final class Report {
    /** 归档声明的范围 id。 */
    public final String scope;
    /** 归档清单里的 `operation` / `createdAt` / `appVersion` / `integrity` / `dataFormat`。 */
    public final Map<String, Object> provenance;
    public final List<RootSummary> roots;
    public final long conflicts;
    /** 上游的警告（例如某根在归档里没有可物化的记录）。 */
    public final List<String> warnings;
    /** 会**被覆盖**的那些根 —— 落点已存在。这是用户最需要看到的一栏。 */
    public final List<String> overwrites;

    Report(
        String scope,
        Map<String, Object> provenance,
        List<RootSummary> roots,
        long conflicts,
        List<String> warnings,
        List<String> overwrites) {
      this.scope = scope;
      this.provenance = provenance;
      this.roots = roots;
      this.conflicts = conflicts;
      this.warnings = warnings;
      this.overwrites = overwrites;
    }

    /** 给界面的一行摘要。不含任何用户内容，只有计数与名字。 */
    public String describe() {
      return roots.size()
          + " 个根 · 将覆盖 "
          + overwrites.size()
          + " 个 · 冲突 "
          + conflicts
          + (warnings.isEmpty() ? "" : " · 警告 " + warnings.size());
    }
  }

  private Phase phase = Phase.FRESH;
  private BackupFileSystem fs;
  private File task;
  private File home;
  private NativeRestorePlan plan;
  private List<String> groups;
  private HostRestoreTransaction transaction;
  private Report report;

  /**
   * 第一步：预检。
   *
   * <h3>为什么成员是参数，而不是在这里 import `DshBackupScope`</h3>
   *
   * 范围的词汇在 Kotlin 侧。这个类一旦 import 它，**本机就编不了**（没有 kotlinc）——
   * 而它是会毁数据的那条路径，只让 CI 编译它太贵。所以我第一版写出来时立刻在
   * `run-backup-jvm-tests.sh` 里红了三条 `cannot find symbol`。与 `DshBackupExporter`
   * 同一条约定：**这一层只认纯数据，翻译留在 Kotlin 侧。**
   *
   * @param archive 归档文件（例如下载目录里的那一份）
   * @param members 这个范围允许落地的那些根（`id` / `path` / `v5Scope`）。
   *     归档**实际**含哪些根由它的清单说了算；两边不一致时由
   *     {@link HostRestoreMapping} 拒绝（`ROOT_SCOPE` / `MEMBER_SCOPE`）——
   *     这一步刻意不在这里「猜」。
   * @param selectedScopes 用户选中的 **v5 范围名**（`sessions` / `settings` / `plugins` / …）。
   *
   *     <h3>为什么这个参数是安全属性，而不只是配置</h3>
   *
   *     上游把 `selected` 当成**范围名**集合，而且 `"application"` 在里面是**通配符** ——
   *     `selected.contains("application")` 会让归档里的**每一个**根都通过。
   *
   *     我第一版传的是「成员 id 的集合」（我读注释猜的语义），结果是
   *     `NO_RECOVERABLE_SCOPE`：一个根都没匹配上。**真跑又一次推翻了我的阅读。**
   *
   *     而正确的修法**不能**是「传 `Set.of("application")` 图省事」：
   *     那样用户选了「会话」却拿到一份全量归档时，设置与插件也会被一起恢复进去 ——
   *     而全量归档的根是按 `application` 记的。所以这个集合**必须**来自用户选的那个范围
   *     （`DshBackupScope.v5Scopes`），由 Kotlin 侧传进来。
   *
   *     归档里若有不属于这些范围的根，`mapping.skippedReason` 会给出一条警告 —— 那是如实报告。
   * @param scopeId 只用于写进报告，方便界面显示「这是按哪个范围检的」
   * @param userData 应用自己的 `user-data` 目录
   * @param taskDir 独占的任务目录，**必须不在** `userData` 之下
   */
  public Report inspect(
      BackupFileSystem fs,
      File archive,
      List<HostRestoreMapping.Member> members,
      Set<String> selectedScopes,
      String scopeId,
      File userData,
      File taskDir,
      BackupControl control)
      throws IOException {
    ensureNotFinalized();
    if (fs == null || archive == null || members == null || userData == null || taskDir == null)
      throw new IOException("RESTORE_ARGUMENTS");
    if (members.isEmpty()) throw new IOException("NO_MEMBERS");
    if (selectedScopes == null || selectedScopes.isEmpty())
      throw new IOException("NO_SELECTED_SCOPE");
    File homeDir = userData.getAbsoluteFile();
    File taskRoot = taskDir.getAbsoluteFile();
    if (!taskRoot.isDirectory()) throw new IOException("TASK_DIRECTORY_MISSING");
    if (isInside(homeDir, taskRoot)) throw new IOException("TASK_INSIDE_SOURCE");
    if (!archive.isFile()) throw new IOException("ARCHIVE_MISSING");

    // **自己建 UUID 子目录**，而不是要求调用方给一个 UUID 名字的目录。
    //
    // 上游的 `HostDataTransaction` 构造器会校验 `directory.getName()` 匹配 UUID 形状；
    // 我第一版把这件事推给调用方，然后自己的测试就 `TASK_NAME_NOT_UUID` 了。
    // 一个「你必须记得拼 UUID」的接口是**注定会被用错**的接口 ——
    // 而这个错只会在 prepare 阶段才响，那时界面已经展示了预检结果。
    // 所以这里自己建：调用方给一个**根**（例如 cacheDir 下的 restore-task），
    // 每次预检在里面开一个独占的子目录。
    File taskDirectory = new File(taskRoot, java.util.UUID.randomUUID().toString());
    try {
      fs.directory(taskDirectory);
    } catch (IOException error) {
      // `fs.directory` 对已存在的目录会抛 —— UUID 撞车在现实中不会发生，
      // 但如果真发生了，我们要的是**明确报出来**而不是继续。
      throw new IOException("TASK_DIRECTORY_CREATE", error);
    }

    HostRestoreMapping mapping = new HostRestoreMapping(homeDir, members);

    // ⚠️ 这个集合的语义我错了两次，两次都被真跑抓出来（第 22 条同族）：
    //   ① 先写「null = 全部根」→ 三处 `NullPointerException`（上游直接解引用它）；
    //   ② 再改成「成员 id 的集合」→ `NO_RECOVERABLE_SCOPE`（它收的是**范围名**）。
    // 而正确的值**不能**是通配的 `"application"` —— 见 `selectedScopes` 的参数说明。
    NativeRestorePlan built;
    try {
      built =
          NativeRestorePlan.inspect(
              fs, taskDirectory, archive, mapping, new LinkedHashSet<>(selectedScopes), control);
    } catch (IOException unreadable) {
      // 🔴 **上游对「归档被截断」抛的异常 message 是 null。**
      //
      // 实测（三种坏法各跑一遍）：
      //   截断到一半   → message 是 **null**
      //   整个不是容器 → `ARCHIVE_VERSION`
      //   中间翻一个字节 → `RECORD_CHECKSUM`
      //
      // 而界面把 `error.message` 原样显示给用户 —— 于是**最可能发生的那种损坏**
      // （拷贝中断、空间不足、传输截断）会显示成一句空话。
      // 阶段 2 的验收标准写的是「坏备份要**明确报错**」，null 不是明确。
      //
      // 修在这里而不是上游：`NativeRestorePlan` 是逐字节导入的副本（改它就没法逐字节核验了），
      // 而「给用户看一个能对照的错误码」本来就是适配层的活。
      String code = unreadable.getMessage();
      if (code != null && !code.isEmpty()) throw unreadable;
      throw new IOException(
          unreadable instanceof java.io.EOFException ? "ARCHIVE_TRUNCATED" : "ARCHIVE_UNREADABLE",
          unreadable);
    }
    List<String> groupNames = new ArrayList<>(built.groups.keySet());
    // `overwrite = false`：候选是**平行**的，落点上现有数据在这一步一个字节都不动。
    // 真正的合并/替换发生在 HostDataTransaction.prepare/commit。
    built.buildCandidates(false, control);

    List<String> overwrites = new ArrayList<>();
    List<RootSummary> summaries = new ArrayList<>();
    for (Map.Entry<String, NativeRestorePlan.Target> entry : built.groups.entrySet()) {
      File write = entry.getValue().write;
      if (write.exists()) overwrites.add(entry.getKey());
      summaries.add(new RootSummary(entry.getKey(), relativeTo(homeDir, write)));
    }

    Map<String, Object> provenance = new LinkedHashMap<>();
    for (String key :
        List.of("operation", "createdAt", "appVersion", "integrity", "dataFormat", "runtime")) {
      Object value = built.manifest.get(key);
      if (value != null) provenance.put(key, value);
    }

    this.fs = fs;
    this.task = taskDirectory;
    this.home = homeDir;
    this.plan = built;
    this.groups = groupNames;
    this.transaction = null;
    this.report =
        new Report(
            scopeId == null ? "" : scopeId,
            provenance,
            summaries,
            built.conflicts,
            new ArrayList<>(built.warnings),
            overwrites);
    this.phase = Phase.INSPECTED;
    return report;
  }

  /**
   * 第二步：准备事务 —— 重验目标、把候选与设置摆好。**仍然不写现有数据。**
   *
   * 它可能失败，而且**失败是它存在的意义之一**：`TARGET_CHANGED` 意味着
   * 「预检之后有人动过目标」，这时继续换入会把那次改动吃掉。
   */
  public void prepare(BackupControl control) throws IOException {
    ensureNotFinalized();
    requirePhase(Phase.INSPECTED, "PREPARE_WITHOUT_INSPECT");
    HostRestoreTransaction created = new HostRestoreTransaction(fs, task, plan);
    // 跨文件系统时上游的 `move` 会**静默退化成拷贝加删除**，
    // 于是「两次 rename 换进去」那个原子性就没了，而所有测试仍然全绿。
    // 这条检查是适配层加的（上游没有），必须在 prepare 之前跑。
    created.checkSameFileSystem(groups);
    created.prepare(groups, control);
    this.transaction = created;
    this.phase = Phase.PREPARED;
  }

  /** 第三步：提交。**从这里开始会写数据。** 只能调用一次。 */
  public void commit(BackupControl control) throws IOException {
    ensureNotFinalized();
    requirePhase(Phase.PREPARED, "COMMIT_WITHOUT_PREPARE");
    transaction.commit(control);
    this.phase = Phase.COMMITTED;
  }

  /**
   * 崩溃或失败后的恢复入口。任何阶段都能调；没有可恢复的东西时它是空操作。
   *
   * 调完之后回到 `FRESH` —— **不是** `INSPECTED`：预检结果对应的那份候选可能已经被
   * 回切动过，拿它继续提交是不安全的，要重新检一遍。
   */
  public void recover() throws IOException {
    // 已经换入完成的运行没有「可恢复的东西」。这里**保持 COMMITTED** 而不是回到 FRESH ——
    // 回到 FRESH 会让界面以为「还能再来一次」，而那个事务已经收尾了。
    if (phase == Phase.COMMITTED) return;
    if (transaction != null) transaction.recover();
    this.transaction = null;
    this.plan = null;
    this.groups = null;
    this.report = null;
    this.phase = Phase.FRESH;
  }

  /** 上一次预检结果；没检过或已 `recover` 则是 null。 */
  public Report report() {
    return report;
  }

  /**
   * 这次运行实际用的任务目录（`inspect` 建的那个 UUID 子目录）。
   *
   * 刻意**不自动删除**：提交后的五相位标记是排查问题的第一手证据。
   * 清理交给调用方（界面在 `finally` 里删整个任务根）。
   */
  public File taskDirectory() {
    return task;
  }

  public Phase phase() {
    return phase;
  }

  /** 未来状态？只给界面判断按钮可用性用，不暴露内部对象。 */
  public boolean mayCommit() {
    return phase == Phase.PREPARED;
  }

  private void ensureNotFinalized() throws IOException {
    if (phase == Phase.COMMITTED) throw new IOException("ALREADY_FINALIZED");
  }

  private void requirePhase(Phase want, String code) throws IOException {
    if (phase != want) throw new IOException(code);
  }

  /** `home` 是 `write` 的祖先时给出相对路径；否则原样给绝对路径（不猜）。 */
  static String relativeTo(File home, File write) {
    String base = home.getAbsolutePath();
    String full = write.getAbsolutePath();
    if (full.equals(base)) return ".";
    if (full.startsWith(base + File.separator)) return full.substring(base.length() + 1);
    return full;
  }

  static boolean isInside(File ancestor, File candidate) {
    File a = ancestor.getAbsoluteFile();
    File c = candidate.getAbsoluteFile();
    while (c != null) {
      if (c.equals(a)) return true;
      c = c.getParentFile();
    }
    return false;
  }
}
