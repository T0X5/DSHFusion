package com.dshfusion.app.backup

import com.deepseekharness.app.backup.CredentialProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * 凭据投影**拒绝得对不对**：钉住每一种坏输入抛出的**具体错误码**。
 *
 * ## 为什么需要这一条，而上游的测试不够
 *
 * 上游 `CredentialProjectionTest` 对 5 种坏输入只断言
 * `failure.getMessage().startsWith("CREDENTIAL_PROJECTION_")`。
 * 那只能证明「被拒绝了」，**证明不了「因为正确的原因被拒绝」**——
 * 一份合法文件因为无关的 bug 抛 `_FAILED`，那条断言照样通过。
 * 这正是本仓库反复记录的那类假信号（见 docs/INCIDENTS.md 第 17 条同族）。
 *
 * ## 这些码是**实测**的，不是读源码推的
 *
 * 我按源码读过一遍，推出来的结果是 `anchor-alias → _ALIAS`、
 * `explicit-tag → _TAG`、`deep-nesting → _LIMIT`。**三个全错**：
 * SnakeYAML 在自己的 `compose()` 里就先抛了，被 `portable()` 兜成 `_FAILED`。
 * 所以下面每一个期望值都来自 `tools/run-backup-jvm-tests.sh` 能跑的那次真实执行。
 *
 * 结论：`CredentialProjection.check()` 里的 `_ALIAS` / `_TAG` 分支，
 * 对这几类输入**实际不可达**。这条测试如实钉住**实际行为**，
 * 而不是钉住「源码看起来应该怎样」。
 */
class CredentialProjectionCodesTest {

    private companion object {
        /** 一个必须**永远不出现在任何诊断里**的假密钥。 */
        const val LOCAL = "machine-local-secret-value"

        fun portable(text: String): ByteArray =
            CredentialProjection.portable(text.toByteArray(StandardCharsets.UTF_8))
    }

    /** 跑一次，返回抛出的错误码；没抛就返回 null（那种情况测试会红）。 */
    private fun codeOf(text: String): String? = try {
        portable(text)
        null
    } catch (e: IOException) {
        e.message
    }

    private fun assertCode(expected: String, name: String, text: String) {
        val actual = codeOf(text)
        assertEquals("$name 的错误码不对（拒绝的理由与预期不符）", expected, actual)
    }

    @Test
    fun duplicateKeysAreRejectedAsDuplicatesNotAsSomeOtherFailure() {
        assertCode(
            "CREDENTIAL_PROJECTION_DUPLICATE",
            "重复键",
            "version: 1\nrecords: {provider/main: {kind: api-key, key: '$LOCAL', key: other}}\n",
        )
    }

    @Test
    fun nonFiniteNumbersAreRejectedAsNumbers() {
        assertCode("CREDENTIAL_PROJECTION_NUMBER", "payload: .inf",
            "version: 1\nrecords: {provider/main: {kind: grant, payload: .inf}}\n")
        assertCode("CREDENTIAL_PROJECTION_NUMBER", "payload: .nan",
            "version: 1\nrecords: {provider/main: {kind: grant, payload: .nan}}\n")
    }

    @Test
    fun anchorsTagsAndDeepNestingSurfaceAsFailedBecauseTheParserThrowsFirst() {
        // 这三条**实测**是 _FAILED。写 _ALIAS / _TAG / _LIMIT 会在 CI 上红 ——
        // 而且红的是断言，不是代码。
        assertCode("CREDENTIAL_PROJECTION_FAILED", "锚点 + 别名",
            "version: 1\nrecords: &record {provider/main: {kind: grant, payload: '$LOCAL'}}\n" +
                "refs: *record\n")
        assertCode("CREDENTIAL_PROJECTION_FAILED", "显式标签",
            "version: 1\nrecords: {provider/main: {kind: grant, payload: !!java.lang.String '$LOCAL'}}\n")
        assertCode("CREDENTIAL_PROJECTION_FAILED", "40 层嵌套",
            "version: 1\nrecords: {provider/main: {kind: grant, payload: " +
                "[".repeat(40) + "null" + "]".repeat(40) + "}}\n")
    }

