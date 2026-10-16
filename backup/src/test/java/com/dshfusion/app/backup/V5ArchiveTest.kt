package com.dshfusion.app.backup

import com.deepseekharness.app.backup.BackupArchive
import com.deepseekharness.app.backup.BackupControl
import com.deepseekharness.app.backup.BackupJson
import com.deepseekharness.app.backup.BackupLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5 归档预检在 DSHFusion 侧的行为。
 *
 * 这里刻意**不只断言「抛了异常」**，而是钉住具体错误码 —— 阶段 2 的验收要求是
 * 「故意投喂损坏备份 → 明确报错且不破坏现有数据」，「明确」指的就是这个：
 * 用户看到的必须是可以据以处理的分类，而不是一句笼统的失败。
 *
 * 容器本身（截断到每一个长度、篡改、尾部多余字节、路径穿越、重复路径、
 * 假完整、深层嵌套 JSON）已由原样导入的 `BackupArchiveTest` / `BackupJsonTest`
 * 覆盖，这里不重复，只测 DSHFusion 自己加的那一层。
 *
 * 需要留意两类夹具的**分工**：
 * - [archive] 走真实的 `BackupArchive.Writer`，证明写入侧在我们这里可用；
 * - [forge] 手写组装容器，用来构造**writer 自己就会拒绝**的恶意输入
 *   （writer 的 `finish` 也做同一套校验）。没有 [forge]，
 *   「假完整」与「范围夹带」这两条就只能在构造夹具阶段抛错，
 *   根本测不到读取侧。
 */
class V5ArchiveTest {

    private val fileA = "对话字节\n".toByteArray(Charsets.UTF_8)
    private val fileB = """{"model":"deepseek"}""".toByteArray(Charsets.UTF_8)

    private val defaultRoots = listOf(linkedMapOf("id" to "dsh", "scope" to "application"))

    private fun digest(bytes: ByteArray): String =
        BackupArchive.hex(BackupArchive.sha().digest(bytes))

    private fun summary(
        roots: List<Map<String, String>> = defaultRoots,
        integrity: String = "QUIESCENT",
    ): Map<String, Any> =
        linkedMapOf(
            "operation" to "EXPORT",
            "integrity" to integrity,
            "createdAt" to 1_700_000_000_000L,
            "appVersion" to "dshfusion-0.1.0",
            "runtime" to "debian-trixie",
            "dataFormat" to "dsh-v3",
            "sensitivePolicy" to "UNENCRYPTED",
            "plugins" to emptyMap<String, Any>(),
            "roots" to roots,
        )

    private fun directory(root: String, path: String, scope: String) =
        BackupArchive.Record.of(root, path, "DIRECTORY", scope, 0, "")

    private fun file(root: String, path: String, scope: String, bytes: ByteArray) =
        BackupArchive.Record.of(root, path, "FILE", scope, bytes.size.toLong(), digest(bytes))

    private fun unreadable(root: String, path: String, scope: String) =
        BackupArchive.Record.of(root, path, "UNREADABLE", scope, 0, "")

    /**
     * 容器魔数：**取自真实写入器**，而不是在测试里重抄一遍字面量。
     *
     * 第一版手抄成 `"DSHDATA"`（7 字节，实际是 8 字符的 `DSHADATA`），
     * CI 立刻报 `hasMagic` 失败，连带 5 条 forge 用例全部因为夹具坏掉而红。
     * 这里改成从 [twoScopeArchive] 的前 8 字节取 —— 测试与容器对魔数不可能再各说各话。
     */
    private val magic: ByteArray by lazy { twoScopeArchive().copyOf(V5Archive.MAGIC_SIZE) }

    /** 走真实写入器：证明 DSHFusion 这边写得出来一份容器。 */
    private fun archive(
        entries: List<Pair<BackupArchive.Record, ByteArray?>>,
        roots: List<Map<String, String>> = defaultRoots,
        integrity: String = "QUIESCENT",
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = BackupArchive.Writer(out, BackupControl(null))
        for ((record, payload) in entries) {
            writer.add(record, payload?.let { ByteArrayInputStream(it) })
        }
        writer.finish(summary(roots, integrity))
        return out.toByteArray()
    }

