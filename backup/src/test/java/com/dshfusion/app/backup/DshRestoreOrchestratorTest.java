// DSHFusion 自写。登记在 governance/dsha-imports.json 的 original 段。
package com.dshfusion.app.backup;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link DshRestoreOrchestrator} 的本机行为测试。
 *
 * <p>这是本模块**第二条会毁数据**的路径（第一条是它内部用的提交事务，
 * 那条由上游那份测试覆盖）。所以这里的断言分两类：
 *
 * <ul>
 *   <li><b>顺序与状态机</b>：不许跳过预检就提交、不许提交两次。
 *   <li><b>「预检不写数据」这件事必须是可验证的</b> —— 只写在注释里不算。
 *       所以有两条用例专门对它取摘要/改目标，断言预检前后目标一个字节都没变，
 *       以及预检之后目标被改动会**在 prepare 阶段**被抓住。
 * </ul>
 */
public class DshRestoreOrchestratorTest {

  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private final BackupFileSystem fs = new JvmBackupFileSystem();

  private static void write(File file, String text) throws IOException {
    Files.createDirectories(file.getParentFile().toPath());
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  private static String read(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  /** 造一份「源」用户数据：`.dsh/sessions/{a,b}.json` + `.dsh/credentials`。 */
  private File makeSource() throws IOException {
    File home = temporary.newFolder("source");
    write(new File(home, ".dsh/sessions/a.json"), "{\"a\":1}");
    write(new File(home, ".dsh/sessions/b.json"), "{\"b\":2}");
    write(new File(home, ".dsh/credentials"), "secret: hunter2\n");
    return home;
  }

  /** 把源导出成一份归档，返回归档文件。 */
  private File exportArchive(File source, String name) throws IOException {
    File task = temporary.newFolder("export-task-" + UUID.randomUUID());
    File downloads = temporary.newFolder("Download-" + UUID.randomUUID());
    File destination = new File(downloads, name);
    DshBackupExporter.export(
        fs,
        source,
        List.of(new DshBackupExporter.Member("dsh", ".dsh", "application", false)),
        task,
        destination,
        provenance(),
        // 与真实链路一致：排除谓词收 user-data 相对路径。
        path -> path.equals(".dsh/credentials"),
        false,
        new BackupControl(null));
    return destination;
  }

  private static Map<String, Object> provenance() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("operation", "EXPORT");
    p.put("createdAt", 1_700_000_000_000L);
    p.put("appVersion", "0.1.0");
    p.put("runtime", "test");
    p.put("dataFormat", 5);
    p.put("sensitivePolicy", "machine-local-excluded");
    p.put("plugins", List.of());
    return p;
  }

  private List<HostRestoreMapping.Member> members() {
    return List.of(new HostRestoreMapping.Member("dsh", ".dsh", "application"));
  }

  private DshRestoreOrchestrator inspectInto(
      File archive, File target, File task, DshRestoreOrchestrator orchestrator)
      throws IOException {
    orchestrator.inspect(
        fs,
        archive,
        members(),
        Set.of("application"),
        "full",
        target,
        task,
        new BackupControl(null));
    return orchestrator;
  }

  // ─────────────────────────── 正路径 ───────────────────────────

  /**
   * 从一份真的导出产物恢复：删掉一个文件，恢复后它必须回来。
   *
   * <p>这条是「端到端」的那一条 —— 导出的产物必须能被恢复读懂，
   * 否则两个阶段各自绿着而合起来不成立。
   */
  @Test
  public void restoresWhatTheExporterWrote() throws Exception {
    File source = makeSource();
    File archive = exportArchive(source, "a.dshbak");

    File target = temporary.newFolder("target-restore");
    write(new File(target, ".dsh/sessions/a.json"), "{\"a\":1}");
    // b.json 在目标里**不存在** —— 恢复应该把它带回来
    File task = temporary.newFolder("restore-task");

    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    DshRestoreOrchestrator.Report report = inspectInto(archive, target, task, run).report();

    assertNotNull(report);
    assertEquals("full", report.scope);
    assertEquals("EXPORT", report.provenance.get("operation"));
    assertEquals(1, report.roots.size());
    assertEquals("dsh", report.roots.get(0).id);
    assertEquals(".dsh", report.roots.get(0).destination);
    assertTrue("落点已存在，必须报出来给用户看", report.overwrites.contains("dsh"));

    run.prepare(new BackupControl(null));
    assertEquals(DshRestoreOrchestrator.Phase.PREPARED, run.phase());
    assertTrue(run.mayCommit());
    run.commit(new BackupControl(null));
    assertEquals(DshRestoreOrchestrator.Phase.COMMITTED, run.phase());
    assertFalse(run.mayCommit());

    assertEquals("恢复后 a.json 必须还是原内容", "{\"a\":1}", read(new File(target, ".dsh/sessions/a.json")));
    assertEquals("恢复后 b.json 必须回来了", "{\"b\":2}", read(new File(target, ".dsh/sessions/b.json")));
    // 被排除的凭据**不在归档里**，所以恢复也就不会把它带回来 —— 这是对的：
    // 机器本地凭据本来就该在新设备上重新生成。
    assertFalse("凭据不在归档里，恢复不该凭空造一个", new File(target, ".dsh/credentials").exists());
  }

  /** 目标本来是空的：恢复应当把它填起来，且不报「覆盖」。 */
  @Test
  public void restoringIntoAnEmptyTargetReportsNoOverwrites() throws Exception {
    File archive = exportArchive(makeSource(), "a.dshbak");
    File target = temporary.newFolder("empty-target");
    File task = temporary.newFolder("restore-task");

    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    DshRestoreOrchestrator.Report report = inspectInto(archive, target, task, run).report();
    assertTrue("目标里什么都没有，不该报覆盖：" + report.overwrites, report.overwrites.isEmpty());
    assertTrue(report.describe().contains("将覆盖 0 个"));
  }

  // ─────────────────── 「预检不写数据」必须是可验证的 ───────────────────

  /**
   * **预检与准备都不许碰现有数据。**
   *
   * <p>只把这句话写在注释里是没有价值的。这条用例对目标树逐文件取摘要，
   * 在 `inspect` 与 `prepare` 之后各比一次。
   */
  @Test
  public void inspectAndPrepareDoNotTouchExistingData() throws Exception {
    File archive = exportArchive(makeSource(), "a.dshbak");
    File target = temporary.newFolder("target-untouched");
    write(new File(target, ".dsh/sessions/a.json"), "PRESENT-BEFORE");
    write(new File(target, ".dsh/settings.yaml"), "model: x\n");
    File task = temporary.newFolder("restore-task");

    Map<String, String> before = digestTree(target);

    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    inspectInto(archive, target, task, run);
    assertEquals("inspect 之后目标必须一个字节都没变", before, digestTree(target));

    run.prepare(new BackupControl(null));
    assertEquals("prepare 之后目标必须一个字节都没变", before, digestTree(target));

    run.commit(new BackupControl(null));
    assertNotEquals("commit 之后**必须**变了 —— 否则上面那两条是假通过", before, digestTree(target));
  }

  /**
   * 预检之后有人动了目标 → `prepare` 必须抓住，而不是把那次改动吃掉。
   *
   * <p>这条证明的是上游那条 `TARGET_CHANGED` 真的接在了我们的链路上 ——
   * 少接一步，用户在这段时间里的改动会被静默覆盖。
   */
  @Test
  public void changedTargetBetweenInspectAndPrepareIsRefused() throws Exception {
    File archive = exportArchive(makeSource(), "a.dshbak");
    File target = temporary.newFolder("target-changed");
    write(new File(target, ".dsh/sessions/a.json"), "{\"a\":1}");
    File task = temporary.newFolder("restore-task");

    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    inspectInto(archive, target, task, run);

    // 预检之后，用户（或别的进程）动了目标
    write(new File(target, ".dsh/sessions/a.json"), "{\"a\":\"CHANGED-AFTER-INSPECT\"}");

    IOException error = assertThrows(IOException.class, () -> run.prepare(new BackupControl(null)));
    assertEquals("TARGET_CHANGED", error.getMessage());
    assertEquals("失败后状态必须留在 INSPECTED（没准备好就不许提交）",
        DshRestoreOrchestrator.Phase.INSPECTED, run.phase());
  }

  // ─────────────── 坏归档：要明确报错，且不破坏现有数据 ───────────────
  //
  // 这一段对应阶段 2 的验收标准「坏备份要明确报错且**不破坏现有数据**」。
  // 先前我的编排测试里**一条坏归档的用例都没有** —— 而这是恢复路径上最可能发生的事
  // （拷贝中断、空间不足、传输截断）。
  //
  // 三种坏法覆盖三种不同的检测层，缺一不可：
  //   截断     → 读到一半就没了（容器长度字段）
  //   不是容器 → 魔数/版本
  //   翻一个字节 → 逐记录摘要

  private File corrupt(File good, String kind) throws IOException {
    File victim = new File(good.getParentFile(), kind + ".dshbak");
    byte[] bytes = Files.readAllBytes(good.toPath());
    if (kind.equals("truncated")) {
      bytes = Arrays.copyOf(bytes, bytes.length / 2);
    } else if (kind.equals("garbage")) {
      bytes = "not a DSHADATA container at all".getBytes(StandardCharsets.UTF_8);
    } else {
      bytes[bytes.length / 2] ^= 0x5A;
    }
    Files.write(victim.toPath(), bytes);
    return victim;
  }

  /**
   * 三种坏法各跑一遍：**必须抛出具名错误码，且目标树一个字节都没变。**
   *
   * <p>「不破坏现有数据」这一半比「报错」更重要 —— 一个说得清楚但已经把数据写坏的恢复
   * 比一个静默失败更糟。
   */
  @Test
  public void corruptedArchivesAreRefusedWithANamedCodeAndChangeNothing() throws Exception {
    File archive = exportArchive(makeSource(), "good.dshbak");
    // 每种坏法**允许**哪些错误码。前两种是确定的；第三种不是 ——
    // 翻掉的那个字节落在哪个结构上**取决于偏移**，而偏移取决于归档大小。
    //
    // ⚠️ 我第一版把第三种钉成了 `RECORD_CHECKSUM`（我在探针里看到的那个），
    // 然后测试红了：这份归档比探针那份小几字节，于是翻转的字节落在了**页脚**里。
    // **我又一次钉了「我以为的形态」而不是「必须成立的性质」**（第 15/20/32 条同族）。
    // 真正要证明的性质有两条：① 它被**检测到**了；② 报出的是**这个容器自己的**
    // 完整性错误码（而不是「没选中任何数据」之类的、来自别处的失败）。
    Map<String, Set<String>> allowed =
        Map.of(
            "truncated", Set.of("ARCHIVE_TRUNCATED"),
            "garbage", Set.of("ARCHIVE_VERSION"),
            "flipped",
                Set.of(
                    "ARCHIVE_FOOTER", "ARCHIVE_MANIFEST", "RECORD_CHECKSUM", "RECORD_LIMIT",
                    "ARCHIVE_TRUNCATED"));

    for (Map.Entry<String, Set<String>> each : allowed.entrySet()) {
      String kind = each.getKey();
      File victim = corrupt(archive, kind);
      File target = temporary.newFolder("target-" + kind);
      write(new File(target, ".dsh/sessions/a.json"), "PRESENT-BEFORE");
      Map<String, String> before = digestTree(target);

      DshRestoreOrchestrator run = new DshRestoreOrchestrator();
      IOException error =
          assertThrows(
              IOException.class,
              () -> run.inspect(
                  fs,
                  victim,
                  members(),
                  Set.of("application"),
                  "full",
                  target,
                  temporary.newFolder("task-" + kind),
                  new BackupControl(null)));

      // ① 必须是一个**能对照的固定错误码**，不是 null、也不是某句自然语言。
      assertNotNull(kind + " 的错误码不能是 null —— 界面会把它原样显示给用户", error.getMessage());
      assertTrue(
          kind + " 的错误码不在允许集合里，实际「" + error.getMessage() + "」（允许 " + each.getValue() + "）",
          each.getValue().contains(error.getMessage()));
      assertTrue(
          "错误码必须形如 ABC_DEF（能被用户照着查），实际「" + error.getMessage() + "」",
          error.getMessage().matches("[A-Z][A-Z0-9_]+"));

      // ② 目标树一个字节都没变。
      assertEquals(kind + " 之后目标树必须一个字节都没变", before, digestTree(target));
      // ③ 状态留在 FRESH —— 没检成就没有「可提交」的东西。
      assertEquals(DshRestoreOrchestrator.Phase.FRESH, run.phase());
      assertFalse(run.mayCommit());
    }
  }

  // ─────────────────────────── 状态机 ───────────────────────────

  @Test
  public void prepareWithoutInspectIsRefused() throws Exception {
    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    IOException error =
        assertThrows(IOException.class, () -> run.prepare(new BackupControl(null)));
    assertEquals("PREPARE_WITHOUT_INSPECT", error.getMessage());
  }

  @Test
  public void commitWithoutPrepareIsRefused() throws Exception {
    File archive = exportArchive(makeSource(), "a.dshbak");
    File target = temporary.newFolder("target-no-prepare");
    File task = temporary.newFolder("restore-task");
    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    inspectInto(archive, target, task, run);

    IOException error = assertThrows(IOException.class, () -> run.commit(new BackupControl(null)));
    assertEquals("COMMIT_WITHOUT_PREPARE", error.getMessage());
  }

  /** 提交过之后，任何一步都不许再来 —— 界面状态管理错了就该立刻红。 */
  @Test
  public void nothingWorksAfterCommit() throws Exception {
    File archive = exportArchive(makeSource(), "a.dshbak");
    File target = temporary.newFolder("target-finalized");
    File task = temporary.newFolder("restore-task");
    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    inspectInto(archive, target, task, run);
    run.prepare(new BackupControl(null));
    run.commit(new BackupControl(null));

    for (Runnable again :
        List.<Runnable>of(
            () -> {
              try {
                run.inspect(
                    fs,
                    archive,
                    members(),
                    Set.of("application"),
                    "full",
                    target,
                    task,
                    new BackupControl(null));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            },
            () -> {
              try {
                run.prepare(new BackupControl(null));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            },
            () -> {
              try {
                run.commit(new BackupControl(null));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            })) {
      UncheckedIOException error = assertThrows(UncheckedIOException.class, again::run);
      assertEquals("ALREADY_FINALIZED", error.getCause().getMessage());
    }
    // recover 是**空操作**而不是错误：已收尾的事务没有可恢复的东西。
    run.recover();
    assertEquals("recover 不该把已提交的运行退回可提交状态",
        DshRestoreOrchestrator.Phase.COMMITTED, run.phase());
    assertFalse(run.mayCommit());
  }

  @Test
  public void recoverReturnsToFreshAndClearsTheReport() throws Exception {
    File archive = exportArchive(makeSource(), "a.dshbak");
    File target = temporary.newFolder("target-recover");
    File task = temporary.newFolder("restore-task");
    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    inspectInto(archive, target, task, run);
    assertNotNull(run.report());

    run.recover();
    assertEquals(DshRestoreOrchestrator.Phase.FRESH, run.phase());
    assertNull("recover 之后必须重新检 —— 候选可能已被回切动过", run.report());
    assertFalse(run.mayCommit());
  }

  // ─────────────────────────── 负路径 ───────────────────────────

  @Test
  public void refusesATaskDirectoryInsideTheSourceTree() throws Exception {
    File archive = exportArchive(makeSource(), "a.dshbak");
    File target = temporary.newFolder("target-task-inside");
    File taskInside = new File(target, "task");
    Files.createDirectories(taskInside.toPath());

    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                run.inspect(
                    fs,
                    archive,
                    members(),
                    Set.of("application"),
                    "full",
                    target,
                    taskInside,
                    new BackupControl(null)));
    assertEquals("TASK_INSIDE_SOURCE", error.getMessage());
  }

  @Test
  public void refusesAMissingArchive() throws Exception {
    File target = temporary.newFolder("target-no-archive");
    File task = temporary.newFolder("restore-task");
    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    IOException error =
        assertThrows(
            IOException.class,
            () -> run.inspect(
                fs,
                new File(target, "nope.dshbak"),
                members(),
                Set.of("application"),
                "full",
                target,
                task,
                new BackupControl(null)));
    assertEquals("ARCHIVE_MISSING", error.getMessage());
  }

  @Test
  public void refusesAMissingTaskDirectory() throws Exception {
    File archive = exportArchive(makeSource(), "a.dshbak");
    File target = temporary.newFolder("target-no-task");
    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    IOException error =
        assertThrows(
            IOException.class,
            () -> run.inspect(
                fs,
                archive,
                members(),
                Set.of("application"),
                "full",
                target,
                new File(target, "no-such-task"),
                new BackupControl(null)));
    assertEquals("TASK_DIRECTORY_MISSING", error.getMessage());
  }

  @Test
  public void refusesWhenActingOnAMissingArchiveFile() throws Exception {
    // 归档是个目录而不是文件 —— `isFile()` 必须挡住，否则后面读它会抛一个更晦涩的错。
    File archiveDir = temporary.newFolder("not-a-file.dshbak");
    File target = temporary.newFolder("target-archive-is-dir");
    File task = temporary.newFolder("restore-task");
    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    IOException error =
        assertThrows(
            IOException.class,
            () -> run.inspect(
                fs,
                archiveDir,
                members(),
                Set.of("application"),
                "full",
                target,
                task,
                new BackupControl(null)));
    assertEquals("ARCHIVE_MISSING", error.getMessage());
  }

  @Test
  public void relativeToHandlesTheBoundaryCases() throws Exception {
    File home = temporary.newFolder("rel-home");
    assertEquals(".", DshRestoreOrchestrator.relativeTo(home, home));
    assertEquals(
        ".dsh/sessions",
        DshRestoreOrchestrator.relativeTo(home, new File(home, ".dsh/sessions")));
    // 同名前缀不是包含关系：`<root>/rel-home` 不是 `<root>/ab/abc` 的祖先，
    // 于是函数**原样给绝对路径**（不猜、不硬凑一个相对路径出来）。
    File outside = new File(home.getParentFile(), "ab/abc");
    assertEquals(
        "不是祖先就给绝对路径", outside.getAbsolutePath(), DshRestoreOrchestrator.relativeTo(home, outside));
    // 反向：`<root>/rel-home2` 也**不是** `<root>/rel-home` 的子孙。
    File sibling = new File(home.getParentFile(), "rel-home2/x");
    assertEquals(sibling.getAbsolutePath(), DshRestoreOrchestrator.relativeTo(home, sibling));
  }

  /** 逐文件摘要（路径 → 内容哈希），用来证明「一个字节都没变」。 */
  private static Map<String, String> digestTree(File root) throws IOException {
    Map<String, String> out = new TreeMap<>();
    java.nio.file.Path base = root.toPath();
    if (!Files.exists(base)) return out;
    try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(base)) {
      for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) walk::iterator) {
        if (!Files.isRegularFile(path)) continue;
        byte[] bytes = Files.readAllBytes(path);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b));
        out.put(base.relativize(path).toString(), hex.toString());
      }
    }
    return out;
  }

  /**
   * 🔴 **全量恢复必须「替换」，不是「合并」。**
   *
   * <p>真机上就是这么暴露的：用户建了会话 `测试C会话` → 导出（C 不在归档里）→ 恢复 →
   * 界面报「已恢复：1」，而 **C 还在**。用户以为「恢复没生效」，
   * 其实恢复生效了 —— 只是它做的是**合并**而不是**替换**。
   *
   * <p>根因：`HostRestoreMapping` 把 `Target.merge` **写死成 `true`**。
   * 而 `NativeRestorePlan` 里 `merge=true` 的语义是先把**现有的整棵树**拷进候选、
   * 再把归档覆盖上去 —— 于是归档里没有的东西一律保留，
   * **全量恢复永远删不掉任何东西**。
   *
   * <p>这条断言就是那次事故的守卫：**归档之后新建的东西必须消失。**
   */
  @Test
  public void aWholeHomeRestoreReplacesTheTreeInsteadOfMergingIntoIt() throws Exception {
    File source = makeSource();
    File archive = exportArchive(source, "whole.dshbak");

    File target = temporary.newFolder("target-whole");
    write(new File(target, ".dsh/sessions/a.json"), "{\"a\":1}");
    // 归档**之后**新建的 —— 真机上就是那个「测试C会话」。
    File afterExport = new File(target, ".dsh/sessions/after-export.json");
    write(afterExport, "{\"new\":1}");

    File task = temporary.newFolder("restore-whole");
    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    inspectInto(archive, target, task, run);
    run.prepare(new BackupControl(null));
    run.commit(new BackupControl(null));

    assertFalse(
        "全量恢复必须删掉归档里没有的文件 —— 否则「恢复成那份归档的样子」永远做不到",
        afterExport.exists());
    assertTrue("归档里有的必须还在", new File(target, ".dsh/sessions/a.json").isFile());
  }

  /**
   * `merge` 的推导规则：**根是整个 home → 替换；根是子树 → 合并。**
   *
   * <p>前者是「把这个家恢复成那份归档的样子」，后者是「只换这一部分、别动其它」。
   * 这条规则原来是不存在的 —— `merge` 被硬编码成 `true`，
   * 而那让**全量**恢复也走了合并那条路。
   */
  @Test
  public void wholeHomeRootsReplaceAndSubtreeRootsMerge() throws Exception {
    File userData = temporary.newFolder("map-merge");
    HostRestoreMapping mapping =
        new HostRestoreMapping(
            userData,
            List.of(
                new HostRestoreMapping.Member("dsh", ".dsh", "application"),
                new HostRestoreMapping.Member("dsh-sessions", ".dsh/sessions", "sessions")));

    Map<String, Object> whole = new LinkedHashMap<>();
    whole.put("id", "dsh");
    whole.put("scope", "application");
    assertFalse(
        "根就是整个 home（.dsh）→ 必须是替换，否则全量恢复删不掉任何东西",
        mapping.target(whole).merge);

    Map<String, Object> part = new LinkedHashMap<>();
    part.put("id", "dsh-sessions");
    part.put("scope", "sessions");
    assertTrue(
        "根是 home 里的子树 → 必须是合并，否则窄范围恢复会把其它东西也删掉",
        mapping.target(part).merge);
  }

  /**
   * 🔴 **窄范围 + 全量归档 = 报错，不是「静默把设置与插件也恢复进去」。**
   *
   * <p>这条测试是为**更正一句文档**而写的。`docs/INSTALL-ON-DEVICE.md` §8.8 第 2 条写着：
   *
   * <blockquote>选择了「只恢复会话」而拿到一份全量归档时，设置与插件也会被恢复进去
   * —— 上游把它做成了通配符，我们目前照它的语义走。</blockquote>
   *
   * <p>**那句话与代码不符。** `NativeRestorePlan.inspect` 的过滤是
   * `if (!selected.contains("application") && !selected.contains(scope)) continue;`，
   * 而全量归档的根 `scope` 就是 `"application"` —— 选「会话」时 `selected = {"sessions"}`，
   * 两个条件都成立 → **根被跳过** → `plan.targets` 为空 → 抛 `NO_RECOVERABLE_SCOPE`。
   *
   * <p>也就是说行为是**明确报错**，而不是**静默多恢复**。
   * 后者是更严重的事（用户以为只换了会话，结果设置也被换掉），
   * 所以「实际是哪个」必须由执行来回答，不能靠推理 —— 我是推理错的。
   */
  @Test
  public void aNarrowScopeRefusesAFullArchiveInsteadOfOverRestoring() throws Exception {
    File source = makeSource();
    File archive = exportArchive(source, "full-for-narrow.dshbak");

    File target = temporary.newFolder("target-narrow");
    write(new File(target, ".dsh/sessions/a.json"), "{\"a\":1}");
    write(new File(target, ".dsh/settings.yaml"), "keep: me");
    File task = temporary.newFolder("restore-narrow");

    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                run.inspect(
                    fs,
                    archive,
                    // SESSIONS 范围的成员：id 是 `dsh-sessions` 这类，**不是** `dsh`
                    List.of(new HostRestoreMapping.Member("dsh-sessions", ".dsh/sessions", "sessions")),
                    Set.of("sessions"),
                    "sessions",
                    target,
                    task,
                    new BackupControl(null)));

    assertEquals(
        "窄范围拿到全量归档时必须**明确报错**，而不是静默把设置与插件也恢复进去"
            + "（§8.8 第 2 条原话与代码不符，这条测试是那句更正的证据）",
        "NO_RECOVERABLE_SCOPE",
        error.getMessage());

    // 而且**什么都没动** —— 报错之后目标必须原样。
    assertTrue("报错不能动数据", new File(target, ".dsh/sessions/a.json").isFile());
    assertTrue("设置也不能被动", new File(target, ".dsh/settings.yaml").isFile());
  }
}
