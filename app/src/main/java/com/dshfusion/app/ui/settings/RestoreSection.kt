package com.dshfusion.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.dshfusion.app.R
import com.dshfusion.app.backup.DshBackupExportBridge
import com.dshfusion.app.backup.DshBackupScope
import com.dshfusion.app.backup.DshRestoreOrchestrator
import com.deepseekharness.app.backup.AndroidBackupFileSystem
import com.deepseekharness.app.backup.BackupControl
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「从归档恢复」这一节。
 *
 * ## 为什么与导出放在同一屏，而不是各开一屏
 *
 * 因为它们是同一件事的两个方向，而**用户要在这两者之间做判断**
 * （「我刚导出的是哪一份？」「恢复它会覆盖什么？」）。分成两个入口只会让人
 * 在两个界面之间来回找不到自己在干什么。
 *
 * ## 为什么是「预检 → 确认 → 恢复」而不是一个按钮
 *
 * 一口气做完的「恢复」在界面上只有一个按钮，那是把确认的责任推给了运气：
 * 上游的 `prepare` 会在换入前重验目标（`TARGET_CHANGED`），
 * 但**「目标没变」不等于「用户知道目标里有什么、即将被换掉什么」**。
 * 所以预检必须把「会覆盖哪些根」摆到用户眼前。
 *
 * ## 归档为什么要先落到本机
 *
 * `AndroidBackupFileSystem` 收的是 `File`，而系统文件选择器给的是 `content://`。
 * 所以选中的内容先原样拷进任务区，再让预检去读那一份。**拷贝是原样字节**，
 * 不做任何解释 —— 解归档是 `NativeRestorePlan` 的事。
 */
@Composable
internal fun RestoreSection(
    scope: DshBackupScope,
    userDataDir: File,
    cacheDir: File,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 预检与提交必须用**同一个**编排实例 —— 它带着状态（候选、重验摘要）。
    // 用 `remember` 而不是每次 new：后者会让「确认」永远落在 `COMMIT_WITHOUT_PREPARE` 上。
    var run by remember { mutableStateOf<DshRestoreOrchestrator?>(null) }
    var report by remember { mutableStateOf<DshRestoreOrchestrator.Report?>(null) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<RestoreOutcome?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var pickedName by remember { mutableStateOf<String?>(null) }

    // 每次换范围都要重新检：`selectedScopes` 是安全属性，不能沿用上一份。
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            run = null
            report = null
            outcome = null
            pickedName = null
            busy = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) { precheck(context, uri, scope, userDataDir, cacheDir) }
                when (result) {
                    is PrecheckOutcome.Ready -> {
                        run = result.run
                        report = result.report
                        pickedName = result.displayName
                    }
                    is PrecheckOutcome.Failed -> outcome = RestoreOutcome.Failed(result.code)
                }
                busy = false
            }
        }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.restore_section),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.restore_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        pickedName?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.restore_pick))
            }
            Button(
                onClick = { confirming = true },
                enabled = !busy && report != null && run != null,
            ) {
                Text(stringResource(R.string.restore_commit_button))
            }
        }

        report?.let { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text =
                        stringResource(
                            R.string.restore_report,
                            shown.roots.size,
                            shown.overwrites.size,
                            shown.conflicts.toInt(),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // **「会覆盖什么」必须摆出来。** 这是用户唯一能据以判断的那一栏：
                // 别的数字（几个根、几条记录）都不告诉他「我会失去什么」。
                if (shown.overwrites.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.restore_overwrites, shown.overwrites.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (shown.warnings.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.restore_warnings, shown.warnings.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.restore_running))
            }
        }

        outcome?.let { result ->
            Text(
                text = result.message(),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (result.ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
            )
        }
    }

    if (confirming && report != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.restore_confirm_message,
                        report?.roots?.joinToString(", ") { it.destination }.orEmpty(),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        val active = run ?: return@TextButton
                        busy = true
                        outcome = null
                        coroutineScope.launch {
                            outcome = withContext(Dispatchers.IO) { commit(active) }
                            busy = false
                            // 提交之后这次运行就结束了。清掉它，
                            // 免得界面以为「还能再点一次」—— 编排自己会抛 `ALREADY_FINALIZED`，
                            // 但那是一个错误路径，不该让用户走到。
                            run = null
                            report = null
                        }
                    },
                ) {
                    Text(stringResource(R.string.restore_commit_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.backup_back))
                }
            },
        )
    }
}

