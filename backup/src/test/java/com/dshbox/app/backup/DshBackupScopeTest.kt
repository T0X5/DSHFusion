package com.dshbox.app.backup

import com.deepseekharness.app.util.BackupScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 范围模型的**不变式**。
 *
 * 这里钉的每一条都对应一种「看起来成功、其实数据没了」的失败：
 * - 部分备份叫了全量的名字 → 老版本按全量恢复，清掉配置与插件；
 * - `path` 与 `mergeSubdir` 错位 → 恢复成功但东西没回来；
 * - 记录范围与逻辑根范围不符 → 容器报 `ROOT_SCOPE_MISMATCH`，整份归档不可用；
 * - 排除清单写宽一个目录 → 用户状态（含安全机制）静默丢失。
 */
class DshBackupScopeTest {

    // ---- 文件名：部分备份绝不叫全量的名字 ----------------------------------

    @Test
    fun onlyFullScopeUsesTheFullPrefix() {
        assertEquals("DSHFusion-backup-", DshBackupScope.FULL.filePrefix)
        for (scope in DshBackupScope.entries) {
            if (scope == DshBackupScope.FULL) continue
            assertFalse(
                "${scope.id} 的全量名字会被老版本当全量恢复",
                scope.filePrefix == DshBackupScope.FULL.filePrefix,
            )
            assertFalse(
                "${scope.id} 的前缀以全量前缀开头，认名字时会撞车",
                scope.filePrefix.startsWith(DshBackupScope.FULL.filePrefix),
            )
        }
    }

    @Test
    fun noTwoScopesShareAPrefix() {
        val prefixes = DshBackupScope.entries.map { it.filePrefix }
        assertEquals("范围前缀必须互不包含，否则 fromFileName 会有歧义", prefixes.size, prefixes.toSet().size)
        for (a in DshBackupScope.entries) {
            for (b in DshBackupScope.entries) {
                if (a == b) continue
                assertFalse("${a.id} 与 ${b.id} 的前缀互为前缀", a.filePrefix.startsWith(b.filePrefix))
            }
        }
    }

    @Test
    fun archiveNamesRoundTripThroughTheFileName() {
        for (scope in DshBackupScope.entries) {
            val name = scope.archiveName("2026-10-14T1200", ".dshdata")
            assertEquals(name, scope, DshBackupScope.fromFileName(name))
            assertEquals(name, scope, DshBackupScope.fromFileName("/downloads/$name"))
        }
    }

    @Test
    fun unknownFileNameIsNotAssumedFull() {
        // 与 DSHA 的刻意分歧：它把认不出的名字当 FULL（要兼容历史 DSHA-backup-* 老包）。
        // DSHFusion 没有历史归档，「认不出」只可能是外来文件或损坏的名字，
        // 而**默认当全量是最危险的猜法** —— 全量恢复会清掉现有配置与插件。
        assertNull(DshBackupScope.fromFileName(null))
        assertNull(DshBackupScope.fromFileName("backup.tar.gz"))
        assertNull(DshBackupScope.fromFileName("DSHA-backup-1.tar.gz"))
        assertNull(DshBackupScope.fromFileName("DSHFusion-unknown-1.dshdata"))
    }

    @Test
    fun archiveNameCannotEscapeTheDirectory() {
        for (bad in listOf("../other", "a/b", "a\\b", "..", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                DshBackupScope.FULL.archiveName(bad, ".dshdata")
            }
        }
        // 扩展名同样要挡：直接拼串就能把归档写到别处
        for (bad in listOf("dshdata", "", ".", "x/y")) {
            assertThrows(IllegalArgumentException::class.java) {
                DshBackupScope.FULL.archiveName("stamp", bad)
            }
        }
    }

    // ---- id ----------------------------------------------------------------

    @Test
    fun idRoundTripsAndRejectsUnknown() {
        for (scope in DshBackupScope.entries) {
            assertEquals(scope, DshBackupScope.fromId(scope.id))
        }
        // 配置损坏要立刻暴露，不能静默退回全量
        assertThrows(IllegalArgumentException::class.java) { DshBackupScope.fromId("bogus") }
        assertThrows(IllegalArgumentException::class.java) { DshBackupScope.fromId(null) }
    }

    // ---- 读取起点 ↔ 合并位置：必须一一对应 ---------------------------------

