// DSHFusion 自写（**不是**从 DSHA 移植）。见 backup/README.md「为什么这个类是 Java」。
package com.dshbox.app.backup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动备份的**保留策略**（保留最新 N 份）。本仓库自写，**不能**直接搬上游的
 * `AutomaticBackupPruner` —— 理由见下，而且那条理由有测试证明。
 *
 * ## 为什么这里必须自写（审计结论 + 一个可证伪的实验）
 *
 * 上游的 `AutomaticBackupPruner` 在删之前要求：
 *
 * ```java
 * Set<String> allowed = Set.of("portable.dshbak", "verified.json", "operation.json", "source-checks");
 * if (!fs.list(directory).stream().allMatch(allowed::contains)) continue;
 * ```
 *
 * 也就是说，它的自动备份是**一个目录、里面四个具名成员**，而
 * **我们的是一个单文件**（`DSHDATA` 容器，名字形如 `DSHFusion-backup-<stamp>.dshbak`，
 * 见 `DshBackupScope.archiveName`）。
 *
 * 直接抄过来的后果不是「命名对不上」这么轻 —— 是**它一条都不会删**：
 * `fs.list(file)` 对文件会抛，而就算把它改成读文件名，`allowed` 也永远不匹配。
 * 于是一个**静默的空操作**：用户以为「只保留 3 份」，实际磁盘上无限增长。
 *
 * > ⚠️ 这类「抄过来什么也没做，但没有任何报错」是最难发现的形态 ——
 * > 它长得和「确实没有需要删的」一模一样。`DshAutomaticBackupRetentionTest`
 * > 里有一条用例**专门把上游那套判据跑在我们的命名上**，断言它确实什么都不删。
 * > 这就是审计结论的**可证伪形式**（事故表第 28 条）。
 *
 * ## 三条比上游更严的规则
 *
 * 1. **只碰 `MAINTENANCE`。** 手动导出是 `EXPORT`，救援是 `RESCUE` ——
 *    用户手动打的那一份**永远不在候选里**。上游用 `metadata.automatic` 标记，
 *    我们用的是容器本来就强制的 `operation` 字段，不引入第二个真相来源。
 * 2. **只删「能证明是完整的」那一些。** `PARTIAL`/`BEST_EFFORT` 表示当时就有东西没读到，
 *    它们**不参与计数、也不被删**，而是如实报给调用方。上游在这里是 `continue`
 *    （静默跳过）；我们把它放进 {@link Result#unverified}，因为「跳过」和「没有」
 *    在结果上无法区分，而那正是第 6/13/16/18/24/26 条那个病的形态。
 * 3. **候选必须来自解析过的 manifest，不接受目录列举。** 见 {@link Archive} 的说明。
 */
public final class DshAutomaticBackupRetention {

  /** 计划备份用的 operation 取值。`BackupArchive` 本来就允许这三个，不需要改格式。 */
  public static final String OPERATION = "MAINTENANCE";

  /** 默认保留份数。 */
  public static final int KEEP = 3;

  /** 声明为「完整」的 integrity 取值。其余一律不参与删除。 */
  private static final Set<String> COMPLETE = Set.of("QUIESCENT", "EXTERNAL_CHECKED");

  private DshAutomaticBackupRetention() {}

  /**
   * 一份**已经解析过 manifest** 的归档。
   *
   * ⚠️ 字段全部来自 manifest 本身，而不是文件名。这是刻意的：
   * 若允许调用方从目录列举里构造候选，那么「按前缀删文件」就成了一条真实路径 ——
   * 而我们的归档名是 `<范围前缀><时间戳><扩展名>`，前缀在多个范围之间是**共享的**
   * （`DSHFusion-backup-` / `DSHFusion-sessions-` …）。删错范围的数据是不可逆的。
   */
  public static final class Archive {
    /** 归档文件名（不含目录）。只用于回报，不参与判定。 */
    public final String name;
    /** manifest 的 `createdAt`。 */
    public final long createdAt;
    /** manifest 的 `operation`。 */
    public final String operation;
    /** manifest 的 `integrity`。 */
    public final String integrity;

    public Archive(String name, long createdAt, String operation, String integrity) {
      this.name = name == null ? "" : name;
      this.createdAt = createdAt;
      this.operation = operation == null ? "" : operation;
      this.integrity = integrity == null ? "" : integrity;
    }
  }

  /** 结果：要删的、被保留的、以及**为什么没被删**的。 */
  public static final class Result {
    /** 按 `createdAt` 从新到旧的完整排序（含未被选中的）。 */
    public final List<Archive> ordered = new ArrayList<>();
    /** 超出保留份数、可以删的。 */
    public final List<Archive> expired = new ArrayList<>();
    /** 是自动备份、但因为**证明不了完整**而没参与计数、也没被删的。 */
    public final List<Archive> unverified = new ArrayList<>();

    public boolean isEmpty() {
      return expired.isEmpty();
    }

    /** 名字 → 原因，供调用方原样展示（不静默）。 */
    public Map<String, String> explain() {
      Map<String, String> out = new LinkedHashMap<>();
      for (Archive a : expired) out.put(a.name, "超出保留份数（保留最新 " + KEEP + " 份）");
      for (Archive a : unverified) {
        out.put(a.name, "integrity=" + a.integrity + " 证明不了完整，不删也不计数");
      }
      return out;
    }
  }

  /**
   * 选出可以删的自动备份。
   *
   * @param archives 调用方**已经解析过 manifest** 的归档；顺序无关。
   * @param keep 保留份数；小于 1 时按 1 处理（**不允许「全删」** ——
   *     一个把用户全部备份删光的保留策略，比没有保留策略危险得多）。
   */
  public static Result select(List<Archive> archives, int keep) {
    int wanted = Math.max(1, keep);
    Result result = new Result();
    if (archives == null) return result;

    List<Archive> automatic = new ArrayList<>();
    for (Archive archive : archives) {
      if (archive == null) continue;
      result.ordered.add(archive);
      if (!OPERATION.equals(archive.operation)) continue; // 手动导出/救援永不入候选
      if (!COMPLETE.contains(archive.integrity)) {
        result.unverified.add(archive);
        continue;
      }
      automatic.add(archive);
    }

    // 从新到旧。**两个键同向**（都降序）—— 这一点不是随便定的：
    // 归档名里就嵌着时间戳（`DSHFusion-backup-<stamp>.dshbak`），所以名字的字典序
    // 本身就近似时间序。若让名字升序而时间降序，平局时保留的就是**字典序最小**的那几份，
    // 与「保留最新的」在直觉上相反 —— 而这类「反正都是确定的」的次序最容易被当成不重要。
    // 实测过：我第一版就是升序，`tiesAreBrokenStablyByName` 当场红了。
    Comparator<Archive> newestFirst =
        Comparator.comparingLong((Archive a) -> a.createdAt)
            .reversed()
            .thenComparing(Comparator.comparing((Archive a) -> a.name).reversed());
    automatic.sort(newestFirst);
    result.ordered.sort(newestFirst);

    for (int index = wanted; index < automatic.size(); index++) result.expired.add(automatic.get(index));
    return result;
  }

  public static Result select(List<Archive> archives) {
    return select(archives, KEEP);
  }
}
