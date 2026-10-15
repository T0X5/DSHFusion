// DSHFusion 自写。不是 DSHA 的副本 —— 登记在 governance/dsha-imports.json 的 original 段。
//
// 为什么是 Java 而不是 Kotlin：本机有 JDK 17、没有 kotlinc（见 docs/INCIDENTS.md 第 21 条）。
// 这一层决定「到底把哪些东西写进了归档」，是本模块第三段会毁数据/泄数据的路径
// （前两段是恢复的落点与提交回切）。只让 CI 编译它太贵。
// 与 `HostRestoreMapping` / `HostRestoreTransaction` / `DshAutomaticBackupRetention`
// 同一条约定。
package com.dshbox.app.backup;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupSource;
import com.deepseekharness.app.backup.CredentialFileBackupSource;
import com.deepseekharness.app.backup.CredentialProjection;
import com.deepseekharness.app.backup.FileBackupSource;
import com.deepseekharness.app.backup.HostSnapshot;
import com.deepseekharness.app.util.Ids;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Predicate;

/**
 * 把用户数据导出成一份 v5 归档。
 *
 * <h2>它做什么，不做什么</h2>
 *
 * 它只做三件事：把「范围成员」翻译成 {@link BackupSource}、调用上游的
 * {@link HostSnapshot#create}、把产物移出任务目录。**容器格式、逐文件校验、
 * 完整性判定全部在上游那个类里**，这里一行都不重写（硬约束 2）。
 *
 * <h2>为什么成员与排除都是参数</h2>
 *
 * 范围的词汇（{@code DshBackupScope}）与排除清单（{@code DshBackupExclusions}）
 * 都在 Kotlin 侧。若在这里 import 它们，本机就编不了、跑不了，这一层就退化回
 * 「只有 CI 能验」—— 而它恰恰是最该本机验的一层。
 * 所以这里只认**纯数据**（{@link Member}）与一个
 * {@link Predicate}（「这个 user-data 相对路径要不要排除」）；
 * Kotlin 侧负责把范围翻译成前者、把排除清单包成后者。
 *
 * <h2>任务目录与产物为什么必须分开</h2>
 *
 * 上游 {@code create} 有一条硬断言：{@code archive.getParentFile()} 必须**等于**
 * {@code ownedTask}，而 {@link BackupSource#containsLocal} 又不允许任务目录落在任何
 * 源根之内。这两条合起来的意思是：**产物必须先写在一个既在源之外、又被我们独占的
 * 目录里**，写完再移出去。所以任务目录由调用方给出（应当是一块不在
 * {@code user-data} 下的独占目录），产物文件名由这里从目标文件名派生。
 */
public final class DshBackupExporter {
  private DshBackupExporter() {}

  /** 任务目录里被上游自己使用的两个名字 —— 产物不能撞上它们。 */
  private static final Set<String> RESERVED = Set.of("source-checks", "capture");

  /** 一个范围成员：选中的那一小块用户数据。纯数据，不含任何 Kotlin 类型。 */
  public static final class Member {
    public final String id;
    /** 相对 {@code userDataDir} 的路径，例如 {@code .dsh/sessions}。 */
    public final String path;
    /** 写进每条记录的 v5 范围名（例如 {@code sessions}）。 */
    public final String scope;
    public final boolean external;

    public Member(String id, String path, String scope, boolean external) {
      this.id = id;
      this.path = path;
      this.scope = scope;
      this.external = external;
    }

    @Override
    public String toString() {
      return id + "(" + path + ")";
    }
  }

  /** 一次导出的结果。{@code skipped} 是**没被选中**的成员，不是错误。 */
  public static final class Exported {
    public final File archive;
    public final Map<String, Object> manifest;
    public final long bytes;
    public final String sha256;
    public final List<String> skipped;

    Exported(
        File archive,
        Map<String, Object> manifest,
        long bytes,
        String sha256,
        List<String> skipped) {
      this.archive = archive;
      this.manifest = manifest;
      this.bytes = bytes;
      this.sha256 = sha256;
      this.skipped = Collections.unmodifiableList(skipped);
    }

    /** 逐文件、给界面看的一行摘要。不含路径以外的任何用户数据。 */
    public String describe() {
      Object integrity = manifest.get("integrity");
      return archive.getName()
          + " · "
          + bytes
          + " 字节 · 完整性 "
          + integrity
          + (skipped.isEmpty() ? "" : " · 跳过 " + skipped.size() + " 项");
    }
  }

