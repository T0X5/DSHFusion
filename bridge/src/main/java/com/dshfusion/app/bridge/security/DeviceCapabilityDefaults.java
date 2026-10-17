package com.dshfusion.app.bridge.security;

/**
 * 设备能力层的**默认开关**。
 *
 * <h2>为什么把它做成一个可以被测试钉住的常量</h2>
 *
 * 「默认关」这个承诺最容易被一次「顺手改成 true」推翻，而那种改动的表现
 * 是**没有任何报错**——服务在用户不知情的时候开始监听端口。
 *
 * <p>所以在纯 Java 里留一个常量，并让测试钉住它的值。
 * 它挡不住所有情况（有人可以直接在调用点写 `true`），但它让
 * **「默认值被改掉」这件事必须显式地红一次测试**，而不是悄悄发生。
 *
 * <p>真正的边界仍在两处：套件的 5z 节（调用点必须只有一个、且不能写死 `true`），
 * 以及本常量（默认值必须是 {@code false}）。**两者都只是绊索，不是证明** ——
 * 证明要靠 3.5 在真机上验「关着时真的做不了」。
 */
public final class DeviceCapabilityDefaults {

    /**
     * 全新安装时设备能力层**不启用**。
     *
     * <p>改成 {@code true} 会让服务在用户从未同意的情况下开始监听回环端口 ——
     * 那与阶段 3.4 边界 1 直接冲突，也会让 5z 的语义变成一句空话。
     */
    public static final boolean ENABLED = false;

    private DeviceCapabilityDefaults() {}
}
