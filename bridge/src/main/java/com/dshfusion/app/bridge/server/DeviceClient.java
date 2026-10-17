package com.dshfusion.app.bridge.server;

/**
 * 真正去设备上做事的那一层。**只有它是 Android 相关的。**
 *
 * <p>把它抽成接口，是为了让「门禁 + 路由 + 响应形状」全部留在纯 Java 里 ——
 * 于是「关着时到底有没有碰到设备」可以在本机用一个**探针实现**
 * （被调用就抛异常）证死，而不是靠读代码相信它没调用。
 *
 * <p>实现方（Android）**不需要**再做授权判断：门禁已经在
 * {@link DeviceEndpointDispatcher} 里做完了。若实现方也判一次，就会出现
 * 「两处判断不一致」的可能，而那是最难查的一类。
 */
public interface DeviceClient {

    /** 桥协议版本 + App 版本（对应 {@code /app/version}）。 */
    String version();

    /** 当前页面结构（对应 {@code /app/ui/dump}）。 */
    String uiDump();
}
