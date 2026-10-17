package com.dshfusion.app.bridge.server;

import com.dshfusion.app.bridge.security.CapabilityGate.Tier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备能力端点表 —— 阶段 3.3 的子集里**最小的那一对**。
 *
 * <h2>为什么只有两条</h2>
 *
 * 阶段 3.3 §七 定了「只为 3.1 台账里的能力开端点」，并且把「排除」写在最前面。
 * 第一刀只开两条，因为它们的档位正好相反 —— 于是「档位门真的在工作」这件事
 * 才能在**同一份代码、同一轮测试**里被证明：
 *
 * <ul>
 *   <li>{@code /app/version} → T0：默认开 ⇒ 应当放行；</li>
 *   <li>{@code /app/ui/dump} → T2：默认关 ⇒ 应当拒绝，**且读屏函数一次都不许被调用**。</li>
 * </ul>
 *
 * <p>只开「都会放行」的两条，测不出门；只开「都会被拒」的两条，测不出放行路径。
 *
 * <h2>{@code /app/ui/dump} 为什么是 T2</h2>
 *
 * 它读的是**当前屏幕上的内容**。按 3.2 §二：无障碍不是「更大的权限」，
 * 它是「一个新的你」—— 能读到你正在看的每一屏。所以它默认关，
 * 而且**应用侧开还不够，系统里的无障碍服务也要开**。
 */
public final class DeviceEndpointTable {

    /** 端点路径 → 档位。**只增不删**；删掉一项等于悄悄关掉一条能力。 */
    private static final Map<String, Tier> ENDPOINTS;

    static {
        Map<String, Tier> map = new LinkedHashMap<>();
        // 元信息：不读设备状态以外的任何东西，拿到也不泄露用户数据。
        map.put("/app/version", Tier.T0_UNCONDITIONAL);
        // 读屏：默认关。见类注释。
        map.put("/app/ui/dump", Tier.T2_ACCESSIBILITY);
        ENDPOINTS = Collections.unmodifiableMap(map);
    }

    private DeviceEndpointTable() {}

    /** 该路径的档位；**未知路径返回 {@code null}**（调用方必须当成拒绝）。 */
    public static Tier tierFor(String path) {
        if (path == null) {
            return null;
        }
        // 去掉查询串：`/app/ui/dump?x=1` 与 `/app/ui/dump` 是同一个端点。
        int query = path.indexOf('?');
        String bare = query >= 0 ? path.substring(0, query) : path;
        return ENDPOINTS.get(bare);
    }

    /** 已开放的端点（只读）。 */
    public static java.util.Set<String> paths() {
        return ENDPOINTS.keySet();
    }
}
