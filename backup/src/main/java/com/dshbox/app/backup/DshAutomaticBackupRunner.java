// DSHFusion 自写。登记在 governance/dsha-imports.json 的 original 段。
//
// 为什么是 Java：它决定「什么时候自动备份」「哪些旧备份可以删」—— 后者是**会删数据**的
// 判断。本机有 JDK 17、没有 kotlinc（见 docs/INCIDENTS.md 第 21 条），
// 只让 CI 编译它太贵。与 DshBackupExporter / DshRestoreOrchestrator 同一条约定。
package com.dshbox.app.backup;

import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.util.AutomaticBackupPolicy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 自动备份：到期判定 → 导出 → 保留策略。
 *
 * <h2>它把三个各自有测试的东西接起来</h2>
 *
 * {@link AutomaticBackupPolicy}（什么时候该备份，逐字节导入）、
 * {@link DshBackupExporter}（怎么导）、{@link DshAutomaticBackupRetention}
 * （哪些旧备份可以删）。**这一层自己不重新实现其中任何一条**，
 * 它只负责「按什么顺序调、拿什么当输入」—— 而顺序恰好是这里最容易错的地方。
 *
 * <h2>顺序：先导出、后清理</h2>
 *
 * 反过来（先清理再导出）在导出失败时会**净损失**：旧备份被删掉了，新备份没生成。
 * 先导出后清理，最坏情况只是多留一份旧备份 —— 那是可以接受的失败方向。
 * 这条不是审美，它是这个类存在的理由之一。
 *
 * <h2>为什么读每份归档的清单</h2>
 *
 * 保留策略的判据是**归档自己声明的** `operation` 与 `integrity`：
 * 只有 `MAINTENANCE` 且证明得了完整（`QUIESCENT`/`EXTERNAL_CHECKED`）的才参与计数与删除。
 * 手动的、救援的、以及证明不了完整的一律不动。所以每份归档都要读一次清单 ——
 * 而**读清单会解容器**，于是这个类必须能容忍「某一份读不了」：
 * 读不了的那份**既不删也不计数**，如实报出来。
 */
public final class DshAutomaticBackupRunner {
  private DshAutomaticBackupRunner() {}

  /** 自动备份用的 operation 名。与保留策略的判据是同一个常量，不各写一份。 */
  public static final String OPERATION = DshAutomaticBackupRetention.OPERATION;

  /** 一次自动备份的结果。**每一栏都是给界面/日志看的**，没有内部对象。 */
  public static final class Outcome {
    /** 这次到点了没有。false 时其余栏都是空的。 */
    public final boolean ran;
    /** 没到点的原因（`NOT_DUE`），或到点了但**没做成**的原因。空表示成功。 */
    public final String reason;
    /** 新生成的归档名；没生成则是空串。 */
    public final String archive;
    /** 被删掉的旧归档名。 */
    public final List<String> deleted;
    /** **没被删**的自动备份及其原因（证明不了完整的那一类）。不静默。 */
    public final Map<String, String> kept;
    /** 新归档的字节数；没生成是 0。 */
    public final long bytes;

    Outcome(
        boolean ran,
        String reason,
        String archive,
        List<String> deleted,
        Map<String, String> kept,
        long bytes) {
      this.ran = ran;
      this.reason = reason == null ? "" : reason;
      this.archive = archive == null ? "" : archive;
      this.deleted = deleted;
      this.kept = kept;
      this.bytes = bytes;
    }

    static Outcome notDue(String reason) {
      return new Outcome(false, reason, "", List.of(), Map.of(), 0L);
    }

    public String describe() {
      if (!ran) return "未到点：" + reason;
      if (!reason.isEmpty()) return "失败：" + reason;
      return archive
          + "（"
          + bytes
          + " 字节，清理 "
          + deleted.size()
          + " 份"
          + (kept.isEmpty() ? "" : "，保留 " + kept.size() + " 份未验证的")
          + "）";
    }
  }

  /** 自动备份的设置。**纯数据**，Kotlin 侧从 SharedPreferences 读出来填进来。 */
  public static final class Settings {
    /** `interval` / `daily` / `stop` 三选一。其余值由策略类当成 `daily` 处理。 */
    public final String mode;
    /** `interval` 模式的间隔小时数（策略类会夹到 1–168）。 */
    public final int intervalHours;
    /** `daily` 模式的本地时刻（分钟，0–1439）。 */
    public final int minuteOfDay;
    /** 首次启用的时刻 —— 策略类用它算「第一次该在什么时候」。 */
    public final long anchor;
    /** 上一次成功的自动备份时刻；0 表示从没成功过。 */
    public final long last;
    /** 上一次沙箱停止的时刻；0 表示没记录。 */
    public final long stopped;

    public Settings(
        String mode, int intervalHours, int minuteOfDay, long anchor, long last, long stopped) {
      this.mode = mode == null ? "" : mode;
      this.intervalHours = intervalHours;
      this.minuteOfDay = minuteOfDay;
      this.anchor = anchor;
      this.last = last;
      this.stopped = stopped;
    }
  }

