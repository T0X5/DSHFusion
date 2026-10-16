// DSHFusion 自写（**不是**从 DSHA 移植）。见 backup/README.md「为什么这个类是 Java」。
package com.dshfusion.app.backup;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * 阶段 2.5：自动备份的保留策略。
 *
 * 这里最有价值的一条是 {@link #upstreamPrunerJudgementMatchesNothingOfOurs()} ——
 * 它把「为什么不能直接抄上游的 pruner」从一个**说法**变成一个**会红的实验**。
 */
public class DshAutomaticBackupRetentionTest {

  private static DshAutomaticBackupRetention.Archive auto(String name, long at, String integrity) {
    return new DshAutomaticBackupRetention.Archive(
        name, at, DshAutomaticBackupRetention.OPERATION, integrity);
  }

  private static DshAutomaticBackupRetention.Archive manual(String name, long at) {
    return new DshAutomaticBackupRetention.Archive(name, at, "EXPORT", "QUIESCENT");
  }

  private static List<String> names(List<DshAutomaticBackupRetention.Archive> list) {
    List<String> out = new ArrayList<>();
    for (DshAutomaticBackupRetention.Archive a : list) out.add(a.name);
    return out;
  }

  // ---------- 正常路径 ----------

  @Test
  public void keepsTheNewestThreeAndExpiresTheRest() {
    List<DshAutomaticBackupRetention.Archive> all =
        List.of(
            auto("a.dshbak", 100, "QUIESCENT"),
            auto("b.dshbak", 200, "QUIESCENT"),
            auto("c.dshbak", 300, "QUIESCENT"),
            auto("d.dshbak", 400, "QUIESCENT"),
            auto("e.dshbak", 500, "QUIESCENT"));

    DshAutomaticBackupRetention.Result r = DshAutomaticBackupRetention.select(all);

    // 保留最新的三份（e/d/c），删最早的两份（b/a）
    assertEquals(List.of("b.dshbak", "a.dshbak"), names(r.expired));
    assertEquals(5, r.ordered.size());
    assertEquals("e.dshbak", r.ordered.get(0).name);
    assertEquals("a.dshbak", r.ordered.get(4).name);
  }

  @Test
  public void fewerThanKeepDeletesNothing() {
    DshAutomaticBackupRetention.Result r =
        DshAutomaticBackupRetention.select(
            List.of(auto("a.dshbak", 100, "QUIESCENT"), auto("b.dshbak", 200, "QUIESCENT")));
    assertTrue(r.isEmpty());
    assertTrue(r.expired.isEmpty());
  }

  // ---------- 比上游更严的三条规则 ----------

  /**
   * 手动导出的那一份**永远**不在候选里。
   *
   * 上游用 `metadata.automatic` 标记；我们用容器本来就强制的 `operation` ——
   * 少一个真相来源，就少一处「夹具与源码各说各话」的机会（事故表第 17 条）。
   */
  @Test
  public void manualExportsAndRescuesAreNeverCandidates() {
    List<DshAutomaticBackupRetention.Archive> all = new ArrayList<>();
    all.add(manual("manual-old.dshbak", 1)); // 最旧，而且是手动的
    all.add(new DshAutomaticBackupRetention.Archive("rescue.dshbak", 2, "RESCUE", "QUIESCENT"));
    for (int i = 0; i < 5; i++) all.add(auto("auto-" + i + ".dshbak", 100 + i, "QUIESCENT"));

    DshAutomaticBackupRetention.Result r = DshAutomaticBackupRetention.select(all);

    // 手动的两份**一份都不许删**，哪怕它们是最旧的
    assertFalse(names(r.expired).contains("manual-old.dshbak"));
    assertFalse(names(r.expired).contains("rescue.dshbak"));
    // 只有 5 份自动的参与计数 → 删掉最旧的 2 份。
    // `expired` 与 `ordered` 同序（**最新在前**），所以是 auto-1 在前 ——
    // 第一版我把这个顺序写反了，断言当场红（次序也是规格的一部分）。
    assertEquals(List.of("auto-1.dshbak", "auto-0.dshbak"), names(r.expired));
  }

  /**
   * `PARTIAL` / `BEST_EFFORT` 表示当时就有东西没读到 —— 它们**既不计数也不被删**，
   * 而且要**报出来**。
   *
   * 上游在这里是 `continue`（静默跳过）。「跳过」和「本来就没有」在结果上无法区分，
   * 而那正是事故表第 6/13/16/18/24/26 条那个病：门禁的覆盖面小于它宣称的覆盖面。
   */
  @Test
  public void unverifiedArchivesAreNeitherCountedNorDeletedButAreReported() {
    List<DshAutomaticBackupRetention.Archive> all = new ArrayList<>();
    all.add(auto("partial.dshbak", 1, "PARTIAL")); // 最旧，但证明不了完整
    all.add(auto("best-effort.dshbak", 2, "BEST_EFFORT"));
    for (int i = 0; i < 4; i++) all.add(auto("ok-" + i + ".dshbak", 100 + i, "QUIESCENT"));

    DshAutomaticBackupRetention.Result r = DshAutomaticBackupRetention.select(all);

    assertFalse(names(r.expired).contains("partial.dshbak"));
    assertFalse(names(r.expired).contains("best-effort.dshbak"));
    // 它们被报出来，而不是被静默跳过
    assertEquals(2, r.unverified.size());
    assertTrue(r.explain().get("partial.dshbak").contains("PARTIAL"));
    // 只有 4 份完整的参与计数 → 删最旧的 1 份
    assertEquals(List.of("ok-0.dshbak"), names(r.expired));
  }

  /** 保留份数小于 1 时按 1 处理 —— 一个把用户全部备份删光的策略比没有策略更危险。 */
  @Test
  public void keepIsNeverZero() {
    List<DshAutomaticBackupRetention.Archive> all =
        List.of(
            auto("a.dshbak", 100, "QUIESCENT"),
            auto("b.dshbak", 200, "QUIESCENT"),
            auto("c.dshbak", 300, "QUIESCENT"));

    for (int keep : new int[] {0, -5, Integer.MIN_VALUE}) {
      DshAutomaticBackupRetention.Result r = DshAutomaticBackupRetention.select(all, keep);
      assertEquals("keep=" + keep + " 时删了 " + r.expired.size() + " 份", 2, r.expired.size());
      assertEquals(List.of("b.dshbak", "a.dshbak"), names(r.expired));
    }
  }

  /**
   * `createdAt` 相同时按文件名定序 —— **稳定且可复现**。
   *
   * 若不定序，同一批输入在不同机器上可能选出不同的删除集。
   * 那是不可复现的破坏，而且**测不出来**（每次跑都「通过」）。
   */
  @Test
  public void tiesAreBrokenStablyByName() {
    List<DshAutomaticBackupRetention.Archive> all =
        List.of(
            auto("d.dshbak", 500, "QUIESCENT"),
            auto("b.dshbak", 500, "QUIESCENT"),
            auto("a.dshbak", 500, "QUIESCENT"),
            auto("c.dshbak", 500, "QUIESCENT"));

    DshAutomaticBackupRetention.Result first = DshAutomaticBackupRetention.select(all);
    // 打乱输入顺序，结果必须一样
    List<DshAutomaticBackupRetention.Archive> shuffled = new ArrayList<>(all);
    java.util.Collections.reverse(shuffled);
    DshAutomaticBackupRetention.Result second = DshAutomaticBackupRetention.select(shuffled);

    assertEquals(List.of("a.dshbak"), names(first.expired));
    assertEquals(names(first.expired), names(second.expired));
  }

  // ---------- 审计结论的可证伪形式 ----------

  /**
   * **上游那套判据跑在我们的产物命名上，一份都删不掉。**
   *
   * 这是「不能直接抄 `AutomaticBackupPruner`」这个审计结论的实验形式。
   * 没有它，那句话就只是一句**说法** —— 而事故表第 28 条正是「审计结论看起来对，
   * 但没有任何东西能推翻它」。
   *
   * 上游的判据（逐字）：
   *
   * ```java
   * Set<String> allowed = Set.of("portable.dshbak", "verified.json", "operation.json", "source-checks");
   * if (!fs.list(directory).stream().allMatch(allowed::contains)) continue;
   * ```
   *
   * 它面对的是**一个目录、四个具名成员**。我们的是**单文件**。
   * 下面用**对上游最有利**的读法（把文件名当成它唯一的内容）来判 ——
   * 再有利一点，它就得先能「列举一个文件」。
   */
  @Test
  public void upstreamPrunerJudgementMatchesNothingOfOurs() {
    Set<String> upstreamAllowed =
        Set.of("portable.dshbak", "verified.json", "operation.json", "source-checks");

    // 我们真实的产物名（来自 DshBackupScope.archiveName 的前缀）
    List<String> ours =
        List.of(
            "DSHFusion-backup-20261015-0300.dshbak",
            "DSHFusion-sessions-20261015-0300.dshbak",
            "DSHFusion-settings-20261015-0300.dshbak",
            "DSHFusion-plugins-20261015-0300.dshbak");

    for (String name : ours) {
      boolean upstreamWouldPrune =
          Set.of(name).stream().allMatch(upstreamAllowed::contains);
      assertFalse(
          "上游的判据居然认了 " + name + " —— 那说明我关于「两边产物形状不同」的审计结论是错的",
          upstreamWouldPrune);
    }

    // 而我们的策略**确实**会删（否则这条用例证明的只是「两边都不删」，
    // 那就成了一个两边都绿、什么也没区分开的断言）。
    List<DshAutomaticBackupRetention.Archive> all = new ArrayList<>();
    for (int i = 0; i < 5; i++)
      all.add(auto("DSHFusion-backup-2026101" + i + "-0300.dshbak", 100 + i, "QUIESCENT"));
    DshAutomaticBackupRetention.Result r = DshAutomaticBackupRetention.select(all);
    assertEquals(2, r.expired.size());
  }
}
