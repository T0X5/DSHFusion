package com.dshbox.app.service

import com.dshbox.app.sandbox.DshState

/**
 * 通知栏动作。枚举顺序即显示顺序。
 *
 * **上限 3 个。** Android 把动作栏按个数平分宽度；4 个及以上时文案会被压成
 * 「一个字 + 省略号」—— 真机实测（2026-10-14，zh）显示为「打… / 启… / 重… / 停…」。
 * 所以这一版的修法不只是把字改短，而是**先把个数降下来**。见 DIV-0018。
 */
internal enum class DshNotifyAction { OPEN, START, RESTART, STOP }

/**
 * 按 DSH 状态决定通知栏放哪些动作。纯函数，便于单测。
 *
 * 三条规则：
 * - **打开 / 启动 互斥。** DSH 已经在跑时「启动」是误导；没在跑时「打开」无处可开。
 *   上游把两个都列出来，既占宽度（这正是压缩文案的直接原因）又让人选错。
 * - **运行中才给「重启」。** 没在跑的时候「重启」与「启动」是同一件事。
 * - **「停止」永远在最后。** 它是沙箱卡住时唯一的出路，不能因为状态判断失误而消失 ——
 *   这是 DIV-0014 的教训：一个误报的「就绪」曾让首页按钮永久灰掉、无路可走。
 */
internal fun dshNotifyActions(dshState: DshState): List<DshNotifyAction> {
    val alive = dshState == DshState.READY ||
        dshState == DshState.RUNNING ||
        dshState == DshState.STARTING
    return buildList {
        if (dshState == DshState.READY) add(DshNotifyAction.OPEN)
        if (alive) add(DshNotifyAction.RESTART) else add(DshNotifyAction.START)
        add(DshNotifyAction.STOP)
    }
}
