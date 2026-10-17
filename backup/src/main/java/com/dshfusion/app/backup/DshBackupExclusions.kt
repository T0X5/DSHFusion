package com.dshfusion.app.backup

import com.deepseekharness.app.backup.CredentialProjection

/**
 * 备份的排除清单：**哪些不进归档，哪些看着像资产却必须进**。
 *
 * ## 为什么不能只写「排除 `.dsh/dshbox`」
 *
 * `.dsh/dshbox` 是我方随包资产目录，第一直觉是整目录排除。**那是错的**，
 * 而且是会造成真机故障的那种错。`DshboxLayoutMigration` 的类注释写得很清楚：
 *
 * > 三份层文件承载**用户状态**：官方插件开关、**哪些插件被停用**、绝对安全模式整层。
 * > 其中「被停用」是安全机制：丢了会把此前被隔离的坏插件重新加载 ——
 * > 真机上出过「每次都起不来」的事故。
 *
 * 那三份文件就在 `.dsh/dshbox/` 里面。所以排除必须是**按子路径逐项**的：
 * 重铺得出来的（`bin/`、装配暂存）排除，承载状态的（三份 overlay、
 * 隔离清单）必须留下。
 *
 * ## 为什么 `.dsh/bin` 不在排除清单里
 *
 * 历史上我方资产散放在 `.dsh/bin`、`.dsh/mobile-adapt`、`.dsh/pilot`，
 * 后来收拢到 `.dsh/dshbox`。但 `SandboxService.removeLegacyAssetDirs()` 的注释专门
 * 说明：`.dsh/bin` 这个**名字没有命名空间**，「每次启动都无条件删会静默吃掉
 * 用户自己建的 `~/.dsh/bin`」，所以那是一次性清理而不是每次清。
 *
 * 同理，这里**不排除** `.dsh/bin`：它可能真是用户自己放东西的地方。
 * 排除了会把用户数据静默丢掉，而丢数据比多备份一份脚本严重得多。
 * 有命名空间的 `.dsh/dshbox/bin`（我方 CLI 包装脚本，每次 bootstrap 重写）
 * 才是可以安全排除的那个。
 */
object DshBackupExclusions {

    /**
     * **装机或每次启动就会重铺的产物**，进归档只会把过期副本带回去。
     *
     * 路径均相对 `user-data`。
     */
    val REGENERATED: List<String> =
        listOf(
            // 我们的 CLI 包装脚本（`dsh` / apt 包装等），bootstrap 时按 0555/0444 重写。
            ".dsh/dshbox/bin",
            // 我方自有插件包的**装配暂存**，每次启动由 APK 资产重铺（没有状态）。
            ".dsh/dshbox/dshbox-plugins",
            // 已退役的 pilot 覆盖层目录。
            ".dsh/dshbox/pilot",
            // 早期散放位置里同样没有状态的暂存目录（见 DshboxLayoutMigration.LEGACY_STAGE_DIRS）。
            ".dsh/dshbox/mobile-adapt",
            ".dsh/dshbox/mobile-pilot",
            // pnpm 的 store 目录：**内容寻址、可由锁文件重建**。
            // 正常情况下它根本不在 user-data 里（在 `filesDir/guest-cache`，见 SandboxService
            // 的 PNPM_CLI_SHIM），只有当缓存挂载点不存在时才回落到这里 —— 那时它仍然只是
            // 缓存，且可能有几个 GB。
            ".dsh/pnpm-store",
        )

    /**
     * **看着像资产、其实是状态**，必须进归档。
     *
     * 这一组是给 [checkInvariants] 用的：它保证将来有人把上面对某个父目录的排除
     * 写宽（例如把 `.dsh/dshbox` 整个排掉）时，单测会立刻红，而不是等到用户真机上
     * 恢复后坏插件被重新加载。
     */
    val MUST_KEEP: List<String> =
        listOf(
            // 「DSH官方插件」的开关（DshboxLayoutMigration: features/feature-overlay.yml）。
            ".dsh/dshbox/dsh-official-plugin",
            // **哪些插件被停用** —— 安全机制，丢了会把隔离的坏插件重新加载。
            ".dsh/dshbox/plugin-market",
            // 绝对安全模式独占整层。
            ".dsh/dshbox/safe-mode",
            // 安全模式的隔离清单与归因记录（PluginPaths.guardStateFile）。
            ".dsh/dshbox/plugin-guard.json",
        )

    // 🔴 **这里原先有一条「凭据整份不进归档」，已删除。**
    //
    // 它曾经是错的两次：
    //   ① 路径写成 `.dsh/credentials`（我照着 `Constants.kt` 里的**逻辑名**猜的），
    //      而真机上的文件名是 `.credentials.yaml` —— 那条排除**匹配不到任何东西**，
    //      于是用户的 API Key 明文进了归档；
    //   ② 修好路径之后它又和「凭据投影」互相矛盾：一边说整份排除、
    //      一边说投影后保留。两者不可能同时成立。
    //
    // **现在的语义是明确的**：凭据文件**进归档，但是投影之后的**
    // （`DshCredentialsSource` 按路径施加，见那个类的注释）。
    // 所以它**不该**出现在排除清单里 —— 排除 = 不备份，而用户要的是「投影后备份」。

