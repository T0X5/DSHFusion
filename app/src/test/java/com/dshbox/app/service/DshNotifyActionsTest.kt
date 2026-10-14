package com.dshbox.app.service

import com.dshbox.app.sandbox.DshState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知栏动作集合（DIV-0018）。
 *
 * 这些断言钉的不是「实现怎么写」，而是四条**必须成立的性质**：
 * 个数上限、打开/启动互斥、停止永远在场且最后、非就绪不给「打开」。
 * 只要这四条在，具体顺序怎么排都不会退化成真机上那个「打…/启…/重…/停…」。
 */
class DshNotifyActionsTest {

    @Test
    fun readyOffersOpenRestartStop() {
        assertEquals(
            listOf(DshNotifyAction.OPEN, DshNotifyAction.RESTART, DshNotifyAction.STOP),
            dshNotifyActions(DshState.READY),
        )
    }

    @Test
    fun notRunningOffersStartThenStop() {
        for (state in listOf(DshState.UNINITIALIZED, DshState.STOPPED, DshState.ERROR)) {
            assertEquals(
                "没在跑时应该是「启动 + 停止」：$state",
                listOf(DshNotifyAction.START, DshNotifyAction.STOP),
                dshNotifyActions(state),
            )
        }
    }

    @Test
    fun runningWithoutReadyOffersRestartThenStop() {
        for (state in listOf(DshState.STARTING, DshState.RUNNING)) {
            assertEquals(
                "起来了但还没就绪时不该给「打开」：$state",
                listOf(DshNotifyAction.RESTART, DshNotifyAction.STOP),
                dshNotifyActions(state),
            )
        }
    }

    @Test
    fun openAndStartAreMutuallyExclusive() {
        for (state in DshState.entries) {
            val actions = dshNotifyActions(state)
            assertTrue(
                "「打开」与「启动」不该同时出现（它们互斥，且同时出现就是上游那个 bug 的来源）：$state -> $actions",
                !(DshNotifyAction.OPEN in actions && DshNotifyAction.START in actions),
            )
        }
    }

    @Test
    fun neverMoreThanThreeActions() {
        for (state in DshState.entries) {
            val actions = dshNotifyActions(state)
            assertTrue(
                "最多 3 个动作，否则 Android 会把文案压成一个字加省略号：$state -> $actions",
                actions.size <= 3,
            )
        }
    }

    @Test
    fun stopIsAlwaysPresentAndLast() {
        for (state in DshState.entries) {
            val actions = dshNotifyActions(state)
            assertEquals(
                "「停止」必须始终在场且排在最后 —— 它是卡住时唯一的出路：$state -> $actions",
                DshNotifyAction.STOP,
                actions.last(),
            )
        }
    }

    @Test
    fun everyStateProducesAtLeastOneAction() {
        for (state in DshState.entries) {
            assertTrue("任何状态都得有动作可点：$state", dshNotifyActions(state).isNotEmpty())
        }
    }
}