  /**
   * 导出。**不覆盖已存在的目标文件**（上游的 {@code move} 语义）。
   *
   * @param userDataDir 成员路径的解析起点（DSHFusion 的 {@code user-data}）
   * @param taskDir 独占的临时目录，**必须不在 {@code userDataDir} 之下**
   * @param destination 最终位置（例如下载目录里的一个文件）
   * @param provenance 容器清单的**调用方字段**（{@code operation}/{@code createdAt}/
   *     {@code appVersion}/{@code runtime}/{@code dataFormat}/{@code sensitivePolicy}/
   *     {@code plugins}）。{@code integrity} 由上游自己推导，这里不放。
   */
  public static Exported export(
      BackupFileSystem fs,
      File userDataDir,
      List<Member> members,
      File taskDir,
      File destination,
      Map<String, Object> provenance,
      Predicate<String> excluded,
      boolean rescue,
      BackupControl control)
      throws IOException {
    if (members == null || members.isEmpty()) throw new IOException("NO_SELECTED_DATA");
    // ⚠️ **规范化，不是 `getAbsoluteFile()`。** 后者只保证绝对路径、**不解析符号链接**。
    // 真机上第一次点导出就红了 `PARENT_LINK`：Android 的 `/data/user/0` 是指向
    // `/data/data` 的符号链接，而 `cacheDir`/`filesDir` 返回的正是那条路径 ——
    // `AndroidBackupFileSystem` 逐级用 `O_NOFOLLOW` 走父目录，见到链接就拒绝。
    // 详见 `DshHostPaths` 的类注释。
    File home = DshHostPaths.canonical(userDataDir);
    File task = DshHostPaths.canonical(taskDir);
    File target = DshHostPaths.canonical(destination);

    if (!task.isDirectory()) throw new IOException("TASK_DIRECTORY_MISSING");
    // 任务目录落在源之内 = 备份会把自己正在写的东西也读进去。
    // 上游也有这条检查（BACKUP_DESTINATION_IN_SOURCE），但它是在 create 内部才抛 ——
    // 那时已经建了草稿文件。这里提前一次，代价是一次字符串比较。
    if (isInside(home, task)) throw new IOException("TASK_INSIDE_SOURCE");
    if (isInside(home, target)) throw new IOException("DESTINATION_INSIDE_SOURCE");

    File parent = target.getParentFile();
    if (parent == null || !parent.isDirectory()) throw new IOException("DESTINATION_PARENT_MISSING");
    if (target.exists()) throw new IOException("DESTINATION_EXISTS");
    // 产物必须落在任务目录里（上游的硬断言），所以它是「任务目录 + 目标文件名」。
    String name = target.getName();
    if (RESERVED.contains(name)) throw new IOException("RESERVED_ARTIFACT_NAME");
    File archive = new File(task, name);
    if (archive.exists()) throw new IOException("ARTIFACT_EXISTS");
    // **目标不能就是产物本身。** 上游 `move` 要求目标不存在，所以那种调用会以
    // `UNSAFE_MOVE` 失败 —— 而那个名字说的是「跨设备/父目录不对」，**指向的是错的方向**：
    // 真机上我照着它去查文件系统，而真正的原因是调用方把 destination 传成了产物路径。
    // 所以在这里把它变成一个说得出自己意思的码。
    if (archive.equals(target)) throw new IOException("DESTINATION_IS_ARTIFACT");

    List<BackupSource> sources = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Member member : members) {
      if (!ids.add(member.id)) throw new IOException("DUPLICATE_ROOT");
      File root = new File(home, member.path);
      // 没被选中（用户还没用过那一块）不是错误。如实报出来，不静默。
      if (!root.exists()) {
        skipped.add(member.id);
        continue;
      }
      boolean credentials = isCredentialFile(member.path);
      BackupSource source =
          new FileBackupSource(
              fs,
              member.id,
              member.scope,
              root,
              member.external,
              // ⚠️ **凭据源不套排除清单。**
              //
              // 排除清单里那条 `.dsh/.credentials.yaml` 是给**别的**源用的
              // （让 `.dsh` 那棵树不要把凭据原样带走）。而这个成员的**根就是那个文件**，
              // 它的相对路径是 `""` —— 拼出来正好命中那条排除，于是整个文件被排掉、
              // 归档里一个字都不剩。
              //
              // 我第一版就是这样：断言「provider 的 ref 必须保留」红了，而归档里
              // 凭据那条记录的内容是**空的**。两个「排除」叠在一起，把该留的那份也删了。
              credentials ? null : policyFor(member, excluded));
      // 🔴 **凭据文件必须套上投影。**
      //
      // 阶段 2.3 做了 `CredentialFileBackupSource`（删掉机器本地记录、保留 provider 的
      // `refs:`），它的单测也过了 —— 但**生产路径上从来没有人用它**：
      // 那个类全库只被它自己的测试引用。于是真机上导出时，`.credentials.yaml`
      // **原样**进了归档，用户的 API Key 与浏览器会话授权明文躺在下载目录里。
      //
      // 这里按**路径**判断，而不是按调用方传进来的标志位 —— 因为「忘了传标志位」
      // 正是这个 bug 的形态。按路径判断则**不可能忘**：任何范围只要含这个文件，
      // 它就被投影。
      if (credentials) source = new CredentialFileBackupSource(source);
      sources.add(source);
    }
    if (sources.isEmpty()) throw new IOException("NO_SELECTED_DATA");

