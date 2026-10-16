package com.dshbox.app.ui.settings

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.backup.DshBackupExportBridge
import com.dshbox.app.backup.DshBackupExporter
import com.dshbox.app.backup.DshBackupScope
import com.deepseekharness.app.backup.AndroidBackupFileSystem
import com.deepseekharness.app.backup.BackupControl
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.vector.ImageVector
import com.dshbox.app.common.R as CommonR

/**
 * 备份与恢复（阶段 2.6 的第 ④ 段：**导出**）。
 *
 * 恢复入口在下一段，本界面刻意只做导出 —— 一条一次只接一段，
 * 因为「会毁数据的那条路径」和「只读的那条路径」该分开验。
 *
 * ## 三个刻意的选择
 *
 * 1. **产物写进 `cacheDir`，再交给 MediaStore 落到下载目录。**
 *    上游 `HostSnapshot.create` 有一条硬断言：`archive.parentFile` 必须**等于**任务目录，
 *    而任务目录又不许落在任何源根之内。所以产物必须先在一个既在源之外、
 *    又被我们独占的目录里成形，再移出去。`cacheDir` 不落在 `user-data` 之下，符合条件。
 * 2. **走 MediaStore 而不是 `Environment.getExternalStoragePublicDirectory`。**
 *    minSdk 29 起是分区存储，后者要么拿不到写权限、要么需要用户授权整盘；
 *    MediaStore 的 `Downloads` 集合在 API 29+ **不需要任何权限**。
 *    写入用 `IS_PENDING=1` 起头、写完再置 0 —— 这样别的应用在写完之前看不到半个文件。
 * 3. **文件系统用 `AndroidBackupFileSystem`。** 它用 `Os.lstat` + `O_NOFOLLOW`，
 *    打开描述符后还会复查类型/设备/inode（防 TOCTOU）。不用 root、不跟随符号链接。
 */
@Composable
fun BackupScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as DshApp).container
    val coroutineScope = rememberCoroutineScope()

    BackHandler(onBack = onBack)

    var scope by remember { mutableStateOf(DshBackupScope.FULL) }
    var running by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ExportOutcome?>(null) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrow_left),
                        contentDescription = stringResource(R.string.backup_back),
                    )
                }
                Text(
                    text = stringResource(R.string.backup_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Text(
                text = stringResource(R.string.backup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.backup_scope_section),
                style = MaterialTheme.typography.titleSmall,
            )
            DshBackupScope.entries.forEach { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = candidate == scope,
                            enabled = !running,
                            onClick = {
                                scope = candidate
                                outcome = null
                            },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = candidate == scope,
                        enabled = !running,
                        onClick = {
                            scope = candidate
                            outcome = null
                        },
                    )
                    Column {
                        Text(
                            text = stringResource(scopeLabel(candidate)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(scopeDescription(candidate)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    running = true
                    outcome = null
                    coroutineScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runExport(
                                userDataDir = container.sandboxConfig.userDataDir,
                                cacheDir = context.cacheDir,
                                scope = scope,
                                publish = { file, name -> publishToDownloads(context, file, name) },
                            )
                        }
                        outcome = result
                        running = false
                    }
                },
                enabled = !running,
            ) {
                Text(stringResource(R.string.backup_export_button))
            }

            if (running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.backup_export_running))
                }
            }

            outcome?.let { result ->
                Text(
                    text = result.message(context),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (result.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                )
            }

            // 恢复与导出同屏：它们是同一件事的两个方向，而用户要在这两者之间做判断
            // （「我刚导出的是哪一份？」「恢复它会覆盖什么？」）。
            RestoreSection(
                scope = scope,
                userDataDir = container.sandboxConfig.userDataDir,
                cacheDir = context.cacheDir,
            )

            // 自动备份的设置与「立即备份一次」。它与上面两节共用同一个屏：
            // 三件事都是「我的数据现在怎么样」，分三个入口只会让人找不到。
            AutomaticBackupSection()
        }
    }
}

/** 一次导出的结果，界面只认这三种。 */
sealed interface ExportOutcome {
    val ok: Boolean