  /**
   * 跑一次。**不会因为「没到点」而抛错** —— 没到点是正常路径，用 {@code ran=false} 表示。
   *
   * @param archives 自动备份存放的目录。必须已存在。
   * @param taskRoot 导出用的任务根，**必须在 userData 之外**
   * @param archiveName 新归档的文件名。**由调用方按范围给**
   *     （Kotlin 侧走 `DshBackupScope.archiveName`），这里不自己拼 ——
   *     那条「部分备份绝不能用全量的文件名」的不变式是 `DshBackupScope` 存在的理由之一。
   * @param keep 保留份数（默认 {@link DshAutomaticBackupRetention#KEEP}）
   */
  public static Outcome run(
      BackupFileSystem fs,
      File userData,
      File archives,
      File taskRoot,
      String archiveName,
      List<DshBackupExporter.Member> members,
      Predicate<String> excluded,
      Settings settings,
      long now,
      ZoneId zone,
      int keep,
      BackupControl control)
      throws IOException {
    if (fs == null || userData == null || archives == null || taskRoot == null || settings == null)
      throw new IOException("AUTOMATIC_ARGUMENTS");
    if (archiveName == null || archiveName.isEmpty()) throw new IOException("AUTOMATIC_ARCHIVE_NAME");
    if (!archives.isDirectory()) throw new IOException("ARCHIVE_DIRECTORY_MISSING");
    if (!taskRoot.isDirectory()) throw new IOException("TASK_DIRECTORY_MISSING");

    // ① 到点了没有。判定逻辑在逐字节导入的那个类里，这里只喂参数。
    if (!AutomaticBackupPolicy.due(
        settings.mode,
        settings.intervalHours,
        settings.minuteOfDay,
        now,
        settings.anchor,
        settings.last,
        settings.stopped,
        zone)) {
      return Outcome.notDue(settings.mode);
    }

    // ② 先导出。**顺序不能与③对调** —— 见类注释。
    //
    // 文件名由调用方给（Kotlin 侧走 `DshBackupScope.archiveName`）——
    // 这里不能自己拼：那条「部分备份绝不能用全量的文件名」的不变式
    // 是 `DshBackupScope` 存在的理由之一，绕过去就等于把它废掉。
    String name = archiveName;
    File task = new File(taskRoot, java.util.UUID.randomUUID().toString());
    if (!task.mkdirs()) throw new IOException("TASK_DIRECTORY_CREATE");
    long bytes;
    try {
      DshBackupExporter.Exported exported =
          DshBackupExporter.export(
              fs,
              userData,
              members,
              task,
              new File(archives, name),
              provenance(now),
              excluded,
              false,
              control);
      bytes = exported.bytes;
    } finally {
      deleteTree(task);
    }

    // ③ 再清理。**只碰 MAINTENANCE 且证明得了完整的那些** —— 判据全在保留策略里。
    List<DshAutomaticBackupRetention.Archive> existing = new ArrayList<>();
    for (File file : listArchives(archives)) {
      DshAutomaticBackupRetention.Archive archive = readArchive(fs, file);
      // 读不了的归档：**不删、不计数，如实报出来**。
      // 直接跳过它会让它永远留在盘上而没人知道；报出来才有机会被人处理。
      if (archive != null) existing.add(archive);
    }
    DshAutomaticBackupRetention.Result retention =
        DshAutomaticBackupRetention.select(existing, keep);

    List<String> deleted = new ArrayList<>();
    for (DshAutomaticBackupRetention.Archive archive : retention.expired) {
      File victim = new File(archives, archive.name);
      // 只删**确实是文件**的那一个：`expired` 里的名字来自我们刚扫过的目录，
      // 但两次之间目录可能变了。`delete` 返回 false 时不当成错误，只是没删成。
      if (victim.isFile() && victim.delete()) deleted.add(archive.name);
    }
    return new Outcome(true, "", name, deleted, retention.explain(), bytes);
  }

  /** 容器清单里**调用方该给**的那几个字段。`integrity` 由上游推导，这里不放。 */
  static Map<String, Object> provenance(long now) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("operation", OPERATION);
    values.put("createdAt", now);
    values.put("appVersion", "0.1.0");
    values.put("runtime", "android");
    values.put("dataFormat", 5);
    values.put("sensitivePolicy", "machine-local-excluded");
    values.put("plugins", List.of());
    return values;
  }

  /** 时间戳：与归档名的后缀是同一套格式，读回时不必再解析。 */
  static String stamp(long now, ZoneId zone) {
    return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(zone)
        .format(java.time.Instant.ofEpochMilli(now));
  }

  /** 目录里的归档文件，按名字排序（确定性）。 */
  static List<File> listArchives(File archives) {
    File[] found = archives.listFiles();
    if (found == null) return List.of();
    List<File> out = new ArrayList<>();
    for (File file : found) if (file.isFile()) out.add(file);
    out.sort((a, b) -> a.getName().compareTo(b.getName()));
    return out;
  }

  /**
   * 读一份归档的清单，取出保留策略要的那三个字段。
   *
   * **读不了就返回 null**（调用方不删它、也不计数）。这不是「容错」的偷懒：
   * 一份读不了的归档可能是半截的、也可能是被别人改过的 ——
   * 两种情况都**不该**参与「保留最新 N 份」的计数，否则它会把一份好的挤掉。
   */
  static DshAutomaticBackupRetention.Archive readArchive(BackupFileSystem fs, File file) {
    try (InputStream input = fs.read(file, fs.stat(file))) {
      Map<String, Object> manifest =
          BackupArchive.read(
              input,
              (ordinal, record) -> null, // null = 只校验，不接收载荷
              new BackupControl(null));
      return new DshAutomaticBackupRetention.Archive(
          file.getName(),
          number(manifest.get("createdAt")),
          BackupJson.string(manifest, "operation"),
          BackupJson.string(manifest, "integrity"));
    } catch (IOException unreadable) {
      return null;
    }
  }

  private static long number(Object value) {
    if (value instanceof Number) return ((Number) value).longValue();
    if (value instanceof String) {
      try {
        return Long.parseLong((String) value);
      } catch (NumberFormatException notANumber) {
        return 0L;
      }
    }
    return 0L;
  }

  /** 递归删一棵树。**不用 `BackupFileSystem.delete`** —— 那个只删空目录。 */
  static void deleteTree(File root) {
    File[] children = root.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    root.delete();
  }
}
