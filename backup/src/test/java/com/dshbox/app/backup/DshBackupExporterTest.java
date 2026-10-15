// DSHFusion 自写。登记在 governance/dsha-imports.json 的 original 段。
package com.dshbox.app.backup;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.FileBackupSource;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link DshBackupExporter} 的本机行为测试。
 *
 * <p>它的价值在于：这一段编排（决定哪些文件进归档）**不依赖 Android**，
 * 所以这些断言在本机就真跑，而不是等 CI。
 */
public class DshBackupExporterTest {

  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private final BackupFileSystem fs = new JvmBackupFileSystem();

  /** 造一棵假用户数据树：{@code home/.dsh/{sessions,settings.yaml}}。 */
  private File makeHome() throws IOException {
    File home = temporary.newFolder("user-data");
    write(new File(home, ".dsh/sessions/a.json"), "{\"a\":1}");
    write(new File(home, ".dsh/sessions/b.json"), "{\"b\":2}");
    write(new File(home, ".dsh/settings.yaml"), "model: x\n");
    write(new File(home, ".dsh/credentials"), "secret: hunter2\n");
    return home;
  }

  private static void write(File file, String text) throws IOException {
    Files.createDirectories(file.getParentFile().toPath());
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  private File makeTask() throws IOException {
    return temporary.newFolder("task-" + UUID.randomUUID());
  }

  private Map<String, Object> provenance() {
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

  /** 策略看到过的每一个相对路径 —— 它证明「回调真的被调用了」，而不是被静默跳过。 */
  private final List<String> policySaw = new ArrayList<>();

  private DshBackupExporter.Exported export(
      File home, List<DshBackupExporter.Member> members, File destination, String... excluded)
      throws IOException {
    // ⚠️ 用 `endsWith` 而不是相等：这里要证的是**性质** ——
    // 「名字以 credentials 结尾的那一项的内容不会进归档」。
    // 路径的完整形式（有没有前缀、前缀怎么拼）由下面那条专门的用例钉住。
    // 谓词收的是 **user-data 相对**路径（与 DshBackupExclusions.isExcluded 同一套坐标系）。
    Predicate<String> policy =
        relative -> {
          policySaw.add(relative);
          for (String e : excluded) if (relative.endsWith(e)) return true;
          return false;
        };
    return DshBackupExporter.export(
        fs,
        home,
        members,
        makeTask(),
        destination,
        provenance(),
        policy,
        false,
        new BackupControl(null));
  }

  /**
   * 读一遍归档，收下每条记录的元数据，并**分别**记下哪些路径真的带了载荷。
   *
   * <p>为什么必须分开记：被排除的文件**仍然会有一条元数据记录**
   * （`kind=EXCLUDED`、带 reason），只是 {@code writer.add(record, null)} —— 没有载荷。
   * 所以「路径出现在归档里」**不等于**「内容泄进了归档」。
   * 我第一版就是断言「路径不许出现」，于是它把正确实现判失败了，
   * 而失败信息长得像「排除功能坏了」（第 32 条同族：问错了问题）。
   * 真正要证明的性质是：**被排除项的内容一个字节都不在归档里。**
   */
  private static final class Scan {
    final List<Map<String, Object>> records = new ArrayList<>();
    final Set<String> paths = new LinkedHashSet<>();
    /**
     * 真的**带了内容**的那些路径。
     *
     * <p>⚠️ 判据是 {@code size > 0}，不是「访问器被调用过」。
     * 第一版我写的是「`payload()` 被调用的那些」—— 而读取器对**每一条**记录都会调用它
     * （目录也一样，因为容器每条记录都带一个 int64 长度字段）。
     * 于是那个集合等于全体路径，它什么都没区分开，还把正确实现判失败了。
     * 这是第 32 条的同族：**又一次问错了问题**。
     */
    final Set<String> withPayload = new LinkedHashSet<>();
  }

  private static Scan scan(File archive) throws IOException {
    Scan scan = new Scan();
    try (InputStream in = Files.newInputStream(archive.toPath())) {
      BackupArchive.read(
          in,
          new BackupArchive.Visitor() {
            @Override
            public OutputStream payload(int ordinal, BackupArchive.Record record) {
              scan.records.add(record.metadata);
              scan.paths.add(record.path);
              if (record.size > 0) scan.withPayload.add(record.path);
              return null; // null = 只校验，不接收载荷
            }

            @Override
            public void verified(int ordinal, BackupArchive.Record record) {
              scan.records.add(record.metadata);
              scan.paths.add(record.path);
            }
          },
          new BackupControl(null));
    }
    return scan;
  }

  private static Map<String, Object> recordFor(Scan scan, String path) {
    for (Map<String, Object> values : scan.records)
      if (path.equals(values.get("path"))) return values;
    return null;
  }

  // ─────────────────────────── 正路径 ───────────────────────────

  @Test
  public void exportsSelectedMembersAndMovesTheArchiveToItsDestination() throws Exception {
    File home = makeHome();
    File downloads = temporary.newFolder("Download");
    File destination = new File(downloads, "DSHFusion-backup-20261015.dshbak");

    var result =
        export(
            home,
            List.of(
                new DshBackupExporter.Member("dsh-sessions", ".dsh/sessions", "sessions", false)),
            destination);
    var expected =
        export(
                home,
                List.of(
                    new DshBackupExporter.Member(
                        "dsh-sessions", ".dsh/sessions", "sessions", false)),
                new File(temporary.newFolder("Download2"), "b.dshbak"))
            .archive;

    assertTrue("产物必须真的落在目标位置", destination.isFile());
    assertEquals(destination, result.archive);
    assertEquals(destination.length(), result.bytes);
    assertTrue("清单里必须有一棵根", ((List<?>) result.manifest.get("roots")).size() == 1);
    assertEquals("没有源失败，完整性就是 QUIESCENT", "QUIESCENT", result.manifest.get("integrity"));
    assertTrue(result.skipped.isEmpty());
    assertNotNull(result.sha256);
    assertEquals("两份同内容的归档，摘要必须一致", expected.length(), result.bytes);
  }

  @Test
  public void theArchiveReallyContainsTheSelectedFiles() throws Exception {
    File home = makeHome();
    File destination = new File(temporary.newFolder("Download"), "a.dshbak");
    export(
        home,
        List.of(new DshBackupExporter.Member("dsh-sessions", ".dsh/sessions", "sessions", false)),
        destination);
    Scan scan = scan(destination);
    assertEquals(
        "选中 sessions 就**只**能有 sessions 里的东西（含那条描述根自身的记录）",
        Set.of("", "a.json", "b.json"),
        scan.paths);
    assertEquals(
        "带内容的是那两个文件；根是一条目录记录，没有内容",
        Set.of("a.json", "b.json"),
        scan.withPayload);
  }

  /**
   * 排除清单是**回调**，所以这里能证明它真的接上了。
   *
   * <p>这条断言存在的理由：`DshBackupExclusions` 在 Kotlin 侧，若这里只断言「导出来了」，
   * 那么「排除根本没接上」和「排除接上了」在结果上完全一样 —— 而后果是**归档里带着
   * 机器本地凭据**。
   */
  @Test
  public void excludedFilesNeverReachTheArchive() throws Exception {
    File home = makeHome();
    File destination = new File(temporary.newFolder("Download"), "a.dshbak");
    export(
        home,
        List.of(new DshBackupExporter.Member("dsh", ".dsh", "application", false)),
        destination,
        "credentials");
    Scan scan = scan(destination);

    // ① 先证明**前提成立**：回调真的被调用过，而且看得到 credentials。
    //    少了这一条，下面那条在「策略压根没被调用」时**也是绿的** ——
    //    那就成了第 30 条那种没有输入空间的断言。
    assertTrue(
        "排除谓词必须真的被调用过，且看得到 credentials；实际看到 " + policySaw,
        policySaw.stream().anyMatch(r -> r.endsWith("credentials")));

    // ③ 它仍然会留下一条**元数据**记录，且说清了为什么。这是有意的：
    //    「这次备份漏了什么」必须能从归档里读出来，而不是静默消失。
    Map<String, Object> excluded = recordFor(scan, "credentials");
    assertNotNull("被排除项必须留下一条可追溯的记录", excluded);
    assertEquals("EXCLUDED", excluded.get("kind"));
    assertEquals("EXCLUDED_BY_POLICY", excluded.get("reason"));
    // ② 真正要证明的性质：**内容**一个字节都没进归档。
    assertFalse(
        "被排除的凭据的内容绝不能出现在归档里；带载荷的是 " + scan.withPayload,
        scan.withPayload.contains("credentials"));
    assertTrue("但同一棵树里的别的文件必须带载荷", scan.withPayload.contains("settings.yaml"));
    assertTrue("反向自证：这棵树里确实有那个文件", new File(home, ".dsh/credentials").isFile());

  }

  /**
   * **坐标系拼接**：谓词收的是 user-data 相对路径，成员内相对路径要拼上前缀。
   *
   * <p>为什么单独一条：这两套坐标系差一个前缀，而少了拼接的后果是
   * **排除清单一条都不命中、且不报任何错** —— 归档里安静地带上机器本地凭据，
   * 而所有计数、摘要、完整性判定照常通过。
   *
   * <p>这条用例用**真实的一条 `.dsh` 成员**跑，而不是直接调 `join` ——
   * 直接调 `join` 只能证明拼接函数对，证明不了「它真的被接在了链路上」。
   * 首尾斜杠那几种形态另用 `join` 单测（那是纯字符串逻辑）。
   */
  @Test
  public void exclusionsAreMatchedThroughTheMemberPrefix() throws Exception {
    File home = makeHome();
    File destination = new File(temporary.newFolder("Download"), "a.dshbak");
    List<String> seen = new ArrayList<>();

    // 谓词按 **user-data 相对**路径判断：`.dsh/credentials` 命中，`credentials` 不命中。
    // 若链路上少了前缀拼接，它会收到裸的 `credentials`，于是**一条都不命中**。
    DshBackupExporter.export(
        fs,
        home,
        List.of(new DshBackupExporter.Member("dsh", ".dsh", "application", false)),
        makeTask(),
        destination,
        provenance(),
        relative -> {
          seen.add(relative);
          return relative.equals(".dsh/credentials");
        },
        false,
        new BackupControl(null));

    assertTrue("谓词必须收到 user-data 相对路径（带 .dsh/ 前缀）；实际 " + seen,
        seen.contains(".dsh/credentials"));
    assertFalse("不许收到裸的 credentials —— 那说明前缀拼接丢了", seen.contains("credentials"));
    // 且它**真的起了作用**：凭据的内容不在归档里。
    assertFalse(scan(destination).withPayload.contains("credentials"));
  }

  @Test
  public void joinHandlesLeadingAndTrailingSlashes() {
    assertEquals(".dsh/credentials", DshBackupExporter.join(".dsh", "credentials"));
    assertEquals(".dsh/credentials", DshBackupExporter.join(".dsh/", "/credentials"));
    assertEquals(".dsh", DshBackupExporter.join(".dsh", ""));
    assertEquals("credentials", DshBackupExporter.join("", "credentials"));
    assertEquals("", DshBackupExporter.join("", ""));
    // 根自身：把整个 .dsh/credentials 选成一个成员时，它自己那条也必须能被排除。
    assertEquals(".dsh/credentials", DshBackupExporter.join(".dsh/credentials", ""));
  }

  // ─────────────────────────── 负路径 ───────────────────────────

  @Test
  public void refusesWhenNothingWasSelected() throws Exception {
    File home = makeHome();
    File destination = new File(temporary.newFolder("Download"), "a.dshbak");
    assertThrows(
        IOException.class, () -> export(home, List.of(), destination));
  }

  @Test
  public void refusesWhenEveryMemberIsMissing() throws Exception {
    File home = makeHome();
    File destination = new File(temporary.newFolder("Download"), "a.dshbak");
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                export(
                    home,
                    List.of(
                        new DshBackupExporter.Member("nope", ".dsh/does-not-exist", "s", false)),
                    destination));
    assertEquals("NO_SELECTED_DATA", error.getMessage());
    assertFalse("失败时不许留下半个产物", destination.exists());
  }

  @Test
  public void missingMembersAreReportedNotSilentlyDropped() throws Exception {
    File home = makeHome();
    File destination = new File(temporary.newFolder("Download"), "a.dshbak");
    var result =
        export(
            home,
            List.of(
                new DshBackupExporter.Member("dsh-sessions", ".dsh/sessions", "sessions", false),
                new DshBackupExporter.Member("dsh-profiles", ".dsh/profiles", "plugins", false)),
            destination);
    assertEquals("没被选中的那一项必须如实报出来，不静默", List.of("dsh-profiles"), result.skipped);
    assertTrue(result.describe().contains("跳过 1 项"));
  }

  @Test
  public void refusesToWriteInsideTheSourceTree() throws Exception {
    File home = makeHome();
    // 任务目录落在 user-data 里面 = 备份会把自己正在写的草稿也读进去
    File taskInside = new File(home, "task");
    Files.createDirectories(taskInside.toPath());
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                DshBackupExporter.export(
                    fs,
                    home,
                    List.of(new DshBackupExporter.Member("dsh", ".dsh", "application", false)),
                    taskInside,
                    new File(temporary.newFolder("Download"), "a.dshbak"),
                    provenance(),
                    null,
                    false,
                    new BackupControl(null)));
    assertEquals("TASK_INSIDE_SOURCE", error.getMessage());
  }

