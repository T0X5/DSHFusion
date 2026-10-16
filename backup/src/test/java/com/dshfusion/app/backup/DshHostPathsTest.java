// DSHFusion 自写。登记在 governance/dsha-imports.json 的 original 段。
package com.dshfusion.app.backup;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupControl;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 路径规范化：**真机上第一次点导出就红了的那个 bug**（`PARENT_LINK`）。
 *
 * <p>Android 的 `/data/user/0` 是指向 `/data/data` 的符号链接，而
 * `Context.getCacheDir()` / `getFilesDir()` 返回的正是那条路径。
 * `AndroidBackupFileSystem`（逐字节导入自 DSHA）逐级用 `O_NOFOLLOW` 走父目录，
 * 见到符号链接就抛 `PARENT_LINK` —— 那是有意的安全设计。
 * 所以**交出去之前必须规范化**。
 *
 * <p>本机没有 android.jar，`JvmBackupFileSystem` 也**不**做那条 `O_NOFOLLOW` 走查，
 * 所以这里**复现不了** `PARENT_LINK` 本身。能证明的是它的**原因**：
 * 我们交出去的路径是规范的（符号链接已解析），以及依赖路径形态的那条安全判定
 * （`TASK_INSIDE_SOURCE`）在有符号链接时仍然成立。
 */
public class DshHostPathsTest {

  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  /**
   * 造一个「真实目录 + 指向它的符号链接」，模仿 Android 的 `/data/user/0` → `/data/data`。
   *
   * @return {real, link} —— 两者指向同一个地方，但只有 `real` 是规范路径。
   */
  private File[] realAndLink() throws IOException {
    File real = temporary.newFolder("real");
    Path link = temporary.getRoot().toPath().resolve("link");
    Files.createSymbolicLink(link, real.toPath());
    return new File[] {real, link.toFile()};
  }

  @Test
  public void canonicalResolvesTheSymlink() throws Exception {
    File[] pair = realAndLink();
    File real = pair[0], link = pair[1];

    // 前提自证：这两条路径**字符串不同**、但指向同一个地方。
    // 少了这一步，下面的断言在「这台机器上没建成符号链接」时也会绿。
    assertNotEquals("夹具必须真的造出两条不同的路径", real.getPath(), link.getPath());
    assertTrue("符号链接必须真的能解析", Files.isSymbolicLink(link.toPath()));

    assertEquals(
        "规范化必须解析掉符号链接",
        real.getCanonicalPath(),
        DshHostPaths.canonical(link).getPath());
  }

  /**
   * 🔴 **这条是安全判定，不是好看。**
   *
   * <p>`TASK_INSIDE_SOURCE` 挡的是「导出/恢复的任务目录落在源数据之内」——
   * 那会让工具读到自己正在写的东西。而源与任务目录只要有一条经过符号链接，
   * 用「绝对路径字符串比较」就会得出「不在里面」，于是守卫**静默失效**。
   */
  @Test
  public void isInsideSeesThroughTheSymlink() throws Exception {
    File[] pair = realAndLink();
    File real = pair[0], link = pair[1];
    File viaReal = new File(new File(real, "task"), "x");
    File viaLink = new File(new File(link, "task"), "x");

    assertTrue(
        "源用规范路径、候选用符号链接路径 —— 必须仍然判为「在里面」",
        DshHostPaths.isInside(real, viaLink));
    assertTrue("反过来也必须成立", DshHostPaths.isInside(link, viaReal));
    assertTrue("两条规范路径当然成立", DshHostPaths.isInside(real, viaReal));

    // 反向：真的在外面时不许判成里面。
    File outside = temporary.newFolder("outside");
    assertFalse(
        "同义词近邻不算包含关系",
        DshHostPaths.isInside(real, new File(outside, "x")));
    File sibling = new File(temporary.getRoot(), "real2/x");
    assertFalse(DshHostPaths.isInside(real, sibling));
  }