    /**
     * 手写组装容器，模拟一个**绕过写入器校验**的生产者。
     *
     * 布局与 `BackupArchive.Writer` 完全一致（8 字节魔数 + int 5 + 逐条
     * 「元数据长度/元数据/载荷长度/载荷」 + int -1 + 清单长度/清单 + 全文摘要），
     * 但不做任何一致性自查；manifest 里的 `entries` / `bytes` 仍如实填写，
     * 以便把「假完整」与「范围夹带」隔离成唯一变量。
     */
    private fun forge(
        entries: List<Pair<BackupArchive.Record, ByteArray?>>,
        roots: List<Map<String, String>> = defaultRoots,
        integrity: String = "QUIESCENT",
        declaredEntries: Long? = null,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        out.write(magic)
        out.writeInt(5)
        var count = 0L
        var bytes = 0L
        for ((record, payload) in entries) {
            val metadata = BackupJson.write(record.metadata, BackupLimits.RECORD)
            out.writeInt(metadata.size)
            out.write(metadata)
            out.writeLong(record.size)
            payload?.let { out.write(it) }
            count++
            bytes += record.size
        }
        out.writeInt(-1)
        val manifest = LinkedHashMap<String, Any>(summary(roots, integrity))
        manifest["formatVersion"] = 5L
        manifest["entries"] = declaredEntries ?: count
        manifest["bytes"] = bytes
        val encoded = BackupJson.write(manifest, BackupLimits.MANIFEST)
        out.writeInt(encoded.size)
        out.write(encoded)
        out.flush()
        out.write(BackupArchive.sha().digest(body.toByteArray()))
        out.flush()
        return body.toByteArray()
    }

    /** 两个范围、三个条目的正常归档。 */
    private fun twoScopeArchive(integrity: String = "QUIESCENT"): ByteArray =
        archive(
            listOf(
                directory("dsh", "", "application") to null,
                file("dsh", "sessions/a.jsonl", "sessions", fileA) to fileA,
                file("dsh", "settings/settings.yaml", "settings", fileB) to fileB,
            ),
            integrity = integrity,
        )

    private fun verify(bytes: ByteArray): V5ArchiveManifest =
        V5Archive.verify(ByteArrayInputStream(bytes))

    // ---- 正常路径 ----------------------------------------------------------

    @Test
    fun verifyReportsManifestAndRealPerScopeTotals() {
        val report = verify(twoScopeArchive())

        assertEquals("EXPORT", report.operation)
        assertEquals("QUIESCENT", report.integrity)
        assertEquals("dshfusion-0.1.0", report.appVersion)
        assertEquals("debian-trixie", report.runtime)
        assertEquals("dsh-v3", report.dataFormat)
        assertEquals("UNENCRYPTED", report.sensitivePolicy)
        assertEquals(listOf(V5Root("dsh", "application")), report.roots)

        // 条目数/字节数来自容器逐条对账，不是 manifest 的自述
        assertEquals(3L, report.entries)
        assertEquals((fileA.size + fileB.size).toLong(), report.bytes)

        // 统计来自逐条记录：sessions / settings 都**没有**被 manifest 的 roots 声明，
        // 却真实存在 —— 这正是「不能只读 manifest 就下结论」的证据
        assertEquals(V5ScopeUsage(1L, 0L), report.usage["application"])
        assertEquals(V5ScopeUsage(1L, fileA.size.toLong()), report.usage["sessions"])
        assertEquals(V5ScopeUsage(1L, fileB.size.toLong()), report.usage["settings"])
        assertFalse(report.isPartial)
    }

    @Test
    fun verifyAcceptsAnArchiveBuiltByTheRealWriter() {
        // 正向对照：证明 Writer 与读取侧在我们这里对得上。
        // 若这条红了，下面几条负面用例的结论就不可信。
        assertEquals(3L, verify(twoScopeArchive()).entries)
    }

    @Test
    fun verifyMarksPartialArchivesAsPartial() {
        val bytes =
            archive(
                listOf(
                    directory("dsh", "", "application") to null,
                    unreadable("dsh", "sessions/locked.jsonl", "application") to null,
                ),
                integrity = "PARTIAL",
            )
        assertTrue(verify(bytes).isPartial)
    }

    @Test
    fun hasMagicAcceptsOnlyTheV5Header() {
        val real = twoScopeArchive()
        assertTrue(V5Archive.hasMagic(real.copyOf(V5Archive.MAGIC_SIZE)))

        // 首字节与**末字节**被改都必须 false —— 只比前缀会漏掉末字节。
        // 第一版把魔数抄成 7 字节的 "DSHDATA"，正是这条（差在第 8 字节）该抓到它。
        fun flipped(at: Int) =
            real.copyOf(V5Archive.MAGIC_SIZE).also { it[at] = (it[at].toInt() xor 1).toByte() }

        assertFalse(V5Archive.hasMagic(flipped(0)))
        assertFalse(V5Archive.hasMagic(flipped(V5Archive.MAGIC_SIZE - 1)))
        assertFalse(V5Archive.hasMagic(real.copyOf(V5Archive.MAGIC_SIZE - 1)))
        assertFalse(V5Archive.hasMagic(ByteArray(0)))
    }

