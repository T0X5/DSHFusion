// DSHFusion 自写。登记在 governance/dsha-imports.json 的 original 段。
package com.dshbox.app.backup

import java.util.function.Predicate

/**
 * 把 Kotlin 侧的范围与排除清单，接到 Java 侧的导出编排上。
 *
 * ## 为什么要有这一层，而不是让界面直接调 `DshBackupExporter`
 *
 * `DshBackupExporter` 收的是**纯数据**（`Member`）与一个「user-data 相对路径要不要排除」
 * 的谓词。它**故意不认识** `DshBackupScope` 与 `DshBackupExclusions` ——
 * 那两个在 Kotlin 侧，一旦被它 import，本机（没有 kotlinc）就编不了、跑不了，
 * 而它恰恰是最该本机验的一层（见 `docs/INCIDENTS.md` 第 21 条）。
 *
 * 所以翻译放在这里，只有**三处**真正的决策，且每一处都可能静默出错：
 *
 * 1. **`v5Scope`** 是写进每条记录的 v5 范围名。写错不会报错，只会让
 *    「读回时的反向核验」在将来拒绝一份其实合法的归档 —— 所以这里不自己拼字符串，
 *    直接取 `DshBackupMember.v5Scope`（单一来源）。
 * 2. **`external` 恒为 `false`。** 我们所有的成员都在应用自己的 `user-data` 里。
 *    它与容器的 `integrity` 字段有关系：`HostSnapshot` 在没有任何外部根时给出
 *    `QUIESCENT`，有外部根时给出 `EXTERNAL_CHECKED`。写成 `true` 会让完整性字段
 *    说谎 —— 而**保留策略正是按这个字段决定删不删**（`DshAutomaticBackupRetention`）。
 * 3. **排除谓词的坐标系。** 这里传出去的谓词收的是 **user-data 相对**路径
 *    （`.dsh/credentials`），与 `DshBackupExclusions.isExcluded` 同一套坐标系；
 *    到成员根的拼接由 Java 侧做（那一层有测试钉住）。**不要在这里提前拼前缀** ——
 *    两边都拼一次，排除清单会一条都不命中，而且不报任何错。
 */
object DshBackupExportBridge {

    /**
     * 范围内的成员 → 导出编排要的纯数据。
     *
     * [DshBackupMember.mergeSubdir] **不进这里**：它是**恢复**用的落点信息
     * （合并到哪个子目录下），与导出无关。导出只需要「读哪个根」。
     */
    @JvmStatic
    fun members(scope: DshBackupScope): List<DshBackupExporter.Member> =
        scope.members.map { member ->
            DshBackupExporter.Member(member.id, member.path, member.v5Scope, false)
        }

    /**
     * 排除清单 → 谓词。收的是 **user-data 相对**路径。
     *
     * 每次调用都新建一个：`DshBackupExclusions` 是无状态的 object，
     * 这里不做任何缓存 —— 缓存一份「清单快照」会让将来改清单时出现
     * 「同一进程里两处判断不一致」的假象。
     */
    @JvmStatic
    fun exclusions(): Predicate<String> = Predicate { path -> DshBackupExclusions.isExcluded(path) }

    /**
     * 建议的归档文件名。
     *
     * 用范围的 [DshBackupScope.filePrefix] 而不是自己拼 —— 那条不变式
     * （**部分备份绝不能用全量的文件名**，否则老版本会把它当全量恢复并清掉配置与插件）
     * 是 `DshBackupScope` 存在的理由之一，不能在这里绕过去。
     *
     * @param stamp 形如 `20261015-061500`。`archiveName` 会拒绝含 `/`、`\`、`..` 的戳。
     */
    @JvmStatic
    fun archiveName(scope: DshBackupScope, stamp: String): String =
        scope.archiveName(stamp, ARCHIVE_EXTENSION)

    /** 归档的扩展名。**改了就不认识旧产物了** —— 读回时的 `fromFileName` 靠它分辨。 */
    const val ARCHIVE_EXTENSION: String = ".dshbak"
}