    /**
     * 🔴 **桥接与调试凭据 —— 现在手机上还没有，但阶段 3.1 一定会带来它们。**
     *
     * 这条是**预防性的**，而它有一个具体的来源：本项目的容器里那份 DSH home
     * （DSHA 风格的设备桥集成）**确实**有这些文件：
     *
     * ```
     * .dsh/.bridge_token        32 字节：桥接令牌
     * .dsh/.bridge_headers      42 字节：桥接鉴权头
     * .dsh/.bridge_status
     * .dsh/adbkeys/             ADB 私钥（能连设备）
     * .dsh/adb-wheels.tar.gz    9.9 MB：可重建的 wheel 包，不是状态
     * ```
     *
     * **而我们的备份范围是整个 `.dsh`。** 也就是说：谁把设备桥接进 DSH home，
     * 谁就会让这些凭据进归档 —— 与技术事故第 41 条（API Key 明文进归档）**同一个坑**。
     *
     * 今天**不漏**：真机归档里 `.dsh` 根下只有 `.anonymous-user-id`、`.credentials.yaml`、
     * `dshbox/`、`profiles/`、`sessions/`、`storages/`；也确认全库没有往 `.dsh` 写
     * token / adbkeys 的代码。**但 3.1 正好要做设备桥。**
     *
     * ⚠️ 这是**保险，不是修复** —— 它的效果**没有被真机验证过**（没有文件可验）。
     * 单测证明的是「这些路径**会被**排除」，而不是「真机上一次都没漏」。
     */
    val BRIDGE_CREDENTIALS: List<String> =
        listOf(
            ".dsh/.bridge_token",
            ".dsh/.bridge_headers",
            ".dsh/.bridge_status",
            ".dsh/adbkeys",
            // 9.9 MB 的 wheel 包：内容寻址、可从锁文件重建，进归档只是把包撑大。
            ".dsh/adb-wheels.tar.gz",
        )

    /**
     * **设备能力的授权状态**（阶段 3.2 未决③ / 3.4 §四）。
     *
     * 它必须**不进归档**，理由与凭据不同：**授权是「这台设备对这台设备」的信任。**
     * 跟着归档搬到新设备上，等于「换台设备恢复」时把旧的信任一起搬过去 ——
     * 那不是恢复数据，**那是伪造授权**。
     *
     * 路径与 {@code GrantStore.FILE_NAME} 必须一致；那条链没有编译期约束，
     * 所以这里写死并在测试里钉住（两处不一致会表现为「排除没生效」而没有任何报错）。
     *
     * ⚠️ 与 [BRIDGE_CREDENTIALS] 一样，这是**保险，不是修复**：真机上还没有这个文件，
     * 所以「真机上一次都没漏」没有被验证过。单测证明的是「这些路径**会被**排除」。
     */
    val CAPABILITY_GRANTS: List<String> =
        listOf(
            ".dsh/.capability-grants",
            // 保存是先写临时文件再改名；正常不会残留，但残留的那一份内容同样是授权状态。
            ".dsh/.capability-grants.tmp",
        )

    /** [isExcluded] 用的全集。 */
    private val all: List<String> = REGENERATED + BRIDGE_CREDENTIALS + CAPABILITY_GRANTS

    /**
     * 这个路径是否不进归档。命中规则：**等于**清单项，或是清单项的**子孙**。
     *
     * 不做「祖先也命中」的推断 —— 那正是把 `.dsh/dshbox` 整个排掉的错法。
     * 见 [checkInvariants]。
     */
    fun isExcluded(relativeToUserData: String): Boolean {
        val path = relativeToUserData.trim('/')
        if (path.isEmpty()) return false
        return all.any { path == it || path.startsWith("$it/") }
    }

    /**
     * 清单自身的结构不变式。**返回问题描述列表，空表示健康。**
     *
     * 这些不是运行时逻辑，是给单测/CI 的断言入口 —— 一条检查若只写在注释里，
     * 下一个人重构时看不见它。
     */
    fun checkInvariants(): List<String> {
        val problems = mutableListOf<String>()

        // 一段就够：isExcluded 命中「等于或子孙」，所以「某个排除项是它的祖先」
        // 这一情形已经被覆盖 —— 重复写三段只会让人以为它们各管一件事。
        for (keep in MUST_KEEP) {
            if (isExcluded(keep)) {
                problems += "必须保留的 $keep 被排除清单命中（多半是把某个父目录排宽了）"
            }
        }
        for (path in all + MUST_KEEP) {
            if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
                problems += "$path 不是干净的 user-data 相对路径"
            }
        }
        if (all.toSet().size != all.size) problems += "排除清单里有重复项"
        if (MUST_KEEP.toSet().size != MUST_KEEP.size) problems += "必须保留清单里有重复项"
        if (all.toSet().intersect(MUST_KEEP.toSet()).isNotEmpty()) {
            problems += "同一路径同时出现在排除与必须保留清单里"
        }
        return problems
    }
}
