package com.dshfusion.app.bridge.security;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * 「默认关」是一条**承诺**，所以它得有人看着。
 *
 * <p>这条断言看起来几乎是同义反复（断言一个常量等于 false），但它钉住的是一件真事：
 * **有人把它改成 true 的时候，CI 会红一次**。没有它，那次改动不会有任何症状 ——
 * 服务只是开始监听端口而已。
 */
public final class DeviceCapabilityDefaultsTest {

    @Test
    public void theCapabilityLayerMustStayOffByDefault() {
        assertFalse(
                "设备能力层的默认开关被改成了启用 —— 这会让服务在用户从未同意时监听端口。"
                        + "若确实要改，请同时更新阶段 3.4 边界 1 与套件 5z 节，并说明打开的条件是什么。",
                DeviceCapabilityDefaults.ENABLED);
    }
}
