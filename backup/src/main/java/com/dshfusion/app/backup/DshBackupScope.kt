package com.dshfusion.app.backup

import com.deepseekharness.app.backup.CredentialProjection

/**
 * 备份范围里的一项：**从哪读、往哪合、记成哪个 v5 范围**。
 *
 * 三者必须一一对应。错位的后果不是报错，而是「恢复成功但东西没回来」——
 * 这是备份功能最坏的失败形态，因为它看起来是成功的。所以 [id] / [path] /
 * [mergeSubdir] / [v5Scope] 都写成显式常量，并由单测逐项钉住。
 *
 * @param id 归档里的**逻辑根**名。必须匹配容器对根名的要求 `[a-z][a-z0-9_-]{0,79}`，
 *   且在同一份归档里唯一（容器会报 `DUPLICATE_ROOT`）。
 * @param path 读取起点，**相对 `user-data`**（宿主 `filesDir/user-data`，即 guest 的 `/root/projects`）。
 * @param mergeSubdir 恢复时合并到 `.dsh` 下的相对位置。空串表示「`.dsh` 整根」。
 * @param v5Scope 写进归档的记录范围，必须是容器 `BackupScopes` 允许的字面量。
 */
data class DshBackupMember(
    val id: String,
    val path: String,
    val mergeSubdir: String,
    val v5Scope: String,
)

/**
 * DSHFusion 的备份范围 —— 用户在界面上选的那一个。
 *
 * ## 与 DSHA 的关系（这是**改写后移植**，不是原样复制）
 *
 * 来源：`DSHA:app/src/main/java/com/deepseekharness/app/util/BackupScope.java`（MIT）。
 * 保留的是它的**结构与那条最重要不变式**：部分备份绝不能用全量的文件名
 * （老版本会把它当全量恢复，清掉配置与插件）。改掉的是三处：
 *
 * 1. **`.dsh` 子路径按 DSHFusion 的真实布局校正**。DSHA 的 PLUGINS 范围列了
 *    `plugin-src` / `plugin-sources.json` / `plugin-history` / `plugin-safe-mode.json`，
 *    这四个在 DSHFusion 里**根本不存在**（全库 grep 无引用）—— 它们是 DSHA 自建那套
 *    插件管理的目录。照搬会得到「备份了一个永远为空的目录」。DSHFusion 的插件实体与
 *    清单在 `.dsh/profiles/<profile>/`（`PluginPaths.profileDir` / `nodeModulesDir`）。
 * 2. **不带 DSHA 的文案层**。DSHA 的 `label()` / `describe()` / `restoreImpact()` 走
 *    `UiText` + `UiMessages` + `UiLanguagePreference` 一整条 i18n 子系统；DSHFusion 用
 *    6 语言的 Android 资源 + `tools/i18n_check.sh`，搬过来会变成第二套语言服务。
 *    所以这里**只有数据**，文案留给界面层。
 * 3. **认不出的文件名不再默认当成全量**（见 [fromFileName]）。
 *
 * 与 DSHA 一致的部分：`dshPaths` 描述的是 **DSH 自己的目录布局**（`.dsh/sessions`、
 * `.dsh/storages`、`.dsh/settings.yaml`），不是 DSHA 的私有布局，所以那部分是可按原样
 * 对照使用的 —— 并且已经用 DSHFusion 侧的证据复核过：`Constants.kt` 第 148 行把 DSH 的
 * 数据列为 `profiles / sessions / settings.yaml / storages / credentials`，
 * `SandboxService.kt` 第 557 行同样把 `profiles/sessions/storages` 称作「DSH 的数据」。
 *
 * ## 刻意没做
 *
 * 「每个范围允许出现哪些 v5 记录范围」用 [v5Scopes] 表达，但**不在这里推导每个文件的
 * 逐条范围**：FULL 范围把 `.dsh` 当一个根走，记录范围统一记 `application`。容器允许
 * `application` 根承载任意范围的记录（见 `BackupArchive.validateSummary` 里那条
 * 「非 application 根不许夹带别的范围」的判定），所以这不构成错误标注，只是粒度更粗。
 * 逐条细化属于阶段 2.4 的恢复编排。
 */