    /** 成功。[displayName] 是交给 MediaStore 的那个名字。 */
    data class Done(
        val displayName: String,
        /** 可读的导出时刻。文件名里那个 `20261016-030146` 其实**已经是秒级**，
         *  但人看着要一位一位数 —— 所以结果行里另给一个 `2026-10-16 03:01:46`。 */
        val at: String,
        val bytes: Long,
        val skipped: Int,
        val integrity: String,
    ) : ExportOutcome {
        override val ok: Boolean get() = true
    }

    /** 失败。[code] 是上游或我们抛出的错误码 —— 原样显示，不做二次解释。 */
    data class Failed(val code: String) : ExportOutcome {
        override val ok: Boolean get() = false
    }
}

/**
 * 结果的界面文案。
 *
 * 刻意**放在 sealed interface 外面**：`runExport` 是纯函数，它产出的结果
 * 要在没有 Compose 的地方也能构造与断言。把文案做成 Composable 成员，
 * 会让这个类型反过来依赖 Compose —— 而「结果」不该依赖「怎么显示」。
 */
@Composable
private fun ExportOutcome.message(context: android.content.Context): String =
    when (this) {
        is ExportOutcome.Done -> {
            val size = Formatter.formatFileSize(context, bytes)
            val base = stringResource(R.string.backup_export_ok, displayName, at, size, integrity)
            if (skipped == 0) base
            else base + " " + stringResource(R.string.backup_export_skipped, skipped)
        }
        is ExportOutcome.Failed -> stringResource(R.string.backup_export_failed, code)
    }

private fun scopeLabel(scope: DshBackupScope): Int =
    when (scope) {
        DshBackupScope.FULL -> R.string.backup_scope_full
        DshBackupScope.SESSIONS -> R.string.backup_scope_sessions
        DshBackupScope.SETTINGS -> R.string.backup_scope_settings
        DshBackupScope.PLUGINS -> R.string.backup_scope_plugins
    }

private fun scopeDescription(scope: DshBackupScope): Int =
    when (scope) {
        DshBackupScope.FULL -> R.string.backup_scope_full_desc
        DshBackupScope.SESSIONS -> R.string.backup_scope_sessions_desc
        DshBackupScope.SETTINGS -> R.string.backup_scope_settings_desc
        DshBackupScope.PLUGINS -> R.string.backup_scope_plugins_desc
    }

/**
 * 真正干活的那一段。**与 Compose 无关**，所以它的行为可以被单测/仪器测试直接调用。
 *
 * 顺序不能换：
 *   ① 建一个独占的任务目录（在 `cacheDir` 下，**不在** `user-data` 里）；
 *   ② 让导出编排把产物写进那个目录；
 *   ③ 交给 `publish` 落到下载目录；
 *   ④ **无论成败都清掉任务目录** —— 半成品留在 cache 里最坏的情况是占地方，
 *      但更坏的是它看起来像一份可用的备份。
 */