    @Test
    fun membersLineUpElementWise() {
        // DSHA 的同名用例只比长度（`paths.length == subs.length`）。
        // 长度相等挡不住**顺序错位**这种最常见的写错，所以这里逐项比。
        for (scope in DshBackupScope.entries) {
            for (member in scope.members) {
                val expectedLeaf = member.path.substringAfterLast('/')
                val actualLeaf = member.mergeSubdir.substringAfterLast('/')
                if (member.mergeSubdir.isEmpty()) {
                    assertEquals("${scope.id}: 空 mergeSubdir 只用于整根，${member.path} 不是整根", ".dsh", member.path)
                } else {
                    assertEquals(
                        "${scope.id} 的 ${member.path} 与 ${member.mergeSubdir} 对不上（恢复时会合到别处）",
                        expectedLeaf,
                        actualLeaf,
                    )
                }
                assertTrue("${scope.id} 的 ${member.path} 应落在 .dsh 下", member.path.startsWith(".dsh/"))
            }
        }
    }

    @Test
    fun memberIdsAreLegalAndUnique() {
        val legal = Regex("^[a-z][a-z0-9_-]{0,79}$")
        val seen = mutableSetOf<String>()
        for (scope in DshBackupScope.entries) {
            for (member in scope.members) {
                assertTrue(
                    "${scope.id} 的逻辑根名 ${member.id} 不合法（容器会报 ROOT_ID）",
                    legal.matches(member.id),
                )
                assertTrue("逻辑根名在全部范围里必须唯一（容器会报 DUPLICATE_ROOT）", seen.add(member.id))
            }
        }
    }

    @Test
    fun fullScopeCoversTheWholeDshHome() {
        assertEquals(listOf(DshBackupMember("dsh", ".dsh", "", "application")), DshBackupScope.FULL.members)
    }

    @Test
    fun sessionsScopeCarriesTheWorkspaceRegistry() {
        // dsh 1.2 起：只带 sessions/ 的话，恢复后会话文件在、但 storages/workspace.json
        // 注册表没引用，WebUI 里**看不到**这些会话 —— 这正是「恢复成功但东西没回来」。
        assertEquals(
            listOf(".dsh/sessions", ".dsh/storages", ".dsh/attachments"),
            DshBackupScope.SESSIONS.members.map { it.path },
        )
        assertEquals(
            listOf("sessions", "storages", "attachments"),
            DshBackupScope.SESSIONS.members.map { it.mergeSubdir },
        )
    }

    @Test
    fun pluginsScopeFollowsThisProjectsLayoutNotDshas() {
        // DSHA 的 PLUGINS 范围列了 plugin-src / plugin-sources.json / plugin-history /
        // plugin-safe-mode.json —— 那四个是 DSHA 自建插件管理的目录，DSHFusion 里
        // 根本不存在（全库 grep 无引用）。照搬会备份一个永远为空的目录。
        assertEquals(listOf(".dsh/profiles"), DshBackupScope.PLUGINS.members.map { it.path })
        val paths = DshBackupScope.entries.flatMap { it.members }.map { it.path }
        for (dshaOnly in listOf("plugin-src", "plugin-sources.json", "plugin-history", "plugin-safe-mode.json")) {
            assertFalse("$dshaOnly 是 DSHA 专有布局，不该出现在 DSHFusion 的范围里", paths.any { it.endsWith(dshaOnly) })
        }
    }

    // ---- 与 2.1 容器契约的对接 ---------------------------------------------

    @Test
    fun everyRecordScopeIsAllowedByItsOwnScope() {
        for (scope in DshBackupScope.entries) {
            for (member in scope.members) {
                assertTrue(
                    "${scope.id} 声明的 v5Scopes ${scope.v5Scopes} 不含成员 ${member.path} 要记的 ${member.v5Scope}",
                    scope.allowsV5Scope(member.v5Scope),
                )
            }
        }
    }

    @Test
    fun everyV5ScopeIsOneTheContainerKnows() {
        for (scope in DshBackupScope.entries) {
            assertTrue("${scope.id} 的 v5Scopes 不能为空", scope.v5Scopes.isNotEmpty())
            for (v5 in scope.v5Scopes) {
                assertTrue(
                    "${scope.id} 声明了容器不认识的范围 $v5（会报 UNKNOWN_SCOPE）",
                    BackupScopes.ALL.contains(v5),
                )
            }
        }
    }

    @Test
    fun partialScopesCannotClaimOtherScopesRecords() {
        // 容器规则：逻辑根的范围不是 application 时，它承载的记录必须**只有**那一种范围
        // （否则报 ROOT_SCOPE_MISMATCH，整份归档不可用）。所以部分范围绝不能声称
        // 自己能带别的范围的记录。
        for (scope in DshBackupScope.entries) {
            if (scope.isFull) continue
            assertEquals("${scope.id} 只应承载自己那一种范围的记录", setOf(scope.id), scope.v5Scopes)
        }
        assertEquals(BackupScopes.ALL, DshBackupScope.FULL.v5Scopes)
    }

    @Test
    fun onlyFullNamesItselfFull() {
        for (scope in DshBackupScope.entries) {
            assertEquals(scope == DshBackupScope.FULL, scope.isFull)
        }
    }
}
