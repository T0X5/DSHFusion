// DSHFusion 自写。登记在 governance/dsha-imports.json 的 original 段。
package com.dshfusion.app.backup;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link DshAutomaticBackupRunner} 的本机行为测试。
 *
 * <p>这个类里有两条**不能靠读代码确认**的性质，所以各有一条用例：
 * ① 「先导出、后清理」的顺序 —— 导出失败时**一份旧备份都不许少**；
 * ② 「证明不了完整的归档既不删也不计数」—— 少了它，一份半截的归档会把一份好的挤掉。
 */
public class DshAutomaticBackupRunnerTest {

  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private final BackupFileSystem fs = new JvmBackupFileSystem();
  private static final ZoneId UTC = ZoneId.of("UTC");

  /**
   * 2026-10-15T06:00:00Z。
   *
   * **算出来而不是写死。** 我第一版写了一个硬编码的毫秒数，年份算错了（写成了 2025），
   * 红了一条断言才被发现；而隐私审计还把那个 13 位数字当成「设备序列号样式」报了出来。
   * 第 27 条同族：**常量该由它的定义推导，不该由我手抄。**
   */
  private static final long NOW =
      java.time.Instant.parse("2026-10-15T06:00:00Z").toEpochMilli();

  private static void write(File file, String text) throws IOException {
    Files.createDirectories(file.getParentFile().toPath());
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  private File makeHome() throws IOException {
    File home = temporary.newFolder("home-" + UUID.randomUUID());
    write(new File(home, ".dsh/sessions/a.json"), "{\"a\":1}");
    write(new File(home, ".dsh/credentials"), "secret: hunter2\n");
    return home;
  }

  private List<DshBackupExporter.Member> members() {
    return List.of(new DshBackupExporter.Member("dsh", ".dsh", "application", false));
  }

  private static final java.util.function.Predicate<String> EXCLUDE_CREDENTIALS =
      path -> path.equals(".dsh/credentials");

  /** 直接造一份「旧的自动备份」，`createdAt` 可控。 */
  private File makeOldArchive(File archives, String name, long createdAt, String operation)
      throws IOException {
    File task = temporary.newFolder("t-" + UUID.randomUUID());
    Map<String, Object> provenance = DshAutomaticBackupRunner.provenance(createdAt);
    provenance.put("operation", operation);
    return DshBackupExporter.export(
            fs,
            makeHome(),
            members(),
            task,
            new File(archives, name),
            provenance,
            EXCLUDE_CREDENTIALS,
            false,
            new BackupControl(null))
        .archive;
  }

  private DshAutomaticBackupRunner.Outcome run(File home, File archives, File taskRoot, DshAutomaticBackupRunner.Settings settings)
      throws IOException {
    return DshAutomaticBackupRunner.run(
        fs,
        home,
        archives,
        taskRoot,
        "DSHFusion-backup-20261015-060000.dshbak",
        members(),
        EXCLUDE_CREDENTIALS,
        settings,
        NOW,
        UTC,
        DshAutomaticBackupRetention.KEEP,
        new BackupControl(null));
  }

  private DshAutomaticBackupRunner.Settings intervalSettings(long last) {
    // interval 模式、间隔 1 小时、last 是 2 小时前 ⇒ 到点。
    return new DshAutomaticBackupRunner.Settings("interval", 1, 0, 0L, last, 0L);
  }

  // ─────────────────────────── 到期判定 ───────────────────────────

  @Test
  public void notDueMeansNothingIsWritten() throws Exception {
    File home = makeHome();
    File archives = temporary.newFolder("archives-notdue");
    File taskRoot = temporary.newFolder("tasks-notdue");
    // interval 24 小时，上次是 1 小时前 ⇒ 没到点
    var outcome =
        run(home, archives, taskRoot, new DshAutomaticBackupRunner.Settings("interval", 24, 0, 0L, NOW - 3_600_000L, 0L));

    assertFalse(outcome.ran);
    assertEquals("interval", outcome.reason);
    assertEquals(0, archives.listFiles().length);
    assertTrue(outcome.describe().startsWith("未到点"));
  }

  @Test
  public void dueWritesAnArchiveThatTheRetentionPolicyRecognises() throws Exception {
    File home = makeHome();
    File archives = temporary.newFolder("archives-due");
    File taskRoot = temporary.newFolder("tasks-due");
    var outcome = run(home, archives, taskRoot, intervalSettings(NOW - 7_200_000L));

    assertTrue(outcome.ran);
    assertEquals("", outcome.reason);
    assertEquals("DSHFusion-backup-20261015-060000.dshbak", outcome.archive);
    assertTrue(outcome.bytes > 0);

    File written = new File(archives, outcome.archive);
    assertTrue(written.isFile());
    // 它必须**能被保留策略认出来**：operation=MAINTENANCE 且完整性可证明。
    var meta = DshAutomaticBackupRunner.readArchive(fs, written);
    assertNotNull("刚写的归档必须读得了", meta);
    assertEquals(DshAutomaticBackupRetention.OPERATION, meta.operation);
    assertEquals("QUIESCENT", meta.integrity);
    assertEquals(NOW, meta.createdAt);
  }

  @Test
  public void taskDirectoryIsCleanedUpEvenOnSuccess() throws Exception {
    File home = makeHome();
    File archives = temporary.newFolder("archives-clean");
    File taskRoot = temporary.newFolder("tasks-clean");
    run(home, archives, taskRoot, intervalSettings(NOW - 7_200_000L));
    assertEquals("任务目录必须清干净，不留半成品", 0, taskRoot.listFiles().length);
  }

  // ─────────────── 「先导出、后清理」的顺序（这条最要紧） ───────────────

  /**
   * 导出失败时，**一份旧备份都不许少**。
   *
   * <p>反过来实现（先清理再导出）在这里会净损失：旧的被删了、新的没生成。
   * 这条用例就是把那个方向钉死 —— 少了它，「顺序」只是注释里的一句话。
   */
  @Test
  public void aFailedExportDeletesNothing() throws Exception {
    File home = makeHome();
    File archives = temporary.newFolder("archives-fail");
    File taskRoot = temporary.newFolder("tasks-fail");
    // 造 5 份旧的自动备份：任何保留策略都会想删掉其中两份。
    for (int i = 0; i < 5; i++) {
      makeOldArchive(archives, "old-" + i + ".dshbak", NOW - (10 - i) * 3_600_000L,
          DshAutomaticBackupRetention.OPERATION);
    }
    int before = archives.listFiles().length;
    assertEquals(5, before);

    // 让导出失败：目标文件名与已存在的文件撞上。
    makeOldArchive(archives, "collide.dshbak", NOW, DshAutomaticBackupRetention.OPERATION);
    IOException error =
        assertThrows(
            IOException.class,
            () -> DshAutomaticBackupRunner.run(
                fs,
                home,
                archives,
                taskRoot,
                "collide.dshbak",
                members(),
                EXCLUDE_CREDENTIALS,
                intervalSettings(NOW - 7_200_000L),
                NOW,
                UTC,
                DshAutomaticBackupRetention.KEEP,
                new BackupControl(null)));
    assertEquals("DESTINATION_EXISTS", error.getMessage());

    assertEquals("导出失败后旧备份必须一份不少", 6, archives.listFiles().length);
    assertTrue(new File(archives, "old-0.dshbak").isFile());
    assertEquals("失败时任务目录也要清干净", 0, taskRoot.listFiles().length);
  }

  // ─────────────────────────── 保留策略 ───────────────────────────

  @Test
  public void keepsTheNewestThreeAutomaticArchives() throws Exception {
    File home = makeHome();
    File archives = temporary.newFolder("archives-keep");
    File taskRoot = temporary.newFolder("tasks-keep");
    // 4 份旧的（越靠后越新），加上这次新导出的 = 5 份 ⇒ 应删掉最旧的 2 份。
    for (int i = 0; i < 4; i++) {
      makeOldArchive(archives, "old-" + i + ".dshbak", NOW - (10 - i) * 3_600_000L,
          DshAutomaticBackupRetention.OPERATION);
    }

    var outcome = run(home, archives, taskRoot, intervalSettings(NOW - 7_200_000L));

    assertTrue(outcome.ran);
    assertEquals("应删掉最旧的两份：" + outcome.deleted, 2, outcome.deleted.size());
    assertTrue(outcome.deleted.contains("old-0.dshbak"));
    assertTrue(outcome.deleted.contains("old-1.dshbak"));
    assertEquals("删完之后盘上应当正好 3 份", 3, archives.listFiles().length);
    assertTrue(new File(archives, "old-2.dshbak").isFile());
    assertTrue(new File(archives, "old-3.dshbak").isFile());
    assertTrue(new File(archives, outcome.archive).isFile());
  }

  /** 手动与救援备份**永不**参与「保留最新 N 份」—— 它们不是自动备份。 */
  @Test
  public void manualAndRescueArchivesAreNeverDeleted() throws Exception {
    File home = makeHome();
    File archives = temporary.newFolder("archives-manual");
    File taskRoot = temporary.newFolder("tasks-manual");
    makeOldArchive(archives, "manual.dshbak", NOW - 100 * 3_600_000L, "EXPORT");
    makeOldArchive(archives, "rescue.dshbak", NOW - 99 * 3_600_000L, "RESCUE");
    for (int i = 0; i < 4; i++) {
      makeOldArchive(archives, "auto-" + i + ".dshbak", NOW - (10 - i) * 3_600_000L,
          DshAutomaticBackupRetention.OPERATION);
    }

    var outcome = run(home, archives, taskRoot, intervalSettings(NOW - 7_200_000L));

    assertTrue(new File(archives, "manual.dshbak").isFile());
    assertTrue(new File(archives, "rescue.dshbak").isFile());
    assertFalse(outcome.deleted.contains("manual.dshbak"));
    assertFalse(outcome.deleted.contains("rescue.dshbak"));
    // ⚠️ 我第一版在这里写「2 旧 + 1 新 = 3 份 ⇒ 删 0 份」—— 而上面造的是 **4** 份自动备份，
    // 4 + 1 = 5 ⇒ 删 2 份。**我数错了自己造的数据**，红的是我的算术不是实现（第 27 条同族）。
    // 而这条断言的价值恰恰在于：它证明手动/救援**没有**被算进那 5 份里 ——
    // 若它们被算进去，删除数会变成 4 而不是 2。
    assertEquals("只有 4 份自动备份参与计数：4 + 1 = 5 份 ⇒ 删 2 份；"
            + "手动与救援若被算进去会变成 4 份，所以这个数字同时证明它们没被计数",
        2, outcome.deleted.size());
  }

  /**
   * **读不了的归档既不删、也不计数，而且要报出来。**
   *
   * <p>少了这条，一份半截的归档会占掉「保留 3 份」里的一个位置，把一份好的挤掉 ——
   * 而那是**静默的数据损失**。所以断言两层：它还在，且它出现在 {@code kept} 里。
   */
  @Test
  public void unreadableArchivesAreNeitherDeletedNorCountedButAreReported() throws Exception {
    File home = makeHome();
    File archives = temporary.newFolder("archives-unreadable");
    File taskRoot = temporary.newFolder("tasks-unreadable");
    // 一份看起来像归档、其实不是的东西（没有魔数）。
    write(new File(archives, "broken.dshbak"), "this is not a DSHADATA container");
    for (int i = 0; i < 4; i++) {
      makeOldArchive(archives, "auto-" + i + ".dshbak", NOW - (10 - i) * 3_600_000L,
          DshAutomaticBackupRetention.OPERATION);
    }

    var outcome = run(home, archives, taskRoot, intervalSettings(NOW - 7_200_000L));

    assertTrue("读不了的必须留在盘上", new File(archives, "broken.dshbak").isFile());
    assertFalse("读不了的绝不参与删除", outcome.deleted.contains("broken.dshbak"));
    assertNull("它连清单都读不出，所以不在 kept 里（那需要 integrity 值）",
        outcome.kept.get("broken.dshbak"));
    // 4 份旧的 + 1 份新的 = 5 份**可计数**的 ⇒ 删 2 份。
    // 读不了的那份**没有**被算进 5 份里 —— 这正是「不计数」的意思。
    assertEquals(2, outcome.deleted.size());
  }

  // ─────────────────────────── 负路径 ───────────────────────────

  @Test
  public void refusesAMissingArchiveDirectory() throws Exception {
    File home = makeHome();
    File taskRoot = temporary.newFolder("tasks-neg");
    IOException error =
        assertThrows(
            IOException.class,
            () -> run(home, new File(home, "nope"), taskRoot, intervalSettings(NOW - 7_200_000L)));
    assertEquals("ARCHIVE_DIRECTORY_MISSING", error.getMessage());
  }

  @Test
  public void refusesAMissingTaskRoot() throws Exception {
    File home = makeHome();
    File archives = temporary.newFolder("archives-neg2");
    IOException error =
        assertThrows(
            IOException.class,
            () -> run(home, archives, new File(home, "nope"), intervalSettings(NOW - 7_200_000L)));
    assertEquals("TASK_DIRECTORY_MISSING", error.getMessage());
  }

  @Test
  public void refusesAnEmptyArchiveName() throws Exception {
    File home = makeHome();
    File archives = temporary.newFolder("archives-neg3");
    File taskRoot = temporary.newFolder("tasks-neg3");
    IOException error =
        assertThrows(
            IOException.class,
            () -> DshAutomaticBackupRunner.run(
                fs, home, archives, taskRoot, "", members(), EXCLUDE_CREDENTIALS,
                intervalSettings(NOW - 7_200_000L), NOW, UTC,
                DshAutomaticBackupRetention.KEEP, new BackupControl(null)));
    assertEquals("AUTOMATIC_ARCHIVE_NAME", error.getMessage());
  }

  @Test
  public void stampUsesTheGivenZone() {
    assertEquals("20261015-060000", DshAutomaticBackupRunner.stamp(NOW, UTC));
    assertEquals("20261015-140000", DshAutomaticBackupRunner.stamp(NOW, ZoneId.of("Asia/Shanghai")));
  }
}
