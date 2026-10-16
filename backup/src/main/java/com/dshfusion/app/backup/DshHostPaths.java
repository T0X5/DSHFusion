// DSHFusion 自写。不是 DSHA 的副本 —— 登记在 governance/dsha-imports.json 的 original 段。
package com.dshfusion.app.backup;

import java.io.File;
import java.io.IOException;

/**
 * 交给 {@code BackupFileSystem} 的宿主路径**必须先规范化**。
 *
 * <h2>为什么这是必须的，而不是「好看」</h2>
 *
 * `AndroidBackupFileSystem`（逐字节导入自 DSHA）在打开文件时**逐级走父目录**，
 * 每一级都用 {@code O_PATH | O_NOFOLLOW}，并且要求那一级 {@code S_ISDIR}。
 * 只要父链里有**任何一级是符号链接**，它就直接抛 {@code PARENT_LINK}
 * —— 这是有意的安全设计：不跟随符号链接，就不存在「被链接骗到别的目录去」。
 *
 * <p>而 Android 上 {@code /data/user/0} **就是指向 `/data/data` 的符号链接**，
 * 而 {@code Context.getFilesDir()} / {@code getCacheDir()} 返回的正是
 * `/data/user/0/<包名>/…` 这条路径。
 *
 * <h2>这不是假想的</h2>
 *
 * 真机上第一次点「导出到下载目录」就红了：
 *
 * ```
 * 导出失败：PARENT_LINK
 * ```
 *
 * 原因就是导出编排把 {@code getAbsoluteFile()} 的结果直接交了出去 ——
 * 它**只保证绝对路径，不解析符号链接**。
 *
 * <h2>为什么是一个统一的入口，而不是各处在调用前顺手转一下</h2>
 *
 * 因为这条约束的作用域是「**所有**交给它的路径」，而不是某几个。
 * 逐个调用点转，早晚会漏一处 —— 而漏掉的那处表现是一个**看起来像别的问题**
 * 的错误码（`PARENT_LINK`），排查它要先想到「这其实是路径形态问题」。
 *
 * <h2>顺带修掉的一条安全侧漏</h2>
 *
 * 我们的 {@code isInside} 这类**包含关系判定**也必须两边都规范化。
 * 一边是 `/data/data/a`、另一边是 `/data/user/0/a/task` 时，
 * 原始字符串比较会认为「不在里面」—— 于是 `TASK_INSIDE_SOURCE`
 * 这条「任务目录不许落在源之内」的守卫会**静默失效**。
 */
final class DshHostPaths {
  private DshHostPaths() {}

  /**
   * 解析掉路径里的符号链接。
   *
   * <p>`getCanonicalFile()` 会访问文件系统（解析每一级链接），所以它不是纯字符串操作，
   * 也**会抛 IOException**。那正是我们想要的：路径不可解析时早失败，
   * 而不是把一个可疑的字符串交下去。
   */
  static File canonical(File file) throws IOException {
    if (file == null) throw new IOException("PATH_REQUIRED");
    return file.getCanonicalFile();
  }

  /** 包含关系判定。**两边都先规范化**，否则符号链接会让判定出错（见类注释）。 */
  static boolean isInside(File ancestor, File candidate) throws IOException {
    File a = canonical(ancestor);
    File c = canonical(candidate);
    while (c != null) {
      if (c.equals(a)) return true;
      c = c.getParentFile();
    }
    return false;
  }
}
