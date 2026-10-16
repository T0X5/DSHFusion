package com.dshbox.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.backup.DshAutomaticBackupRetention
import com.dshbox.app.service.AutomaticBackupJobService
import com.dshbox.app.service.AutomaticBackupSettingsStore
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「自动备份」这一节。
 *
 * ## 界面只做两件事：写设置、显示上次结果
 *
 * **它不做任何到期判定。** 「该不该备份」是 `AutomaticBackupPolicy` 的事，
 * 那个类有 4 条本机测试（含 DST 与时钟回拨）；在这里重算一遍就会出现
 * 「界面说该备份了、任务说不该」而没人知道哪边对。
 *
 * ## 「立即备份一次」与心跳走同一条路径
 *
 * 都调 `AutomaticBackupJobService.runOnceFor`。两条路各自实现一遍的话，
 * 「手动能跑、自动不能跑」这类差异**不会有任何东西报出来**。
 */
@Composable
internal fun AutomaticBackupSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(AutomaticBackupSettingsStore.enabled(context)) }
    var mode by remember { mutableStateOf(AutomaticBackupSettingsStore.mode(context)) }
    var intervalHours by remember { mutableStateOf(AutomaticBackupSettingsStore.intervalHours(context)) }
    var last by remember { mutableStateOf(AutomaticBackupSettingsStore.last(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    /** 改设置之后重新登记一次心跳 —— 让新的判定在下个周期就生效。 */
    fun persist() {
        AutomaticBackupSettingsStore.setEnabled(context, enabled)
        AutomaticBackupSettingsStore.setMode(context, mode)
        AutomaticBackupSettingsStore.setIntervalHours(context, intervalHours)
        AutomaticBackupJobService.schedule(context)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.auto_section),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.auto_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    persist()
                },
            )
            Text(stringResource(R.string.auto_enable), style = MaterialTheme.typography.bodyLarge)
        }

        if (enabled) {
            listOf(
                    AutomaticBackupSettingsStore.MODE_INTERVAL to R.string.auto_mode_interval,
                    AutomaticBackupSettingsStore.MODE_DAILY to R.string.auto_mode_daily,
                    AutomaticBackupSettingsStore.MODE_STOP to R.string.auto_mode_stop,
                )
                .forEach { (value, label) ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = mode == value,
                                    onClick = {
                                        mode = value
                                        persist()
                                    },
                                )
                                .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = mode == value,
                            onClick = {
                                mode = value
                                persist()
                            },
                        )
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }

            if (mode == AutomaticBackupSettingsStore.MODE_INTERVAL) {
                // 用几个预设档而不是滑块：`AutomaticBackupPolicy` 会把值夹到 1–168，
                // 而一个能滑到 500 的滑块会让用户以为那是有效的。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 6, 12, 24, 72).forEach { hours ->
                        OutlinedButton(
                            onClick = {
                                intervalHours = hours
                                persist()
                            },
                        ) {
                            Text(
                                stringResource(R.string.auto_interval_value, hours),
                                style =
                                    if (hours == intervalHours) MaterialTheme.typography.labelLarge
                                    else MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            Text(
                text =
                    if (last == 0L) stringResource(R.string.auto_last_never)
                    else
                        stringResource(
                            R.string.auto_last,
                            DateFormat.getDateTimeInstance().format(Date(last)),
                        ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.auto_keep_note, DshAutomaticBackupRetention.KEEP),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    busy = true
                    message = null
                    coroutineScope.launch {
                        val userData = (context.applicationContext as DshApp).container.sandboxConfig.userDataDir
                        val outcome =
                            withContext(Dispatchers.IO) {
                                AutomaticBackupJobService.runOnceFor(context, userData)
                            }
                        if (outcome.ran && outcome.reason.isEmpty()) {
                            // 与心跳同一条规则：**只有成功才推进 `last`**。
                            AutomaticBackupSettingsStore.setLast(context, System.currentTimeMillis())
                            last = AutomaticBackupSettingsStore.last(context)
                        }
                        message = outcome.describe()
                        busy = false
                    }
                },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.auto_run_now))
            }
            if (busy) CircularProgressIndicator()
        }

        message?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