enum class DshBackupScope(
    /** 写进归档清单/配置的范围名。**一旦发布不可更改**（旧归档靠它认范围）。 */
    val id: String,
    /**
     * 归档文件名前缀。
     *
     * **只有全量能用 `DSHFusion-backup-`。** 别的范围若也叫这个名字，
     * 老版本会把一份「只有会话」的归档当全量恢复，然后清掉配置与插件。
     * [DshBackupScopeTest] 逐条钉住这一点。
     */
    val filePrefix: String,
    /** 这份归档里**允许**出现的 v5 记录范围。必须是容器 `BackupScopes` 的子集。 */
    val v5Scopes: Set<String>,
) {
    FULL(
        id = "full",
        filePrefix = "DSHFusion-backup-",
        v5Scopes = setOf("application", "sessions", "settings", "plugins", "projects"),
    ) {
        override val members: List<DshBackupMember>
            get() =
                listOf(
                    // **只有一个根。** 凭据文件**不**单独成根。
                    //
                    // 我第一版让它单独成根（好套上游那个只认单文件的投影装饰器），
                    // 结果恢复时报 `OVERLAPPING_TARGETS`：`.dsh/.credentials.yaml`
                    // 落在 `.dsh` 之内，而上游用**路径前缀**判目标重叠。
                    // 那个拒绝是对的 —— 嵌套目标没法保证「要么都换成、要么都不换」。
                    //
                    // 现在投影由 `DshCredentialsSource` 在**树内部**按路径施加，
                    // 根仍然只有一个。
                    DshBackupMember("dsh", ".dsh", "", "application"),
                )
    },

    SESSIONS(
        id = "sessions",
        filePrefix = "DSHFusion-sessions-",
        v5Scopes = setOf("sessions"),
    ) {
        override val members: List<DshBackupMember>
            get() =
                listOf(
                    DshBackupMember("dsh-sessions", ".dsh/sessions", "sessions", "sessions"),
                    // dsh 1.2 起：会话文件在 sessions/，但 UI 入口是 storages/workspace.json
                    // 注册表（workspace → sessionIds）。只带 sessions/ 的话，恢复后会话文件
                    // 在、注册表没引用，WebUI 里**看不到** —— 所以注册表必须一起走。
                    DshBackupMember("dsh-storages", ".dsh/storages", "storages", "sessions"),
                    DshBackupMember("dsh-attachments", ".dsh/attachments", "attachments", "sessions"),
                )
    },

    SETTINGS(
        id = "settings",
        filePrefix = "DSHFusion-settings-",
        v5Scopes = setOf("settings"),
    ) {
        override val members: List<DshBackupMember>
            get() = listOf(DshBackupMember("dsh-settings", ".dsh/settings.yaml", "settings.yaml", "settings"))
    },

    PLUGINS(
        id = "plugins",
        filePrefix = "DSHFusion-plugins-",
        v5Scopes = setOf("plugins"),
    ) {
        override val members: List<DshBackupMember>
            get() = listOf(DshBackupMember("dsh-profiles", ".dsh/profiles", "profiles", "plugins"))
    };

    /** 读取起点与合并位置的对应关系。见 [DshBackupMember] 的类型说明。 */
    abstract val members: List<DshBackupMember>

    /** 只有全量范围可以叫全量名字。 */
    val isFull: Boolean get() = this == FULL

    /**
     * 这个范围是否允许归档里出现某个 v5 记录范围。
     *
     * 用途是**读回时的反向核验**：拿到一份声称是 SESSIONS 的归档，里面却有条记录写着
     * `settings`，那它要么不是这个范围打的，要么被人改过 —— 两种情况都不该继续恢复。
     */
    fun allowsV5Scope(scope: String): Boolean = scope in v5Scopes

    /**
     * 生成本范围内的归档文件名。
     *
     * @param stamp 时间戳/序号一类的后缀。
     * @param extension 扩展名（含点）。**由阶段 2.4 决定**：归档怎么封装属于导出格式，
     *   不该在这里猜 —— 2.1 的容器本身是裸 `DSHDATA` 字节流，DSHA 之所以叫 `.tar.gz`
     *   是因为它的宿主导出又套了一层 tar+gzip。
     * @throws IllegalArgumentException [stamp] 含路径分隔符或 `..`（否则能把文件写到下载目录之外）。
     */
    fun archiveName(stamp: String, extension: String): String {
        require(stamp.isNotEmpty()) { "归档名后缀不能为空" }
        require(!stamp.contains('/') && !stamp.contains('\\') && !stamp.contains("..")) {
            "归档名后缀不能含路径分隔符或 ..：$stamp"
        }
        require(extension.startsWith(".") && extension.length > 1) { "扩展名必须形如 .xxx：$extension" }
        return filePrefix + stamp + extension
    }

    companion object {
        /**
         * 按文件名认范围；**认不出返回 null**。
         *
         * 这是与 DSHA 的一处**刻意分歧**：DSHA 的 `fromFileName(null)` 与认不出的名字都
         * 返回 `FULL`，因为它要兼容历史上没有范围前缀的 `DSHA-backup-*` 老包。
         * DSHFusion 没有任何历史归档（阶段 2 才引入备份），所以「认不出」只可能是
         * 别人给的文件或损坏的名字 —— 此时**默认当成全量是最危险的猜法**：
         * 全量恢复会清掉现有配置与插件。宁可让调用方处理 null。
         */
        fun fromFileName(name: String?): DshBackupScope? {
            if (name == null) return null
            val leaf = name.substringAfterLast('/').substringAfterLast('\\')
            // 先长后短：DSHFusion-backup- 与 DSHFusion-sessions- 无前缀包含关系，
            // 但将来若新增更长的前缀，这个顺序能保证长前缀优先。
            return entries
                .sortedByDescending { it.filePrefix.length }
                .firstOrNull { leaf.startsWith(it.filePrefix) }
        }

        /** 按 [id] 认范围；认不出抛错（配置损坏要立刻暴露，不能静默退回全量）。 */
        fun fromId(id: String?): DshBackupScope =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("未知的备份范围：$id")
    }
}