    Map<String, Object> manifest = HostSnapshot.create(
        fs, sources, task, archive, provenance, rescue, control);

    // 移出任务目录。上游的 move 不覆盖 —— 上面已经查过目标不存在，
    // 但两次检查之间目标可能被人创建，那时 move 会抛，而不是悄悄盖掉别人的备份。
    fs.move(archive, target);
    long bytes = fs.stat(target).size;
    return new Exported(target, manifest, bytes, sha256(fs, target), skipped);
  }

  /**
   * 把一个「排除谓词」适配成某个成员根的 {@link FileBackupSource.Policy}。
   *
   * <h2>为什么这里有一步前缀拼接，而它不是可有可无的</h2>
   *
   * 上游的 {@code Policy.exclusion(relative, node)} 给的 {@code relative} 是**相对这个成员根**
   * 的 —— 成员根是 {@code .dsh} 时，凭据文件叫 {@code credentials}。
   * 而 {@code DshBackupExclusions.isExcluded} 收的是**相对 user-data** 的 —— 同一个文件叫
   * {@code .dsh/credentials}。**两套坐标系，差一个前缀。**
   *
   * 少了这一步拼接，排除清单**一条都不会命中**，而且不会报任何错：
   * 归档里会安静地带上机器本地凭据，所有计数、所有摘要、所有完整性判定都照常通过。
   * 所以拼接放在这里（本机真跑），由 {@code exclusionsAreMatchedThroughTheMemberPrefix}
   * 那条用例钉住 —— 它也**先自证谓词真的被调用过**，否则「一条都没命中」与
   * 「谓词压根没被调用」在结果上一样。
   */
  static FileBackupSource.Policy policyFor(Member member, Predicate<String> excluded) {
    if (excluded == null) return null;
    return (relative, node) ->
        excluded.test(join(member.path, relative)) ? "EXCLUDED_BY_POLICY" : "";
  }

  /**
   * 把成员根与成员内的相对路径拼成 user-data 相对路径。
   *
   * 根自身（{@code relative} 为空）得到的就是成员路径本身 —— 它有意的：
   * 用户把整个 {@code .dsh/credentials} 选成一个成员时，那条也必须被排除。
   */
  static String join(String base, String relative) {
    String head = base == null ? "" : base.replaceAll("^/+|/+$", "");
    String tail = relative == null ? "" : relative.replaceAll("^/+|/+$", "");
    if (head.isEmpty()) return tail;
    if (tail.isEmpty()) return head;
    return head + "/" + tail;
  }

  /**
   * 这个成员是不是凭据文件。
   *
   * <p>认的是**文件名**（`CredentialProjection.FILE`，来自逐字节导入的副本），
   * 不认调用方传的标志位 —— 见上面那段注释：这个 bug 的形态就是「没人接上」。
   */
  static boolean isCredentialFile(String memberPath) {
    return memberPath != null
        && (memberPath.equals(CredentialProjection.FILE)
            || memberPath.endsWith("/" + CredentialProjection.FILE));
  }

  /** 任务目录名：**必须匹配上游的 UUID 形状**（它自己会校验）。 */
  public static String taskName() {
    return UUID.randomUUID().toString();
  }

  /**
   * 包含关系判定。**委托给 `DshHostPaths`** —— 它两边都规范化。
   *
   * <p>自己那份用 `getAbsoluteFile()` 的实现在符号链接下会**判错**：源是
   * `/data/data/a`、任务目录是 `/data/user/0/a/task` 时，字符串比较会得出
   * 「不在源之内」—— 于是 `TASK_INSIDE_SOURCE` 这条守卫静默失效。
   */
  static boolean isInside(File ancestor, File candidate) throws IOException {
    return DshHostPaths.isInside(ancestor, candidate);
  }

  private static String sha256(BackupFileSystem fs, File file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IOException("SHA256_UNAVAILABLE", impossible);
    }
    try (InputStream in = fs.read(file, fs.stat(file))) {
      byte[] buffer = new byte[64 * 1024];
      int n;
      while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
    }
    StringBuilder out = new StringBuilder(64);
    for (byte b : digest.digest()) out.append(String.format("%02x", b));
    return out.toString();
  }

  /** 供 Kotlin 侧构造合法的任务目录名时自检，避免把非法 UUID 传进来。 */
  public static boolean validTaskName(String name) {
    return Ids.uuid(name);
  }
}
