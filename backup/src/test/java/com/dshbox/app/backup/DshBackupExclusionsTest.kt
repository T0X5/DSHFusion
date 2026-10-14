package com.dshbox.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排除清单的不变式。
 *
 * 这一组的存在理由是一个**真机事故记录**（`DshboxLayoutMigration` 的类注释）：
 * `.dsh/dshbox/` 里的三份 overlay 承载用户状态，其中「哪些插件被停用」是**安全机制**，
 * 丢了会把此前被隔离的坏插件重新加载 —— 「真机上出过『每次都起不来』的事故」。
 *
 * 而 `.dsh/dshbox` 从名字上看就是「我方资产目录」，最自然的写法就是整目录排除。
 * 所以这里把「不能整目录排除」写成断言，而不是写在注释里。
 */
class DshBackupExclusionsTest {

    @Test
    fun invariantsAreClean() {
        assertEquals(emptyList<String>(), DshBackupExclusions.checkInvariants())
    }

    // ---- 可以排除的：重铺得出来的产物 --------------------------------------

    @Test
    fun regeneratedAssetsAreExcluded() {
        for (path in DshBackupExclusions.REGENERATED) {
            assertTrue("$path 应当被排除", DshBackupExclusions.isExcluded(path))
            assertTrue("$path 的子孙也应当被排除", DshBackupExclusions.isExcluded("$path/whatever/file"))
        }
        // 具体到会真的出现在设备上的几个
        assertTrue(DshBackupExclusions.isExcluded(".dsh/dshbox/bin/dsh"))
        assertTrue(DshBackupExclusions.isExcluded(".dsh/dshbox/dshbox-plugins/mobile-adapt/plugin"))
        assertTrue(DshBackupExclusions.isExcluded(".dsh/pnpm-store/v3/files/ab"))
    }

    // ---- 不能排除的：看着像资产，其实是状态 --------------------------------

    @Test
    fun assetsCarryingUserStateAreNotExcluded() {
        for (keep in DshBackupExclusions.MUST_KEEP) {
            assertFalse("$keep 承载用户状态，排除它会让恢复后的坏插件被重新加载", DshBackupExclusions.isExcluded(keep))
            assertFalse(
                "$keep 里的文件也不能被排除",
                DshBackupExclusions.isExcluded("$keep/some-file.yml"),
            )
        }
        // 资产目录**本身**不能被排除 —— 否则根本走不进去，那三份 overlay 也就丢了
        assertFalse(
            ".dsh/dshbox 整目录排除会连带丢掉三份承载状态的 overlay",
            DshBackupExclusions.isExcluded(".dsh/dshbox"),
        )
    }

    /**
     * 把「写宽一个目录」这件事**逐级祖先**查一遍。
     *
     * 与 `checkInvariants()` 不重复：那边比的是清单里的项，这边把每个必须保留路径的
     * **每一个祖先目录**都拿出来问一次 `isExcluded`。有人把 `.dsh/dshbox` 加进排除清单时，
     * 这条会立刻红 —— 而且报的是具体是哪个祖先。
     */
    @Test
    fun noAncestorOfAKeptPathIsExcluded() {
        for (keep in DshBackupExclusions.MUST_KEEP) {
            val parts = keep.split("/")
            for (depth in 1..parts.size) {
                val ancestor = parts.take(depth).joinToString("/")
                assertFalse(
                    "$ancestor 是必须保留的 $keep 的祖先，却被排除了 —— 这个排除项写得太宽",
                    DshBackupExclusions.isExcluded(ancestor),
                )
            }
        }
    }

    // ---- `.dsh/bin` 这个坑 -------------------------------------------------

    @Test
    fun unNamespacedLegacyBinIsNotExcluded() {
        // SandboxService.removeLegacyAssetDirs() 的注释：`.dsh/bin` 这个**名字没有命名空间**，
        // 「每次启动都无条件删会静默吃掉用户自己建的 ~/.dsh/bin」，所以那是一次性清理。
        // 同理这里不排除它：用户可能真在里面放东西，丢用户数据比多备份一份脚本严重得多。
        assertFalse(".dsh/bin 可能装着用户自己的东西，不能排除", DshBackupExclusions.isExcluded(".dsh/bin"))
        assertFalse(DshBackupExclusions.isExcluded(".dsh/bin/my-script"))
    }

    // ---- 凭据 --------------------------------------------------------------

    @Test
    fun credentialsAreExcluded() {
        // Constants.kt 第 148 行把 DSH 的数据列为
        // `profiles / sessions / settings.yaml / storages / credentials`。
        for (path in DshBackupExclusions.CREDENTIALS) {
            assertTrue(DshBackupExclusions.isExcluded(path))
            assertTrue(DshBackupExclusions.isExcluded("$path/nested"))
        }
    }

    @Test
    fun dshOwnDataIsNotExcluded() {
        // 与上一条互为反向：排除凭据不能顺手把 DSH 的其它数据也排掉。
        for (path in listOf(".dsh/profiles", ".dsh/sessions", ".dsh/storages", ".dsh/settings.yaml", ".dsh/attachments")) {
            assertFalse("$path 是 DSH 的数据，必须进归档", DshBackupExclusions.isExcluded(path))
        }
    }

    // ---- 路径归一化 --------------------------------------------------------

    @Test
    fun pathNormalisationDoesNotOpenHoles() {
        assertFalse(DshBackupExclusions.isExcluded(""))
        assertFalse(DshBackupExclusions.isExcluded("/"))
        // 首尾斜杠会被去掉，不会因为多一个 / 就漏掉排除
        assertTrue(DshBackupExclusions.isExcluded(".dsh/dshbox/bin/"))
        assertTrue(DshBackupExclusions.isExcluded("/.dsh/dshbox/bin"))
        // 相邻名字不能被误伤：`.dsh/dshbox/binx` 不是 `bin` 的子孙
        assertFalse(DshBackupExclusions.isExcluded(".dsh/dshbox/binx"))
        // 前缀相同但层级不同的，也不能被误伤
        assertFalse(DshBackupExclusions.isExcluded(".dsh/pnpm-store-other"))
    }
}