/** 一次恢复的结果。与 [ExportOutcome] 分开：它俩的字段不一样，硬合并会让两边都多一堆空值。 */
internal sealed interface RestoreOutcome {
    val ok: Boolean

    /** 成功。[count] 是恢复了几个根。 */
    data class Done(val count: Int) : RestoreOutcome {
        override val ok: Boolean get() = true
    }

    /** 失败。[code] 是上游或编排抛出的固定错误码，原样显示。 */
    data class Failed(val code: String) : RestoreOutcome {
        override val ok: Boolean get() = false
    }
}

@Composable
private fun RestoreOutcome.message(): String =
    when (this) {
        is RestoreOutcome.Done -> stringResource(R.string.restore_done, count.toString())
        is RestoreOutcome.Failed -> stringResource(R.string.restore_failed, code)
    }

/** 预检的结果。 */
internal sealed interface PrecheckOutcome {
    data class Ready(
        val run: DshRestoreOrchestrator,
        val report: DshRestoreOrchestrator.Report,
        val displayName: String,
    ) : PrecheckOutcome

    data class Failed(val code: String) : PrecheckOutcome
}

/**
 * 把选中的 `content://` 原样落到任务区，再让编排预检。
 *
 * **预检本身不碰用户数据** —— 它只建任务目录、解归档、推导落点、建平行候选。
 * 这一条由 `DshRestoreOrchestratorTest.inspectAndPrepareDoNotTouchExistingData`
 * 在本机用逐文件摘要证明。
 */
internal fun precheck(
    context: android.content.Context,
    uri: Uri,
    scope: DshBackupScope,
    userDataDir: File,
    cacheDir: File,
): PrecheckOutcome {
    val taskRoot = File(cacheDir, RESTORE_TASK_ROOT)
    var staged: File? = null
    return try {
        if (!taskRoot.isDirectory() && !taskRoot.mkdirs())
            return PrecheckOutcome.Failed("TASK_DIRECTORY")
        staged = File(taskRoot, "picked-" + UUID.randomUUID() + ".dshbak")
        context.contentResolver.openInputStream(uri)?.use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        } ?: return PrecheckOutcome.Failed("ARCHIVE_UNREADABLE")

        val displayName = queryDisplayName(context, uri) ?: staged.name
        val run = DshRestoreOrchestrator()
        val report =
            run.inspect(
                AndroidBackupFileSystem(),
                staged,
                DshBackupExportBridge.restoreMembers(scope),
                DshBackupExportBridge.selectedScopes(scope),
                scope.id,
                userDataDir,
                taskRoot,
                BackupControl(null),
            )
        PrecheckOutcome.Ready(run, report, displayName)
    } catch (error: Exception) {
        PrecheckOutcome.Failed(error.message ?: error.javaClass.simpleName)
    } finally {
        // ⚠️ **必须在 `inspect` 之后才删。** `inspect` 会把归档读一遍
        // （清单 + 载荷落进它自己的任务目录）；删早了它会读到一个空文件，
        // 而那个错看起来会像「归档坏了」。这条注释钉住顺序，因为搬动这两行
        // 不会让任何检查变红 —— 只会让预检在真机上神秘地失败。
        staged?.delete()
    }
}

/** 尽力取一个给人看的文件名；取不到就返回 null（调用方用暂存名兜底）。 */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
            context.contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }
        .getOrNull()

/** 提交。到这里才会写用户数据。 */
internal fun commit(run: DshRestoreOrchestrator): RestoreOutcome =
    try {
        run.prepare(BackupControl(null))
        run.commit(BackupControl(null))
        RestoreOutcome.Done(run.report()?.roots?.size ?: 0)
    } catch (error: IOException) {
        RestoreOutcome.Failed(error.message ?: error.javaClass.simpleName)
    }

/** 恢复的任务根，落在 `cacheDir` 下 —— 不在 `user-data` 里。 */
private const val RESTORE_TASK_ROOT = "restore-task"