internal fun runExport(
    userDataDir: File,
    cacheDir: File,
    scope: DshBackupScope,
    publish: (file: File, displayName: String) -> Unit,
): ExportOutcome {
    val taskRoot = File(cacheDir, TASK_ROOT_NAME)
    val task = File(taskRoot, UUID.randomUUID().toString())
    // ⚠️ **出料目录必须与任务目录分开。**
    //
    // 真机上第一版把目标传成 `File(task, name)` —— 也就是 `HostSnapshot` 刚写出来的
    // **同一个路径**。于是上游的 `fs.move(archive, target)` 变成「把文件移到它自己」，
    // 而 `AndroidBackupFileSystem.move` 要求目标**不存在**：
    //
    //     if (... || !stat(target).type.equals("MISSING")) throw new IOException("UNSAFE_MOVE");
    //
    // 所以真机上看到的是 `导出失败：UNSAFE_MOVE`。
    //
    // 为什么不能直接「让 export 允许目标等于产物」：上游 `HostSnapshot.create` 有一条硬断言
    // `archive.parentFile == ownedTask`，产物**必须**先落在任务目录里；而 `export` 的契约是
    // 「把产物交到 destination」。两者的交集就是「destination 在任务目录之外」。
    val out = File(File(cacheDir, OUT_ROOT_NAME), task.name)
    return try {
        if (!task.mkdirs() || !out.mkdirs()) return ExportOutcome.Failed("TASK_DIRECTORY")
        val name = DshBackupExportBridge.archiveName(scope, stamp())
        val written =
            DshBackupExporter.export(
                AndroidBackupFileSystem(),
                userDataDir,
                DshBackupExportBridge.members(scope),
                task,
                File(out, name),
                provenance(),
                DshBackupExportBridge.exclusions(),
                false,
                BackupControl(null),
            )
        publish(written.archive, name)
        ExportOutcome.Done(
            displayName = name,
            at = readableNow(),
            bytes = written.bytes,
            skipped = written.skipped.size,
            integrity = written.manifest["integrity"]?.toString().orEmpty(),
        )
    } catch (error: Exception) {
        // 错误码原样向上。上游抛的是固定的机器码（`NO_SELECTED_DATA` /
        // `DESTINATION_EXISTS` / 那几个 `BackupArchive` 码），二次解释只会把
        // 「因为正确的原因被拒绝」和「因为别的原因被拒绝」混成一句。
        ExportOutcome.Failed(error.message ?: error.javaClass.simpleName)
    } finally {
        // 两个目录都要清 —— 少清一个就会在 cache 里积一份几十 MB 的归档。
        if (task.exists()) task.deleteRecursively()
        if (out.exists()) out.deleteRecursively()
    }
}

/**
 * 容器清单里**调用方该给**的那几个字段。
 *
 * `integrity` 不在这里 —— 它由上游按「有没有源校验失败 / 是不是救援 / 有没有外部根」
 * 自己推导。手动塞一个进去会被 `validateSummary` 拒绝，或者更坏：盖掉真实值。
 */
private fun provenance(): Map<String, Any> =
    linkedMapOf(
        "operation" to "EXPORT",
        "createdAt" to System.currentTimeMillis(),
        "appVersion" to APP_VERSION,
        "runtime" to "android",
        "dataFormat" to 5,
        "sensitivePolicy" to "machine-local-excluded",
        "plugins" to emptyList<String>(),
    )

private fun stamp(): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

/**
 * 给人看的导出时刻。
 *
 * 文件名里那个 `20261016-030146` **本来就是秒级**（年月日-时分秒），
 * 但连在一起不好读；结果行里另给一个带分隔符的。
 */
private fun readableNow(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

/** 落在 `cacheDir` 下的任务根。放在这里而不是 `user-data` 里 —— 见 `runExport` 的注释。 */
private const val TASK_ROOT_NAME = "backup-task"

/** 出料根。**必须与任务根分开** —— 见 `runExport` 里那段关于 `UNSAFE_MOVE` 的注释。 */
private const val OUT_ROOT_NAME = "backup-out"

// 与 `app/build.gradle.kts` 的 versionName 应当一致；只写进归档清单供**读回时**参考，
// 不参与任何判定，所以这里不做构建期注入（那会让本文件的编译依赖构建脚本）。
private const val APP_VERSION = "0.1.0"

/**
 * 把产物交给 MediaStore 的下载集合。
 *
 * `IS_PENDING=1` 起头、写完置 0：**在写完之前，别的应用看不到这个文件**。
 * 少了这一步，用户可能在文件只写了一半时就去点它。
 *
 * 失败时把刚插入的条目删掉，不留一个空壳 —— 空壳在下载目录里看起来
 * 像一份备份，而它其实是 0 字节。
 */
private fun publishToDownloads(
    context: android.content.Context,
    file: File,
    displayName: String,
) {
    val resolver = context.contentResolver
    val collection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else MediaStore.Files.getContentUri("external")
    val values =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR,
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
    val uri = resolver.insert(collection, values) ?: throw java.io.IOException("MEDIASTORE_INSERT")
    try {
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: throw java.io.IOException("MEDIASTORE_STREAM")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
    } catch (error: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
}

/** 下载目录里的子目录名。产品名，不翻译。 */
private const val DOWNLOAD_SUBDIR = "DSHFusion"