    @Test
    fun schemaViolationsAreRejectedAsSchemaOrFields() {
        assertCode("CREDENTIAL_PROJECTION_SCHEMA", "YAML 版本指令",
            "%YAML 1.1\n---\nversion: 1\nrecords: {}\n")
        assertCode("CREDENTIAL_PROJECTION_SCHEMA", "version: 2",
            "version: 2\nrecords: {}\n")
        assertCode("CREDENTIAL_PROJECTION_SCHEMA", "记录键不是 <scope>/<id>",
            "version: 1\nrecords: {ProviderMain: {kind: grant, payload: {}}}\n")
        assertCode("CREDENTIAL_PROJECTION_SCHEMA", "未知的 kind",
            "version: 1\nrecords: {provider/main: {kind: other, payload: {}}}\n")
        assertCode("CREDENTIAL_PROJECTION_FIELDS", "顶层多出一个键",
            "version: 1\nrecords: {}\nextra: x\n")
        assertCode("CREDENTIAL_PROJECTION_FIELDS", "记录里多出一个字段",
            "version: 1\nrecords: {provider/main: {kind: api-key, key: k, extra: x}}\n")
        assertCode("CREDENTIAL_PROJECTION_FORMAT", "根本不是映射",
            "- a\n- b\n")
    }

    @Test
    fun byteLevelFailuresAreRejectedBeforeAnyParsing() {
        val overLimit = try {
            CredentialProjection.portable(ByteArray(CredentialProjection.LIMIT + 1))
            null
        } catch (e: IOException) {
            e.message
        }
        assertEquals("超过 LIMIT 应报 LIMIT（而不是先去做别的）",
            "CREDENTIAL_PROJECTION_LIMIT", overLimit)

        val badUtf8 = try {
            CredentialProjection.portable(byteArrayOf(0xFF.toByte()))
            null
        } catch (e: IOException) {
            e.message
        }
        assertEquals("非法 UTF-8 应报 ENCODING", "CREDENTIAL_PROJECTION_ENCODING", badUtf8)
    }

    /** 每一条诊断都不许把密钥带出来 —— 上游测过，这里再钉一次，因为这是整件事的意义。 */
    @Test
    fun noFailureDiagnosticEverCarriesTheSecret() {
        val bad = listOf(
            "version: 1\nrecords: {provider/main: {kind: api-key, key: '$LOCAL', key: other}}\n",
            "version: 1\nrecords: &record {provider/main: {kind: grant, payload: '$LOCAL'}}\nrefs: *record\n",
            "version: 1\nrecords: {provider/main: {kind: grant, payload: !!java.lang.String '$LOCAL'}}\n",
            "version: 1\nrecords: {provider/main: {kind: grant, payload: .inf}} # $LOCAL\n",
        )
        for (text in bad) {
            val failure = try {
                portable(text)
                null
            } catch (e: IOException) {
                e
            }
            assertTrue("这些输入都该被拒绝", failure != null)
            assertFalse(
                "诊断里出现了密钥：${failure!!.message}",
                failure.toString().contains(LOCAL),
            )
            assertEquals("不得把解析器的原始异常当 cause 带出来", null, failure.cause)
        }
    }

    /**
     * 正常路径：机器本地记录被删掉，provider 凭据**一个字节都不动**。
     * 这两件事必须同时成立 —— 只测「删掉了」会漏掉「顺手改坏了 provider」。
     */
    @Test
    fun machineRecordsGoAndProviderCredentialsSurviveUntouched() {
        val text = "version: 1\n" +
            "records: {provider/main: {kind: api-key, key: keep-me}, " +
            "client-connection/browser-session: {kind: grant, payload: {secret: $LOCAL}}}\n"
        val out = String(portable(text), StandardCharsets.UTF_8)
        assertFalse("机器本地记录（client-connection/）必须被删掉", out.contains(LOCAL))
        assertFalse("连键名都不该留下", out.contains("client-connection"))
        assertTrue("provider 凭据必须还在", out.contains("keep-me"))
        assertTrue("version 必须还在（否则 DSH 读不了）", out.contains("version: 1"))
    }

    /** 没有机器记录时**逐字节不变**：不因「重新序列化」而抖动。 */
    @Test
    fun aSourceWithoutMachineRecordsComesBackByteIdentical() {
        val text = "version: 1\nrecords: {provider/main: {kind: api-key, key: keep-me}}\n"
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        assertTrue(
            "没有机器记录时必须原样返回（重新序列化会让每次备份都产生无谓的差异）",
            bytes.contentEquals(CredentialProjection.portable(bytes)),
        )
    }
}
