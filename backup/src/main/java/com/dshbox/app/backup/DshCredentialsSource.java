// DSHFusion 自写。不是 DSHA 的副本 —— 登记在 governance/dsha-imports.json 的 original 段。
package com.dshbox.app.backup;

import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupSource;
import com.deepseekharness.app.backup.CredentialProjection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 把**凭据投影**施加到一棵树里的**那一个文件**上。
 *
 * <h2>为什么不能直接用上游的 {@code CredentialFileBackupSource}</h2>
 *
 * 那个类对**它拿到的每一个 item** 做投影（并且硬性要求「源里只有一个文件、
 * 路径为空」）。所以它只能套在「**根就是凭据文件**」的源上。
 *
 * <p>而「根就是凭据文件」意味着凭据文件成了**独立的根** —— 于是它的恢复目标
 * （`.dsh/.credentials.yaml`）落在另一个根（`.dsh`）的目标**之内**。
 * 上游 {@code HostDataTransaction} 用**路径前缀**比较判定目标重叠，
 * 这种情况直接抛 {@code OVERLAPPING_TARGETS}：
 *
 * <pre>
 * for (String seen : uniqueTargets)
 *   if (path.equals(seen) || path.startsWith(seen + File.separator)
 *       || seen.startsWith(path + File.separator)) throw new IOException("OVERLAPPING_TARGETS");
 * </pre>
 *
 * <p>那个拒绝是**对的**：两个根各自独立走「五相位标记 + 换入」，
 * 目标互相嵌套时无法保证「要么都换成、要么都不换」。
 *
 * <h2>所以：一个根，内部按路径选择性投影</h2>
 *
 * 这个类包在**整棵 {@code .dsh} 树**的源外面，只对路径等于
 * {@link CredentialProjection#FILE} 的那一个 item 动手，其余**原样透传**。
 * 根的数量不变 ⇒ 目标不重叠 ⇒ 上游的事务可以照常用。
 *
 * <h2>与上游那层共享的两条语义</h2>
 *
 * <ol>
 *   <li><b>token 里同时绑原始与投影后的哈希</b>（{@code 原token:hash(raw):hash(portable)}）——
 *       读取时先验原始字节没变，再验投影结果一致。少了任何一半，
 *       「读的时候文件已经变了」都会静默产出一份不一致的归档。
 *   <li><b>大小记的是投影后的长度</b>，不是原始长度。
 * </ol>
 */
final class DshCredentialsSource implements BackupSource {
  private final BackupSource original;

  DshCredentialsSource(BackupSource original) {
    this.original = original;
  }

  public String id() {
    return original.id();
  }

  public String scope() {
    return original.scope();
  }

  public boolean external() {
    return original.external();
  }

  public boolean containsLocal(File file) {
    return original.containsLocal(file);
  }

  public Map<String, Object> description() {
    return original.description();
  }

  public String category(Item item) {
    return original.category(item);
  }

  /**
   * 这个 item 是不是那份凭据文件。
   *
   * <p>认**文件名**（{@link CredentialProjection#FILE}，来自逐字节导入的副本），
   * 不认调用方传的标志位 —— 这个模块的历史教训是「没人接上」，
   * 而按路径判断则**不可能忘**。
   */
  static boolean isCredential(String path) {
    return path != null
        && (path.equals(CredentialProjection.FILE)
            || path.endsWith("/" + CredentialProjection.FILE));
  }

  private byte[] raw(Item item, BackupControl control) throws IOException {
    try (InputStream input = original.open(item);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        control.check();
        if (count > CredentialProjection.LIMIT - output.size())
          throw new IOException("CREDENTIAL_PROJECTION_LIMIT");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  private static String hash(byte[] bytes) {
    return BackupArchive.hex(BackupArchive.sha().digest(bytes));
  }

  public void walk(Visit visitor, BackupControl control) throws IOException {
    original.walk(
        item -> {
          if (!isCredential(item.path)
              || item.kind.equals("MISSING")
              || item.kind.equals("UNREADABLE")
              || item.kind.equals("EXCLUDED")
              || !item.kind.equals("FILE")) {
            // 非凭据、以及一切特殊形态，**原样透传** —— 这个类只负责那一个文件。
            visitor.item(item);
            return;
          }
          byte[] raw = raw(item, control), portable = CredentialProjection.portable(raw);
          visitor.item(
              new Item(
                  item.path,
                  "FILE",
                  portable.length,
                  item.token + ":" + hash(raw) + ":" + hash(portable),
                  item.target,
                  item.reason,
                  item.mode));
        },
        control);
  }

  public InputStream open(Item item) throws IOException {
    if (!isCredential(item.path)) return original.open(item);
    String[] token = item.token.split(":", -1);
    if (token.length != 3 || !token[1].matches("[a-f0-9]{64}") || !token[2].matches("[a-f0-9]{64}"))
      throw new IOException("CREDENTIAL_PROJECTION_ITEM");
    Item source = new Item(item.path, "FILE", 0, token[0], item.target, item.reason, item.mode);
    byte[] raw = raw(source, new BackupControl(null));
    if (!token[1].equals(hash(raw))) throw new IOException("CREDENTIAL_PROJECTION_SOURCE_CHANGED");
    byte[] portable = CredentialProjection.portable(raw);
    if (portable.length != item.size || !token[2].equals(hash(portable)))
      throw new IOException("CREDENTIAL_PROJECTION_SOURCE_CHANGED");
    return new ByteArrayInputStream(portable);
  }

  public void verify(Item item, String sha256, BackupControl control) throws IOException {
    if (!isCredential(item.path)) {
      original.verify(item, sha256, control);
      return;
    }
    if (item.kind.equals("EXCLUDED")) return;
    try (InputStream input = open(item)) {
      if (!sha256.equals(BackupArchive.digest(input, control)))
        throw new IOException("CREDENTIAL_PROJECTION_SOURCE_CHANGED");
    }
  }
}
