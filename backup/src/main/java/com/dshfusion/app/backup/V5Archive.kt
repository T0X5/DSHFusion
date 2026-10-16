package com.dshfusion.app.backup

import com.deepseekharness.app.backup.BackupArchive
import com.deepseekharness.app.backup.BackupControl
import com.deepseekharness.app.backup.BackupJson
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 归档声明的一个逻辑根。
 *
 * `id` 是**逻辑**根名（小写字母开头、≤80 字符），不是本机路径 ——
 * 逻辑根到物理落点的映射属于阶段 2.4，本阶段刻意不做。
 */
data class V5Root(val id: String, val scope: String)

/** 某个范围在归档里实际占用的条目数与载荷字节数。 */
data class V5ScopeUsage(val entries: Long, val bytes: Long)

/**
 * 一次 v5 归档预检的结果。
 *
 * 这是**预检**而不是**恢复**。能构造出这个对象，意味着归档满足：
 * 容器魔数与版本正确、每条记录的元数据格式合法、条目数/字节数与 manifest
 * 逐条对账、每个 FILE 的载荷 SHA-256 与记录声明一致、路径无重复无类型冲突、
 * 声明的范围与记录实际范围双向一致、文件尾 SHA-256 覆盖全文且之后无多余字节。
 *
 * 它**不**代表这些路径在本机写得进去，也**不**代表归档里的东西应该被恢复。
 */
data class V5ArchiveManifest(
    val operation: String,
    val integrity: String,
    val createdAt: String,
    val appVersion: String,
    val runtime: String,
    val dataFormat: String,
    val sensitivePolicy: String,
    val entries: Long,
    val bytes: Long,
    val roots: List<V5Root>,
    /** 按 `scope` 聚合的**实际**记录统计（来自逐条记录，不是 manifest 的自述）。 */
    val usage: Map<String, V5ScopeUsage>,
) {
    /**
     * 归档自己承认存在「读不到的成员」（MISSING / UNREADABLE 记录）。
     *
     * 容器层强制了：有缺失项就**不允许**声明 `QUIESCENT` / `EXTERNAL_CHECKED`
     * （否则报 `FALSE_COMPLETE`）。所以这个值可以采信，不是自述。
     */
    val isPartial: Boolean
        get() = integrity != "QUIESCENT" && integrity != "EXTERNAL_CHECKED"
}

/**
 * v5 归档（`DSHADATA` 容器）在 DSHFusion 侧的入口。
 *
 * 容器格式本身是 DSHA 的成熟实现，逐字节原样放在
 * `com.deepseekharness.app.backup`（MIT，见本模块 README 与
 * `governance/dsha-imports.json`）。这里只做两件事：
 * 把「这是不是 v5 归档」与「这份归档能不能用」暴露成类型安全的 API，
 * 并把容器返回的裸 `Map` 收成数据类。
 *
 * **不在这里做任何落盘决策。** 预检全程只读，一个字节都不写目标位置。
 */
object V5Archive {

    /** `DSHADATA` 魔数的长度；判断文件头是否为未加密的 v5 归档时用。 */
    const val MAGIC_SIZE = 8

    /** 无进度的默认控制器：容器把 null Progress 当作 no-op。 */
    private val QUIET = BackupControl(null)

    /** 只认 `DSHADATA`；长度不足或首字节被改都返回 false。 */
    fun hasMagic(header: ByteArray): Boolean = BackupArchive.hasMagic(header)

    /**
     * 只读预检：从头到尾核验一份 v5 归档，并汇总各范围的实际占用。
     *
     * 容器收到的 visitor 的 `payload` **恒返回 null** —— 这是有意的，
     * 容器据此走「只校验此记录」分支：载荷仍然逐块读、仍然算 SHA-256、
     * 仍然和记录声明对比，但**不写到任何地方**。所以这个方法既能给出
     * 真实的统计，又不会碰目标路径。
     *
     * 任何不完整、被篡改、自相矛盾，或声称完整却含缺失项的归档都会在这里
     * 抛 [IOException]，其 message 即用户可见的原因。
     */
    fun verify(input: InputStream, control: BackupControl = QUIET): V5ArchiveManifest {
        val totals = LinkedHashMap<String, LongArray>()
        val manifest =
            BackupArchive.read(
                input,
                object : BackupArchive.Visitor {
                    override fun payload(ordinal: Int, record: BackupArchive.Record): OutputStream? =
                        null

                    override fun verified(ordinal: Int, record: BackupArchive.Record) {
                        val slot = totals.getOrPut(record.scope) { LongArray(2) }
                        slot[0] += 1
                        slot[1] += record.size
                    }
                },
                control,
            )
        return parse(
            manifest,
            totals.mapValues { (_, slot) -> V5ScopeUsage(slot[0], slot[1]) },
        )
    }

    /**
     * 把容器返回的 manifest 收成数据类。
     *
     * 严格程度**刻意与容器对齐**：`entries` / `bytes` / `operation` /
     * `integrity` / `roots` 的 `id` 与 `scope` 由容器强制校验，这里照样
     * 严格取；而 `createdAt` / `appVersion` / `runtime` / `dataFormat` /
     * `sensitivePolicy` 容器只要求**键存在**、不强制类型，所以这里也按
     * 文本宽松读取 —— 不能比容器更严，否则会拒掉容器自己接受的归档。
     */
    private fun parse(
        manifest: Map<String, Any?>,
        usage: Map<String, V5ScopeUsage>,
    ): V5ArchiveManifest {
        val declared = manifest["roots"] as? List<Any?> ?: throw IOException("ROOTS_FORMAT")
        val roots =
            declared.map { row ->
                @Suppress("UNCHECKED_CAST")
                val root = row as? Map<String, Any?> ?: throw IOException("ROOTS_FORMAT")
                V5Root(BackupJson.string(root, "id"), BackupJson.string(root, "scope"))
            }
        return V5ArchiveManifest(
            operation = BackupJson.string(manifest, "operation"),
            integrity = BackupJson.string(manifest, "integrity"),
            createdAt = text(manifest["createdAt"]),
            appVersion = text(manifest["appVersion"]),
            runtime = text(manifest["runtime"]),
            dataFormat = text(manifest["dataFormat"]),
            sensitivePolicy = text(manifest["sensitivePolicy"]),
            entries = BackupJson.number(manifest, "entries"),
            bytes = BackupJson.number(manifest, "bytes"),
            roots = roots,
            usage = usage,
        )
    }

    /** 仅用于展示的来源字段：容器只要求键存在，这里就不假设类型。 */
    private fun text(value: Any?): String = value?.toString().orEmpty()
}