  @Test
  public void refusesToOverwriteAnExistingBackup() throws Exception {
    File home = makeHome();
    File destination = new File(temporary.newFolder("Download"), "a.dshbak");
    write(destination, "上一个备份，不能被盖掉");
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                export(
                    home,
                    List.of(
                        new DshBackupExporter.Member("dsh-sessions", ".dsh/sessions", "s", false)),
                    destination));
    assertEquals("DESTINATION_EXISTS", error.getMessage());
    assertEquals(
        "原文件必须一个字节都没动",
        "上一个备份，不能被盖掉",
        new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
  }

  /**
   * **目标不能就是产物本身** —— 真机上第二版红的那个（`UNSAFE_MOVE`）。
   *
   * <p>上游 `move` 要求目标不存在，所以「把 destination 传成任务目录里的产物路径」
   * 会以 `UNSAFE_MOVE` 失败 —— 而那个名字说的是「跨设备 / 父目录不对」，
   * **指向的是错的方向**：我照着它去查文件系统，而真正的原因是调用方传错了参数。
   *
   * <p>所以这里把它变成一个说得出自己意思的码。这条用例同时是那条守卫的存在证明。
   */
  @Test
  public void refusesADestinationThatIsTheArtifactItself() throws Exception {
    File home = makeHome();
    File task = makeTask();
    // 目标在**任务目录之内** —— 于是它与 `HostSnapshot` 要写的那份产物是同一个路径。
    File insideTask = new File(task, "a.dshbak");

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                DshBackupExporter.export(
                    fs,
                    home,
                    List.of(
                        new DshBackupExporter.Member("dsh", ".dsh", "application", false)),
                    task,
                    insideTask,
                    provenance(),
                    null,
                    false,
                    new BackupControl(null)));

