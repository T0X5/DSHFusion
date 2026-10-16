package com.dshfusion.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 从 DSH 自己的输出里学「本实例在哪个端口」的解析回归（DIV-0014）。
 *
 * 这些用例里的字符串**来自真机/本机实测输出**，不是构造出来的示例：
 *
 * ```
 * dsh web: http://127.0.0.1:42797/?token=8OMeQzE98nRyjCPBhqSu4_sVBhKFQdbq8mCP-9qf5u4
 * ```
 *
 * 这是 app 唯一能知道真实端口的入口。之所以把它做成纯函数并逐个钉住，
 * 是因为这条链路的失效方式很隐蔽：解析不出来时，最"顺手"的兜底就是回退到
 * 默认端口 3080 —— 而那正是 bug 本身（探测到别人的 DSH 却报「就绪」）。
 * 所以下面的用例明确要求**解析失败返回 null，而不是任何默认值**。
 */
class DshWebLaunchParserTest {

    private val realLine =
        "dsh web: http://127.0.0.1:42797/?token=8OMeQzE98nRyjCPBhqSu4_sVBhKFQdbq8mCP-9qf5u4"

    @Test
    fun `解析实测输出：端口与 token 都取到`() {
        val launch = DshWebLaunchParser.parse(realLine)
        assertNotNull(launch)
        assertEquals(42797, launch!!.port)
        assertEquals("8OMeQzE98nRyjCPBhqSu4_sVBhKFQdbq8mCP-9qf5u4", launch.token)
    }

    @Test
    fun `内核分配的高位端口不会被当成默认值`() {
        val launch = DshWebLaunchParser.parse("dsh web: http://127.0.0.1:65535/?token=x")
        assertEquals(65535, launch?.port)
    }

    @Test
    fun `行尾带 LAN 后缀时仍能解析`() {
        // DSH 在有局域网地址时会追加 " (LAN: http://…)"。
        val launch = DshWebLaunchParser.parse(
            "dsh web: http://127.0.0.1:39001/?token=abc (LAN: http://192.168.1.5:39001/?token=abc)",
        )
        assertEquals(39001, launch?.port)
        assertEquals("abc", launch?.token)
    }

    @Test
    fun `没有 token 时端口仍然有效`() {
        val launch = DshWebLaunchParser.parse("dsh web: http://127.0.0.1:39001/")
        assertEquals(39001, launch?.port)
        assertNull("没有 token 就是没有，不要编一个", launch?.token)
    }

    @Test
    fun `token 在 & 之前截断`() {
        val launch = DshWebLaunchParser.parse("dsh web: http://127.0.0.1:39001/?token=abc&foo=bar")
        assertEquals("abc", launch?.token)
    }

    @Test
    fun `token 之前还有别的查询参数也能取到`() {
        val launch = DshWebLaunchParser.parse("dsh web: http://127.0.0.1:39001/?a=1&token=abc&b=2")
        assertEquals("abc", launch?.token)
    }

    @Test
    fun `无关输出返回 null`() {
        assertNull(DshWebLaunchParser.parse("starting dsh proot"))
        assertNull(DshWebLaunchParser.parse(""))
        assertNull(DshWebLaunchParser.parse("dsh web: opening the default browser; pass --no-open to disable"))
    }

    @Test
    fun `端口 0 不是合法结果`() {
        // `--port 0` 是**请求**，内核绝不会绑 0；真打印 0 说明这条行不可信。
        assertNull(DshWebLaunchParser.parse("dsh web: http://127.0.0.1:0/?token=x"))
    }

    @Test
    fun `超出范围的端口被判为解析失败而不是夹到边界`() {
        assertNull(DshWebLaunchParser.parse("dsh web: http://127.0.0.1:65536/?token=x"))
        assertNull(DshWebLaunchParser.parse("dsh web: http://127.0.0.1:99999/?token=x"))
    }

    /**
     * 这条是本文件存在的主要理由。
     *
     * 解析失败的返回值必须是 `null`（= 上层继续等），**不能**是任何形式的
     * 「那就用默认端口吧」。上游把 3080 写死在常量里，等于让解析失败这件事
     * 无声无息地退化成「去探别人的端口」，并在此基础上报出「DSH 就绪」。
     */
    @Test
    fun `解析失败不返回任何默认端口`() {
        val garbage = listOf(
            "dsh web: http://127.0.0.1/?token=x",     // 缺端口
            "dsh web: http://:3080/?token=x",         // 缺主机
            "dsh web: http://127.0.0.1:abc/?token=x", // 端口非数字
            "web: http://127.0.0.1:3080/?token=x",    // 缺 dsh 标记
        )
        for (line in garbage) {
            assertNull("不应从 '$line' 里解析出端口", DshWebLaunchParser.parse(line))
        }
    }

    @Test
    fun `端口与 token 必须在同一行里一起取到`() {
        // 健康检查用端口定位、用 token 证明身份；两者分属两行就会产生
        // 「有端口没 token」的中间态，那种状态下无法核验身份。
        val launch = DshWebLaunchParser.parse(realLine)
        assertNotNull(launch?.port)
        assertNotNull(launch?.token)
    }
}
