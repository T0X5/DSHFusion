package com.dshfusion.app.backup;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.HostDataTransaction;
import com.deepseekharness.app.backup.NativeRestorePlan;
import com.deepseekharness.app.util.Ids;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「我们的恢复计划」接到「上游的多根事务」上的**适配层**。本仓库自写（Java，为能本机真跑）。
 *
 * ## 为什么是适配层，而不是又一份实现
 *
 * 阶段 2.4b-② 我**从零手写过**一份提交与回切，理由是「上游那个类依赖它自己的
 * `ConfigStore`/`KeyVault`」。**那个理由是错的** —— 我写下它的时候没有读那个文件。
 * 实测 `HostDataTransaction` 只有 `java.io`/`java.util` 两个 import，一次都没提到
 * `ConfigStore`/`KeyVault`/`CredentialRead`，它**就在可达范围内**，而且比我写的那份更严密
 * （重验 `before` **和** `after`、`OVERLAPPING_TARGETS`、五相位持久标记、相位+摘要双判恢复、
 * 真杀子进程的测试）。手写的那一份已删除，不再保留两套。
 * 见 [docs/INCIDENTS.md](../../../../../../../../docs/INCIDENTS.md) 第 33 条。
 *
 * ## 两边的词汇**恰好同构**，所以适配层很薄
 *
 * | 上游 | 我们 |
 * |---|---|
 * | `directory/candidate/<id>` | `NativeRestorePlan` 的 `task/candidate/<group>`（逐字同名） |
 * | `Targets.resolve(rootId)` | `plan.targets.get(group).write` |
 * | `prepare(roots, expectedBefore, …)` 的 `expectedBefore` | `plan.before` |
 * | `Settings`（设置轴） | **我们没有这个轴**，见下 |
 *
 * ## 上游**没有**的那一条检查：`CROSS_DEVICE`
 *
 * `HostDataTransaction` 直接 `fs.move`。跨文件系统时 `move` **不报错**，只是退化成
 * 拷贝加删除 —— 于是「两次 rename 换进去」这个原子性**静默消失**，
 * 而所有测试仍然全绿。这是上游的缺口，不是我们的偏离，所以补在**适配层**里，
 * 上游那个类保持逐字节不动。
 *
 * ## 设置轴：我们**没有**它，所以让它**拒绝**而不是静默空转
 *
 * `HostDataTransaction` 会在提交时一并切换一份 `settings.json`。我们的恢复路径只写
 * 用户数据，没有设置轴。若给它一个「恒返回空表、apply 什么都不做」的实现，
 * 那么上游那三处设置断言（`SETTINGS_CHANGED` / `SETTINGS_COMMIT_VERIFICATION`）
 * 会**永远通过** —— 那是第 30 条说的「断言没有输入空间」。
 * 所以这里反过来：空表才接受，**非空表直接抛 `SETTINGS_NOT_SUPPORTED`**。
 * 将来真要做设置轴时它会红，而不是静默丢掉用户的设置。
 *
 * ## 调用方必须先满足的两条前提
 *
 * 1. `task` 目录名必须是 `Ids.UUID_PATTERN` 形状 —— 上游用它给事务命名，
 *    而事务名会被写进 `plan.json` 与每个相位标记。不满足时上游抛 `TRANSACTION_ID`，
 *    本类提前用更明确的 `TASK_NAME_NOT_UUID` 拦下。
 * 2. `task` 必须**已经存在且是目录**（上游构造器直接 `fs.stat`）。
 */
public final class HostRestoreTransaction {

  private final BackupFileSystem fs;
  private final File task;
  private final NativeRestorePlan plan;
  private final HostDataTransaction transaction;

  public HostRestoreTransaction(BackupFileSystem fs, File task, NativeRestorePlan plan)
      throws IOException {
    this(fs, task, plan, null);
  }