  /**
   * 端到端：**经过符号链接的源路径**导出，产物路径必须是规范的。
   *
   * <p>这一条直接对应真机上的那次失败 —— 那时 `export` 收到的是
   * `/data/user/0/<包名>/user-data`（含符号链接），而它把它原样交了出去。
   */
  @Test
  public void exportThroughASymlinkedSourceYieldsCanonicalPaths() throws Exception {
    File[] pair = realAndLink();
    File real = pair[0], link = pair[1];
    File home = new File(real, "user-data");
    File sessions = new File(home, ".dsh/sessions");
    Files.createDirectories(sessions.toPath());
    Files.write(
        new File(sessions, "a.json").toPath(), "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

    // **源与目标都经符号链接表示** —— 这正是真机上的情形：
    // 源是 `cacheDir`/`filesDir` 那种含符号链接的路径。
    //
    // ⚠️ `Exported.archive` 是**移出去之后的目标文件**，不是任务目录里的那份，
    // 所以断言只能落在目标上。我前两版分别断言了「产物在源下面」与「产物在任务目录下面」，
    // 两次都红 —— 因为产物**既不在源下面、也不在任务目录下面**，它已经被移走了。
    File realDownloads = new File(real, "dl");
    Files.createDirectories(realDownloads.toPath());
    File destinationThroughLink = new File(new File(link, "dl"), "a.dshbak");
    File task = temporary.newFolder("task");

    // 前提自证：目标这条路径确实经过符号链接。
    assertTrue(
        "夹具必须让目标路径真的经过符号链接",
        destinationThroughLink.getPath().contains(File.separator + "link" + File.separator));

    DshBackupExporter.Exported exported =
        DshBackupExporter.export(
            new com.deepseekharness.app.backup.JvmBackupFileSystem(),
            new File(link, "user-data"),
            List.of(new DshBackupExporter.Member("dsh", ".dsh", "application", false)),
            task,
            destinationThroughLink,
            DshAutomaticBackupRunner.provenance(1_700_000_000_000L),
            path -> false,
            false,
            new BackupControl(null));

    assertTrue("导出必须成功", exported.archive.isFile());
    // 产物路径必须是**规范化之后**的：符号链接那一级要被解析掉。
    // 少了规范化，这里会留下 `/link/` —— 而真机上留的就是 `/data/user/0/`。
    assertFalse(
        "产物路径里不该留下符号链接那一级：「" + exported.archive.getPath() + "」",
        exported.archive.getPath().contains(File.separator + "link" + File.separator));
    assertTrue(
        "产物必须落在符号链接解析之后的真实目录里",
        exported.archive.getPath().startsWith(realDownloads.getCanonicalPath()));
  }

  /** 恢复侧同理：经过符号链接的源路径，`inspect` 也要能用。 */
  @Test
  public void restoreThroughASymlinkedSourceWorks() throws Exception {
    File[] pair = realAndLink();
    File real = pair[0], link = pair[1];
    File source = new File(real, "user-data");
    File sessions = new File(source, ".dsh/sessions");
    Files.createDirectories(sessions.toPath());
    Files.write(
        new File(sessions, "a.json").toPath(), "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

    File task = temporary.newFolder("task");
    File downloads = temporary.newFolder("dl2");
    File archive = new File(downloads, "a.dshbak");
    com.deepseekharness.app.backup.BackupFileSystem fs =
        new com.deepseekharness.app.backup.JvmBackupFileSystem();
    DshBackupExporter.export(
        fs,
        source,
        List.of(new DshBackupExporter.Member("dsh", ".dsh", "application", false)),
        task,
        archive,
        DshAutomaticBackupRunner.provenance(1_700_000_000_000L),
        path -> false,
        false,
        new BackupControl(null));

    File target = temporary.newFolder("target");
    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    run.inspect(
        fs,
        archive,
        List.of(new HostRestoreMapping.Member("dsh", ".dsh", "application")),
        Set.of("application"),
        "full",
        // 同样故意用符号链接路径
        new File(link, "user-data"),
        temporary.newFolder("rtask"),
        new BackupControl(null));

    assertNotNull(run.report());
    assertEquals(1, run.report().roots.size());
  }

  /** 任务目录落在「经符号链接表示」的源之内，必须仍然被抓住。 */
  @Test
  public void taskInsideSourceIsStillCaughtThroughASymlink() throws Exception {
    File[] pair = realAndLink();
    File real = pair[0], link = pair[1];
    File home = new File(real, "user-data");
    Files.createDirectories(new File(home, ".dsh").toPath());
    File taskInside = new File(new File(link, "user-data"), "task");
    Files.createDirectories(taskInside.toPath());

    DshRestoreOrchestrator run = new DshRestoreOrchestrator();
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                run.inspect(
                    new com.deepseekharness.app.backup.JvmBackupFileSystem(),
                    new File(temporary.newFolder("d3"), "nope.dshbak"),
                    List.of(new HostRestoreMapping.Member("dsh", ".dsh", "application")),
                    Set.of("application"),
                    "full",
                    home,
                    taskInside,
                    new BackupControl(null)));
    assertEquals(
        "任务目录经符号链接落在源之内 —— 必须判为 TASK_INSIDE_SOURCE，不能因为路径字符串不同就放过",
        "TASK_INSIDE_SOURCE",
        error.getMessage());
  }
}
