package com.dshfusion.app.bridge

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

import interlock.relay.core.exec.a11y.RelayAccessibilityService

/**
 * 把骨干的**无障碍服务**接到设备能力层的 `uiDump` 上。
 *
 * ## 为什么这里是一段自己的遍历，而不是复用 `A11yBackend`
 *
 * `A11yBackend` 是**执行引擎**（`suspend`、能力域、`BackendCall`、存储回收器），
 * 它的正确性花在「点击/输入/选择在边缘情况下怎么做对」上。
 * 而这里要的是一次**只读的树遍历**，输出一个字符串。
 *
 * 为了拿一个字符串去构造 `StorageReaper` + `BackendCall` + 协程调度，
 * 会把「读屏」这件事绑到执行引擎的生命周期上 —— 而它本来不需要。
 *
 * **所以界线是：执行动作全部留给骨干；这里只做观察。**
 * 若将来 `uiDump` 需要和 `ui.snapshot` 完全同构，那时再换成调用 `A11yBackend`，
 * 而不是现在为了让代码看起来更「复用」而提前耦合。
 *
 * ## 三条失败路径，都**不假装成功**
 *
 * | 情况 | 抛出的标识 |
 * |---|---|
 * | 无障碍服务没绑定（没开 / 被系统回收） | `A11Y_SERVICE_NOT_BOUND` |
 * | 服务在，但当前没有活动窗口 | `NO_ACTIVE_WINDOW` |
 * | 树里一个节点都没读到 | `EMPTY_TREE` |
 *
 * 三者都会被 [AndroidDeviceClient] 向上抛、由分发器变成 `503 DEVICE_UNAVAILABLE`。
 * **「服务被回收」是常态而不是异常** —— 无障碍服务随时可能被系统收回，
 * 所以「用户开过」不能当成「现在还在」。
 *
 * ## 为什么输出是 ASCII
 *
 * 两个理由，都不是口味问题：
 * ① 它是**协议面**：`[n] "text" clickable center=(x,y) bounds=l,t,r,b` 里的词
 *    是给调用方解析的标识，不是给人读的文案 —— 标识符不该随语言变；
 * ② 本仓库的 i18n 门禁**禁止源码里出现中文字面量**（`tools/i18n_check.sh`），
 *    而它判得对：不是 UI 文案的东西不该长得像 UI 文案。
 */
class RelayScreenDump(
    /** 节点上限：防止一个畸形树把响应撑爆。超出时**明确截断并说明**，不静默丢。 */
    private val maxNodes: Int = 300,
    /** 深度上限：同上。 */
    private val maxDepth: Int = 40,
) : AndroidDeviceClient.ScreenDump {

    override fun dump(): String {
        val service = RelayAccessibilityService.current()
            ?: throw IllegalStateException("A11Y_SERVICE_NOT_BOUND")
        val root = service.rootInActiveWindow
            ?: throw IllegalStateException("NO_ACTIVE_WINDOW")

        val lines = ArrayList<String>()
        val truncated = walk(root, 0, lines)
        if (lines.isEmpty()) {
            throw IllegalStateException("EMPTY_TREE")
        }
        val builder = StringBuilder()
        for (line in lines) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(line)
        }
        if (truncated) {
            builder.append("\ntruncated=true maxNodes=").append(maxNodes)
        }
        return builder.toString()
    }

    /** 深度优先遍历；返回是否因为上限被截断。 */
    private fun walk(node: AccessibilityNodeInfo, depth: Int, out: MutableList<String>): Boolean {
        if (out.size >= maxNodes) return true
        if (depth > maxDepth) return true

        val label = label(node)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        out.add(
            "[" + out.size + "] \"" + label + "\"" +
                (if (node.isClickable) " clickable" else "") +
                " center=(" + centerX + "," + centerY + ")" +
                " bounds=" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom
        )

        var truncated = false
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (walk(child, depth + 1, out)) {
                truncated = true
                break
            }
        }
        return truncated
    }

    /** 优先用可见文字，其次内容描述，再次类名 —— 三者都空就留空，不编造。 */
    private fun label(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return escape(text)
        val description = node.contentDescription?.toString()?.trim()
        if (!description.isNullOrEmpty()) return escape(description)
        val className = node.className?.toString()?.substringAfterLast('.')
        return if (className.isNullOrEmpty()) "" else escape(className)
    }

    /** 换行与引号会把「一行一个节点」的格式破坏掉，而那是调用方唯一的解析依据。 */
    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
}