    // ---- forge 的正向对照 --------------------------------------------------

    @Test
    fun forgeProducesAContainerTheReaderAccepts() {
        // 这条是上面所有 forge 负面用例的地基：如果手写组装本身不合规，
        // 那些用例就会因为「夹具坏了」而红，而不是因为读取侧挡住了攻击。
        val report = verify(forgeOf())
        assertEquals(3L, report.entries)
        assertEquals((fileA.size + fileB.size).toLong(), report.bytes)
        assertEquals(V5ScopeUsage(1L, fileA.size.toLong()), report.usage["sessions"])
    }

    private fun forgeOf(declaredEntries: Long? = null): ByteArray =
        forge(
            listOf(
                directory("dsh", "", "application") to null,
                file("dsh", "sessions/a.jsonl", "sessions", fileA) to fileA,
                file("dsh", "settings/settings.yaml", "settings", fileB) to fileB,
            ),
            declaredEntries = declaredEntries,
        )

    // ---- 负面路径：错误码必须具体 ------------------------------------------

    @Test
    fun corruptedPayloadIsRejectedAsChecksumFailure() {
        val bytes = twoScopeArchive()
        val at = indexOf(bytes, fileA)
        assertTrue("载荷应原样出现在归档里，否则这个用例定位不到它", at >= 0)
        bytes[at] = (bytes[at].toInt() xor 1).toByte()

        val error = assertThrows(IOException::class.java) { verify(bytes) }
        assertEquals("PAYLOAD_CHECKSUM", error.message)
    }

    @Test
    fun trailingBytesAfterTheFooterAreRejected() {
        val bytes = twoScopeArchive()
        val extended = bytes.copyOf(bytes.size + 1)

        val error = assertThrows(IOException::class.java) { verify(extended) }
        assertEquals("ARCHIVE_FOOTER", error.message)
    }

    @Test
    fun truncatedArchiveIsRejected() {
        val bytes = twoScopeArchive()
        assertThrows(IOException::class.java) { verify(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IOException::class.java) { verify(bytes.copyOf(bytes.size / 2)) }
        assertThrows(IOException::class.java) { verify(ByteArray(0)) }
    }

    @Test
    fun archiveClaimingFullWhileCarryingMissingMembersIsRejected() {
        // writer 自己会拒绝这种归档，所以只能手写组装来喂给读取侧
        val bytes =
            forge(
                listOf(
                    directory("dsh", "", "application") to null,
                    unreadable("dsh", "sessions/locked.jsonl", "application") to null,
                ),
                integrity = "QUIESCENT",
            )

        val error = assertThrows(IOException::class.java) { verify(bytes) }
        assertEquals("FALSE_COMPLETE", error.message)
    }

    @Test
    fun rootDeclaringOneScopeCannotCarryAnother() {
        val bytes =
            forge(
                listOf(
                    directory("dsh", "", "sessions") to null,
                    file("dsh", "settings/settings.yaml", "settings", fileB) to fileB,
                ),
                roots = listOf(linkedMapOf("id" to "dsh", "scope" to "sessions")),
            )

        val error = assertThrows(IOException::class.java) { verify(bytes) }
        assertEquals("ROOT_SCOPE_MISMATCH", error.message)
    }

    @Test
    fun manifestThatUnderReportsItsOwnEntriesIsRejected() {
        // 归档里实际有 3 条记录，manifest 却只声称 2 条 —— 必须被逐条对账挡住，
        // 否则「少报」就能让恢复流程漏掉真实存在的数据。
        val bytes = forgeOf(declaredEntries = 2L)

        val error = assertThrows(IOException::class.java) { verify(bytes) }
        assertEquals("MANIFEST_PAYLOAD_MISMATCH", error.message)
    }

    @Test
    fun archiveWithNonV5VersionIsRejected() {
        val bytes = twoScopeArchive()
        bytes[8] = 0x04 // formatVersion int 紧跟在 8 字节魔数之后

        val error = assertThrows(IOException::class.java) { verify(bytes) }
        assertEquals("ARCHIVE_VERSION", error.message)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        for (start in 0..haystack.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return start
        }
        return -1
    }
}