  /**
   * 带故障注入钩子的构造器。`fault` 为 null 时是空实现。
   *
   * ⚠️ **这个钩子是上游的设计，比我自己那套好，值得说明白。**
   * 阶段 2.4b-② 我手写的那版按「第 N 次 `move`」注入，结果 `fs.atomic()` 内部也在 `move`，
   * 于是注入打在了别的地方，而**三个用例全是绿的**（第 31 条）。
   * 上游按**命名边界**注入（`fault.boundary("old-" + id)`、`"files-committed"`…），
   * 名字由代码自己给出，注入点不依赖任何计数 —— 从根上不会有那个病。
   */
  public HostRestoreTransaction(
      BackupFileSystem fs, File task, NativeRestorePlan plan, HostDataTransaction.Fault fault)
      throws IOException {
    this.fs = fs;
    this.task = task;
    this.plan = plan;
    if (task == null) throw new IOException("NO_TASK");
    if (!Ids.uuid(task.getName())) throw new IOException("TASK_NAME_NOT_UUID");
    this.transaction = new HostDataTransaction(fs, task, this::resolve, new NoSettings(), fault);
  }

  /** `Targets`：归档的逻辑根 → 本机活动目录。没见过的根一律拒绝，不猜。 */
  private File resolve(String rootId) throws IOException {
    NativeRestorePlan.Target target = plan.targets.get(rootId);
    if (target == null) throw new IOException("UNKNOWN_GROUP");
    return target.write;
  }

  /**
   * **上游没有的检查**：`task` 与每个活动目标的父目录必须同一个文件系统。
   *
   * 必须在 `prepare` 之前调用。不成立时 `move` 会退化成拷贝，原子性静默消失。
   */
  public void checkSameFileSystem(List<String> groups) throws IOException {
    if (groups == null || groups.isEmpty()) throw new IOException("NO_GROUPS");
    long taskDevice = fs.stat(task).device;
    if (taskDevice == 0) throw new IOException("DEVICE_UNKNOWN");
    for (String group : groups) {
      File target = resolve(group);
      File parent = target.getParentFile();
      if (parent == null || !fs.stat(parent).type.equals("DIRECTORY"))
        throw new IOException("TARGET_PARENT_MISSING");
      if (fs.stat(parent).device != taskDevice) throw new IOException("CROSS_DEVICE");
    }
  }

  /**
   * 预检 + 落盘 `plan.json` + `verified` 标记。**一次都不碰活动目录。**
   *
   * 上游在这里重新算一遍每个根的活动摘要并与 `plan.before` 比 —— 这就是
   * `TARGET_CHANGED`。所以调用方不必自己再比一次，但**必须**在候选建好之后、
   * 用户可能又写数据之前尽快调用。
   */
  public void prepare(List<String> groups, BackupControl control) throws IOException {
    if (groups == null || groups.isEmpty()) throw new IOException("NO_GROUPS");
    List<String> roots = new ArrayList<>(groups);
    transaction.prepare(roots, plan.before, new LinkedHashMap<>(), control);
  }

  /** 换入候选；失败自动回切（回切本身再失败时把异常挂成 suppressed）。 */
  public void commit(BackupControl control) throws IOException {
    transaction.commit(control);
  }

  /** 崩溃恢复。相位 + 摘要双判；认不出时报上游的具体错误码，**不猜、不覆盖**。 */
  public void recover() throws IOException {
    transaction.recover();
  }

  public boolean finalized() throws IOException {
    return transaction.finalized();
  }

  /**
   * 设置轴的空实现。**故意会红**：非空值一律拒绝。
   *
   * 上游的三处设置断言在空表下恒真 —— 若这里默默接受非空值，那三处就成了
   * 「没有输入空间的断言」（第 30 条）。将来接上设置轴时，先改这里，让它真的做事。
   */
  static final class NoSettings implements HostDataTransaction.Settings {

    @Override
    public Map<String, Object> current() {
      return new LinkedHashMap<>();
    }

    @Override
    public void apply(Map<String, Object> values) throws IOException {
      if (values != null && !values.isEmpty()) throw new IOException("SETTINGS_NOT_SUPPORTED");
    }
  }
}