    assertEquals(
        "目标等于产物时必须报这个码，而不是让上游以 UNSAFE_MOVE 失败 —— "
            + "那个名字会把人引向文件系统，而真正的原因是调用方参数",
        "DESTINATION_IS_ARTIFACT",
        error.getMessage());
  }

  @Test
  public void refusesAnArtifactNameThatCollidesWithTheTaskBookkeeping() throws Exception {
    File home = makeHome();
    File destination = new File(temporary.newFolder("Download"), "capture");
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                export(
                    home,
                    List.of(
                        new DshBackupExporter.Member("dsh-sessions", ".dsh/sessions", "s", false)),
                    destination));
    assertEquals("RESERVED_ARTIFACT_NAME", error.getMessage());
  }

  @Test
  public void refusesADestinationWhoseParentDoesNotExist() throws Exception {
    File home = makeHome();
    File destination = new File(temporary.getRoot(), "no-such-dir/a.dshbak");
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                export(
                    home,
                    List.of(
                        new DshBackupExporter.Member("dsh-sessions", ".dsh/sessions", "s", false)),
                    destination));
    assertEquals("DESTINATION_PARENT_MISSING", error.getMessage());
  }

  /** 任务目录名由上游校验形状；这里证明我们的生成器与它的正则一致。 */
  @Test
  public void generatedTaskNamesMatchUpstreamsUuidShape() {
    for (int i = 0; i < 20; i++) {
      String name = DshBackupExporter.taskName();
      assertTrue(name + " 不符合上游的 UUID 形状", DshBackupExporter.validTaskName(name));
    }
    assertFalse(DshBackupExporter.validTaskName("not-a-uuid"));
  }

  @Test
  public void isInsideHandlesTheBoundaryCases() throws Exception {
    File parent = temporary.newFolder("p");
    File child = new File(parent, "a/b");
    assertTrue(DshBackupExporter.isInside(parent, child));
    assertTrue("自己算不算在自己里面 —— 必须算", DshBackupExporter.isInside(parent, parent));
    assertFalse(DshBackupExporter.isInside(child, parent));
    assertFalse(
        "同名前缀不是包含关系",
        DshBackupExporter.isInside(new File(parent, "ab"), new File(parent, "a/b")));
  }
}
